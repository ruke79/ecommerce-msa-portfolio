package com.project.userservice.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.common.constants.event.CustomerTier;

/**
 * 등급 기준.
 *
 * <p>기준 금액은 코드가 아니라 설정이다 — 마케팅이 바꾸고 싶을 때 배포가 필요하면
 * 실제로는 아무도 바꾸지 않고 처음 정한 값에 굳는다. 대신 <b>설정 실수로 혜택이 새지
 * 않도록</b> 몇 가지를 여기서 못 박는다.
 *
 * <p>[JA] 等級基準。
 * <p>基準金額はコードではなく設定である — マーケティングが変えたいときにデプロイが必要なら、実際には誰も変えず最初に決めた値で固まる。
 * 代わりに<b>設定ミスで特典が漏れない</b>よういくつかをここで固定する。
 */
@DisplayName("등급 기준")
class TierPropertiesTest {

    private final TierProperties properties = new TierProperties();

    @Test
    @DisplayName("기본값은 3개월 / 20만 · 50만 · 150만")
    void sensibleDefaults() {
        assertThat(properties.getMonths()).isEqualTo(3);
        assertThat(properties.tierFor(199_999L)).isEqualTo(CustomerTier.BASIC);
        assertThat(properties.tierFor(200_000L)).isEqualTo(CustomerTier.SILVER);
        assertThat(properties.tierFor(500_000L)).isEqualTo(CustomerTier.GOLD);
        assertThat(properties.tierFor(1_500_000L)).isEqualTo(CustomerTier.VIP);
    }

    /**
     * "50만 원 이상 골드" 라고 안내해 놓고 정확히 50만 원인 사람을 실버로 두면
     * 그건 문의로 접수된다.
     *
     * <p>[JA] 「50 万ウォン以上ゴールド」と案内しておいてちょうど 50 万ウォンの人をシルバーにすると、それは問い合わせとして寄せられる。
     */
    @Test
    @DisplayName("기준 금액에 정확히 도달하면 그 등급이다")
    void theThresholdIsInclusive() {
        properties.setGoldAmount(500_000L);

        assertThat(properties.tierFor(500_000L)).isEqualTo(CustomerTier.GOLD);
        assertThat(properties.tierFor(499_999L)).isNotEqualTo(CustomerTier.GOLD);
    }

    /**
     * 🔴 기준 금액을 0 으로 잘못 넣으면 <b>전 회원이 VIP</b> 가 된다. 이미 발급된 쿠폰은
     * 회수할 수 없어서 되돌릴 방법이 사실상 없는 사고다.
     *
     * <p>[JA] 🔴 基準金額を誤って 0 にすると<b>全会員が VIP</b> になる。発行済みクーポンは回収できないので事実上戻す方法がない事故である。
     */
    @Test
    @DisplayName("구매가 없으면 어떤 설정으로도 등급이 오르지 않는다")
    void zeroSpendNeverGetsPromoted() {
        properties.setSilverAmount(0L);
        properties.setGoldAmount(0L);
        properties.setVipAmount(0L);

        assertThat(properties.tierFor(0L)).isEqualTo(CustomerTier.BASIC);
        assertThat(properties.tierFor(-1L)).isEqualTo(CustomerTier.BASIC);
    }

    /** 기준이 뒤집혀 들어와도 두 등급이 동시에 적용되지는 않는다 — 하나만 잡힌다. */
    // [JA] 基準が逆転して入っても二つの等級が同時に適用されることはない — 一つだけ取られる。
    @Test
    @DisplayName("기준이 뒤집혀 있어도 등급은 하나만 잡힌다")
    void invertedThresholdsStillYieldOneTier() {
        properties.setSilverAmount(1_000_000L);
        properties.setGoldAmount(100_000L);
        properties.setVipAmount(500_000L);

        assertThat(properties.tierFor(200_000L)).isEqualTo(CustomerTier.GOLD);
    }

    @Test
    @DisplayName("기준을 올리면 등급이 따라 내려간다")
    void raisingTheBarDemotes() {
        properties.setGoldAmount(900_000L);

        assertThat(properties.tierFor(600_000L)).isEqualTo(CustomerTier.SILVER);
    }
}
