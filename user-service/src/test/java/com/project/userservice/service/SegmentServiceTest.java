package com.project.userservice.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.project.common.constants.event.AudienceSegment;
import com.project.common.constants.event.CustomerTier;
import com.project.userservice.model.User;

/**
 * 고객군 판정 — 기획전 쿠폰이 "신규 가입자만", "골드 이상만" 같은 조건을 걸 때 쓴다.
 *
 * <p><b>왜 이 판정이 user-service 에 있는가.</b> "신규가 며칠인가", "얼마부터 골드인가" 는
 * 우리 정책이고 가입일·등급은 이 서비스가 갖고 있다. 다른 서비스에 원재료를 넘겨 각자
 * 계산하게 하면 같은 규칙이 여러 곳에 생기고, 기준을 바꿀 때 한 곳을 빠뜨린다.
 *
 * <p>세그먼트는 서로 독립인 <b>세 축</b>에서 나온다 — 가입 시점 / 구매 이력 / 등급.
 * 한 사람이 여러 개를 갖는 것이 정상이다.
 */
@DisplayName("고객군 판정")
class SegmentServiceTest {

    private SegmentService service;

    @BeforeEach
    void setUp() {
        service = new SegmentService();
        ReflectionTestUtils.setField(service, "newUserDays", 30);
    }

    private static User joinedAt(LocalDateTime when) {
        User user = new User("tester", "tester@example.com", "pw");
        user.setCreatedAt(when);
        return user;
    }

    private static User with(CustomerTier tier, long lifetimeOrders) {
        User user = joinedAt(LocalDateTime.now().minusDays(100));
        user.setTier(tier);
        user.setLifetimeOrderCount(lifetimeOrders);
        return user;
    }

    // ── 축 1: 가입 시점 ────────────────────────────────────────────────

    @Test
    @DisplayName("기준일 안에 가입했으면 신규다")
    void recentJoinIsNew() {
        assertThat(service.segmentsOf(joinedAt(LocalDateTime.now().minusDays(3))))
                .contains(AudienceSegment.NEW_USER)
                .doesNotContain(AudienceSegment.EXISTING_USER);
    }

    @Test
    @DisplayName("기준일을 넘겼으면 기존 가입자다")
    void oldJoinIsExisting() {
        assertThat(service.segmentsOf(joinedAt(LocalDateTime.now().minusDays(31))))
                .contains(AudienceSegment.EXISTING_USER)
                .doesNotContain(AudienceSegment.NEW_USER);
    }

    /**
     * 🔴 createdAt 은 감사 필드라 비어 있을 수 있다(옛 데이터·직접 삽입). 그때 신규로
     * 치면 <b>모든 옛 계정이 신규 전용 혜택을 받는다</b> — 혜택을 잘못 주는 쪽이 못 주는
     * 쪽보다 되돌리기 어렵다.
     */
    @Test
    @DisplayName("가입일이 없으면 신규가 아니다 — 혜택을 잘못 주는 쪽이 더 나쁘다")
    void missingJoinDateIsNotNew() {
        assertThat(service.segmentsOf(joinedAt(null)))
                .contains(AudienceSegment.EXISTING_USER)
                .doesNotContain(AudienceSegment.NEW_USER);
    }

    @Test
    @DisplayName("사용자가 없으면 빈 목록 — 어떤 조건에도 걸리지 않는다")
    void unknownUserHasNoSegments() {
        assertThat(service.segmentsOf(null)).isEmpty();
    }

    /**
     * 둘을 합치면 정확히 로그인 사용자 전체다. 어느 쪽도 아닌 사용자가 생기면 그 사람은
     * 신규 혜택도 기존 혜택도 못 받는다.
     */
    @Test
    @DisplayName("신규와 기존은 언제나 정확히 하나씩 — 사이에 빈 곳이 없다")
    void exactlyOneOfNewOrExisting() {
        for (int days : new int[] { 0, 1, 29, 30, 31, 365 }) {
            assertThat(service.segmentsOf(joinedAt(LocalDateTime.now().minusDays(days))))
                    .as("가입 %d일 전", days)
                    .filteredOn(s -> s == AudienceSegment.NEW_USER || s == AudienceSegment.EXISTING_USER)
                    .hasSize(1);
        }
    }

    /**
     * ALL 은 모두가 항상 속하므로 목록에 넣어 봤자 정보가 없고, 받는 쪽은 그것을
     * "조건 없음" 으로 따로 처리한다({@link AudienceSegment#matches}).
     */
    @Test
    @DisplayName("ALL 은 목록에 담지 않는다")
    void allIsNotListed() {
        assertThat(service.segmentsOf(joinedAt(LocalDateTime.now())))
                .doesNotContain(AudienceSegment.ALL);
    }

    @Test
    @DisplayName("기준일을 바꾸면 판정이 따라 바뀐다 — 정책은 설정이다")
    void theWindowIsConfigurable() {
        ReflectionTestUtils.setField(service, "newUserDays", 7);

        assertThat(service.segmentsOf(joinedAt(LocalDateTime.now().minusDays(10))))
                .contains(AudienceSegment.EXISTING_USER);

        ReflectionTestUtils.setField(service, "newUserDays", 14);

        assertThat(service.segmentsOf(joinedAt(LocalDateTime.now().minusDays(10))))
                .contains(AudienceSegment.NEW_USER);
    }

