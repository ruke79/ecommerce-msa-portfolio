package com.project.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;

/**
 * 요청의 사용자 id 를 로그 문맥에 넣는다 — 그리고 요청이 끝나면 <b>그것만</b> 지운다.
 */
@DisplayName("UserMdcFilter")
class UserMdcFilterTest {

    private final UserMdcFilter filter = new UserMdcFilter();

    @AfterEach
    void clear() {
        MDC.clear();
    }

    /** 체인 안에서 본 MDC 의 userId. */
    private String userIdSeenInside(MockHttpServletRequest request) throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        FilterChain chain = (req, res) -> seen.set(MDC.get(UserMdcFilter.MDC_KEY));
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return seen.get();
    }

    @Test
    @DisplayName("🔴 게이트웨이가 넣은 User-Id 가 요청 동안 MDC userId 가 되고, 끝나면 지워진다")
    void putsAndRemovesUserId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("User-Id", "736459283746592837");

        assertThat(userIdSeenInside(request)).isEqualTo("736459283746592837");
        assertThat(MDC.get(UserMdcFilter.MDC_KEY)).as("풀 스레드가 남의 userId 를 달고 다니면 안 된다").isNull();
    }

    /** MDC.clear() 를 하면 추적이 넣은 traceId 까지 지워 같은 스레드의 뒤따르는 로그가 추적을 잃는다. */
    @Test
    @DisplayName("🔴 끝날 때 traceId 는 건드리지 않는다 — userId 만 지운다")
    void leavesTracingAlone() throws Exception {
        MDC.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("User-Id", "7");

        userIdSeenInside(request);

        assertThat(MDC.get("traceId")).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    }

    @Test
    @DisplayName("헤더가 없으면 아무것도 넣지 않는다 — 로그인 전 요청")
    void noHeaderNoUser() throws Exception {
        assertThat(userIdSeenInside(new MockHttpServletRequest())).isNull();
    }

    /** 게이트웨이를 거치지 않은 요청은 헤더를 꾸밀 수 있다. 로그 표시에만 쓰지만 가짜 줄은 못 만들게 한다. */
    @Test
    @DisplayName("🔒 줄바꿈·특수문자는 걸러진다 — 로그에 가짜 줄을 끼워 넣지 못한다")
    void blocksLogInjection() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("User-Id", "7\n2026-09-24 INFO forged line <script>");

        assertThat(userIdSeenInside(request)).isEqualTo("72026-09-24INFOforgedlinescript");
        assertThat(UserMdcFilter.sanitize("x".repeat(200))).hasSize(64);
        assertThat(UserMdcFilter.sanitize("\n\t ")).isNull();
    }
}
