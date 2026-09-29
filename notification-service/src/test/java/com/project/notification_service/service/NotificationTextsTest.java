package com.project.notification_service.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.common.i18n.ClientText;
import com.project.notification_service.dto.NotificationResponse;

/**
 * 🔴 알림 제목·본문은 모두 일본어가 있다 (docs/60 §9).
 *
 * <p>알림은 서버가 만든 한국어 문구로 저장되고, 목록을 내줄 때 요청 언어로 바꾼다. 새 문구를 NotificationTexts 에 더하고
 * 카탈로그를 빠뜨리면 일본어 화면에 한국어가 남는다 — 한국어 화면에서는 멀쩡해 보인다. 모양마다 예시 값을 넣어 본다.
 */
@DisplayName("알림 문구 — 일본어")
class NotificationTextsTest {

    private static final Locale JA = Locale.JAPANESE;

    @Test
    @DisplayName("🔴 모든 모양이 일본어로 바뀐다 — 한글이 남지 않는다(바뀌는 자리는 영문 예시 값)")
    void everyFormatHasJapanese() {
        for (String format : NotificationTexts.FORMATS) {
            String sample = format.replace("{}", "X1");
            String ja = ClientText.localize(sample, JA);
            assertThat(ja).as("번역이 없는 알림 문구: %s", format).doesNotContainPattern("[\\uac00-\\ud7a3]");
        }
    }

    @Test
    @DisplayName("바뀌는 자리의 값은 그대로 — 주문 번호·쿠폰 이름·기기(알 수 없는 브라우저는 번역)")
    void valuesAreKept() {
        assertThat(ClientText.localize(NotificationTexts.paymentCompleted("20260925-001"), JA))
                .isEqualTo("ご注文20260925-001の決済が完了しました。");
        assertThat(ClientText.localize(NotificationTexts.deliveryBody("DELIVERED", "77"), JA))
                .isEqualTo("ご注文77の商品が届きました。");
        assertThat(ClientText.localize(NotificationTexts.couponBody("WELCOME10", "2026-10-31T00:00"), JA))
                .isEqualTo("クーポン「WELCOME10」が発行されました。2026-10-31までご利用いただけます。");
        assertThat(ClientText.localize(NotificationTexts.securityBody("알 수 없는 브라우저 · Windows", "203.0.113.xxx"), JA))
                .startsWith("不明なブラウザ · Windows · 203.0.113.xxx");
    }

    @Test
    @DisplayName("한국어 요청은 그대로, 목록 응답은 요청 밖(테스트)에서 원문 그대로")
    void koreanUntouched() {
        String ko = NotificationTexts.orderPlaced("5");
        assertThat(ClientText.localize(ko, Locale.KOREAN)).isEqualTo(ko);
        NotificationResponse r = new NotificationResponse(1L, "ORDER_PLACED", NotificationTexts.ORDER_PLACED_TITLE, ko,
                null, false, "NOT_APPLICABLE", null, NotificationResponse.SCOPE_PERSONAL);
        assertThat(r.localized().content()).isEqualTo(ko);
    }
}
