package com.project.notification_service.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * "이 알림을 메일로 보낼 것인가" 정책.
 *
 * <p>두 가지 실수를 막는 것이 목적이다.
 *
 * <ul>
 *   <li><b>보내면 안 되는 것을 보낸다.</b> 채팅 메시지마다 메일을 보내면 활발한 대화 하나가
 *       메일함을 수십 통으로 채운다. 사용자에게는 스팸이고, 수신 서버가 우리를 스팸 발신자로
 *       학습하므로 정상 메일까지 스팸함으로 간다.</li>
 *   <li><b>보내야 하는 것을 막는다.</b> 사용자가 방금 요청한 비밀번호 재설정 메일이 수신 거부
 *       설정으로 차단되면, 사용자는 스스로 계정에 못 들어간다.</li>
 * </ul>
 */
@DisplayName("메일 채널 정책")
class MailChannelPolicyTest {

    @Test
    @DisplayName("🐛 채팅·친구접속은 메일로 보내지 않는다 — 대화 하나가 메일함을 채운다")
    void 채팅은_메일_대상_아님() {
        assertThat(MailKind.forNotification(NotificationType.CHAT_MESSAGE)).isEmpty();
        assertThat(MailKind.forNotification(NotificationType.FRIEND_ONLINE)).isEmpty();
    }

    @Test
    @DisplayName("주문·결제·배송·쿠폰·보안은 메일로 보낸다")
    void 메일_대상() {
        assertThat(MailKind.forNotification(NotificationType.ORDER_PLACED))
                .contains(MailKind.ORDER_PLACED);
        assertThat(MailKind.forNotification(NotificationType.PAYMENT_COMPLETED))
                .contains(MailKind.PAYMENT_COMPLETED);
        assertThat(MailKind.forNotification(NotificationType.PAYMENT_FAILED))
                .contains(MailKind.PAYMENT_FAILED);
        assertThat(MailKind.forNotification(NotificationType.SHIPPING_UPDATED))
                .contains(MailKind.SHIPPING_UPDATED);
        assertThat(MailKind.forNotification(NotificationType.COUPON_ISSUED))
                .contains(MailKind.COUPON_ISSUED);
        assertThat(MailKind.forNotification(NotificationType.SECURITY_ALERT))
                .contains(MailKind.SECURITY_ALERT);
    }

    @Test
    @DisplayName("🔒 가입 확인·비밀번호 재설정은 수신 거부로 막을 수 없다")
    void 트랜잭션_메일() {
        // 본인이 방금 요청한 메일이다. 이걸 수신 거부로 막으면 계정에 못 들어간다.
        assertThat(MailKind.REGISTRATION_CONFIRM.isTransactional()).isTrue();
        assertThat(MailKind.PASSWORD_RESET.isTransactional()).isTrue();
        assertThat(MailKind.LOGIN_CHALLENGE.isTransactional()).isTrue();

        // 알림성 메일은 끌 수 있어야 한다.
        assertThat(MailKind.ORDER_PLACED.isTransactional()).isFalse();
        assertThat(MailKind.SECURITY_ALERT.isTransactional()).isFalse();
    }

    @Test
    @DisplayName("주소가 없으면 보내지 않는다")
    void 주소_없으면_안_보냄() {
        NotificationPreference p = new NotificationPreference(1L);
        assertThat(p.shouldSendEmail(NotificationType.ORDER_PLACED)).isFalse();
    }

    @Test
    @DisplayName("주소가 있고 켜져 있으면 보낸다 — SMS 와 달리 종류별 기본값 선별이 없다")
    void 기본적으로_보냄() {
        NotificationPreference p = new NotificationPreference(1L);
        p.rememberEmail("alice@example.com");

        // SMS 는 건당 과금이라 SHIPPING_UPDATED 가 기본 꺼짐이지만, 메일은 비용이 없으므로
        // 그런 선별이 필요 없다.
        assertThat(p.shouldSendEmail(NotificationType.SHIPPING_UPDATED)).isTrue();
        assertThat(p.shouldSendSms(NotificationType.SHIPPING_UPDATED)).isFalse();
    }

    @Test
    @DisplayName("수신 거부를 켜면 알림 메일이 멈춘다")
    void 수신_거부() {
        NotificationPreference p = new NotificationPreference(1L);
        p.rememberEmail("alice@example.com");
        p.setEmailEnabled(false);

        assertThat(p.shouldSendEmail(NotificationType.ORDER_PLACED)).isFalse();
    }

    @Test
    @DisplayName("🔒 보안 알림은 수신 거부를 무시한다 — 공격자가 먼저 알림을 꺼 두면 안 된다")
    void 보안_알림은_예외() {
        NotificationPreference p = new NotificationPreference(1L);
        p.rememberEmail("alice@example.com");
        p.setEmailEnabled(false);

        assertThat(p.shouldSendEmail(NotificationType.SECURITY_ALERT)).isTrue();
    }

    @Test
    @DisplayName("메일 대상이 아닌 종류는 수신 거부 여부와 무관하게 안 나간다")
    void 채팅은_켜도_안_나감() {
        NotificationPreference p = new NotificationPreference(1L);
        p.rememberEmail("alice@example.com");

        assertThat(p.shouldSendEmail(NotificationType.CHAT_MESSAGE)).isFalse();
    }

    @Test
    @DisplayName("같은 주소를 다시 기록해도 쓰지 않는다 — 화면마다 불리는 경로다")
    void 같은_주소는_무시() {
        NotificationPreference p = new NotificationPreference(1L);

        assertThat(p.rememberEmail("alice@example.com")).isTrue();
        assertThat(p.rememberEmail("alice@example.com")).isFalse();
        assertThat(p.rememberEmail(null)).isFalse();
        assertThat(p.rememberEmail("  ")).isFalse();

        assertThat(p.rememberEmail("bob@example.com")).isTrue();
    }
}
