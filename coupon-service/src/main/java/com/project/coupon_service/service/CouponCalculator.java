package com.project.coupon_service.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import com.project.common.constants.coupon.CouponScope;

/**
 * 쿠폰 할인 계산. 부수 효과가 없는 순수 계산이라 목(mock) 없이 테스트할 수 있다.
 *
 * <p><b>왜 "대상 소계에만" 인가.</b> 제약 있는 쿠폰이 장바구니 총액 전체를 깎으면,
 * 1,000원짜리 대상 상품 하나로 100만원 장바구니를 전부 할인받을 수 있다. 대상 줄만 골라
 * 그 소계에 할인율을 곱한다.
 */
public final class CouponCalculator {

    private CouponCalculator() {
    }

    /** 장바구니의 한 줄. 카테고리는 catalog 에서 알아낸 값이고, 모르면 null 이다. */
    public record Line(String productId, String categoryId, BigDecimal price, int qty) {

        /** 이 줄의 금액. price 는 단가다. */
        public BigDecimal amount() {
            if (price == null) {
                return BigDecimal.ZERO;
            }
            return price.multiply(BigDecimal.valueOf(Math.max(0, qty)));
        }
    }

    public record Result(BigDecimal eligibleSubtotal,
                         BigDecimal discountAmount,
                         BigDecimal totalAfterDiscount,
                         int eligibleItemCount) {
    }

    /**
     * 할인을 계산한다.
     *
     * <h3>🔴 쿠폰은 <b>상품 금액에만</b> 적용된다 — 배송비는 절대 깎지 않는다</h3>
     *
     * <p>예전에는 scope 에 따라 기준이 달랐다. 제약 <b>없는</b> 쿠폰은 {@code cartTotal}
     * (배송비 포함)을 통째로 깎았고, 제약 <b>있는</b> 쿠폰은 줄 금액({@code price×qty})만
     * 봤다. 같은 장바구니인데 쿠폰 종류만 달라도 할인액이 달랐고, 전자는 배송비까지
     * 할인해 주고 있었다.
     *
     * <p>이제 두 경우 모두 <b>대상 줄의 상품 금액 합</b>이 기준이다. scope=ALL 은 "모든
     * 줄이 대상" 일 뿐 특별한 경로가 아니다 — 계산에서 분기가 사라졌다.
     *
     * <p>⚠️ 이 변경으로 제약 없는 쿠폰의 할인액이 <b>배송비 몫만큼 줄어든다.</b> 덜 주는
     * 방향이라 되돌리기 쉽고, 무엇보다 배송비를 할인해 주던 쪽이 의도된 적이 없다.
     *
     * <p><b>반올림.</b> 먼저 "할인 후 금액"을 구하고 거기서 할인액을 역산한다:
     *
     * <pre>
     *   eligibleAfter  = round(subtotal × (100 - d) / 100)
     *   discountAmount = subtotal - eligibleAfter
     *   totalAfter     = cartTotal - discountAmount
     * </pre>
     *
     * 순서가 중요하다. {@code round(subtotal × d / 100)} 으로 할인액을 먼저 구하면 1전이
     * 어긋나는 경우가 생긴다(예: 0.05 에 30% → 0.04 냐 0.03 이냐).
     *
     * @param cartTotal <b>배송비를 포함한</b> 장바구니 총액. 할인 후 총액을 내기 위한 값이고
     *                  할인의 기준이 아니다.
     * @param lines     장바구니의 모든 줄. scope=ALL 이어도 비워 보내면 안 된다 —
     *                  비면 대상 금액이 0 이 되어 할인이 사라진다.
     */
    public static Result calculate(int discountPercent,
                                   CouponScope scope,
                                   Set<String> targets,
                                   BigDecimal cartTotal,
                                   List<Line> lines) {

        BigDecimal total = cartTotal == null ? BigDecimal.ZERO : cartTotal;

        List<Line> eligible = eligibleLines(scope, targets, lines);

        // scope 로 나누지 않는다. ALL 은 "모든 줄이 대상" 일 뿐이고, 어느 경우든 기준은
        // 대상 줄의 **상품 금액** 합이다 — 배송비는 Line.amount 에 들어 있지 않다.
        BigDecimal subtotal = eligible.stream()
                .map(Line::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int count = eligible.size();

        BigDecimal remaining = BigDecimal.valueOf(100L - clampPercent(discountPercent));
        BigDecimal eligibleAfter = subtotal
                .multiply(remaining)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        BigDecimal discountAmount = subtotal.subtract(eligibleAfter);

        // 할인액이 총액을 넘을 수는 없다. 상품 금액은 총액(배송비 포함)의 일부이므로
        // 정상적으로는 일어나지 않지만, 장바구니 총액과 줄 합계가 어긋나면 가능하다.
        if (discountAmount.compareTo(total) > 0) {
            discountAmount = total;
        }

        BigDecimal totalAfterDiscount = total.subtract(discountAmount)
                .setScale(2, RoundingMode.HALF_UP);

        return new Result(subtotal.setScale(2, RoundingMode.HALF_UP),
                discountAmount.setScale(2, RoundingMode.HALF_UP),
                totalAfterDiscount,
                count);
    }

    /** 이 쿠폰이 적용되는 줄만. */
    public static List<Line> eligibleLines(CouponScope scope, Set<String> targets, List<Line> lines) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        if (scope == CouponScope.ALL) {
            return List.copyOf(lines);
        }
        if (targets == null || targets.isEmpty()) {
            // 제약 쿠폰인데 대상이 하나도 없다 — 아무것도 적용되지 않는다. 전체 할인으로
            // 넘어가면 "대상을 지정하다 만 쿠폰"이 전 상품 할인이 되어 버린다.
            return List.of();
        }
        return lines.stream()
                .filter(line -> matches(scope, targets, line))
                .toList();
    }

    private static boolean matches(CouponScope scope, Collection<String> targets, Line line) {
        String value = scope == CouponScope.CATEGORY ? line.categoryId() : line.productId();
        // catalog 가 모르는 상품은 카테고리가 null 이다 — 대상이 아닌 것으로 본다.
        return value != null && targets.contains(value);
    }

    /**
     * 할인율을 0~100 으로 자른다.
     *
     * <p>쿠폰 생성에 할인율 검증이 없어 음수나 100 초과가 들어갈 수 있었다. 음수면 금액이
     * <b>늘어나고</b>, 100 초과면 할인 후 금액이 음수가 된다. 계산 단계에서 막는다.
     */
    private static int clampPercent(int discountPercent) {
        return Math.max(0, Math.min(100, discountPercent));
    }
}
