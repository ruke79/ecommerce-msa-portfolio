package com.project.gatewayserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.config.HttpClientProperties;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;

import io.jsonwebtoken.Jwts;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * B-99: 백엔드 인스턴스 하나가 죽어도(포트가 닫힘) GET 은 다른 인스턴스로 다시 간다 — 연결 실패만, GET 만, 응답 상태는 다시 보내지 않는다.
 *
 * <p>🐛 2026-10-21 실습 8시간(docs/83 §2.8): catalog 파드 하나가 JVM 충돌(SIGSEGV)로 죽고 다시 뜨는 동안 게이트웨이가 Eureka 에 남아 있는 그
 * 인스턴스로 계속 보냈다 — 그 몫(1/3)의 home · banners · 상품이 회로 차단기 fallback 503 이었다. 이 테스트는 실제 라우트(application-local.yml)에
 * 살아 있는 인스턴스 하나와 닫힌 포트 하나를 붙인다(라운드 로빈이라 두 번에 한 번은 닫힌 쪽).
 *
 * <p>[JA] バックエンドのインスタンス一つが死んでも GET は別インスタンスへ送り直す — 接続失敗だけ、GET だけ、応答ステータスは送り直さない。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("B-99: 게이트웨이 — 죽은 인스턴스로의 GET 은 다른 인스턴스로 다시(연결 실패만)")
class RouteRetryTest {

    private static final String SECRET = "0123456789012345678901234567890123456789012345678901234567890123";
    private static final AtomicInteger HOME = new AtomicInteger();
    private static final AtomicInteger BUSY = new AtomicInteger();
    private static final AtomicInteger WRITES = new AtomicInteger();

    /** catalog-service 대역 — 살아 있는 인스턴스. */
    private static final DisposableServer LIVE = HttpServer.create().host("127.0.0.1").port(0)
            .route(r -> r
                    .get("/api/content/home", (req, res) -> {
                        HOME.incrementAndGet();
                        return res.sendString(Mono.just("home"));
                    })
                    // 격벽이 찬 catalog 처럼 503 — 이것은 다시 보내면 안 된다(바쁜 서비스에 같은 요청을 얹는다)
                    .get("/api/busy", (req, res) -> {
                        BUSY.incrementAndGet();
                        return res.status(503).sendString(Mono.just("busy"));
                    })
                    .post("/api/product", (req, res) -> {
                        WRITES.incrementAndGet();
                        return res.sendString(Mono.just("created"));
                    }))
            .bindNow();

    /** 죽은 인스턴스 — 잡았다 놓은 포트(연결 거부, 파드가 다시 뜨는 동안과 같다). */
    private static final int DEAD = freePort();

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @AfterAll
    static void stop() {
        LIVE.disposeNow();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        NettyServerTest.properties(registry);
        registry.add("JWT_SECRET_KEY", () -> SECRET);
        registry.add("spring.cloud.discovery.client.simple.instances.catalog-service[0].uri", () -> "http://127.0.0.1:" + DEAD);
        registry.add("spring.cloud.discovery.client.simple.instances.catalog-service[1].uri", () -> "http://127.0.0.1:" + LIVE.port());
    }

    @LocalServerPort
    int port;

