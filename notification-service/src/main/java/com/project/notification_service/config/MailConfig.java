package com.project.notification_service.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

import com.project.notification_service.mail.LoggingMailSender;
import com.project.notification_service.mail.MailSender;
import com.project.notification_service.mail.SmtpMailSender;

import lombok.extern.slf4j.Slf4j;

/**
 * 메일 발송기 선택. {@link SmsConfig} 와 같은 모양이다.
 *
 * <p><b>기본값이 로깅 발송기인 것은 의도적이다.</b> 근거는 SMS 와 조금 다르다 — 메일은 대개
 * 공짜지만 <b>되돌릴 수 없고 평판이 남는다.</b> 설정이 덜 된 채 배포돼 테스트 데이터의 주소로
 * 메일이 나가면 그 하드 바운스가 발신 도메인에 그대로 쌓이고, 한 번 스팸으로 분류된 도메인을
 * 되돌리는 데는 몇 주가 걸린다. 실제 발송은 {@code notification.mail.provider=smtp} 로 명시해야
 * 켜진다.
 */
@Slf4j
@Configuration
public class MailConfig {

    @Value("${notification.mail.provider:logging}")
    private String provider;

    @Value("${notification.mail.from:}")
    private String from;

    @Value("${notification.mail.from-name:}")
    private String fromName;

    /** SMTP 호스트. 비어 있으면 실제 발송을 켤 수 없다 — 아래 검사 참조. */
    @Value("${spring.mail.host:}")
    private String smtpHost;

    /** 자리 표시자 검사에만 쓴다. 값은 어디에도 찍지 않는다. */
    @Value("${spring.mail.username:}")
    private String smtpUsername;

    @Value("${spring.mail.password:}")
    private String smtpPassword;

    /**
     * ⚠️ <b>빈 이름이 {@code mailSender} 면 안 된다.</b> 스프링 부트의
     * {@code MailSenderPropertiesConfiguration} 이 같은 이름으로 {@code JavaMailSenderImpl} 을
     * 등록하기 때문에, 그대로 두면 {@code BeanDefinitionOverrideException} 으로
     * <b>애플리케이션이 아예 뜨지 않는다.</b> (실제로 이 실수를 하고 기동 테스트가 잡았다.)
     *
     * <p>타입은 겹치지 않는다 — 이 인터페이스는
     * {@code com.project.notification_service.mail.MailSender} 이고 스프링의 것은
     * {@code org.springframework.mail.MailSender} 라서, 타입 기준 주입은 애매해지지 않는다.
     * 부딪히는 것은 이름뿐이다.
     *
     * @param javaMailSender 스프링이 {@code spring.mail.host} 가 있을 때만 만드는 빈이다.
     *                       {@link ObjectProvider} 로 받는 이유: 설정이 없으면 이 빈도 없는데,
     *                       직접 주입하면 <b>logging 발송기를 쓰려는 경우에도 기동이 실패한다.</b>
     */
    @Bean
    public MailSender notificationMailSender(ObjectProvider<JavaMailSender> javaMailSender) {
        if (!"smtp".equalsIgnoreCase(provider)) {
            log.warn("메일 발송기: logging (실제로 발송하지 않습니다). "
                    + "실제 발송은 notification.mail.provider=smtp 로 켜세요.");
            return new LoggingMailSender();
        }

        JavaMailSender delegate = javaMailSender.getIfAvailable();
        // ⚠️ 호스트를 따로 본다. 부트의 조건은 "spring.mail.host 프로퍼티가 존재하는가"인데,
        //    yml 이 ${MAIL_HOST:} 처럼 빈 기본값을 주면 <b>빈 문자열도 '존재'로 친다.</b>
        //    그래서 JavaMailSender 빈은 만들어지지만 호스트가 없어, 여기서 막지 않으면
        //    매 발송이 알 수 없는 오류로 실패하며 큐만 쌓인다.
        if (delegate == null || smtpHost.isBlank()) {
            throw new IllegalStateException(
                    "notification.mail.provider=smtp 이지만 spring.mail.host 가 비어 있습니다.");
        }
        if (from.isBlank()) {
            // From 이 비면 JavaMail 이 SMTP 계정 주소로 대체한다 — 우연히 동작하지만
            // SPF/DKIM 정렬이 깨져 스팸으로 분류되기 쉽다. 명시하게 한다.
            throw new IllegalStateException(
                    "notification.mail.provider=smtp 이지만 notification.mail.from 이 비어 있습니다. "
                    + "발신 주소는 SPF/DKIM 정렬에 직결되므로 명시해야 합니다.");
        }

        // ⚠️ 위 두 검사는 **비어 있는 값**만 잡는다. 안 채운 값은 잡지 못했다 —
        //    "REPLACE_ME_smtp_host" 는 비어 있지 않으므로 통과했고, 서비스는 "smtp (실제 발송)"
        //    이라고 찍으며 정상 기동한 뒤 25초 동안 재시도 상한을 태우고 조용히 포기했다.
        //    실제로 겪은 일이다(Placeholders 에 경위가 적혀 있다).
        Placeholders.rejectUnfilled("notification.mail.provider=smtp", Placeholders.pairs(
                "spring.mail.host (MAIL_HOST)", smtpHost,
                "spring.mail.username (MAIL_USERNAME)", smtpUsername,
                "spring.mail.password (MAIL_PASSWORD)", smtpPassword,
                "notification.mail.from (MAIL_FROM)", from));

        log.info("메일 발송기: smtp (실제 발송) from={}", from);
        return new SmtpMailSender(delegate, from, fromName);
    }
}
