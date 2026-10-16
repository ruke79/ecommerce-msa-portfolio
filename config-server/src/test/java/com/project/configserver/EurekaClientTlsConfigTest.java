package com.project.configserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * config-server 의 Eureka 클라이언트는 자기 신원 인증서로 등록한다 — common 을 쓰지 않으므로 그 설정을 자기 yml 에 둔다.
 *
 * <p>🐛 예전: 매니페스트가 EUREKA_CLIENT_TLS_ENABLED=true 를 줬지만 키스토어 경로가 어디에도 없어 키 없는 SSL 로 등록했다 — Eureka 의
 * 쓰기 판정(observe)에서 30초마다 걸렸고 enforce 면 등록부에서 빠졌다.
 *
 * <p>[JA] config-server の Eureka クライアントは自分の身元証明書で登録する。以前は鍵ストアの経路がどこにもなく、証明書なしで登録していた。
 */
@DisplayName("config-server — Eureka 클라이언트 신원 인증서")
class EurekaClientTlsConfigTest {

    private static StandardEnvironment environment(Map<String, Object> env) throws IOException {
        StandardEnvironment e = new StandardEnvironment();
        e.getPropertySources().addFirst(new MapPropertySource("env", env));
        new YamlPropertySourceLoader().load("application-local", new ClassPathResource("application-local.yml"))
                .forEach(e.getPropertySources()::addLast);
        return e;
    }

    @Test
    @DisplayName("🔴 매니페스트가 켜면(EUREKA_CLIENT_TLS_ENABLED=true) 신원 키스토어 /app/identity/keystore.p12 를 낸다")
    void manifestTurnsOnIdentityKeystore() throws IOException {
        StandardEnvironment e = environment(Map.of("EUREKA_CLIENT_TLS_ENABLED", "true",
                "LOCAL_TRUST_STORE_PATH", "/app/resources/truststore.p12", "LOCAL_KEY_STORE_PASSWORD", "pw"));

        assertThat(e.getProperty("eureka.client.tls.enabled")).isEqualTo("true");
        assertThat(e.getProperty("eureka.client.tls.key-store")).isEqualTo("file:/app/identity/keystore.p12");
        assertThat(e.getProperty("eureka.client.tls.key-store-password")).isEqualTo("pw");
        assertThat(e.getProperty("eureka.client.tls.trust-store")).isEqualTo("file:/app/resources/truststore.p12");
    }

    @Test
    @DisplayName("로컬 개발은 꺼진 채다 — 신원 파일이 없어도 뜬다")
    void offByDefault() throws IOException {
        assertThat(environment(Map.of()).getProperty("eureka.client.tls.enabled")).isEqualTo("false");
    }
}
