package com.project.order_service.config.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.redisson.spring.data.connection.RedissonConnection;
import org.redisson.spring.data.connection.RedissonConnectionFactory;
import org.springframework.data.redis.connection.ExpirationOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redisson 의 Spring Data 연결 모듈이 이 Spring Data Redis 판과 맞는가 — <b>expire 가 무한 재귀하지 않는가</b>.
 *
 * <p>order 의 Redis 연결은 Lettuce 다({@code RedisCacheConfig} — {@code RedissonConfig} 는 주석). 그래도 starter 가 있어
 * {@code RedissonClient} · Redisson 연결 빈이 자동 설정으로 생긴다 — 누가 그 연결을 쓰거나 설정을 되살리면 바로 닿는다.
 * 🐛 Redisson 3.23.5 의 {@code redisson-spring-data-31} 은 Spring Data Redis 3.1 용인데 Boot 3.5 는 3.5 를 쓴다 — 3.5 에서 새로 생긴
 * 기본 메서드(조건 있는 {@code pExpire})가 {@code keyCommands()}(= 연결 자신)로 서로를 불러 {@code StackOverflowError} 가 났다
 * (chat, 2026-11-20 실습 — chat 의 같은 이름 시험). 판을 맞춘다(3.50.0 = spring-data-35).
 *
 * <p>네트워크 없이 잰다 — 재귀는 Redis 에 닿기 전에 일어난다. 맞는 판이면 (가짜 클라이언트라) 다른 예외가 날 뿐이다.
 *
 * <p>[JA] Redisson の Spring Data 連結モジュールがこの Spring Data Redis の版に合うか — expire が無限再帰しないか。
 *      order の Redis 接続は Lettuce だが、starter が RedissonClient · Redisson 接続 Bean を自動設定で作る。
 *      3.23.5(spring-data-31)は Boot 3.5 と合わず、chat では expire が StackOverflowError になった — 3.50.0 に合わせる。
 */
@DisplayName("Redisson ↔ Spring Data Redis — expire 가 재귀하지 않는다")
class RedissonExpireRecursionTest {

    /** 이 호출이 StackOverflowError 를 내지 않는지 — 가짜 클라이언트라 다른 예외는 괜찮다. [JA] この呼び出しが StackOverflowError を出さないか — 偽のクライアントなので他の例外は構わない。 */
    private static Throwable call(Runnable r) {
        try {
            r.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    @Test
    @DisplayName("🔴 연결의 pExpire(조건 있는 판 · 없는 판) · StringRedisTemplate.expire 가 StackOverflowError 를 내지 않는다")
    void expireDoesNotRecurse() {
        // 닿지 않는 주소 · 지연 초기화 — 연결을 만들지 않고 클라이언트만 띄운다(재귀는 Redis 에 닿기 전에 일어난다)
        // [JA] 届かないアドレス · 遅延初期化 — 接続を作らずクライアントだけ立てる(再帰は Redis に届く前に起きる)
        org.redisson.config.Config config = new org.redisson.config.Config();
        config.setLazyInitialization(true);
        config.useSingleServer().setAddress("redis://127.0.0.1:1").setConnectTimeout(200).setTimeout(200).setRetryAttempts(0);
        RedissonClient client = org.redisson.Redisson.create(config);
        RedissonConnection connection = new RedissonConnection(client);
        byte[] key = "recursion:probe".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(call(() -> connection.pExpire(key, 90_000L, ExpirationOptions.Condition.ALWAYS)))
                .as("조건 있는 pExpire — Spring Data Redis 3.5 의 기본 메서드").isNotInstanceOf(StackOverflowError.class);
        assertThat(call(() -> connection.keyCommands().pExpire(key, 90_000L)))
                .as("keyCommands().pExpire").isNotInstanceOf(StackOverflowError.class);
        StringRedisTemplate redis = new StringRedisTemplate(new RedissonConnectionFactory(client));
        assertThat(call(() -> redis.expire("recursion:probe", Duration.ofSeconds(90))))
                .as("StringRedisTemplate.expire(Duration)").isNotInstanceOf(StackOverflowError.class);
        client.shutdown();
    }
}
