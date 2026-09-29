package com.project.coupon_service.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.common.constants.coupon.CouponScope;
import com.project.coupon_service.service.CouponCalculator.Line;

/**
 * 쿠폰 할인 계산.
 *
 * <p>예전에는 계산이 {@code CouponService.applyCoupon} 안에 네 줄로 박혀 있었고, 늘
 * <b>장바구니 총액 전체</b>에 할인율을 곱했다. 제약(카테고리·상품)이 생기면서 "얼마에
 * 곱할 것인가"가 판단이 필요한 문제가 됐다 — 그래서 떼어내 테스트한다.
 */
class CouponCalculatorTest {

    private static Line line(String productId, String categoryId, String price, int qty) {
        return new Line(productId, categoryId, new BigDecimal(price), qty);
    }

    private static BigDecimal won(String v) {
        return new BigDecimal(v).setScale(2, RoundingMode.HALF_UP);
    }

    /** 배송비가 없는 장바구니 — 상품 금액 합이 곧 총액이다. */
    private static List<Line> cartWorth(String amount) {
        return List.of(line("p1", "cat", amount, 1));
    }

    // ── 제약 없는 쿠폰 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("ALL 쿠폰은 모든 줄의 상품 금액을 깎는다")
    void allScopeDiscountsEveryLine() {
        var r = CouponCalculator.calculate(10, CouponScope.ALL, Set.of(),
                new BigDecimal("1000"), cartWorth("1000"));

        assertThat(r.totalAfterDiscount()).isEqualByComparingTo(won("900"));
        assertThat(r.discountAmount()).isEqualByComparingTo(won("100"));
        assertThat(r.eligibleSubtotal()).isEqualByComparingTo(won("1000"));
    }

    /**
     * 🔴 <b>쿠폰은 배송비를 깎지 않는다.</b>
     *
     * <p>예전에는 제약 없는 쿠폰이 {@code cartTotal}(배송비 포함)을 통째로 깎았고, 제약
     * 있는 쿠폰은 줄 금액만 봤다 — 같은 장바구니인데 쿠폰 종류만 달라도 할인액이 달랐다.
     *
     * <p>상품 10,000원 + 배송비 3,000원 = 13,000원 장바구니에 10% 쿠폰:
     * 예전에는 1,300원이 깎였고(배송비의 10%인 300원 포함), 이제 1,000원이 깎인다.
     */
    @Test
    @DisplayName("배송비는 할인하지 않는다 — 상품 금액에만 적용된다")
    void shippingIsNeverDiscounted() {
        // 총액 13,000원 중 상품은 10,000원, 나머지 3,000원은 배송비다.
        var r = CouponCalculator.calculate(10, CouponScope.ALL, Set.of(),
                new BigDecimal("13000"), cartWorth("10000"));

        assertThat(r.discountAmount())
                .as("배송비 3,000원의 10%%까지 깎이면 안 된다")
                .isEqualByComparingTo(won("1000"));
        assertThat(r.totalAfterDiscount()).isEqualByComparingTo(won("12000"));
    }

    /** 제약 있는 쿠폰도 같은 답이어야 한다 — 쿠폰 종류로 할인액이 갈리면 안 된다. */
    @Test
    @DisplayName("제약 있는 쿠폰도 같은 기준이다 — 종류로 할인액이 갈리지 않는다")
    void bothScopesAgreeOnTheBase() {
        List<Line> cart = List.of(line("p1", "cat", "10000", 1));

        var all = CouponCalculator.calculate(10, CouponScope.ALL, Set.of(),
                new BigDecimal("13000"), cart);
        var product = CouponCalculator.calculate(10, CouponScope.PRODUCT, Set.of("p1"),
                new BigDecimal("13000"), cart);

        assertThat(all.discountAmount()).isEqualByComparingTo(product.discountAmount());
    }

