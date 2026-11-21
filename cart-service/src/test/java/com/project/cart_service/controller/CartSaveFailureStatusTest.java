package com.project.cart_service.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.project.cart_service.service.CartService;
import com.project.common.exception.UpstreamCallException;

/**
 * 장바구니 저장(savecart)의 실패 상태 — catalog 가 아프면 503, 고칠 수 있는 요청(수량 범위)만 400. 다른 장바구니 경로와 같다.
 * 🐛 예전에는 IllegalArgumentException 만 잡아 catalog 실패가 500 이었다(docs/101 §4).
 * [JA] カート保存の失敗の状態 — catalog の不調は 503、直せる要求だけ 400。以前は catalog の失敗が 500 だった。
 */
@DisplayName("장바구니 저장 — catalog 실패는 503 · 수량 범위는 400")
class CartSaveFailureStatusTest {

    CartService svc;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        svc = mock(CartService.class);
        mvc = MockMvcBuilders.standaloneSetup(new CartController(svc)).build();
    }

    private org.springframework.test.web.servlet.ResultActions save() throws Exception {
        return mvc.perform(post("/api/cart/savecart").header("User-Id", "1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"products\":[{}]}"));
    }

    @Test
    @DisplayName("🔴 catalog 호출 실패 · 서킷 브레이커 열림은 503")
    void upstreamFailureIs503() throws Exception {
        org.mockito.Mockito.doThrow(new UpstreamCallException("CatalogServiceClient#getProductsInfo", 503)).when(svc).saveCart(any(), anyLong());
        save().andExpect(status().isServiceUnavailable());
        org.mockito.Mockito.doThrow(io.github.resilience4j.circuitbreaker.CallNotPermittedException.createCallNotPermittedException(
                        io.github.resilience4j.circuitbreaker.CircuitBreaker.ofDefaults("cart-catalog"))).when(svc).saveCart(any(), anyLong());
        save().andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("수량 범위 밖은 그대로 400")
    void badQuantityIs400() throws Exception {
        when(svc.saveCart(any(), anyLong())).thenThrow(new IllegalArgumentException("수량은 1 ~ 1000 사이여야 합니다."));
        save().andExpect(status().isBadRequest());
    }
}
