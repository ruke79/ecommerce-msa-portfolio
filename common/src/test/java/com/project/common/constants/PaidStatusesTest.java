package com.project.common.constants;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * "돈이 들어온 주문" 의 정의.
 *
 * <p>매출 집계·구매 이력·등급 산정이 전부 이 집합 하나를 본다. 기준이 흩어지면
 * "주문 내역에는 결제된 주문인데 등급 계산에는 안 잡힌다" 같은, 사용자가 설명을 들어도
 * 납득하지 못하는 불일치가 생긴다.
 */
@DisplayName("결제된 주문 상태")
class PaidStatusesTest {

    @Test
    @DisplayName("취소된 주문은 매출이 아니다")
    void canceledIsNotPaid() {
        assertThat(OrderStatus.PAID_STATUSES).doesNotContain(OrderStatus.CANCELED);
        // 돈이 돌아간 주문은 매출이 아니다 — 여기 넣으면 환불액이 매출로 잡힌다.
        assertThat(OrderStatus.PAID_STATUSES).doesNotContain(OrderStatus.REFUNDED);
    }

    @Test
    @DisplayName("결제 전 상태는 매출이 아니다")
    void beforePaymentIsNotPaid() {
        assertThat(OrderStatus.PAID_STATUSES)
                .doesNotContain(OrderStatus.NOT_PROCESSED, OrderStatus.UNPAID);
    }

    @Test
    @DisplayName("결제 후 진행 중인 주문은 전부 매출이다 — 배송 단계에서 빠지면 안 된다")
    void everyPostPaymentStageCounts() {
        assertThat(OrderStatus.PAID_STATUSES).containsExactlyInAnyOrder(
                OrderStatus.PAID,
                OrderStatus.PROCESSING,
                OrderStatus.DISPATCHED,
                OrderStatus.DELIVERED,
                OrderStatus.COMPLETED);
    }

    /**
     * 🔴 <b>결제된 쪽을 나열한다 — 안 된 쪽을 빼는 것이 아니다.</b> 상태가 하나 늘었을 때
     * 빼는 방식이면 새 상태가 조용히 매출로 잡히고, 나열하는 방식이면 조용히 빠진다.
     * 돈을 부풀리는 쪽이 훨씬 나쁜 실패다.
     *
     * <p>이 테스트는 상태가 늘어날 때 <b>실패하라고</b> 있다. 새 상태가 매출인지 아닌지를
     * 사람이 한 번 판단하고 여기에 적으라는 뜻이다.
     */
    @Test
    @DisplayName("상태가 늘어나면 여기서 막힌다 — 매출 여부를 사람이 정하라는 뜻이다")
    void newStatusMustBeClassified() {
        assertThat(OrderStatus.values())
                .as("OrderStatus 에 값이 늘었다면 PAID_STATUSES 에 넣을지 판단하고 이 숫자를 고쳐라")
                .hasSize(9);
    }

    @Test
    @DisplayName("집합은 바꿀 수 없다 — 누가 런타임에 매출 정의를 바꿀 수 없어야 한다")
    void theSetIsImmutable() {
        assertThat(OrderStatus.PAID_STATUSES.getClass().getName())
                .doesNotContain("EnumSet");
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> OrderStatus.PAID_STATUSES.add(OrderStatus.CANCELED));
    }
}
