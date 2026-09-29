package com.project.catalog_service.message.producer;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.catalog_service.model.Notice;

import lombok.extern.slf4j.Slf4j;

/**
 * 공지가 게시됐다는 사실을 알린다.
 *
 * <p>🐛 <b>지금까지 공지는 아무에게도 전달되지 않았다.</b> 관리자가 공지를 공개해도,
 * 그 사실을 아는 방법은 사용자가 스스로 공지 목록 화면에 들어가 보는 것뿐이었다.
 * 점검 예고처럼 <b>미리 봐야 의미가 있는</b> 공지는 그 방식으로는 전달되지 않는다.
 *
 * <p><b>개인 알림이 아니라 전체 알림이다.</b> 알림 서비스는 이 이벤트를
 * {@code broadcast_notification} 행 <b>하나</b>로 만든다 — 사용자 수만큼 알림 행을 만들지
 * 않는다(notification-service 의 BroadcastNotification 주석). 그래서 이 이벤트에는 받는
 * 사람이 없다. 그 점이 {@code coupon-issued}·{@code delivery-status} 와 다르다.
 *
 * <p><b>커밋한 뒤에 보낸다.</b> 공개 전환은 dirty checking 으로 커밋 시점에 반영되므로,
 * 트랜잭션 안에서 바로 발행하면 <b>아직 공개되지 않은(또는 롤백될) 공지</b>의 알림이 먼저
 * 나갈 수 있다. 사용자가 알림을 눌렀을 때 404 가 되거나, 더 나쁘게는 롤백된 초안의 제목이
 * 전체 알림으로 남는다. 트랜잭션 밖에서 불리면 그냥 바로 보낸다.
 *
 * <p><b>발행 실패가 게시를 되돌리지 않는다.</b> 공지는 이미 공개됐고 그것이 진실이다.
 * 알림이 안 나갔다고 공지를 내리면 더 나쁘다 — 목록에는 있어야 할 글이 사라진다.
 * (정석은 아웃박스다. coupon-service·delivery-service 도 같은 이유로 직접 발행한다.)
 */
@Slf4j
@Component
public class NoticePublishedProducer {

    /**
     * 이벤트에 싣는 본문 길이 상한.
     *
     * <p>공지 본문은 {@code text} 라 길이 제한이 없는데(Notice 주석), 알림에 보이는 것은
     * 두어 줄이다. 20KB 짜리 본문을 통째로 카프카에 흘려보낼 이유가 없고, 받는 쪽 컬럼도
     * {@code varchar(1000)} 이라 어차피 잘린다.
     */
    public static final int SUMMARY_LIMIT = 200;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${content.topics.notice-published:notice-published}")
    private String topic;

    public NoticePublishedProducer(
            @Qualifier("contentEventKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    /** 공지가 <b>공개로 전환됐을 때</b>만 부른다. 수정 저장마다 부르면 알림이 반복된다. */
    public void publish(Notice notice) {
        if (notice == null || notice.getNoticeId() == null || !notice.isPublished()) {
            return;
        }

        // 커밋되기 전의 값을 그대로 읽어 둔다 — afterCommit 시점에는 영속성 컨텍스트가
        // 닫혀 있을 수 있어 엔티티를 다시 만지면 LazyInitializationException 이 난다.
        Long noticeId = notice.getNoticeId();
        String title = notice.getTitle();
        String summary = summarize(notice.getBody());
        boolean pinned = notice.isPinned();

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send(noticeId, title, summary, pinned);
                }
            });
            return;
        }
        send(noticeId, title, summary, pinned);
    }

    private void send(Long noticeId, String title, String summary, boolean pinned) {
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("noticeId", noticeId);
            event.put("title", title);
            event.put("summary", summary);
            event.put("pinned", pinned);
            event.put("occurredAt", LocalDateTime.now().toString());
            // 공지 하나당 전체 알림 하나. 재게시(내렸다 다시 올림)해도 같은 키라 중복되지
            // 않는다 — source_event_id 가 UNIQUE 다.
            event.put("eventId", "notice:" + noticeId);

            kafkaTemplate.send(topic, String.valueOf(noticeId), objectMapper.writeValueAsString(event));
            log.debug("공지 게시 발행: noticeId={}", noticeId);

        } catch (Exception e) {
            // 공지는 이미 공개됐다. 알림만 못 나간 것이라 되돌리지 않는다.
            log.error("공지 게시 발행 실패: noticeId={} — 알림이 나가지 않는다: {}", noticeId, e.getMessage());
        }
    }

    /**
     * 본문 앞부분만. 줄바꿈을 공백으로 눌러 한 줄로 만든다(알림은 두어 줄만 보인다).
     *
     * <p>순수 함수라 따로 테스트한다 — 카프카를 띄우지 않고도 경계(빈 본문, 정확히 상한,
     * 상한 초과)를 고정할 수 있다.
     */
    public static String summarize(String body) {
        if (body == null) {
            return "";
        }
        String flattened = body.replaceAll("\\s+", " ").trim();
        return flattened.length() <= SUMMARY_LIMIT
                ? flattened
                : flattened.substring(0, SUMMARY_LIMIT) + "…";
    }
}
