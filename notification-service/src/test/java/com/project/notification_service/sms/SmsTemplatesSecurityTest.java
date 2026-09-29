package com.project.notification_service.sms;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.notification_service.domain.NotificationType;
import com.project.notification_service.sms.PhoneNumbers.Region;

/**
 * 보안 문자 — 사건(새 기기 · 로그인 해제, user-service docs/68)을 알림 제목으로 구분한다.
 *
 * <p>🐛 예전: 종류로 고정이라 어떤 보안 사건이든 "새로운 기기에서 로그인되었습니다" 문자였다.
 */
@DisplayName("SMS 문구 — 보안 알림")
class SmsTemplatesSecurityTest {

    @Test
    @DisplayName("🔴 로그인 해제는 로그인 해제로 — 새 기기 문구가 아니다")
    void revokedSaysRevoked() {
        String ko = SmsTemplates.forNotification(Region.KR, NotificationType.SECURITY_ALERT,
                Map.of("subject", "보안을 위해 로그인이 해제되었습니다"));
        assertThat(ko).contains("보안을 위해 로그인이 해제되었습니다").contains("비밀번호").doesNotContain("새로운 기기");

        // 일본 번호 — 한국어 제목을 카탈로그로 바꿔 넣는다(docs/60 §9)
        String ja = SmsTemplates.forNotification(Region.JP, NotificationType.SECURITY_ALERT,
                Map.of("subject", "보안을 위해 로그인이 해제되었습니다"));
        assertThat(ja).contains("ログインを解除しました").contains("パスワード").doesNotContain("로그인");
    }

    @Test
    @DisplayName("새 기기 로그인은 예전과 같은 뜻 — 제목이 없어도 할 일은 말한다")
    void deviceStillReadsTheSame() {
        assertThat(SmsTemplates.forNotification(Region.KR, NotificationType.SECURITY_ALERT,
                Map.of("subject", "새로운 기기에서 로그인되었습니다")))
                .contains("새로운 기기에서 로그인되었습니다. 본인이 아니면 비밀번호를 변경하세요.");
        assertThat(SmsTemplates.forNotification(Region.KR, NotificationType.SECURITY_ALERT, Map.of()))
                .contains("새로운 기기에서 로그인되었습니다. 본인이 아니면 비밀번호를 변경하세요.");
        assertThat(SmsTemplates.forNotification(Region.JP, NotificationType.SECURITY_ALERT, Map.of()))
                .contains("心当たりがない場合はパスワードを変更してください");
    }
}
