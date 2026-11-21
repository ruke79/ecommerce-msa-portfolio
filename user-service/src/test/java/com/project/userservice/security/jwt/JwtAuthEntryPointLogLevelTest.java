package com.project.userservice.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * 정상 401 은 ERROR 가 아니다(docs/99 §4 — 운영 user-service ERROR 의 큰 몫이 이 줄이었다, ErrorLogSurge 의 바닥을 올렸다).
 * [JA] 正常な 401 は ERROR ではない — 本番 user-service の ERROR の大きな割合がこの行だった。
 */
@DisplayName("JwtAuthEntryPoint — 정상 401 은 ERROR 로 남기지 않는다")
class JwtAuthEntryPointLogLevelTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(JwtAuthEntryPoint.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level original;

    @BeforeEach
    void attach() {
        original = logger.getLevel();
        logger.setLevel(Level.TRACE);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
        logger.setLevel(original);
    }

    @Test
    @DisplayName("🔴 401 응답 · 로그는 DEBUG 이하")
    void unauthorizedIsNotError() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        new JwtAuthEntryPoint().commence(new MockHttpServletRequest("GET", "/api/user/profile/me"), response,
                new InsufficientAuthenticationException("Full authentication is required"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(appender.list).isNotEmpty();
        assertThat(appender.list).allMatch(e -> !e.getLevel().isGreaterOrEqual(Level.WARN));
    }
}
