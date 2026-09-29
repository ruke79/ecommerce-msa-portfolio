package com.project.notification_service.sms;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SMS 길이 규칙 — <b>길이는 곧 요금이다</b>.
 *
 * <p>한글·일본어가 하나라도 섞이면 UCS-2 로 인코딩되어 70자가 한 건의 한계이고, 한국 사업자는
 * 이를 넘으면 LMS 로 전환해 요금이 3~4배가 된다. 문구가 조용히 경계를 넘으면 청구서에서야 안다.
 */
@DisplayName("SMS 길이 규칙")
class SmsMessageTest {

    @Test
    @DisplayName("한글이 섞이면 유니코드로 취급한다 — 한계가 160자가 아니라 70자다")
    void 한글은_유니코드() {
        assertThat(SmsMessage.isUnicode("[쇼핑몰] 인증번호 123456")).isTrue();
        assertThat(SmsMessage.isUnicode("[SHOP] code 123456")).isFalse();
    }

    @Test
    @DisplayName("일본어도 유니코드다")
    void 일본어는_유니코드() {
        assertThat(SmsMessage.isUnicode("[ショップ] 認証番号 123456")).isTrue();
    }

    @Test
    @DisplayName("70자를 넘는 한글 문구는 한 건에 들어가지 않는다")
    void 한글_70자_경계() {
        assertThat(SmsMessage.fitsInSingleSms("가".repeat(70))).isTrue();
        assertThat(SmsMessage.fitsInSingleSms("가".repeat(71)))
                .as("여기를 넘으면 LMS 로 전환되어 요금이 3~4배가 된다")
                .isFalse();
    }

    @Test
    @DisplayName("ASCII 만이면 160자까지 들어간다")
    void ascii_160자_경계() {
        assertThat(SmsMessage.fitsInSingleSms("a".repeat(160))).isTrue();
        assertThat(SmsMessage.fitsInSingleSms("a".repeat(161))).isFalse();
    }

    @Test
    @DisplayName("자르면 한 건 안에 들어가고, 잘렸다는 표시가 남는다")
    void 자르기() {
        String truncated = SmsMessage.truncate("가".repeat(200));

        assertThat(SmsMessage.fitsInSingleSms(truncated)).isTrue();
        assertThat(truncated).endsWith("…");
    }

    @Test
    @DisplayName("이모지를 반으로 쪼개지 않는다")
    void 서로게이트_쌍_보호() {
        // substring 으로 자르면 서로게이트 쌍이 깨져 깨진 문자가 문자로 나간다.
        // 코드포인트 단위로 세야 한다.
        String text = "🎁".repeat(100);
        String truncated = SmsMessage.truncate(text);

        assertThat(SmsMessage.fitsInSingleSms(truncated))
                .as("자른 뒤에는 반드시 한 건에 들어가야 한다. 코드포인트로 세면 이모지가 "
                        + "UTF-16 2단위를 차지해 여기서 실패한다 — 실제로 그 버그가 있었다")
                .isTrue();

        // 서로게이트 쌍이 쪼개졌다면 마지막 이모지 자리에 상위 서로게이트만 남는다.
        String beforeEllipsis = truncated.substring(0, truncated.length() - 1);
        assertThat(beforeEllipsis.isEmpty()
                        || !Character.isHighSurrogate(beforeEllipsis.charAt(beforeEllipsis.length() - 1)))
                .as("잘린 자리에 상위 서로게이트만 남으면 깨진 문자가 전송된다")
                .isTrue();
    }

    @Test
    void null_에도_깨지지_않는다() {
        assertThat(SmsMessage.fitsInSingleSms(null)).isTrue();
        assertThat(SmsMessage.truncate(null)).isNull();
    }
}
