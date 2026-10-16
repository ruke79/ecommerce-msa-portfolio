package com.project.coupon_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
 * 쿠폰은 그것을 쓴 주문에 묶인다 — 결제 확정이 주문을 적고, 보상은 그 주문의 것만 되돌린다.
 *
 * <p>🐛 예전: 보상이 이름 · 사용자로 찾은 USED 쿠폰을 무조건 되돌렸다 — 결제가 실패한 주문 B 의 보상이 결제된 주문 A 의 쿠폰을
 * 되살려 같은 쿠폰을 또 쓸 수 있었다. 결제 확정은 재전달과 이중 사용을 가르지 못했다.
 *
 * <p>[JA] クーポンは使った注文に結び付く — 補償はその注文のものだけを戻す。
 */
@DisplayName("쿠폰 — 주문에 묶인 사용 · 보상")
class CouponOrderBindingTest {

    private final CouponRepository coupons = mock(CouponRepository.class);
    private final CouponService service = new CouponService(coupons, mock(CouponTargetRepository.class),
            mock(UserServiceClient.class), mock(CartServiceClient.class), mock(CatalogServiceClient.class),
            mock(CouponUseProducer.class), mock(CouponIssuedProducer.class));
    private Coupon coupon;

    @BeforeEach
    void setUp() {
        coupon = Coupon.builder().couponId(1L).userId(7L).name("WELCOME").discount(10).couponStatus(CouponStatus.NOT_USED).build();
        when(coupons.findForUpdate("WELCOME", 7L)).thenReturn(Optional.of(coupon));
        when(coupons.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private static PaymentResponse paid(long orderId) {
        return PaymentResponse.builder().paymentId(orderId * 10).orderId(orderId).customerId(7L).couponName("WELCOME").build();
    }

    private static CouponRollbackRequest rollback(Long orderId) {
        return new CouponRollbackRequest(7L, "WELCOME", orderId);
    }

    @Test
    @DisplayName("결제 확정 — USED 로, 쓴 주문을 적는다. 같은 주문의 재전달은 그대로, 다른 주문은 이중 사용")
    void useBindsTheOrder() {
        assertThat(service.useForOrder(paid(100))).isEqualTo(CouponService.UseOutcome.USED);
        assertThat(coupon.getCouponStatus()).isEqualTo(CouponStatus.USED);
        assertThat(coupon.getUsedOrderId()).isEqualTo(100L);

        assertThat(service.useForOrder(paid(100))).isEqualTo(CouponService.UseOutcome.ALREADY_THIS_ORDER);
        assertThat(service.useForOrder(paid(200))).isEqualTo(CouponService.UseOutcome.USED_BY_ANOTHER_ORDER);
        assertThat(coupon.getUsedOrderId()).as("이중 사용이 쓴 주문을 덮지 않는다").isEqualTo(100L);
    }

    @Test
    @DisplayName("🔴 다른 주문의 보상은 쿠폰을 되돌리지 않는다 — 결제 실패한 주문 B 가 A 의 쿠폰을 되살리던 것")
    void rollbackOfAnotherOrderIsIgnored() {
        service.useForOrder(paid(100));

        assertThat(service.couponRollback(rollback(200L))).isNull();
        assertThat(coupon.getCouponStatus()).isEqualTo(CouponStatus.USED);
        verify(coupons, org.mockito.Mockito.times(1)).save(any());   // useForOrder 의 저장뿐
    }

    @Test
    @DisplayName("🔴 쓴 주문의 보상은 되돌리고 주문을 지운다")
    void rollbackOfTheSameOrderRestores() {
        service.useForOrder(paid(100));

        assertThat(service.couponRollback(rollback(100L))).isSameAs(coupon);
        assertThat(coupon.getCouponStatus()).isEqualTo(CouponStatus.NOT_USED);
        assertThat(coupon.getUsedOrderId()).isNull();
    }

    @Test
    @DisplayName("누구의 것인지 모르면 예전처럼 되돌린다 — 옛 메시지(orderId 없음) · 이 변경 전에 쓴 쿠폰(used_order_id 없음)")
    void legacyRollbackRestores() {
        coupon.setCouponStatus(CouponStatus.USED);
        assertThat(service.couponRollback(rollback(200L))).as("쓴 주문이 기록되지 않은 옛 USED").isSameAs(coupon);

        coupon.setCouponStatus(CouponStatus.USED);
        coupon.setUsedOrderId(100L);
        assertThat(service.couponRollback(rollback(null))).as("orderId 없는 v1 메시지").isSameAs(coupon);
    }

    @Test
    @DisplayName("쓰지 않은 쿠폰의 보상은 아무것도 하지 않는다")
    void rollbackOfUnusedDoesNothing() {
        assertThat(service.couponRollback(rollback(100L))).isNull();
        verify(coupons, never()).save(any());
    }
}