    /**
     * 🔒 반올림 회귀 방지. 할인액을 먼저 반올림하는 식으로 바꾸면 1전이 어긋나는 값이
     * 있다(0.05 에 30%: 0.04 냐 0.03 이냐). <b>배송비가 없는</b> 장바구니에서는 상품 금액이
     * 곧 총액이므로 결과가 교과서 식과 정확히 같아야 한다.
     */
    @Test
    @DisplayName("배송비가 없으면 금액이 교과서 식과 완전히 같다 — 반올림 경계까지")
    void roundingMatchesTheTextbookFormula() {
        for (String total : List.of("0.05", "0.15", "1", "33.33", "99.99", "1234.56", "1000")) {
            for (int d : List.of(0, 5, 10, 30, 33, 50, 99, 100)) {
                BigDecimal expected = new BigDecimal(total)
                        .multiply(BigDecimal.valueOf(100 - d))
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

                var r = CouponCalculator.calculate(d, CouponScope.ALL, Set.of(),
                        new BigDecimal(total), cartWorth(total));

                assertThat(r.totalAfterDiscount())
                        .as("total=%s discount=%d", total, d)
                        .isEqualByComparingTo(expected);
            }
        }
    }

    // ── 카테고리 제약 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("카테고리 쿠폰은 그 카테고리 줄의 소계에만 할인한다")
    void categoryScopeDiscountsOnlyEligibleSubtotal() {
        List<Line> cart = List.of(
                line("p1", "cat-fashion", "10000", 1),   // 대상
                line("p2", "cat-books", "90000", 1));    // 대상 아님

        var r = CouponCalculator.calculate(10, CouponScope.CATEGORY, Set.of("cat-fashion"),
                new BigDecimal("100000"), cart);

        assertThat(r.eligibleSubtotal()).isEqualByComparingTo(won("10000"));
        assertThat(r.discountAmount()).isEqualByComparingTo(won("1000"));
        assertThat(r.totalAfterDiscount()).isEqualByComparingTo(won("99000"));
        assertThat(r.eligibleItemCount()).isEqualTo(1);
    }

    /**
     * 🐛 이게 "대상 소계에만" 을 고른 이유다. 전체 할인이었다면 1,000원짜리 대상 상품
     * 하나로 100,000원 장바구니 전체가 10% 깎인다 — 10,000원 할인.
     */
    @Test
    @DisplayName("대상 상품이 싸다고 장바구니 전체가 깎이지 않는다")
    void cheapEligibleItemCannotDiscountWholeCart() {
        List<Line> cart = List.of(
                line("p1", "cat-fashion", "1000", 1),
                line("p2", "cat-books", "99000", 1));

        var r = CouponCalculator.calculate(10, CouponScope.CATEGORY, Set.of("cat-fashion"),
                new BigDecimal("100000"), cart);

        assertThat(r.discountAmount()).isEqualByComparingTo(won("100"));   // 10,000 이 아니다
        assertThat(r.totalAfterDiscount()).isEqualByComparingTo(won("99900"));
    }

    @Test
    @DisplayName("수량을 곱해 소계를 낸다 — price 는 단가다")
    void quantityCounts() {
        List<Line> cart = List.of(line("p1", "cat-fashion", "1000", 3));

        var r = CouponCalculator.calculate(10, CouponScope.CATEGORY, Set.of("cat-fashion"),
                new BigDecimal("3000"), cart);

        assertThat(r.eligibleSubtotal()).isEqualByComparingTo(won("3000"));
        assertThat(r.discountAmount()).isEqualByComparingTo(won("300"));
    }

    @Test
    @DisplayName("대상 상품이 장바구니에 없으면 할인이 0이고 줄 수도 0이다")
    void noEligibleItems() {
        List<Line> cart = List.of(line("p2", "cat-books", "50000", 1));

        var r = CouponCalculator.calculate(10, CouponScope.CATEGORY, Set.of("cat-fashion"),
                new BigDecimal("50000"), cart);

        assertThat(r.eligibleItemCount()).isZero();
        assertThat(r.discountAmount()).isEqualByComparingTo(won("0"));
        assertThat(r.totalAfterDiscount()).isEqualByComparingTo(won("50000"));
    }

