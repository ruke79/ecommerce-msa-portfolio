package com.project.gatewayserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * SLO {@code gateway}(docs/91 §2-7)의 SLI — 게이트웨이가 라우트마다 요청 타이머 {@code spring.cloud.gateway.requests} 를 남기고,
 * 그 타이머에 규칙이 읽는 2초 경계 버킷이 있다.
 *
 * <p>Spring Cloud Gateway 4.3 은 이 타이머가 기본 꺼짐이다({@code spring.cloud.gateway.server.webflux.metrics.enabled}) — 꺼져 있으면
 * {@code spring_cloud_gateway_requests_seconds} 가 없어 SLO gateway 는 조용히 비고, 경보는 요청 하한에 걸려 울리지 않는다.
 * 설정 키를 틀려도(옛 위치 · 오타) 실패한다. 규칙 쪽(경계 · 빼는 라우트)은 common 의 {@code SloRulesTest}, 경보는 promtool 시험.
 *
 * <p>[JA] SLO gateway の SLI — ゲートウェイがルートごとのタイマーを残し、ルールが読む 2 秒境界のバケットがある(4.3 は既定で無効)。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("SLO gateway — 라우트 타이머(spring.cloud.gateway.requests)와 2초 경계 버킷")
class GatewayRouteMetricsTest {

    /** catalog-service 대역 — 200 과, 격벽이 찬 catalog 처럼 503. */
    private static final DisposableServer CATALOG = HttpServer.create().host("127.0.0.1").port(0)
            .route(r -> r
                    .get("/api/content/home", (req, res) -> res.sendString(Mono.just("home")))
                    .get("/api/busy", (req, res) -> res.status(503).sendString(Mono.just("busy"))))
            .bindNow();

    @AfterAll
    static void stop() {
        CATALOG.disposeNow();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        NettyServerTest.properties(registry);
        registry.add("spring.cloud.discovery.client.simple.instances.catalog-service[0].uri", () -> "http://127.0.0.1:" + CATALOG.port());
    }

    @LocalServerPort
    int port;

    @Autowired
    MeterRegistry meters;

    private int get(String path) {
        return WebClient.create("http://127.0.0.1:" + port).get().uri(path)
                .exchangeToMono(r -> r.releaseBody().thenReturn(r.statusCode().value()))
                .block(Duration.ofSeconds(15));
    }

    private Timer routeTimer(String routeId, String status) {
        return meters.find("spring.cloud.gateway.requests").tag("routeId", routeId).tag("httpStatusCode", status).timer();
    }

    @Test
    @DisplayName("🔴 라우트를 지난 요청이 routeId · httpStatusCode 로 세어진다 — 백엔드의 503 도(규칙의 5xx)")
    void routeRequestsAreTimedByRouteAndStatus() {
        long okBefore = count("catalog-service", "200");
        long busyBefore = count("catalog-service", "503");
        assertThat(get("/catalog-service/api/content/home")).isEqualTo(200);
        assertThat(get("/catalog-service/api/busy")).isEqualTo(503);

        assertThat(routeTimer("catalog-service", "200"))
                .as("spring.cloud.gateway.requests{routeId=catalog-service,httpStatusCode=200} — 꺼져 있으면 없다").isNotNull();
        assertThat(count("catalog-service", "200") - okBefore).isEqualTo(1);
        assertThat(routeTimer("catalog-service", "503")).as("503 도 같은 타이머에 — SLO gateway 의 오류 SLI 는 httpStatusCode=~\"5..\"").isNotNull();
        assertThat(count("catalog-service", "503") - busyBefore).isEqualTo(1);

        // 게이트웨이의 http.server.requests 는 라우트를 모른다 — 라우트 요청의 uri 는 모두 UNKNOWN(그래서 라우트 타이머가 필요하다)
        assertThat(meters.find("http.server.requests").tag("status", "503").timers())
                .isNotEmpty().allMatch(t -> "UNKNOWN".equals(t.getId().getTag("uri")));
    }

    private long count(String routeId, String status) {
        Timer t = routeTimer(routeId, status);
        return t == null ? 0 : t.count();
    }

    @Test
    @DisplayName("🔴 라우트 타이머에 2초 경계 버킷이 있다(application-common.yml 의 slo) — 규칙의 le=\"2.0\"")
    void routeTimerHasTheSloBoundaryBucket() {
        assertThat(get("/catalog-service/api/content/home")).isEqualTo(200);
        Timer ok = routeTimer("catalog-service", "200");
        assertThat(ok).isNotNull();
        double[] boundariesSeconds = Arrays.stream(ok.takeSnapshot().histogramCounts())
                .mapToDouble(b -> b.bucket(TimeUnit.SECONDS)).toArray();
        assertThat(boundariesSeconds).as("경계(초)").contains(2.0);
        CountAtBucket within2s = Arrays.stream(ok.takeSnapshot().histogramCounts())
                .filter(b -> b.bucket(TimeUnit.SECONDS) == 2.0).findFirst().orElseThrow();
        assertThat(within2s.count()).as("빠른 요청은 2초 버킷 안에 센다").isEqualTo(ok.count());
    }
}
