package com.project.userservice.security.service;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.project.userservice.message.producer.SecurityAlertProducer;
import com.project.userservice.model.User;
import com.project.userservice.model.VerificationToken;
import com.project.userservice.repository.jpa.UserRepository;
import com.project.userservice.repository.jpa.VerificationTokenRepository;
import com.project.userservice.service.RefreshTokenService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 소셜 로그인을 <b>이미 있는 계정</b>에 연결한다(docs/68 §15).
 *
 * <h3>사전 탈취(pre-account hijacking)</h3>
 *
 * <p>🐛 예전: 프로바이더가 검증한 주소와 같은 주소의 계정이 있으면 그냥 연결했다. 그런데 그 계정의 비밀번호는 <b>누가 정했는지 모른다</b> —
 * 운영은 메일 인증 게이트가 꺼져 있어(require-email-verification=false) 아무 주소로나 가입하면 바로 쓸 수 있다.
 *
 * <ol>
 *   <li>공격자가 피해자의 주소 + 자기 비밀번호로 먼저 가입한다.</li>
 *   <li>나중에 피해자가 "구글로 로그인" — 같은 주소라 그 계정에 연결되고, 피해자는 자기 계정이라고 믿고 주소 · 결제 정보를 넣는다.</li>
 *   <li>공격자는 처음 정한 비밀번호로 언제든 같은 계정에 들어온다.</li>
 * </ol>
 *
 * <p>지금: 소셜 로그인은 그 주소의 <b>주인임을 증명</b>한다. 그래서 주소가 확인된 적 없는 비밀번호 계정({@code emailVerified=false})에
 * 연결할 때는 그 비밀번호를 <b>해제</b>(아무도 모르는 값)하고, 주소를 확인됨으로 바꾸고, 열려 있던 모든 로그인을 끊고, 주인에게 알린다.
 * 정상 사용자라면 한 번 알림을 받고, 비밀번호 로그인이 필요하면 "비밀번호 찾기" 로 새로 정한다 — 그 메일도 주인만 받는다.
 *
 * <p>막아 둔 계정(관리자 정지 · 탈퇴 — 비활성인데 기다리는 인증 토큰이 없다)은 건드리지 않는다. 소셜 로그인이 정지를 풀면 안 된다.
 * 인증 메일을 기다리던 계정(비활성 + 토큰 있음)은 소셜 로그인이 그 인증을 대신한다 — 활성화하고 토큰을 지운다.
 *
 * <h3>연결 알림</h3>
 *
 * <p>프로바이더가 이 계정에 <b>처음</b> 연결되면 알린다 — 본인이 한 일이 아니면 누군가 그 소셜 계정으로 들어오고 있다는 뜻이다.
 * "처음" 은 Redis 집합({@code oauth:linked:<userId>})으로 판단한다. Redis 가 비워지면 다음 연결에서 한 번 더 알릴 뿐이다(덜 알리는 쪽보다 낫다).
 */
// [JA] ソーシャルログインを既存アカウントに連結する。以前は同じアドレスなら無条件に連結し、誰が決めたか分からないパスワードが残った
//      (事前乗っ取り)。今はソーシャルログインがアドレスの所有を証明するので、確認されていないパスワードを解除し、確認済みにし、全ログインを切って知らせる。
//      停止 · 退会したアカウントは触らない。プロバイダーが初めて連結されたら知らせる。
@Slf4j
@Component
@RequiredArgsConstructor
public class SocialAccountLinker {

    static final String LINKED_KEY = "oauth:linked:";
    /** 비밀번호 가입의 signUpMethod — UserServiceImpl.registerUserAccount 와 같은 값. */
    static final String PASSWORD_SIGNUP = "email";

    private final UserRepository userRepository;
    private final VerificationTokenRepository verificationTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final SecurityAlertProducer securityAlertProducer;
    private final StringRedisTemplate redis;

    @Transactional
    public User link(User user, String provider) {
        Long userId = user.getUserId();

        if (!user.isEmailVerified()) {
            if (PASSWORD_SIGNUP.equals(user.getSignUpMethod())) {
                VerificationToken pending = verificationTokenRepository.findByUser(user);
                if (!user.isEnabled() && pending == null) {
                    // 관리자 정지 · 탈퇴 — 소셜 로그인이 풀어 주지 않는다.
                    log.info("소셜 연결 — 막힌 계정이라 건드리지 않는다. provider={}, userId={}", provider, userId);
                    return user;
                }
                clearUnprovenPassword(user, pending, provider);
                rememberLinked(userId, provider);
                return user;
            }
            // 소셜로 만든 계정 — 프로바이더가 이미 주소를 검증했다. 표시만 맞춘다.
            user.setEmailVerified(true);
            userRepository.save(user);
        }

        if (rememberLinked(userId, provider) && !provider.equals(user.getSignUpMethod())) {
            securityAlertProducer.publishAccountEvent(userId, user.getEmail(),
                    SecurityAlertProducer.ALERT_SOCIAL_LINKED, provider);
        }
        return user;
    }

    private void clearUnprovenPassword(User user, VerificationToken pending, String provider) {
        Long userId = user.getUserId();
        // 아무도 모르는 값 — null 로 두면 BCrypt matches 가 예외를 던지는 애매한 상태가 된다(소셜 가입과 같은 방식).
        user.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setPasswordChangedAt(LocalDateTime.now());
        user.setEmailVerified(true);
        if (!user.isEnabled() && pending != null) {
            user.setEnabled(true);
        }
        userRepository.save(user);
        if (pending != null) {
            verificationTokenRepository.delete(pending);
        }
        // 그 비밀번호로 열려 있던 로그인 · 채팅 · 알림 연결을 모두 닫는다. 이 소셜 로그인의 새 로그인은 이 뒤에 발급된다(OAuth2SuccessHandler).
        int revoked = refreshTokenService.revokeAll(userId);
        securityAlertProducer.publishAccountEvent(userId, user.getEmail(),
                SecurityAlertProducer.ALERT_UNPROVEN_PASSWORD_CLEARED, provider);
        log.warn("소셜 연결 — 주소가 확인된 적 없는 비밀번호를 해제했다(사전 탈취 방어). provider={}, userId={}, 끊은 로그인={}",
                provider, userId, revoked);
    }

    /** @return 이 프로바이더가 이 계정에 처음 연결됐으면 true */
    private boolean rememberLinked(Long userId, String provider) {
        try {
            Long added = redis.opsForSet().add(LINKED_KEY + userId, provider);
            return added != null && added > 0;
        } catch (RuntimeException e) {
            // Redis 가 안 되면 알림만 빠진다 — 로그인을 막을 이유는 아니다.
            log.warn("소셜 연결 기록 실패: userId={} ({})", userId, e.getClass().getSimpleName());
            return false;
        }
    }
}
