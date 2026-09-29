package com.project.notification_service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.message.dto.request.OutgoingMailRequest;
import com.project.notification_service.domain.MailStatus;
import com.project.notification_service.listener.OutgoingMailListener;
import com.project.notification_service.mail.LoggingMailSender;
import com.project.notification_service.mail.MailSender;
import com.project.notification_service.repository.MailMessageRepository;
import com.project.notification_service.service.MailDispatcher;

/**
 * 메일 채널 전 구간 — <b>서비스 사이의 계약</b>을 검사한다.
 *
 * <p>여기서 진짜로 검증하는 것은 로직이 아니라 <b>이음매</b>다. user-service 가 만드는
 * {@link OutgoingMailRequest} 를 실제로 Jackson 으로 직렬화해 리스너에 넣는다 — 필드 이름이
 * 하나라도 어긋나면 여기서 걸린다. 이 계약은 두 서비스가 <b>따로 배포되므로</b> 컴파일러가
 * 지켜 주지 않는다. 조용히 어긋나면 "가입은 되는데 메일이 안 온다"가 되고, 그 증상은 로그를
 * 뒤지기 전까지 아무도 모른다.
 *
 * <p>Kafka 컨테이너는 띄우지 않는다. 리스너가 받는 것은 어차피 JSON 문자열이고
 * (notification-service 의 모든 컨슈머가 그렇다), 브로커를 하나 더 띄우는 비용에 비해 얻는
 * 것이 적다. Kafka 배선 자체는 기존 리스너들과 같은 팩토리를 쓴다.
 */
