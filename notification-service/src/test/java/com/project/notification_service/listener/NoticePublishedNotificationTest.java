package com.project.notification_service.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.notification_service.domain.NotificationType;
import com.project.notification_service.service.BroadcastService;

/**
 * 🐛 회귀 대상: <b>공지를 게시해도 아무에게도 전달되지 않았다.</b>
 *
 * <p>사용자가 스스로 공지 목록 화면에 들어와야만 볼 수 있었다. 점검 예고처럼 <b>미리 봐야
 * 의미가 있는</b> 공지가 그대로 묻혔다.
 *
 * <p>여기서 고정하는 것은 "공지 이벤트 → 전체 알림" 변환이다. 받는 사람이 <b>없다</b>는
 * 점이 다른 리스너와 근본적으로 다르다 — 공지당 행 하나이고 개인별로는 읽음 표시만 남는다
 * (BroadcastNotification 주석).
 */
class NoticePublishedNotificationTest {

    private final BroadcastService broadcastService = mock(BroadcastService.class);
    private final BroadcastEventListener listener =
            new BroadcastEventListener(broadcastService, new ObjectMapper(), com.project.common.outbox.OutboxTraceContext.NOOP);

    private static String event() {
        return """
            {
              "noticeId": 42,
              "title": "시스템 점검 안내",
              "summary": "9월 20일 02:00~04:00 서비스가 중단됩니다.",
              "pinned": true,
              "occurredAt": "2026-09-15T10:00:00",
              "eventId": "notice:42"
            }
            """;
    }

    private ArgumentCaptor<String> capture(int position) {
        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> eventId = ArgumentCaptor.forClass(String.class);
        verify(broadcastService).create(any(), title.capture(), content.capture(),
                url.capture(), eventId.capture(), any(), any());
        return switch (position) {
            case 0 -> title;
            case 1 -> content;
            case 2 -> url;
            default -> eventId;
        };
    }

    @Test
    @DisplayName("공지가 게시되면 전체 알림을 만든다")
    void createsBroadcast() {
        listener.onNoticePublished(List.of(event()), null);

        assertThat(capture(0).getValue()).isEqualTo("시스템 점검 안내");
    }

    @Test
    @DisplayName("본문 요약이 알림 내용이 된다")
    void summaryBecomesContent() {
        listener.onNoticePublished(List.of(event()), null);

        assertThat(capture(1).getValue()).contains("9월 20일");
    }

    /** 알림을 눌렀을 때 그 공지로 가야 한다. 프론트가 "/" + url 로 이동한다. */
    @Test
    @DisplayName("공지 상세로 가는 링크를 단다")
    void linksToTheNotice() {
        listener.onNoticePublished(List.of(event()), null);

        assertThat(capture(2).getValue()).isEqualTo("notice/42");
    }

    @Test
    @DisplayName("NOTICE_PUBLISHED 종류로 만든다")
    void usesNoticeType() {
        listener.onNoticePublished(List.of(event()), null);

        ArgumentCaptor<NotificationType> type = ArgumentCaptor.forClass(NotificationType.class);
        verify(broadcastService).create(type.capture(), anyString(), any(), anyString(),
                anyString(), any(), any());

        assertThat(type.getValue()).isEqualTo(NotificationType.NOTICE_PUBLISHED);
    }

    /**
     * 카프카는 at-least-once 다. 재전달될 때마다 공지가 하나씩 더 생기면 사용자 화면에
     * 같은 줄이 쌓인다 — 발행자가 만들어 준 안정적인 키를 그대로 넘겨야
     * {@code source_event_id} UNIQUE 가 막는다.
     */
    @Test
    @DisplayName("발행자가 준 eventId 를 그대로 넘긴다")
    void passesThroughStableEventId() {
        listener.onNoticePublished(List.of(event()), null);

        assertThat(capture(3).getValue()).isEqualTo("notice:42");
    }

    /**
     * 키가 null 이면 UNIQUE 가 걸리지 않아(널은 서로 충돌하지 않는다) 재전달마다 공지가
     * 하나씩 는다. 옛 형식의 이벤트여도 id 로 키를 만들어 낸다.
     */
    @Test
    @DisplayName("eventId 가 없으면 공지 id 로 만들어 낸다 — 키가 비면 중복이 막히지 않는다")
    void derivesEventIdWhenMissing() {
        listener.onNoticePublished(List.of("{\"noticeId\":42,\"title\":\"제목\"}"), null);

        assertThat(capture(3).getValue()).isEqualTo("notice:42");
    }

    /** 게시 즉시 보이고, 관리자가 내리기 전까지 만료되지 않는다. */
    @Test
    @DisplayName("시작·종료 시각을 정하지 않는다 — 즉시 보이고 스스로 사라지지 않는다")
    void noSchedule() {
        listener.onNoticePublished(List.of(event()), null);

        ArgumentCaptor<LocalDateTime> startsAt = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> endsAt = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(broadcastService).create(any(), anyString(), any(), anyString(), anyString(),
                startsAt.capture(), endsAt.capture());

        assertThat(startsAt.getValue()).isNull();
        assertThat(endsAt.getValue()).isNull();
    }

    @Test
    @DisplayName("id 나 제목이 없으면 건너뛴다 — 알림 하나 때문에 컨슈머가 멈추면 안 된다")
    void skipsIncompleteEvents() {
        listener.onNoticePublished(List.of(
                "{\"title\":\"제목만 있음\"}",
                "{\"noticeId\":7}"), null);

        verify(broadcastService, never()).create(any(), anyString(), any(), anyString(),
                anyString(), any(), any());
    }

