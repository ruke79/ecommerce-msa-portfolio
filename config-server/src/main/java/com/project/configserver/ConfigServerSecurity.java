package com.project.configserver;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * config-server 의 HTTP 인증 — <b>비밀번호가 없으면 닫힌다</b>(프로브 · 지표만 연다).
 *
 * <p>🐛 예전에는 Spring Security 가 없어 같은 네임스페이스의 누구나 {@code GET /{application}/{profile}} 로 모든 서비스의 설정을 읽고,
 * {@code ENCRYPT_KEY} 를 켜면 {@code POST /decrypt} 로 설정 저장소의 {@code {cipher}} 값을 풀 수 있었다. 지금은 저장소가 연결돼 있지
 * 않아(CONFIG_GIT_URI 가 자리표시) 내줄 것이 없을 뿐이었다 — 저장소를 붙이는 날 그대로 열린다.
 *
 * <p>이제 {@code CONFIG_SERVER_PASSWORD}(Secret)가 있으면 사용자 {@code config} 의 HTTP Basic 으로만, 없으면 아무에게도 열지 않는다.
 * 클라이언트는 {@code optional:configserver:} 라 거절돼도 자기 번들 설정으로 돈다(지금과 같다). 저장소를 연결할 때 Secret 에
 * {@code CONFIG_SERVER_PASSWORD} 를 넣고 클라이언트에 {@code spring.cloud.config.username/password} 를 준다.
 * 열어 두는 것: kubelet 프로브({@code /actuator/health/**}) · {@code /actuator/info} · Prometheus({@code /actuator/prometheus}).
 *
 * <p>[JA] config-server の HTTP 認証 — パスワードがなければ閉じる(プローブ・メトリクスだけ開く)。以前は Spring Security がなく、
 * 同じ名前空間の誰でも全サービスの設定を読め、ENCRYPT_KEY を有効にすると /decrypt で {cipher} を解けた。
 */
@Configuration(proxyBeanMethods = false)
public class ConfigServerSecurity {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ConfigServerSecurity.class);

    static final String USER = "config";

    @Bean
    SecurityFilterChain configServerFilterChain(HttpSecurity http,
            @Value("${config-server.password:${CONFIG_SERVER_PASSWORD:}}") String password) throws Exception {
        boolean open = password != null && !password.isBlank();
        if (!open) {
            log.warn("CONFIG_SERVER_PASSWORD 가 없다 — 설정 · 복호화 API 를 모두 거절한다(프로브 · 지표만 연다)");
        }
        http.csrf(c -> c.disable())   // 브라우저가 아니라 서비스가 부르는 API — 세션 · 쿠키가 없다
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> {
                    a.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info", "/actuator/prometheus")
                            .permitAll();
                    if (open) {
                        a.anyRequest().authenticated();
                    } else {
                        a.anyRequest().denyAll();
                    }
                })
                .httpBasic(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    PasswordEncoder configServerPasswordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /** 사용자 하나({@code config}). 비밀번호가 없으면 아무도 없다 — Boot 의 기본 사용자(기동마다 로그에 찍히는 임의 비밀번호)도 막는다. */
    @Bean
    UserDetailsService configServerUsers(PasswordEncoder encoder,
            @Value("${config-server.password:${CONFIG_SERVER_PASSWORD:}}") String password) {
        if (password == null || password.isBlank()) {
            return new InMemoryUserDetailsManager();
        }
        return new InMemoryUserDetailsManager(
                User.withUsername(USER).password(encoder.encode(password)).roles("CONFIG_CLIENT").build());
    }
}
