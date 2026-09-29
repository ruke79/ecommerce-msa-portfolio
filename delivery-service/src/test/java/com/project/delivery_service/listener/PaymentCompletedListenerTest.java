package com.project.delivery_service.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.listener.BatchListenerFailedException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.dto.order.OrderShippingDto;
import com.project.delivery_service.client.OrderServiceClient;
import com.project.delivery_service.dto.CreateDeliveryCommand;
import com.project.delivery_service.service.DeliveryService;

/**
 * 결제 완료 이벤트 → 배송 생성.
 *
 * <h3>🐛 회귀 대상 — 배송이 한 건도 만들어지지 않았다</h3>
 *
 * <p>{@code payment} 토픽의 이벤트에는 주소가 없다. 이전 리스너는 이벤트 안에서 주소를 찾다
 * 못 찾고 예외를 던졌고, 모든 결제 완료가 DLT 로 갔다. 이제 order-service 에 묻는다.
 */
@DisplayName("delivery-service — 결제 이벤트로 배송 만들기")
class PaymentCompletedListenerTest {

    /** 실제 PaymentResponse 가 Avro→JSON 으로 오는 모양 그대로 — 주소도 품목도 없다. */
    private static final String COMPLETED =
            "{\"paymentId\":\"p-1\",\"orderId\":889196209423729897,\"customerId\":7,"
            + "\"couponName\":null,\"amounts\":\"59.98\",\"paymentStatus\":\"COMPLETED\"}";

    private DeliveryService deliveryService;
    private OrderServiceClient orderServiceClient;
    private PaymentCompletedListener listener;

    @BeforeEach
    void setUp() {
        deliveryService = mock(DeliveryService.class);
        orderServiceClient = mock(OrderServiceClient.class);
        listener = new PaymentCompletedListener(deliveryService, orderServiceClient, new ObjectMapper(), com.project.common.outbox.OutboxTraceContext.NOOP);
        when(deliveryService.createFromOrder(any())).thenReturn(Optional.empty());
    }

    private static OrderShippingDto order(OrderShippingDto.Address address) {
        return new OrderShippingDto(889196209423729897L, 7L, "PAID", address, List.of(
                new OrderShippingDto.Item(101L, 10L, "티셔츠", "/img/t.png", 2, "TEE-BLK-M"),
                new OrderShippingDto.Item(102L, 11L, "바지", "/img/p.png", 0)));
    }

    private static OrderShippingDto.Address seoul() {
        return new OrderShippingDto.Address("홍 길동", "010-1234-5678", "06236",
                "서울 강남구 테헤란로 1", "3층", "서울", "서울", "KR");
    }

    /** 🔴 핵심 — 이벤트에 주소가 없어도 order-service 에서 읽어 배송을 만든다. */
    @Test
    @DisplayName("🔴 이벤트에 주소가 없으면 order-service 에 묻고, 그 주소·품목으로 배송을 만든다")
    void addressComesFromOrderService() {
        when(orderServiceClient.shipping(889196209423729897L)).thenReturn(order(seoul()));

        listener.onPayment(List.of(COMPLETED), null);

        ArgumentCaptor<CreateDeliveryCommand> captor = ArgumentCaptor.forClass(CreateDeliveryCommand.class);
        verify(deliveryService).createFromOrder(captor.capture());
        CreateDeliveryCommand c = captor.getValue();
        assertThat(c.orderId()).isEqualTo(889196209423729897L);
        assertThat(c.customerId()).isEqualTo(7L);
        assertThat(c.recipientName()).isEqualTo("홍 길동");
        assertThat(c.recipientPhone()).isEqualTo("010-1234-5678");
        assertThat(c.postalCode()).isEqualTo("06236");
        assertThat(c.addressLine1()).isEqualTo("서울 강남구 테헤란로 1");
        assertThat(c.addressLine2()).isEqualTo("3층");
        assertThat(c.country()).isEqualTo("KR");
        assertThat(c.items()).extracting(CreateDeliveryCommand.Item::orderProductId).containsExactly(101L, 102L);
        // 수량 0 은 상자에 넣을 수 없다 — 최소 1
        assertThat(c.items()).extracting(CreateDeliveryCommand.Item::quantity).containsExactly(2, 1);
        // 포장 목록의 SKU — 주문 품목이 복사해 둔 값을 그대로. SKU 가 없던 주문(옛 품목)은 null 인 채로 배송은 만들어진다
        assertThat(c.items()).extracting(CreateDeliveryCommand.Item::skuCode).containsExactly("TEE-BLK-M", null);
    }

