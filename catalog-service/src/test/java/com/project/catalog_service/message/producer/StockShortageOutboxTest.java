package com.project.catalog_service.message.producer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.catalog_service.model.StockShortage;
import com.project.catalog_service.repository.jpa.data.StockShortageRepository;
import com.project.catalog_service.service.ProductService.ShortLine;

/**
 * 재고 부족 아웃박스 — 적기({@link StockShortageRecorder})와 보내기({@link StockShortagePublisher}) (docs/53).
 */
@DisplayName("재고 부족 아웃박스")
class StockShortageOutboxTest {

    private final StockShortageRepository repository = mock(StockShortageRepository.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("차감 이벤트 키에서 주문 id 를 읽고, 모자란 줄을 JSON 으로 적는다")
    void recordsTheShortage() throws Exception {
        new StockShortageRecorder(repository, mapper)
                .record("order-42", List.of(new ShortLine(1L, 5L, "M", 3, 2)));

        ArgumentCaptor<StockShortage> saved = ArgumentCaptor.forClass(StockShortage.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getEventId()).isEqualTo("shortage-order-42");
        assertThat(saved.getValue().getOrderId()).isEqualTo(42L);
        assertThat(saved.getValue().getPublishedAt()).isNull();
        JsonNode json = mapper.readTree(saved.getValue().getPayload());
        assertThat(json.get("orderId").asLong()).isEqualTo(42L);
        assertThat(json.get("eventId").asText()).isEqualTo("shortage-order-42");
        JsonNode line = json.get("lines").get(0);
        assertThat(line.get("productId").asLong()).isEqualTo(1L);
        assertThat(line.get("colorId").asLong()).isEqualTo(5L);
        assertThat(line.get("size").asText()).isEqualTo("M");
        assertThat(line.get("orderedQty").asInt()).isEqualTo(3);
        assertThat(line.get("shortQty").asInt()).isEqualTo(2);
    }

    /** 누구에게 돌려줄지 모르면 적지 않고 운영자에게 넘긴다(로그 "수동 확인 필요" → Loki 경보). */
    @Test
    @DisplayName("주문을 알 수 없는 키(옛 발행자)는 적지 않는다 — 운영자에게 넘긴다")
    void anUnknownOrderIsNotRecorded() {
        new StockShortageRecorder(repository, mapper).record(null, List.of(new ShortLine(1L, 5L, "M", 1, 1)));
        new StockShortageRecorder(repository, mapper).record("restock-1-u3", List.of(new ShortLine(1L, 5L, "M", 1, 1)));

        verify(repository, never()).save(any());
        assertThat(StockShortageRecorder.orderIdOf("order-123")).isEqualTo(123L);
        assertThat(StockShortageRecorder.orderIdOf("order-x")).isNull();
    }

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);

    private StockShortage pending(long orderId) {
        return new StockShortage("shortage-order-" + orderId, orderId, "{\"orderId\":" + orderId + "}");
    }

    @Test
    @DisplayName("보내고 나서 표시한다 — 주문 id 가 키")
    void publishesThenMarks() {
        StockShortage s = pending(42);
        when(repository.findUnpublished(any())).thenReturn(List.of(s));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));

        new StockShortagePublisher(repository, kafka).publishPending();

        verify(kafka).send(eq("stock-shortage"), eq("42"), eq("{\"orderId\":42}"));
        assertThat(s.getPublishedAt()).isNotNull();
        verify(repository).save(s);
    }

    /** 보내지 못했으면 표시하지 않는다 — 다음 주기에 다시 보낸다. 브로커 문제면 나머지도 실패하므로 멈춘다. */
    @Test
    @DisplayName("발행이 실패하면 표시하지 않고 멈춘다 — 다음 주기에 다시")
    void aFailedSendIsRetriedLater() {
        StockShortage first = pending(1);
        StockShortage second = pending(2);
        when(repository.findUnpublished(any())).thenReturn(List.of(first, second));
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        new StockShortagePublisher(repository, kafka).publishPending();

        assertThat(first.getPublishedAt()).isNull();
        verify(kafka, never()).send(anyString(), eq("2"), anyString());
        verify(repository, never()).save(any());
    }
}
