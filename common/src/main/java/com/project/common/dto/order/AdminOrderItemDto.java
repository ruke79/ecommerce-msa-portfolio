package com.project.common.dto.order;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 관리자 주문 목록의 품목 한 줄 — 부분 환불·반품의 수량 상태.
 *
 * <p>네 수량의 뜻(docs/49 §10): {@code refundedQty} 돈이 돌아간 수, {@code returnedQty}
 * 물건이 돌아온(또는 창고를 떠나지 않은) 수, {@code restockedQty} 재고로 되돌린 수,
 * {@code discardedQty} 파손 등으로 재고에 넣지 않은 수. 아직 재고로 갈 수 있는 수는
 * {@code returned − restocked − discarded} 다.
 *
 * <p>[JA] 管理者注文一覧の品目一行 — 部分返金 · 返品の数量状態。
 * 四つの数量の意味(docs/49 §10): {@code refundedQty} お金が戻った数、{@code returnedQty} 物が戻った(または倉庫を出なかった)数、{@code restockedQty} 在庫に
 * 戻した数、{@code discardedQty} 破損などで在庫に入れなかった数。まだ在庫に戻せる数は {@code returned − restocked − discarded} である。
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminOrderItemDto {

    /**
     * 🐛 <b>문자열이다.</b> {@link AdminOrderSummaryDto#orderId} 와 같은 이유 — TSID 다.
     *
     * <p>여기는 표시만 하는 값이 아니다. 부분 환불·반품 입고가 이 id 로 품목을 지목하므로,
     * 값이 바뀌면 서버가 그 품목을 찾지 못해 <b>조용히 아무것도 환불되지 않는다.</b>
     *
     * <p>[JA] 🐛 <b>文字列である。</b>{@link AdminOrderSummaryDto#orderId} と同じ理由 — TSID である。
     * ここは表示だけの値ではない。部分返金 · 返品入庫がこの id で品目を指すので、値が変わるとサーバーがその品目を見つけられず<b>静かに何も返金されない。</b>
     */
    private String orderProductId;
    private String name;
    private String size;
    /** 사이즈 한 줄의 SKU(catalog V17) — 주문 시점 값. 이전 주문은 null. ／ [JA] サイズ一行の SKU(catalog V17) — 注文時点の値。以前の注文は null。 */
    private String skuCode;
    private BigDecimal price;
    private int qty;
    private int refundedQty;
    private int returnedQty;
    private int restockedQty;
    private int discardedQty;
    /** 재고가 모자라 배정되지 못한 수(docs/53). 보낼 수는 {@code qty − shortQty − (발송 전 환불)}. ／ [JA] 在庫が足りず割り当てられなかった数(docs/53)。送れる数は {@code qty − shortQty − (発送前返金)}。 */
    private int shortQty;
}
