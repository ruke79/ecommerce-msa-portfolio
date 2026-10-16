package com.project.gatewayserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.embedded.netty.NettyWebServer;
import org.springframework.boot.web.reactive.context.ReactiveWebServerApplicationContext;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import org.springframework.web.reactive.socket.server.RequestUpgradeStrategy;
import org.springframework.web.reactive.socket.server.upgrade.ReactorNettyRequestUpgradeStrategy;

import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * 게이트웨이는 <b>Reactor Netty</b> 서버로 뜨고, WebSocket 은 Gateway 자동 설정의 ReactorNetty 부품으로 프록시된다.
 *
 * <p>🐛 예전: spring-boot-starter-websocket 이 서블릿 · Tomcat 을 끌어와 Tomcat 위의 리액티브로 떴다. 그래서 WebSocket 업그레이드 ·
 * 클라이언트를 Tomcat 구현의 {@code @Primary} 빈으로 따로 맞췄고, 셋(starter · web-application-type · 두 빈) 중 하나만 바뀌어도
 * 조용히 깨졌다. 이제 build.gradle.kts 가 서블릿 · Tomcat 을 빼고, 이 테스트가 실제로 띄워 확인한다 — Redis · Eureka · 설정 서버
 * 없이(레이트 리미터는 첫 요청 때에야 Redis 에 붙는다).
 *
 * <p>[JA] ゲートウェイは Reactor Netty サーバーで起動し、WebSocket は Gateway 自動設定の ReactorNetty 部品でプロキシされる。以前は
 * starter-websocket が Tomcat を連れてきて、WebSocket の部品を Tomcat 実装の @Primary Bean で合わせていた。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("게이트웨이 — Reactor Netty 서버 · WebSocket 프록시")
class NettyServerTest {

    /** 백엔드 대역 — 받은 글을 그대로 돌려보내는 WebSocket. 컨텍스트(라우트)보다 먼저 떠 있어야 해서 정적 초기화로 띄운다. */
    static final DisposableServer ECHO = HttpServer.create().host("127.0.0.1").port(0)
            .route(r -> r.ws("/echo", (in, out) -> out.sendString(in.receive().asString().map(s -> "echo:" + s))))
            .bindNow();

    @AfterAll
    static void stopEcho() {
        ECHO.disposeNow();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> 1);
        registry.add("eureka.client.enabled", () -> false);
        registry.add("eureka.client.register-with-eureka", () -> false);
        registry.add("eureka.client.fetch-registry", () -> false);
        registry.add("spring.cloud.config.enabled", () -> false);
        registry.add("management.tracing.enabled", () -> false);
        registry.add("server.ssl.enabled", () -> false);
        registry.add("JWT_SECRET_KEY", () -> "0123456789012345678901234567890123456789012345678901234567890123");
        registry.add("LOCAL_KEY_STORE_PATH", () -> "");
        registry.add("LOCAL_TRUST_STORE_PATH", () -> "");
        registry.add("LOCAL_KEY_STORE_PASSWORD", () -> "");
        registry.add("LOCAL_KEY_PASSWORD", () -> "");
    }

    @TestConfiguration
    static class EchoRoute {
        @Bean
        RouteLocator wsEchoRoute(RouteLocatorBuilder routes) {
            return routes.routes()
                    .route("ws-echo-test", r -> r.path("/ws-echo")
                            .filters(f -> f.setPath("/echo"))
                            .uri("ws://127.0.0.1:" + ECHO.port()))
                    .build();
        }
    }

    @Autowired
    ReactiveWebServerApplicationContext context;

    @Autowired
    RequestUpgradeStrategy upgradeStrategy;

    @Autowired
    WebSocketClient webSocketClient;

    @Autowired
    RouteLocator routeLocator;

    @LocalServerPort
    int port;

    @Test
    @DisplayName("🔴 서버는 Netty — 서블릿 · Tomcat 이 클래스패스에 없다")
    void runsOnNetty() {
        assertThat(context.getWebServer()).isInstanceOf(NettyWebServer.class);
        assertThatThrownBy(() -> Class.forName("org.apache.catalina.startup.Tomcat")).isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName("org.springframework.web.servlet.DispatcherServlet"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    @DisplayName("🔴 WebSocket 업그레이드 · 클라이언트는 Gateway 가 주는 ReactorNetty 구현 — 따로 맞추는 빈이 없다")
    void websocketPartsAreReactorNetty() {
        assertThat(upgradeStrategy).isInstanceOf(ReactorNettyRequestUpgradeStrategy.class);
        assertThat(webSocketClient).isInstanceOf(ReactorNettyWebSocketClient.class);
        assertThat(context.getBeansOfType(RequestUpgradeStrategy.class)).hasSize(1);
        assertThat(context.getBeansOfType(WebSocketClient.class)).hasSize(1);
    }

    @Test
    @DisplayName("YAML 라우트가 실제로 로딩된다 — 서블릿으로 떴다면 Gateway 자동 설정이 꺼져 0개")
    void routesAreLoaded() {
        assertThat(routeLocator.getRoutes().filter(r -> !r.getId().equals("ws-echo-test")).collectList().block())
                .hasSizeGreaterThanOrEqualTo(10);
    }

    @Test
    @DisplayName("🔴 WebSocket 이 게이트웨이를 지나 백엔드까지 오간다(chat STOMP 경로의 바탕)")
    void proxiesWebSocket() {
        AtomicReference<String> reply = new AtomicReference<>();
        new ReactorNettyWebSocketClient().execute(URI.create("ws://127.0.0.1:" + port + "/ws-echo"),
                session -> session.send(Mono.just(session.textMessage("hi")))
                        .thenMany(session.receive().map(WebSocketMessage::getPayloadAsText).take(1).doOnNext(reply::set))
                        .then())
                .block(Duration.ofSeconds(15));
        assertThat(reply.get()).isEqualTo("echo:hi");
    }
}
