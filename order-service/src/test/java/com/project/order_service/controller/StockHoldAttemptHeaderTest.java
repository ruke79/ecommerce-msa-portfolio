package com.project.order_service.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.project.order_service.exception.OrderRestExceptionHandler;
import com.project.order_service.payment.PaymentVerificationException;
import com.project.order_service.service.OrderDataService;
import com.project.order_service.service.OrderService;

/** 결제 시작 — Payment-Attempt 헤더가 서비스에 닿고, 다른 창이 결제 중이면 400 과 이유(화면이 그 이유로 멈춘다). */
@DisplayName("결제 시작 — 결제 시도 헤더")
class StockHoldAttemptHeaderTest {

    private final OrderService orderService = mock(OrderService.class);
    private final MockMvc mvc;

    StockHoldAttemptHeaderTest() {
        OrderController controller = new OrderController();
        ReflectionTestUtils.setField(controller, "orderService", orderService);
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new OrderRestExceptionHandler()).build();
    }

    @Test
    @DisplayName("헤더는 그대로 서비스에 — 없으면 null(옛 화면)")
    void headerReachesService() throws Exception {
        when(orderService.renewCheckoutHold(eq("t-1"), eq(7L), eq("tab-one-attempt"))).thenReturn(Map.of("renewed", true));
        when(orderService.renewCheckoutHold(eq("t-1"), eq(7L), isNull())).thenReturn(Map.of("renewed", true));

        mvc.perform(post("/api/order/payment/stock-hold/t-1").header("User-Id", "7").header("Payment-Attempt", "tab-one-attempt"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/order/payment/stock-hold/t-1").header("User-Id", "7")).andExpect(status().isOk());

        verify(orderService).renewCheckoutHold("t-1", 7L, "tab-one-attempt");
        verify(orderService).renewCheckoutHold(eq("t-1"), eq(7L), isNull());
    }

    @Test
    @DisplayName("🔴 다른 창이 결제 중이면 400 과 이유")
    void otherWindowIs400() throws Exception {
        when(orderService.renewCheckoutHold("t-1", 7L, "tab-two-attempt"))
                .thenThrow(new PaymentVerificationException(OrderDataService.OTHER_WINDOW_MESSAGE));

        mvc.perform(post("/api/order/payment/stock-hold/t-1").header("User-Id", "7").header("Payment-Attempt", "tab-two-attempt"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(OrderDataService.OTHER_WINDOW_MESSAGE));
    }
}
