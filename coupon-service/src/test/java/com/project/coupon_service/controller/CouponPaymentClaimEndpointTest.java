package com.project.coupon_service.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.project.coupon_service.service.CouponService;

/** 결제 시작 엔드포인트 — 내부 호출만(게이트웨이를 지난 사용자 요청은 403), 결과는 이름 그대로. */
@DisplayName("쿠폰 결제 시작 엔드포인트")
class CouponPaymentClaimEndpointTest {

    private final CouponService service = mock(CouponService.class);
    private final CouponController controller = new CouponController(service);

    @Test
    @DisplayName("🔒 게이트웨이를 지난 사용자 요청(신원 헤더)은 403 — 남의 쿠폰을 묶지 못한다")
    void externalCallerIsRefused() {
        var response = controller.claimForPayment(7L, "WELCOME", 100L, 1800, "ROLE_USER", null, "8");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(service, never()).claimForPayment(anyLong(), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("내부 호출은 서비스의 결과를 그대로 — 시간은 초로 받는다")
    void internalCallReturnsOutcome() {
        when(service.claimForPayment(7L, "WELCOME", 100L, Duration.ofSeconds(1800)))
                .thenReturn(CouponService.ClaimOutcome.IN_PAYMENT_ELSEWHERE);

        var response = controller.claimForPayment(7L, "WELCOME", 100L, 1800, null, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(Map.of("result", "IN_PAYMENT_ELSEWHERE"));
        verify(service).claimForPayment(eq(7L), eq("WELCOME"), eq(100L), eq(Duration.ofSeconds(1800)));
    }

    @Test
    @DisplayName("🔴 CP1: 견적은 주문이 실어 보낸 장바구니를 그대로 서비스에 넘긴다")
    void quotePassesTheOrdersCart() {
        com.project.common.dto.CartDto cart = com.project.common.dto.CartDto.builder().cartTotal(new java.math.BigDecimal("100")).build();

        controller.quote(7L, "WELCOME", cart, null, null, null);

        verify(service).quote("WELCOME", 7L, cart);
    }
}

