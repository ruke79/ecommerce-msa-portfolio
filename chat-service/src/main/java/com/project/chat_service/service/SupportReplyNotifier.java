package com.project.chat_service.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
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
 * <p>"이미 보냈다" 는 Redis 키 {@code chat:support-reply:<상담>}(SET NX · {@link #THROTTLE})로 정한다 — 파드가 여럿이라(docs/21 §2)
 * 메모리에 두면 같은 상담의 답변을 서로 다른 파드가 소비할 때 알림이 파드 수만큼 갔다. Redis 에 닿지 못하면 예전처럼 이 파드의 메모리로.
 *
 * <p>친구 채팅의 {@code chat} 토픽(Avro)에 수신자를 더하지 않은 이유: 필드를 더하면 스키마 버전과 이력, 한 릴리스
 * 동안의 옛 스키마 쓰기가 따라온다(docs/55). 상담 알림은 상담에만 있는 일이라 작은 토픽 하나로 뗀다.
 */
@Slf4j
@Component
public class SupportReplyNotifier {

    public static final String TOPIC = "support-reply";
    static final Duration THROTTLE = Duration.ofSeconds(60);
    static final String THROTTLE_KEY_PREFIX = "chat:support-reply:";

    private final SupportThreadRepository threads;
    private final SupportPresence presence;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redis;
    private final Map<Long, Instant> lastSent = new ConcurrentHashMap<>();
    private Clock clock = Clock.systemUTC();

    /** 이 파드의 메모리로만 거르는 판(테스트 · Redis 없음). */
    public SupportReplyNotifier(SupportThreadRepository threads, SupportPresence presence,
            KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this(threads, presence, kafkaTemplate, objectMapper, null);
    }

    @Autowired
    public SupportReplyNotifier(SupportThreadRepository threads, SupportPresence presence,
            @Qualifier("supportEventKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper, StringRedisTemplate redis) {
        this.threads = threads;
        this.presence = presence;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.redis = redis;
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
        String token = claim(threadId, now);
        if (token == null) {
            return;
        }

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
                            failed(threadId, now, token, ex);
                        }
                    });
        } catch (JsonProcessingException | RuntimeException e) {
            // 알림은 부가 기능이다 — 상담 메시지의 저장·전달을 되돌리지 않는다.
            failed(threadId, now, token, e);
        }
    }

    /** 이 상담의 알림을 지금 보내도 되는가 — 되면 기록을 남기고 그 표(token)를, 창 안이면 null. */
    private String claim(Long threadId, Instant now) {
        if (redis != null) {
            String token = UUID.randomUUID().toString();
            try {
                Boolean ok = redis.opsForValue().setIfAbsent(THROTTLE_KEY_PREFIX + threadId, token, THROTTLE);
                return Boolean.TRUE.equals(ok) ? token : null;
            } catch (RuntimeException e) {
                log.debug("상담 알림 중복 억제 — Redis 에 닿지 못해 이 파드의 메모리로 ({})", e.getClass().getSimpleName());
            }
        }
        Instant previous = lastSent.get(threadId);
        if (previous != null && previous.plus(THROTTLE).isAfter(now)) {
            return null;
        }
        // 창이 지난 항목은 지운다 — 예전에는 파드 수명 동안 지금까지 본 모든 상담 번호가 쌓였다. 남는 것은 최근 60초에 알린 상담뿐.
        lastSent.values().removeIf(sent -> !sent.plus(THROTTLE).isAfter(now));
        lastSent.put(threadId, now);
        return LOCAL;
    }

    /** 메모리 판의 표 — Redis 의 표와 섞이지 않는다. */
    private static final String LOCAL = "local";

    private void failed(Long threadId, Instant now, String token, Throwable e) {
        if (LOCAL.equals(token)) {
            lastSent.remove(threadId, now);
        } else if (redis != null) {
            try {
                // 내 표일 때만 지운다 — 그사이 창이 지나 다른 파드가 새로 잡은 기록을 지우지 않게.
                redis.execute(RELEASE, java.util.List.of(THROTTLE_KEY_PREFIX + threadId), token);
            } catch (RuntimeException re) {
                log.debug("상담 알림 기록을 지우지 못했다 — {}초 뒤 풀린다", THROTTLE.toSeconds());
            }
        }
        log.warn("상담 답변 알림 발행 실패: threadId={} ({})", threadId, e.getClass().getSimpleName());
    }

    private static final org.springframework.data.redis.core.script.RedisScript<Long> RELEASE =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                    "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end", Long.class);

    /** 테스트용 — 알림 기록이 남은 상담 수. */
    int trackedThreads() {
        return lastSent.size();
    }
}
