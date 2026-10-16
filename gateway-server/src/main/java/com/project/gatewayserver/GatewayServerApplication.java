package com.project.gatewayserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 게이트웨이 — WebFlux 를 <b>Reactor Netty</b> 서버 위에서 돌린다. 백엔드 호출도 Reactor Netty 클라이언트다.
 *
 * <p>예전에는 spring-boot-starter-websocket 이 서블릿 · Tomcat 을 끌어와 Tomcat 위의 리액티브로 떴고, 그래서 WebSocket 업그레이드
 * 전략 · 클라이언트를 Tomcat 구현으로 {@code @Primary} 빈 두 개로 맞춰야 했다(셋이 서로를 떠받쳐 하나만 건드리면 조용히 깨졌다).
 * 이제 build.gradle.kts 가 서블릿 · Tomcat 을 빼므로 Gateway 자동 설정이 ReactorNetty 의 업그레이드 전략 · 클라이언트를 준다 —
 * 여기에 WebSocket 빈을 두지 않는다(NettyServerTest).
 *
 * <p>[JA] ゲートウェイは WebFlux を Reactor Netty サーバーの上で動かす。以前は starter-websocket が Tomcat を連れてきて、WebSocket の
 * アップグレード戦略・クライアントを Tomcat 実装の @Primary Bean で合わせていた。今は Gateway の自動設定が ReactorNetty のものを用意する。
 */
@SpringBootApplication
public class GatewayServerApplication {

   

    public static void main(String[] args) {
        applySslPasswordsFromEnv(System.getenv(), System.getProperties());   // 첫 TLS 연결보다 먼저
        SpringApplication.run(GatewayServerApplication.class, args);
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
