package com.project.userservice.security.service;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.AccessLevel;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.project.userservice.model.User;

@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
@Setter
public class UserDetailsImpl implements UserDetails {
    private static final long serialVersionUID = 1L;

    private Long id;
    private String username;
    private String email;

    @JsonIgnore
    private String password;

    private boolean is2faEnabled;

    /**
     * 계정 활성 여부 — <b>메일 인증의 게이트</b>.
     *
     * <p>예전에는 이 값을 담지도 않고 {@link #isEnabled()} 가 {@code true} 를 하드코딩했다.
     * 그래서 {@code APP_REQUIRE_EMAIL_VERIFICATION=true} 로 켜서 계정을 {@code enabled=false}
     * 로 만들어도 <b>그대로 로그인됐다</b> — 메일 인증이 아무것도 막지 않았다(docs/38 §1.4).
     */
    private boolean enabled;

    private Collection<? extends GrantedAuthority> authorities;

    public UserDetailsImpl(Long id, String username, String email, String password,
                           boolean is2faEnabled, boolean enabled,
                           Collection<? extends GrantedAuthority> authorities) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.password = password;
        this.is2faEnabled = is2faEnabled;
        this.enabled = enabled;
        this.authorities = authorities;
    }

    public static UserDetailsImpl build(User user) {
        GrantedAuthority authority = new SimpleGrantedAuthority(user.getRole().getRoleName().name());

        UserDetailsImpl details = new UserDetailsImpl(
                user.getUserId(),
                user.getUsername(),
                user.getEmail(),
                user.getPassword(),
                user.isTwoFactorEnabled(),
                user.isEnabled(),
                List.of(authority) // Wrapping the single authority in a list
        );
        details.accountNonLocked = user.isAccountNonLocked();
        details.accountNonExpired = user.isAccountNonExpired();
        details.credentialsNonExpired = user.isCredentialsNonExpired();
        return details;
    }


    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    /**
     * 관리자가 정하는 계정 상태 셋 — 잠금 · 계정 만료 · 자격 만료({@code User} 의 같은 이름 필드, AdminController).
     *
     * <p>🐛 예전에는 셋 다 {@code true} 를 하드코딩했다("아무도 false 로 만들지 않는다"는 주석과 달리 관리자 화면이 만든다).
     * 그래서 관리자가 잠가도 비밀번호 로그인은 통과했고 — 리프레시만 막혀 로그인할 때마다 15분짜리 액세스를 다시 받았다.
     * 이제 엔티티의 값이고 {@code CustomAuthenticationProvider} 가 비밀번호가 맞은 뒤 검사한다.
     *
     * <p>로그인 5회 실패의 자동 잠금은 여기가 아니다 — Redis 의 잠금 키로 {@code LoginAttemptService} 가 판정 · 강제한다
     * (그것을 여기에 섞으면 남이 비밀번호를 틀려 내 갱신까지 끊게 된다). 만료 날짜 열({@code accountExpiryDate} 등)은 아직
     * 판정에 쓰지 않는다 — 불리언만 본다.
     */
    // [JA] 管理者が決めるアカウント状態の三つ。以前は true 固定で、管理者がロックしてもパスワードログインは通った。
    //      今はエンティティの値で、CustomAuthenticationProvider がパスワード一致の後に検査する。自動ロック(5 回失敗)は別。
    private boolean accountNonLocked = true;
    private boolean accountNonExpired = true;
    private boolean credentialsNonExpired = true;

    @Override
    public boolean isAccountNonExpired() {
        return accountNonExpired;
    }

    @Override
    public boolean isAccountNonLocked() {
        return accountNonLocked;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return credentialsNonExpired;
    }

    /** 메일 인증 게이트. 하드코딩이 아니라 <b>엔티티의 값</b>이다 — 위 {@link #enabled} 주석 참조. */
    @Override
    public boolean isEnabled() {
        return enabled;
    }

    public boolean is2faEnabled() {
        return is2faEnabled;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        UserDetailsImpl user = (UserDetailsImpl) o;
        return Objects.equals(id, user.id);
    }
}
