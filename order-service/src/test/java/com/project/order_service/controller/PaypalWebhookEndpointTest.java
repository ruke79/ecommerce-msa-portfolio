package com.project.order_service.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.project.order_service.payment.PaypalWebhookService;

/**
 * PayPal 웹훅 엔드포인트의 응답(5단계 14) — 꺼짐 503 · 서명/본문 400(다시 보내지 않게) · 일시 실패 500(다시 보내게) · 정상 200.
 * [JA] PayPal ウェブフックの応答 — 無効 503 · 署名/本文 400 · 一時的失敗 500 · 正常 200。
 */
@DisplayName("PayPal 웹훅 엔드포인트 — 503 · 400 · 500 · 200")
class PaypalWebhookEndpointTest {

    private MockMvc mvc(PaypalWebhookService svc) {
        OrderController controller = new OrderController();
        ReflectionTestUtils.setField(controller, "paypalWebhookService", svc);
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    private org.springframework.test.web.servlet.ResultActions send(MockMvc mvc) throws Exception {
        return mvc.perform(post("/api/order/payment/paypal/webhook").contentType(MediaType.APPLICATION_JSON)
                .header("PAYPAL-TRANSMISSION-ID", "t").content("{\"id\":\"e\",\"event_type\":\"X\"}"));
    }

    @Test
    @DisplayName("🔴 웹훅 id 가 없으면 503 — 받지 않는다")
    void disabledIs503() throws Exception {
        PaypalWebhookService svc = mock(PaypalWebhookService.class);
        send(mvc(svc)).andExpect(status().isServiceUnavailable());
        send(mvc(null)).andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("서명 · 본문 거절은 400, 우리 쪽 일시 실패는 500, 정상은 200")
    void statuses() throws Exception {
        PaypalWebhookService svc = mock(PaypalWebhookService.class);
        when(svc.enabled()).thenReturn(true);
        when(svc.handle(anyString(), any())).thenReturn(PaypalWebhookService.Result.HANDLED);
        send(mvc(svc)).andExpect(status().isOk());

        org.mockito.Mockito.doThrow(new PaypalWebhookService.Rejected("서명")).when(svc).handle(anyString(), any());
        send(mvc(svc)).andExpect(status().isBadRequest());

        org.mockito.Mockito.doThrow(new IllegalStateException("db")).when(svc).handle(anyString(), any());
        send(mvc(svc)).andExpect(status().isInternalServerError());
    }
}
