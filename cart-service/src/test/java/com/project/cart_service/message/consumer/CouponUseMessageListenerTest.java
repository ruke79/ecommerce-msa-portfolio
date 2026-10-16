package com.project.cart_service.message.consumer;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.cart_service.model.Cart;
import com.project.cart_service.service.CartService;
import com.project.common.message.dto.request.CouponUseRequest;

/**
 * coupon-use — 장바구니가 없으면 건너뛴다.
 *
 * <p>🐛 예전: null 을 받은 바로 다음 줄에서 NPE — 재시도 끝에 DLT 로 가 경보가 울렸다.
 *
 * <p>[JA] coupon-use — カートがなければ飛ばす。以前は null の次の行で NPE になり DLT・警報まで行った。
 */
@DisplayName("cart coupon-use 리스너")
class CouponUseMessageListenerTest {

    private static CouponUseRequest request() {
        return CouponUseRequest.builder().userId(7L).discount(10).totalAfterDiscount(new BigDecimal("9000")).build();
    }

    @Test
    @DisplayName("🔴 장바구니가 없으면 예외 없이 건너뛴다 — DLT · 경보로 가지 않는다")
    void noCartIsNotAFailure() {
        CartService carts = mock(CartService.class);
        when(carts.couponUsed(any())).thenReturn(null);

        assertThatCode(() -> new CouponUseMessageListener(carts).couponUsed(request())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("장바구니가 있으면 할인 후 금액을 반영한다")
    void appliesToTheCart() {
        CartService carts = mock(CartService.class);
        when(carts.couponUsed(any())).thenReturn(mock(Cart.class));

        new CouponUseMessageListener(carts).couponUsed(request());

        verify(carts).couponUsed(any());
    }
}
