package com.project.common.dto.stock;

import java.time.Instant;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 재고 예약 결과 (docs/59). 모자라도 HTTP 200 이다 — "모자라다" 는 오류가 아니라 답이다. 부르는 쪽이 {@link #held} 로 가른다.
 *
 * <p>[JA] 在庫予約の結果 (docs/59)。足りなくても HTTP 200 である — 「足りない」はエラーではなく答えである。呼ぶ側が {@link #held} で分岐する。
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockReservationResult {

    /** 모든 줄을 잡았는가. false 면 아무것도 잡지 않았다. ／ [JA] 全行を押さえたか。false なら何も押さえていない。 */
    private boolean held;
    /** 잡았다면 언제까지. ／ [JA] 押さえたならいつまで。 */
    private Instant expiresAt;
    /** 모자란 줄 — 요청 수와 지금 잡을 수 있는 수. ／ [JA] 足りない行 — 要求数と今押さえられる数。 */
    private List<Shortage> shortages;

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Shortage {
        private Long productId;
        private Long colorId;
        private String size;
        private int requested;
        private int available;
    }
}
