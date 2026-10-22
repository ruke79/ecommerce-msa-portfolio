package com.project.gatewayserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreaker;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.micrometer.core.instrument.MeterRegistry;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 게이트웨이 라우트의 동시 호출 상한(벌크헤드)이 설정값으로 걸리는가 — 실제 컨텍스트의 회로 차단기 팩토리로 동시 호출을 걸어 센다.
 *
 * <p>🐛 2026-10-18 부하 시험(docs/83 §5): 설정에 벌크헤드가 없어 Spring Cloud CircuitBreaker 가 resilience4j 기본값(동시 25 · 대기 0)을
 * 그대로 썼고, catalog 가 느려지자 파드당 26번째 호출부터 {@code BulkheadFullException} → fallback 503 이었다. 이제 값은 설정에 있고
 * (application-local.yml 의 resilience4j.bulkhead — 25 와 100 을 재서 25), GATEWAY_BULKHEAD_MAX_CONCURRENT 로 바꿀 수 있다.
 * 기본값이 resilience4j 의 기본값과 같으니, 이 테스트는 변수로 40 을 주고 실제로 40 이 걸리는지 본다 — 설정 키가 틀려도 ·
 * 다른 레지스트리를 써도 · 설정을 지워도(그러면 25) 실패한다. 기본값 25 는 설정 파일을 읽어 따로 본다.
 *
 * <p>[JA] ゲートウェイのルートの同時呼び出し上限が設定値で掛かるか — 実際のコンテキストのファクトリで同時呼び出しを掛けて数える。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("게이트웨이 — 동시 호출 상한(벌크헤드)은 설정값")
class GatewayBulkheadTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        NettyServerTest.properties(registry);
        registry.add("GATEWAY_BULKHEAD_MAX_CONCURRENT", () -> "40");   // 기본값(25)과 다른 값 — 설정이 실제로 이어졌는지 보려고
    }

    @Autowired
    ReactiveCircuitBreakerFactory<?, ?> factory;

    @Autowired
    MeterRegistry meters;

    /** 이름이 같은 회로 차단기로 calls 개를 동시에 걸어 — 각 호출은 300ms 동안 슬롯을 쥔다 — 벌크헤드에 막힌 수를 센다. */
    private long rejected(String name, int calls) {
        ReactiveCircuitBreaker cb = factory.create(name);
        List<String> results = Flux.fromStream(IntStream.range(0, calls).boxed())
                .flatMap(i -> cb.run(Mono.delay(Duration.ofMillis(300)).map(x -> "ok"),
                        // resilience4j-bulkhead 는 런타임에만 있다(테스트 컴파일 경로 밖) — 이름으로 본다
                        t -> Mono.just("BulkheadFullException".equals(t.getClass().getSimpleName()) ? "bulkhead" : "other:" + t.getClass().getSimpleName())),
                        calls)
                .collectList()
                .block(Duration.ofSeconds(20));
        assertThat(results).hasSize(calls).noneMatch(r -> r.startsWith("other:"));
        return results.stream().filter("bulkhead"::equals).count();
    }

    @Test
    @DisplayName("🔴 라우트의 회로 차단기 이름(catalog)으로 설정한 상한(여기서는 40)까지는 막지 않는다 — 설정이 안 이어지면 25 라 15개가 막힌다")
    void routeCircuitBreakerAllowsConfiguredConcurrency() {
        assertThat(rejected("catalogServiceCircuitBreaker", 40)).isZero();
    }

    @Test
    @DisplayName("벌크헤드 상태가 지표로 나온다 — 부하 시험에서 남은 슬롯 · 상한을 Grafana 로 본다")
    void bulkheadIsObservable() {
        rejected("orderServiceCircuitBreaker", 5);
        // Prometheus 이름: resilience4j_bulkhead_max_allowed_concurrent_calls · resilience4j_bulkhead_available_concurrent_calls{name=…}
        assertThat(meters.get("resilience4j.bulkhead.max.allowed.concurrent.calls").tag("name", "orderServiceCircuitBreaker").gauge().value())
                .isEqualTo(40.0);
        assertThat(meters.get("resilience4j.bulkhead.available.concurrent.calls").tag("name", "orderServiceCircuitBreaker").gauge().value())
                .as("호출이 끝나면 슬롯이 돌아온다").isEqualTo(40.0);
    }

    @Test
    @DisplayName("🔴 상한을 넘으면 기다리지 않고 바로 막는다(대기 0) — 48개 중 8개")
    void overTheLimitIsRejectedImmediately() {
        assertThat(rejected("cartServiceCircuitBreaker", 48)).isEqualTo(8);
    }

    @Test
    @DisplayName("🔴 기본값은 25 · 대기 0 — 재서 정한 값(100 은 p95 267 → 814ms 로 나빴다, docs/83 §5)")
    void defaultIsTheMeasuredValue() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        new YamlPropertySourceLoader().load("application-local", new ClassPathResource("application-local.yml"))
                .forEach(env.getPropertySources()::addLast);
        assertThat(env.getProperty("resilience4j.bulkhead.configs.default.max-concurrent-calls")).isEqualTo("25");
        assertThat(env.getProperty("resilience4j.bulkhead.configs.default.max-wait-duration")).isEqualTo("0ms");
    }
}
