package com.project.notification_service.dto;

import com.project.notification_service.domain.NotificationPreference;
import com.project.notification_service.mail.EmailAddresses;
import com.project.notification_service.sms.PhoneNumbers;

/**
 * 알림 설정.
 *
 * <p>번호와 메일 주소는 <b>마스킹해서</b> 내려준다. 설정 화면은 "어디로 알림이 가는지" 확인만
 * 되면 충분하고, 전체 값을 응답에 실으면 XSS 나 로그 유출 시 그대로 새어 나간다.
 *
 * <p>[JA] 通知設定。
 * 番号とメールアドレスは<b>マスキングして</b>返す。設定画面は「どこに通知が行くか」を確認できれば十分で、
 * 値全体をレスポンスに載せると XSS やログ流出時にそのまま漏れる。
 */
public record PreferenceResponse(
        String phone,
        String region,
        boolean phoneVerified,
        boolean smsEnabled,
        String email,
        boolean emailEnabled) {

    public static PreferenceResponse from(NotificationPreference p) {
        return new PreferenceResponse(
                p.getPhoneE164() == null ? null : PhoneNumbers.mask(p.getPhoneE164()),
                p.getRegion(),
                p.isPhoneVerified(),
                p.isSmsEnabled(),
                p.getEmail() == null ? null : EmailAddresses.mask(p.getEmail()),
                p.isEmailEnabled());
    }
}
