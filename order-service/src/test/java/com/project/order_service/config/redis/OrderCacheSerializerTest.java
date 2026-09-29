package com.project.order_service.config.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;

import com.project.common.dto.order.OrderDto;

/**
 * 주문 캐시는 날짜·시각이 있는 주문도 넣고 되읽는다.
 *
 * <p>🐛 기본 직렬화기는 {@code LocalDateTime} 에서 예외를 던져, 재고 예약 만료({@code stockHoldUntil})가 채워진 새 주문의 상세가 전부
 * 400 이었다.
 */
@DisplayName("주문 캐시 직렬화")
class OrderCacheSerializerTest {

    @Test
    @DisplayName("🔴 날짜·시각이 채워진 주문을 넣고 그대로 되읽는다")
    void roundTripsDates() {
        LocalDateTime hold = LocalDateTime.of(2026, 9, 26, 15, 30, 5);
        OrderDto dto = OrderDto.builder().id("891579158660068589").orderStatus("NOT_PROCESSED")
                .stockHoldUntil(hold).depositDeadline(hold.plusDays(2)).completedAt(hold.plusDays(7))
                .total(new BigDecimal("37.38")).products(List.of()).build();
        GenericJackson2JsonRedisSerializer serializer = RedisCacheConfig.cacheValueSerializer();

        Object back = serializer.deserialize(serializer.serialize(dto));

        assertThat(back).isInstanceOf(OrderDto.class);
        OrderDto read = (OrderDto) back;
        assertThat(read.getStockHoldUntil()).isEqualTo(hold);
        assertThat(read.getDepositDeadline()).isEqualTo(hold.plusDays(2));
        assertThat(read.getTotal()).isEqualByComparingTo("37.38");
    }

    @Test
    @DisplayName("옛 캐시 값에 지금은 없는 필드가 있어도 읽는다 — 배포 직후 400 을 내지 않는다")
    void ignoresUnknownFields() {
        String old = "{\"@class\":\"com.project.common.dto.order.OrderDto\",\"id\":\"1\",\"removedLater\":\"x\"}";

        Object back = RedisCacheConfig.cacheValueSerializer().deserialize(old.getBytes());

        assertThat(((OrderDto) back).getId()).isEqualTo("1");
    }

    @Test
    @DisplayName("기본 직렬화기는 실제로 실패한다 — 이 테스트가 지키는 것")
    void defaultSerializerFails() {
        OrderDto dto = OrderDto.builder().id("1").stockHoldUntil(LocalDateTime.now()).build();
        assertThatThrownBy(() -> new GenericJackson2JsonRedisSerializer().serialize(dto))
                .hasMessageContaining("LocalDateTime");
    }
}
