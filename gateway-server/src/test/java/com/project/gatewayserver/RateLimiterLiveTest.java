package com.project.gatewayserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import reactor.core.publisher.Flux;

/**
 * 유입 제한이 실제로 429 를 내는가 — 실제 게이트웨이 설정 · 실제 Redis(비밀번호), 기동 직후 동시 70발(배포 스모크 ops/06 3번과 같은 모양).
 *
 * <p>🐛 2026-10-24 운영 배포: 스모크 3번이 429 를 하나도 받지 못해 자동 복귀했다. Redis 명령 타임아웃(500ms, B-84)을 넘긴 명령은
 * 통과(fail-open)인데, 막 뜬 게이트웨이의 첫 명령들이 연결 · AUTH · 스크립트 적재를 함께 치렀다. 이 기계에서도 데우기 없이는
 * 첫 70발 중 57발이 지나갔다(버스트 40) — 이제 readiness 전에 데운다({@code RateLimiterWarmup}).
 *
 * <p>[JA] 流入制限が実際に 429 を返すか。2026-10-24 の本番スモークで 429 が出ず自動復帰した — 起動直後の最初の Redis コマンドが
 * タイムアウト(500ms)を超えて通過していた。今は readiness の前に温める。
 */
@Tag("containers")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("게이트웨이 유입 제한 — 실제 Redis 에서 기동 직후 동시 70발")
class RateLimiterLiveTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379).withCommand("redis-server", "--requirepass", "testpw");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        NettyServerTest.properties(registry);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("REDIS_PASSWORD", () -> "testpw");
    }

    @LocalServerPort
    int port;

    @Autowired
    ReactiveStringRedisTemplate template;

    @Autowired
    @Qualifier("redisRequestRateLimiterScript")
    RedisScript<List<Long>> script;

    @Test
    @Order(1)
    @DisplayName("🔴 손님 요청이 오기 전(기동 중)에 스크립트가 이미 Redis 에 올라가 있다 — 첫 요청이 적재를 떠안지 않는다")
    void scriptIsLoadedBeforeTheFirstRequest() {
        List<Boolean> exists = template.execute(c -> c.scriptingCommands()
                        .scriptExists(List.of(script.getSha1())))
                .collectList().block(Duration.ofSeconds(10));
        assertThat(exists).as("SCRIPT EXISTS " + script.getSha1()).containsExactly(true);
        assertThat(template.hasKey("request_rate_limiter.{gateway-warmup}.tokens").block(Duration.ofSeconds(10)))
                .as("데우기는 손님과 겹치지 않는 키를 쓴다").isTrue();
    }

    @Test
    @Order(2)
    @DisplayName("🔴 기동 직후 같은 손님의 동시 70발 — 버스트 40 을 넘는 몫은 429")
    void burstIsLimited() {
        WebClient client = WebClient.create("http://127.0.0.1:" + port);
        List<Integer> codes = Flux.range(0, 70)
                .flatMap(i -> client.get().uri("/catalog-service/api/category/categories")
                        .exchangeToMono(r -> r.releaseBody().thenReturn(r.statusCode().value())), 70)
                .collectList().block(Duration.ofSeconds(60));
        Map<Integer, Long> byCode = codes.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        assertThat(byCode).as("응답 코드별 수 " + byCode).containsKey(429);
    }
}