    /** 0 으로 두면 신규 혜택을 끈 것이다 — 음수가 들어와도 같은 뜻이어야 한다. */
    @Test
    @DisplayName("기준일이 0 이하면 아무도 신규가 아니다")
    void zeroWindowDisablesNewUser() {
        ReflectionTestUtils.setField(service, "newUserDays", 0);

        assertThat(service.segmentsOf(joinedAt(LocalDateTime.now().minusMinutes(1))))
                .contains(AudienceSegment.EXISTING_USER);
    }

    // ── 축 2: 구매 이력 ────────────────────────────────────────────────

    @Test
    @DisplayName("주문이 한 건이라도 있으면 PURCHASED")
    void oneOrderMakesPurchased() {
        assertThat(service.segmentsOf(with(CustomerTier.BASIC, 1)))
                .contains(AudienceSegment.PURCHASED)
                .doesNotContain(AudienceSegment.NEVER_PURCHASED);
    }

    @Test
    @DisplayName("주문이 없으면 NEVER_PURCHASED")
    void noOrderMakesNeverPurchased() {
        assertThat(service.segmentsOf(with(CustomerTier.BASIC, 0)))
                .contains(AudienceSegment.NEVER_PURCHASED)
                .doesNotContain(AudienceSegment.PURCHASED);
    }

    /**
     * 🔴 구매 이력은 <b>평생</b> 기준이다. 등급은 최근 3개월을 보므로 오래 쉰 단골은
     * 일반 등급으로 떨어지는데, 그 사람이 <b>첫 구매 쿠폰</b>까지 받으면 안 된다.
     * 등급이 BASIC 이라는 사실이 구매 이력에 영향을 주지 않아야 한다.
     */
    @Test
    @DisplayName("등급이 일반으로 떨어져도 구매 이력은 남는다 — 첫 구매 쿠폰의 대상이 아니다")
    void demotedRegularIsStillAPurchaser() {
        User lapsed = with(CustomerTier.BASIC, 42);

        assertThat(service.segmentsOf(lapsed))
                .contains(AudienceSegment.PURCHASED)
                .doesNotContain(AudienceSegment.NEVER_PURCHASED);
    }

    /** 신규 가입자가 곧 미구매자는 아니다. 어제 가입해서 바로 산 사람이 있다. */
    @Test
    @DisplayName("가입 시점과 구매 이력은 서로 독립이다")
    void joinDateAndPurchaseAreIndependent() {
        User boughtImmediately = joinedAt(LocalDateTime.now().minusDays(1));
        boughtImmediately.setLifetimeOrderCount(1);

        assertThat(service.segmentsOf(boughtImmediately))
                .contains(AudienceSegment.NEW_USER, AudienceSegment.PURCHASED);
    }

    // ── 축 3: 등급 ────────────────────────────────────────────────────

    @Test
    @DisplayName("일반 등급은 등급 세그먼트를 갖지 않는다 — '일반 이상' 은 전원이라 조건이 아니다")
    void basicHasNoTierSegment() {
        assertThat(service.segmentsOf(with(CustomerTier.BASIC, 3)))
                .doesNotContain(AudienceSegment.TIER_SILVER,
                        AudienceSegment.TIER_GOLD, AudienceSegment.TIER_VIP);
    }

    /**
     * 🔴 등급 세그먼트는 전부 <b>"이상"</b> 이다. 골드 회원이 TIER_GOLD 만 갖는다면
     * "실버 이상 쿠폰" 을 골드 회원이 못 받는다 — 상위 등급이 하위 혜택에서 배제되는,
     * 사용자에게 절대 설명할 수 없는 동작이다.
     */
    @Test
    @DisplayName("골드는 실버 조건에도 해당한다 — 등급은 누적이다")
    void goldAlsoMatchesSilver() {
        assertThat(service.segmentsOf(with(CustomerTier.GOLD, 5)))
                .contains(AudienceSegment.TIER_SILVER, AudienceSegment.TIER_GOLD)
                .doesNotContain(AudienceSegment.TIER_VIP);
    }

    @Test
    @DisplayName("VIP 는 아래 등급 조건을 모두 만족한다")
    void vipMatchesEverything() {
        assertThat(service.segmentsOf(with(CustomerTier.VIP, 9)))
                .contains(AudienceSegment.TIER_SILVER, AudienceSegment.TIER_GOLD,
                        AudienceSegment.TIER_VIP);
    }

    /**
     * 옛 행이나 직접 삽입된 행은 tier 가 비어 있을 수 있다. 그때 최고 등급으로 읽으면
     * 아무나 VIP 혜택을 받는다.
     */
    @Test
    @DisplayName("등급이 비어 있으면 일반으로 읽는다")
    void nullTierReadsAsBasic() {
        User noTier = with(CustomerTier.BASIC, 1);
        noTier.setTier(null);

        assertThat(service.segmentsOf(noTier))
                .doesNotContain(AudienceSegment.TIER_SILVER,
                        AudienceSegment.TIER_GOLD, AudienceSegment.TIER_VIP);
    }

    /** 세 축이 한 목록에 함께 담긴다. 받는 쪽은 그중 하나만 보면 된다. */
    @Test
    @DisplayName("세 축이 함께 담긴다")
    void allThreeAxesTogether() {
        assertThat(service.segmentsOf(with(CustomerTier.GOLD, 7)))
                .containsExactlyInAnyOrder(
                        AudienceSegment.EXISTING_USER,
                        AudienceSegment.PURCHASED,
                        AudienceSegment.TIER_SILVER,
                        AudienceSegment.TIER_GOLD);
    }
}
