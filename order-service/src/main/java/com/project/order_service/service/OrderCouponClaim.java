package com.project.order_service.service;

import java.time.Duration;

import org.springframework.stereotype.Component;

import com.project.order_service.client.CouponServiceClient;
import com.project.order_service.domain.exception.OrderDomainException;
import com.project.order_service.model.Order;

import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 결제를 시작할 때 주문의 쿠폰을 그 주문에 잡는다(coupon V7) — 같은 쿠폰으로 두 주문을 동시에 결제하지 못하게.
 *
 * <p>🐛 예전에는 쿠폰이 결제 확정 때에야 USED 가 됐다. 같은 손님이 같은 쿠폰으로 주문 둘을 만들면(견적은 부수효과가 없다) 둘 다
 * 결제할 수 있었고, 두 번째 결제 확정에서 경보(CouponDoubleUse)만 울렸다 — 할인은 두 번 나갔다.
 *
 * <p>결제창을 열기 직전(OrderService.renewCheckoutHold — 카드 · 간편결제 · 포트원 · Stripe 모두 거친다)과 입금 안내(BankTransferService)
 * 에서 부른다. 잡지 못하면 결제를 시작하지 않는다(fail-closed — 주문 생성의 쿠폰 견적과 같다). 단 coupon-service 에 이 엔드포인트가
 * 없으면(404 — 옛 파드, 롤링 중) 예전처럼 막지 않는다.
 *
 * <p>[JA] 決済開始時に注文のクーポンをその注文に押さえる。押さえられなければ決済を始めない。coupon-service が古ければ(404)以前どおり。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCouponClaim {

    static final String IN_PAYMENT_MESSAGE =
            "이 쿠폰으로 결제가 진행 중인 다른 주문이 있습니다. 그 결제를 마치거나 취소한 뒤 다시 시도해 주세요.";
    static final String USED_MESSAGE =
            "이 주문의 쿠폰은 이미 다른 주문에 쓰였습니다. 이 주문을 취소하고 쿠폰 없이 다시 주문해 주세요.";
    static final String NOT_FOUND_MESSAGE =
            "이 주문의 쿠폰을 찾을 수 없습니다. 이 주문을 취소하고 다시 주문해 주세요.";
    static final String UNREACHABLE_MESSAGE =
            "쿠폰을 확인하지 못해 결제를 시작하지 못했습니다. 잠시 후 다시 시도해 주세요.";

    private final CouponServiceClient couponServiceClient;

    /**
     * 주문에 쿠폰이 있으면 {@code hold} 동안 이 주문에 잡는다. 쿠폰이 없으면 아무것도 하지 않는다.
     *
     * @throws OrderDomainException 잡지 못했다 — 결제를 시작하지 않는다(문구는 손님용)
     */
    public void claim(Order order, Duration hold) {
        String coupon = order == null ? null : order.getCouponApplied();
        if (coupon == null || coupon.isBlank()) {
            return;
        }
        CouponServiceClient.PaymentClaim claim;
        try {
            claim = couponServiceClient.claimForPayment(order.getCustomerId(), coupon, order.getOrderId(),
                    Math.max(1, hold.toSeconds()));
        } catch (FeignException.NotFound e) {
            log.warn("coupon-service 에 결제 시작 엔드포인트가 없다(옛 파드) — 쿠폰을 잡지 않고 간다. orderId={}", order.getOrderId());
            return;
        } catch (RuntimeException e) {
            log.warn("쿠폰 결제 시작 실패 — 결제를 시작하지 않는다. orderId={} {}", order.getOrderId(), e.toString());
            throw new OrderDomainException(UNREACHABLE_MESSAGE);
        }
        String result = claim == null ? null : claim.result();
        if ("CLAIMED".equals(result)) {
            return;
        }
        log.info("쿠폰을 잡지 못해 결제를 시작하지 않는다. orderId={} result={}", order.getOrderId(), result);
        if ("IN_PAYMENT_ELSEWHERE".equals(result)) {
            throw new OrderDomainException(IN_PAYMENT_MESSAGE);
        }
        if ("USED".equals(result)) {
            throw new OrderDomainException(USED_MESSAGE);
        }
        if ("NOT_FOUND".equals(result)) {
            throw new OrderDomainException(NOT_FOUND_MESSAGE);
        }
        throw new OrderDomainException(UNREACHABLE_MESSAGE);
    }
}
