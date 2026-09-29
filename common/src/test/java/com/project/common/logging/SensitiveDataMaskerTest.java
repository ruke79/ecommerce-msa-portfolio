package com.project.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 로그 마스킹 규칙.
 *
 * <p>두 방향을 다 고정한다. <b>가려야 할 것을 가린다</b> — 그리고 <b>운영자가 봐야 할 것은
 * 건드리지 않는다.</b> 주문 번호나 추적 ID 를 오탐으로 가리면 장애를 추적할 수 없다. 아래
 * "건드리지 않는다" 의 입력은 이 저장소의 실제 로그 모양에서 가져왔다.
 */
@DisplayName("SensitiveDataMasker")
class SensitiveDataMaskerTest {

    /** 🐛 cart-service 에 실제로 있던 줄이다 — 이메일이 그대로 찍혔다. */
    @Test
    @DisplayName("🔴 이메일은 첫 글자와 도메인만 남는다 — cart·order·user 의 평문 이메일 로그")
    void email() {
        assertThat(SensitiveDataMasker.mask("Cache miss for carts::hong.gildong@example.com, fetching from DB."))
                .isEqualTo("Cache miss for carts::h****@example.com, fetching from DB.");
        assertThat(SensitiveDataMasker.mask("User not found for email: a@b.co"))
                .isEqualTo("User not found for email: a****@b.co");
    }

    @Test
    @DisplayName("이미 가린 이메일(EmailAddresses.mask)은 다시 건드리지 않는다")
    void alreadyMaskedEmail() {
        assertThat(SensitiveDataMasker.mask("to=h***@example.com")).isEqualTo("to=h***@example.com");
    }

    @Test
    @DisplayName("🔴 휴대폰 — 국제 표기(+82·+81)와 국내 표기(010·090) 모두")
    void phones() {
        assertThat(SensitiveDataMasker.mask("phone=+821012345678")).isEqualTo("phone=+82****5678");
        assertThat(SensitiveDataMasker.mask("phone=+819012345678")).isEqualTo("phone=+81****5678");
        assertThat(SensitiveDataMasker.mask("연락처 010-1234-5678")).isEqualTo("연락처 010-****-5678");
        assertThat(SensitiveDataMasker.mask("連絡先 090-1234-5678")).isEqualTo("連絡先 090-****-5678");
    }

    @Test
    @DisplayName("이미 가린 번호(PhoneNumbers.mask)는 다시 건드리지 않는다")
    void alreadyMaskedPhone() {
        assertThat(SensitiveDataMasker.mask("to=+8210****5678")).isEqualTo("to=+8210****5678");
    }

    @Test
    @DisplayName("🔴 토큰 — Bearer 헤더, JWT, key=value·JSON 의 비밀값")
    void tokens() {
        // 헤더 이름이 붙으면 key=value 규칙이 "Bearer" 까지 가린다 — 더 가려질 뿐 새지는 않는다.
        assertThat(SensitiveDataMasker.mask("Authorization: Bearer abcdefghijklmnop"))
                .isEqualTo("Authorization: **** ****")
                .doesNotContain("abcdefghijklmnop");
        assertThat(SensitiveDataMasker.mask("sent Bearer abcdefghijklmnop upstream"))
                .isEqualTo("sent Bearer **** upstream");
        assertThat(SensitiveDataMasker.mask("token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.c2lnbmF0dXJlX2hlcmU"))
                .isEqualTo("token eyJ****.****.****");
        assertThat(SensitiveDataMasker.mask("{\"password\":\"hunter22\",\"user\":\"kim\"}"))
                .isEqualTo("{\"password\":\"****\",\"user\":\"kim\"}");
        assertThat(SensitiveDataMasker.mask("refresh_token=abc.def api_key: xyz"))
                .isEqualTo("refresh_token=**** api_key: ****");
    }

    @Test
    @DisplayName("주민등록번호·카드번호")
    void identityAndCard() {
        assertThat(SensitiveDataMasker.mask("rrn 900101-1234567")).isEqualTo("rrn 900101-1******");
        assertThat(SensitiveDataMasker.mask("card 4111-1111-1111-1111")).isEqualTo("card 4111-1111-11**-****");
        assertThat(SensitiveDataMasker.mask("card 4111111111111111")).isEqualTo("card 4111-1111-11**-****");
    }

    /**
     * 🔴 오탐 금지. 이 저장소의 로그에 실제로 나오는 모양들이다 — 18~19자리 TSID 주문 번호,
     * UUID 추적 ID(사가 trackingId), 금액, 시각, epoch 밀리초, Kafka 주소, 송장번호, traceId.
     * 하나라도 가려지면 장애 때 로그로 주문을 찾을 수 없다.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "배송 상태 반영: orderId=736459283746592837 PAID → PROCESSING (delivery=PENDING)",
            "주문 1234567890123456789 결제 확인",
            "trackingId=3f2a9c1e-0101-4567-8901-0123456789ab",
            "refund amount=59.98 chargedAmount=78000 currency=KRW",
            "occurredAt=2026-09-20T10:00:00 epoch=1758362400000",
            "bootstrap=10.0.0.5:9092 partition=3 offset=128734",
            "송장 등록: carrier=SIMULATED trackingNumber=SIM-1",
            "[4bf92f3577b34da6a3ce929d0e0e4736,00f067aa0ba902b7] handled",
            "쿠폰 사용: couponId=17 discount=15%",
    })
    @DisplayName("🔴 주문 번호·UUID·금액·시각·주소·traceId 는 건드리지 않는다")
    void leavesOperationalValuesAlone(String line) {
        assertThat(SensitiveDataMasker.mask(line)).isEqualTo(line);
        assertThat(SensitiveDataMasker.containsSensitiveData(line)).isFalse();
    }

    /** 여섯 자리 숫자를 인증번호로 알아볼 수 없다 — 그래서 인증번호는 애초에 로그로 넘기지 않는다. */
    @Test
    @DisplayName("인증번호는 여기서 막지 못한다 — 로그로 넘기지 않는 것이 규칙이다(LoggingSmsSender)")
    void cannotRecognizeVerificationCodes() {
        assertThat(SensitiveDataMasker.mask("[쇼핑몰] 인증번호 482913")).contains("482913");
    }

    @Test
    @DisplayName("긴 입력은 잘라서 검사한다 — 스택트레이스의 원인이 잘리지 않을 만큼은 길다")
    void longInputIsTruncated() {
        String longLine = "x".repeat(SensitiveDataMasker.MAX_SCAN_LENGTH + 10);
        String masked = SensitiveDataMasker.mask(longLine);
        assertThat(masked).endsWith(SensitiveDataMasker.TRUNCATED_SUFFIX);
        assertThat(masked).hasSize(SensitiveDataMasker.MAX_SCAN_LENGTH + SensitiveDataMasker.TRUNCATED_SUFFIX.length());
        assertThat(SensitiveDataMasker.MAX_SCAN_LENGTH).isGreaterThanOrEqualTo(64 * 1024);
    }

    @Test
    @DisplayName("비었거나 null 이면 그대로")
    void emptyIsUntouched() {
        assertThat(SensitiveDataMasker.mask(null)).isNull();
        assertThat(SensitiveDataMasker.mask("")).isEmpty();
        assertThat(SensitiveDataMasker.containsSensitiveData(null)).isFalse();
    }
}
