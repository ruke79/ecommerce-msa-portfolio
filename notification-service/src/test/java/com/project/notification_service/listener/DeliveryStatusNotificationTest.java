package com.project.notification_service.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.notification_service.domain.NotificationType;
import com.project.notification_service.service.NotificationService;

/**
 * 🐛 회귀 대상: <b>배송 알림이 만들어지는 경로가 없었다.</b>
 *
 * <p>delivery-service 의 {@code DeliveryStatusPublisher} 는 {@code delivery-status} 토픽으로
 * 상태 변경을 발행하고 있었는데, <b>그 토픽을 듣는 코드가 코드베이스 전체에 없었다.</b>
 * 이벤트가 카프카로 나갔다가 그대로 사라졌다.
 *
 * <p>{@link NotificationType#SHIPPING_UPDATED} 는 메일 제목·SMS 문구·수신 설정 정책·테스트가
 * 전부 갖춰져 있었다 — 만드는 쪽만 없었다.
 */
class DeliveryStatusNotificationTest {

    private final NotificationService notificationService = mock(NotificationService.class);
    private final NotificationEventListener listener =
            new NotificationEventListener(notificationService, new ObjectMapper(), com.project.common.outbox.OutboxTraceContext.NOOP);

    private static String event(String status, boolean worthNotifying) {
        return """
            {
              "deliveryId": 5,
              "orderId": 1234,
              "customerId": 7,
              "status": "%s",
              "stage": "IN_TRANSIT",
              "worthNotifying": %s,
              "occurredAt": "2026-09-15T10:00:00",
              "eventId": "delivery:1234:%s"
            }
            """.formatted(status, worthNotifying, status);
    }

    private ArgumentCaptor<String> captureTitle() {
        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        verify(notificationService).create(anyLong(), any(), title.capture(),
                anyString(), anyString(), anyString(), any());
        return title;
    }

    /** 받는 사람이 가장 먼저 기다리는 소식이다. 대부분의 쇼핑몰이 보낸다. */
    @Test
    @DisplayName("송장이 등록되면 '발송되었습니다' 를 알린다")
    void shipped() {
        listener.onDeliveryStatus(List.of(event("INFO_RECEIVED", true)), null);

        assertThat(captureTitle().getValue()).isEqualTo("상품이 발송되었습니다");
    }

    @Test
    @DisplayName("배달 출발이면 알림을 만든다")
    void outForDelivery() {
        listener.onDeliveryStatus(List.of(event("OUT_FOR_DELIVERY", true)), null);

        assertThat(captureTitle().getValue()).isEqualTo("오늘 배송될 예정입니다");
    }

    @Test
    @DisplayName("배달 완료도 알린다")
    void delivered() {
        listener.onDeliveryStatus(List.of(event("DELIVERED", true)), null);

        assertThat(captureTitle().getValue()).isEqualTo("배송이 완료되었습니다");
    }

    /**
     * 한 건의 배송은 접수 → 집화 → 간선 → 배달출발 → 배달완료로 대여섯 번 바뀐다. 전부
     * 알리면 소음이다. 무엇이 알릴 만한지는 <b>배송 도메인이 계산해서</b> 보내 준다
     * ({@code DeliveryStatus.isWorthNotifying()}) — 여기서는 그 플래그만 믿는다. 그래서
     * 발송(INFO_RECEIVED)을 알림 대상에 더할 때도 이쪽은 고칠 것이 없었다.
     */
    @Test
    @DisplayName("집화·간선 같은 중간 단계는 알리지 않는다 — 알아도 할 일이 없다")
    void skipsIntermediateStages() {
        listener.onDeliveryStatus(List.of(
                event("PICKED_UP", false),
                event("IN_TRANSIT", false),
                event("CUSTOMS_CLEARED", false)), null);

        verify(notificationService, never()).create(
                anyLong(), any(), anyString(), anyString(), anyString(), anyString(), any());
    }

