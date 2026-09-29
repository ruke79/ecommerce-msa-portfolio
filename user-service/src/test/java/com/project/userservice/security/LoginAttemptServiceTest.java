package com.project.userservice.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.LockedException;
import org.springframework.test.util.ReflectionTestUtils;

import com.project.userservice.model.User;
import com.project.userservice.repository.jpa.UserRepository;

/**
 * 계정 잠금 회귀 테스트.
 *
 * <p>배경: users.account_non_locked 컬럼은 처음부터 있었지만 실패를 세는 코드가 없었고,
 * 커스텀 AuthenticationProvider 는 그 플래그를 검사조차 하지 않았다 — 게이트웨이 IP
 * 레이트리밋이 유일한 방어였는데, IP 를 바꿔 가며 한 계정을 노리는 공격에는 무력하다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LoginAttemptServiceTest {

    @Mock private UserRepository userRepository;

    @InjectMocks private LoginAttemptService service;

    private User user;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "maxFailures", 5);
        ReflectionTestUtils.setField(service, "lockDuration", Duration.ofMinutes(15));

        user = new User("kim", "kim@example.com", "$2a$pw");
        when(userRepository.findByEmail("kim@example.com")).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("5회 연속 실패에서 잠긴다 — 4회까지는 안 잠긴다")
    void locksAtThreshold() {
        for (int i = 0; i < 4; i++) {
            service.onFailure("kim@example.com");
        }
        assertNull(user.getLockExpiresAt());
        assertEquals(4, user.getFailedLoginAttempts());

        service.onFailure("kim@example.com");

        assertNotNull(user.getLockExpiresAt());
        assertTrue(user.getLockExpiresAt().isAfter(LocalDateTime.now().plusMinutes(14)));
    }

    @Test
    @DisplayName("잠긴 동안에는 비밀번호 검사 전에 LockedException — 맞는 비밀번호도 통과 못 한다")
    void assertNotLockedThrowsWhileLocked() {
        user.setLockExpiresAt(LocalDateTime.now().plusMinutes(10));

        LockedException e = assertThrows(LockedException.class,
                () -> service.assertNotLocked("kim@example.com"));
        // 사용자 안내는 "언제 풀리나"를 답한다 — 실패 횟수·임계값은 답하지 않는다.
        assertTrue(e.getMessage().contains("분 후"));
    }

    @Test
    @DisplayName("만료된 잠금은 자동 해제 — 운영자 개입이 필요 없다")
    void expiredLockAutoUnlocks() {
        user.setLockExpiresAt(LocalDateTime.now().minusSeconds(1));
        user.setFailedLoginAttempts(5);

        assertDoesNotThrow(() -> service.assertNotLocked("kim@example.com"));
        assertNull(user.getLockExpiresAt());
        assertEquals(0, user.getFailedLoginAttempts());
    }

    @Test
    @DisplayName("성공하면 카운터가 리셋된다 — 어제 3번 틀린 것이 오늘까지 쌓이지 않는다")
    void successResets() {
        user.setFailedLoginAttempts(3);

        service.onSuccess("kim@example.com");

        assertEquals(0, user.getFailedLoginAttempts());
    }

    @Test
    @DisplayName("없는 계정의 실패는 세지 않는다 — 카운터가 계정 존재의 부채널이 되면 안 된다")
    void unknownAccountIsNotCounted() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> service.onFailure("ghost@example.com"));
        assertDoesNotThrow(() -> service.assertNotLocked("ghost@example.com"));
        verify(userRepository, never()).save(any());
    }
}
