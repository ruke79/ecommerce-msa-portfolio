package com.project.common.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 🔴 할인 값은 퍼센트 — catalog · cart · 화면이 같은 값을 낸다(옛 식 price − price/d 는 50% 를 2% 로 만들었다). */
@DisplayName("할인가 — 퍼센트")
class DiscountTest {

    private static BigDecimal of(String v) {
        return new BigDecimal(v);
    }

    @Test
    @DisplayName("🔴 10,000 원 50% → 5,000 · 5% → 9,500 · 70% → 3,000 (옛 식이면 9,800 · 8,000 · 9,857.14)")
    void percent() {
        assertThat(Discount.apply(of("10000"), 50)).isEqualByComparingTo("5000");
        assertThat(Discount.apply(of("10000"), 5)).isEqualByComparingTo("9500");
        assertThat(Discount.apply(of("10000"), 70)).isEqualByComparingTo("3000");
    }

    @Test
    @DisplayName("소수점 2자리 HALF_UP · 0 이하는 그대로 · 100 초과는 0 원에서 멈춘다 · null 은 null")
    void edges() {
        assertThat(Discount.apply(of("19.99"), 15)).isEqualByComparingTo("16.99");   // 16.9915
        assertThat(Discount.apply(of("10000"), 0)).isEqualByComparingTo("10000");
        assertThat(Discount.apply(of("10000"), -5)).isEqualByComparingTo("10000");
        assertThat(Discount.apply(of("10000"), 150)).isEqualByComparingTo("0");
        assertThat(Discount.apply(null, 50)).isNull();
    }
}
