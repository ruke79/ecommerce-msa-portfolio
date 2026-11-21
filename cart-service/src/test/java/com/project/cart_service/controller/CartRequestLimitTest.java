package com.project.cart_service.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.project.cart_service.service.CartService;

/**
 * 장바구니 요청의 줄 수 상한 — 한 요청에 100줄까지, 넘으면 서비스에 닿기 전에 400(docs/93 ③ 3단계).
 *
 * <p>🐛 2026-11-21 까지 savecart · updatecart 의 목록에 제한이 없었다 — 줄마다 catalog 조회가 붙고, updatecart 는 로그인 없이
 * 불리는데 {@code @Valid} 도 없었다. 실제 HTTP 경로(MockMvc)로 보내야 {@code @Valid} 가 켜졌는지 보인다 — 메서드를 직접 부르면
 * 검증을 거치지 않는다.
 *
 * <p>[JA] カート要求の行数の上限 — 一回 100 行まで、超えればサービスに届く前に 400。以前は制限がなく、updatecart はログインなしで
 *      呼ばれるのに @Valid もなかった。実際の HTTP 経路(MockMvc)で送らないと @Valid が効いているか見えない。
 */
@DisplayName("장바구니 요청 — 한 번에 100줄까지(넘으면 400)")
class CartRequestLimitTest {

    CartService svc;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        svc = mock(CartService.class);
        mvc = MockMvcBuilders.standaloneSetup(new CartController(svc)).build();
    }

    /** {"products":[{}, {}, …]} — 줄 n개. [JA] 行 n 個。 */
    private static String body(int lines) {
        return "{\"products\":[" + String.join(",", java.util.Collections.nCopies(lines, "{}")) + "]}";
    }

    @Test
    @DisplayName("🔴 updatecart(로그인 없이 불린다) — 101줄은 400 · 서비스에 닿지 않는다, 100줄은 통과")
    void updateCartRejectsOverLimit() throws Exception {
        when(svc.updateCart(any())).thenReturn(List.of());

        mvc.perform(put("/api/cart/updatecart").contentType(MediaType.APPLICATION_JSON).content(body(101)))
                .andExpect(status().isBadRequest());
        verify(svc, never()).updateCart(any());

        mvc.perform(put("/api/cart/updatecart").contentType(MediaType.APPLICATION_JSON).content(body(100)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("🔴 savecart — 101줄은 400 · 서비스에 닿지 않는다")
    void saveCartRejectsOverLimit() throws Exception {
        mvc.perform(post("/api/cart/savecart").header("User-Id", "1").contentType(MediaType.APPLICATION_JSON).content(body(101)))
                .andExpect(status().isBadRequest());
        verify(svc, never()).saveCart(any(), anyLong());
    }
}
