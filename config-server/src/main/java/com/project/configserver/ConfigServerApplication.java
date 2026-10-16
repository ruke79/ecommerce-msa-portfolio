package com.project.configserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.config.server.EnableConfigServer;

@SpringBootApplication
@EnableConfigServer
public class ConfigServerApplication {

    public static void main(String[] args) {
        applySslPasswordsFromEnv(System.getenv(), System.getProperties());   // 첫 TLS 연결보다 먼저
        SpringApplication.run(ConfigServerApplication.class, args);
    }

    /**
     * JVM 기본 트러스트 · 키 스토어 비밀번호를 환경 변수에서 시스템 속성으로 — {@code JAVA_TOOL_OPTIONS} 에 넣으면 JVM 이 기동마다
     * 평문으로 stderr 에 찍는다(Picked up JAVA_TOOL_OPTIONS). 이 모듈은 common 을 쓰지 않아 common 의 JvmSslPasswords 와 같은 일을
     * 여기서 한다 — 바꾸면 둘 다(B-83).
     */
    // [JA] JVM 既定ストアのパスワードを環境変数からシステムプロパティへ。このモジュールは common を使わないのでここで同じことをする。
    static void applySslPasswordsFromEnv(java.util.Map<String, String> env, java.util.Properties properties) {
        java.util.Map.of("JAVAX_NET_SSL_TRUST_STORE_PASSWORD", "javax.net.ssl.trustStorePassword",
                "JAVAX_NET_SSL_KEY_STORE_PASSWORD", "javax.net.ssl.keyStorePassword").forEach((variable, property) -> {
                    String value = env.get(variable);
                    if (value != null && !value.isBlank() && properties.getProperty(property) == null) {
                        properties.setProperty(property, value);
                    }
                });
    }


}
