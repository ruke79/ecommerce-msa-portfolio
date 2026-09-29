package com.project.order_service.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.common.constants.PaymentProvider;
import com.project.order_service.payment.fx.FxRateSource;

/**
 * 결제수단별 통화.
 *
 * <p>여기서 고정하는 것은 두 가지다: <b>환율 없이 조용히 청구하지 않는다</b>(59 USD 가
 * 59 KRW 가 되는 사고), 그리고 <b>소수 자릿수는 통화가 정한다</b>(JPY 에 ×100 하면
 * 100배, "1500.00" 은 PayPal 이 거절).
 */
@DisplayName("결제수단별 통화")
class PaymentCurrencyPolicyTest {

    private static PaymentCurrencyPolicy policy() {
        PaymentCurrencyPolicy p = new PaymentCurrencyPolicy();
        p.setBaseCurrency("USD");
        p.setProviders(Map.of("PAYPAL", "USD", "STRIPE", "JPY", "PORTONE", "KRW"));
        p.setRates(Map.of("KRW", new BigDecimal("1350"), "JPY", new BigDecimal("150.5")));
        return p;
    }

    @Test
    @DisplayName("상점 통화와 같은 제공자는 환율 1, 금액 그대로")
    void sameCurrency() {
        PaymentCurrencyPolicy.Charge c = policy().quote(59, PaymentProvider.PAYPAL);
        assertThat(c.currency()).isEqualTo("USD");
        assertThat(c.amount()).isEqualByComparingTo("59.00");
        assertThat(c.rate()).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("포트원은 KRW — 소수 없이 반올림")
    void krw() {
        PaymentCurrencyPolicy.Charge c = policy().quote(59, PaymentProvider.PORTONE);
        assertThat(c.currency()).isEqualTo("KRW");
        assertThat(c.amount()).isEqualByComparingTo("79650");
        assertThat(c.amount().scale()).isZero();
    }

    @Test
    @DisplayName("일본 테스트 — Stripe 를 JPY 로, 소수 없이")
    void jpy() {
        PaymentCurrencyPolicy.Charge c = policy().quote(59, PaymentProvider.STRIPE);
        assertThat(c.currency()).isEqualTo("JPY");
        // 59 × 150.5 = 8879.5 → 8880 (HALF_UP)
        assertThat(c.amount()).isEqualByComparingTo("8880");
        assertThat(c.amount().scale()).isZero();
        assertThat(c.rate()).isEqualByComparingTo("150.5");
    }

    /** 🔴 환율이 없으면 실패해야 한다. 조용히 1 로 두면 59 USD 주문이 59 JPY 로 청구된다. */
    @Test
    @DisplayName("🔴 환율이 없는 통화로는 청구를 만들지 않는다")
    void missingRateFails() {
        PaymentCurrencyPolicy p = policy();
        p.setProviders(Map.of("STRIPE", "EUR"));

        assertThatThrownBy(() -> p.quote(59, PaymentProvider.STRIPE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EUR")
                .hasMessageContaining("FX_RATE_EUR");
    }

    @Test
    @DisplayName("설정에 없는 제공자·제공자 없음은 상점 통화다")
    void unknownProviderFallsBackToBase() {
        PaymentCurrencyPolicy p = policy();
        p.setProviders(Map.of());
        assertThat(p.currencyFor(PaymentProvider.PORTONE)).isEqualTo("USD");
        assertThat(p.currencyFor(null)).isEqualTo("USD");
        assertThat(p.quote(10, null).currency()).isEqualTo("USD");
    }

    @Test
    @DisplayName("통화 코드는 대소문자·공백에 흔들리지 않는다")
    void normalizes() {
        PaymentCurrencyPolicy p = new PaymentCurrencyPolicy();
        p.setBaseCurrency(" usd ");
        p.setProviders(Map.of("PORTONE", "krw"));
        p.setRates(Map.of("krw", new BigDecimal("1300")));
        assertThat(p.quote(1, PaymentProvider.PORTONE).currency()).isEqualTo("KRW");
    }

    /** 🔴 JPY·KRW 는 0, USD 는 2 — 여기서 틀리면 Stripe 에 100배를 보낸다. */
    @Test
    @DisplayName("🔴 최소 단위는 통화가 정한다 — JPY 1500 은 1500, USD 59.98 은 5998")
    void minorUnits() {
        assertThat(PaymentCurrencyPolicy.fractionDigits("USD")).isEqualTo(2);
        assertThat(PaymentCurrencyPolicy.fractionDigits("JPY")).isZero();
        assertThat(PaymentCurrencyPolicy.fractionDigits("KRW")).isZero();

        assertThat(PaymentCurrencyPolicy.toMinorUnits(new BigDecimal("59.98"), "USD")).isEqualTo(5998);
        assertThat(PaymentCurrencyPolicy.toMinorUnits(new BigDecimal("1500"), "JPY")).isEqualTo(1500);
        assertThat(PaymentCurrencyPolicy.toMinorUnits(new BigDecimal("80000"), "KRW")).isEqualTo(80000);

        assertThat(PaymentCurrencyPolicy.fromMinorUnits(5998, "USD")).isEqualByComparingTo("59.98");
        assertThat(PaymentCurrencyPolicy.fromMinorUnits(1500, "JPY")).isEqualByComparingTo("1500");
    }

    // ── 실시간 환율 ──────────────────────────────────────────────────────────

    private static FxRateSource live(Map<String, String> rates) {
        return (base, quote) -> Optional.ofNullable(rates.get(quote)).map(BigDecimal::new);
    }

    @Test
    @DisplayName("실시간 환율이 있으면 그것을 쓴다 — 주문에 고정되는 값도 그것")
    void liveRateWins() {
        PaymentCurrencyPolicy p = policy();
        p.setLiveRates(live(Map.of("KRW", "1400.5")));

        PaymentCurrencyPolicy.Charge c = p.quote(new BigDecimal("59.98"), PaymentProvider.PORTONE);

        assertThat(c.rate()).isEqualByComparingTo("1400.5");
        assertThat(c.amount()).isEqualByComparingTo("84002");   // 59.98 × 1400.5 = 84001.99 → 84002
    }

    /** 🔴 통화 코드 혼동·데이터 사고로 청구액이 열 배가 되는 것을 막는 마지막 줄. */
    @Test
    @DisplayName("🔴 고정값과 너무 다르면 실시간 값을 버리고 고정값을 쓴다")
    void deviationGuardFallsBackToFixed() {
        PaymentCurrencyPolicy p = policy();
        p.getFx().setMaxDeviationPercent(new BigDecimal("30"));
        p.setLiveRates(live(Map.of("KRW", "13500")));   // 고정 1350 의 10배

        assertThat(p.quote(59, PaymentProvider.PORTONE).rate()).isEqualByComparingTo("1350");
    }

    @Test
    @DisplayName("편차 한도 안이면 실시간 값 — 한도 정확히는 통과")
    void deviationAtTheLimitPasses() {
        PaymentCurrencyPolicy p = policy();
        p.getFx().setMaxDeviationPercent(new BigDecimal("30"));
        p.setLiveRates(live(Map.of("KRW", "1755")));   // 1350 × 1.30

        assertThat(p.quote(59, PaymentProvider.PORTONE).rate()).isEqualByComparingTo("1755");
    }

    @Test
    @DisplayName("실시간이 비어 있으면(장애·통화 없음) 고정값으로 간다")
    void liveEmptyFallsBackToFixed() {
        PaymentCurrencyPolicy p = policy();
        p.setLiveRates(live(Map.of()));

        assertThat(p.quote(59, PaymentProvider.PORTONE).rate()).isEqualByComparingTo("1350");
    }

    /** 포트 계약은 "실패 = empty" 지만, 구현이 어겨도 주문 생성이 죽어서는 안 된다. */
    @Test
    @DisplayName("실시간 출처가 예외를 던져도 고정값으로 간다")
    void liveExceptionFallsBackToFixed() {
        PaymentCurrencyPolicy p = policy();
        p.setLiveRates((base, quote) -> { throw new IllegalStateException("boom"); });

        assertThat(p.quote(59, PaymentProvider.PORTONE).rate()).isEqualByComparingTo("1350");
    }

    @Test
    @DisplayName("고정값이 없는 통화는 실시간 값을 검사 없이 쓴다")
    void liveWithoutFixedIsUsedUnguarded() {
        PaymentCurrencyPolicy p = policy();
        p.setProviders(Map.of("STRIPE", "EUR"));
        p.setLiveRates(live(Map.of("EUR", "0.92")));

        PaymentCurrencyPolicy.Charge c = p.quote(new BigDecimal("100"), PaymentProvider.STRIPE);
        assertThat(c.currency()).isEqualTo("EUR");
        assertThat(c.rate()).isEqualByComparingTo("0.92");
        assertThat(c.amount()).isEqualByComparingTo("92.00");
    }

    @Test
    @DisplayName("실시간도 고정도 없으면 여전히 실패한다 — 조용히 1 로 두지 않는다")
    void neitherSourceStillFails() {
        PaymentCurrencyPolicy p = policy();
        p.setProviders(Map.of("STRIPE", "EUR"));
        p.setLiveRates(live(Map.of()));

        assertThatThrownBy(() -> p.quote(59, PaymentProvider.STRIPE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FX_RATE_EUR")
                .hasMessageContaining("실시간");
    }

    /** PayPal 은 JPY 에 소수를 거절한다(DECIMALS_NOT_SUPPORTED). */
    @Test
    @DisplayName("PayPal 문자열 — USD \"59.98\", JPY \"1500\", 지수 표기 없음")
    void paypalPlain() {
        assertThat(PaymentCurrencyPolicy.plain(new BigDecimal("59.98"), "USD")).isEqualTo("59.98");
        assertThat(PaymentCurrencyPolicy.plain(new BigDecimal("59"), "USD")).isEqualTo("59.00");
        assertThat(PaymentCurrencyPolicy.plain(new BigDecimal("1500"), "JPY")).isEqualTo("1500");
        assertThat(PaymentCurrencyPolicy.plain(new BigDecimal("9E+3"), "KRW")).isEqualTo("9000");
    }
}
