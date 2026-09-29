package com.project.order_service.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import com.project.common.constants.PaymentProvider;
import com.project.order_service.payment.fx.FxRateSource;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * 결제수단마다 다른 통화.
 *
 * <h3>두 통화가 있다</h3>
 *
 * <ul>
 *   <li><b>상점 통화</b>({@code payment.base-currency}) — 카탈로그 가격·{@code Order.total}·
 *       매출 집계·pay-service 의 통화. <b>하나뿐</b>이고 바뀌지 않는다.</li>
 *   <li><b>청구 통화</b>({@code payment.providers.<PROVIDER>}) — 그 제공자에게 실제로
 *       결제를 요청하는 통화. 포트원은 KRW, 일본 테스트면 Stripe 를 JPY 로.</li>
 * </ul>
 *
 * <p>둘이 다르면 주문을 만들 때 환율로 <b>청구 금액을 한 번 정해 주문에 저장</b>한다
 * ({@code Order.currency · chargedAmount · fxRate}). 결제 확인과 환불은 그 저장된 값과
 * 대조한다 — 그 사이 환율이 바뀌어도 "그때 청구한 금액" 이 기준이다. 재계산하면 환율이
 * 바뀐 뒤의 확인·환불이 전부 거부된다.
 *
 * <h3>환율 — 고정값이 기본, 실시간은 선택</h3>
 *
 * <p>{@code payment.rates.<CUR>} = 상점 통화 1 단위가 그 통화로 얼마인가(고정값). 값이 없는
 * 통화로 청구하려 하면 <b>기동이 아니라 주문 생성이 실패</b>한다. 설정을 빠뜨린 결과가
 * "환율 1 로 조용히 청구" 여서는 안 된다(59 USD 주문이 59 KRW 가 된다).
 *
 * <p>{@code payment.fx.live.enabled=true} 면 {@link FxRateSource}(Frankfurter) 의 값을
 * <b>먼저</b> 쓴다. 그래도 고정값은 지우지 않는다 — 두 역할이 남는다:
 * <ul>
 *   <li><b>대비책</b> — API 가 죽었거나 그 통화가 없으면 고정값.</li>
 *   <li><b>상한선</b> — 실시간 값이 고정값과 {@code payment.fx.max-deviation-percent}(기본 30)
 *       넘게 다르면 고정값을 쓰고 경고한다. 통화 코드 혼동·데이터 사고로 청구액이 열 배가
 *       되는 것을 막는 마지막 줄이다. 고정값이 없는 통화는 이 검사 없이 실시간 값을 쓴다.</li>
 * </ul>
 * 어느 쪽을 썼든 환율은 <b>주문에 고정</b>된다({@code fx_rate}). 실시간이라는 것은 "주문
 * 생성 시점의 값" 이지 "결제 시점마다 다시 계산" 이 아니다.
 *
 * <h3>소수 자릿수는 통화가 정한다</h3>
 *
 * <p>KRW·JPY 는 소수가 없고 USD 는 둘이다. {@link java.util.Currency} 가 안다. 여기서
 * 틀리면 Stripe 에 100 배 금액을 보내거나(JPY 에 ×100) PayPal 이 "DECIMALS_NOT_SUPPORTED"
 * 로 거절한다(JPY 에 "1500.00").
 *
 * <p>[JA] 決済手段ごとに異なる通貨。
 *
 * <h3>2 つの通貨がある</h3>
 *
 * <ul>
 *   <li><b>店舗通貨</b>({@code payment.base-currency}) — カタログ価格・{@code Order.total}・
 *       売上集計・pay-service の通貨。<b>ひとつだけ</b>で、変わることはない。</li>
 *   <li><b>請求通貨</b>({@code payment.providers.<PROVIDER>}) — そのプロバイダに実際に
 *       決済を要求する通貨。PortOne は KRW、日本向けテストなら Stripe を JPY で。</li>
 * </ul>
 *
 * <p>両者が異なる場合、注文作成時に為替レートで<b>請求金額を一度だけ決めて注文に保存</b>する
 * ({@code Order.currency · chargedAmount · fxRate})。決済確認と返金はその保存値と
 * 照合する — その間にレートが変わっても「その時請求した金額」が基準である。再計算すると、
 * レートが変わった後の確認・返金がすべて拒否されてしまう。
 *
 * <h3>為替レート — 固定値が基本、リアルタイムは任意</h3>
 *
 * <p>{@code payment.rates.<CUR>} = 店舗通貨 1 単位がその通貨でいくらか(固定値)。値のない
 * 通貨で請求しようとすると、<b>起動ではなく注文作成が失敗</b>する。設定漏れの結果が
 * 「レート 1 で黙って請求」であってはならない(59 USD の注文が 59 KRW になってしまう)。
 *
 * <p>{@code payment.fx.live.enabled=true} なら {@link FxRateSource}(Frankfurter) の値を
 * <b>優先して</b>使う。それでも固定値は消さない — 2 つの役割が残る:
 * <ul>
 *   <li><b>フォールバック</b> — API が落ちているか、その通貨がなければ固定値。</li>
 *   <li><b>上限</b> — リアルタイム値が固定値と {@code payment.fx.max-deviation-percent}(デフォルト 30)
 *       を超えて乖離していれば固定値を使い、警告を出す。通貨コードの取り違えやデータ事故で請求額が
 *       10 倍になるのを防ぐ最後の砦である。固定値のない通貨はこの検査なしでリアルタイム値を使う。</li>
 * </ul>
 * どちらを使ってもレートは<b>注文に固定</b>される({@code fx_rate})。リアルタイムとは「注文
 * 作成時点の値」のことであり、「決済のたびに再計算」ではない。
 *
 * <h3>小数桁数は通貨が決める</h3>
 *
 * <p>KRW・JPY は小数がなく、USD は 2 桁である。{@link java.util.Currency} がそれを知っている。
 * ここを間違えると Stripe に 100 倍の金額を送ったり(JPY に ×100)、PayPal が
 * "DECIMALS_NOT_SUPPORTED" で拒否したりする(JPY に "1500.00")。
 */
