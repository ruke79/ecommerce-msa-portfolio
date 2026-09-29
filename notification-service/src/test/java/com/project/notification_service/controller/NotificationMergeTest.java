package com.project.notification_service.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import com.project.notification_service.domain.BroadcastNotification;
import com.project.notification_service.domain.Notification;
import com.project.notification_service.domain.NotificationType;
import com.project.notification_service.dto.NotificationResponse;
import com.project.notification_service.service.BroadcastService;
import com.project.notification_service.service.BroadcastService.Visible;
import com.project.notification_service.service.NotificationService;
import com.project.notification_service.service.VerificationService;

/**
 * 개인 알림과 전체 알림은 <b>따로 저장되고 합쳐서 보인다.</b>
 *
 * <p>사용자에게는 "알림" 하나이고 어디에 저장됐는지는 관심 밖이다. 저장을 나눈 것은 공지
 * 하나에 사용자 수만큼 행을 만들지 않기 위한 우리 사정이다(BroadcastNotification 주석).
 * 그 사정이 화면에 새어 나가면 안 된다 — 목록도 배지도 하나여야 한다.
 */
@DisplayName("알림 목록 합치기")
class NotificationMergeTest {

    private final NotificationService notifications = mock(NotificationService.class);
    private final BroadcastService broadcasts = mock(BroadcastService.class);
    private final NotificationController controller = new NotificationController(
            notifications, mock(VerificationService.class), broadcasts);

    private static Notification personal(String title, LocalDateTime at) {
        Notification n = Notification.builder()
                .recipientId(7L)
                .type(NotificationType.ORDER_PLACED)
                .title(title)
                .content("내용")
                .url("order/1")
                .build();
        // createdAt 은 생성자가 찍는다. 정렬을 검증하려면 시각을 정해야 한다.
        ReflectionTestUtils.setField(n, "createdAt", at);
        return n;
    }

    private static Visible broadcast(String title, LocalDateTime at, boolean read) {
        return new Visible(BroadcastNotification.builder()
                .broadcastId(99L)
                .type(NotificationType.NOTICE_PUBLISHED)
                .title(title)
                .startsAt(at)
                .createdAt(at)
                .build(), read);
    }

