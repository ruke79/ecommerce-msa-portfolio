package com.project.userservice.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.project.userservice.constants.AppRole;
import com.project.userservice.model.Role;
import com.project.userservice.model.User;
import com.project.userservice.security.service.UserDetailsImpl;
import com.project.userservice.security.service.UserDetailsServiceImpl;

/**
 * 메일 인증이 <b>실제로 로그인을 막는지</b> 확인한다.
 *
 * <p>예전에는 막지 못했다. {@code UserDetailsImpl.isEnabled()} 가 {@code true} 를 하드코딩했고,
 * {@link CustomAuthenticationProvider} 는 {@code AuthenticationProvider} 를 직접 구현해
 * 스프링의 사전 검사({@code isEnabled} 등)를 아예 거치지 않았다. 두 겹으로 무력화돼 있어서,
 * {@code APP_REQUIRE_EMAIL_VERIFICATION=true} 로 켜도 비활성 계정이 그대로 로그인됐다
 * (docs/38 §1.4).
 *
 * <p>둘 중 하나만 되돌려도 게이트가 사라지므로 양쪽을 각각 세는 테스트를 둔다.
 */
@DisplayName("미인증 계정 로그인 차단")
class DisabledAccountLoginTest {

    private static final String RAW_PASSWORD = "correct-horse-battery";

    private UserDetailsServiceImpl userDetailsService;
    private LoginAttemptService loginAttemptService;
    private CustomAuthenticationProvider provider;
    private PasswordEncoder encoder;

    @BeforeEach
    void setUp() {
        userDetailsService = mock(UserDetailsServiceImpl.class);
        loginAttemptService = mock(LoginAttemptService.class);
        encoder = new BCryptPasswordEncoder();
        provider = new CustomAuthenticationProvider(userDetailsService, encoder, loginAttemptService);
    }

    private static User user(boolean enabled) {
        User user = new User("tester", "tester@example.com", "ignored");
        user.setRole(new Role(AppRole.ROLE_USER));
        user.setEnabled(enabled);
        return user;
    }

    private UserDetailsImpl detailsFor(boolean enabled) {
        User entity = user(enabled);
        entity.setPassword(encoder.encode(RAW_PASSWORD));
        return UserDetailsImpl.build(entity);
    }

    private Authentication attempt() {
        return provider.authenticate(
                new UsernamePasswordAuthenticationToken("tester@example.com", RAW_PASSWORD));
    }

    @Test
    @DisplayName("UserDetailsImpl 은 엔티티의 enabled 를 그대로 반영한다 — 하드코딩이면 게이트가 없다")
    void userDetailsReflectsTheEntityFlag() {
        assertThat(detailsFor(false).isEnabled()).isFalse();
        assertThat(detailsFor(true).isEnabled()).isTrue();
    }

    @Test
    @DisplayName("비활성 계정은 비밀번호가 맞아도 DisabledException 으로 거부된다")
    void disabledAccountIsRejectedEvenWithTheRightPassword() {
        when(userDetailsService.loadUserByUsername(anyString())).thenReturn(detailsFor(false));

        assertThatThrownBy(this::attempt)
                .isInstanceOf(DisabledException.class)
                .hasMessageContaining("메일 인증");
    }

    @Test
    @DisplayName("활성 계정은 정상 인증된다")
    void enabledAccountAuthenticates() {
        when(userDetailsService.loadUserByUsername(anyString())).thenReturn(detailsFor(true));

        assertThatCode(this::attempt).doesNotThrowAnyException();
        assertThat(attempt().isAuthenticated()).isTrue();
    }

    @Test
    @DisplayName("비활성 계정 거부는 실패 카운터를 올리지 않는다 — 본인이 반복해도 잠기면 안 된다")
    void rejectionDoesNotCountAsAFailedAttempt() {
        when(userDetailsService.loadUserByUsername(anyString())).thenReturn(detailsFor(false));

        assertThatThrownBy(this::attempt).isInstanceOf(DisabledException.class);

        verify(loginAttemptService, never()).onFailure(anyString());
        verify(loginAttemptService).onSuccess("tester@example.com");
    }

    @Test
    @DisplayName("비밀번호가 틀리면 계정 상태와 무관하게 BadCredentials — 가입 여부가 새지 않는다")
    void wrongPasswordNeverRevealsAccountState() {
        when(userDetailsService.loadUserByUsername(anyString())).thenReturn(detailsFor(false));

        assertThatThrownBy(() -> provider.authenticate(
                new UsernamePasswordAuthenticationToken("tester@example.com", "wrong")))
                .isInstanceOf(BadCredentialsException.class);

        verify(loginAttemptService).onFailure("tester@example.com");
    }

    @Test
    @DisplayName("잠금 검사가 비밀번호 검사보다 먼저다")
    void lockIsCheckedFirst() {
        when(userDetailsService.loadUserByUsername(anyString())).thenReturn(detailsFor(true));
        org.mockito.Mockito.doThrow(new org.springframework.security.authentication.LockedException("locked"))
                .when(loginAttemptService).reserveAttempt(anyString());

        assertThatThrownBy(this::attempt)
                .isInstanceOf(org.springframework.security.authentication.LockedException.class);

        verify(userDetailsService, never()).loadUserByUsername(any());
    }
}
