package com.project.common.constants.event;

/**
 * 기획전의 대상 고객군.
 *
 * <p><b>이 값이 무엇을 가르는가.</b> 기획전에 딸린 <b>쿠폰을 누가 받을 수 있는가</b>이다.
 * 진열가(상품에 붙는 할인)는 가르지 않는다 — 그 이유가 이 설계의 핵심이라 아래에 적는다.
 *
 * <h3>🔴 왜 진열가는 세그먼트로 나누지 않는가</h3>
 *
 * <p><b>1. 상품 조회에는 신원이 없다.</b> 게이트웨이의 catalog-service 공개 라우트에는
 * {@code JwtAuthorization} 이 붙지 않는다(붙이면 비회원이 상품을 못 본다). 그래서 목록과
 * 상세를 그릴 때 서버는 보는 사람이 누구인지 모른다.
 *
 * <p><b>2. 캐시가 세그먼트 수만큼 갈라진다.</b> 상품 목록은 Redis 에 캐시된다
 * ({@code @Cacheable(value="products", ...)}). 사람마다 가격이 다르면 캐시 키에 세그먼트가
 * 들어가야 하고, 캐시 항목이 세그먼트 수만큼 늘어난다. 세그먼트가 늘어날 때마다 캐시
 * 적중률이 떨어진다.
 *
 * <p><b>3. 무엇보다, 화면마다 가격이 달라진다.</b> 목록에서 30% 를 보고 들어왔는데 상세나
 * 장바구니에서 정가가 되는 것 — 방금 고친 버그가 정확히 그 모양이었다. 신원을 아는 화면과
 * 모르는 화면이 섞이면 그 버그가 구조적으로 되살아난다.
 *
 * <p>그래서 세그먼트 할인은 <b>쿠폰</b>으로 준다. 쿠폰은 결제 시점에 적용되고 그때는 신원이
 * 확실하며, 진열가는 누가 보든 같다. 실제 커머스가 "신규 가입자 추가 20%" 를 쿠폰으로
 * 주는 이유이기도 하다.
 *
 * <p>세그먼트는 사용자 데이터를 가진 <b>user-service 가 판정</b>한다. "신규가 며칠인가" 는
 * 그쪽의 정책이고, 다른 서비스가 가입일을 받아 각자 계산하면 규칙이 여러 곳으로 흩어진다.
 *
 * <h3>세 가지 축</h3>
 *
 * <p>값들은 서로 독립인 세 축에서 나온다. 한 사용자는 각 축에서 하나씩 받으므로 목록이
 * 여러 개인 것이 정상이다 — 예를 들어 골드 등급의 기존 고객은
 * {@code [EXISTING_USER, PURCHASED, TIER_SILVER, TIER_GOLD]} 를 갖는다.
 *
 * <ul>
 *   <li><b>가입 시점</b> — {@link #NEW_USER} / {@link #EXISTING_USER}</li>
 *   <li><b>구매 이력</b> — {@link #PURCHASED} / {@link #NEVER_PURCHASED}</li>
 *   <li><b>등급</b> — {@link #TIER_SILVER} / {@link #TIER_GOLD} / {@link #TIER_VIP}</li>
 * </ul>
 */
public enum AudienceSegment {

    /** 전체. 로그인한 사용자라면 누구나. */
    ALL,

    /**
     * 신규 가입자.
     *
     * <p>기준일 수는 user-service 설정({@code user.segment.new-user-days})이 정한다.
     */
    NEW_USER,

    /**
     * 기존 가입자 — {@link #NEW_USER} 가 아닌 로그인 사용자.
     *
     * <p>"신규만" 의 반대편이 필요하다. 신규에게만 주는 혜택이 있으면 기존 고객에게만 주는
     * 혜택도 있어야 형평이 맞고, 둘을 합치면 정확히 {@link #ALL} 이 된다.
     */
    EXISTING_USER,

    /**
     * 구매 이력이 있는 사용자 — <b>가입 이후 한 번이라도</b> 결제한 적이 있다.
     *
     * <p>🔴 등급과 달리 <b>기간을 보지 않는다.</b> 등급은 "지금 얼마나 사는가" 를 묻고
     * 이쪽은 "산 적이 있는가" 를 묻는다. 여기에 3개월 창을 걸면 2년째 잘 사다가 최근에
     * 잠잠한 고객이 {@link #NEVER_PURCHASED} 로 분류돼 <b>첫 구매 쿠폰</b>을 받는다.
     */
    PURCHASED,

    /**
     * 한 번도 구매하지 않은 사용자.
     *
     * <p>{@link #NEW_USER} 와 다르다. 가입은 2년 전인데 아직 첫 주문이 없는 사람이 여기
     * 속하고, 어제 가입해서 바로 산 사람은 여기 속하지 않는다. 첫 구매 유도 쿠폰의
     * 진짜 대상은 가입일이 아니라 이쪽이다.
     */
    NEVER_PURCHASED,

    /**
     * 실버 <b>이상</b>.
     *
     * <p>🔴 <b>TIER_* 는 전부 "이상"이다</b> — "정확히 그 등급"이 아니다. 골드 사용자는
     * {@code TIER_SILVER} 와 {@code TIER_GOLD} 를 <b>둘 다</b> 갖는다
     * ({@link CustomerTier#segments()}). 덕분에 "실버 이상만" 은 여기서 단순 포함
     * 검사로 끝나고, 등급의 높낮이를 아는 곳이 {@link CustomerTier} 하나로 유지된다.
     *
     * <p>그래서 "일반 등급만" 같은 조건은 이 축으로 만들 수 없다 — 의도적이다. 등급이
     * 낮은 사람을 콕 집어 겨냥하는 판촉은 {@link #NEVER_PURCHASED}/{@link #NEW_USER}
     * 처럼 <b>행동</b>으로 표현하는 편이 정확하고, 사용자에게도 덜 불쾌하다.
     */
    TIER_SILVER,

    /** 골드 이상. {@link #TIER_SILVER} 의 주석 참고 — "이상"이다. */
    TIER_GOLD,

    /** VIP. 최상위라 "이상"과 "정확히"가 같다. */
    TIER_VIP;

    /** 문자열을 안전하게 읽는다. 모르는 값이나 null 은 {@link #ALL}. */
    public static AudienceSegment from(String raw) {
        if (raw == null || raw.isBlank()) {
            return ALL;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            // 모르는 값을 거절하지 않고 ALL 로 읽는다. 세그먼트가 나중에 늘어났을 때
            // 옛 서비스가 그 값을 만나도 "아무에게도 못 준다" 가 되지 않게 한다 —
            // 혜택을 못 받는 쪽이 조용히 틀리는 것보다 나쁘다.
            return ALL;
        }
    }

    /** 이 사용자가 가진 세그먼트 목록으로 대상 여부를 판정한다. */
    public boolean matches(java.util.Collection<String> userSegments) {
        if (this == ALL) {
            return true;
        }
        return userSegments != null && userSegments.stream()
                .anyMatch(s -> name().equalsIgnoreCase(s == null ? null : s.trim()));
    }
}