@Tag("containers")
@Testcontainers
@SpringBootTest(properties = {
        // 디스패처를 테스트가 직접 부른다. 스케줄러가 같이 돌면 어느 쪽이 보냈는지 알 수 없다.
        "notification.mail.dispatch-initial-delay-ms=600000",
        "notification.sms.dispatch-initial-delay-ms=600000",
        "notification.mail.scrub-initial-delay-ms=600000"
})
@DisplayName("메일 채널 전 구간 (Testcontainers)")
class MailChannelIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("notificationdb")
                    .withUsername("product")
                    .withPassword("testpw");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379)
                    .withCommand("redis-server", "--requirepass", "testpw");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "testpw");
        registry.add("spring.data.redis.ssl.enabled", () -> false);
        registry.add("eureka.client.enabled", () -> false);
        registry.add("eureka.client.register-with-eureka", () -> false);
        registry.add("eureka.client.fetch-registry", () -> false);
        registry.add("management.tracing.enabled", () -> false);
        registry.add("server.ssl.enabled", () -> false);
    }

    @Autowired private OutgoingMailListener listener;
    @Autowired private MailDispatcher dispatcher;
    @Autowired private MailMessageRepository mailMessageRepository;
    @Autowired private MailSender mailSender;
    @Autowired private ObjectMapper objectMapper;

    private LoggingMailSender recorder;

    @BeforeEach
    void setUp() {
        mailMessageRepository.deleteAll();
        // 기본 발송기는 logging 이다(설정을 켜지 않았으므로). 보낸 내역을 검사에 쓴다.
        assertThat(mailSender).isInstanceOf(LoggingMailSender.class);
        recorder = (LoggingMailSender) mailSender;
        recorder.clear();
    }

    /** user-service 가 실제로 만드는 형태 그대로 직렬화한다. */
    private String serialized(OutgoingMailRequest request) throws Exception {
        return objectMapper.writeValueAsString(request);
    }

    private OutgoingMailRequest registrationConfirm(String eventId) {
        return OutgoingMailRequest.builder()
                .sourceEventId(eventId)
                .toEmail("alice@example.com")
                .recipientUserId(42L)
                .kind("REGISTRATION_CONFIRM")
                .params(Map.of("confirmationUrl",
                        "https://shop.example.com/user-service/registrationConfirm?token=TOKEN-1"))
                .locale("ko-KR")
                .build();
    }

    @Test
    @DisplayName("가입 확인 명령이 메일까지 이어진다 — 서비스 간 계약이 맞다는 증거")
    void 전_구간() throws Exception {
        listener.onOutgoingMail(List.of(serialized(registrationConfirm("evt-1"))), null);

        var queued = mailMessageRepository.findBySourceEventId("evt-1").orElseThrow();
        assertThat(queued.getStatus()).isEqualTo(MailStatus.PENDING);
        assertThat(queued.getToEmail()).isEqualTo("alice@example.com");

        dispatcher.dispatch();

        assertThat(recorder.sent()).hasSize(1);
        var sent = recorder.sent().get(0);
        assertThat(sent.to()).isEqualTo("alice@example.com");
        assertThat(sent.subject()).contains("회원가입");
        // 링크가 실제로 본문에 들어가야 한다 — 이게 빠지면 메일은 갔는데 가입은 못 한다.
        assertThat(sent.text()).contains("token=TOKEN-1");
        assertThat(sent.html()).contains("token=TOKEN-1");
    }

    @Test
    @DisplayName("🔒 발송이 끝나면 DB 에서 링크가 사라진다")
    void 발송후_링크_삭제() throws Exception {
        listener.onOutgoingMail(List.of(serialized(registrationConfirm("evt-2"))), null);
        dispatcher.dispatch();

        var after = mailMessageRepository.findBySourceEventId("evt-2").orElseThrow();
        assertThat(after.getStatus()).isEqualTo(MailStatus.SENT);
        assertThat(after.rawParams())
                .as("확인 링크는 그 자체로 계정을 활성화할 수 있다 — 발송 뒤 남길 이유가 없다")
                .isNull();
    }

    @Test
    @DisplayName("🐛 같은 명령이 두 번 와도 메일은 한 통이다 — Kafka 는 at-least-once 다")
    void 멱등성() throws Exception {
        String message = serialized(registrationConfirm("evt-3"));

        listener.onOutgoingMail(List.of(message), null);
        listener.onOutgoingMail(List.of(message), null);   // 재전달

        assertThat(mailMessageRepository.count()).isEqualTo(1);

        dispatcher.dispatch();
        assertThat(recorder.sent()).hasSize(1);
    }

    @Test
    @DisplayName("일본어 로케일이면 일본어로 나간다")
    void 로케일() throws Exception {
        listener.onOutgoingMail(List.of(serialized(OutgoingMailRequest.builder()
                .sourceEventId("evt-4")
                .toEmail("taro@example.jp")
                .recipientUserId(43L)
                .kind("PASSWORD_RESET")
                .params(Map.of("resetUrl", "https://shop.example.com/reset?token=R"))
                // user-service 는 Locale#toLanguageTag 를 넘긴다 → "ja-JP"
                .locale("ja-JP")
                .build())), null);

        dispatcher.dispatch();

        assertThat(recorder.sent().get(0).subject()).contains("パスワード");
    }

    @Test
    @DisplayName("보낼 수 없는 주소는 큐에 들어가지도 않는다")
    void 잘못된_주소() throws Exception {
        var bad = OutgoingMailRequest.builder()
                .sourceEventId("evt-5")
                .toEmail("not-an-email")
                .kind("REGISTRATION_CONFIRM")
                .params(Map.of("confirmationUrl", "https://x.example/c"))
                .build();

        // 보내는 쪽의 문제이므로 재시도 없이 격리된다(DLT).
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.kafka.listener.BatchListenerFailedException.class,
                () -> listener.onOutgoingMail(List.of(serialized(bad)), null));

        assertThat(mailMessageRepository.count()).isZero();
    }

    @Test
    @DisplayName("모르는 종류는 조용히 버리지 않고 격리한다 — 버리면 추적할 방법이 없다")
    void 모르는_종류() throws Exception {
        var unknown = OutgoingMailRequest.builder()
                .sourceEventId("evt-6")
                .toEmail("alice@example.com")
                .kind("NO_SUCH_KIND")
                .build();

        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.kafka.listener.BatchListenerFailedException.class,
                () -> listener.onOutgoingMail(List.of(serialized(unknown)), null));
    }

    @Test
    @DisplayName("🔒 명령 객체를 로그에 찍어도 링크가 새지 않는다")
    void toString_에_링크_없음() {
        String s = registrationConfirm("evt-7").toString();

        assertThat(s).doesNotContain("TOKEN-1");
        assertThat(s).doesNotContain("registrationConfirm?token");
        // 어떤 값이 실려 있었는지는 알 수 있어야 한다 — 키 이름까지는 남긴다.
        assertThat(s).contains("confirmationUrl");
        assertThat(s).contains("a***@example.com");
    }
}
