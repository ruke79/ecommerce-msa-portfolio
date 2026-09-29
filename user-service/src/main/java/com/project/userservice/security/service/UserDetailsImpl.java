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

        return new UserDetailsImpl(
                user.getUserId(),
                user.getUsername(),
                user.getEmail(),
                user.getPassword(),
                user.isTwoFactorEnabled(),
                user.isEnabled(),
                List.of(authority) // Wrapping the single authority in a list
        );
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
     * ⚠️ 아래 셋은 <b>의도적으로</b> 항상 참이다. 엔티티에 같은 이름의 필드가 있지만 이 저장소에서
     * 계정 상태를 결정하는 것은 그 필드들이 아니다:
     * <ul>
     *   <li>잠금은 {@code lockExpiresAt} 하나가 판정하고 {@code LoginAttemptService} 가 강제한다
     *       ({@code User} 필드 주석 참조). {@code accountNonLocked} 는 아무도 false 로 만들지 않는다.</li>
     *   <li>만료는 {@code accountExpiryDate}/{@code credentialsExpiryDate} 날짜 컬럼이 있지만
     *       그 날짜를 검사하는 코드가 없다. 불리언만 강제하면 "만료됐는데 통과"가 되어 오히려
     *       지켜지는 것처럼 보인다.</li>
     * </ul>
     * 셋 중 하나라도 실제 게이트로 쓰려면 그 판정 로직을 먼저 만들어야 한다.
     */
    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
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
