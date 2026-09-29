package com.project.common.constants.event;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 고객 등급.
 *
 * <p>여기서 지키는 것은 하나다 — <b>등급 세그먼트는 "이상" 이다.</b> 골드 회원이
 * {@code TIER_GOLD} 만 갖는다면 "실버 이상 쿠폰" 을 골드 회원이 못 받는다. 상위 등급이
 * 하위 혜택에서 빠지는 동작은 사용자에게 설명할 방법이 없다.
 */
@DisplayName("고객 등급")
class CustomerTierTest {

    @Test
    @DisplayName("일반은 등급 세그먼트가 없다 — '일반 이상' 은 전원이라 조건이 아니다")
    void basicHasNone() {
        assertThat(CustomerTier.BASIC.segments()).isEmpty();
    }

    @Test
    @DisplayName("실버는 실버 하나")
    void silver() {
        assertThat(CustomerTier.SILVER.segments())
                .containsExactly(AudienceSegment.TIER_SILVER);
    }

    @Test
    @DisplayName("골드는 실버와 골드를 함께 갖는다")
    void goldIsCumulative() {
        assertThat(CustomerTier.GOLD.segments())
                .containsExactly(AudienceSegment.TIER_SILVER, AudienceSegment.TIER_GOLD);
    }

    @Test
    @DisplayName("VIP 는 아래 등급을 모두 갖는다")
    void vipIsCumulative() {
        assertThat(CustomerTier.VIP.segments())
                .containsExactly(AudienceSegment.TIER_SILVER, AudienceSegment.TIER_GOLD,
                        AudienceSegment.TIER_VIP);
    }

    /**
     * 🔴 누적의 목적은 <b>받는 쪽이 등급 순서를 몰라도 되게</b> 하는 것이다. 쿠폰
     * 서비스는 단순 포함 검사만 한다 — 등급의 높낮이를 아는 곳이 이 enum 하나로 유지된다.
     */
    @Test
    @DisplayName("'실버 이상' 은 받는 쪽에서 단순 포함 검사로 끝난다")
    void matchingNeedsNoOrdering() {
        var goldUser = CustomerTier.GOLD.segments().stream().map(Enum::name).toList();

        assertThat(AudienceSegment.TIER_SILVER.matches(goldUser)).isTrue();
        assertThat(AudienceSegment.TIER_GOLD.matches(goldUser)).isTrue();
        assertThat(AudienceSegment.TIER_VIP.matches(goldUser)).isFalse();
    }

    @Test
    @DisplayName("ALL 은 등급과 무관하게 언제나 참이다")
    void allAlwaysMatches() {
        assertThat(AudienceSegment.ALL.matches(java.util.List.of())).isTrue();
    }

    @Test
    @DisplayName("선언 순서가 곧 높낮이다")
    void orderIsMeaning() {
        assertThat(CustomerTier.VIP.atLeast(CustomerTier.GOLD)).isTrue();
        assertThat(CustomerTier.GOLD.atLeast(CustomerTier.VIP)).isFalse();
        assertThat(CustomerTier.SILVER.atLeast(CustomerTier.SILVER)).isTrue();
        assertThat(CustomerTier.BASIC.atLeast(CustomerTier.SILVER)).isFalse();
    }

    /**
     * 🔴 {@link AudienceSegment#from} 은 모르는 값을 {@code ALL}(=모두에게 준다)로 읽지만
     * 등급은 반대다. 등급이 곧 혜택의 크기라, 모르는 값을 높게 읽으면 아무나 VIP 혜택을
     * 받는다.
     */
    @Test
    @DisplayName("모르는 등급 문자열은 일반으로 읽는다 — 높게 읽으면 혜택이 샌다")
    void unknownReadsAsBasic() {
        assertThat(CustomerTier.from("PLATINUM")).isEqualTo(CustomerTier.BASIC);
        assertThat(CustomerTier.from(null)).isEqualTo(CustomerTier.BASIC);
        assertThat(CustomerTier.from("   ")).isEqualTo(CustomerTier.BASIC);
    }

    @Test
    @DisplayName("대소문자와 공백은 무시한다")
    void readingIsForgiving() {
        assertThat(CustomerTier.from(" gold ")).isEqualTo(CustomerTier.GOLD);
    }

    @Test
    @DisplayName("모든 등급에 한국어 이름이 있다")
    void everyTierHasAName() {
        for (CustomerTier tier : CustomerTier.values()) {
            assertThat(tier.displayName()).as("%s", tier).isNotBlank();
        }
    }

    /**
     * 등급이 새로 생겼는데 {@link CustomerTier#segment()} 의 switch 를 빠뜨리면 그 등급
     * 회원은 <b>아무 등급 세그먼트도 받지 못한다</b> — 컴파일은 통과하고, 쿠폰을 못 받는
     * 사람이 생길 때까지 아무도 모른다.
     */
    @Test
    @DisplayName("일반을 뺀 모든 등급은 자기 세그먼트를 갖는다")
    void everyTierAboveBasicMapsToASegment() {
        for (CustomerTier tier : CustomerTier.values()) {
            if (tier == CustomerTier.BASIC) {
                continue;
            }
            assertThat(tier.segments()).as("%s", tier).isNotEmpty();
            assertThat(tier.segments()).as("%s 자신", tier).contains(tier.segment());
        }
    }
}
