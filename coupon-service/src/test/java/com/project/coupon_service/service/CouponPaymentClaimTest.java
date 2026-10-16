package com.project.coupon_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.common.constants.coupon.CouponStatus;
import com.project.common.message.dto.request.CouponRollbackRequest;
import com.project.common.message.dto.response.PaymentResponse;
import com.project.coupon_service.client.CartServiceClient;
import com.project.coupon_service.client.CatalogServiceClient;
import com.project.coupon_service.client.UserServiceClient;
import com.project.coupon_service.message.producer.CouponIssuedProducer;
import com.project.coupon_service.message.producer.CouponUseProducer;
import com.project.coupon_service.model.Coupon;
import com.project.coupon_service.repository.jpa.CouponRepository;
import com.project.coupon_service.repository.jpa.CouponTargetRepository;

/**
 * 결제를 시작할 때 주문이 쿠폰을 잡는다(V7) — 같은 쿠폰으로 두 주문을 동시에 결제하지 못하게.
 *
 * <p>🐛 예전: 쿠폰은 결제 확정 때에야 USED 가 됐다. 같은 쿠폰으로 주문 둘을 만들면 견적은 둘 다 통과하고 둘 다 결제할 수 있었다 —
 * 두 번째 결제 확정에서 경보(CouponDoubleUse)만 울리고 할인은 두 번 나갔다.
 *
 * <p>[JA] 決済開始時に注文がクーポンを押さえる — 同じクーポンで二つの注文を同時に決済できないように。
 */
@DisplayName("쿠폰 — 결제 시작 때 잡기(V7)")
class CouponPaymentClaimTest {

    private final CouponRepository coupons = mock(CouponRepository.class);
    private final CartServiceClient cart = mock(CartServiceClient.class);
    private final CouponService service = new CouponService(coupons, mock(CouponTargetRepository.class),
            mock(UserServiceClient.class), cart, mock(CatalogServiceClient.class),
            mock(CouponUseProducer.class), mock(CouponIssuedProducer.class));
    private Coupon coupon;

