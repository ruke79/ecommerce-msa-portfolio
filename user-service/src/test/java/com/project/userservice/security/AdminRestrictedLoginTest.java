package com.project.userservice.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AccountExpiredException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.project.userservice.constants.AppRole;
import com.project.userservice.model.Role;
import com.project.userservice.model.User;
import com.project.userservice.security.service.UserDetailsImpl;
import com.project.userservice.security.service.UserDetailsServiceImpl;

/**
 * 관리자가 잠그거나 만료한 계정은 비밀번호가 맞아도 로그인하지 못한다.
 *
 * <p>🐛 예전: {@code UserDetailsImpl} 의 잠금 · 만료 셋이 {@code true} 하드코딩이고 인증기는 {@code isEnabled} 만 봤다 — 관리자
 * 잠금(update-lock-status) 뒤에도 비밀번호 로그인이 200 이었다. 리프레시만 막혀 로그인할 때마다 액세스 토큰을 다시 받았다.
 *
 * <p>[JA] 管理者がロック · 期限切れにしたアカウントは、パスワードが合ってもログインできない。以前は true 固定で素通りだった。
 */
@DisplayName("관리자 잠금 · 만료 계정의 로그인 차단")
class AdminRestrictedLoginTest {

    private static final String RAW = "correct-horse-battery";

    private UserDetailsServiceImpl userDetailsService;
    private CustomAuthenticationProvider provider;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);

    @BeforeEach
    void setUp() {
        userDetailsService = mock(UserDetailsServiceImpl.class);
        provider = new CustomAuthenticationProvider(userDetailsService, encoder, mock(LoginAttemptService.class));
    }

    private UserDetailsImpl details(Consumer<User> state) {
        User user = new User("tester", "tester@example.com", encoder.encode(RAW));
        user.setRole(new Role(AppRole.ROLE_USER));
        state.accept(user);
        return UserDetailsImpl.build(user);
    }

    private void login(UserDetailsImpl d, String password) {
        when(userDetailsService.loadUserByUsername(anyString())).thenReturn(d);
        provider.authenticate(new UsernamePasswordAuthenticationToken("tester@example.com", password));
    }

    @Test
    @DisplayName("🔴 UserDetailsImpl 은 엔티티의 잠금 · 만료 셋을 그대로 담는다 — 하드코딩이면 인증기가 볼 것이 없다")
    void detailsReflectTheEntity() {
        assertThat(details(u -> u.setAccountNonLocked(false)).isAccountNonLocked()).isFalse();
        assertThat(details(u -> u.setAccountNonExpired(false)).isAccountNonExpired()).isFalse();
        assertThat(details(u -> u.setCredentialsNonExpired(false)).isCredentialsNonExpired()).isFalse();
        UserDetailsImpl ok = details(u -> { });
        assertThat(ok.isAccountNonLocked() && ok.isAccountNonExpired() && ok.isCredentialsNonExpired()).isTrue();
    }

    @Test
    @DisplayName("🔴 관리자가 잠근 계정 — 맞는 비밀번호도 LockedException(제한 문구)")
    void lockedByAdminIsRejected() {
        assertThatThrownBy(() -> login(details(u -> u.setAccountNonLocked(false)), RAW))
                .isInstanceOf(LockedException.class)
                .hasMessage(CustomAuthenticationProvider.RESTRICTED);
    }

    @Test
    @DisplayName("🔴 계정 만료 · 자격 만료도 거부된다")
    void expiredIsRejected() {
        assertThatThrownBy(() -> login(details(u -> u.setAccountNonExpired(false)), RAW))
                .isInstanceOf(AccountExpiredException.class);
        assertThatThrownBy(() -> login(details(u -> u.setCredentialsNonExpired(false)), RAW))
                .isInstanceOf(CredentialsExpiredException.class);
    }

    @Test
    @DisplayName("🔒 비밀번호가 틀리면 제한 여부를 알리지 않는다 — BadCredentials")
    void wrongPasswordDoesNotRevealRestriction() {
        assertThatThrownBy(() -> login(details(u -> u.setAccountNonLocked(false)), "wrong"))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("응답 — 만료도 401 + 제한 문구(일반 실패 문구가 아니다)")
    void filterShowsTheReason() {
        var f = AuthLoginFilter.describe(new AccountExpiredException(CustomAuthenticationProvider.RESTRICTED));
        assertThat(f.status()).isEqualTo(401);
        assertThat(f.message()).isEqualTo(CustomAuthenticationProvider.RESTRICTED);
    }

    @Test
    @DisplayName("User.canSignIn — 넷 중 하나라도 막히면 false (세션을 주는 모든 곳이 이것을 본다)")
    void canSignIn() {
        User u = new User("t", "t@example.com", "x");
        assertThat(u.canSignIn()).isTrue();
        u.setCredentialsNonExpired(false);
        assertThat(u.canSignIn()).isFalse();
        u.setCredentialsNonExpired(true);
        u.setEnabled(false);
        assertThat(u.canSignIn()).isFalse();
    }

    /** 🐛 예전: 없는 계정은 BCrypt 를 건너뛰어(약 50~100ms) 응답 시간으로 가입 여부가 갈렸다. */
    @Test
    @DisplayName("🔴 없는 계정도 비밀번호 비교를 한 번 한다 — 응답은 틀린 비밀번호와 같다")
    void unknownAccountStillHashes() {
        PasswordEncoder spy = org.mockito.Mockito.spy(encoder);
        CustomAuthenticationProvider p = new CustomAuthenticationProvider(userDetailsService, spy, mock(LoginAttemptService.class));
        when(userDetailsService.loadUserByUsername(anyString()))
                .thenThrow(new org.springframework.security.core.userdetails.UsernameNotFoundException("x"));

        assertThatThrownBy(() -> p.authenticate(new UsernamePasswordAuthenticationToken("ghost@example.com", RAW)))
                .isInstanceOf(BadCredentialsException.class);
        org.mockito.Mockito.verify(spy).matches(org.mockito.ArgumentMatchers.eq(RAW), anyString());
    }
}
