package com.project.notification_service.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import com.project.notification_service.domain.MailKind;
import com.project.notification_service.sms.PhoneNumbers.Region;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

/**
 * 메일 발송 로그에 확인 코드 · 받는 주소 원문이 남지 않는다.
 *
 * <p>🐛 예전: 두 발송기가 INFO 로 {@code subject={}} 를 찍었다 — 로그인 확인 메일은 제목에 6자리 코드가 들어가므로, 로그를 읽을 수
 * 있는 사람이 10분짜리 2차 인증 코드를 얻었다. SMTP 예외 메시지(받는 주소 원문 포함)도 그대로 찍히고 detail 에 저장됐다.
 *
 * <p>[JA] メール送信ログに確認コード・宛先の生アドレスが残らない。以前は件名(ログイン確認コード入り)と SMTP 例外メッセージをそのまま残していた。
 */
@DisplayName("메일 발송 로그 — 확인 코드 · 주소 원문 없음")
class MailSenderLogPrivacyTest {

    private static final String CODE = "482913";
    private static final String TO = "alice.kim@example.com";

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final List<Logger> loggers = List.of((Logger) LoggerFactory.getLogger(SmtpMailSender.class),
            (Logger) LoggerFactory.getLogger(LoggingMailSender.class));

    @BeforeEach
    void attach() {
        appender.start();
        loggers.forEach(l -> l.addAppender(appender));
    }

    @AfterEach
    void detach() {
        loggers.forEach(l -> l.detachAppender(appender));
    }

    private String logs() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining("\n"));
    }

    private static MailTemplates.Rendered loginChallenge() {
        MailTemplates.Rendered r = MailTemplates.render(Region.KR, MailKind.LOGIN_CHALLENGE, Map.of("code", CODE));
        assertThat(r.subject()).as("전제 — 제목에 코드가 들어 있다").contains(CODE);
        return r;
    }

    private static JavaMailSender smtp() {
        JavaMailSender delegate = mock(JavaMailSender.class);
        when(delegate.createMimeMessage()).thenAnswer(i -> new MimeMessage(Session.getInstance(new Properties())));
        return delegate;
    }

    @Test
    @DisplayName("🔴 SMTP 발송 성공 로그에 제목(확인 코드)이 없다")
    void smtpSentLogHasNoSubject() {
        MailTemplates.Rendered r = loginChallenge();

        MailResult result = new SmtpMailSender(smtp(), "no-reply@example.com", "shop").send(TO, r.subject(), r.text(), r.html());

        assertThat(result.status()).isEqualTo(MailResult.sent().status());
        assertThat(logs()).contains("[MAIL:sent]").doesNotContain(CODE).doesNotContain(TO);
    }

    @Test
    @DisplayName("🔴 dry-run 발송기 로그에도 제목(확인 코드)이 없다")
    void dryRunLogHasNoSubject() {
        MailTemplates.Rendered r = loginChallenge();

        new LoggingMailSender().send(TO, r.subject(), r.text(), r.html());

        assertThat(logs()).contains("[MAIL:dry-run]").doesNotContain(CODE).doesNotContain(TO);
    }

    @Test
    @DisplayName("🔴 SMTP 예외 메시지의 받는 주소 원문은 로그 · detail 에 남지 않는다 — 예외 종류와 코드만")
    void smtpErrorMessageIsNotLogged() {
        JavaMailSender delegate = smtp();
        doThrow(new MailSendException("550 5.1.1 <" + TO + ">: Recipient address rejected"))
                .when(delegate).send(any(MimeMessage.class));

        MailResult result = new SmtpMailSender(delegate, "no-reply@example.com", "shop").send(TO, "제목", "본문", "");

        assertThat(logs()).doesNotContain(TO).contains("MailSendException");
        assertThat(result.detail()).doesNotContain(TO).isEqualTo("MailSendException");
    }
}
