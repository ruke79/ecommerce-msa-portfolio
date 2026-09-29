package com.project.userservice.security;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.project.userservice.security.service.UserDetailsServiceImpl;
import com.project.userservice.model.User;
import com.project.userservice.repository.jpa.UserRepository;

@Component
public class CustomAuthenticationProvider implements AuthenticationProvider {

    // @Autowired
    private final UserDetailsServiceImpl userDetailsService;
    // @Autowired
    private final PasswordEncoder passwordEncoder;

    private final LoginAttemptService loginAttemptService;

    public CustomAuthenticationProvider(UserDetailsServiceImpl userDetailsService, PasswordEncoder passwordEncoder,
            LoginAttemptService loginAttemptService) {
        this.userDetailsService = userDetailsService;
        this.passwordEncoder = passwordEncoder;
        this.loginAttemptService = loginAttemptService;
    }

    @Override
    public Authentication authenticate(Authentication auth) throws AuthenticationException {
        String username = auth.getName();
        String password = auth.getCredentials().toString();

        // ⚠️ 비밀번호 검사 **전에** 잠금을 본다. 잠긴 동안 맞는 비밀번호가 통과되면
        //    공격자는 잠금을 무시하고 대입을 계속할 수 있다 — 잠금의 의미가 없어진다.
        //    (예전에는 accountNonLocked 플래그를 아무도 검사하지 않았다 — 장식이었다.)
        loginAttemptService.assertNotLocked(username);

        UserDetails userDetails = userDetailsService.loadUserByUsername(username);

        if (passwordEncoder.matches(password, userDetails.getPassword())) {

            // 비밀번호가 맞았으니 대입 시도가 아니다 — 실패 카운터를 먼저 되돌린다.
            loginAttemptService.onSuccess(username);

            // ⚠️ 이 클래스는 AuthenticationProvider 를 **직접 구현**한다. DaoAuthenticationProvider 가
            //    해 주던 사전 검사(isEnabled 등)가 전혀 돌지 않으므로 여기서 직접 해야 한다.
            //    이게 없으면 APP_REQUIRE_EMAIL_VERIFICATION 을 켜서 계정을 비활성으로 만들어도
            //    그대로 로그인된다 — 메일 인증이 아무것도 막지 못한다(docs/38 §1.4).
            //
            // ⚠️ **비밀번호 검사 뒤에** 둔 것은 의도적이다. 앞에 두면 비밀번호를 모르는 사람도
            //    "이 주소는 가입돼 있고 미인증"이라는 사실을 알 수 있다 — 가입 여부 확인 도구가
            //    된다. 뒤에 두면 올바른 비밀번호를 아는 본인에게만 이유가 보인다.
            if (!userDetails.isEnabled()) {
                throw new DisabledException(
                        "메일 인증이 완료되지 않은 계정입니다. 가입 확인 메일의 링크를 눌러 주세요. "
                                + "메일이 오지 않았다면 확인 메일 재발송을 이용해 주세요.");
            }

            return new UsernamePasswordAuthenticationToken(userDetails,
                    null, userDetails.getAuthorities());
        } else {
            loginAttemptService.onFailure(username);
            throw new BadCredentialsException("Bad Credentials");
        }

    }

    private boolean isValidLong(String code) {
        try {
            Long.parseLong(code);
        } catch (final NumberFormatException e) {
            return false;
        }
        return true;
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return authentication.equals(UsernamePasswordAuthenticationToken.class);
    }
}