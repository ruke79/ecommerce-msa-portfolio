package com.project.chat_service.message.consumer;

import java.util.List;
import java.util.Map;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.messaging.handler.annotation.Headers;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.outbox.OutboxTraceContext;

import lombok.RequiredArgsConstructor;

/**
 * 원본(user-service)의 프로필 변경·탈퇴를 채팅 회원 복제본에 반영한다 — {@code user-updated}, {@code user-deleted}
 * (docs/48 §6-⑤).
 *
 * <p>{@code user-created} 만 받던 동안 복제본은 가입 순간의 값에서 멈춰 있었다. 닉네임·사진을 바꿔도 채팅에는 옛
 * 값이 보였고, 탈퇴해 원본을 익명화해도 여기에는 이메일·이름·사진이 남았다.
 *
 * <p>페이로드는 JSON 문자열이다. 필드 이름으로만 읽는다 — 모르는 필드는 무시되므로 보내는 쪽이 필드를 더해도
 * 깨지지 않는다. {@code userId} 가 없는 레코드는 고칠 수 없으니 실패로 보고해 DLT 로 격리한다.
 */
@Component
@RequiredArgsConstructor
public class UserLifecycleConsumer {

    // 가드(B-41·B-60b)가 애너테이션의 토픽을 문자열로 읽는다 — 애너테이션에는 상수 대신 글자를 쓴다.
    static final String UPDATED = "user-updated";
    static final String DELETED = "user-deleted";

    private final UserMessageListener userMessageListener;
    private final ObjectMapper objectMapper;
    /** 레코드 헤더의 traceparent 로 보낸 쪽(아웃박스 릴레이)의 추적을 이어받는다(docs/51 §4h). */
    private final OutboxTraceContext outboxTraceContext;

    @KafkaListener(id = "chat-user-updated", topics = "user-updated", containerFactory = "userLifecycleContainerFactory")
    public void updated(List<String> messages, @Headers Map<String, Object> kafkaHeaders) {
        for (int i = 0; i < messages.size(); i++) {
            try (OutboxTraceContext.TraceScope trace = outboxTraceContext.openFrom(
                    OutboxTraceContext.recordHeaders(kafkaHeaders, i), "receive " + UPDATED)) {
                JsonNode event = objectMapper.readTree(messages.get(i));
                userMessageListener.userUpdated(userId(event),
                        text(event, "nickname"), text(event, "image"),
                        event.path("updatedAt").asLong(0));
            } catch (Exception e) {
                // 실패 인덱스 보고 → 성공분 커밋 + 실패 레코드만 DLT 격리 (KafkaDltConfig, docs/11 §3)
                throw new BatchListenerFailedException(UPDATED + "(chat) 처리 실패", e, i);
            }
        }
    }

    @KafkaListener(id = "chat-user-deleted", topics = "user-deleted", containerFactory = "userLifecycleContainerFactory")
    public void deleted(List<String> messages, @Headers Map<String, Object> kafkaHeaders) {
        for (int i = 0; i < messages.size(); i++) {
            try (OutboxTraceContext.TraceScope trace = outboxTraceContext.openFrom(
                    OutboxTraceContext.recordHeaders(kafkaHeaders, i), "receive " + DELETED)) {
                userMessageListener.userDeleted(userId(objectMapper.readTree(messages.get(i))));
            } catch (Exception e) {
                throw new BatchListenerFailedException(DELETED + "(chat) 처리 실패", e, i);
            }
        }
    }

    private static long userId(JsonNode event) {
        JsonNode id = event.path("userId");
        if (!id.canConvertToLong()) {
            // TSID 는 JSON 숫자로 온다. 문자열로 오는 경우도 받는다.
            if (id.isTextual() && id.asText().matches("-?\\d+")) {
                return Long.parseLong(id.asText());
            }
            throw new IllegalArgumentException("userId 가 없는 사용자 이벤트");
        }
        return id.asLong();
    }

    private static String text(JsonNode event, String field) {
        JsonNode v = event.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }
}
