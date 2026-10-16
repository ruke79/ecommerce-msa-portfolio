package com.project.gatewayserver.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;

/**
 * 게이트웨이 전역 필터 셋 — 순서가 숫자로 정해져 있다.
 *
 * <p>🐛 예전: 셋 다 {@code HIGHEST_PRECEDENCE} 였다. 같은 order 의 상대 순서는 빈 등록 순서에 맡겨져 보장되지 않는다 — 지금은 셋이
 * 서로 독립이라 무해하지만, 앞뒤에 기대는 네 번째가 들어오는 날 조용히 깨진다.
 *
 * <p>[JA] ゲートウェイのグローバルフィルター三つ — 順序を数値で決める。以前は三つとも HIGHEST_PRECEDENCE で相対順序が保証されなかった。
 */
@DisplayName("게이트웨이 전역 필터 순서")
class GlobalFilterOrderTest {

    @Test
    @DisplayName("🔴 로그 → 내부 엔드포인트 차단 → 신원 헤더 제거, 서로 다른 order — 모두 라우트 필터(1 부터)보다 앞")
    void distinctAndAheadOfRouteFilters() {
        List<Ordered> filters = List.of(new PreLoggingFilter(), new InternalEndpointBlockFilter(), new IdentityHeaderScrubFilter());
        List<Integer> orders = filters.stream().map(Ordered::getOrder).toList();

        assertThat(orders).as("같은 order 면 상대 순서가 보장되지 않는다").doesNotHaveDuplicates().isSorted();
        // 라우트 필터는 목록 위치 + 1(1 부터) · 내장 NettyWriteResponseFilter 는 -1 — 그보다 한참 앞
        assertThat(orders).allSatisfy(o -> assertThat(o).isLessThan(Ordered.HIGHEST_PRECEDENCE + 1000));
    }
}
