package com.project.order_service.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.project.common.constants.OrderStatus;
import com.project.common.dto.request.PaypalPaymentRequest;
import com.project.order_service.model.Order;
import com.project.order_service.saga.OrderSaga;
import com.project.order_service.stock.OrderStockHold;

/**
 * PayPal 창을 닫아 주문을 취소하면 결제 시작 때 잡은 쿠폰도 푼다(coupon-rollback — coupon V7 은 이 주문의 표시만 지운다).
 *
 * <p>🐛 보상이 없으면 PayPal 창을 닫고 다시 주문할 때 30분 동안 같은 쿠폰이 "다른 주문이 결제 중" 으로 막힌다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OrderService.cancelPaypalPayment — 쿠폰 보상")
class PaypalCancelCompensationTest {

    @Mock OrderCacheService cache;
    @Mock OrderStockHold stockHold;
    @Mock OrderSaga saga;
    @InjectMocks OrderService service;

    private PaypalPaymentRequest request() {
        PaypalPaymentRequest r = new PaypalPaymentRequest();
        r.setTrackingId("t-1");
        return r;
    }

    @Test
    @DisplayName("🔴 취소됐으면 재고와 함께 쿠폰 보상을 보낸다")
    void canceledCompensatesCoupon() {
        Order order = Order.builder().orderId(77L).customerId(7L).trackingId("t-1").couponApplied("WELCOME")
                .orderStatus(OrderStatus.CANCELED).build();
        when(cache.getOrderByTrackingId("t-1")).thenReturn(order);

        service.cancelPaypalPayment(request(), 7L);

        verify(stockHold).releaseAfterCommit(77L);
        verify(saga).compensateCancel(order);
    }

    @Test
    @DisplayName("취소가 거절됐으면(결제된 주문) 보상하지 않는다")
    void notCanceledDoesNothing() {
        when(cache.getOrderByTrackingId("t-1")).thenReturn(Order.builder().orderId(77L).orderStatus(OrderStatus.PAID).build());

        service.cancelPaypalPayment(request(), 7L);

        verify(saga, never()).compensateCancel(any());
    }
}
