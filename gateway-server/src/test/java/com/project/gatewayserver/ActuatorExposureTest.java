package com.project.gatewayserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 게이트웨이 자신의 actuator 는 읽기 넷(health · info · metrics · prometheus)만 — 실제 설정(application-local.yml + 공통)으로 띄워 본다(B-112).
 *
 * <p>게이트웨이에는 Spring Security 가 없어 클러스터 안(같은 네임스페이스 · kube-system — k8s/95)에서는 actuator 에 인증 없이 닿는다.
 * 바깥에서는 터널이 {@code /actuator} 를 게이트웨이로 보내지 않는다(B-82). 2026-11-17 까지 게이트웨이 yml 에 {@code refresh, busrefresh}
 * 가 적혀 있었지만, {@code spring.config.import} 로 읽은 application-common.yml 의 목록이 우선해 실제로는 404 였다(이 시험으로 확인 —
 * 죽은 설정이라 지웠다). 설정을 읽는 순서가 바뀌거나 공통 목록에 쓰기 엔드포인트가 들어오면 여기서 걸린다. 파일 쪽 가드는 common 의 B-112.
 *
 * <p>[JA] ゲートウェイ自身の actuator は読み取りの四つだけ — 実際の設定で起動して refresh などが 404 であることを確かめる。
 */
// Spring Boot 시험은 지표 내보내기(prometheus)를 끈다 — 운영처럼 켠다(추적은 끈 채)
@AutoConfigureObservability(tracing = false)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("B-112: 게이트웨이 actuator — refresh · env · gateway 는 404, 프로브 · 지표는 200")
class ActuatorExposureTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        NettyServerTest.properties(registry);
    }

    @LocalServerPort
    int port;

    private int status(HttpMethod method, String path) {
        return WebClient.create("http://127.0.0.1:" + port).method(method).uri(path)
                .exchangeToMono(r -> r.releaseBody().thenReturn(r.statusCode().value()))
                .block(Duration.ofSeconds(15));
    }

    @Test
    @DisplayName("🔴 쓰기 · 비밀 노출 엔드포인트는 없다 — POST refresh · busrefresh, GET env · configprops · gateway/routes · beans · heapdump")
    void writeAndSecretEndpointsAreNotExposed() {
        assertThat(status(HttpMethod.POST, "/actuator/refresh")).as("POST /actuator/refresh").isEqualTo(404);
        assertThat(status(HttpMethod.POST, "/actuator/busrefresh")).as("POST /actuator/busrefresh").isEqualTo(404);
        for (String path : new String[] {"/actuator/env", "/actuator/configprops", "/actuator/gateway/routes", "/actuator/beans",
                "/actuator/heapdump", "/actuator/loggers"}) {
            assertThat(status(HttpMethod.GET, path)).as("GET " + path).isEqualTo(404);
        }
    }

    @Test
    @DisplayName("읽기 넷은 그대로 — health(k8s/31 의 프로브는 그 아래 — 프로브 그룹은 k8s 에서만 켜진다) · prometheus(지표 에이전트) · info · metrics 는 있다")
    void probesAndMetricsStayExposed() {
        // 시험에는 Redis 가 없어(포트 1) health 는 DOWN = 503 — 여기서 보는 것은 상태가 아니라 '있다'(404 가 아니다)
        assertThat(status(HttpMethod.GET, "/actuator/health")).as("health").isIn(200, 503);
        assertThat(status(HttpMethod.GET, "/actuator/prometheus")).as("prometheus").isEqualTo(200);
        assertThat(status(HttpMethod.GET, "/actuator/info")).as("info").isEqualTo(200);
        assertThat(status(HttpMethod.GET, "/actuator/metrics")).as("metrics").isEqualTo(200);
    }
}
