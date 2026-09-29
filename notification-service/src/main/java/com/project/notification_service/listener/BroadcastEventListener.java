package com.project.notification_service.listener;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.notification_service.domain.NotificationType;
import com.project.notification_service.service.BroadcastService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 전체 사용자 대상 이벤트를 <b>전체 알림</b>으로 바꾼다.
 *
 * <p><b>{@link NotificationEventListener} 와 왜 나눴나.</b> 저장 구조가 다르다. 개인
 * 알림은 받는 사람마다 행을 만들지만, 전체 알림은 공지당 행 하나이고 개인별로는 읽음
 * 표시만 남긴다(BroadcastNotification 주석). 한 클래스에 섞으면 메서드마다 "이건 개인 행을
 * 만들고 저건 안 만든다"가 뒤섞여 읽기 어려워진다. 무엇보다, 받는 사람이 없는 이벤트를
 * 받는 사람이 있는 리스너에 넣으면 {@code userId == null} 을 "버린다"로 처리하는 기존
 * 규칙과 정면으로 충돌한다.
 *
 * <p><b>멱등성.</b> 카프카는 at-least-once 다. 같은 공지 게시 이벤트가 재전달될 때마다
 * 공지가 하나씩 더 생기면 사용자 화면에 같은 줄이 쌓인다. 발행자가 만들어 준 안정적인
 * {@code eventId} 를 그대로 넘기고, {@code source_event_id} 의 UNIQUE 가 최종 방어선이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BroadcastEventListener {

    private final BroadcastService broadcastService;
    private final ObjectMapper objectMapper;
    /** 레코드 헤더의 traceparent 로 보낸 쪽의 추적을 이어받는다(docs/51 §4h). */
    private final com.project.common.outbox.OutboxTraceContext outboxTraceContext;

    /**
     * 공지사항 게시.
     *
     * <p>catalog-service 의 {@code NoticePublishedProducer} 가 공지를 <b>비공개 → 공개로
     * 전환한 순간에만</b> 발행한다. 공개된 글의 오타 수정에는 나오지 않는다.
     *
     * <p>SMS·메일로 나가지 않는다 — 전체 사용자에게 문자를 보내는 것은 요금 사고이고,
     * 단체 메일은 발신 평판을 깎는다(MailKind·SmsTemplates 주석). 앱 안에서만 보여 준다.
     */
    @KafkaListener(id = "notification-notice", topics = "notice-published",
                   containerFactory = "notificationListenerContainerFactory")
    public void onNoticePublished(List<String> messages,
            // 레코드마다 보낸 쪽의 추적을 이어받는다(docs/51 §4h). 배치 리스너에는 컨슈머 추적이 걸리지 않는다.
            @org.springframework.messaging.handler.annotation.Headers java.util.Map<String, Object> kafkaHeaders) {
        for (int i = 0; i < messages.size(); i++) {
            try (com.project.common.outbox.OutboxTraceContext.TraceScope trace = outboxTraceContext.openFrom(
                    com.project.common.outbox.OutboxTraceContext.recordHeaders(kafkaHeaders, i), "receive notice-published")) {
                JsonNode event = objectMapper.readTree(messages.get(i));

                String noticeId = textOf(event, "noticeId");
                String title = textOf(event, "title");
                if (noticeId == null || title == null) {
                    // 무엇을 알려야 할지 모르는 이벤트다. 버리되 조용히 버리지는 않는다.
                    log.warn("공지 이벤트에 id 나 제목이 없어 알림을 만들지 못함: {}",
                            abbreviate(messages.get(i)));
                    continue;
                }

                String eventId = textOf(event, "eventId");

                broadcastService.create(
                        NotificationType.NOTICE_PUBLISHED,
                        title,
                        textOf(event, "summary"),
                        NotificationType.NOTICE_PUBLISHED.path() + noticeId,
                        // 발행자가 "notice:<noticeId>" 로 만들어 준다. 없으면 우리가 만든다 —
                        // 키가 null 이면 UNIQUE 가 걸리지 않아 재전달마다 공지가 하나씩 는다.
                        eventId != null ? eventId : "notice:" + noticeId,
                        // 게시 즉시 보인다. 예약 게시는 공지 도메인에 아직 없다.
                        null,
                        // 공지는 스스로 만료되지 않는다 — 관리자가 내리기 전까지 남는다.
                        null);

            } catch (Exception e) {
                // 이 레코드만 DLT 로 보내고 나머지 배치는 계속 처리한다(docs/11 §3).
                throw new BatchListenerFailedException("notice-published 알림 처리 실패", e, i);
            }
        }
    }

    /**
     * 기획전 시작.
     *
     * <p>catalog-service 의 {@code EventScheduler} 가 시각에 맞춰 SCHEDULED → ACTIVE 로
     * 넘길 때 발행한다. 인스턴스가 둘이어도 조건부 UPDATE 로 <b>하나만</b> 발행하고,
     * 그래도 재전달되면 {@code eventId} 가 걸러 낸다.
     *
     * <p>종료는 듣지 않는다 — 발행하지도 않는다. "세일이 끝났습니다"는 사용자에게 쓸모가
     * 없고, 기획전 하나당 알림이 두 개가 된다.
     */
    @KafkaListener(id = "notification-event", topics = "event-started",
                   containerFactory = "notificationListenerContainerFactory")
    public void onEventStarted(List<String> messages,
            // 레코드마다 보낸 쪽의 추적을 이어받는다(docs/51 §4h). 배치 리스너에는 컨슈머 추적이 걸리지 않는다.
            @org.springframework.messaging.handler.annotation.Headers java.util.Map<String, Object> kafkaHeaders) {
        for (int i = 0; i < messages.size(); i++) {
            try (com.project.common.outbox.OutboxTraceContext.TraceScope trace = outboxTraceContext.openFrom(
                    com.project.common.outbox.OutboxTraceContext.recordHeaders(kafkaHeaders, i), "receive event-started")) {
                JsonNode event = objectMapper.readTree(messages.get(i));

                String slug = textOf(event, "slug");
                String title = textOf(event, "title");
                if (slug == null || title == null) {
                    log.warn("기획전 이벤트에 slug 나 제목이 없어 알림을 만들지 못함: {}",
                            abbreviate(messages.get(i)));
                    continue;
                }

                String eventId = textOf(event, "eventId");
                String endsAt = textOf(event, "endsAt");

                broadcastService.create(
                        NotificationType.EVENT_STARTED,
                        title,
                        eventBody(textOf(event, "description"), endsAt),
                        NotificationType.EVENT_STARTED.path() + slug,
                        // 발행자가 "event:<id>" 로 만들어 준다. 없으면 slug 로 만든다 —
                        // 키가 null 이면 UNIQUE 가 걸리지 않아 재전달마다 하나씩 는다.
                        eventId != null ? eventId : "event:" + slug,
                        null,
                        // 기획전이 끝나면 알림도 사라진다. 끝난 세일이 목록에 남아 있으면
                        // 눌러 들어갔다가 404 를 본다.
                        parseOrNull(endsAt));

            } catch (Exception e) {
                throw new BatchListenerFailedException("event-started 알림 처리 실패", e, i);
            }
        }
    }

    /**
     * 기획전 알림의 본문.
     *
     * <p>언제까지 하는지가 핵심이다 — 없으면 받아 두고 잊는다. 설명이 없으면 기간만이라도
     * 보여 준다.
     */
    private static String eventBody(String description, String endsAt) {
        String until = endsAt == null ? null : endsAt.substring(0, Math.min(endsAt.length(), 10));
        if (description == null || description.isBlank()) {
            return until == null ? "기획전이 시작되었습니다." : until + "까지 진행됩니다.";
        }
        return until == null ? description : description + " (" + until + "까지)";
    }

    /**
     * 만료 시각.
     *
     * <p>파싱에 실패하면 null 이다 — 만료 없는 알림이 되는 쪽이, 알림이 아예 안 만들어지는
     * 것보다 낫다. 형식이 어긋난 이벤트 하나 때문에 기획전 알림이 통째로 빠지면 안 된다.
     */
    private static LocalDateTime parseOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw);
        } catch (DateTimeParseException e) {
            log.warn("기획전 종료 시각을 읽지 못했다 — 만료 없는 알림으로 만든다: {}", raw);
            return null;
        }
    }

    private static String textOf(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull()) {
                return value.asText();
            }
        }
        return null;
    }

    private static String abbreviate(String raw) {
        return raw == null ? "" : raw.substring(0, Math.min(raw.length(), 200));
    }
}