    @Test
    @DisplayName("본문 요약이 없어도 알림은 만든다")
    void worksWithoutSummary() {
        listener.onNoticePublished(List.of("{\"noticeId\":9,\"title\":\"제목\",\"eventId\":\"notice:9\"}"), null);

        assertThat(capture(1).getValue()).isNull();
    }

    // ── 기획전 시작 ─────────────────────────────────────────────────────────
    //
    // 공지와 같은 전체 알림 구조를 쓴다 — 모든 사용자에게 같은 내용이라 사용자 수만큼
    // 알림 행을 만들 이유가 없다. 다른 점은 **만료가 있다**는 것이다: 기획전이 끝나면
    // 알림도 사라져야 한다.

    private static String eventStarted(String extra) {
        return """
            {
              "eventKey": 7,
              "title": "가을 대세일",
              "slug": "autumn-sale",
              "description": "전 상품 최대 50%%",
              "productCount": 42,
              "endsAt": "2026-10-01T23:59:00",
              "eventId": "event:7"
              %s
            }
            """.formatted(extra);
    }

    @Test
    @DisplayName("기획전이 시작되면 전체 알림을 만든다")
    void eventStartedCreatesBroadcast() {
        listener.onEventStarted(List.of(eventStarted("")), null);

        assertThat(capture(0).getValue()).isEqualTo("가을 대세일");
    }

    @Test
    @DisplayName("기획전 상세로 가는 링크를 단다")
    void eventLinksToItsPage() {
        listener.onEventStarted(List.of(eventStarted("")), null);

        assertThat(capture(2).getValue()).isEqualTo("event/autumn-sale");
    }

    @Test
    @DisplayName("EVENT_STARTED 종류로 만든다")
    void eventUsesItsOwnType() {
        listener.onEventStarted(List.of(eventStarted("")), null);

        ArgumentCaptor<NotificationType> type = ArgumentCaptor.forClass(NotificationType.class);
        verify(broadcastService).create(type.capture(), anyString(), any(), anyString(),
                anyString(), any(), any());

        assertThat(type.getValue()).isEqualTo(NotificationType.EVENT_STARTED);
    }

    /** 언제까지 하는지가 이 알림의 핵심이다 — 없으면 받아 두고 잊는다. */
    @Test
    @DisplayName("언제까지 하는지를 본문에 넣는다")
    void eventBodyCarriesTheDeadline() {
        listener.onEventStarted(List.of(eventStarted("")), null);

        assertThat(capture(1).getValue()).contains("2026-10-01");
    }

    /**
     * 🔴 끝난 세일이 알림 목록에 남아 있으면 눌러 들어갔다가 404 를 본다. 기획전 상세는
     * 진행 중일 때만 열린다(EventService.publicOne).
     */
    @Test
    @DisplayName("기획전 종료 시각이 알림의 만료 시각이 된다 — 끝난 세일이 목록에 남으면 안 된다")
    void eventExpiresWhenTheSaleEnds() {
        listener.onEventStarted(List.of(eventStarted("")), null);

        ArgumentCaptor<LocalDateTime> endsAt = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(broadcastService).create(any(), anyString(), any(), anyString(), anyString(),
                any(), endsAt.capture());

        assertThat(endsAt.getValue()).isEqualTo(LocalDateTime.parse("2026-10-01T23:59:00"));
    }

    @Test
    @DisplayName("종료 시각이 없으면 만료 없는 알림이다")
    void openEndedEventNeverExpires() {
        listener.onEventStarted(List.of(
                "{\"slug\":\"forever\",\"title\":\"상시 세일\",\"eventId\":\"event:1\"}"), null);

        ArgumentCaptor<LocalDateTime> endsAt = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(broadcastService).create(any(), anyString(), any(), anyString(), anyString(),
                any(), endsAt.capture());

        assertThat(endsAt.getValue()).isNull();
    }

    /**
     * 형식이 어긋난 이벤트 하나 때문에 기획전 알림이 통째로 빠지면 안 된다. 만료 없는
     * 알림이 되는 쪽이 알림이 아예 없는 것보다 낫다.
     */
    @Test
    @DisplayName("종료 시각을 읽지 못해도 알림은 만든다")
    void unparseableDeadlineStillCreatesTheNotification() {
        listener.onEventStarted(List.of(
                "{\"slug\":\"s\",\"title\":\"세일\",\"endsAt\":\"내일까지\",\"eventId\":\"event:2\"}"), null);

        verify(broadcastService).create(any(), anyString(), any(), anyString(), anyString(),
                any(), any());
    }

    @Test
    @DisplayName("발행자가 준 eventId 를 그대로 넘긴다 — 재전달돼도 하나다")
    void eventPassesThroughStableEventId() {
        listener.onEventStarted(List.of(eventStarted("")), null);

        assertThat(capture(3).getValue()).isEqualTo("event:7");
    }

    @Test
    @DisplayName("slug 나 제목이 없으면 건너뛴다")
    void eventSkipsIncompleteMessages() {
        listener.onEventStarted(List.of(
                "{\"title\":\"제목만\"}",
                "{\"slug\":\"슬러그만\"}"), null);

        verify(broadcastService, never()).create(any(), anyString(), any(), anyString(),
                anyString(), any(), any());
    }
}
