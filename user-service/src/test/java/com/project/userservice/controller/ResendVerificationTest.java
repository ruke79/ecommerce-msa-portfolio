package com.project.userservice.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;

import com.project.userservice.model.User;
import com.project.userservice.service.UserService;

/**
 * 확인 메일 재발송 — 인증 여부는 emailVerified 로 본다(enabled 가 아니다).
 *
 * <p>인증 게이트가 꺼진 운영(APP_REQUIRE_EMAIL_VERIFICATION=false)에서는 가입 즉시 활성이라 활성 여부로는 가를 수 없다.
 *
 * <p>[JA] 確認メールの再送 — 認証済みかは emailVerified で見る。認証ゲートが無効な本番では加入直後から有効なので、有効かでは区別できない。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("확인 메일 재발송 — 활성이지만 미인증이면 보낸다")
class ResendVerificationTest {

    @Mock UserService userService;
    @InjectMocks AuthController controller;

    private static User user(boolean verified) {
        User u = new User("u7", "u7@example.com", "pw");
        u.setUserId(7L);
        u.setEnabled(true);
        u.setEmailVerified(verified);
        return u;
    }

    @Test
    @DisplayName("🔴 활성 · 미인증 계정(인증 게이트가 꺼진 운영)은 다시 보낸다")
    void enabledButUnverifiedIsResent() {
        User u = user(false);
        when(userService.findByEmail("u7@example.com")).thenReturn(u);
        controller.resendVerificationEmail(Map.of("email", "u7@example.com"), new MockHttpServletRequest());
        verify(userService).requestVerificationMail(any(), any());
    }

    @Test
    @DisplayName("이미 인증한 계정은 보내지 않는다 — 응답은 같다")
    void verifiedIsNotResent() {
        when(userService.findByEmail("u7@example.com")).thenReturn(user(true));
        controller.resendVerificationEmail(Map.of("email", "u7@example.com"), new MockHttpServletRequest());
        verify(userService, never()).requestVerificationMail(any(), any());
    }
}
