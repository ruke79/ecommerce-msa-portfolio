package com.project.userservice.security.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.project.userservice.security.risk.LoginRiskGuard.Signal;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** 🔴 위험 신호 · 메일 확인 코드 — <b>실제 Redis</b>(집합 · 해시 · 만료, docs/68 §19). */
@Tag("containers")
@Testcontainers
@DisplayName("위험 기반 로그인 (실제 Redis)")
class LoginRiskGuardTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379).withCommand("redis-server", "--requirepass", "testpw");

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate template;
    private LoginRiskGuard guard;

    @BeforeAll
    static void connect() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379));
        config.setPassword("testpw");
        factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        factory.start();
        template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
    }

    @AfterAll
    static void close() {
        factory.destroy();
    }

    @BeforeEach
    void setUp() {
        template.getConnectionFactory().getConnection().serverCommands().flushAll();
        guard = new LoginRiskGuard(template, new SimpleMeterRegistry(), "risk-test-secret-risk-test-secret-0123456", 3, 2, 5);
    }

    @Test
    @DisplayName("🔴 IP 팬아웃 — 한 IP 에서 서로 다른 계정 3개가 틀리면 신호(같은 계정 반복은 하나로 센다)")
    void ipFanout() {
        guard.recordFailure("a@example.com", "203.0.113.7");
        guard.recordFailure("a@example.com", "203.0.113.7");
        guard.recordFailure("b@example.com", "203.0.113.7");
        assertThat(guard.signals("victim@example.com", "203.0.113.7")).doesNotContain(Signal.IP_FANOUT);
        guard.recordFailure("C@Example.com ", "203.0.113.7");
        assertThat(guard.signals("victim@example.com", "203.0.113.7")).contains(Signal.IP_FANOUT);
        assertThat(guard.signals("victim@example.com", "198.51.100.1")).as("다른 IP 는 상관없다").doesNotContain(Signal.IP_FANOUT);
    }

    @Test
    @DisplayName("🔴 표적 계정 — 한 계정이 서로 다른 IP 2곳에서 틀리면 신호")
    void targetedAccount() {
        guard.recordFailure("victim@example.com", "203.0.113.7");
        assertThat(guard.signals("victim@example.com", "198.51.100.9")).doesNotContain(Signal.TARGETED_ACCOUNT);
        guard.recordFailure("Victim@example.com", "198.51.100.1");
        assertThat(guard.signals("victim@example.com", "198.51.100.9")).contains(Signal.TARGETED_ACCOUNT);
    }

    @Test
    @DisplayName("전체 급증 — 최근 실패 수가 문턱을 넘으면 모든 로그인에 신호")
    void globalSurge() {
        for (int i = 0; i < 4; i++) {
            guard.recordFailure("u" + i + "@example.com", "10.0.0." + i);
        }
        assertThat(guard.signals("x@example.com", "10.9.9.9")).doesNotContain(Signal.GLOBAL_SURGE);
        guard.recordFailure("u9@example.com", "10.0.0.9");
        assertThat(guard.signals("x@example.com", "10.9.9.9")).contains(Signal.GLOBAL_SURGE);
    }

    @Test
    @DisplayName("🔴 기록한 세 키에는 반드시 만료가 붙는다 — 없으면 그 신호가 영영 켜진 채로 남는다")
    void everyRecordedKeyExpires() {
        guard.recordFailure("kim@example.com", "203.0.113.9");

        var keys = template.keys("risk:*");
        // IP 집합 · 계정 집합 · 분 카운터 셋.
        assertThat(keys).hasSize(3);
        // 🐛 SADD 와 EXPIRE 가 따로였을 때는 그 사이에 끊기면 만료 없는 집합이 남아, 아무 일 없는 사용자에게 계속 확인을 요구했다.
        assertThat(keys).allSatisfy(k -> assertThat(template.getExpire(k))
                .as("%s 의 TTL", k).isPositive());
    }

    @Test
    @DisplayName("기록을 거듭하면 창을 다시 건다 — 공격이 이어지는 동안 신호가 유지된다")
    void recordingAgainRefreshesTheWindow() {
        guard.recordFailure("kim@example.com", "203.0.113.9");
        String ipKey = template.keys("risk:ip:*").iterator().next();
        template.expire(ipKey, java.time.Duration.ofSeconds(1));

        guard.recordFailure("lee@example.com", "203.0.113.9");

        assertThat(template.getExpire(ipKey)).isGreaterThan(60L);
    }

    @Test
    @DisplayName("🔒 Redis 에 이메일 · IP 원문이 없다 — 키도 값도 HMAC 뿐")
    void noRawPii() {
        guard.recordFailure("victim@example.com", "203.0.113.7");
        Set<String> keys = template.keys("*");
        assertThat(keys).isNotEmpty();
        for (String k : keys) {
            assertThat(k).doesNotContain("victim").doesNotContain("203.0.113.7");
            if (k.startsWith(LoginRiskGuard.IP_KEY) || k.startsWith(LoginRiskGuard.ACCOUNT_KEY)) {
                assertThat(template.opsForSet().members(k)).allSatisfy(v ->
                        assertThat(v).doesNotContain("victim").doesNotContain("203.0.113"));
            }
        }
    }

    @Test
    @DisplayName("🔴 확인 코드 — 맞으면 한 번만 통과, 코드는 원문으로 저장하지 않는다")
    void challengeOnce() {
        var issued = guard.issue(42L, List.of(Signal.IP_FANOUT));
        String pending = issued.issued().cookieValue();
        assertThat(issued.code()).matches("\\d{6}");
        for (String k : template.keys(LoginRiskGuard.CHALLENGE_KEY + "*")) {
            assertThat(template.opsForHash().values(k)).noneMatch(v -> v.equals(issued.code()));
            assertThat(k).doesNotContain(pending);
        }

        assertThat(guard.verify(pending, issued.code())).isEqualTo(new LoginRiskGuard.Passed(42L));
        assertThat(guard.verify(pending, issued.code())).as("두 번째는 없다").isInstanceOf(LoginRiskGuard.Expired.class);
    }

    @Test
    @DisplayName("🔴 틀린 코드는 5번까지 — 다섯 번째에 대기 값이 사라지고 맞는 코드도 받지 않는다")
    void attemptsLimited() {
        var issued = guard.issue(42L, List.of(Signal.GLOBAL_SURGE));
        String pending = issued.issued().cookieValue();
        String wrong = issued.code().equals("000000") ? "111111" : "000000";
        for (int i = 1; i <= 4; i++) {
            assertThat(guard.verify(pending, wrong)).isEqualTo(new LoginRiskGuard.Wrong(true));
        }
        assertThat(guard.verify(pending, wrong)).isEqualTo(new LoginRiskGuard.Wrong(false));
        assertThat(guard.verify(pending, issued.code())).isInstanceOf(LoginRiskGuard.Expired.class);
    }

    @Test
    @DisplayName("다른 대기 값의 코드로는 통과하지 못한다 · 빈 값 · 모르는 값은 만료")
    void codeBoundToPending() {
        var a = guard.issue(1L, List.of(Signal.IP_FANOUT));
        var b = guard.issue(2L, List.of(Signal.IP_FANOUT));
        if (!a.code().equals(b.code())) {
            assertThat(guard.verify(a.issued().cookieValue(), b.code())).isInstanceOf(LoginRiskGuard.Wrong.class);
        }
        assertThat(guard.verify(null, "123456")).isInstanceOf(LoginRiskGuard.Expired.class);
        assertThat(guard.verify("nope", "123456")).isInstanceOf(LoginRiskGuard.Expired.class);
    }

    @Test
    @DisplayName("🔴 사용자별 한도 — 다시 로그인해 새 대기 값을 받아도 1시간에 틀린 코드 10번까지")
    void userLimitAcrossPendings() {
        var first = guard.issue(77L, List.of(Signal.IP_FANOUT));
        String wrong1 = first.code().equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 5; i++) {
            guard.verify(first.issued().cookieValue(), wrong1);
        }
        var second = guard.issue(77L, List.of(Signal.IP_FANOUT));
        String wrong2 = second.code().equals("000000") ? "111111" : "000000";
        for (int i = 1; i <= 4; i++) {
            assertThat(guard.verify(second.issued().cookieValue(), wrong2)).isEqualTo(new LoginRiskGuard.Wrong(true));
        }
        // 열 번째 — 대기 값당으로는 5번째지만 사용자 한도에 닿았다.
        assertThat(guard.verify(second.issued().cookieValue(), wrong2)).isEqualTo(new LoginRiskGuard.Wrong(false));

        // 세 번째 로그인 — 맞는 코드도 받지 않는다.
        var third = guard.issue(77L, List.of(Signal.IP_FANOUT));
        assertThat(guard.verify(third.issued().cookieValue(), third.code())).isInstanceOf(LoginRiskGuard.Expired.class);
        // 다른 사용자는 영향 없다.
        var other = guard.issue(78L, List.of(Signal.IP_FANOUT));
        assertThat(guard.verify(other.issued().cookieValue(), other.code())).isEqualTo(new LoginRiskGuard.Passed(78L));
    }

    /**
     * 🐛 예전: tries 를 읽고 → 비교하고 → 틀리면 더했다 — 동시에 보낸 요청이 모두 0 을 읽어 6자리를 수백 개 대입할 수 있었다.
     * 🐛 그 다음: 세기는 INCR 로 했지만, 다섯 번째 실패가 키를 지운 <b>뒤에</b> 도착한 요청의 HINCRBY 가 tries 만 든 새 해시를
     *    만들어(만료도 없이) 다시 1 부터 셌다 — 비교가 5번을 넘었고 그 키는 영원히 남았다. 여러 판 돌려 그 틈을 잡는다.
     */
    @Test
    @DisplayName("🔴 동시에 40번 틀린 코드를 보내도 비교까지 가는 것은 대기 값당 5번뿐 — 지운 대기 값이 되살아나지 않는다")
    void concurrentWrongCodesAreCapped() throws Exception {
        for (int round = 0; round < 10; round++) {
            long uid = 4200L + round;   // 사용자 칸(MAX_USER_FAILURES)이 판 사이에 섞이지 않게
            var issued = guard.issue(uid, List.of(Signal.IP_FANOUT));
            String pending = issued.issued().cookieValue();
            String wrong = issued.code().equals("000000") ? "111111" : "000000";

            int threads = 40;
            var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
            var start = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.Future<LoginRiskGuard.Verification>> results = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return guard.verify(pending, wrong);
                }));
            }
            start.countDown();
            long compared = 0;
            for (var f : results) {
                if (f.get() instanceof LoginRiskGuard.Wrong) {
                    compared++;
                }
            }
            pool.shutdown();
            assertThat(compared).as("판 %d", round).isLessThanOrEqualTo(LoginRiskGuard.MAX_ATTEMPTS);
            assertThat(guard.verify(pending, issued.code())).isNotInstanceOf(LoginRiskGuard.Passed.class);
            // 지운 대기 값이 tries 만 든 채(만료 없이) 되살아나지 않았다.
            for (String k : template.keys(LoginRiskGuard.CHALLENGE_KEY + "*")) {
                assertThat(template.getExpire(k)).as("만료 없는 대기 값 " + k).isPositive();
            }
        }
    }
}