    /**
     * 플래그가 없는 이벤트는 알리지 않는다. 옛 형식의 이벤트가 재전달됐을 때 전부 문자로
     * 나가는 것보다, 안 나가는 쪽이 낫다.
     */
    @Test
    @DisplayName("worthNotifying 이 아예 없으면 알리지 않는다")
    void missingFlagMeansNoNotification() {
        listener.onDeliveryStatus(List.of(
                "{\"orderId\":1,\"customerId\":7,\"status\":\"DELIVERED\"}"), null);

        verify(notificationService, never()).create(
                anyLong(), any(), anyString(), anyString(), anyString(), anyString(), any());
    }

    /**
     * 카프카는 at-least-once 다. 같은 상태 전이가 재전달되면 문자가 한 통 더 나간다 —
     * 발행자가 만들어 준 안정적인 키를 그대로 넘겨야 {@code sourceEventId} UNIQUE 가 막는다.
     */
    @Test
    @DisplayName("발행자가 준 eventId 를 그대로 넘긴다 — 재전달돼도 알림은 하나다")
    void passesThroughStableEventId() {
        listener.onDeliveryStatus(List.of(event("DELIVERED", true)), null);

        ArgumentCaptor<String> eventId = ArgumentCaptor.forClass(String.class);
        verify(notificationService).create(anyLong(), any(), anyString(), anyString(),
                anyString(), eventId.capture(), any());

        assertThat(eventId.getValue()).isEqualTo("delivery:1234:DELIVERED");
    }

    @Test
    @DisplayName("SHIPPING_UPDATED 종류로, 주문 상세로 가는 링크와 함께 만든다")
    void usesShippingTypeAndOrderLink() {
        listener.onDeliveryStatus(List.of(event("DELIVERED", true)), null);

        ArgumentCaptor<NotificationType> type = ArgumentCaptor.forClass(NotificationType.class);
        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(notificationService).create(anyLong(), type.capture(), anyString(),
                anyString(), url.capture(), anyString(), any());

        assertThat(type.getValue()).isEqualTo(NotificationType.SHIPPING_UPDATED);
        assertThat(url.getValue()).isEqualTo(NotificationType.SHIPPING_UPDATED.path() + "1234");
    }

    /**
     * 사용자가 <b>직접 조치해야</b> 풀리는 상태다. "상태가 변경되었습니다" 로 뭉뚱그리면
     * 왜 며칠째 멈춰 있는지 알 수 없다.
     */
    @Test
    @DisplayName("통관 보류는 무엇을 해야 하는지 알려 준다")
    void customsHoldExplainsWhatToDo() {
        listener.onDeliveryStatus(List.of(event("CUSTOMS_HOLD", true)), null);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notificationService).create(anyLong(), any(), anyString(), body.capture(),
                anyString(), anyString(), any());

