package com.project.catalog_service.message.producer;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.mock.MockProducerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.catalog_service.message.producer.StockEventProducer.Transition;
import com.project.catalog_service.message.producer.StockEventProducer.Variant;

/**
 * 재고가 0 을 건너는 순간 — 품절(운영자 경보)과 재입고(손님 알림) (docs/53).
 */
@DisplayName("StockEventProducer — 품절·재입고")
class StockEventProducerTest {

    private final MockProducer<String, String> producer =
            new MockProducer<>(true, new StringSerializer(), new StringSerializer());
    private final StockEventProducer events = new StockEventProducer(
            new KafkaTemplate<>(new MockProducerFactory<>(() -> producer)), new ObjectMapper());
    private static final Variant V = new Variant(10L, "tee", "티셔츠", 3L, "black", "M");

    @AfterEach
    void clear() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("0 을 건널 때만 — 있음→0 은 품절, 0→있음 은 재입고, 나머지는 없음")
    void onlyCrossingZeroCounts() {
        assertThat(StockEventProducer.transitionOf(1, 0)).isEqualTo(Transition.SOLD_OUT);
        assertThat(StockEventProducer.transitionOf(3, -1)).isEqualTo(Transition.SOLD_OUT);
        assertThat(StockEventProducer.transitionOf(0, 2)).isEqualTo(Transition.RESTOCKED);
        assertThat(StockEventProducer.transitionOf(5, 3)).isNull();
        assertThat(StockEventProducer.transitionOf(0, 0)).isNull();
        assertThat(StockEventProducer.transitionOf(2, 7)).isNull();
    }

    @Test
    @DisplayName("🔴 재입고 — product-stock 으로 옵션 정보와 함께 나간다(상품 id 키)")
    void restockedIsPublished() throws Exception {
        events.onQuantityChanged(V, 0, 4);

        assertThat(producer.history()).hasSize(1);
        assertThat(producer.history().get(0).topic()).isEqualTo("product-stock");
        assertThat(producer.history().get(0).key()).isEqualTo("10");
        JsonNode e = new ObjectMapper().readTree(producer.history().get(0).value());
        assertThat(e.get("type").asText()).isEqualTo("RESTOCKED");
        assertThat(e.get("productId").asLong()).isEqualTo(10L);
        assertThat(e.get("colorId").asLong()).isEqualTo(3L);
        assertThat(e.get("size").asText()).isEqualTo("M");
        assertThat(e.get("name").asText()).isEqualTo("티셔츠");
        assertThat(e.get("quantity").asInt()).isEqualTo(4);
    }

    @Test
    @DisplayName("재고가 0 을 건너지 않으면 아무것도 보내지 않는다 — 주문마다 이벤트는 소음이다")
    void ordinaryChangesAreQuiet() {
        events.onQuantityChanged(V, 5, 3);
        events.onQuantityChanged(V, 0, 0);

        assertThat(producer.history()).isEmpty();
    }

    @Test
    @DisplayName("🔴 트랜잭션 안이면 커밋 뒤에 보낸다 — 롤백된 차감으로 '품절' 이 나가면 거짓말이다")
    void waitsForCommit() {
        TransactionSynchronizationManager.initSynchronization();
        events.onQuantityChanged(V, 1, 0);
        assertThat(producer.history()).as("커밋 전").isEmpty();

        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCommit();
        }
        assertThat(producer.history()).hasSize(1);
        assertThat(producer.history().get(0).value()).contains("\"type\":\"SOLD_OUT\"");
    }
}
