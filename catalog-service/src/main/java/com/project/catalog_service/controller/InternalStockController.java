package com.project.catalog_service.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.project.catalog_service.service.StockReservationService;
import com.project.common.dto.stock.StockReservationRequest;
import com.project.common.dto.stock.StockReservationResult;

import lombok.RequiredArgsConstructor;

/**
 * 재고 예약 — <b>내부 전용</b>(order-service 가 부른다, docs/59). 인가는 {@code AdminWriteInterceptor} 가
 * {@code /api/internal/**} 경로로 건다(신원 헤더 없는 내부 호출·관리자만).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/internal/stock/reservations")
public class InternalStockController {

    private final StockReservationService reservations;

    /** 잡기(다시 잡기·연장 포함). 모자라도 200 — held=false 와 모자란 줄. */
    /**
     * 🔒 잘못된 요청(줄 없음 · 수량 1 미만)은 400 — 🐛 예전에는 500 이라 order 가 "catalog 에 닿지 못함" 으로 읽고 예약 없이 주문을
     * 진행했다(fail-open). 400 은 order 의 OrderStockHold 가 거절(REJECTED)로 읽는다 — gRPC 의 INVALID_ARGUMENT 와 같다.
     * [JA] 不正な要求は 400 — 以前は 500 で、order が「到達できない」と読み予約なしで注文を進めていた。
     */
    @PostMapping
    public ResponseEntity<StockReservationResult> reserve(@RequestBody StockReservationRequest request) {
        try {
            return ResponseEntity.ok(reservations.reserve(request));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /** 풀기 — 여러 번 불러도 같다. */
    @PostMapping("/{orderId}/release")
    public ResponseEntity<Void> release(@PathVariable("orderId") Long orderId) {
        reservations.release(orderId);
        return ResponseEntity.noContent().build();
    }
}
