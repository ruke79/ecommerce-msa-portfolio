package com.project.notification_service.sms;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.project.notification_service.sms.PhoneNumbers.Region;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * 🔒 회귀 대상 — <b>휴대폰 인증번호가 운영 로그에 평문으로 남았다.</b>
 *
 * <p>이 발송기가 기본값이고(k8s 도 provider 를 바꾸지 않는다) 문자 본문 전체를 {@code body={}} 로
 * INFO 에 찍었다. 인증 문자의 본문에는 인증번호가 들어 있다. 로그를 읽을 수 있는 사람이면 남의
 * 번호로 인증을 끝낼 수 있었다. {@code VerificationService} 는 "코드는 절대 로그에 남기지 않는다"
 * 고 적어 두고 자기 로그에서는 빼 놓았지만, 그 코드를 받아 간 이쪽이 그대로 찍고 있었다.
 *
 * <p>전역 마스킹으로는 막을 수 없다 — 여섯 자리 숫자는 주문 번호·금액과 구별이 안 된다. 그래서
 * <b>실제로 찍힌 로그를 캡처해서</b> 확인한다. 소스를 훑는 정적 검사로는 인자 이름만 보이고
 * 그 안에 무엇이 들었는지는 보이지 않는다.
 */
@DisplayName("LoggingSmsSender — 문자 본문을 로그에 남기지 않는다")
class LoggingSmsSenderTest {

    private static final String CODE = "482913";

    private final Logger logger = (Logger) LoggerFactory.getLogger(LoggingSmsSender.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attach() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
        appender.stop();
    }

    private List<String> rendered() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    @DisplayName("🔴 인증 문자를 보내도 인증번호는 어느 로그 줄에도 없다 — 한국어·일본어 문구 모두")
    void verificationCodeNeverReachesTheLog() {
        LoggingSmsSender sender = new LoggingSmsSender();

        for (Region region : Region.values()) {
            sender.send("+821012345678", SmsTemplates.verificationCode(region, CODE));
        }

        assertThat(appender.list).as("발송 사실 자체는 남아야 한다").hasSize(Region.values().length);
        assertThat(rendered()).allSatisfy(line -> assertThat(line).doesNotContain(CODE));
        // 포맷 전 인자에도 없어야 한다 — JSON 인코더는 인자를 따로 직렬화할 수 있다.
        assertThat(appender.list).allSatisfy(e -> assertThat(e.getArgumentArray())
                .allSatisfy(arg -> assertThat(String.valueOf(arg)).doesNotContain(CODE)));
    }

    @Test
    @DisplayName("번호는 가려서 남기고, 길이와 한 건 여부는 남긴다 — 운영자가 볼 것은 이것이다")
    void logsOnlyMaskedNumberAndSize() {
        new LoggingSmsSender().send("+821012345678", "안녕하세요");

        String line = rendered().get(0);
        assertThat(line).contains("[SMS:dry-run]").contains("chars=5").contains("single=");
        assertThat(line).doesNotContain("12345678").doesNotContain("안녕하세요");
    }

    @Test
    @DisplayName("문구를 확인해야 하는 테스트는 메모리 기록으로 본다 — 로그가 아니다")
    void testsStillSeeTheTextInMemory() {
        LoggingSmsSender sender = new LoggingSmsSender();
        sender.send("+821012345678", SmsTemplates.verificationCode(Region.KR, CODE));

        assertThat(sender.sent()).singleElement()
                .satisfies(sent -> assertThat(sent.message()).contains(CODE));
    }
}
