package com.project.gatewayserver.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import reactor.core.publisher.Mono;

/**
 * 요청마다의 경로 로그는 DEBUG — 운영 기본(INFO)에서는 한 줄도 쓰지 않는다.
 *
 * <p>🐛 2026-10-23 CPU 프로파일(docs/83 §5): INFO 로 모든 요청에 한 줄씩 써 게이트웨이 CPU 의 4.7% 였다.
 * <p>[JA] リクエストごとのパスのログは DEBUG — 既定の INFO では一行も書かない。
 */
@DisplayName("게이트웨이 요청 경로 로그 — DEBUG")
class PreLoggingFilterLevelTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(PreLoggingFilter.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level before;

    @BeforeEach
    void attach() {
        before = logger.getLevel();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
        logger.setLevel(before);
    }

    private void request() {
        new PreLoggingFilter().filter(MockServerWebExchange.from(MockServerHttpRequest.get("/catalog-service/api/search")),
                ex -> Mono.empty()).block();
    }

    @Test
    @DisplayName("🔴 INFO 에서는 쓰지 않고, DEBUG 로 켜면 원래 경로가 한 줄")
    void pathLogIsDebugOnly() {
        logger.setLevel(Level.INFO);
        request();
        assertThat(appender.list).isEmpty();

        logger.setLevel(Level.DEBUG);
        request();
        assertThat(appender.list).singleElement().satisfies(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(e.getFormattedMessage()).isEqualTo("Original Request Path: /catalog-service/api/search");
        });
    }
}
