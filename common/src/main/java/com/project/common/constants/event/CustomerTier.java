package com.project.common.constants.event;

import java.util.ArrayList;
import java.util.List;

/**
 * 고객 등급.
 *
 * <p><b>무엇을 기준으로 나누는가.</b> 최근 일정 기간의 <b>실결제액</b>이다. 누적 평생
 * 결제액이 아니다 — 5년 전에 크게 한 번 사고 떠난 사람과 지난달에도 사는 사람을 같은
 * 등급으로 두면 등급이 "현재 고객"을 가리키지 못한다. 기간과 금액 기준은 정책이라
 * <b>이 enum 이 아니라 user-service 설정</b>이 정한다({@code user.tier.*}).
 *
 * <h3>아마존을 참고했다는 것의 의미</h3>
 *
 * <p>아마존식 등급의 특징은 <b>등급을 자랑거리로 만들지 않는 것</b>이다. 점수·배지·
 * 다음 등급까지 남은 금액 같은 장치로 소비를 압박하지 않고, 등급은 오직 <b>혜택이
 * 도착하는 형태</b>로만 드러난다. 그래서 여기서도 등급 자체를 화면에 전시하지 않고,
 * 기획전 쿠폰의 대상 조건으로만 쓴다({@link AudienceSegment}).
 *
 * <p>또 하나 — 아마존은 등급을 <b>내리는 것도 조용히</b> 한다. 우리도 강등을 알리지
 * 않는다. "등급이 떨어졌습니다" 알림은 사용자가 할 수 있는 일이 소비밖에 없는 통보다.
 *
 * <h3>🔴 순서가 의미를 갖는다</h3>
 *
 * <p>선언 순서가 곧 등급의 높낮이다({@link #ordinal()}). "골드 이상" 같은 조건은
 * {@link #atLeast(CustomerTier)} 로 판정한다. 중간에 등급을 끼워 넣으면 순서가 바뀌므로
 * <b>새 등급은 반드시 제자리에</b> 넣어야 한다.
 */
public enum CustomerTier {

    /** 일반. 기준 금액에 못 미치거나 최근 구매가 없는 모든 사용자의 기본값. */
    BASIC("일반"),

    /** 실버. */
    SILVER("실버"),

    /** 골드. */
    GOLD("골드"),

    /** VIP. 최상위. */
    VIP("VIP");

    private final String displayName;

    CustomerTier(String displayName) {
        this.displayName = displayName;
    }

    /** 화면에 보일 한국어 이름. */
    public String displayName() {
        return displayName;
    }

    /** 문자열을 안전하게 읽는다. 모르는 값이나 null 은 {@link #BASIC}. */
    public static CustomerTier from(String raw) {
        if (raw == null || raw.isBlank()) {
            return BASIC;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            // 모르는 등급을 만나면 가장 낮은 등급으로 읽는다. AudienceSegment.from 이
            // 모르는 값을 ALL(=모두에게 준다)로 읽는 것과 방향이 반대인데, 이유가 있다.
            // 저쪽은 "혜택을 못 받는 침묵"이 더 나쁜 자리고, 이쪽은 등급이 곧 혜택의
            // 크기라 모르는 값을 높게 읽으면 아무나 VIP 혜택을 받는다.
            return BASIC;
        }
    }

    /** 이 등급이 {@code floor} 이상인가. */
    public boolean atLeast(CustomerTier floor) {
        return floor != null && ordinal() >= floor.ordinal();
    }

    /**
     * 이 등급이 가지는 세그먼트 목록.
     *
     * <p><b>누적으로 준다.</b> 골드 사용자는 {@code [TIER_SILVER, TIER_GOLD]} 을 받는다.
     * 그래야 받는 쪽({@link AudienceSegment#matches})이 <b>단순 포함 검사</b>만으로
     * "실버 이상" 을 판정할 수 있다 — 대소 비교나 등급 순서를 쿠폰 서비스가 알 필요가
     * 없어진다. 등급 순서를 아는 곳이 늘어날수록 한 곳이 뒤처졌을 때 티가 안 난다.
     *
     * <p>{@link #BASIC} 은 <b>빈 목록</b>이다. "일반 이상" 은 전원이라 조건으로서 의미가
     * 없고, 그건 {@link AudienceSegment#ALL} 이 이미 뜻하는 바다.
     */
    public List<AudienceSegment> segments() {
        List<AudienceSegment> out = new ArrayList<>();
        for (CustomerTier tier : values()) {
            if (tier == BASIC || !atLeast(tier)) {
                continue;
            }
            AudienceSegment segment = tier.segment();
            if (segment != null) {
                out.add(segment);
            }
        }
        return List.copyOf(out);
    }

    /** 이 등급 하나에 대응하는 세그먼트("이 등급 이상"). {@link #BASIC} 은 {@code null}. */
    AudienceSegment segment() {
        return switch (this) {
            case SILVER -> AudienceSegment.TIER_SILVER;
            case GOLD -> AudienceSegment.TIER_GOLD;
            case VIP -> AudienceSegment.TIER_VIP;
            case BASIC -> null;
        };
    }
}
