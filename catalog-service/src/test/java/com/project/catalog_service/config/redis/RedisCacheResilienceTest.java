package com.project.catalog_service.config.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.RedisConnectionFailureException;

/**
 * 캐시 설정 — products 는 만료가 있고, Redis 오류는 조회를 막지 않는다.
 *
 * <p>🐛 예전: products 는 만료 없는 영구 캐시였고(놓친 무효화가 영원히 남았다), 캐시 오류 처리기가 없어 Redis 가 멈추면 검색 · 상세 ·
 * 홈이 모두 500 이었다.
 *
 * <p>[JA] products には期限があり、Redis の失敗は照会を止めない。
 */
@DisplayName("캐시 설정 — 만료 · 장애 내성")
class RedisCacheResilienceTest {

    @Test
    @DisplayName("🔴 products 캐시에는 만료가 있다(30분) — 놓친 무효화가 영원히 남지 않는다")
    void productsHaveTtl() {
        RedisCacheManager manager = new RedisCacheConfig(null).redisCacheManager(mock(RedisConnectionFactory.class));
        manager.afterPropertiesSet();
        var config = manager.getCacheConfigurations().get("products");
        assertThat(config).isNotNull();
        assertThat(config.getTtlFunction().getTimeToLive(null, null)).isEqualTo(RedisCacheConfig.PRODUCTS_TTL);
        assertThat(RedisCacheConfig.PRODUCTS_TTL).isGreaterThan(Duration.ZERO);
    }

    @Test
    @DisplayName("🔴 캐시 오류는 삼킨다 — 읽기는 미스로, 쓰기 · 비우기는 경고로(Redis 가 멈춰도 조회는 된다)")
    void cacheErrorsDoNotPropagate() {
        var handler = new RedisCacheConfig(null).errorHandler();
        var cache = new ConcurrentMapCache("products");
        RuntimeException down = new RedisConnectionFailureException("down");
        assertThatCode(() -> handler.handleCacheGetError(down, cache, "k")).doesNotThrowAnyException();
        assertThatCode(() -> handler.handleCachePutError(down, cache, "k", "v")).doesNotThrowAnyException();
        assertThatCode(() -> handler.handleCacheEvictError(down, cache, "k")).doesNotThrowAnyException();
        assertThatCode(() -> handler.handleCacheClearError(down, cache)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("🔴 Redis 명령 시간 제한은 1초 — 직접 만든 Lettuce 설정의 기본값(60초)이 아니다")
    void commandTimeoutIsShort() throws Exception {
        RedisCacheConfig config = new RedisCacheConfig(null);
        org.springframework.test.util.ReflectionTestUtils.setField(config, "host", "localhost");
        org.springframework.test.util.ReflectionTestUtils.setField(config, "port", 6379);
        org.springframework.test.util.ReflectionTestUtils.setField(config, "password", "pw");
        var factory = (org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory) config.redisConnectionFactory();
        assertThat(factory.getClientConfiguration().getCommandTimeout()).isEqualTo(Duration.ofSeconds(1));
    }
}
