package com.project.order_service.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.order_service.exception.OrderRestExceptionHandler;
import com.project.order_service.model.Order;
import com.project.order_service.service.OrderService;

/**
 * 주문 생성의 배송지 검증 — 손님이 적은 그대로 쓰는 유일한 값이라 서버가 최소한을 본다.
 *
 * <p>예전에는 {@code OrderAddressDto} 에 검증이 없고 컨트롤러에 {@code @Valid} 도 없어, 기본 주소 · 도시 · 시/도 · 우편번호가 없으면
 * 서비스 · DB 까지 내려가 NOT NULL 위반(500)이 났고 빈 값 · 수취인 없는 주소는 그대로 저장됐다. 이제 서비스에 닿기 전에 400.
 *
 * <p>[JA] 注文作成の配送先の検証。以前は検証も {@code @Valid} もなく、必須の値がないとサービス・DB まで降りて NOT NULL 違反(500)になり、
 * 空の値や受取人のない住所はそのまま保存されていた。今はサービスに届く前に 400。
 */
@DisplayName("주문 생성 — 배송지 검증")
class OrderCreateAddressValidationTest {

    private final ObjectMapper json = new ObjectMapper();
    private OrderService orderService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        OrderController controller = new OrderController();
        orderService = mock(OrderService.class);
        ReflectionTestUtils.setField(controller, "orderService", orderService);
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new OrderRestExceptionHandler()).build();

        Order created = mock(Order.class);
        when(created.getOrderId()).thenReturn(42L);
        when(orderService.createOrder(any(), anyLong(), any())).thenReturn(created);
    }

    /** 화면(AddShipping.jsx)이 만드는 모양의 정상 주소. */
    private static Map<String, Object> address() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("id", "1");
        a.put("firstname", "길동");
        a.put("lastname", "홍");
        a.put("address1", "서울특별시 중구 세종대로 110");
        a.put("address2", "3층");
        a.put("city", "중구");
        a.put("state", "서울특별시");
        a.put("zipCode", "04524");
        a.put("country", "대한민국");
        a.put("phoneNumber", "010-1234-5678");
        a.put("active", true);
        return a;
    }

    private org.springframework.test.web.servlet.ResultActions create(Map<String, Object> shippingAddress) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        if (shippingAddress != null) {
            body.put("shippingAddress", shippingAddress);
        }
        body.put("paymentMethod", "paypal");
        return mvc.perform(post("/api/order/create").header("User-Id", "7")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    @Test
    @DisplayName("정상 주소는 그대로 통과한다 — 한쪽에만 이름을 적은 주소록 주소도")
    void validAddressPasses() throws Exception {
        create(address()).andExpect(status().isOk()).andExpect(jsonPath("$.orderId").value("42"));

        Map<String, Object> oneName = address();
        oneName.put("firstname", "홍길동");
        oneName.put("lastname", "");
        oneName.put("address2", null);
        create(oneName).andExpect(status().isOk());
    }

    @Test
    @DisplayName("🔴 DB 가 요구하는 값(기본 주소 · 도시 · 시/도 · 우편번호)이 비면 서비스에 닿기 전에 400 — 예전엔 NOT NULL 위반 500")
    void requiredFieldsAreChecked() throws Exception {
        for (String field : new String[] {"address1", "city", "state", "zipCode"}) {
            Map<String, Object> a = address();
            a.put(field, field.equals("city") ? "   " : null);
            create(a).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("요청 값이 올바르지 않습니다."));
        }
        verify(orderService, never()).createOrder(any(), anyLong(), any());
    }

    @Test
    @DisplayName("🔴 받는 사람 이름이 없으면 400")
    void recipientIsRequired() throws Exception {
        Map<String, Object> a = address();
        a.put("firstname", "");
        a.put("lastname", null);
        create(a).andExpect(status().isBadRequest());
        verify(orderService, never()).createOrder(any(), anyLong(), any());
    }

    @Test
    @DisplayName("🔴 컬럼 폭(255)을 넘으면 400 — 예전엔 DB 오류 500")
    void tooLongIsRejected() throws Exception {
        Map<String, Object> a = address();
        a.put("address2", "가".repeat(256));
        create(a).andExpect(status().isBadRequest());
        verify(orderService, never()).createOrder(any(), anyLong(), any());
    }

    @Test
    @DisplayName("배송지가 아예 없으면 검증이 아니라 서비스가 '배송지를 선택해 주세요.' 로 거절한다(그 문구를 지킨다)")
    void missingAddressIsLeftToTheService() throws Exception {
        create(null);
        verify(orderService).createOrder(any(), anyLong(), any());
    }

    @Test
    @DisplayName("Idempotency-Key 헤더는 그대로 서비스에 닿는다 — 없으면 null(예전 동작)")
    void idempotencyKeyReachesTheService() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("shippingAddress", address());
        body.put("paymentMethod", "paypal");
        mvc.perform(post("/api/order/create").header("User-Id", "7").header("Idempotency-Key", "a1b2c3d4-try-1")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk());
        verify(orderService).createOrder(any(), eq(7L), eq("a1b2c3d4-try-1"));

        create(address()).andExpect(status().isOk());
        verify(orderService).createOrder(any(), eq(7L), isNull());
    }
}
