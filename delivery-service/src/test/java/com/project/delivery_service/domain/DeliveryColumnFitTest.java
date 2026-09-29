package com.project.delivery_service.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 🐛 <b>회귀 대상 — 나라 이름 한 칸 때문에 결제가 DLT 로 갔다.</b>
 *
 * <p>주소 폼은 나라를 이름("Korea, Republic of")으로 저장하는데 {@code country} 컬럼은
 * {@code varchar(2)} 다. 첫 배송을 만들려는 순간 이렇게 죽었다:
 *
 * <pre>
 *   ERROR: value too long for type character varying(2)
 * </pre>
 *
 * <p>그리고 그 예외는 배송 하나가 아니라 <b>결제 레코드</b>를 재시도 끝에 DLT 로 보냈다.
 * 주문은 PAID 인데 배송은 영영 안 생기고, 화면에는 "배송 준비 중" 만 남았다.
 *
 * <p>여기서 고정하는 것은 하나다: <b>어떤 입력이 와도 컬럼에 들어간다.</b> 값이 이상하면
 * 자르고 경고를 남기되, 배송은 만든다. 잘린 주소는 관리자 화면에 보이고 발송 전에 고칠 수
 * 있지만, 배송이 없으면 아무도 모른다.
 */
@DisplayName("배송 — 어떤 입력도 컬럼에 들어간다")
class DeliveryColumnFitTest {

    private static Delivery of(String country, String postalCode) {
        return Delivery.builder()
                .orderId(1L).customerId(7L)
                .recipientName("홍 길동").recipientPhone("010-1234-5678")
                .postalCode(postalCode).addressLine1("서울 강남구 테헤란로 1")
                .city("서울").state("서울").country(country)
                .build();
    }

    /** 🔴 폼이 저장하는 값 그대로. 이것이 터져서 결제가 DLT 로 갔다. */
    @Test
    @DisplayName("🔴 나라를 이름으로 줘도 두 글자 코드로 들어간다")
    void countryNameBecomesCode() {
        assertThat(of("Korea, Republic of", "06236").getCountry()).isEqualTo("KR");
        assertThat(of("Japan", "100-0001").getCountry()).isEqualTo("JP");
        assertThat(of("United States", "90210").getCountry()).isEqualTo("US");
    }

    @Test
    @DisplayName("이미 코드면 그대로, 없으면 발송국")
    void codesAndDefaults() {
        assertThat(of("KR", "06236").getCountry()).isEqualTo("KR");
        assertThat(of("kr", "06236").getCountry()).isEqualTo("KR");
        assertThat(of(null, "06236").getCountry()).isEqualTo("KR");
    }

    /** 알아보지 못하는 나라라도 배송은 만들어진다 — 예외를 던지면 결제가 멈춘다. */
    @Test
    @DisplayName("모르는 나라 이름이어도 배송은 만들어진다(발송국으로 본다)")
    void unknownCountryStillCreatesDelivery() {
        Delivery d = of("Neverland", "06236");
        assertThat(d.getCountry()).isEqualTo("KR");
        assertThat(d.getCountry()).hasSize(2);
    }

    /** 폼은 우편번호를 30자까지 받는데 컬럼은 12자다 — 같은 종류의 사고가 하나 더 있었다. */
    @Test
    @DisplayName("우편번호가 컬럼보다 길면 잘라서라도 저장한다")
    void longPostalCodeIsTruncatedNotThrown() {
        Delivery d = of("KR", "0123456789ABCDEFGHIJ");
        assertThat(d.getPostalCode()).hasSize(12).isEqualTo("0123456789AB");
    }

    /** 컬럼 폭을 넘는 값이 하나라도 남아 있으면 배송 생성이 죽는다. 전부 확인한다. */
    @Test
    @DisplayName("모든 문자열 칸이 컬럼 폭 안에 들어간다")
    void everyStringFieldFitsItsColumn() {
        String tooLong = "가".repeat(500);
        Delivery d = Delivery.builder()
                .orderId(1L).customerId(7L)
                .recipientName(tooLong).recipientPhone(tooLong)
                .postalCode(tooLong).addressLine1(tooLong).addressLine2(tooLong)
                .city(tooLong).state(tooLong).country(tooLong).deliveryMemo(tooLong)
                .build();

        assertThat(d.getRecipientName()).hasSizeLessThanOrEqualTo(80);
        assertThat(d.getRecipientPhone()).hasSizeLessThanOrEqualTo(20);
        assertThat(d.getPostalCode()).hasSizeLessThanOrEqualTo(12);
        assertThat(d.getAddressLine1()).hasSizeLessThanOrEqualTo(200);
        assertThat(d.getAddressLine2()).hasSizeLessThanOrEqualTo(200);
        assertThat(d.getCity()).hasSizeLessThanOrEqualTo(80);
        assertThat(d.getState()).hasSizeLessThanOrEqualTo(80);
        assertThat(d.getDeliveryMemo()).hasSizeLessThanOrEqualTo(200);
        assertThat(d.getCountry()).hasSize(2);
    }

    /** 주소 변경도 같은 폼에서 오므로 같은 규칙이어야 한다. */
    @Test
    @DisplayName("주소를 바꿀 때도 나라 이름이 코드가 된다")
    void addressChangeNormalizesToo() {
        Delivery d = of("KR", "06236");
        d.changeAddress(7L, "김 영희", "010-0000-0000", "48000", "부산 해운대구 1", null,
                "부산", "부산", "Japan", null, "이사");

        assertThat(d.getCountry()).isEqualTo("JP");
    }
}
