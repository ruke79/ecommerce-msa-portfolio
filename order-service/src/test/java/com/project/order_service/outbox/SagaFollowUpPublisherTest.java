package com.project.order_service.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.message.dto.request.CartEmptyItem;
import com.project.common.message.dto.request.CartEmptyRequest;
import com.project.common.message.dto.request.OrderProductRequest;
import com.project.common.message.dto.request.ProductUpdateRequest;
import com.project.common.outbox.OutboxStatus;
import com.project.order_service.outbox.model.PaymentOutboxEvent;
import com.project.order_service.ports.output.message.publisher.CartEmptyKafkaPublisher;
import com.project.order_service.ports.output.message.publisher.ProductUpdateKafkaPublisher;

/**
 * 사가의 후속 메시지(재고 차감 · 복원 · 장바구니 비우기)는 아웃박스 행 → 릴레이 → 동기 발행.
 *
 * <p>🐛 예전: 사가가 Kafka 로 바로 보내고 결과는 로그뿐 — PAID 커밋 뒤 발행이 빠지면 재고가 차감되지 않았다.
 *
 * <p>[JA] サガの後続メッセージはアウトボックス行 → リレー → 同期発行。以前は直接送り、結果はログだけだった。
 */
@DisplayName("order-service — 사가 후속 메시지의 아웃박스 발행")
class SagaFollowUpPublisherTest {

    private final ObjectMapper json = new ObjectMapper();

    private static <V> CompletableFuture<SendResult<String, V>> sent(String topic) {
        return CompletableFuture.completedFuture(new SendResult<>(new ProducerRecord<>(topic, "k", null),
                new RecordMetadata(new TopicPartition(topic, 0), 7, 0, 0, 0, 0)));
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("🔴 product-update 행 — 페이로드가 그대로 돌아오고(eventId · restock · 줄), 키는 eventId, 보낸 뒤에만 COMPLETED")
    void productUpdateRoundTrip() throws Exception {
        ProductUpdateRequest request = new ProductUpdateRequest();
        request.setEventId("restock-1-u3");
        request.setRestock(true);
        request.setOrderProducts(new ArrayList<>(List.of(new OrderProductRequest(10L, 2, "M", 5L))));
        PaymentOutboxEvent row = new PaymentOutboxEvent("1", "Order", PaymentOutboxHelper.PRODUCT_UPDATE_EVENT,
                json.writeValueAsString(request), OutboxStatus.STARTED);

        KafkaTemplate<String, ProductUpdateRequest> template = mock(KafkaTemplate.class);
        org.mockito.ArgumentCaptor<ProductUpdateRequest> payload = org.mockito.ArgumentCaptor.forClass(ProductUpdateRequest.class);
        when(template.send(eq("product-update"), eq("restock-1-u3"), payload.capture())).thenReturn(sent("product-update"));
        List<OutboxStatus> settled = new ArrayList<>();

        assertThat(new ProductUpdateKafkaPublisher(template, json).publish(row, (r, st) -> settled.add(st))).isTrue();

        assertThat(settled).containsExactly(OutboxStatus.COMPLETED);
        ProductUpdateRequest back = payload.getValue();
        assertThat(back.isRestock()).isTrue();
        assertThat(back.getOrderProducts()).singleElement().satisfies(l -> {
            assertThat(l.getProductId()).isEqualTo(10L);
            assertThat(l.getQty()).isEqualTo(2);
            assertThat(l.getSize()).isEqualTo("M");
            assertThat(l.getColorId()).isEqualTo(5L);
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("🔴 브로커가 실패하면 false — 상태를 건드리지 않아 다음 폴링이 다시 보낸다(예전에는 로그만 남고 사라졌다)")
    void brokerFailureLeavesRowStarted() throws Exception {
        ProductUpdateRequest request = new ProductUpdateRequest();
        request.setEventId("order-1");
        request.setOrderProducts(new ArrayList<>());
        PaymentOutboxEvent row = new PaymentOutboxEvent("1", "Order", PaymentOutboxHelper.PRODUCT_UPDATE_EVENT,
                json.writeValueAsString(request), OutboxStatus.STARTED);
        KafkaTemplate<String, ProductUpdateRequest> template = mock(KafkaTemplate.class);
        when(template.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));
        List<OutboxStatus> settled = new ArrayList<>();

        assertThat(new ProductUpdateKafkaPublisher(template, json).publish(row, (r, st) -> settled.add(st))).isFalse();
        assertThat(settled).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("🔴 cart-empty 행 — 주문한 줄이 그대로, 키는 사용자 id")
    void cartEmptyRoundTrip() throws Exception {
        CartEmptyRequest request = new CartEmptyRequest(7L, true, new ArrayList<>(List.of(new CartEmptyItem(10L, "M", 5L, 2))));
        PaymentOutboxEvent row = new PaymentOutboxEvent("1", "Order", PaymentOutboxHelper.CART_EMPTY_EVENT,
                json.writeValueAsString(request), OutboxStatus.STARTED);
        KafkaTemplate<String, CartEmptyRequest> template = mock(KafkaTemplate.class);
        org.mockito.ArgumentCaptor<CartEmptyRequest> payload = org.mockito.ArgumentCaptor.forClass(CartEmptyRequest.class);
        when(template.send(eq("cart-empty"), eq("7"), payload.capture())).thenReturn(sent("cart-empty"));
        List<OutboxStatus> settled = new ArrayList<>();

        assertThat(new CartEmptyKafkaPublisher(template, json).publish(row, (r, st) -> settled.add(st))).isTrue();

        assertThat(settled).containsExactly(OutboxStatus.COMPLETED);
        assertThat(payload.getValue().isEmptyCart()).isTrue();
        assertThat(payload.getValue().getItems()).singleElement().satisfies(i -> {
            assertThat(i.getProductId()).isEqualTo(10L);
            assertThat(i.getQty()).isEqualTo(2);
        });
        verify(template, never()).send(eq("product-update"), anyString(), any());
    }
}