    @BeforeEach
    void setUp() {
        coupon = Coupon.builder().couponId(1L).userId(7L).name("WELCOME").discount(10).couponStatus(CouponStatus.NOT_USED)
                .startDate(LocalDateTime.now().minusDays(1)).endDate(LocalDateTime.now().plusDays(30)).build();
        when(coupons.findForUpdate("WELCOME", 7L)).thenReturn(Optional.of(coupon));
        when(coupons.findByNameAndUserIdAndCouponStatus(any(), anyLong(), any())).thenReturn(Optional.of(coupon));
        when(coupons.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private CouponService.ClaimOutcome claim(long orderId) {
        return service.claimForPayment(7L, "WELCOME", orderId, Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("🔴 한 주문이 결제 중이면 다른 주문은 결제를 시작하지 못한다 — 같은 주문은 다시 열 수 있다(끝을 늘린다)")
    void secondOrderIsRefusedWhileFirstPays() {
        assertThat(claim(100)).isEqualTo(CouponService.ClaimOutcome.CLAIMED);
        assertThat(coupon.getPaymentOrderId()).isEqualTo(100L);
        LocalDateTime first = coupon.getPaymentUntil();
        assertThat(first).isAfter(LocalDateTime.now().plusMinutes(29));

        assertThat(claim(200)).isEqualTo(CouponService.ClaimOutcome.IN_PAYMENT_ELSEWHERE);
        assertThat(coupon.getPaymentOrderId()).as("거절이 표시를 덮지 않는다").isEqualTo(100L);

        assertThat(claim(100)).isEqualTo(CouponService.ClaimOutcome.CLAIMED);
        assertThat(coupon.getPaymentUntil()).isAfterOrEqualTo(first);
    }

    @Test
    @DisplayName("끝이 지난 표시는 다른 주문이 넘겨받는다 — 결제창을 닫고 떠난 주문이 쿠폰을 붙들지 않는다")
    void expiredClaimIsTakenOver() {
        coupon.setPaymentOrderId(100L);
        coupon.setPaymentUntil(LocalDateTime.now().minusSeconds(1));

        assertThat(claim(200)).isEqualTo(CouponService.ClaimOutcome.CLAIMED);
        assertThat(coupon.getPaymentOrderId()).isEqualTo(200L);
    }

    @Test
    @DisplayName("이미 쓴 쿠폰 — 쓴 주문이면 CLAIMED(재요청), 다른 주문이면 USED. 없는 쿠폰은 NOT_FOUND")
    void usedAndMissing() {
        coupon.setCouponStatus(CouponStatus.USED);
        coupon.setUsedOrderId(100L);
        assertThat(claim(100)).isEqualTo(CouponService.ClaimOutcome.CLAIMED);
        assertThat(claim(200)).isEqualTo(CouponService.ClaimOutcome.USED);

        when(coupons.findForUpdate("NONE", 7L)).thenReturn(Optional.empty());
        assertThat(service.claimForPayment(7L, "NONE", 100L, Duration.ofMinutes(30))).isEqualTo(CouponService.ClaimOutcome.NOT_FOUND);
        assertThat(service.claimForPayment(7L, "WELCOME", null, Duration.ofMinutes(30))).isEqualTo(CouponService.ClaimOutcome.NOT_FOUND);
    }

    @Test
    @DisplayName("잡는 시간에는 상한(72시간)이 있다 — 호출 쪽 실수로 쿠폰이 영영 묶이지 않게")
    void holdIsBounded() {
        service.claimForPayment(7L, "WELCOME", 100L, Duration.ofDays(30));
        assertThat(coupon.getPaymentUntil()).isBefore(LocalDateTime.now().plusHours(73));
    }

    @Test
    @DisplayName("🔴 다른 주문이 결제 중이면 견적(주문 생성 · 장바구니 적용)도 거절한다 — 장바구니를 읽기 전에")
    void quoteRefusesWhileAnotherOrderPays() {
        claim(100);

        var response = service.quote("WELCOME", 7L);

        assertThat(response.getMessage()).isEqualTo(CouponService.IN_PAYMENT_MESSAGE);
        verify(cart, never()).getCart(anyLong());
    }

    @Test
    @DisplayName("결제 확정은 표시를 지우고 USED 로 — 결제 중이던 주문이 달라도(돈은 이미 나갔다) 이 주문이 쓴 것으로")
    void useClearsTheClaim() {
        claim(100);
        assertThat(service.useForOrder(paid(100))).isEqualTo(CouponService.UseOutcome.USED);
        assertThat(coupon.getPaymentOrderId()).isNull();
        assertThat(coupon.getPaymentUntil()).isNull();

        coupon.setCouponStatus(CouponStatus.NOT_USED);
        claim(100);
        assertThat(service.useForOrder(paid(200))).isEqualTo(CouponService.UseOutcome.USED);
        assertThat(coupon.getUsedOrderId()).isEqualTo(200L);
        assertThat(coupon.getPaymentOrderId()).isNull();
    }

    @Test
    @DisplayName("🔴 결제를 시작했다가 실패 · 취소된 주문의 보상은 표시만 푼다 — 예전에는 '되돌릴 것 없음' 으로 DLT 에 갔다")
    void rollbackReleasesTheClaim() {
        claim(100);

        assertThat(service.couponRollback(new CouponRollbackRequest(7L, "WELCOME", 200L))).as("다른 주문의 보상").isNull();
        assertThat(coupon.getPaymentOrderId()).isEqualTo(100L);

        assertThat(service.couponRollback(new CouponRollbackRequest(7L, "WELCOME", 100L))).isSameAs(coupon);
        assertThat(coupon.getPaymentOrderId()).isNull();
        assertThat(coupon.getCouponStatus()).isEqualTo(CouponStatus.NOT_USED);
        assertThat(claim(200)).isEqualTo(CouponService.ClaimOutcome.CLAIMED);
    }

    private static PaymentResponse paid(long orderId) {
        return PaymentResponse.builder().paymentId(orderId * 10).orderId(orderId).customerId(7L).couponName("WELCOME").build();
    }
}
