package com.project.order_service.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.order_service.client.CouponServiceClient;
import com.project.order_service.domain.exception.OrderDomainException;
import com.project.order_service.model.Order;

import feign.FeignException;
import feign.Request;

/**
 * 결제를 시작할 때 주문의 쿠폰을 잡는다(coupon V7) — 잡지 못하면 결제를 시작하지 않는다(fail-closed). 옛 coupon 파드(404)는 예외.
 *
 * <p>[JA] 決済開始時に注文のクーポンを押さえる — 押さえられなければ決済を始めない。古い coupon(404)は例外。
 */
@DisplayName("OrderCouponClaim — 결제 시작 때 쿠폰 잡기")
class OrderCouponClaimTest {

    private final CouponServiceClient client = mock(CouponServiceClient.class);
    private final OrderCouponClaim claim = new OrderCouponClaim(client);
    private final Order order = Order.builder().orderId(77L).customerId(7L).trackingId("t-1").couponApplied("WELCOME").build();

    private void answers(String result) {
        when(client.claimForPayment(7L, "WELCOME", 77L, 1800L)).thenReturn(new CouponServiceClient.PaymentClaim(result));
    }

    @Test
    @DisplayName("잡았으면 통과 — 주문의 고객 · 쿠폰 · 주문 id · 시간(초)을 보낸다")
    void claimedPasses() {
        answers("CLAIMED");
        assertThatCode(() -> claim.claim(order, Duration.ofMinutes(30))).doesNotThrowAnyException();
        verify(client).claimForPayment(7L, "WELCOME", 77L, 1800L);
    }

    @Test
    @DisplayName("🔴 다른 주문이 결제 중 · 이미 씀 · 없음이면 결제를 시작하지 않는다 — 이유는 손님 문구로")
    void refusals() {
        answers("IN_PAYMENT_ELSEWHERE");
        assertThatThrownBy(() -> claim.claim(order, Duration.ofMinutes(30)))
                .isInstanceOf(OrderDomainException.class).hasMessage(OrderCouponClaim.IN_PAYMENT_MESSAGE);
        answers("USED");
        assertThatThrownBy(() -> claim.claim(order, Duration.ofMinutes(30))).hasMessage(OrderCouponClaim.USED_MESSAGE);
        answers("NOT_FOUND");
        assertThatThrownBy(() -> claim.claim(order, Duration.ofMinutes(30))).hasMessage(OrderCouponClaim.NOT_FOUND_MESSAGE);
        answers("SOMETHING_NEW");
        assertThatThrownBy(() -> claim.claim(order, Duration.ofMinutes(30))).hasMessage(OrderCouponClaim.UNREACHABLE_MESSAGE);
    }

    @Test
    @DisplayName("🔴 coupon-service 에 닿지 못하면 결제를 시작하지 않는다(fail-closed) — 404(옛 파드)만 예전처럼 통과")
    void unreachableIsClosedButOldPodIsOpen() {
        Request request = Request.create(Request.HttpMethod.POST, "/api/coupon/internal/payment-claim", Map.of(), null,
                StandardCharsets.UTF_8, null);
        when(client.claimForPayment(7L, "WELCOME", 77L, 1800L)).thenThrow(new RuntimeException("connect timed out"));
        assertThatThrownBy(() -> claim.claim(order, Duration.ofMinutes(30))).hasMessage(OrderCouponClaim.UNREACHABLE_MESSAGE);

        org.mockito.Mockito.doThrow(new FeignException.NotFound("not found", request, null, null))
                .when(client).claimForPayment(7L, "WELCOME", 77L, 1800L);
        assertThatCode(() -> claim.claim(order, Duration.ofMinutes(30))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("쿠폰이 없는 주문은 부르지 않는다")
    void noCouponNoCall() {
        claim.claim(Order.builder().orderId(77L).customerId(7L).build(), Duration.ofMinutes(30));
        claim.claim(Order.builder().orderId(77L).customerId(7L).couponApplied(" ").build(), Duration.ofMinutes(30));
        verify(client, never()).claimForPayment(anyLong(), anyString(), anyLong(), anyLong());
    }
}
