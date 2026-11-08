package com.project.cart_service.message.consumer;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.cart_service.model.Cart;
import com.project.cart_service.service.CartService;
import com.project.common.message.dto.request.CartRollbackRequest;

/**
 * cart-rollback — 장바구니가 없으면 건너뛴다.
 *
 * <p>🐛 예전: null 을 받고 로그를 남긴 다음 줄에서 NPE — 재시도 끝에 cart-rollback.DLT 로 가 경보가 울렸다(coupon-use 와 같은 결함).
 *
 * <p>[JA] cart-rollback — カートがなければ飛ばす。以前は null の次の行で NPE になり DLT・警報まで行った。
 */
@DisplayName("cart cart-rollback 리스너")
class CartRollbackMessageListenerTest {

    private static CartRollbackRequest request() {
        CartRollbackRequest request = mock(CartRollbackRequest.class);
        when(request.getUserId()).thenReturn(7L);
        return request;
    }

    @Test
    @DisplayName("🔴 장바구니가 없으면 예외 없이 건너뛴다 — DLT · 경보로 가지 않는다")
    void noCartIsNotAFailure() {
        CartService carts = mock(CartService.class);
        when(carts.cartRollback(any())).thenReturn(null);

        assertThatCode(() -> new CartRollbackMessageListener(carts).cartRollback(request())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("장바구니가 있으면 쿠폰 적용액을 지운다")
    void rollsBackTheCart() {
        CartService carts = mock(CartService.class);
        when(carts.cartRollback(any())).thenReturn(mock(Cart.class));

        new CartRollbackMessageListener(carts).cartRollback(request());

        verify(carts).cartRollback(any());
    }
}
