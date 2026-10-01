package com.project.userservice.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.constants.StatusMessages;
import com.project.common.message.dto.request.UserCreatedRequest;
import com.project.userservice.constants.AppRole;
import com.project.userservice.dto.AddressDto;
import com.project.userservice.dto.UserProfileDto;
import com.project.userservice.model.PasswordResetToken;
import com.project.userservice.model.Role;
import com.project.userservice.model.ShippingAddress;
import com.project.userservice.model.User;
import com.project.userservice.model.VerificationToken;
import com.project.userservice.repository.jpa.PasswordResetTokenRepository;
import com.project.userservice.repository.jpa.RoleRepository;
import com.project.userservice.repository.jpa.UserRepository;
import com.project.userservice.repository.jpa.VerificationTokenRepository;
import com.project.userservice.security.request.SignupRequest;
import com.project.userservice.service.AddressService;
import com.project.userservice.service.EmailService;
import com.project.userservice.service.TotpService;
import com.project.userservice.service.UserService;
import com.project.userservice.security.jwt.JwtUtils;
import com.project.userservice.security.response.LoginResponse;
import com.project.userservice.security.service.UserDetailsImpl;
import com.project.userservice.security.service.UserDetailsServiceImpl;
import com.project.userservice.security.util.VerificationResult;
import com.warrenstrange.googleauth.GoogleAuthenticatorKey;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.project.userservice.message.model.OutboxEvent;
import com.project.userservice.message.producer.UserCreatedProducer;
import com.project.userservice.message.repository.jpa.OutboxEventRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Transactional
@Service
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class UserServiceImpl implements UserService {

    @Value("${frontend.url}")
    String frontendUrl;

    private final JwtUtils jwtUtils;

    private final AuthenticationManager authenticationManager;
    
    private final UserDetailsServiceImpl userDetailsService;

    private final PasswordEncoder passwordEncoder;

    private final SessionRegistry sessionRegistry;

    private final UserRepository userRepository;

    private final RoleRepository roleRepository;

    private final VerificationTokenRepository tokenRepository;

    private final PasswordResetTokenRepository passwordResetTokenRepository;

    private final EmailService emailService;

    /**
     * 로그인 세션 기록(Redis). 비밀번호를 바꾸면 <b>여기 남은 옛 세션도 지운다.</b>
     *
     * <p>지우지 않으면 다음 로그인에서 "다중 로그인" 경고가 뜬다 — 혼자 쓰는데도.
     * 실제로 비밀번호를 재설정하고 로그인한 사용자가 그 경고를 봤다.
     */
    private final com.project.userservice.security.RedisSessionManager sessionManager;

    /** 로그인 기기 목록·삭제. 판단 로직은 그쪽이 갖고 있다. */
    private final com.project.userservice.service.DeviceRecognitionService deviceRecognitionService;
    /** 등급 기준(기간·금액). 화면이 "왜 이 등급인가" 를 설명하려면 기준이 필요하다. */
    private final com.project.userservice.config.TierProperties tierProperties;

    private final TotpService totpService;

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    private final com.project.userservice.message.UserOutboxHelper userOutboxHelper;

    /** 관리자가 계정을 막을 때 이미 열린 로그인도 끊는다(docs/68 §13). */
    private final com.project.userservice.service.RefreshTokenService refreshTokenService;

    
    /**
     * 확인 토큰 유효 기간. 기본 24시간.
     *
     * <p>예전에는 엔티티 상수 {@code EXPIRATION = 3}(분)이 박혀 있었다. 옆에 {@code //60 * 24}
     * 주석이 남아 있는 걸 보면 테스트하려고 줄인 값을 되돌리지 않은 것으로 보인다. 메일이
     * 배달되는 데 걸리는 시간만으로도 3분을 넘기는 경우가 흔해, 사실상 상당수 가입자가
     * 링크를 눌러도 만료였다.
     */
    @Value("${app.verification.token-ttl:PT24H}")
    private java.time.Duration verificationTokenTtl;

    /**
     * 가입 직후 계정을 비활성으로 둘지 여부.
     *
     * <p>⚠️ 기본값 false 는 안전해서가 아니라, <b>메일이 실제로 나가지 않는 환경에서 켜면
     * 아무도 로그인할 수 없기 때문</b>이다(notification-service 의 기본 발송기는 logging).
     * 메일 발송을 확인한 뒤 {@code APP_REQUIRE_EMAIL_VERIFICATION=true} 로 켠다.
     * 자세한 순서는 application-local.yml 의 주석 참조.
     */
    @Value("${app.verification.require-email-verification:false}")
    private boolean requireEmailVerification;

    public static final String TOKEN_INVALID = "invalidToken";
    public static final String TOKEN_EXPIRED = "expired";
    public static final String TOKEN_VALID = "valid";

    @Override
    public void updateUserRole(Long userId, String roleName) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException(StatusMessages.USER_NOT_FOUND));
        AppRole appRole = AppRole.valueOf(roleName);
        Role role = roleRepository.findByRoleName(appRole)
                .orElseThrow(() -> new RuntimeException("Role not found"));
        user.setRole(role);
        userRepository.save(user);
    }

    @Override
    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserProfileDto> getAllUserProfiles() {
        return userRepository.findAll().stream().map(u -> UserProfileDto.toDto(u, rolesOf(u))).toList();
    }

    @Override
    public UserProfileDto getUserProfileById(Long id) {

        User user = userRepository.findById(id).orElseThrow();

        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getEmail());

        List<String> roles = userDetails.getAuthorities().stream()
        .map(item -> item.getAuthority())
        .collect(Collectors.toList());

        return UserProfileDto.toDto(user, roles);
    }

    // ── 내 프로필 ────────────────────────────────────────────────────────────
    //
    // 🐛 지금까지 **프로필을 돌려주는 GET 이 없었다.** 그래서 화면은 JWT 페이로드에서
    //    사용자 객체를 만들어 썼는데(util/jwtUser.js 의 toUser), 거기에는
    //    { email, userId, roles } 뿐이다. 화면들이 user.name · user.image 를 읽으면
    //    언제나 undefined 였고, 프로필 사이드바는 이름이 빈 칸에 아바타가 깨진 채로
    //    나왔다. `/profile` 본문은 아예 만들어진 적이 없다.
    //
    //    JWT 는 **신원 증명**이지 프로필 저장소가 아니다. 이름을 바꿔도 이미 발급된
    //    토큰은 그대로이므로, 프로필은 그때그때 서버에서 읽어야 한다.

    /**
     * 내가 로그인한 적 있는 기기들.
     *
     * <p>보안 알림을 받은 사용자가 확인하러 오는 화면이다. 토큰 해시는 내보내지 않는다 —
     * 그 값을 알면 그 기기인 척해서 <b>알림을 끌 수 있다</b>(KnownDevice 주석).
     */
    @Transactional(readOnly = true)
    public java.util.List<com.project.userservice.dto.DeviceDto> myDevices(String email) {
        User user = findByUsername(email);
        if (user == null) {
            throw new RuntimeException("사용자를 찾을 수 없습니다.");
        }
        return deviceRecognitionService.devicesOf(user.getUserId()).stream()
                .map(com.project.userservice.dto.DeviceDto::of)
                .toList();
    }

    /**
     * 내 등급 — 저장된 값을 읽기만 한다.
     *
     * <p>🔴 <b>여기서 계산하지 않는다.</b> 등급은 하루 한 번 배치가 채운다
     * ({@link com.project.userservice.service.CustomerTierService}). 화면을 열 때마다
     * order-service 를 부르면 주문 서비스 장애가 프로필 화면 장애로 번지고, 같은 사람이
     * 새로고침할 때마다 등급이 달라 보일 수 있다.
     *
     * <p>기준 금액을 함께 내려보내는 이유는 화면이 <b>"왜 내가 실버인가"</b> 에 답해야
     * 하기 때문이다. 기준을 클라이언트에 하드코딩하면 설정을 바꾼 날부터 화면이 거짓말을
     * 한다.
     */
    @Transactional(readOnly = true)
    public com.project.userservice.dto.TierStatusDto myTier(String email) {
        User user = findByUsername(email);
        if (user == null) {
            throw new RuntimeException("사용자를 찾을 수 없습니다.");
        }

        com.project.common.constants.event.CustomerTier tier = user.tierOrBasic();

        return com.project.userservice.dto.TierStatusDto.builder()
                .tier(tier.name())
                .displayName(tier.displayName())
                .computedAt(user.getTierComputedAt())
                .recentPaidAmount(user.getRecentPaidAmount())
                .lifetimeOrderCount(user.getLifetimeOrderCount())
                .months(tierProperties.getMonths())
                .silverAmount(tierProperties.getSilverAmount())
                .goldAmount(tierProperties.getGoldAmount())
                .vipAmount(tierProperties.getVipAmount())
                .build();
    }

    /** 기기 기억을 지운다. 세션은 끊지 않는다(컨트롤러 주석). */
    @Transactional
    public boolean forgetDevice(String email, Long deviceId) {
        User user = findByUsername(email);
        if (user == null) {
            return false;
        }
        return deviceRecognitionService.forget(user.getUserId(), deviceId);
    }

    /** 이메일(검증된 신원)로 내 프로필. */
    @Transactional(readOnly = true)
    public UserProfileDto getMyProfile(String email) {
        User user = findByUsername(email);
        if (user == null) {
            throw new RuntimeException("사용자를 찾을 수 없습니다.");
        }
        return UserProfileDto.toDto(user, rolesOf(user));
    }

    /**
     * 표시 이름을 바꾼다.
     *
     * <p>{@code username}(로그인 id = 이메일)과 {@code email} 은 <b>바꾸지 않는다.</b>
     * 둘은 신원 그 자체이고, JWT 의 subject 이며 다른 서비스가 사용자를 찾는 키다
     * (order·cart·coupon 이 전부 이메일로 조회한다). 여기서 바꾸면 이미 발급된 토큰과
     * 다른 서비스의 참조가 한꺼번에 어긋난다. 표시 이름({@code name})만 바꾼다.
     */
    @Transactional
    public UserProfileDto updateMyProfile(String email, String newName) {
        User user = findByUsername(email);
        if (user == null) {
            throw new RuntimeException("사용자를 찾을 수 없습니다.");
        }

        String trimmed = newName == null ? "" : newName.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("이름을 입력해 주세요.");
        }
        if (trimmed.length() > 50) {
            throw new IllegalArgumentException("이름은 50자까지 입력할 수 있습니다.");
        }

        user.setName(trimmed);
        userRepository.save(user);
        // 복제본(chat_member)이 새 이름을 따라오게 — 같은 트랜잭션의 아웃박스(docs/48 §6-⑤).
        userOutboxHelper.recordUserUpdated(user);

        return UserProfileDto.toDto(user, rolesOf(user));
    }

    /** 프로필 이미지 주소를 바꾼다. null 이면 지운다(기본 아바타로 돌아간다). */
    @Transactional
    public UserProfileDto updateMyProfileImage(String email, String imageUrl) {
        User user = findByUsername(email);
        if (user == null) {
            throw new RuntimeException("사용자를 찾을 수 없습니다.");
        }
        user.setImage(imageUrl);
        userRepository.save(user);
        userOutboxHelper.recordUserUpdated(user);

        return UserProfileDto.toDto(user, rolesOf(user));
    }

    private List<String> rolesOf(User user) {
        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getEmail());
        return userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());
    }

    @Override
    public LoginResponse getLoginInfoByEmail(String email) {
        
        UserDetails userDetails = userDetailsService.loadUserByUsername(email);

        List<String> roles = userDetails.getAuthorities().stream()
        .map(item -> item.getAuthority())
        .collect(Collectors.toList());

        // 토큰 갱신 경로다 — 사용자에게 알릴 것이 없으므로 문구도 코드도 없다.
        return new LoginResponse(email, roles, null, null);
    }
    
    

    @Override
    public User findByUsername(String username) {
        Optional<User> user = userRepository.findByUsername(username);
        return user.orElse(null);
    }

    @Override
    public void updateAccountLockStatus(Long userId, boolean lock) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException(StatusMessages.USER_NOT_FOUND));
        user.setAccountNonLocked(!lock);
        userRepository.save(user);
        if (lock) {
            endSessions(userId, "잠금");
        }
    }

    /**
     * 🐛 관리자가 계정을 정지 · 잠금 · 만료해도 <b>DB 값만 바뀌었다</b> — 이미 로그인한 사용자는 리프레시 토큰으로 계속 갱신해 최대 30일
     * 동안 주문 · 채팅을 계속했다(막히는 것은 새 로그인뿐). 막는 순간 모든 로그인 묶음을 끊고 채팅 · 알림 연결도 닫는다(revokeAll).
     * 갱신 경로도 계정 상태를 다시 본다(RefreshTokenController) — 이것이 실패해도 다음 갱신에서 끊긴다.
     * 로그인 5회 실패의 자동 잠금은 다른 필드(lockExpiresAt)라 여기에 오지 않는다 — 공격자가 남의 로그인을 끊는 데 쓸 수 없다.
     */
    // [JA] 管理者がアカウントを停止 · ロック · 期限切れにしても DB の値だけが変わり、ログイン済みのユーザーは最大 30 日使い続けた。
    //      止めた瞬間に全ファミリーを切り、チャット · 通知の接続も閉じる。自動ロック(5 回失敗)は別フィールドなのでここには来ない。
    private void endSessions(Long userId, String why) {
        int revoked = refreshTokenService.revokeAll(userId);
        log.info("관리자 {} — 로그인 {}개를 끊었다. userId={}", why, revoked, userId);
    }

    @Override
    public List<Role> getAllRoles() {
        return roleRepository.findAll();
    }

    @Override
    public void updateAccountExpiryStatus(Long userId, boolean expire) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException(StatusMessages.USER_NOT_FOUND));
        user.setAccountNonExpired(!expire);
        userRepository.save(user);
        if (expire) {
            endSessions(userId, "계정 만료");
        }
    }

    @Override
    public void updateAccountEnabledStatus(Long userId, boolean enabled) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException(StatusMessages.USER_NOT_FOUND));
        user.setEnabled(enabled);
        userRepository.save(user);
        if (!enabled) {
            endSessions(userId, "정지");
        }
    }

    @Override
    public void updateCredentialsExpiryStatus(Long userId, boolean expire) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException(StatusMessages.USER_NOT_FOUND));
        user.setCredentialsNonExpired(!expire);
        userRepository.save(user);
        if (expire) {
            endSessions(userId, "자격 증명 만료");
        }
    }

    /**
     * 로그인 상태에서 비밀번호를 바꾼다.
     *
     * <p>🐛 <b>현재 비밀번호가 틀려도 성공으로 보고했다.</b> 예전 코드는 이랬다:
     *
     * <pre>
     * if (passwordEncoder.matches(currPassword, user.getPassword())) {
     *     user.setPassword(...);  userRepository.save(user);
     * }
     * // else 가 없다 — 그냥 빠져나간다
     * </pre>
     *
     * <p>메서드가 void 라 컨트롤러는 그대로 {@code 200 "Password updated"} 를 돌려줬다.
     * 사용자는 비밀번호가 바뀐 줄 알고 다음 로그인에서 새 비밀번호를 넣다 실패한다 —
     * 그때는 이미 무엇이 잘못됐는지 알 수 없다.
     *
     * <p>{@code catch (Exception e) -> RuntimeException("Failed to update password")} 도
     * 함께 없앴다. 그게 있으면 아래에서 던지는 구체적인 사유까지 뭉뚱그려진다.
     */
    @Override
    @Transactional
    public void updatePassword(Long userId, String currPassword, String password) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException(StatusMessages.USER_NOT_FOUND));

        if (!passwordEncoder.matches(currPassword, user.getPassword())) {
            throw new InvalidCurrentPasswordException();
        }

        applyNewPassword(user, password);
        log.info("비밀번호 변경: userId={}", userId);
    }

    /** 현재 비밀번호가 맞지 않는다. 컨트롤러가 400 으로 바꾼다. */
    public static class InvalidCurrentPasswordException extends RuntimeException {
        public InvalidCurrentPasswordException() {
            super("현재 비밀번호가 올바르지 않습니다.");
        }
    }

    /**
     * 새 비밀번호를 적용하고 <b>기존 세션을 끊는다.</b>
     *
     * <p>세션을 끊지 않으면, 계정이 털려서 비밀번호를 바꾼 경우에도 공격자는 로그인 상태로
     * 남는다 — 비밀번호를 바꾼 의미가 사라진다.
     *
     * <p>{@code passwordChangedAt} 보다 <b>먼저 발급된</b> 리프레시 토큰이 거절되는 방식이다
     * ({@code RefreshTokenController}). 액세스 토큰은 서명만으로 검증되므로 남은 수명
     * (기본 15분) 동안은 살아 있다 — 그 뒤 갱신이 막혀 로그아웃된다. 완전한 즉시 차단은
     * 모든 요청마다 DB 를 보는 구조라야 가능하고, 그 비용은 이 서비스에 맞지 않는다.
     */
    private void applyNewPassword(User user, String rawPassword) {
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setPasswordChangedAt(java.time.LocalDateTime.now());
        userRepository.save(user);

        // 옛 세션 기록을 지운다. passwordChangedAt 이 그 세션을 이미 <b>쓸 수 없게</b>
        // 만들지만, 기록이 남아 있으면 다음 로그인이 "다중 로그인" 으로 보인다 —
        // 죽은 세션을 살아 있다고 말하는 셈이다.
        sessionManager.removeSession(user.getEmail());
    }

    @Override
    public void updatePassword(Long userId, String password) {
        try {
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new RuntimeException(StatusMessages.USER_NOT_FOUND));

            user.setPassword(passwordEncoder.encode(password));
            userRepository.save(user);
        } catch (Exception e) {
            throw new RuntimeException("Failed to update password");
        }
    }

    /**
     * 비밀번호 재설정 링크를 보낸다.
     *
     * <p>🔒 <b>가입되지 않은 주소에도 조용히 성공한다.</b> 예전에는
     * {@code orElseThrow(USER_NOT_FOUND)} 였고, 컨트롤러가 그걸 500 으로 바꿨다 —
     * 미가입 주소는 500, 가입된 주소는 200. <b>응답 코드만 보면 그 주소의 가입 여부를 알 수
     * 있었다.</b> 같은 저장소의 {@code resend-verification} 은 이 누설을 막으려고 모든 경우에
     * 같은 응답을 주는데, 정작 비밀번호 찾기가 뚫려 있었다.
     *
     * <p>여기서 조용히 끝내는 것이 유일하게 맞는 선택이다 — 오타를 알려주는 편의보다
     * "이 주소가 이 서비스에 가입돼 있다"가 훨씬 값진 정보이고, 그 목록은 피싱의 재료가 된다.
     */
    @Override
    @Transactional
    public void generatePasswordResetToken(String email) {
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            // 로그에만 남긴다. 호출자는 성공과 구분할 수 없어야 한다.
            log.debug("비밀번호 재설정 요청이지만 가입되지 않은 주소다");
            return;
        }

        String token = UUID.randomUUID().toString();
        Instant expiryDate = Instant.now().plus(24, ChronoUnit.HOURS);

        // 🔒 같은 행을 재사용한다 — 사용자당 살아 있는 링크가 하나여야 한다. 예전에는
        //    요청할 때마다 새 행을 넣고 이전 것을 그대로 뒀다. 열 번 누르면 24시간짜리
        //    유효 링크가 열 개 살아 있었고, 메일함이 털렸을 때 고를 수 있는 링크가 그만큼
        //    늘어난다. (V10 이 user_id 에 UNIQUE 를 걸어 두 번째 행 자체를 막는다.)
        PasswordResetToken resetToken = passwordResetTokenRepository.findByUser(user)
                .orElseGet(() -> new PasswordResetToken(token, expiryDate, user));
        resetToken.refresh(token, expiryDate);
        passwordResetTokenRepository.save(resetToken);

        String resetUrl = frontendUrl + "/reset-password?token=" + token;
        // 메일은 여기서 보내지 않고 요청만 한다 — 발송·재시도는 notification-service 가 맡는다.
        // 발행이 실패해도 예외가 올라오지 않으므로 이 트랜잭션(토큰 저장)은 그대로 커밋된다.
        // ⚠️ resetUrl 은 자격증명이다 — 로그에 남기지 않는다.
        emailService.sendPasswordResetEmail(user.getUserId(), user.getEmail(), resetUrl);
        log.info("비밀번호 재설정 메일 요청: userId={}", user.getUserId());
    }

    /**
     * 재설정 링크로 비밀번호를 바꾼다.
     *
     * <p>🔒 조회는 <b>해시로</b> 한다. 저장된 값이 해시이므로 원문을 그대로 찾으면 아무것도
     * 걸리지 않는다 — 해싱을 넣으면서 이 줄을 빠뜨리면 재설정이 통째로 죽는다.
     *
     * <p>바꾼 뒤 토큰을 소진 처리하고, {@code passwordChangedAt} 으로 기존 세션을 끊는다.
     * 비밀번호를 되찾는 사람의 절반은 <b>계정을 도둑맞아서</b> 오는데, 그때 공격자의 세션이
     * 살아 있으면 되찾은 것이 아니다.
     */
    @Override
    @Transactional
    public void resetPassword(String token, String newPassword) {
        PasswordResetToken resetToken = passwordResetTokenRepository
                .findByToken(PasswordResetToken.hash(token))
                .orElseThrow(() -> new RuntimeException("유효하지 않은 링크입니다. 비밀번호 찾기를 다시 요청해 주세요."));

        if (resetToken.isUsed())
            throw new RuntimeException("이미 사용된 링크입니다. 비밀번호 찾기를 다시 요청해 주세요.");

        if (resetToken.getExpiryDate().isBefore(Instant.now()))
            throw new RuntimeException("링크의 유효기간이 지났습니다. 비밀번호 찾기를 다시 요청해 주세요.");

        User user = resetToken.getUser();
        applyNewPassword(user, newPassword);

        resetToken.setUsed(true);
        passwordResetTokenRepository.save(resetToken);
        log.info("비밀번호 재설정 완료: userId={}", user.getUserId());
    }

    @Override
    @Transactional(readOnly = true)
    public User findByEmail(String email) {
        //return userRepository.findByEmail(email).
        return userRepository.findByEmailWithRoles(email).        
        orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public User findById(Long userId) {
        return userRepository.findById(userId).
        orElse(null);
    }

    @Override
    public User registerOAuthUser(User user) {
        if (user.getPassword() != null)
            user.setPassword(passwordEncoder.encode(user.getPassword()));
        return userRepository.save(user);
    }

    @Override
    //@Transactional(readOnly = true)
    public User getUser(final String verificationToken) {
        final VerificationToken token = tokenRepository.findByToken(verificationToken);
        if (token != null) {
            return token.getUser();
        }
        return null;
    }

    @Override    
    public User registerNewUserAccount(SignupRequest request) {

        // Create new user's account
        User user = new User(request.getUsername(),
                request.getEmail(),
                passwordEncoder.encode(request.getPassword()));

        Set<String> strRoles = request.getRole();
        Role role;

        // ⚠️ 회원가입으로 받는 역할은 **언제나 ROLE_USER 다.**
        //
        // 예전에는 요청 본문의 role 이 "admin" 이면 그대로 ROLE_ADMIN 을 줬다. 그런데 이
        // 엔드포인트는 /api/auth/public/register — **무인증 공개 경로**다. 즉 누구나
        //
        //     POST /user-service/api/auth/public/register
        //     { "username": "...", "email": "...", "password": "...", "role": ["admin"] }
        //
        // 한 번으로 관리자가 될 수 있었다. 카탈로그 쓰기를 ROLE_ADMIN 으로 잠가 둔 것이
        // 통째로 무의미해지는 구멍이다(docs/34).
        //
        // 관리자 승격은 DB 에서 사람이 한다(docs/34 의 UPDATE 문). 승격 API 를 만든다면
        // 그때는 반드시 인증된 관리자만 호출할 수 있는 별도 경로여야 한다.
        if (strRoles != null && !strRoles.isEmpty()) {
            log.warn("회원가입 요청이 역할을 지정했다 — 무시하고 ROLE_USER 로 만든다. email={}",
                    request.getEmail());
        }
        role = roleRepository.findByRoleName(AppRole.ROLE_USER)
                .orElseThrow(() -> new IllegalStateException(
                        "users.roles 에 ROLE_USER 가 없습니다. 마이그레이션 V3__seed_roles.sql 이"
                                + " 적용됐는지 확인하세요 — 이 행이 없으면 회원가입이 아무도 되지 않습니다."));

        // ⚠️ 이 블록은 예전에 `strRoles` 가 비어 있지 않을 때만 실행됐다. 그런데 프론트엔드는
        //    role 을 보내지 않으므로 **정상 가입 경로에서는 통째로 건너뛰었다.** 그 결과
        //    만료일과 가입 방식이 비어 있는 사용자가 만들어졌다(불리언 넷은 엔티티 기본값이
        //    true 라 우연히 문제가 안 났을 뿐이다). 역할과 아무 상관 없는 계정 초기화라
        //    조건 없이 실행한다.
        user.setAccountNonLocked(true);
        user.setAccountNonExpired(true);
        user.setCredentialsNonExpired(true);
        user.setCredentialsExpiryDate(LocalDate.now().plusYears(1));
        user.setAccountExpiryDate(LocalDate.now().plusYears(1));
        user.setTwoFactorEnabled(false);
        user.setSignUpMethod("email");

        // ⚠️ User 엔티티의 enabled 기본값은 true 다. 그래서 아무도 이 값을 건드리지 않던
        //    동안 **메일 인증이 아무것도 막지 않았다** — 확인 링크를 누르지 않아도 로그인이
        //    됐고, 남의 이메일 주소로 가입해도 그대로 쓸 수 있었다. 인증 확인 코드
        //    (verifyRegistration → setEnabled(true))는 이미 있었지만 통과 전에도 이미
        //    true 라 의미가 없었다.
        //
        //    끄고 켜는 스위치로 둔 이유는 메일 발송 여부에 의존하기 때문이다. 자세한
        //    내용은 필드 선언부와 application-local.yml 주석 참조.
        user.setEnabled(!requireEmailVerification);
        if (requireEmailVerification) {
            log.info("가입 계정을 비활성으로 만든다 — 확인 메일의 링크로 활성화된다. email={}",
                    request.getEmail());
        }

        user.setRole(role);

        User saved = userRepository.save(user);

        // 🔴 가입 확정 이벤트를 **여기서** 적는다 — 같은 트랜잭션이다(docs/48 ②·④).
        //
        //    처음에는 이 줄을 validateAndGetUser(확인 링크를 누른 순간)에 뒀다. 그런데
        //    `require-email-verification` 기본값이 **false** 라 그 경로를 아무도 지나지
        //    않는다 — 계정은 곧바로 enabled 로 만들어지고 확인 링크를 누를 일이 없다.
        //    그래서 **user-created 이벤트가 단 한 건도 발행된 적이 없었다.**
        //
        //    증상 셋이 전부 여기서 나왔다: order DB 에 주문자 행이 없어 주문 실패(docs/47),
        //    chat_member 가 없어 채팅 진입 실패, 그리고 **가입 축하 쿠폰 미발급.**
        //    앞의 둘은 읽기 시점에 알아차릴 수 있어 눈에 띄었지만, 쿠폰은 "안 온다"는
        //    사실조차 알기 어려웠다.
        //
        //    가입 시점으로 옮기면 인증 설정과 무관하게 언제나 발행된다. 인증을 켠 환경
        //    에서는 미인증 계정에도 행이 생기는데, 그 계정은 로그인 자체가 안 되므로
        //    해가 없다 — 반대로 "계정은 있는데 하위 서비스에는 없는" 상태가 훨씬 나쁘다.
        //
        //    ⚠️ 나중에 인증 여부를 하위 서비스가 알아야 하면 UserCreatedRequest 에
        //       emailVerified 를 더한다. 이벤트 스키마(Avro 매핑) + 소비자 셋을 함께
        //       고쳐야 하므로 지금은 하지 않았다(docs/48 ④).
        userOutboxHelper.recordUserCreated(saved);

        return saved;
    }

    @Override
    public GoogleAuthenticatorKey generate2FASecret(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException(StatusMessages.USER_NOT_FOUND));
        GoogleAuthenticatorKey key = totpService.generateSecret();
        user.setTwoFactorSecret(key.getKey());
        userRepository.save(user);
        return key;
    }

    @Override
    public boolean validate2FACode(Long userId, int code) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException(StatusMessages.USER_NOT_FOUND));
        return totpService.verifyCode(user.getTwoFactorSecret(), code);
    }

    @Override
    public void enable2FA(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException(StatusMessages.USER_NOT_FOUND));
        user.setTwoFactorEnabled(true);
        userRepository.save(user);
    }

    @Override
    public void disable2FA(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException(StatusMessages.USER_NOT_FOUND));
        user.setTwoFactorEnabled(false);
        userRepository.save(user);
    }

    /**
     * 사용자와 그에 딸린 토큰들을 지운다.
     *
     * <p>🐛 예전 구현은 <b>거의 항상 예외로 끝났다.</b> 비밀번호 재설정 토큰을
     * {@code orElseThrow(() -> new RuntimeException("Invalid password reset token"))} 로
     * 꺼냈는데, 비밀번호 재설정을 한 번도 요청하지 않은 사용자에게는 그 토큰이 <b>없는 것이
     * 정상</b>이다. 즉 대다수의 사용자에 대해 이 메서드는 삭제를 하지 못하고 예외를 던졌다.
     * 바로 아래 {@code if (passwordToken != null)} 은 orElseThrow 를 통과한 시점에 이미
     * null 일 수 없으므로 죽은 코드였고, 그 사실이 의도(“없으면 건너뛴다”)를 드러낸다.
     */
    @Override
    @Transactional
    public void deleteUser(User user) {

        if (user == null) {
            return;
        }

        final VerificationToken verificationToken = tokenRepository.findByUser(user);
        if (verificationToken != null) {
            tokenRepository.delete(verificationToken);
        }

        // 없는 것이 정상이다 — 있으면 지우고 없으면 넘어간다.
        passwordResetTokenRepository.findByUser(user)
                .ifPresent(passwordResetTokenRepository::delete);

        userRepository.delete(user);
    }

    /**
     * 확인 토큰을 만든다. <b>사용자당 한 행만</b> 유지한다.
     *
     * <p>예전에는 재발송할 때마다 새 행을 넣어 한 사용자에게 유효한 토큰이 여러 개 생겼다.
     * 옛 링크도 계속 살아 있으므로 메일함이 노출됐을 때 위험 창이 불필요하게 넓어진다.
     */
    @Override
    @Transactional
    public void createVerificationTokenForUser(User user, String rawToken) {
        final VerificationToken existing = tokenRepository.findByUser(user);

        if (existing != null) {
            existing.refresh(rawToken, verificationTokenTtl);
            tokenRepository.save(existing);
            return;
        }

        tokenRepository.save(new VerificationToken(rawToken, user, verificationTokenTtl));
    }

    /**
     * 재발송용 토큰을 새로 만들고 <b>원문</b>을 돌려준다.
     *
     * <p>DB 에는 해시만 남으므로 여기서 원문을 반환하지 않으면 메일에 실을 값을 다시 얻을
     * 방법이 없다. 호출부는 이 값을 메일로만 보내고 로그에 남기지 않아야 한다.
     *
     * <p>예전의 {@code generateNewVerificationToken(String oldToken)} 을 대체한다. 그쪽은
     * "옛 토큰 문자열"을 인자로 요구했는데, <b>재발송을 요청하는 사용자는 그 값을 모른다</b>
     * — 메일이 오지 않아서 재발송하는 것이기 때문이다. 그래서 아무도 호출하지 않는 죽은
     * 코드였다.
     */
    @Override
    @Transactional
    public String regenerateVerificationToken(final User user) {
        final String rawToken = UUID.randomUUID().toString();
        createVerificationTokenForUser(user, rawToken);
        return rawToken;
    }

    // 삭제: validateVerificationToken(String)
    //
    // validateAndGetUser 와 같은 일을 하면서 규칙이 달랐다(만료 토큰을 지우고, 성공해도
    // 토큰을 남기고, 이미 인증된 계정을 다루지 않았다). 살아 있는 호출부는 없었고 주석
    // 처리된 옛 코드만 참조하고 있었다. 같은 판정을 두 곳에 두면 한쪽만 고치게 된다.

    @Override
    public List<String> getUsersFromSessionRegistry() {
        return sessionRegistry.getAllPrincipals()
                .stream()
                .filter((u) -> !sessionRegistry.getAllSessions(u, false)
                        .isEmpty())
                .map(o -> {
                    if (o instanceof User) {
                        return ((User) o).getEmail();
                    } else {
                        return o.toString();
                    }
                }).collect(Collectors.toList());
    }

    public UserProfileDto findUserWithAddresses(User user) {

        List<AddressDto> dtos = new ArrayList<>();
        for (ShippingAddress src : user.getShippingAddresses()) {
            AddressDto address = new AddressDto();
            AddressDto.deepCopyShippingAddressDto(address, src);
            address.setId(Long.toString(src.getShippingAddressId()));
            dtos.add(address);
        }

        UserProfileDto result = UserProfileDto.builder()
                .username(user.getUsername())
                .email(user.getEmail())
                .image(user.getImage())
                .addresses(dtos)
                .build();

        return result;

    }

    
    @Transactional(readOnly = true)
    public UserProfileDto findUserWithdefaultPaymentMethod(User user) {

        UserProfileDto result = UserProfileDto.builder()
                .username(user.getUsername())
                .email(user.getEmail())
                .image(user.getImage())
                .defaultPaymentMethod(user.getDefaultPaymentMethod())
                .build();

        return result;

    }

    /**
     * 인증 확정 직후의 마무리.
     *
     * <h3>🐛 여기서 카프카로 직접 쐈다 — 그게 dual-write 였다</h3>
     *
     * <p>예전에는 이 메서드 안에 이런 세 줄이 있었다:
     *
     * <pre>{@code
     * //OutboxEvent event = createOutboxEvent(in);
     * //outboxEventRepository.save(event);
     * userCreatedProducer.publish(dto);
     * }</pre>
     *
     * <p>아웃박스는 <b>만들어 두고 주석 처리돼</b> 있었고, 실제로 도는 것은 비동기
     * {@code kafkaTemplate.send} 였다. 게다가 이 메서드는 {@code validateAndGetUser} 와
     * <b>다른 트랜잭션</b>이다 — 계정 활성화는 이미 커밋된 뒤라, 발행이 실패하면 되돌릴
     * 것도 없다. 실제로 그렇게 됐고 아무도 몰랐다(docs/47).
     *
     * <p>이제 이벤트 기록은 {@code validateAndGetUser} 가 <b>활성화와 같은 트랜잭션</b>에서
     * 한다({@link com.project.userservice.message.UserOutboxHelper}). 여기 남은 일은
     * SecurityContext 설정뿐이다.
     */
    @Override        
    public void registerConfirmed(User user) throws JsonProcessingException {

        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getEmail());

        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(userDetails,
                null,
                userDetails.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
    
    public String updatePaymentMethod(String username, String paymentMethod) {

        Optional<User> user = userRepository.findByUsername(username);

        if (!user.isPresent())
            return null;

        user.get().setDefaultPaymentMethod(paymentMethod);

        if (userRepository.updateDefaultPaymentMethod(user.get().getUserId(), paymentMethod) > 0) {
            return paymentMethod;
        }
        return null;

    }

    /**
     * 확인 토큰을 검증하고 계정을 활성화한다.
     *
     * <p>바뀐 점:
     *
     * <ul>
     *   <li><b>토큰을 해시로 조회한다.</b> 저장된 값이 해시이므로 입력도 같은 방식으로
     *       해싱해서 찾는다.</li>
     *   <li><b>실패해도 사용자 정보를 함께 돌려준다.</b> 예전에는 실패 시 user 자리에
     *       {@code null} 을 넣었는데, 호출부(RegistrationController)가 그 값으로
     *       {@code deleteUser(...)} 를 불러 <b>NPE 로 터졌다</b>. 재발송 안내를 하려면
     *       누구의 토큰이었는지도 알아야 한다.</li>
     *   <li><b>이미 인증된 계정은 성공으로 취급한다.</b> 메일 스캐너나 사용자의 재클릭으로
     *       같은 링크가 두 번 열리는 일은 흔하다. 두 번째를 실패로 처리하면 멀쩡히 가입한
     *       사용자에게 오류 화면이 뜬다.</li>
     *   <li><b>만료된 토큰을 지우지 않는다.</b> 지워 버리면 "재발송" 화면에서 누구의
     *       토큰이었는지 알 수 없다. 만료분 정리는 스케줄러의 몫이다
     *       ({@code deleteAllExpiredSince}).</li>
     * </ul>
     */
    @Override
    @Transactional
    public VerificationResult validateAndGetUser(String token) {

        if (token == null || token.isBlank()) {
            return new VerificationResult(TOKEN_INVALID, null);
        }

        final VerificationToken verificationToken =
                tokenRepository.findByToken(VerificationToken.hash(token));

        if (verificationToken == null) {
            return new VerificationResult(TOKEN_INVALID, null);
        }

        final User user = verificationToken.getUser();

        // 이미 활성화된 계정이면 몇 번을 눌러도 성공이다(멱등).
        if (user != null && user.isEnabled()) {
            return new VerificationResult(TOKEN_VALID, user);
        }

        if (verificationToken.isExpired()) {
            // 계정도 토큰도 지우지 않는다 — 사용자는 재발송으로 복구할 수 있어야 한다.
            return new VerificationResult(TOKEN_EXPIRED, user);
        }

        user.setEnabled(true);
        userRepository.save(user);

        // ⚠️ user-created 이벤트는 여기가 아니라 **가입 시점**에 적는다
        //    (registerUserAccount 주석 참조). 여기 두면 require-email-verification 이
        //    꺼진 환경에서 이 경로를 아무도 지나지 않아 이벤트가 영영 발행되지 않는다 —
        //    실제로 그랬다.

        // 토큰은 1회용이다. 활성화에 성공했으면 즉시 버려 재사용을 막는다.
        tokenRepository.delete(verificationToken);

        return new VerificationResult(TOKEN_VALID, user);
    }
}
