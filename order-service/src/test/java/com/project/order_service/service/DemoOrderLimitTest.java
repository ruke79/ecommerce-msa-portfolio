package com.project.order_service.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.common.constants.OrderStatus;
import com.project.order_service.domain.exception.OrderDomainException;
import com.project.order_service.model.Order;
import com.project.order_service.repository.jpa.OrderRepository;

/**
 * 공개 데모 계정의 결제 전 주문 상한(docs/100) — {@code User-Demo: true} 일 때만, 결제 시작 전 · 입금 대기 합이 상한 이상이면 거절.
 * 같은 멱등 키의 재요청은 이미 만든 주문을 돌려주므로 막지 않는다.
 * [JA] 公開デモアカウントの決済前注文上限 — User-Demo: true のときだけ。同じ冪等キーの再要求は止めない。
 */
@DisplayName("데모 계정 — 결제 전 주문 상한")
class DemoOrderLimitTest {

    OrderRepository orders = mock(OrderRepository.class);
    OrderCacheService cache = mock(OrderCacheService.class);
    DemoOrderLimit limit = new DemoOrderLimit(orders, cache, 3);

    private void pending(long notProcessed, long unpaid) {
        when(orders.countByCustomerIdAndOrderStatus(7L, OrderStatus.NOT_PROCESSED)).thenReturn(notProcessed);
        when(orders.countByCustomerIdAndOrderStatus(7L, OrderStatus.UNPAID)).thenReturn(unpaid);
    }

    @Test
    @DisplayName("보통 계정(헤더 없음 · false)은 세지도 않는다")
    void regularAccountsAreNotCounted() {
        limit.check(null, 7L, null);
        limit.check("false", 7L, null);
        verifyNoInteractions(orders, cache);
    }

    @Test
    @DisplayName("🔴 결제 시작 전 2 + 입금 대기 1 = 3 이면 거절, 2 면 통과")
    void rejectsAtLimit() {
        pending(2, 1);
        assertThatThrownBy(() -> limit.check("true", 7L, null)).isInstanceOf(OrderDomainException.class)
                .hasMessageContaining("3건");
        pending(1, 1);
        assertThatCode(() -> limit.check("true", 7L, null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("🔴 같은 멱등 키로 이미 만든 주문이 있으면 상한이어도 통과 — 재요청은 그 주문을 돌려받는다")
    void sameIdempotencyKeyPasses() {
        pending(3, 0);
        when(cache.findByCreateKey(anyLong(), any())).thenReturn(mock(Order.class));
        assertThatCode(() -> limit.check("true", 7L, "key-12345678")).doesNotThrowAnyException();
    }
}
