package com.project.order_service.stock;

import com.project.common.dto.stock.StockReservationRequest;
import com.project.common.dto.stock.StockReservationResult;

/**
 * catalog 의 재고 예약을 부르는 입구 — 전송(HTTP · gRPC)을 {@link OrderStockHold} 에서 떼어 낸다(docs/66).
 *
 * <p>구현은 둘이고 설정 {@code stock.rpc.transport}(STOCK_RPC_TRANSPORT)로 고른다 — {@code http}(기본) · {@code grpc}.
 * 결과의 의미는 같다: 모자라면 {@code held=false}, 호출 자체가 실패하면 예외(RuntimeException). {@link OrderStockHold} 는
 * 예외를 "catalog 에 닿지 못함" 으로 읽고 예약 없이 진행한다(결제 뒤 부족분 환불이 받는다 — docs/59).
 */
// [JA] catalog の在庫予約を呼ぶ入口 — 伝送(HTTP・gRPC)を OrderStockHold から切り離す。設定 stock.rpc.transport で選ぶ。
//      結果の意味は同じ: 不足なら held=false、呼び出し自体の失敗は例外。
public interface StockReservationPort {

    /** 주문의 모든 줄을 잡는다(연장 포함). */
    StockReservationResult reserve(StockReservationRequest request);

    /** 푼다 — 여러 번 불러도 같다. */
    void release(Long orderId);

    /** 지표 · 로그의 전송 이름. */
    String transport();
}
