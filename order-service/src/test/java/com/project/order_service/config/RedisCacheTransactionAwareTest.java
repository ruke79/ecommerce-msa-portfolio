package com.project.order_service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.project.order_service.config.redis.RedisCacheConfig;

/**
 * 🐛 결제됐는데 상세가 NOT_PROCESSED 로 남고 결제 버튼이 다시 떴다 (docs/25 §11.12).
 *
 * <p>사가는 상태를 바꾼 트랜잭션 <b>안에서</b> 캐시를 비웠다. 커밋 전의 틈에 들어온 조회가 옛 상태를
 * 다시 캐시에 넣으면 그대로 30분 남았다. 트랜잭션 안의 비우기는 <b>커밋 뒤에</b> 일어나야 한다.
 *
 * <p>Redis 없이 본다: 연결 팩토리를 mock 으로 두고, 트랜잭션 동기화가 켜진 동안 비우기가
 * Redis 에 <b>손대지 않는지</b>(= 커밋 뒤로 미뤄졌는지) 확인한다.
 */
@DisplayName("order-service — 주문 캐시 비우기는 커밋 뒤에")
class RedisCacheTransactionAwareTest {

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static RedisCacheManager manager(RedisConnectionFactory factory) {
        RedisCacheManager manager = new RedisCacheConfig(new DefaultResourceLoader()).redisCacheManager(factory);
        manager.afterPropertiesSet();
        return manager;
    }

    @Test
    @DisplayName("🔴 트랜잭션 안에서 비우면 Redis 를 건드리지 않고 커밋을 기다린다")
    void evictInsideTransactionIsDeferred() {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisCacheManager manager = manager(factory);
        assertThat(manager.isTransactionAware()).isTrue();

        TransactionSynchronizationManager.initSynchronization();
        Cache order = manager.getCache("order");
        order.clear();                       // @CacheEvict(allEntries = true)
        order.evict("key");
        order.put("key", "NOT_PROCESSED");   // 커밋 전 틈에 읽은 옛 상태

        verifyNoInteractions(factory);
        assertThat(TransactionSynchronizationManager.getSynchronizations())
                .as("커밋 뒤에 실행될 작업으로 등록됐다").hasSize(3);
    }
}
