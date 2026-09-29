package com.project.order_service.ports.output.message.publisher;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.common.constants.OrderStatus;
import com.project.order_service.model.Order;
import com.project.order_service.model.OrderRefund;

/**
 * 🐛 부분 환불에는 알림이 없었다 — 환불 한 건마다 {@code order-refunded} 를 보낸다 (docs/49 §10).
 */
@DisplayName("OrderRefundedKafkaPublisher — 환불 한 건의 이벤트")
class OrderRefundedKafkaPublisherTest {

    private static Order order(OrderStatus status, OrderRefund... refunds) {
        Order order = Order.builder().orderId(1234L).customerId(7L).orderStatus(status)
                .refunds(new ArrayList<>()).build();
        for (OrderRefund r : refunds) {
            order.getRefunds().add(r);
        }
        return order;
    }

    private static OrderRefund refund(String ref, String charged, String currency) {
        return OrderRefund.builder().refundRef(ref).amount(new BigDecimal("1"))
                .chargedAmount(new BigDecimal(charged)).currency(currency).build();
    }

    @Test
    @DisplayName("🔴 부분 환불 — 가장 최근 환불의 청구 금액·통화, complete=false")
    void partialRefund() {
        Map<String, Object> event = OrderRefundedKafkaPublisher.eventFor(
                order(OrderStatus.PAID, refund("R-1", "12000", "KRW"), refund("R-2", "8000.00", "KRW")));

        assertThat(event).containsEntry("orderId", 1234L).containsEntry("customerId", 7L)
                .containsEntry("amount", "8000").containsEntry("currency", "KRW")
                .containsEntry("complete", false).containsEntry("refundCount", 2)
                .containsEntry("eventId", "order-refunded:1234:2");
    }

    @Test
    @DisplayName("전액이 다 돌아간 환불 — complete=true (알림은 결제 응답 쪽이 이미 보낸다)")
    void completingRefund() {
        assertThat(OrderRefundedKafkaPublisher.eventFor(order(OrderStatus.REFUNDED, refund("R-1", "59.00", "USD"))))
                .containsEntry("complete", true).containsEntry("amount", "59");
    }

    @Test
    @DisplayName("환불 참조가 같아도 n 번째 환불의 키는 다르다 — 두 번째 부분 환불이 중복으로 걸러지지 않는다")
    void keysDifferEvenWhenRefundRefRepeats() {
        Object first = OrderRefundedKafkaPublisher.eventFor(order(OrderStatus.PAID, refund("PAY-1", "10", "KRW")))
                .get("eventId");
        Object second = OrderRefundedKafkaPublisher.eventFor(
                order(OrderStatus.PAID, refund("PAY-1", "10", "KRW"), refund("PAY-1", "5", "KRW"))).get("eventId");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("환불 기록이 없으면(이미 다 환불된 주문의 재요청) 보내지 않는다")
    void nothingToSay() {
        assertThat(OrderRefundedKafkaPublisher.eventFor(order(OrderStatus.REFUNDED))).isNull();
        assertThat(OrderRefundedKafkaPublisher.eventFor(null)).isNull();
    }
}
