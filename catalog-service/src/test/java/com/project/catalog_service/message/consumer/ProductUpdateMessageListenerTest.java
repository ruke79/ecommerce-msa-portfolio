package com.project.catalog_service.message.consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.project.catalog_service.message.producer.StockShortageRecorder;
import com.project.catalog_service.model.ProcessedEvent;
import com.project.catalog_service.repository.jpa.data.ProcessedEventRepository;
import com.project.catalog_service.service.ProductService;
import com.project.common.message.dto.request.ProductUpdateRequest;

/**
 * 컨슈머 멱등성 회귀 테스트 (docs/11 §4 G8 — 재고 이중 차감 방지).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductUpdateMessageListenerTest {

    @Mock ProductService productService;
    @Mock ProcessedEventRepository processedEventRepository;
    @Mock StockShortageRecorder stockShortageRecorder;
    @Mock com.project.catalog_service.service.StockReservationService stockReservations;
    @InjectMocks ProductUpdateMessageListener listener;

    private ProductUpdateRequest requestWithEventId(String eventId) {
        ProductUpdateRequest request = new ProductUpdateRequest();
        request.setEventId(eventId);
        return request;
    }

    @Test
    @DisplayName("이미 처리한 eventId 는 재고 반영 없이 스킵된다 (이중 차감 방지)")
    void duplicateEvent_isSkipped() {
        when(processedEventRepository.existsById("order-1")).thenReturn(true);

        listener.productUpdated(requestWithEventId("order-1"));

        verify(productService, never()).productUpdated(any(), any());
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("처음 보는 eventId 는 처리 후 같은 트랜잭션에서 이력이 기록된다")
    void firstEvent_isProcessedAndRecorded() {
        when(processedEventRepository.existsById("order-2")).thenReturn(false);
        when(productService.productUpdated(any(), any())).thenReturn(new ProductService.StockApplyResult(null, java.util.List.of()));

        ProductUpdateRequest request = requestWithEventId("order-2");
        listener.productUpdated(request);

        verify(productService).productUpdated(org.mockito.ArgumentMatchers.eq(request), any());
        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("eventId 가 없는(구버전) 이벤트는 dedupe 없이 기존대로 처리된다 (하위호환)")
    void legacyEventWithoutId_isProcessedWithoutDedupe() {
        ProductUpdateRequest request = requestWithEventId(null);
        when(productService.productUpdated(any(), any())).thenReturn(new ProductService.StockApplyResult(null, java.util.List.of()));

        listener.productUpdated(request);

        verify(productService).productUpdated(org.mockito.ArgumentMatchers.eq(request), any());
        verify(processedEventRepository, never()).existsById(any());
        verify(processedEventRepository, never()).save(any());
    }

    /** 🔴 예전에는 모자라도 로그 한 줄이었다 — 이제 같은 트랜잭션에서 부족 기록을 남긴다(docs/53). */
    @Test
    @DisplayName("🔴 재고가 모자라면 같은 트랜잭션에서 부족 기록을 남긴다")
    void aShortageIsRecorded() {
        var shortages = java.util.List.of(new ProductService.ShortLine(1L, 5L, "M", 3, 2));
        when(processedEventRepository.existsById("order-9")).thenReturn(false);
        when(productService.productUpdated(any(), any())).thenReturn(new ProductService.StockApplyResult(null, shortages));

        listener.productUpdated(requestWithEventId("order-9"));

        verify(stockShortageRecorder).record("order-9", shortages);
        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("모자라지 않으면 부족 기록이 없다")
    void noShortageNoRecord() {
        when(productService.productUpdated(any(), any())).thenReturn(new ProductService.StockApplyResult(null, java.util.List.of()));

        listener.productUpdated(requestWithEventId("order-10"));

        verify(stockShortageRecorder, never()).record(any(), any());
    }

    // ── 결제 전 재고 예약(docs/59) ─────────────────────────────────────────

    @Test
    @DisplayName("🔴 결제 확정 차감은 다른 주문의 예약을 빼고 세고(자기 주문 제외), 같은 트랜잭션에서 자기 예약을 확정한다")
    void orderDecrementUsesOthersHoldsAndCommits() {
        ProductService.HeldQuantities others = (p, c, sz) -> 2;
        when(stockReservations.heldByOthers(77L)).thenReturn(others);
        when(productService.productUpdated(any(), any())).thenReturn(new ProductService.StockApplyResult(null, java.util.List.of()));

        ProductUpdateRequest request = requestWithEventId("order-77");
        listener.productUpdated(request);

        verify(productService).productUpdated(request, others);
        verify(stockReservations).commit(77L);
    }

    @Test
    @DisplayName("복원(restock)은 예약과 무관하다 — 확정도 없다")
    void restockIgnoresReservations() {
        ProductUpdateRequest request = requestWithEventId("restock-77-u1");
        request.setRestock(true);
        when(productService.productUpdated(any())).thenReturn(new ProductService.StockApplyResult(null, java.util.List.of()));

        listener.productUpdated(request);

        verify(productService).productUpdated(request);
        verify(stockReservations, never()).commit(any());
        verify(stockReservations, never()).heldByOthers(any());
    }

    @Test
    @DisplayName("주문 id 를 읽는다 — order-<id> 만, 다른 모양은 null")
    void orderIdParsing() {
        org.assertj.core.api.Assertions.assertThat(ProductUpdateMessageListener.orderIdOf("order-12345")).isEqualTo(12345L);
        org.assertj.core.api.Assertions.assertThat(ProductUpdateMessageListener.orderIdOf("restock-1-u1")).isNull();
        org.assertj.core.api.Assertions.assertThat(ProductUpdateMessageListener.orderIdOf("order-x")).isNull();
        org.assertj.core.api.Assertions.assertThat(ProductUpdateMessageListener.orderIdOf(null)).isNull();
    }
}