    @Test
    @DisplayName("이벤트에 주소가 실려 오면 order-service 를 부르지 않는다")
    void embeddedAddressWins() {
        String withAddress = "{\"orderId\":5,\"customerId\":7,\"paymentStatus\":\"COMPLETED\","
                + "\"shippingAddress\":{\"firstname\":\"김\",\"address1\":\"부산 해운대구 1\",\"city\":\"부산\",\"zipCode\":\"48000\"},"
                + "\"orderedProducts\":[{\"orderProductId\":1,\"productId\":9,\"name\":\"모자\",\"qty\":3,\"skuCode\":\"CAP-F\"}]}";

        listener.onPayment(List.of(withAddress), null);

        verify(orderServiceClient, never()).shipping(anyLong());
        ArgumentCaptor<CreateDeliveryCommand> captor = ArgumentCaptor.forClass(CreateDeliveryCommand.class);
        verify(deliveryService).createFromOrder(captor.capture());
        assertThat(captor.getValue().addressLine1()).isEqualTo("부산 해운대구 1");
        assertThat(captor.getValue().items()).singleElement()
                .satisfies(i -> {
                    assertThat(i.quantity()).isEqualTo(3);
                    assertThat(i.skuCode()).isEqualTo("CAP-F");
                });
    }

    /** 주소 없이 만든 배송은 송장을 낼 수 없다 — 조용히 만들지 않고 DLT 로 보낸다. */
    @Test
    @DisplayName("주문에도 주소가 없으면 그 레코드만 실패로 표시한다(DLT)")
    void missingAddressIsIsolated() {
        when(orderServiceClient.shipping(anyLong())).thenReturn(order(null));

        assertThatThrownBy(() -> listener.onPayment(List.of("{\"orderId\":1,\"paymentStatus\":\"COMPLETED\"}", COMPLETED), null))
                .isInstanceOf(BatchListenerFailedException.class)
                .satisfies(e -> assertThat(((BatchListenerFailedException) e).getIndex()).isEqualTo(0));
        verify(deliveryService, never()).createFromOrder(any());
    }

    @Test
    @DisplayName("결제가 취소·환불되면 발송 전 배송을 접는다 — 주소를 묻지 않는다")
    void canceledPaymentCancelsDelivery() {
        when(deliveryService.cancelByOrder(5L)).thenReturn(true);

        listener.onPayment(List.of("{\"orderId\":5,\"customerId\":7,\"paymentStatus\":\"CANCELED\"}"), null);

        verify(deliveryService).cancelByOrder(5L);
        verify(deliveryService, never()).createFromOrder(any());
        verify(orderServiceClient, never()).shipping(anyLong());
    }

    @Test
    @DisplayName("실패한 결제로는 아무것도 하지 않는다")
    void failedPaymentIsIgnored() {
        listener.onPayment(List.of("{\"orderId\":5,\"customerId\":7,\"paymentStatus\":\"FAILED\"}"), null);

        verify(deliveryService, never()).createFromOrder(any());
        verify(deliveryService, never()).cancelByOrder(anyLong());
    }

    @Test
    @DisplayName("주문 번호가 없는 이벤트는 경고만 남기고 넘어간다")
    void missingOrderIdIsSkipped() {
        listener.onPayment(List.of("{\"paymentStatus\":\"COMPLETED\",\"customerId\":7}"), null);

        verify(deliveryService, never()).createFromOrder(any());
        verify(orderServiceClient, never()).shipping(anyLong());
    }
}
