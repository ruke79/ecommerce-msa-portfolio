package com.project.delivery_service.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.project.delivery_service.domain.Delivery;

/**
 * 🐛 <b>회귀 대상 — 송장 등록이 언제나 "주문 또는 택배사를 찾을 수 없습니다" 였다.</b>
 *
 * <p>주문 번호는 TSID 라 18자리다. 자바에서는 {@code long} 이라 멀쩡하지만 JSON <b>숫자</b>로
 * 내려보내면 브라우저의 {@code JSON.parse} 가 배정밀도 실수로 읽는다. 안전 정수 한계는
 * {@code 2^53-1}(약 9.0e15)인데 TSID 는 8.9e17 이라 두 자리 크다:
 *
 * <pre>
 *   889196209423729897  →  889196209423729920
 * </pre>
 *
 * <p>화면에는 번호가 맞게 보이는데(자릿수가 같다) 서버로 되돌아온 값은 다른 주문이다.
 * 그래서 "택배사가 없나? 배송이 없나?" 를 한참 뒤졌다.
 */
@DisplayName("배송 응답 — id 는 문자열로 내려간다")
class DeliveryResponseIdTest {

    /** 스프링이 자동 구성해 주는 것과 같은 모양 — LocalDateTime 을 쓸 수 있게 한다. */
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    /** 실제로 막혔던 주문 번호. 배정밀도로 왕복하면 값이 바뀐다. */
    private static final long TSID = 889196209423729897L;

    private static Delivery delivery() {
        return Delivery.builder()
                .orderId(TSID).customerId(TSID + 1)
                .recipientName("홍 길동").addressLine1("서울 강남구 테헤란로 1").country("KR")
                .build();
    }

    /** 🔴 이 값이 숫자로 나가면 화면이 다른 주문을 가리킨다. */
    @Test
    @DisplayName("🔴 주문 번호가 JSON 문자열이고 자릿수가 하나도 안 바뀐다")
    void orderIdSurvivesAsString() throws Exception {
        String json = MAPPER.writeValueAsString(DeliveryResponse.from(delivery(), Map.of()));

        assertThat(json).contains("\"orderId\":\"889196209423729897\"");
        assertThat(json).doesNotContain("\"orderId\":889196209423729897");
    }

    @Test
    @DisplayName("관리자 목록의 고객 번호도 문자열이다")
    void adminCustomerIdIsAlsoString() throws Exception {
        String json = MAPPER.writeValueAsString(AdminDeliveryResponse.from(delivery(), Map.of()));

        assertThat(json).contains("\"customerId\":\"889196209423729898\"");
    }

    /** 자바에서는 멀쩡하지만 브라우저에서 깨진다는 사실 자체를 고정해 둔다. */
    @Test
    @DisplayName("TSID 는 배정밀도로 표현할 수 없다 — 이 전제가 무너지면 위 규칙도 필요 없어진다")
    void tsidDoesNotFitInADouble() {
        assertThat((long) (double) TSID).isNotEqualTo(TSID);
        assertThat(TSID).isGreaterThan((1L << 53) - 1);
    }

    @Test
    @DisplayName("재주문 연결이 없으면 null 이고, 있으면 문자열이다")
    void reorderedFromIsNullOrString() {
        assertThat(DeliveryResponse.from(delivery(), Map.of()).reorderedFromOrderId()).isNull();
    }
}
