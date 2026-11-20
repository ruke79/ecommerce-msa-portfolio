package com.project.common.dto.stock;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 결제 전 재고 예약 요청 — order-service → catalog-service (docs/59).
 *
 * <p>주문 하나의 모든 줄을 <b>한꺼번에</b> 잡는다(전부 아니면 전무). 같은 주문으로 다시 부르면 기존 예약을 이어 붙인다
 * (만료만 늘린다) — 재시도·무통장 전환이 같은 호출이다.
 *
 * <p>[JA] 決済前の在庫予約リクエスト — order-service → catalog-service (docs/59)。
 * 注文一つの全行を<b>まとめて</b>押さえる(全部か無か)。同じ注文で再び呼ぶと既存の予約を引き継ぐ(期限だけ延ばす) — 再試行 · 銀行振込への切り替えが同じ呼び出しである。
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockReservationRequest {

    private Long orderId;
    /** 예약을 유지할 시간(초). catalog 가 상한을 둔다. ／ [JA] 予約を維持する時間(秒)。catalog が上限を設ける。 */
    private long ttlSeconds;
    private List<Line> lines;

    /** 재고 한 단위 — 상품·색상·사이즈. ／ [JA] 在庫の一単位 — 商品 · 色 · サイズ。 */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Line {
        private Long productId;
        private Long colorId;
        private String size;
        private int qty;
    }
}
