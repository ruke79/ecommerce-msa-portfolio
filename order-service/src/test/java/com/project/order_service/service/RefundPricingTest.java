package com.project.order_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.order_service.service.RefundPricing.Item;
import com.project.order_service.service.RefundPricing.Line;
import com.project.order_service.service.RefundPricing.Quote;

/**
 * 부분 환불의 금액 — 서버가 정한다 (docs/49 §10).
 *
 * <p>여기 고정하는 것: 마지막 환불은 남은 전부라 <b>부분 환불의 합은 정확히 총액</b>이고,
 * 그 전의 환불은 <b>할인을 나눠 뺀 단가</b>이며 배송비는 돌려주지 않는다.
 */
@DisplayName("부분 환불 금액")
class RefundPricingTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    /** 2 × 20.00 + 1 × 10.00 = 50.00 상품, 배송비 5.00, 할인 없음 → 총 55.00 */
    private static final List<Line> LINES = List.of(
            new Line(1L, d("20.00"), 2, 0),
            new Line(2L, d("10.00"), 1, 0));

    @Test
    @DisplayName("요청이 비어 있으면 남은 전부 — 배송비까지 총액 그대로")
    void emptyRequestMeansEverything() {
        Quote q = RefundPricing.quote(d("55.00"), d("55.00"), d("5.00"), BigDecimal.ZERO, LINES, null);

        assertThat(q.completesOrder()).isTrue();
        assertThat(q.storeAmount()).isEqualByComparingTo("55.00");
        assertThat(q.items()).containsExactly(new Item(1L, 2), new Item(2L, 1));
    }

    @Test
    @DisplayName("일부만이면 단가 × 수량 — 배송비는 돌려주지 않는다")
    void partialIsUnitPriceOnly() {
        Quote q = RefundPricing.quote(d("55.00"), d("55.00"), d("5.00"), BigDecimal.ZERO, LINES,
                List.of(new Item(1L, 1)));

        assertThat(q.completesOrder()).isFalse();
        assertThat(q.storeAmount()).isEqualByComparingTo("20.00");
    }

    /** 쿠폰 10%: 정가로 돌려주면 손님이 낸 것보다 더 받는다. */
    @Test
    @DisplayName("할인은 품목 금액 비율로 나눠 뺀다 — 10% 쿠폰이면 품목도 10% 깎인 값")
    void discountIsProrated() {
        // 상품 50 → 할인 5 → 45 + 배송 5 = 50
        Quote q = RefundPricing.quote(d("50.00"), d("55.00"), d("5.00"), BigDecimal.ZERO, LINES,
                List.of(new Item(1L, 1)));

        assertThat(q.storeAmount()).as("20 − 20×(5/50) = 18").isEqualByComparingTo("18.00");
    }

    /** 🔴 부분 환불을 몇 번 하든 합은 총액이다 — 반올림 찌꺼기와 배송비는 마지막에 정리된다. */
    @Test
    @DisplayName("🔴 마지막 환불은 남은 전부 — 부분 환불의 합은 정확히 총액")
    void partsSumToTheTotal() {
        List<Line> lines = List.of(
                new Line(1L, d("19.99"), 3, 0),   // 59.97
                new Line(2L, d("0.01"), 1, 0));   // 0.01 → 상품 59.98
        // 할인 7% → 55.78, 배송 4.90 → 총 60.68
        BigDecimal total = d("60.68");
        BigDecimal before = d("64.88");
        BigDecimal ship = d("4.90");

        Quote first = RefundPricing.quote(total, before, ship, BigDecimal.ZERO, lines, List.of(new Item(1L, 1)));
        BigDecimal paid = first.storeAmount();
        List<Line> after1 = List.of(new Line(1L, d("19.99"), 3, 1), new Line(2L, d("0.01"), 1, 0));

        Quote second = RefundPricing.quote(total, before, ship, paid, after1, List.of(new Item(1L, 1)));
        paid = paid.add(second.storeAmount());
        List<Line> after2 = List.of(new Line(1L, d("19.99"), 3, 2), new Line(2L, d("0.01"), 1, 0));

        Quote last = RefundPricing.quote(total, before, ship, paid, after2, null);

        assertThat(first.completesOrder()).isFalse();
        assertThat(second.completesOrder()).isFalse();
        assertThat(last.completesOrder()).isTrue();
        assertThat(paid.add(last.storeAmount())).isEqualByComparingTo(total);
    }

    @Test
    @DisplayName("같은 품목이 두 번 오면 합친다")
    void duplicateItemsAreMerged() {
        Quote q = RefundPricing.quote(d("55.00"), d("55.00"), d("5.00"), BigDecimal.ZERO, LINES,
                List.of(new Item(1L, 1), new Item(1L, 1)));

        assertThat(q.items()).containsExactly(new Item(1L, 2));
        assertThat(q.storeAmount()).isEqualByComparingTo("40.00");
    }

    @Test
    @DisplayName("남은 수량을 넘거나, 이 주문의 품목이 아니거나, 0 이하면 거절")
    void invalidRequestsAreRejected() {
        assertThatThrownBy(() -> RefundPricing.quote(d("55"), d("55"), d("5"), BigDecimal.ZERO, LINES,
                List.of(new Item(1L, 3))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("남은 수량");
        assertThatThrownBy(() -> RefundPricing.quote(d("55"), d("55"), d("5"), BigDecimal.ZERO, LINES,
                List.of(new Item(9L, 1))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("이 주문의 품목이 아닙니다");
        assertThatThrownBy(() -> RefundPricing.quote(d("55"), d("55"), d("5"), BigDecimal.ZERO, LINES,
                List.of(new Item(1L, 0))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("1 이상");
    }

    @Test
    @DisplayName("이미 환불된 수량은 남은 수량에서 빠진다")
    void alreadyRefundedUnitsAreExcluded() {
        List<Line> lines = List.of(new Line(1L, d("20.00"), 2, 1), new Line(2L, d("10.00"), 1, 1));

        Quote q = RefundPricing.quote(d("55.00"), d("55.00"), d("5.00"), d("30.00"), lines, null);

        assertThat(q.items()).containsExactly(new Item(1L, 1));
        assertThat(q.completesOrder()).isTrue();
        assertThat(q.storeAmount()).as("남은 돈 전부").isEqualByComparingTo("25.00");
    }

    @Test
    @DisplayName("전부 환불된 주문에 또 오면 거절")
    void nothingLeftIsRejected() {
        List<Line> lines = List.of(new Line(1L, d("20.00"), 2, 2));
        assertThatThrownBy(() -> RefundPricing.quote(d("40"), d("40"), BigDecimal.ZERO, d("40"), lines, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("이미 전부");
    }

    /** 품목 정보가 없는 옛 주문은 남은 돈 전부다 — 부분 환불이 불가능할 뿐 환불은 된다. */
    @Test
    @DisplayName("품목이 없는 옛 주문은 남은 돈 전부")
    void legacyOrderWithoutLines() {
        Quote q = RefundPricing.quote(d("55.00"), d("55.00"), d("5.00"), BigDecimal.ZERO, List.of(), null);
        assertThat(q.completesOrder()).isTrue();
        assertThat(q.storeAmount()).isEqualByComparingTo("55.00");
    }
}
