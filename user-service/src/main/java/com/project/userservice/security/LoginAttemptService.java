package com.project.userservice.security;

import java.time.Duration;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.LockedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.project.userservice.model.User;
import com.project.userservice.repository.jpa.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 로그인 실패 카운터와 잠금 — 비밀번호 대입 방어의 계정 축.
 *
 * <p>게이트웨이의 IP 레이트리밋은 이미 있다(분당 수십 회). 이건 다른 축이다: IP 를
 * 바꿔 가며 **한 계정**을 노리는 공격(크리덴셜 스터핑)은 IP 버킷으로 못 막는다.
 * 연속 {@code auth.lock.max-failures}(기본 5)회 실패 → {@code auth.lock.duration}
 * (기본 15분) 잠금, 만료는 자동 해제.
 *
 * <p>설계 결정:
 * <ul>
 *   <li><b>영구 잠금은 없다.</b> 만료 시각이 지나면 다음 시도에서 자동으로 풀린다 —
 *       영구 잠금은 "고객이 전화할 때까지 서비스 불능"과 같은 말이고, 공격자에게는
 *       남의 계정을 잠그는 DoS 버튼이 된다. 15분이면 대입 속도는 사실상 0이 된다
 *   <li><b>없는 계정의 실패는 세지 않는다.</b> 카운터가 계정 존재를 노출하는 부채널이
 *       되면 안 된다 — 응답은 어느 쪽이든 같은 "Bad Credentials" 다
 *   <li><b>소셜 계정은 잠그지 않는다.</b> 비밀번호가 난수라 대입이 무의미하고,
 *       실패 카운터는 비밀번호 경로에만 의미가 있다
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginAttemptService {

    private final UserRepository userRepository;

    @Value("${auth.lock.max-failures:5}")
    private int maxFailures;

    @Value("${auth.lock.duration:PT15M}")
    private Duration lockDuration;

    /**
     * 비밀번호를 검사하기 **전에** 부른다. 잠겨 있으면 LockedException — 잠긴 동안에는
     * 맞는 비밀번호를 넣어도 통과시키지 않는다(통과시키면 공격자가 잠금을 무시하고
     * 계속 시도할 수 있다).
     */
    @Transactional
    public void assertNotLocked(String email) {
        userRepository.findByEmail(email).ifPresent(user -> {
            LocalDateTime until = user.getLockExpiresAt();
            if (until == null) {
                return;
            }
            if (LocalDateTime.now().isBefore(until)) {
                long minutes = Math.max(1, Duration.between(LocalDateTime.now(), until).toMinutes());
                throw new LockedException(
                        "로그인이 잠시 잠겼습니다. 약 " + minutes + "분 후 다시 시도해 주세요.");
            }
            // 만료 — 자동 해제. 다음 실패는 1부터 다시 센다.
            user.setLockExpiresAt(null);
            user.setFailedLoginAttempts(0);
            userRepository.save(user);
            log.info("잠금 만료로 자동 해제. userId={}", user.getUserId());
        });
    }

    @Transactional
    public void onFailure(String email) {
        userRepository.findByEmail(email).ifPresent(user -> {
            int failures = user.getFailedLoginAttempts() + 1;
            user.setFailedLoginAttempts(failures);
            if (failures >= maxFailures) {
                user.setLockExpiresAt(LocalDateTime.now().plus(lockDuration));
                // 값(비밀번호)은 물론이고 몇 번째 시도인지도 응답에는 싣지 않는다 —
                // 로그만 안다. 임계값을 알려주면 공격자는 그 직전까지만 시도한다.
                log.warn("로그인 {}회 연속 실패 — {} 동안 잠금. userId={}",
                        failures, lockDuration, user.getUserId());
            }
            userRepository.save(user);
        });
    }

    @Transactional
    public void onSuccess(String email) {
        userRepository.findByEmail(email).ifPresent(user -> {
            if (user.getFailedLoginAttempts() > 0 || user.getLockExpiresAt() != null) {
                user.setFailedLoginAttempts(0);
                user.setLockExpiresAt(null);
                userRepository.save(user);
            }
        });
    }
}