    private List<NotificationResponse> list(int size) {
        ResponseEntity<List<NotificationResponse>> response = controller.list(7L, "a@b.c", size);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private static final LocalDateTime NOW = LocalDateTime.now(ZoneOffset.UTC);

    @Test
    @DisplayName("둘을 합쳐서 시간 역순으로 내려준다")
    void mergesAndSorts() {
        when(notifications.recent(anyLong(), anyInt()))
                .thenReturn(List.of(personal("주문", NOW.minusHours(2))));
        when(broadcasts.visibleFor(anyLong(), anyInt()))
                .thenReturn(List.of(broadcast("공지", NOW.minusHours(1), false)));

        assertThat(list(20)).extracting(NotificationResponse::title)
                .containsExactly("공지", "주문");
    }

    /**
     * scope 가 없으면 화면이 어느 엔드포인트로 읽음·삭제를 보낼지 정할 수 없다. 두 테이블이
     * 각자 TSID 를 쓰므로 id 만으로는 구분되지 않는다.
     */
    @Test
    @DisplayName("각 항목에 출처(scope)를 붙인다 — 읽음·삭제 경로가 다르다")
    void tagsTheScope() {
        when(notifications.recent(anyLong(), anyInt()))
                .thenReturn(List.of(personal("주문", NOW.minusHours(2))));
        when(broadcasts.visibleFor(anyLong(), anyInt()))
                .thenReturn(List.of(broadcast("공지", NOW.minusHours(1), false)));

        assertThat(list(20)).extracting(NotificationResponse::scope)
                .containsExactly(NotificationResponse.SCOPE_BROADCAST,
                                 NotificationResponse.SCOPE_PERSONAL);
    }

    /**
     * 합친 뒤 자르지 않으면 size=5 를 요청해도 최대 10개가 내려간다 — 양쪽에서 각각 5개를
     * 받기 때문이다.
     */
    @Test
    @DisplayName("합친 뒤 요청한 개수로 자른다")
    void limitsTheMergedList() {
        when(notifications.recent(anyLong(), anyInt())).thenReturn(List.of(
                personal("1", NOW.minusMinutes(1)),
                personal("2", NOW.minusMinutes(2))));
        when(broadcasts.visibleFor(anyLong(), anyInt())).thenReturn(List.of(
                broadcast("3", NOW.minusMinutes(3), false),
                broadcast("4", NOW.minusMinutes(4), false)));

        assertThat(list(3)).hasSize(3);
    }

    @Test
    @DisplayName("전체 알림의 읽음 여부는 개인별 표시에서 온다")
    void readFlagComesFromThePerUserRow() {
        when(notifications.recent(anyLong(), anyInt())).thenReturn(List.of());
        when(broadcasts.visibleFor(anyLong(), anyInt()))
                .thenReturn(List.of(broadcast("공지", NOW, true)));

        assertThat(list(20)).singleElement()
                .extracting(NotificationResponse::read).isEqualTo(true);
    }

    /**
     * 배지는 사용자가 보는 "안 읽은 알림" 전체다. 개인 알림만 세면 공지를 안 읽었는데도
     * 0 이 되거나(반대로) 목록을 다 읽었는데 숫자가 남는다.
     */
    @Test
    @DisplayName("배지는 개인 알림과 전체 알림을 더한 수다")
    void badgeSumsBothSources() {
        when(notifications.unreadCount(7L)).thenReturn(2L);
        when(broadcasts.unreadCount(7L)).thenReturn(3L);

        ResponseEntity<Map<String, Long>> response = controller.unreadCount(7L);

        assertThat(response.getBody()).containsEntry("count", 5L);
    }

    /**
     * "모두 읽음"은 배지를 0 으로 만드는 버튼이다. 개인 알림만 처리하면 전체 알림이 남아
     * 숫자가 0 이 되지 않고, 사용자는 버튼을 계속 누르게 된다.
     */
    @Test
    @DisplayName("모두 읽음은 전체 알림도 읽음 처리한다")
    void markAllReadCoversBroadcasts() {
        when(notifications.markAllRead(7L)).thenReturn(2);
        when(broadcasts.markAllRead(7L)).thenReturn(3);

        ResponseEntity<Map<String, Integer>> response = controller.markAllRead(7L);

        assertThat(response.getBody()).containsEntry("updated", 5);
    }

    /**
     * 게이트웨이가 User-Id 를 채운다. 없다는 것은 인증이 안 됐다는 뜻이고, 그대로 진행하면
     * 남의 알림을 읽게 된다.
     */
    @Test
    @DisplayName("신원 헤더가 없으면 401 — 조회 자체를 하지 않는다")
    void anonymousIsRejected() {
        assertThat(controller.list(null, null, 20).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(controller.unreadCount(null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(controller.markBroadcastRead(null, 1L).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(controller.dismissBroadcast(null, 1L).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** 없는 공지를 읽음 처리하면 404 다 — 403 이면 "그 id 는 존재한다"를 알려주는 셈이다. */
    @Test
    @DisplayName("없는 전체 알림은 404")
    void unknownBroadcastIsNotFound() {
        when(broadcasts.markRead(anyLong(), anyLong())).thenReturn(false);
        when(broadcasts.dismiss(anyLong(), anyLong())).thenReturn(false);

        assertThat(controller.markBroadcastRead(7L, 9L).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.dismissBroadcast(7L, 9L).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** 전체 알림은 문자로 나가지 않는다 — 사용자 수만큼 문자가 나가는 것은 요금 사고다. */
    @Test
    @DisplayName("전체 알림의 SMS 상태는 항상 '해당 없음'")
    void broadcastsAreNeverSms() {
        when(notifications.recent(anyLong(), anyInt())).thenReturn(List.of());
        when(broadcasts.visibleFor(anyLong(), anyInt()))
                .thenReturn(List.of(broadcast("공지", NOW, false)));

        assertThat(list(20)).singleElement()
                .extracting(NotificationResponse::smsStatus).isEqualTo("NOT_APPLICABLE");
    }

    @Test
    @DisplayName("전체 알림이 하나도 없어도 개인 알림은 그대로 보인다")
    void worksWithoutBroadcasts() {
        when(notifications.recent(anyLong(), anyInt()))
                .thenReturn(List.of(personal("주문", NOW)));
        when(broadcasts.visibleFor(anyLong(), anyInt())).thenReturn(List.of());

        assertThat(list(20)).extracting(NotificationResponse::title).containsExactly("주문");
    }

    @Test
    @DisplayName("메일 주소는 목록에서만 기록한다 — 배지 경로에 조회를 더 붙이지 않는다")
    void emailIsRememberedOnlyOnTheListPath() {
        when(notifications.recent(anyLong(), anyInt())).thenReturn(List.of());
        when(broadcasts.visibleFor(anyLong(), anyInt())).thenReturn(List.of());

        controller.unreadCount(7L);
        org.mockito.Mockito.verify(notifications, org.mockito.Mockito.never())
                .rememberEmail(anyLong(), any());

        list(20);
        org.mockito.Mockito.verify(notifications).rememberEmail(7L, "a@b.c");
    }
}
