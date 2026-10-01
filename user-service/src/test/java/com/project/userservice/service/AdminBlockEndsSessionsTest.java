package com.project.userservice.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

import com.project.userservice.model.User;
import com.project.userservice.repository.jpa.UserRepository;
import com.project.userservice.service.impl.UserServiceImpl;

/**
 * 🔴 관리자가 계정을 막으면 이미 열린 로그인도 끊는다(docs/68 §13).
 *
 * <p>🐛 예전: DB 값만 바뀌어, 이미 로그인한 사용자는 리프레시 토큰으로 최대 30일 동안 계속 주문 · 채팅했다.
 */
// [JA] 管理者がアカウントを止めたら、既に開いているログインも切る。以前は DB の値だけが変わっていた。
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("관리자 계정 차단 — 열린 로그인도 끊는다")
class AdminBlockEndsSessionsTest {

    @Mock private UserRepository userRepository;
    @Mock private RefreshTokenService refreshTokenService;
    @InjectMocks private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(mock(User.class)));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("🔴 정지 · 잠금 · 계정 만료 · 자격 증명 만료는 모든 로그인을 끊는다")
    void blockingEndsSessions() {
        userService.updateAccountEnabledStatus(7L, false);
        userService.updateAccountLockStatus(7L, true);
        userService.updateAccountExpiryStatus(7L, true);
        userService.updateCredentialsExpiryStatus(7L, true);

        verify(refreshTokenService, org.mockito.Mockito.times(4)).revokeAll(7L);
    }

    @Test
    @DisplayName("풀 때는 끊지 않는다")
    void unblockingKeepsSessions() {
        userService.updateAccountEnabledStatus(7L, true);
        userService.updateAccountLockStatus(7L, false);
        userService.updateAccountExpiryStatus(7L, false);
        userService.updateCredentialsExpiryStatus(7L, false);

        verify(refreshTokenService, never()).revokeAll(any());
    }
}
