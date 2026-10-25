package com.project.chat_service.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.chat_service.dto.MessageDto;
import com.project.chat_service.model.SupportThread;
import com.project.chat_service.repository.jpa.SupportThreadRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 상담원이 답하면 고객에게 알린다 (docs/58 §10).
 *
 * <p>토픽 {@value #TOPIC} 에 {@code { receiverId, threadId, messageId, senderName }} 을 싣고, notification-service 가
 * 받아 알림(벨·SSE)을 만든다. <b>본문은 싣지 않는다</b> — 알림은 "답이 왔다" 만 말하고, 내용은 본인만 열 수 있는 상담
 * 화면에서 읽는다(알림 테이블·메일에 상담 내용이 남지 않게).
 *
 * <p>보내지 않는 경우:
 * <ul>
 *   <li>보낸 사람이 고객 본인이다</li>
 *   <li>고객이 지금 그 상담을 보고 있다({@link SupportPresence}) — 이미 화면에 떴다</li>
 *   <li>같은 상담에 {@link #THROTTLE} 안에 이미 보냈다 — 상담원이 세 줄로 나눠 답하면 알림도 세 통이 된다</li>
 * </ul>
 *
 * <p>친구 채팅의 {@code chat} 토픽(Avro)에 수신자를 더하지 않은 이유: 필드를 더하면 스키마 버전과 이력, 한 릴리스
 * 동안의 옛 스키마 쓰기가 따라온다(docs/55). 상담 알림은 상담에만 있는 일이라 작은 토픽 하나로 뗀다.
 */
@Slf4j
@Component
public class SupportReplyNotifier {

    public static final String TOPIC = "support-reply";
    static final Duration THROTTLE = Duration.ofSeconds(60);

    private final SupportThreadRepository threads;
    private final SupportPresence presence;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final Map<Long, Instant> lastSent = new ConcurrentHashMap<>();
    private Clock clock = Clock.systemUTC();

    public SupportReplyNotifier(SupportThreadRepository threads, SupportPresence presence,
            @Qualifier("supportEventKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper) {
        this.threads = threads;
        this.presence = presence;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    /** 저장된 상담 메시지 하나 — 소비자(MessageConsumer)가 부른다. */
    public void onMessage(MessageDto message) {
        Long threadId = SupportThread.threadIdOf(message.getRoomId());
        if (threadId == null || threadId <= 0 || message.getSenderId() == null) {
            return;
        }
        SupportThread thread = threads.findById(threadId).orElse(null);
        if (thread == null || thread.isCustomer(message.getSenderId())) {
            return;
        }
        Long customerId = thread.getCustomerId();
        if (presence.watching(customerId, thread.roomId())) {
            return;
        }
        Instant now = Instant.now(clock);
        Instant previous = lastSent.get(threadId);
        if (previous != null && previous.plus(THROTTLE).isAfter(now)) {
            return;
        }
        // 창이 지난 항목은 지운다 — 예전에는 파드 수명 동안 지금까지 본 모든 상담 번호가 쌓였다. 남는 것은 최근 60초에 알린 상담뿐.
        lastSent.values().removeIf(sent -> !sent.plus(THROTTLE).isAfter(now));
        lastSent.put(threadId, now);

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("receiverId", customerId);
        event.put("threadId", threadId);
        event.put("messageId", message.getId());
        event.put("senderName", message.getNickname());
        try {
            // 키 = 상담 번호 — 같은 상담의 알림은 한 파티션에서 순서대로(docs/38 §1.15).
            kafkaTemplate.send(TOPIC, String.valueOf(threadId), objectMapper.writeValueAsString(event))
                    .whenComplete((result, ex) -> {
                        // 🐛 예전: 결과를 보지 않아 브로커 거절 · 시간 초과(비동기)는 흔적이 없었고, '보냈다' 기록이 남아
                        //    60초 동안 다음 답변의 알림도 막혔다. 동기 실패와 같이 기록을 지우고 남긴다.
                        // [JA] 以前は結果を見ず、非同期の失敗は痕跡がなく、送信済み記録が残って 60 秒間次の通知も止まった。
                        if (ex != null) {
                            failed(threadId, now, ex);
                        }
                    });
        } catch (JsonProcessingException | RuntimeException e) {
            // 알림은 부가 기능이다 — 상담 메시지의 저장·전달을 되돌리지 않는다.
            failed(threadId, now, e);
        }
    }

    private void failed(Long threadId, Instant now, Throwable e) {
        lastSent.remove(threadId, now);
        log.warn("상담 답변 알림 발행 실패: threadId={} ({})", threadId, e.getClass().getSimpleName());
    }

    /** 테스트용 — 알림 기록이 남은 상담 수. */
    int trackedThreads() {
        return lastSent.size();
    }
}
