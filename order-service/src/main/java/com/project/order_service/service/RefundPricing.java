package com.project.order_service.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 부분 환불의 금액 — <b>서버가</b> 정한다.
 *
 * <p>{@link OrderPricing} 이 주문 금액을 정하듯, 환불액도 요청 본문에서 오지 않는다. 본문에
 * 있는 것은 어느 품목 몇 개뿐이고, 여기서 그것을 돈으로 바꾼다.
 *
 * <h3>규칙</h3>
 *
 * <ol>
 *   <li><b>마지막 환불은 남은 전부다.</b> 요청이 아직 환불되지 않은 수량 전부를 덮으면 금액은
 *       {@code total − 지금까지 환불액} 이다. 배송비와 반올림 찌꺼기가 여기서 정리되므로
 *       부분 환불을 몇 번 하든 합은 정확히 {@code total} 이다.</li>
 *   <li><b>그 전의 환불은 품목 단가에서 할인을 나눠 뺀 값이다.</b> 쿠폰이 10% 였으면 품목도
 *       10% 깎인 값으로 돌려준다 — 정가로 돌려주면 손님이 낸 것보다 더 받는다. 할인은
 *       상품 금액(배송비 뺀 값) 대비 품목 금액의 비율로 나눈다. 배송비는 부분 환불에서
 *       돌려주지 않는다 — 나머지 물건은 여전히 배송됐다.</li>
 *   <li>어떤 경우에도 남은 환불 가능액을 넘지 않는다.</li>
 * </ol>
 *
 * <p>요청 검증도 여기서 한다: 이 주문의 품목인가, 수량이 1 이상인가, 남은 수량을 넘지 않는가.
 * 같은 품목이 두 번 오면 합친다. 규칙 위반은 {@link IllegalArgumentException} — 호출자가
 * 도메인 예외로 바꾼다.
 */
public final class RefundPricing {

    private RefundPricing() {
    }

    /** 주문 품목 한 줄 — 환불 계산에 필요한 것만. */
    public record Line(Long orderProductId, BigDecimal price, int qty, int refundedQty) {
        public int remaining() {
            return Math.max(0, qty - refundedQty);
        }
    }

    /** 요청된 품목·수량. */
    public record Item(Long orderProductId, int qty) {
    }

    /**
     * 정해진 환불.
     *
     * @param storeAmount    상점 통화의 환불액(소수 둘째 자리)
     * @param completesOrder 이 환불로 남은 수량이 0 이 되는가 — 그러면 주문은 REFUNDED 다
     * @param items          실제로 적용되는 품목·수량(비어 있던 요청은 "남은 전부" 로 채워진다)
     */
    public record Quote(BigDecimal storeAmount, boolean completesOrder, List<Item> items) {
    }

    /**
     * @param total               주문 총액(할인 후, 배송비 포함)
     * @param totalBeforeDiscount 할인 전 총액(배송비 포함)
     * @param shippingPrice       그중 배송비
     * @param refundedSoFar       지금까지 환불한 상점 통화 금액
     * @param lines               주문 품목
     * @param requested           요청. {@code null}·빈 목록이면 남은 전부
     */
    public static Quote quote(BigDecimal total, BigDecimal totalBeforeDiscount, BigDecimal shippingPrice,
            BigDecimal refundedSoFar, List<Line> lines, List<Item> requested) {

        Map<Long, Line> byId = new LinkedHashMap<>();
        for (Line line : lines) {
            byId.put(line.orderProductId(), line);
        }

        // 요청을 품목별로 합치고 검증한다. 비어 있으면 남은 전부.
        Map<Long, Integer> wanted = new LinkedHashMap<>();
        if (requested == null || requested.isEmpty()) {
            for (Line line : lines) {
                if (line.remaining() > 0) {
                    wanted.put(line.orderProductId(), line.remaining());
                }
            }
        } else {
            for (Item item : requested) {
                if (item == null || item.orderProductId() == null) {
                    throw new IllegalArgumentException("환불할 품목이 비어 있습니다.");
                }
                if (item.qty() < 1) {
                    throw new IllegalArgumentException("환불 수량은 1 이상이어야 합니다: orderProductId=" + item.orderProductId());
                }
                Line line = byId.get(item.orderProductId());
                if (line == null) {
                    throw new IllegalArgumentException("이 주문의 품목이 아닙니다: orderProductId=" + item.orderProductId());
                }
                int sum = wanted.getOrDefault(item.orderProductId(), 0) + item.qty();
                if (sum > line.remaining()) {
                    throw new IllegalArgumentException("환불 수량이 남은 수량을 넘습니다: orderProductId="
                            + item.orderProductId() + " 남은=" + line.remaining() + " 요청=" + sum);
                }
                wanted.put(item.orderProductId(), sum);
            }
        }

        BigDecimal remainder = nz(total).subtract(nz(refundedSoFar)).max(BigDecimal.ZERO);

        // 품목이 없는 옛 주문(또는 품목이 전부 환불됐는데 돈이 남은 주문)은 남은 돈 전부다.
        boolean completes = true;
        for (Line line : lines) {
            if (line.remaining() - wanted.getOrDefault(line.orderProductId(), 0) > 0) {
                completes = false;
                break;
            }
        }
        if (wanted.isEmpty() && !lines.isEmpty()) {
            throw new IllegalArgumentException("환불할 수량이 없습니다 — 이미 전부 환불됐습니다.");
        }

        List<Item> applied = new ArrayList<>();
        wanted.forEach((id, q) -> applied.add(new Item(id, q)));

        if (completes) {
            return new Quote(remainder.setScale(2, RoundingMode.HALF_UP), true, applied);
        }

        BigDecimal merchandise = nz(totalBeforeDiscount).subtract(nz(shippingPrice)).max(BigDecimal.ZERO);
        BigDecimal discount = nz(totalBeforeDiscount).subtract(nz(total)).max(BigDecimal.ZERO);

        BigDecimal sum = BigDecimal.ZERO;
        for (Item item : applied) {
            Line line = byId.get(item.orderProductId());
            BigDecimal gross = nz(line.price()).multiply(BigDecimal.valueOf(line.qty()));
            BigDecimal share = merchandise.signum() > 0
                    ? discount.multiply(gross).divide(merchandise, 6, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            BigDecimal refundableLine = gross.subtract(share).max(BigDecimal.ZERO);
            BigDecimal amount = refundableLine.multiply(BigDecimal.valueOf(item.qty()))
                    .divide(BigDecimal.valueOf(line.qty()), 2, RoundingMode.HALF_UP);
            sum = sum.add(amount);
        }
        return new Quote(sum.min(remainder).setScale(2, RoundingMode.HALF_UP), false, applied);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
