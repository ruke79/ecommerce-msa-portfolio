package com.project.userservice.security.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestTemplate;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * 깃허브 이메일 폴백의 두 가지 — 로그에 주소가 남지 않고, 호출이 영원히 묶이지 않는다.
 *
 * <p>🐛 예전: 예외 원문을 그대로 찍었다({@code e.getMessage()}) — 깃허브 응답 본문 · 요청 URL 에 섞인 이메일 주소가 로그 · Loki 에
 * 남았다(notification 의 N-H2 와 같은 결함). 그리고 {@code new RestTemplate()} 의 기본 팩토리는 타임아웃이
 * 무제한이라, 깃허브가 응답하지 않으면 그 로그인 스레드가 끝없이 묶였다.
 *
 * <p>[JA] GitHub メールフォールバックの 2 点 — ログに宛先が残らず、呼び出しが無限に縛られない。
 */
@DisplayName("깃허브 이메일 폴백 — 로그 · 타임아웃")
class GithubEmailFallbackTest {

    private static final String EMAIL = "dev.kim@example.com";

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(CustomOAuth2UserService.class);

    private CustomOAuth2UserService service;

    @BeforeEach
    void attach() {
        appender.start();
        logger.addAppender(appender);
        service = new CustomOAuth2UserService(mock(com.project.userservice.repository.jpa.UserRepository.class),
                mock(com.project.userservice.repository.jpa.RoleRepository.class),
                mock(org.springframework.security.crypto.password.PasswordEncoder.class),
                mock(SocialAccountLinker.class));
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    private String logs() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining("\n"));
    }

    @Test
    @DisplayName("🔴 조회가 실패해도 로그에 주소 원문이 남지 않는다 — 예외 종류만")
    void failureLogsTheExceptionTypeOnly() {
        // 예외 원문에 주소가 섞인 상황을 그대로 만든다 — 깃허브 응답 본문 · URL 에 흔히 들어간다.
        service.rest = new RestTemplate() {
            @Override
            public <T> org.springframework.http.ResponseEntity<T> exchange(
                    org.springframework.http.RequestEntity<?> entity,
                    org.springframework.core.ParameterizedTypeReference<T> type) {
                throw new org.springframework.web.client.ResourceAccessException(
                        "I/O error on GET /user/emails: 404 for " + EMAIL);
            }
        };

        assertThat(service.fetchGithubPrimaryEmail("tok")).isNull();

        assertThat(logs()).doesNotContain(EMAIL).doesNotContain("dev.kim").doesNotContain("@example.com");
        assertThat(logs()).contains("ResourceAccessException");
    }

    @Test
    @DisplayName("🔴 응답하지 않는 상대에게 묶이지 않는다 — 기본 RestTemplate 은 타임아웃이 무제한이다")
    void theCallTimesOut() throws IOException {
        // 받아만 놓고 아무것도 돌려주지 않는 서버 — 읽기 타임아웃이 없으면 영원히 기다린다.
        try (ServerSocket silent = new ServerSocket(0)) {
            String url = "http://127.0.0.1:" + silent.getLocalPort() + "/user/emails";
            RestTemplate rest = service.rest;

            assertTimeoutPreemptively(CustomOAuth2UserService.GITHUB_TIMEOUT.plus(Duration.ofSeconds(5)),
                    () -> assertThat(catchThrowable(() -> rest.getForObject(url, List.class))).isNotNull());
        }
    }

    private static Throwable catchThrowable(Runnable r) {
        try {
            r.run();
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }
}
