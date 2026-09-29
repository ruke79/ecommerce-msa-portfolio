package com.project.notification_service.listener;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.notification_service.domain.MailKind;
import com.project.notification_service.mail.EmailAddresses;
import com.project.notification_service.service.MailService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code outgoing-mail} 토픽을 읽어 메일을 큐에 넣는다.
 *
 * <p>이 토픽은 다른 토픽들과 성격이 다르다. {@code order}·{@code payment} 는 <b>도메인 이벤트</b>라
 * 발행자가 스키마를 갖고 이쪽은 필요한 필드만 꺼내 읽지만, 이건 <b>명령</b>이다 — "이 사람에게
 * 이 종류의 메일을 보내라". 그래서 스키마를 이쪽이 소유하고
 * ({@link com.project.common.message.dto.request.OutgoingMailRequest}), 모르는 종류가 오면
 * 조용히 버리지 않고 남긴다.
 *
 * <p><b>여기서 메일을 보내지 않는다.</b> 큐에 넣기만 한다. 컨슈머 안에서 SMTP 를 호출하면
 * 메일 서버 지연이 곧 컨슈머 랙이 되고, 실패를 컨슈머 재시도로 처리하면 재시도마다 메일이 한 통씩
 * 더 나간다. 실제 발송은 {@link com.project.notification_service.service.MailDispatcher} 가 한다.
 *
 * <p>⚠️ <b>페이로드를 통째로 로그에 찍지 않는다.</b> {@code params} 에 가입 확인 링크가 들어 있고,
 * 그 링크는 그 자체로 계정을 활성화할 수 있다. 로그 열람 권한이 계정 탈취 권한이 되면 안 된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutgoingMailListener {

    private final MailService mailService;
    private final ObjectMapper objectMapper;
    /** 레코드 헤더의 traceparent 로 보낸 쪽의 추적을 이어받는다(docs/51 §4h). */
    private final com.project.common.outbox.OutboxTraceContext outboxTraceContext;

    @KafkaListener(id = "notification-outgoing-mail", topics = "outgoing-mail",
                   containerFactory = "notificationListenerContainerFactory")
    public void onOutgoingMail(List<String> messages,
            // 레코드마다 보낸 쪽의 추적을 이어받는다(docs/51 §4h). 배치 리스너에는 컨슈머 추적이 걸리지 않는다.
            @org.springframework.messaging.handler.annotation.Headers java.util.Map<String, Object> kafkaHeaders) {
        for (int i = 0; i < messages.size(); i++) {
            try (com.project.common.outbox.OutboxTraceContext.TraceScope trace = outboxTraceContext.openFrom(
                    com.project.common.outbox.OutboxTraceContext.recordHeaders(kafkaHeaders, i), "receive outgoing-mail")) {
                JsonNode command = objectMapper.readTree(messages.get(i));

                String kindName = text(command, "kind");
                MailKind kind = parseKind(kindName);
                if (kind == null) {
                    // 모르는 종류다. 재시도해도 같으므로 DLT 로 보내 사람이 보게 한다 —
                    // 조용히 버리면 "메일이 안 온다"를 추적할 방법이 없다.
                    throw new BatchListenerFailedException(
                            "알 수 없는 메일 종류: " + kindName, i);
                }

                String toEmail = text(command, "toEmail");
                if (!EmailAddresses.isValid(toEmail)) {
                    // 주소가 잘못된 것은 보내는 쪽의 문제다. 재시도해도 같다.
                    throw new BatchListenerFailedException(
                            "보낼 수 없는 주소: " + EmailAddresses.mask(toEmail), i);
                }

                mailService.enqueue(
                        text(command, "sourceEventId"),
                        toEmail,
                        longOf(command, "recipientUserId"),
                        kind,
                        params(command),
                        text(command, "locale"));

            } catch (BatchListenerFailedException e) {
                throw e;

            } catch (Exception e) {
                // 파싱 실패 등. 어느 레코드인지 알려야 앞의 레코드 오프셋은 커밋되고 이것만
                // 격리된다(docs/11 §3). 인덱스를 넘기지 않으면 배치 전체가 재전달된다.
                // ⚠️ 예외 메시지에 원문 일부가 섞일 수 있으므로 페이로드는 따로 찍지 않는다.
                throw new BatchListenerFailedException("메일 명령을 처리하지 못했습니다", e, i);
            }
        }
    }

    /** 모르는 이름이면 null. enum 이 늘어나기 전 버전이 배포돼 있을 수 있으므로 예외로 죽이지 않는다. */
    private MailKind parseKind(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return MailKind.valueOf(name.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * {@code params} 를 문자열 맵으로 꺼낸다.
     *
     * <p>값이 문자열이 아니어도(숫자·불리언) 문자열로 바꿔 받는다. 발행자가 JSON 타입을 조금
     * 다르게 쓴다고 메일이 안 나갈 이유는 없다.
     */
    private Map<String, String> params(JsonNode command) {
        JsonNode node = command.get("params");
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, String> result = new HashMap<>();
        node.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value != null && !value.isNull()) {
                result.put(entry.getKey(), value.isTextual() ? value.textValue() : value.toString());
            }
        });
        return result;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Long longOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.canConvertToLong() ? value.asLong() : null;
    }
}
