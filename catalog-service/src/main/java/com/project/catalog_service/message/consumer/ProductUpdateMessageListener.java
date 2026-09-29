package com.project.catalog_service.message.consumer;

import org.springframework.stereotype.Service;

import com.project.catalog_service.message.producer.StockShortageRecorder;
import com.project.catalog_service.model.ProcessedEvent;
import com.project.catalog_service.repository.jpa.data.ProcessedEventRepository;
import com.project.catalog_service.service.ProductService;
import com.project.common.message.dto.request.ProductUpdateRequest;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductUpdateMessageListener {

    private final ProductService productService;
    private final ProcessedEventRepository processedEventRepository;
    private final StockShortageRecorder stockShortageRecorder;
    private final com.project.catalog_service.service.StockReservationService stockReservations;

    /** 결제 확정 차감의 eventId 는 {@code order-<주문 id>} 다(OrderSaga.publishProductStateUpdate). 아니면 null. */
    static Long orderIdOf(String eventId) {
        if (eventId == null || !eventId.startsWith("order-")) {
            return null;
        }
        try {
            return Long.parseLong(eventId.substring("order-".length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Transactional
    void productUpdated(ProductUpdateRequest request) {

        // 멱등성 (docs/11 §4 G8): Kafka at-least-once 재전달/사가 재실행으로 같은 주문의
        // 재고 차감이 두 번 들어와도 한 번만 반영한다. eventId 가 null 이면(구버전 생산자)
        // 기존 동작 그대로 처리한다(하위호환).
        String eventId = request.getEventId();
        if (eventId != null && processedEventRepository.existsById(eventId)) {
            log.info("Duplicate product-update event '{}' — already processed, skipping", eventId);
            return;
        }

        // 결제 전 재고 예약(docs/59): 결제 확정 차감은 <b>다른</b> 주문이 잡아 둔 수를 쓸 수 없고, 자기 주문의 예약은 쓴다.
        // 복원(restock)은 예약과 무관하다.
        Long orderId = request.isRestock() ? null : orderIdOf(eventId);
        ProductService.StockApplyResult result = request.isRestock()
                ? productService.productUpdated(request)
                : productService.productUpdated(request, stockReservations.heldByOthers(orderId));
        if (orderId != null) {
            // 같은 트랜잭션 — 차감이 롤백되면 확정도 롤백된다.
            stockReservations.commit(orderId);
        }

        // 🔴 예전에는 여기서 로그 한 줄("Product could not be updated")로 끝났다 — 결제된 주문이 재고 없이
        //    진행되고 아무도 몰랐다. 모자란 수를 같은 트랜잭션으로 아웃박스에 적는다 → order 가 자동 환불(docs/53).
        if (!request.isRestock() && result.hasShortage()) {
            stockShortageRecorder.record(eventId, result.shortages());
        }

        if (eventId != null) {
            // 재고 변경과 같은 트랜잭션으로 기록 → 둘 다 반영되거나 둘 다 롤백(원자성).
            // 동시 중복(희귀)은 PK 충돌로 전체 롤백 → 재전달 시 existsById 로 스킵된다.
            processedEventRepository.save(new ProcessedEvent(eventId));
        }
        log.info("Product is updated");
    }

}
