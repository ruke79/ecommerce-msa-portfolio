package com.project.chat_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.chat_service.constants.SupportThreadStatus;
import com.project.chat_service.dto.MessageDto;
import com.project.chat_service.model.SupportThread;
import com.project.chat_service.repository.jpa.SupportThreadRepository;
import com.project.common.constants.chat.MessageType;

/**
 * 상담원 답변 → 고객 알림 (docs/58 §10).
 */
@DisplayName("SupportReplyNotifier — 상담원 답변 알림")
class SupportReplyNotifierTest {

    static final Long CUSTOMER = 1001L;
    static final Long AGENT = 2001L;

    SupportThreadRepository threads = mock(SupportThreadRepository.class);
    SupportPresence presence = new SupportPresence();
    @SuppressWarnings("unchecked")
    KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    SupportReplyNotifier notifier;
    Instant now = Instant.parse("2026-09-25T01:00:00Z");

    @BeforeEach
    void setUp() {
        notifier = new SupportReplyNotifier(threads, presence, kafka, new ObjectMapper());
        notifier.setClock(Clock.fixed(now, ZoneOffset.UTC));
        SupportThread t = SupportThread.open(CUSTOMER, now);
        ReflectionTestUtils.setField(t, "id", 5L);
        ReflectionTestUtils.setField(t, "status", SupportThreadStatus.ACTIVE);
        ReflectionTestUtils.setField(t, "agentId", AGENT);
        when(threads.findById(5L)).thenReturn(Optional.of(t));
        SupportThread other = SupportThread.open(CUSTOMER, now);
        ReflectionTestUtils.setField(other, "id", 6L);
        ReflectionTestUtils.setField(other, "status", SupportThreadStatus.ACTIVE);
        ReflectionTestUtils.setField(other, "agentId", AGENT);
        when(threads.findById(6L)).thenReturn(Optional.of(other));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
    }

    private static MessageDto msg(String id, Long sender) {
        MessageDto m = MessageDto.builder().id(id).roomId("support-5").type(MessageType.TEXT_TALK)
                .content("환불 계좌를 알려 주세요").nickname("민지").build();
        m.setSenderId(sender);
        return m;
    }

    @Test
    @DisplayName("🔴 상담원이 답하면 고객에게 알린다 — 키는 상담 번호, 본문은 싣지 않는다")
    void agentReplyNotifiesCustomer() throws Exception {
        notifier.onMessage(msg("42", AGENT));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(eq("support-reply"), eq("5"), payload.capture());
        var json = new ObjectMapper().readTree(payload.getValue());
        assertThat(json.get("receiverId").asLong()).isEqualTo(CUSTOMER);
        assertThat(json.get("messageId").asText()).isEqualTo("42");
        assertThat(payload.getValue()).doesNotContain("환불 계좌");
    }

    @Test
    @DisplayName("고객 본인의 메시지는 알리지 않는다")
    void customerMessageSkipped() {
        notifier.onMessage(msg("42", CUSTOMER));

        verify(kafka, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("🔴 고객이 지금 상담을 보고 있으면 알리지 않는다 — 이미 화면에 떴다")
    void watchingCustomerSkipped() {
        presence.subscribed("sess-1", "sub-0", CUSTOMER, "support-5");

        notifier.onMessage(msg("42", AGENT));

        verify(kafka, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("다른 사람이 같은 방을 보고 있는 것은 상관없다 — 상담원이 보고 있어도 고객에게는 알린다")
    void otherWatcherIrrelevant() {
        presence.subscribed("sess-2", "sub-0", AGENT, "support-5");

        notifier.onMessage(msg("42", AGENT));

        verify(kafka).send(eq("support-reply"), eq("5"), anyString());
    }

    @Test
    @DisplayName("🔴 60초 안의 연속 답변은 한 번만 — 세 줄로 나눠 답해도 알림은 한 통")
    void throttled() {
        notifier.onMessage(msg("42", AGENT));
        notifier.onMessage(msg("43", AGENT));
        notifier.onMessage(msg("44", AGENT));
        verify(kafka, times(1)).send(anyString(), anyString(), anyString());

        notifier.setClock(Clock.fixed(now.plusSeconds(61), ZoneOffset.UTC));
        notifier.onMessage(msg("45", AGENT));
        verify(kafka, times(2)).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("발행이 실패하면 다음 답변은 다시 알린다 — 실패를 '보냈다' 로 세지 않는다")
    void failureDoesNotThrottle() {
        when(kafka.send(anyString(), anyString(), anyString())).thenThrow(new RuntimeException("broker down"));
        notifier.onMessage(msg("42", AGENT));

        org.mockito.Mockito.reset(kafka);
        notifier.onMessage(msg("43", AGENT));
        verify(kafka).send(eq("support-reply"), eq("5"), any());
    }

    @Test
    @DisplayName("🔴 비동기 발행 실패(브로커 거절 · 시간 초과)도 '보냈다' 로 세지 않는다 — 다음 답변은 다시 알린다")
    void asyncFailureDoesNotThrottle() {
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker timeout")));
        notifier.onMessage(msg("42", AGENT));
        assertThat(notifier.trackedThreads()).isZero();

        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        notifier.onMessage(msg("43", AGENT));
        verify(kafka, times(2)).send(eq("support-reply"), eq("5"), anyString());
    }

    @Test
    @DisplayName("🔴 창이 지난 기록은 지운다 — 본 적 있는 상담 번호가 계속 쌓이지 않는다")
    void expiredEntriesArePruned() {
        notifier.onMessage(msg("42", AGENT));
        assertThat(notifier.trackedThreads()).isEqualTo(1);

        notifier.setClock(Clock.fixed(now.plusSeconds(61), ZoneOffset.UTC));
        MessageDto later = msg("43", AGENT);
        later.setRoomId("support-6");
        notifier.onMessage(later);

        assertThat(notifier.trackedThreads()).isEqualTo(1);   // 5번은 지워지고 6번만
    }

    @Test
    @DisplayName("친구 채팅방·없는 상담은 무시한다")
    void ignoresOthers() {
        MessageDto friend = msg("42", AGENT);
        friend.setRoomId("4f1c2e3a-uuid");
        notifier.onMessage(friend);
        MessageDto missing = msg("43", AGENT);
        missing.setRoomId("support-99");
        notifier.onMessage(missing);

        verify(kafka, never()).send(anyString(), anyString(), anyString());
    }
}
