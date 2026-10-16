package com.project.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 내부 전용 엔드포인트의 인가 판정.
 *
 * <p>서비스 간 Feign 호출은 게이트웨이를 거치지 않으므로 신원 헤더가 없다. 그것을
 * "내부 호출" 로 보고 통과시키고, 헤더가 있으면 외부 요청이라 관리자만 허용한다.
 *
 * <h3>🔴 여기서 막는 것</h3>
 *
 * <p>여러 곳이 {@code User-Role} <b>하나만</b> 봤다. 그런데 게이트웨이는 토큰에서 뽑은
 * 값이 있을 때만 그 헤더를 붙인다({@code if (role != null)}). role 클레임이 없는 토큰으로
 * 들어오면 헤더가 없고, 받는 쪽은 그것을 내부 호출로 읽어 <b>통과시킨다.</b>
 *
 * <p>지금 발급되는 토큰에는 role 이 항상 들어가므로 실제로 뚫린 적은 없다. 하지만 구조가
 * 그것을 보장하지 않는다 — 클레임 이름이 바뀌거나, 다른 경로로 발급된 토큰이 생기거나,
 * 파싱이 실패하면 그날 조용히 열린다. 이 엔드포인트들이 내주는 것은 <b>전 고객의 결제
 * 금액</b>과 <b>남의 쿠폰 보유 여부</b>다.
 */
@DisplayName("내부 호출 판정")
class InternalCallGuardTest {

    @Test
    @DisplayName("헤더가 하나도 없으면 내부 호출이다")
    void noHeadersMeansInternal() {
        assertThat(InternalCallGuard.fromGateway(null, null, null)).isFalse();
        assertThat(InternalCallGuard.allowed(null, null, null)).isTrue();
    }

    @Test
    @DisplayName("관리자는 외부에서도 허용한다")
    void adminIsAllowedFromOutside() {
        assertThat(InternalCallGuard.allowed("ROLE_ADMIN", "a@b.c", "7")).isTrue();
        assertThat(InternalCallGuard.allowed("ADMIN", "a@b.c", "7")).isTrue();
    }

    @Test
    @DisplayName("일반 사용자는 막는다")
    void aPlainUserIsBlocked() {
        assertThat(InternalCallGuard.allowed("ROLE_USER", "a@b.c", "7")).isFalse();
    }

    /**
     * 🔴 이것이 이 클래스가 존재하는 이유다. role 헤더만 보면 여기서 통과한다 —
     * 로그인한 아무나 전 고객의 결제 금액을 페이지 단위로 긁어갈 수 있게 된다.
     */
    @Test
    @DisplayName("role 헤더가 없어도 다른 신원 헤더가 있으면 외부 요청이다")
    void aMissingRoleHeaderDoesNotLookInternal() {
        // 게이트웨이가 role 을 못 붙인 경우 — User-Email 은 JWT subject 라 남아 있다.
        assertThat(InternalCallGuard.fromGateway(null, "attacker@example.com", "7")).isTrue();
        assertThat(InternalCallGuard.allowed(null, "attacker@example.com", "7")).isFalse();
    }

    @Test
    @DisplayName("User-Id 하나만 있어도 외부 요청이다")
    void anyIdentityHeaderCounts() {
        assertThat(InternalCallGuard.allowed(null, null, "7")).isFalse();
    }

    /** 빈 문자열 헤더는 "없음"과 같다 — 프록시가 빈 값을 붙이는 경우가 있다. */
    @Test
    @DisplayName("빈 값은 없는 것으로 본다")
    void blankIsAbsent() {
        assertThat(InternalCallGuard.fromGateway("  ", "", null)).isFalse();
        assertThat(InternalCallGuard.allowed("  ", "", null)).isTrue();
    }

    @Test
    @DisplayName("역할 문자열의 대소문자와 공백은 무시한다")
    void roleMatchingIsForgiving() {
        assertThat(InternalCallGuard.isAdmin(" role_admin ")).isTrue();
        assertThat(InternalCallGuard.isAdmin("admin")).isTrue();
        assertThat(InternalCallGuard.isAdmin("ROLE_USER")).isFalse();
        assertThat(InternalCallGuard.isAdmin(null)).isFalse();
    }

    /**
     * 🔴 관리자 판정은 <b>정확히 일치</b>해야 한다. 부분 일치로 하면
     * {@code ROLE_ADMIN_READONLY} 같은 역할이 나중에 생겼을 때 저절로 관리자가 된다.
     */
    @Test
    @DisplayName("비슷한 이름의 역할은 관리자가 아니다")
    void lookalikeRolesAreNotAdmin() {
        assertThat(InternalCallGuard.isAdmin("ROLE_ADMINISTRATOR")).isFalse();
        assertThat(InternalCallGuard.isAdmin("NOT_ADMIN")).isFalse();
        assertThat(InternalCallGuard.isAdmin("ADMINX")).isFalse();
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("🔴 기본 로캘이 터키어여도 admin 을 관리자로 본다 — toUpperCase() 는 'ADMİN'(점 있는 İ)이 된다")
    void adminIsRecognisedUnderTurkishLocale() {
        java.util.Locale before = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
            assertThat(InternalCallGuard.isAdmin("admin")).isTrue();
            assertThat(InternalCallGuard.isAdmin("role_admin")).isTrue();
        } finally {
            java.util.Locale.setDefault(before);
        }
    }
}