    // ── 상품 제약 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("상품 쿠폰은 지정한 상품 줄만 본다")
    void productScope() {
        List<Line> cart = List.of(
                line("p1", "cat-fashion", "10000", 1),
                line("p2", "cat-fashion", "10000", 1));

        var r = CouponCalculator.calculate(50, CouponScope.PRODUCT, Set.of("p2"),
                new BigDecimal("20000"), cart);

        assertThat(r.eligibleSubtotal()).isEqualByComparingTo(won("10000"));
        assertThat(r.discountAmount()).isEqualByComparingTo(won("5000"));
        assertThat(r.eligibleItemCount()).isEqualTo(1);
    }

    // ── 망가진 입력 ──────────────────────────────────────────────────────────

    /**
     * catalog 가 모르는 상품은 카테고리가 null 로 남는다. 그것을 대상으로 세면 "모르는
     * 상품이 전부 할인 대상"이 된다 — 반대로 틀려야 한다.
     */
    @Test
    @DisplayName("카테고리를 모르는 줄은 대상이 아니다")
    void unknownCategoryIsNotEligible() {
        List<Line> cart = List.of(line("p1", null, "10000", 1));

        var r = CouponCalculator.calculate(10, CouponScope.CATEGORY, Set.of("cat-fashion"),
                new BigDecimal("10000"), cart);

        assertThat(r.eligibleItemCount()).isZero();
    }

    /**
     * 대상을 지정하다 만 쿠폰이 전 상품 할인이 되면 안 된다. 만드는 쪽에서도 막지만
     * (CouponService.create), 계산에서도 안전한 쪽으로 떨어져야 한다.
     */
    @Test
    @DisplayName("제약 쿠폰인데 대상이 비어 있으면 아무것도 적용되지 않는다")
    void constrainedCouponWithNoTargetsDiscountsNothing() {
        List<Line> cart = List.of(line("p1", "cat-fashion", "10000", 1));

        var r = CouponCalculator.calculate(10, CouponScope.CATEGORY, Set.of(),
                new BigDecimal("10000"), cart);

        assertThat(r.eligibleItemCount()).isZero();
        assertThat(r.totalAfterDiscount()).isEqualByComparingTo(won("10000"));
    }

    /**
     * 쿠폰 생성에 할인율 검증이 없어 음수나 100 초과가 들어갈 수 있었다.
     * 음수면 결제 금액이 <b>늘어나고</b>, 100 초과면 음수가 된다.
     */
    @Test
    @DisplayName("할인율이 범위를 벗어나도 금액이 늘거나 음수가 되지 않는다")
    void clampsDiscountPercent() {
        var negative = CouponCalculator.calculate(-50, CouponScope.ALL, Set.of(),
                new BigDecimal("1000"), cartWorth("1000"));
        assertThat(negative.totalAfterDiscount()).isEqualByComparingTo(won("1000"));

        var over = CouponCalculator.calculate(150, CouponScope.ALL, Set.of(),
                new BigDecimal("1000"), cartWorth("1000"));
        assertThat(over.totalAfterDiscount()).isEqualByComparingTo(won("0"));
        assertThat(over.totalAfterDiscount()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("빈 장바구니·null 총액에도 터지지 않는다")
    void handlesEmptyInput() {
        var r = CouponCalculator.calculate(10, CouponScope.CATEGORY, Set.of("c"), null, null);

        assertThat(r.totalAfterDiscount()).isEqualByComparingTo(won("0"));
        assertThat(r.eligibleItemCount()).isZero();
    }

    @Test
    @DisplayName("단가가 없는 줄은 0원으로 센다")
    void nullPriceCountsAsZero() {
        List<Line> cart = List.of(new Line("p1", "cat-fashion", null, 2));

        var r = CouponCalculator.calculate(10, CouponScope.CATEGORY, Set.of("cat-fashion"),
                new BigDecimal("0"), cart);

        assertThat(r.eligibleSubtotal()).isEqualByComparingTo(won("0"));
    }
}
