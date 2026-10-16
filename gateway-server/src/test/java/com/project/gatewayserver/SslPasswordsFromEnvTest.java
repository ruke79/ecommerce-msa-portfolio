package com.project.gatewayserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 게이트웨이의 JVM 스토어 비밀번호 — 환경 변수에서(B-83). JAVA_TOOL_OPTIONS 에 넣으면 JVM 이 기동마다 평문으로 찍는다.
 *
 * <p>[JA] ゲートウェイの JVM ストアのパスワード — 環境変数から。JAVA_TOOL_OPTIONS に入れると JVM が平文で出す。
 */
@DisplayName("게이트웨이 — 스토어 비밀번호는 환경 변수에서(B-83)")
class SslPasswordsFromEnvTest {

    @Test
    @DisplayName("🔴 트러스트스토어 비밀번호를 옮기고, 이미 정해진 값은 덮지 않는다")
    void movesTrustStorePassword() {
        Properties p = new Properties();
        GatewayServerApplication.applySslPasswordsFromEnv(Map.of("JAVAX_NET_SSL_TRUST_STORE_PASSWORD", "t-pw"), p);
        assertThat(p.getProperty("javax.net.ssl.trustStorePassword")).isEqualTo("t-pw");

        p.setProperty("javax.net.ssl.keyStorePassword", "cmdline");
        GatewayServerApplication.applySslPasswordsFromEnv(Map.of("JAVAX_NET_SSL_KEY_STORE_PASSWORD", "env"), p);
        assertThat(p.getProperty("javax.net.ssl.keyStorePassword")).isEqualTo("cmdline");
    }
}
