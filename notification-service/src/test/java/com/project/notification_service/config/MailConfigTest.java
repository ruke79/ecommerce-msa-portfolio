package com.project.notification_service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import com.project.notification_service.mail.MailSender;

/**
 * 발송기 선택 — <b>설정이 덜 된 채로 "실제 발송"을 켜지 못하게 막는 자리</b>.
 *
 * <p>🐛 운영에서 {@code MAIL_HOST} 가 {@code "REPLACE_ME_smtp_host"} 인 채로 배포됐다. 그 값은
 * 비어 있지 않으므로 {@code isBlank()} 검사를 통과했고, 서비스는 이렇게 찍으며 정상 기동했다:
 *
 * <pre>메일 발송기: smtp (실제 발송) from=…</pre>
 *
 * <p>가입 확인 메일은 큐에 정상 등록됐고, 25초 뒤 {@code UnknownHostException} WARN 다섯 줄을
 * 남기고 FAILED 가 됐다. 사용자에게는 "가입은 됐는데 메일이 안 온다" 로만 보였다.
 */
@DisplayName("메일 발송기 선택")
class MailConfigTest {

    private MailConfig config;
    private ObjectProvider<JavaMailSender> provider;

    @BeforeEach
    void setUp() {
        config = new MailConfig();
        provider = mockProvider(mock(JavaMailSender.class));
        set("provider", "smtp");
        set("smtpHost", "smtp.gmail.com");
        set("smtpUsername", "shop@example.com");
        set("smtpPassword", "app-password");
        set("from", "shop@example.com");
        set("fromName", "가게");
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<JavaMailSender> mockProvider(JavaMailSender sender) {
        ObjectProvider<JavaMailSender> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(sender);
        return p;
    }

    private void set(String field, String value) {
        ReflectionTestUtils.setField(config, field, value);
    }

    @Test
    @DisplayName("설정이 갖춰지면 실제 발송기를 만든다")
    void 정상_설정() {
        MailSender sender = config.notificationMailSender(provider);

        assertThat(sender.name()).isEqualTo("smtp");
        assertThat(sender.isLive()).isTrue();
    }

    /**
     * 🔴 이것이 이 파일의 핵심이다. 안 채운 값은 비어 있지 않아 예전 검사를 통과했다 —
     * 비우려면 지워야 하지만 안 채우는 것은 아무것도 안 하면 된다. 후자가 훨씬 흔한 실수다.
     */
    @Test
    @DisplayName("🐛 호스트가 자리 표시자면 기동을 막는다 — 25초 뒤 WARN 이 아니라 지금 터져야 한다")
    void 자리표시자_호스트() {
        set("smtpHost", "REPLACE_ME_smtp_host");

        assertThatThrownBy(() -> config.notificationMailSender(provider))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MAIL_HOST");
    }

    @Test
    @DisplayName("비밀번호가 자리 표시자여도 막는다 — 인증 실패로 알아내게 두지 않는다")
    void 자리표시자_비밀번호() {
        set("smtpPassword", "REPLACE_ME_smtp_app_password");

        assertThatThrownBy(() -> config.notificationMailSender(provider))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MAIL_PASSWORD")
                // 🔒 어느 키인지만 말한다. 값은 로그로 나간다.
                .hasMessageNotContaining("REPLACE_ME_smtp_app_password");
    }

    @Test
    @DisplayName("호스트가 비어 있으면 막는다")
    void 빈_호스트() {
        set("smtpHost", "");

        assertThatThrownBy(() -> config.notificationMailSender(provider))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.mail.host");
    }

    @Test
    @DisplayName("발신 주소가 비어 있으면 막는다 — SPF/DKIM 정렬에 직결된다")
    void 빈_발신주소() {
        set("from", "");

        assertThatThrownBy(() -> config.notificationMailSender(provider))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("notification.mail.from");
    }

    /**
     * 자리 표시자 검사가 <b>logging 경로까지</b> 번지면 안 된다. 기본값이 logging 인 것은
     * 의도된 안전장치이고, 그때는 MAIL_* 가 안 채워져 있는 것이 정상이다 — 여기서 막으면
     * 아무 설정도 없는 개발 환경이 기동조차 못 한다.
     */
    @Test
    @DisplayName("logging 발송기는 MAIL_* 가 자리 표시자여도 그대로 뜬다")
    void 로깅_경로는_검사하지_않는다() {
        set("provider", "logging");
        set("smtpHost", "REPLACE_ME_smtp_host");
        set("smtpPassword", "REPLACE_ME_smtp_app_password");

        MailSender sender = config.notificationMailSender(provider);

        assertThat(sender.isLive()).isFalse();
    }

    /**
     * {@code spring.mail.host} 가 빈 기본값({@code ${MAIL_HOST:}})이면 부트는 프로퍼티가
     * "존재한다"고 보아 JavaMailSender 빈을 만든다. 호스트를 따로 보지 않으면 매 발송이
     * 알 수 없는 오류로 실패하며 큐만 쌓인다.
     */
    @Test
    @DisplayName("JavaMailSender 빈이 없으면 막는다")
    void 위임_빈_없음() {
        ObjectProvider<JavaMailSender> empty = mockProvider(null);

        assertThatThrownBy(() -> config.notificationMailSender(empty))
                .isInstanceOf(IllegalStateException.class);
    }
}