        assertThat(body.getValue()).contains("개인통관고유부호");
    }

    /**
     * 내부 상태 이름을 그대로 보여주면 안 된다 — 사용자에게 "OUT_FOR_DELIVERY" 는 아무
     * 의미가 없다. 그리고 새 상태가 추가돼도 알림이 깨지면 안 된다.
     */
    @Test
    @DisplayName("모르는 상태여도 내부 이름을 노출하지 않고 넘어간다")
    void unknownStatusFallsBackGracefully() {
        listener.onDeliveryStatus(List.of(event("SOME_NEW_STATUS", true)), null);

        ArgumentCaptor<String> title = captureTitle();
        assertThat(title.getValue()).isEqualTo("배송 상태가 변경되었습니다");
        assertThat(title.getValue()).doesNotContain("SOME_NEW_STATUS");
    }

    @Test
    @DisplayName("customerId 가 없으면 건너뛴다 — 알림 하나 때문에 컨슈머가 멈추면 안 된다")
    void missingRecipientIsSkipped() {
        listener.onDeliveryStatus(List.of(
                "{\"orderId\":1,\"status\":\"DELIVERED\",\"worthNotifying\":true}"), null);

        verify(notificationService, never()).create(
                anyLong(), any(), anyString(), anyString(), anyString(), anyString(), any());
    }

    // ── 쿠폰 발급 ───────────────────────────────────────────────────────────

    /**
     * 🐛 회귀 대상: <b>쿠폰 발급 알림도 만드는 쪽이 없었다.</b>
     *
     * {@link NotificationType#COUPON_ISSUED} 는 메일 제목·SMS 문구·메일 종류 매핑·수신
     * 설정 정책이 전부 갖춰져 있었는데 발행자가 없었다. 가입하면 쿠폰이 발급되지만
     * 사용자는 결제 화면에 가서야 그 사실을 알았다.
     */
    private static String couponEvent(String extra) {
        return """
            {
              "couponId": 42,
              "customerId": 7,
              "name": "웰컴 10%% 할인 쿠폰",
              "discount": 10,
              "endDate": "2026-10-16T12:00:00",
              "eventId": "coupon:42"
              %s
            }
            """.formatted(extra);
    }

    @Test
    @DisplayName("쿠폰이 발급되면 알림을 만든다")
    void couponIssued() {
        listener.onCouponIssued(List.of(couponEvent("")), null);

        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notificationService).create(anyLong(), any(), title.capture(), body.capture(),
                anyString(), anyString(), any());

        assertThat(title.getValue()).isEqualTo("10% 쿠폰이 발급되었습니다");
        assertThat(body.getValue()).contains("웰컴 10% 할인 쿠폰");
        // 언제까지 쓸 수 있는지가 이 알림의 핵심이다 — 없으면 받아 두고 잊는다.
        assertThat(body.getValue()).contains("2026-10-16");
    }

    @Test
    @DisplayName("COUPON_ISSUED 종류로 만든다")
    void couponUsesItsOwnType() {
        listener.onCouponIssued(List.of(couponEvent("")), null);

        ArgumentCaptor<NotificationType> type = ArgumentCaptor.forClass(NotificationType.class);
        verify(notificationService).create(anyLong(), type.capture(), anyString(),
                anyString(), anyString(), anyString(), any());

        assertThat(type.getValue()).isEqualTo(NotificationType.COUPON_ISSUED);
    }

    @Test
    @DisplayName("발행자가 준 eventId 를 그대로 넘긴다 — 재전달돼도 하나다")
    void couponPassesThroughEventId() {
        listener.onCouponIssued(List.of(couponEvent("")), null);

        ArgumentCaptor<String> eventId = ArgumentCaptor.forClass(String.class);
        verify(notificationService).create(anyLong(), any(), anyString(), anyString(),
                anyString(), eventId.capture(), any());

        assertThat(eventId.getValue()).isEqualTo("coupon:42");
    }

    @Test
    @DisplayName("이름이나 받는 사람이 없으면 건너뛴다 — 컨슈머가 멈추면 안 된다")
    void couponSkipsIncompleteEvents() {
        listener.onCouponIssued(List.of(
                "{\"customerId\":7,\"eventId\":\"coupon:1\"}",        // 이름 없음
                "{\"name\":\"쿠폰\",\"eventId\":\"coupon:2\"}"), null);   // 받는 사람 없음

        verify(notificationService, never()).create(
                anyLong(), any(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("만료일이 없어도 알림은 만든다")
    void couponWithoutExpiry() {
        listener.onCouponIssued(List.of(
                "{\"couponId\":1,\"customerId\":7,\"name\":\"무기한 쿠폰\",\"eventId\":\"coupon:1\"}"), null);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notificationService).create(anyLong(), any(), anyString(), body.capture(),
                anyString(), anyString(), any());

        assertThat(body.getValue()).contains("무기한 쿠폰");
    }

    // ── 보안 알림 ───────────────────────────────────────────────────────────
    //
    // 🐛 회귀 대상: 이 알림도 만드는 쪽이 없었다. SECURITY_ALERT 는 메일 제목·SMS 문구·
    // 메일 종류 매핑은 물론이고, 수신 설정에 "보안 알림은 사용자가 꺼도 보낸다"는 예외까지
    // 들어가 있었다(NotificationPreference.shouldSendSms). 전부 갖춰 놓고 발행하는 쪽만
    // 없어서, 계정이 털려도 사용자는 알 방법이 없었다.

    private static String securityEvent(String extra) {
        return """
            {
              "customerId": 7,
              "email": "tester@example.com",
              "deviceLabel": "Chrome · Windows",
              "maskedIp": "203.0.113.xxx",
              "occurredAt": "2026-09-16T10:00:00",
              "eventId": "security:device:42"
              %s
            }
            """.formatted(extra);
    }

    @Test
    @DisplayName("새 기기 로그인이면 보안 알림을 만든다")
    void securityAlert() {
        listener.onSecurityAlert(List.of(securityEvent("")), null);

        assertThat(captureTitle().getValue()).isEqualTo("새로운 기기에서 로그인되었습니다");
    }

    @Test
    @DisplayName("SECURITY_ALERT 종류로 만든다 — 이 종류만 수신 설정을 무시한다")
    void securityUsesItsOwnType() {
        listener.onSecurityAlert(List.of(securityEvent("")), null);

        ArgumentCaptor<NotificationType> type = ArgumentCaptor.forClass(NotificationType.class);
        verify(notificationService).create(anyLong(), type.capture(), anyString(),
                anyString(), anyString(), anyString(), any());

        assertThat(type.getValue()).isEqualTo(NotificationType.SECURITY_ALERT);
    }

    /**
     * 사용자가 <b>"나인가 아닌가"를 판단할 수 있어야</b> 쓸모가 있다. 기기와 대략적인
     * 위치가 그 재료다.
     */
    @Test
    @DisplayName("기기와 위치를 본문에 담는다 — 본인인지 판단할 재료다")
    void securityBodyCarriesTheEvidence() {
        listener.onSecurityAlert(List.of(securityEvent("")), null);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notificationService).create(anyLong(), any(), anyString(), body.capture(),
                anyString(), anyString(), any());

        assertThat(body.getValue()).contains("Chrome · Windows").contains("203.0.113.xxx");
        assertThat(body.getValue()).contains("비밀번호");
    }

    /**
     * 이 서비스에는 사용자 테이블이 없다. 목록 조회 경로에서 주소를 기억해 두지만,
     * 보안 알림은 화면을 한 번도 열지 않은 계정에도 나가야 한다 — 그 경우 기억된 주소가
     * 없어 메일이 나가지 못한다.
     */
    @Test
    @DisplayName("이벤트의 이메일을 먼저 기억시킨다 — 화면을 연 적 없는 계정에도 메일이 나가야 한다")
    void remembersTheEmailBeforeCreating() {
        listener.onSecurityAlert(List.of(securityEvent("")), null);

        verify(notificationService).rememberEmail(7L, "tester@example.com");
    }

    @Test
    @DisplayName("기기 정보가 없어도 무엇을 해야 하는지는 말해 준다")
    void securityBodyAlwaysSaysWhatToDo() {
        listener.onSecurityAlert(List.of("{\"customerId\":7,\"eventId\":\"security:device:1\"}"), null);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notificationService).create(anyLong(), any(), anyString(), body.capture(),
                anyString(), anyString(), any());

        assertThat(body.getValue()).contains("비밀번호");
    }

    @Test
    @DisplayName("발행자가 준 eventId 를 그대로 넘긴다 — 재전달돼도 하나다")
    void securityPassesThroughEventId() {
        listener.onSecurityAlert(List.of(securityEvent("")), null);

        ArgumentCaptor<String> eventId = ArgumentCaptor.forClass(String.class);
        verify(notificationService).create(anyLong(), any(), anyString(), anyString(),
                anyString(), eventId.capture(), any());

        assertThat(eventId.getValue()).isEqualTo("security:device:42");
    }

    @Test
    @DisplayName("받는 사람이 없으면 건너뛴다 — 컨슈머가 멈추면 안 된다")
    void securitySkipsWithoutARecipient() {
        listener.onSecurityAlert(List.of("{\"deviceLabel\":\"Chrome\"}"), null);

        verify(notificationService, never()).create(
                anyLong(), any(), anyString(), anyString(), anyString(), anyString(), any());
    }

    // ── 로그인 해제(리프레시 토큰 재사용, user-service docs/68) ─────────────────
    //
    // 같은 security-alert · 같은 SECURITY_ALERT(수신 설정 무시) — 사건만 다르다. "새 기기 로그인" 문구로 나가면
    // 사용자는 "내가 로그인했으니 괜찮다" 로 넘긴다. 로그인이 끊겼다는 사실이 정확히 전달돼야 한다.

    private static final String REUSE_EVENT = """
            {"alert":"refresh-reuse","customerId":7,"email":"tester@example.com",
             "occurredAt":"2026-09-28T10:00:00","eventId":"security:refresh-reuse:0123abcd"}
            """;

    @Test
    @DisplayName("🔴 토큰 재사용이면 '로그인 해제' 문구로 만든다 — 새 기기 문구가 아니다")
    void refreshReuseSaysTheSessionWasRevoked() {
        listener.onSecurityAlert(List.of(REUSE_EVENT), null);

        ArgumentCaptor<NotificationType> type = ArgumentCaptor.forClass(NotificationType.class);
        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> eventId = ArgumentCaptor.forClass(String.class);
        verify(notificationService).create(anyLong(), type.capture(), title.capture(), body.capture(),
                anyString(), eventId.capture(), any());

        assertThat(type.getValue()).isEqualTo(NotificationType.SECURITY_ALERT);
        assertThat(title.getValue()).isEqualTo("보안을 위해 로그인이 해제되었습니다");
        assertThat(body.getValue()).contains("다시 로그인").contains("비밀번호")
                .doesNotContain("토큰").doesNotContain("새로운 기기");
        assertThat(eventId.getValue()).isEqualTo("security:refresh-reuse:0123abcd");
        verify(notificationService).rememberEmail(7L, "tester@example.com");
    }

    @Test
    @DisplayName("🔴 패스키 등록 · 삭제는 그 사건의 문구로 — 새 기기 문구로 떨어지지 않는다(user-service docs/68 §20)")
    void passkeyAlertsHaveTheirOwnText() {
        listener.onSecurityAlert(List.of(securityEvent(",\"alert\":\"passkey-added\"")), null);
        assertThat(captureTitle().getValue()).isEqualTo("새 패스키가 등록되었습니다");

        org.mockito.Mockito.clearInvocations(notificationService);
        listener.onSecurityAlert(List.of(securityEvent(",\"alert\":\"passkey-removed\"")), null);
        assertThat(captureTitle().getValue()).isEqualTo("패스키가 삭제되었습니다");
    }

    @Test
    @DisplayName("alert 가 없는 예전 이벤트는 그대로 새 기기 로그인이다")
    void missingAlertMeansNewDevice() {
        listener.onSecurityAlert(List.of(securityEvent(",\"alert\":\"something-else\"")), null);

        assertThat(captureTitle().getValue()).isEqualTo("새로운 기기에서 로그인되었습니다");
    }

    @Test
    @DisplayName("한 배치에 섞여 와도 알릴 것만 알린다")
    void mixedBatch() {
        listener.onDeliveryStatus(List.of(
                event("IN_TRANSIT", false),
                event("OUT_FOR_DELIVERY", true),
                event("PICKED_UP", false)), null);

        verify(notificationService).create(anyLong(), any(), anyString(), anyString(),
                anyString(), anyString(), any());
    }
}
