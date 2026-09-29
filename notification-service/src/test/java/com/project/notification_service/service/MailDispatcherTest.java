package com.project.notification_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.notification_service.domain.MailKind;
import com.project.notification_service.domain.MailMessage;
import com.project.notification_service.domain.MailStatus;
import com.project.notification_service.mail.MailResult;
import com.project.notification_service.mail.MailSender;
import com.project.notification_service.repository.MailMessageRepository;
import com.project.notification_service.repository.NotificationPreferenceRepository;

/**
 * 메일 디스패처 — <b>일시 실패와 영구 실패를 가르는 지점</b>.
 *
 * <p>이 구분이 흐려지면 두 방향 모두로 망가진다. 일시적 실패를 FAILED 로 적으면 그 메일은
 * 다시는 나가지 않아 사용자가 가입을 완료할 수 없고, 영구 실패를 계속 재시도하면 하드 바운스가
 * 쌓여 발신 도메인이 스팸으로 분류된다. 그래서 여기가 이 채널에서 가장 중요한 로직이다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("메일 디스패처")
class MailDispatcherTest {

    @Mock private MailMessageRepository mailMessageRepository;
    @Mock private NotificationPreferenceRepository preferenceRepository;

    private RecordingSender sender;
    private MailDispatcher dispatcher;

    /** 결과를 미리 정해 두고, 무엇을 보냈는지 기록하는 발송기. */
    static class RecordingSender implements MailSender {
        final List<String> subjects = new ArrayList<>();
        final List<String> bodies = new ArrayList<>();
        MailResult next = MailResult.sent();

        @Override public MailResult send(String to, String subject, String text, String html) {
            subjects.add(subject);
            bodies.add(text);
            return next;
        }
        @Override public boolean isLive() { return false; }
        @Override public String name() { return "recording"; }
    }

    @BeforeEach
    void setUp() {
        sender = new RecordingSender();
        ObjectMapper objectMapper = new ObjectMapper();
        MailService mailService =
                new MailService(mailMessageRepository, preferenceRepository, objectMapper);

        dispatcher = new MailDispatcher(mailMessageRepository, preferenceRepository, mailService, sender);
        ReflectionTestUtils.setField(dispatcher, "batchSize", 50);
        ReflectionTestUtils.setField(dispatcher, "maxAttempts", 3);
        ReflectionTestUtils.setField(dispatcher, "paramsRetentionHours", 24);
        ReflectionTestUtils.setField(dispatcher, "retryBackoffMs", 60_000L);
        ReflectionTestUtils.setField(dispatcher, "retryBackoffMaxMs", 1_800_000L);

        when(preferenceRepository.findByUserId(any())).thenReturn(Optional.empty());
    }

    private MailMessage message(MailKind kind, String paramsJson) {
        return MailMessage.builder()
                .toEmail("alice@example.com")
                .recipientUserId(7L)
                .kind(kind)
                .params(paramsJson)
                .locale("ko")
                .sourceEventId("evt-1")
                .build();
    }

    private void claimReturns(MailMessage... messages) {
        when(mailMessageRepository.claimForSend(anyList(), anyInt(), any(), any()))
                .thenReturn(List.of(messages));
    }

    @Test
    @DisplayName("발송에 성공하면 SENT 로 확정한다")
    void 성공() {
        MailMessage m = message(MailKind.REGISTRATION_CONFIRM,
                "{\"confirmationUrl\":\"https://shop.example.com/confirm?token=T\"}");
        claimReturns(m);
        sender.next = MailResult.sent();

        dispatcher.dispatch();

        assertThat(m.getStatus()).isEqualTo(MailStatus.SENT);
        assertThat(sender.bodies.get(0)).contains("https://shop.example.com/confirm?token=T");
    }

    @Test
    @DisplayName("🐛 일시적 실패를 FAILED 로 적지 않는다 — 적으면 그 메일은 영영 안 나간다")
    void 일시_실패는_재시도() {
        MailMessage m = message(MailKind.REGISTRATION_CONFIRM, "{\"confirmationUrl\":\"https://x.example/c\"}");
        claimReturns(m);
        sender.next = MailResult.retryable("smtp 421");

        dispatcher.dispatch();

        assertThat(m.getStatus()).isEqualTo(MailStatus.RETRYABLE);
        assertThat(m.getAttempts()).isEqualTo(1);
        // 다음 시도에 보낼 내용이 필요하므로 링크는 남아 있어야 한다.
        assertThat(m.rawParams()).isNotNull();
    }

    /**
     * 🔴 <b>이 파일에서 가장 중요한 테스트다.</b>
     *
     * <p>상한(5회)만 있고 간격이 없어서, 디스패처가 5초마다 같은 행을 다시 집어 재시도가
     * <b>20초 만에</b> 소진됐다. 운영 로그가 그 증거다 — 04:12:10 부터 04:12:30 까지 다섯 번.
     * 그레이리스팅은 수 분을 기다려야 풀리므로, 상한을 5로 둔 이유가 통째로 무효였다.
     *
     * <p>{@code nextAttemptAt} 이 미래여야 선점 질의가 이 행을 건너뛴다. null 로 되돌아가면
     * 즉시 다시 집히므로 여기서 잡힌다.
     */
    @Test
    @DisplayName("🔴 일시적 실패는 다음 시도 시각을 미래로 미룬다 — 없으면 5초 뒤 또 집힌다")
    void 재시도_간격() {
        MailMessage m = message(MailKind.REGISTRATION_CONFIRM, "{\"confirmationUrl\":\"https://x.example/c\"}");
        claimReturns(m);
        sender.next = MailResult.retryable("smtp 421");

        dispatcher.dispatch();

        assertThat(m.getNextAttemptAt())
                .as("다음 시도 시각이 없으면 백오프가 없던 때로 돌아간다")
                .isNotNull()
                .isAfter(LocalDateTime.now(ZoneOffset.UTC).plusSeconds(30));
    }

    /** 실패가 거듭될수록 더 오래 기다린다 — 원인이 오래 갈수록 때리는 횟수를 줄인다. */
    @Test
    @DisplayName("재시도가 거듭될수록 간격이 벌어진다")
    void 간격이_벌어진다() {
        MailMessage first = message(MailKind.ORDER_PLACED, null);
        claimReturns(first);
        sender.next = MailResult.retryable("smtp 421");
        dispatcher.dispatch();
        LocalDateTime afterFirst = first.getNextAttemptAt();

        claimReturns(first);
        dispatcher.dispatch();

        assertThat(first.getNextAttemptAt()).isAfter(afterFirst);
    }

    /**
     * 🐛 포기할 때 {@code recordAttempt} 를 한 번 더 불러 <b>한 번의 발송이 두 번으로</b>
     * 기록됐다. 나중에 "몇 번 시도했나"를 보는 사람에게는 상한을 넘긴 것으로 보인다.
     */
    @Test
    @DisplayName("🐛 포기할 때 시도 횟수를 두 번 세지 않는다")
    void 포기시_이중_계수() {
        MailMessage m = message(MailKind.ORDER_PLACED, null);
        m.recordAttempt(MailStatus.RETRYABLE, "1");
        m.recordAttempt(MailStatus.RETRYABLE, "2");
        claimReturns(m);
        sender.next = MailResult.retryable("smtp 421");

        dispatcher.dispatch();

        assertThat(m.getStatus()).isEqualTo(MailStatus.FAILED);
        assertThat(m.getAttempts()).as("상한이 3이면 정확히 3이어야 한다").isEqualTo(3);
    }

    /** 끝난 행에 예약 시각이 남아 있으면 "언제 다시 보내지나"를 묻게 만든다. 답은 '안 보낸다'다. */
    @Test
    @DisplayName("종료된 행에는 다음 시도 시각이 남지 않는다")
    void 종료시_예약_해제() {
        MailMessage m = message(MailKind.ORDER_PLACED, null);
        m.scheduleRetry("잠깐 실패", 60_000, 1_800_000);
        assertThat(m.getNextAttemptAt()).isNotNull();

        claimReturns(m);
        sender.next = MailResult.sent();
        dispatcher.dispatch();

        assertThat(m.getStatus()).isEqualTo(MailStatus.SENT);
        assertThat(m.getNextAttemptAt()).isNull();
    }

    /**
     * 🔴 선점 질의에 "시각이 됐는가" 를 넣으면서 <b>지금 시각을 넘기는 것</b>을 빠뜨리면
     * 아무것도 발송되지 않는다. 디스패처가 실제로 시각을 넘기는지 고정한다.
     */
    @Test
    @DisplayName("선점 질의에 현재 시각을 넘긴다")
    void 선점에_시각을_넘긴다() {
        when(mailMessageRepository.claimForSend(anyList(), anyInt(), any(), any()))
                .thenReturn(List.of());

        dispatcher.dispatch();

        org.mockito.ArgumentCaptor<LocalDateTime> captor =
                org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        org.mockito.Mockito.verify(mailMessageRepository)
                .claimForSend(anyList(), anyInt(), captor.capture(), any());

        assertThat(captor.getValue()).isNotNull()
                .isCloseTo(LocalDateTime.now(ZoneOffset.UTC),
                        within(1, java.time.temporal.ChronoUnit.MINUTES));
    }

    @Test
    @DisplayName("🐛 영구 실패를 재시도하지 않는다 — 하드 바운스가 쌓이면 도메인이 스팸이 된다")
    void 영구_실패는_포기() {
        MailMessage m = message(MailKind.REGISTRATION_CONFIRM, "{\"confirmationUrl\":\"https://x.example/c\"}");
        claimReturns(m);
        sender.next = MailResult.permanent("invalid_address");

        dispatcher.dispatch();

        assertThat(m.getStatus()).isEqualTo(MailStatus.FAILED);
        assertThat(m.rawParams()).isNull();
    }

    @Test
    @DisplayName("재시도 상한에 닿으면 FAILED 로 확정한다 — 무한히 붙잡지 않는다")
    void 상한_도달() {
        MailMessage m = message(MailKind.REGISTRATION_CONFIRM, "{\"confirmationUrl\":\"https://x.example/c\"}");
        m.recordAttempt(MailStatus.RETRYABLE, "1");
        m.recordAttempt(MailStatus.RETRYABLE, "2");   // 다음 시도가 3회째 = 상한
        claimReturns(m);
        sender.next = MailResult.retryable("smtp 421");

        dispatcher.dispatch();

        assertThat(m.getStatus()).isEqualTo(MailStatus.FAILED);
        assertThat(m.getDetail()).contains("상한");
    }

    @Test
    @DisplayName("🐛 일시적 실패면 배치를 멈춘다 — 계속 시도하면 타임아웃×배치만큼 잠금을 붙잡는다")
    void 일시_실패시_배치_중단() {
        MailMessage first = message(MailKind.ORDER_PLACED, null);
        MailMessage second = message(MailKind.ORDER_PLACED, null);
        claimReturns(first, second);
        sender.next = MailResult.retryable("connect refused");

        dispatcher.dispatch();

        // 두 번째는 손대지 않고 다음 주기로 넘긴다.
        assertThat(sender.subjects).hasSize(1);
        assertThat(second.getAttempts()).isZero();
        assertThat(second.getStatus()).isEqualTo(MailStatus.PENDING);
    }

    @Test
    @DisplayName("영구 실패면 배치를 계속한다 — 주소 하나가 잘못됐다고 나머지를 미룰 이유는 없다")
    void 영구_실패시_배치_계속() {
        MailMessage first = message(MailKind.ORDER_PLACED, null);
        MailMessage second = message(MailKind.ORDER_PLACED, null);
        claimReturns(first, second);
        sender.next = MailResult.permanent("no such user");

        dispatcher.dispatch();

        assertThat(sender.subjects).hasSize(2);
        assertThat(second.getStatus()).isEqualTo(MailStatus.FAILED);
    }

    @Test
    @DisplayName("보낼 것이 없으면 발송기를 부르지 않는다")
    void 빈_배치() {
        when(mailMessageRepository.claimForSend(anyList(), anyInt(), any(), any())).thenReturn(List.of());
        dispatcher.dispatch();
        assertThat(sender.subjects).isEmpty();
    }

    @Test
    @DisplayName("설정의 지역이 명령의 로케일보다 우선한다")
    void 설정_지역_우선() {
        MailMessage m = message(MailKind.ORDER_PLACED, "{\"subject\":\"주문 1234\"}");
        claimReturns(m);

        var preference = new com.project.notification_service.domain.NotificationPreference(7L);
        preference.confirmPhone("+819012345678", "JP");
        when(preferenceRepository.findByUserId(7L)).thenReturn(Optional.of(preference));

        dispatcher.dispatch();

        // locale 은 "ko" 였지만 설정이 JP 이므로 일본어로 나가야 한다.
        assertThat(sender.subjects.get(0)).contains("ご注文");
    }

    @Test
    @DisplayName("params 가 깨져 있어도 발송은 계속한다 — 문구만 어색해질 뿐이다")
    void 깨진_params() {
        MailMessage m = message(MailKind.ORDER_PLACED, "{not json");
        claimReturns(m);
        sender.next = MailResult.sent();

        dispatcher.dispatch();

        assertThat(m.getStatus()).isEqualTo(MailStatus.SENT);
        assertThat(sender.subjects).hasSize(1);
    }

    @Test
    @DisplayName("발송 대상은 PENDING 과 RETRYABLE 뿐이다 — SENT 를 다시 집으면 중복 발송이 된다")
    void 선점_대상_상태() {
        when(mailMessageRepository.claimForSend(anyList(), anyInt(), any(), any())).thenReturn(List.of());

        dispatcher.dispatch();

        org.mockito.ArgumentCaptor<List<MailStatus>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(mailMessageRepository)
                .claimForSend(captor.capture(), anyInt(), any(), any());

        assertThat(captor.getValue())
                .containsExactlyInAnyOrder(MailStatus.PENDING, MailStatus.RETRYABLE);
    }

    @Test
    @DisplayName("정리 작업이 오래된 행의 링크를 지운다")
    void 링크_정리() {
        when(mailMessageRepository.scrubParamsOlderThan(any())).thenReturn(3);
        dispatcher.scrubStaleParams();
        org.mockito.Mockito.verify(mailMessageRepository).scrubParamsOlderThan(any());
    }

    @Test
    @DisplayName("보낸 제목·본문에 종류가 반영된다")
    void 종류별_문구() {
        MailMessage m = message(MailKind.PASSWORD_RESET,
                "{\"resetUrl\":\"https://shop.example.com/reset?token=R\"}");
        claimReturns(m);

        dispatcher.dispatch();

        assertThat(sender.subjects.get(0)).contains("비밀번호");
        assertThat(sender.bodies.get(0)).contains("https://shop.example.com/reset?token=R");
    }

    /** {@code Map.of()} 를 쓰지 않는 이유: 값이 null 일 수 있는 자리를 그대로 다루기 위해서다. */
    @SuppressWarnings("unused")
    private static Map<String, String> params(String k, String v) {
        return v == null ? Map.of() : Map.of(k, v);
    }
}