@Slf4j
@Component
@ConfigurationProperties(prefix = "payment")
@Getter
@Setter
public class PaymentCurrencyPolicy {

    /** 상점 통화 — 카탈로그·주문 총액·매출의 통화. */
    // [JA] 店舗通貨 — カタログ・注文総額・売上の通貨。
    private String baseCurrency = "USD";

    /** 제공자 이름(PAYPAL · STRIPE · PORTONE) → 청구 통화. 없으면 상점 통화. */
    // [JA] プロバイダ名(PAYPAL · STRIPE · PORTONE) → 請求通貨。なければ店舗通貨。
    private Map<String, String> providers = new HashMap<>();

    /** 통화 → 상점 통화 1 단위의 환율(고정값). 상점 통화 자신은 필요 없다. */
    // [JA] 通貨 → 店舗通貨 1 単位あたりのレート(固定値)。店舗通貨自身には不要。
    private Map<String, BigDecimal> rates = new HashMap<>();

    /** {@code payment.fx.*} — 실시간 환율의 정책 쪽 설정. 배선(url·ttl)은 {@code FxConfig}. */
    // [JA] {@code payment.fx.*} — リアルタイム為替のポリシー側の設定。配線(url・ttl)は {@code FxConfig}。
    private Fx fx = new Fx();

    @Getter
    @Setter
    public static class Fx {
        /** 실시간 환율이 고정 환율과 이만큼(%) 넘게 다르면 고정 환율을 쓴다. 0 이하면 검사 안 함. */
        // [JA] リアルタイムレートが固定レートとこの割合(%)を超えて乖離したら固定レートを使う。0 以下なら検査しない。
        private BigDecimal maxDeviationPercent = new BigDecimal("30");
    }

    /** 실시간 환율. 빈이 없으면(기본) {@code null} — 고정 환율만 쓴다. */
    // [JA] リアルタイム為替レート。Bean がなければ(デフォルト) {@code null} — 固定レートのみを使う。
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private FxRateSource liveRates;

    @Autowired(required = false)
    public void setLiveRates(FxRateSource liveRates) {
        this.liveRates = liveRates;
    }

    /** 한 주문에 대해 정해진 청구. {@code rate} 는 상점 통화 1 단위 = {@code currency} 얼마. */
    // [JA] ひとつの注文について決まった請求。{@code rate} は店舗通貨 1 単位 = {@code currency} でいくらか。
    public record Charge(String currency, BigDecimal amount, BigDecimal rate) {
    }

    public String baseCurrency() {
        return normalize(baseCurrency);
    }

    /** 이 제공자의 청구 통화. 설정이 없으면 상점 통화. */
    // [JA] このプロバイダの請求通貨。設定がなければ店舗通貨。
    public String currencyFor(PaymentProvider provider) {
        if (provider == null) {
            return baseCurrency();
        }
        String configured = providers.get(provider.name());
        if (configured == null) {
            configured = providers.get(provider.name().toLowerCase(Locale.ROOT));
        }
        return configured == null || configured.isBlank() ? baseCurrency() : normalize(configured);
    }

    /**
     * 상점 통화의 총액을 이 제공자의 청구 통화로.
     *
     * <p>[JA] 店舗通貨の総額を、このプロバイダの請求通貨に換算する。
     *
     * @throws IllegalStateException 청구 통화의 환율이 실시간·고정 어디에도 없을 때 —
     *                               조용히 1 로 두지 않는다
     *                               [JA] 請求通貨のレートがリアルタイムにも固定値にもない時 — 黙って 1 にはしない
     */
    public Charge quote(BigDecimal baseTotal, PaymentProvider provider) {
        String currency = currencyFor(provider);
        String base = baseCurrency();
        if (currency.equals(base)) {
            return new Charge(currency, round(baseTotal, currency), BigDecimal.ONE);
        }
        BigDecimal rate = rateFor(currency);
        return new Charge(currency, round(baseTotal.multiply(rate), currency), rate);
    }

    public Charge quote(int baseTotal, PaymentProvider provider) {
        return quote(BigDecimal.valueOf(baseTotal), provider);
    }

