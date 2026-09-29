package com.project.coupon_service.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.common.constants.coupon.CouponScope;
import com.project.common.constants.coupon.CouponStatus;

/**
 * 🐛 회귀 대상: <b>쿠폰 유효기간이 검사된 적이 없다.</b>
 *
 * <p>{@code startDate}/{@code endDate} 는 저장만 되고 적용 시점에 아무도 보지 않았다.
 * 쿠폰을 찾는 쿼리는 {@code findByNameAndUserIdAndCouponStatus} — 이름·사용자·상태뿐이다.
 * 그래서 가입 쿠폰의 31일 유효기간은 장식이었고, 1년 전에 만료된 쿠폰도 그대로 할인됐다.
 */
class CouponValidityTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 15, 12, 0);

    private static Coupon coupon(LocalDateTime start, LocalDateTime end) {
        return Coupon.builder()
                .couponId(1L)
                .userId(1L)
                .name("테스트 쿠폰")
                .startDate(start)
                .endDate(end)
                .discount(10)
                .couponStatus(CouponStatus.NOT_USED)
                .build();
    }

    @Test
    @DisplayName("기간 안이면 쓸 수 있다")
    void withinPeriod() {
        assertThat(coupon(NOW.minusDays(1), NOW.plusDays(1)).isUsableAt(NOW)).isTrue();
    }

    @Test
    @DisplayName("만료된 쿠폰은 쓸 수 없다 — 이게 예전에 통과하던 경로다")
    void expired() {
        assertThat(coupon(NOW.minusDays(60), NOW.minusDays(29)).isUsableAt(NOW)).isFalse();
    }

    @Test
    @DisplayName("아직 시작하지 않은 쿠폰도 쓸 수 없다")
    void notStartedYet() {
        assertThat(coupon(NOW.plusDays(1), NOW.plusDays(30)).isUsableAt(NOW)).isFalse();
    }

    @Test
    @DisplayName("경계는 양쪽 다 포함이다")
    void boundariesInclusive() {
        assertThat(coupon(NOW, NOW.plusDays(1)).isUsableAt(NOW)).isTrue();
        assertThat(coupon(NOW.minusDays(1), NOW).isUsableAt(NOW)).isTrue();
    }

    @Test
    @DisplayName("날짜가 비어 있으면 그 방향으로는 제한이 없다")
    void openEnded() {
        assertThat(coupon(null, NOW.plusDays(1)).isUsableAt(NOW)).isTrue();
        assertThat(coupon(NOW.minusDays(1), null).isUsableAt(NOW)).isTrue();
        assertThat(coupon(null, null).isUsableAt(NOW)).isTrue();
    }

    @Test
    @DisplayName("scope 가 비어 있는 기존 쿠폰은 ALL 로 읽는다")
    void nullScopeReadsAsAll() {
        // V5 이전에 만들어진 행은 scope 가 NULL 이다. 이게 CATEGORY 로 읽히면
        // 대상이 없어 기존 쿠폰 전부가 갑자기 아무것에도 적용되지 않는다.
        Coupon legacy = coupon(NOW.minusDays(1), NOW.plusDays(1));
        assertThat(legacy.getScope()).isNull();
        assertThat(legacy.scope()).isEqualTo(CouponScope.ALL);
    }
}
