package com.project.userservice.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.LockedException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 로그인 실패 잠금 — 실제 Redis.
 *
 * <p>🐛 예전: DB 에서 읽고 → 비교하고 → 틀리면 더했다(버전 없는 읽기-고치기-쓰기) — 동시에 보낸 요청이 서로의 증가를 덮어 횟수가 거의
 * 늘지 않았다. 그리고 없는 계정은 세지 않아, 여섯 번째의 잠금 문구가 계정이 있다는 표시가 됐다.
 *
 * <p>[JA] ログイン失敗ロック — 実 Redis。以前は DB の読む → 比較 → 加算で同時リクエストが増分を失い、存在しないアカウントは数えなかった。
 */
@Tag("containers")
@Testcontainers
@DisplayName("로그인 실패 잠금 (실제 Redis)")
class LoginAttemptServiceTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379).withCommand("redis-server", "--requirepass", "testpw");

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate template;
    private LoginAttemptService service;

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
        service = new LoginAttemptService(template, 5, Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("다섯 번은 시도하고, 여섯 번째는 잠금 — 문구는 남은 시간만")
    void locksAfterTheLimit() {
        for (int i = 0; i < 5; i++) {
            service.reserveAttempt("kim@example.com");
            service.onFailure("kim@example.com");
        }
        assertThatThrownBy(() -> service.reserveAttempt("kim@example.com"))
                .isInstanceOf(LockedException.class).hasMessageContaining("분 후");
        // 잠긴 동안에는 계속 잠금(맞는 비밀번호를 넣을 기회도 없다)
        assertThatThrownBy(() -> service.reserveAttempt("KIM@example.com ")).isInstanceOf(LockedException.class);
    }

    @Test
    @DisplayName("맞으면 카운터가 지워진다 — 네 번 틀리고 맞힌 사람은 다시 다섯 번")
    void successResets() {
        for (int i = 0; i < 4; i++) {
            service.reserveAttempt("kim@example.com");
        }
        service.onSuccess("kim@example.com");
        for (int i = 0; i < 5; i++) {
            service.reserveAttempt("kim@example.com");
        }
        assertThatThrownBy(() -> service.reserveAttempt("kim@example.com")).isInstanceOf(LockedException.class);
    }

    @Test
    @DisplayName("🔴 없는 계정도 똑같이 잠긴다 — 여섯 번째의 잠금 문구가 '계정이 있다' 는 표시가 되지 않게")
    void unknownAccountsLockTheSame() {
        for (int i = 0; i < 5; i++) {
            service.reserveAttempt("ghost@example.com");
        }
        assertThatThrownBy(() -> service.reserveAttempt("ghost@example.com")).isInstanceOf(LockedException.class);
    }

    @Test
    @DisplayName("🔴 동시에 50번 보내도 비밀번호까지 가는 것은 다섯 번뿐 — 읽고 더하는 순서면 모두 통과했다")
    void concurrentAttemptsAreCapped() throws Exception {
        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    service.reserveAttempt("kim@example.com");
                    return true;
                } catch (LockedException e) {
                    return false;
                }
            }));
        }
        start.countDown();
        int passed = 0;
        for (Future<Boolean> f : results) {
            if (f.get()) {
                passed++;
            }
        }
        pool.shutdown();
        assertThat(passed).isEqualTo(5);
    }

    @Test
    @DisplayName("🔴 잠금은 저절로 풀린다 — 영구 잠금은 남의 계정을 잠그는 DoS 버튼이 된다")
    void lockExpiresOnItsOwn() throws Exception {
        // 창을 400ms 로 줄인 서비스 — 15분을 기다릴 수 없다(동작은 같다, 길이만 다르다).
        LoginAttemptService shortWindow = new LoginAttemptService(template, 2, Duration.ofMillis(400));
        shortWindow.reserveAttempt("kim@example.com");
        shortWindow.reserveAttempt("kim@example.com");
        assertThatThrownBy(() -> shortWindow.reserveAttempt("kim@example.com")).isInstanceOf(LockedException.class);

        Thread.sleep(500);

        // 🐛 잠금에 TTL 을 걸지 않으면(또는 만료를 보지 않으면) 계정은 고객센터에 전화할 때까지 열리지 않는다.
        assertThatCode(() -> shortWindow.reserveAttempt("kim@example.com")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("🔴 풀린 뒤에는 처음부터 — 한 번 더 틀렸다고 바로 다시 잠기지 않는다")
    void counterRestartsAfterTheLock() throws Exception {
        LoginAttemptService shortWindow = new LoginAttemptService(template, 2, Duration.ofMillis(400));
        for (int i = 0; i < 2; i++) {
            shortWindow.reserveAttempt("kim@example.com");
        }
        assertThatThrownBy(() -> shortWindow.reserveAttempt("kim@example.com")).isInstanceOf(LockedException.class);

        Thread.sleep(500);

        // 🐛 풀린 뒤에도 옛 카운터가 남아 있으면 한 번만 틀려도 바로 다시 잠긴다 — 사실상 영구 잠금이다. 지금은 두 가지가 그것을 막는다:
        //    시도 키의 만료가 잠금보다 먼저 오고(둘 다 ARGV[1], 시도 키가 먼저 시작한다), 잠글 때 Lua 가 한 번 더 버린다(DEL).
        for (int i = 0; i < 2; i++) {
            final int attempt = i;
            assertThatCode(() -> shortWindow.reserveAttempt("kim@example.com"))
                    .as("풀린 뒤 %d 번째 시도", attempt + 1).doesNotThrowAnyException();
        }
        assertThatThrownBy(() -> shortWindow.reserveAttempt("kim@example.com")).isInstanceOf(LockedException.class);
    }

    @Test
    @DisplayName("🔴 계정마다 따로 센다 — 한 사람이 잠겨도 다른 사람은 그대로 로그인한다")
    void accountsAreCountedSeparately() {
        for (int i = 0; i < 5; i++) {
            service.reserveAttempt("kim@example.com");
        }
        assertThatThrownBy(() -> service.reserveAttempt("kim@example.com")).isInstanceOf(LockedException.class);

        // 🐛 키에 계정 표지를 빼먹으면 모든 사용자가 카운터 하나를 나눠 쓴다 — 공격자 한 명이 전원을 잠근다.
        for (int i = 0; i < 5; i++) {
            final int attempt = i;
            assertThatCode(() -> service.reserveAttempt("lee@example.com"))
                    .as("lee 의 %d 번째 시도", attempt + 1).doesNotThrowAnyException();
        }
        assertThatThrownBy(() -> service.reserveAttempt("lee@example.com")).isInstanceOf(LockedException.class);
    }

    @Test
    @DisplayName("Redis 키에 이메일 원문이 없다")
    void keysDoNotHoldTheAddress() {
        service.reserveAttempt("kim@example.com");
        assertThat(template.keys("*")).allSatisfy(k -> assertThat(k).doesNotContain("kim"));
        assertThatCode(() -> service.onSuccess("kim@example.com")).doesNotThrowAnyException();
    }
}
