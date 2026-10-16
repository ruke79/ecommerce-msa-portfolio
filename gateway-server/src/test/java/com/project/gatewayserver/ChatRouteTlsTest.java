package com.project.gatewayserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.FileInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.KeyManagerFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;

import io.jsonwebtoken.Jwts;
import io.netty.handler.ssl.SslContextBuilder;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * 게이트웨이의 chat 라우트(실제 application-local.yml) — TLS 로 뜬 chat-service 에 SockJS 정보 요청 · REST · WebSocket 이 모두 닿는다.
 *
 * <p>⚠️ 예전: 두 라우트의 uri 가 {@code lb:ws://chat-service} 였다. 게이트웨이는 인스턴스가 알리는 스킴을 먼저 쓴다
 * ({@code DelegatingServiceInstance.getScheme}) — Eureka 인스턴스는 https 를 알려 운영에서는 {@code ws} 가 무시되고 동작했다. 스킴을
 * 알리지 않는 디스커버리(이 테스트의 simple)에서는 {@code ws} 가 쓰여 모든 요청이 WebSocket 핸드셰이크가 되었다 — 일반 GET 400,
 * POST 405, WebSocket 은 TLS 포트에 <b>평문</b>. {@code lb://} 는 디스커버리와 상관없이 https · wss 다. 이 테스트는 스킴을 모르는
 * 인스턴스로 그 차이를 드러낸다.
 *
 * <p>백엔드 대역은 테스트가 만든 자체 서명 인증서로 TLS 를 켜고, 게이트웨이는 그 인증서만 신뢰한다(검증을 끄지 않는다).
 *
 * <p>[JA] ゲートウェイの chat ルート(実際の yml) — TLS の chat-service に SockJS 情報要求・REST・WebSocket がすべて届く。以前の
 * lb:ws:// は運用(Eureka が https を知らせる)では動いたが、スキームを知らせないディスカバリーでは GET 400 · POST 405 · 平文だった。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("게이트웨이 chat 라우트 — TLS 백엔드에 HTTP · WebSocket")
class ChatRouteTlsTest {

    private static final String PASSWORD = "changeit-test";
    private static final String SECRET = "0123456789012345678901234567890123456789012345678901234567890123";
    private static final Path DIR;
    private static final DisposableServer CHAT;

    static {
        try {
            DIR = Files.createTempDirectory("chat-route-tls");
            keytool("-genkeypair", "-alias", "chat", "-keyalg", "RSA", "-keysize", "2048", "-storetype", "PKCS12",
                    "-keystore", DIR.resolve("keystore.p12").toString(), "-storepass", PASSWORD, "-keypass", PASSWORD,
                    "-dname", "CN=chat-service", "-ext", "SAN=ip:127.0.0.1,dns:localhost", "-validity", "2");
            keytool("-exportcert", "-alias", "chat", "-keystore", DIR.resolve("keystore.p12").toString(),
                    "-storepass", PASSWORD, "-rfc", "-file", DIR.resolve("chat.crt").toString());

            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (FileInputStream in = new FileInputStream(DIR.resolve("keystore.p12").toFile())) {
                ks.load(in, PASSWORD.toCharArray());
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, PASSWORD.toCharArray());
            io.netty.handler.ssl.SslContext serverTls = SslContextBuilder.forServer(kmf).build();

            // chat-service 대역 — TLS 로만 받는다(운영 8086 과 같다)
            CHAT = HttpServer.create().host("127.0.0.1").port(0)
                    .secure(spec -> spec.sslContext(serverTls))
                    .route(r -> r
                            .get("/chat/info", (req, res) -> res.sendString(Mono.just("{\"websocket\":true}")))
                            .ws("/chat/probe/websocket", (in, out) -> out.sendString(in.receive().asString().map(s -> "echo:" + s)))
                            .get("/api/chat/rooms", (req, res) -> res.sendString(Mono.just("rooms:" + req.requestHeaders().get("User-Id"))))
                            .post("/api/chat/room", (req, res) -> res.sendString(Mono.just("created"))))
                    .bindNow();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static void keytool(String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new IllegalStateException("keytool 실패: " + out);
        }
    }

    @AfterAll
    static void stop() {
        CHAT.disposeNow();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        NettyServerTest.properties(registry);
        registry.add("JWT_SECRET_KEY", () -> SECRET);
        // 게이트웨이 HTTP 클라이언트가 이 인증서만 더 신뢰한다(운영은 JVM 트러스트스토어 = 사설 CA). 검증은 끄지 않는다.
        registry.add("spring.cloud.gateway.server.webflux.httpclient.ssl.trusted-x509-certificates[0]",
                () -> "file:" + DIR.resolve("chat.crt"));
        // 운영의 Eureka 등록처럼 secure 인스턴스(https)
        registry.add("spring.cloud.discovery.client.simple.instances.chat-service[0].uri",
                () -> "https://127.0.0.1:" + CHAT.port());
    }

    @LocalServerPort
    int port;

    private WebClient client() {
        return WebClient.create("http://127.0.0.1:" + port);
    }

    private static String token() {
        return Jwts.builder().subject("a@example.com").claim("uid", 7L).claim("role", "ROLE_USER").claim("tokenType", "access")
                .issuedAt(new Date()).expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256")).compact();
    }

    private static String statusAndBody(WebClient.RequestHeadersSpec<?> spec) {
        return spec.exchangeToMono(r -> r.bodyToMono(String.class).defaultIfEmpty("").map(b -> r.statusCode().value() + " " + b))
                .block(Duration.ofSeconds(15));
    }

    @Test
    @DisplayName("🔴 SockJS 의 첫 요청(GET /chat-service/chat/info)이 https 로 chat-service 에 닿는다 — 예전에는 400")
    void sockJsInfoReachesChat() {
        assertThat(statusAndBody(client().get().uri("/chat-service/chat/info"))).isEqualTo("200 {\"websocket\":true}");
    }

    @Test
    @DisplayName("🔴 채팅 REST(GET · POST)가 https 로 닿고 신원 헤더가 실린다 — 예전에는 400 · 405")
    void restReachesChat() {
        assertThat(statusAndBody(client().get().uri("/chat-service/api/chat/rooms")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token()))).isEqualTo("200 rooms:7");
        assertThat(statusAndBody(client().post().uri("/chat-service/api/chat/room")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token()))).isEqualTo("200 created");
    }

    @Test
    @DisplayName("🔴 WebSocket 이 wss 로 chat-service 까지 오간다 — 예전에는 TLS 포트에 평문 ws")
    void websocketReachesChatOverTls() {
        AtomicReference<String> reply = new AtomicReference<>();
        new ReactorNettyWebSocketClient().execute(URI.create("ws://127.0.0.1:" + port + "/chat-service/chat/probe/websocket"),
                session -> session.send(Mono.just(session.textMessage("hi")))
                        .thenMany(session.receive().map(WebSocketMessage::getPayloadAsText).take(1).doOnNext(reply::set))
                        .then())
                .block(Duration.ofSeconds(15));
        assertThat(reply.get()).isEqualTo("echo:hi");
    }
}