    /**
     * 실시간 → (편차 검사) → 고정 → 예외. 위 클래스 주석 "환율" 절이 규칙이다.
     *
     * <p>[JA] リアルタイム → (乖離検査) → 固定 → 例外。上のクラスコメントの「為替レート」節がルールである。
     */
    BigDecimal rateFor(String currency) {
        BigDecimal fixed = fixedRateFor(currency);
        BigDecimal live = liveRateFor(currency);

        if (live != null) {
            if (fixed == null) {
                log.debug("환율 {}: 실시간 {} (고정값 없음 — 편차 검사 생략)", currency, live);
                return live;
            }
            if (withinDeviation(live, fixed)) {
                return live;
            }
            log.warn("환율 {}: 실시간 {} 이 고정값 {} 과 {}% 넘게 다르다 — 고정값을 쓴다. "
                    + "API 응답이나 FX_RATE_{} 를 확인할 것", currency, live, fixed,
                    fx.getMaxDeviationPercent(), currency);
            return fixed;
        }

        if (fixed == null) {
            throw new IllegalStateException(
                    "통화 " + currency + " 의 환율이 없습니다. payment.rates." + currency
                            + " (FX_RATE_" + currency + ") 을 설정하세요 — 상점 통화 " + baseCurrency()
                            + " 1 단위가 " + currency + " 로 얼마인지."
                            + (liveRates == null ? "" : " (실시간 환율도 이 통화를 주지 않았습니다)"));
        }
        return fixed;
    }

    /** 고정 환율. 없거나 0 이하면 {@code null}. */
    // [JA] 固定レート。無いか 0 以下なら {@code null}。
    private BigDecimal fixedRateFor(String currency) {
        BigDecimal rate = rates.get(currency);
        if (rate == null) {
            rate = rates.get(currency.toLowerCase(Locale.ROOT));
        }
        return rate == null || rate.signum() <= 0 ? null : rate;
    }

    /** 실시간 환율. 꺼져 있거나, 실패했거나, 그 통화가 없으면 {@code null}. 예외는 여기서 멈춘다. */
    // [JA] リアルタイムレート。無効か、失敗したか、その通貨がなければ {@code null}。例外はここで止める。
    private BigDecimal liveRateFor(String currency) {
        if (liveRates == null) {
            return null;
        }
        try {
            return liveRates.rate(baseCurrency(), currency)
                    .filter(r -> r.signum() > 0)
                    .orElse(null);
        } catch (RuntimeException e) {
            // 포트 계약은 "실패 = empty" 지만, 구현이 어기더라도 주문 생성이 죽어서는 안 된다.
            // [JA] ポートの契約は「失敗 = empty」だが、実装がそれを破っても注文作成が落ちてはならない。
            log.warn("실시간 환율 조회 중 예외 — 고정값으로 간다: {}", e.toString());
            return null;
        }
    }

    private boolean withinDeviation(BigDecimal live, BigDecimal fixed) {
        BigDecimal max = fx.getMaxDeviationPercent();
        if (max == null || max.signum() <= 0) {
            return true;
        }
        BigDecimal deviationPercent = live.subtract(fixed).abs()
                .multiply(BigDecimal.valueOf(100))
                .divide(fixed, 6, RoundingMode.HALF_UP);
        return deviationPercent.compareTo(max) <= 0;
    }

    // ------------------------------------------------------------------ 통화별 소수 자릿수
    // [JA] ------------------------------------------------------------ 通貨別の小数桁数

    /** 통화의 소수 자릿수. KRW·JPY = 0, USD·EUR = 2. 모르는 코드는 2. */
    // [JA] 通貨の小数桁数。KRW・JPY = 0、USD・EUR = 2。不明なコードは 2。
    public static int fractionDigits(String currency) {
        try {
            int digits = Currency.getInstance(normalize(currency)).getDefaultFractionDigits();
            return digits < 0 ? 2 : digits;
        } catch (IllegalArgumentException e) {
            return 2;
        }
    }

    /** 그 통화의 자릿수로 반올림. */
    // [JA] その通貨の桁数で四捨五入する。
    public static BigDecimal round(BigDecimal amount, String currency) {
        return amount.setScale(fractionDigits(currency), RoundingMode.HALF_UP);
    }

    /** Stripe 식 최소 단위 정수 — USD 59.98 → 5998, JPY 1500 → 1500. */
    // [JA] Stripe 式の最小単位の整数 — USD 59.98 → 5998、JPY 1500 → 1500。
    public static long toMinorUnits(BigDecimal amount, String currency) {
        return amount.movePointRight(fractionDigits(currency))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    public static BigDecimal fromMinorUnits(long minor, String currency) {
        return BigDecimal.valueOf(minor).movePointLeft(fractionDigits(currency));
    }

    /** PayPal 식 문자열 — USD "59.98", JPY "1500". 지수 표기가 나오지 않게 plain. */
    // [JA] PayPal 式の文字列 — USD "59.98"、JPY "1500"。指数表記にならないよう plain で出す。
    public static String plain(BigDecimal amount, String currency) {
        return round(amount, currency).toPlainString();
    }

    private static String normalize(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }
}
