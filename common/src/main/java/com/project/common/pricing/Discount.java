package com.project.common.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 할인가 — 할인 값은 <b>퍼센트</b>다(시드 데이터가 5~70 을 넣고, 기획전 할인도 SKU 의 discount 에 퍼센트로 얹힌다).
 *
 * <p>🐛 한 식을 서비스마다 따로 갖고 있었다. catalog · 프론트(util/pricing.js)는 2026-09 에 {@code price × (100 − d) / 100} 으로 고쳤는데
 * cart-service 에는 옛 식 {@code price − price / d} 가 남아 있었다 — 상세 화면은 50% 할인가를 보여 주고, 장바구니 · 결제 금액은
 * 정가의 98% 였다. 계산을 여기 하나에 두고 둘 다 이것을 부른다(DiscountTest 가 화면 식과 같은 값을 고정한다).
 *
 * <p>통화 금액 관례대로 소수점 2자리 HALF_UP. 100 을 넘는 값은 100 으로 잘라 음수 가격이 나오지 않게 한다.
 */
// [JA] 割引価格 — 割引値はパーセント。式をサービスごとに別々に持っていて、cart-service だけ旧式(price − price / d)が残り、
//      50% 割引の商品が定価の 98% で決済されていた。計算をここ一か所に置き、catalog と cart の両方がこれを呼ぶ。
public final class Discount {

    private Discount() {
    }

    /** @return 할인가. 가격이 없거나 할인이 0 이하면 그대로 */
    public static BigDecimal apply(BigDecimal price, int percent) {
        if (price == null || percent <= 0) {
            return price;
        }
        int capped = Math.min(percent, 100);
        return price.multiply(BigDecimal.valueOf(100 - capped))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }
}