    private String call(String method, String path, boolean auth) {
        WebClient.RequestBodySpec spec = WebClient.create("http://127.0.0.1:" + port).method(org.springframework.http.HttpMethod.valueOf(method))
                .uri(path);
        if (auth) {
            spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + token());
        }
        return spec.exchangeToMono(r -> r.bodyToMono(String.class).defaultIfEmpty("").map(b -> r.statusCode().value() + " " + b))
                .block(Duration.ofSeconds(15));
    }

    private static String token() {
        return Jwts.builder().subject("a@example.com").claim("uid", 7L).claim("role", "ROLE_USER").claim("tokenType", "access")
                .issuedAt(new Date()).expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256")).compact();
    }

    @Test
    @Order(1)
    @DisplayName("🔴 인스턴스 둘 중 하나가 죽어도 GET 10번이 모두 200 — 예전에는 닫힌 쪽으로 간 절반이 fallback 503")
    void getMovesToAnotherInstanceWhenConnectionIsRefused() {
        List<String> results = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            results.add(call("GET", "/catalog-service/api/content/home", false));
        }
        assertThat(results).allMatch("200 home"::equals);
        assertThat(HOME.get()).as("살아 있는 인스턴스가 열 번 다 받았다").isEqualTo(10);
    }

    @Test
    @Order(2)
    @DisplayName("🔴 백엔드가 답한 503(격벽)은 다시 보내지 않는다 — 6번 요청에 백엔드도 6번(Retry 기본값은 5xx 를 다시 보낸다)")
    void serverErrorResponsesAreNotRetried() {
        for (int i = 0; i < 6; i++) {
            assertThat(call("GET", "/catalog-service/api/busy", false)).startsWith("503");
        }
        assertThat(BUSY.get()).isEqualTo(6);
    }

    @Test
    @Order(3)
    @DisplayName("🔴 쓰기(POST)는 다시 보내지 않는다 — 닫힌 쪽으로 간 요청은 그대로 실패(연결 뒤 실패면 두 번 처리될 수 있다)")
    void writesAreNotRetried() {
        int ok = 0;
        for (int i = 0; i < 6; i++) {
            String r = call("POST", "/catalog-service/api/product", true);
            if (r.equals("200 created")) {
                ok++;
            } else {
                assertThat(r).startsWith("503");
            }
        }
        assertThat(ok).as("라운드 로빈 — 닫힌 쪽으로 간 몫은 실패").isLessThan(6).isGreaterThan(0);
        assertThat(WRITES.get()).isEqualTo(ok);
    }

    // ── 설정 가드: 라우트마다 하나 · 마지막(회로 차단기 안쪽) · GET · 연결 실패만 ─────────────────────────────

    private static GatewayProperties gateway() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        new YamlPropertySourceLoader().load("application-local", new ClassPathResource("application-local.yml"))
                .forEach(env.getPropertySources()::addLast);
        return new Binder(ConfigurationPropertySources.get(env), new PropertySourcesPlaceholdersResolver(env))
                .bind("spring.cloud.gateway.server.webflux", Bindable.of(GatewayProperties.class)).get();
    }

    @Test
    @DisplayName("🔴 WebSocket 밖의 모든 라우트에 Retry 하나 — 마지막 필터(회로 차단기 안쪽) · GET 만 · 응답 상태 없음 · 연결 예외만")
    void everyRouteRetriesConnectFailuresOnly() throws Exception {
        GatewayProperties g = gateway();
        assertThat(g.getDefaultFilters()).extracting(FilterDefinition::getName).doesNotContain("Retry");
        for (RouteDefinition r : g.getRoutes()) {
            List<FilterDefinition> retries = r.getFilters().stream().filter(f -> "Retry".equals(f.getName())).toList();
            if (r.getId().equals("chat-service-ws")) {
                assertThat(retries).as("WebSocket — 장수명 연결").isEmpty();
                continue;
            }
            assertThat(retries).as(r.getId()).hasSize(1);
            assertThat(r.getFilters().get(r.getFilters().size() - 1).getName()).as(r.getId() + " — 마지막(회로 차단기 안쪽)").isEqualTo("Retry");
            Map<String, String> args = retries.get(0).getArgs();
            assertThat(args.get("methods")).as(r.getId()).isEqualTo("GET");
            assertThat(args.get("series")).as(r.getId() + " — 비우지 않으면 5xx 를 다시 보낸다").isEmpty();
            assertThat(args.get("statuses")).as(r.getId()).isEmpty();
            assertThat(args.get("exceptions").split(",\\s*")).as(r.getId() + " — 시간 초과(IOException · TimeoutException)는 아니다")
                    .containsExactlyInAnyOrder("java.net.ConnectException", "java.net.NoRouteToHostException");
            assertThat(Integer.parseInt(args.get("retries"))).as(r.getId()).isBetween(1, 2);
        }
    }

    @Test
    @DisplayName("🔴 연결 시간 상한은 회로 차단기(10초)보다 훨씬 짧다 — 기본 45초면 사라진 파드 IP 에서 Retry 가 돌 틈이 없다")
    void connectTimeoutIsShort() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        new YamlPropertySourceLoader().load("application-local", new ClassPathResource("application-local.yml"))
                .forEach(env.getPropertySources()::addLast);
        HttpClientProperties http = new Binder(ConfigurationPropertySources.get(env), new PropertySourcesPlaceholdersResolver(env))
                .bind("spring.cloud.gateway.server.webflux.httpclient", Bindable.of(HttpClientProperties.class)).get();
        assertThat(http.getConnectTimeout()).isNotNull().isLessThanOrEqualTo(3000);
    }
}
