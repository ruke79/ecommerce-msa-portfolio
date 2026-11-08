package com.project.cart_service.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 오프셋이 없을 때 — 처음부터 읽는다.
 *
 * <p>🐛 예전: 세 그룹 모두 latest. 그룹이 처음 뜨거나 오프셋이 만료된(무활동 7일) 사이의 cart-empty 가 사라져 주문한 물건이 장바구니에
 * 남았다. 다시 읽어도 안전하다 — cart-empty 는 처리 원장(V6), coupon-use · cart-rollback 은 총액 재계산. notification(N-M5) ·
 * delivery(D-M7) · chat 과 같다.
 *
 * <p>[JA] オフセットがないとき — 最初から読む。以前は三つとも latest で、その間の cart-empty が消えていた。
 */
@DisplayName("cart — 컨슈머 오프셋 기본값")
class CartKafkaOffsetResetTest {

    @Test
    @DisplayName("🔴 coupon-use · cart-empty · cart-rollback 컨슈머의 기본은 earliest")
    void defaultsToEarliest() {
        KafkaConsumerConfig config = new KafkaConsumerConfig();
        ReflectionTestUtils.setField(config, "environment", new MockEnvironment());
        ReflectionTestUtils.setField(config, "kafkaServerUrl", "localhost:9092");

        assertThat(config.couponUseConsumerFactory().getConfigurationProperties())
                .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        assertThat(config.cartEmptyConsumerFactory().getConfigurationProperties())
                .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        assertThat(config.cartRollbackConsumerFactory().getConfigurationProperties())
                .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    }
}
