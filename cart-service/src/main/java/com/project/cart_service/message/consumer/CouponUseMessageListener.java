package com.project.cart_service.message.consumer;

import org.springframework.stereotype.Service;

import com.project.cart_service.model.Cart;
import com.project.cart_service.service.CartService;

import com.project.common.message.dto.request.CouponUseRequest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 쿠폰 적용 결과(coupon-use) — 장바구니의 할인 후 금액을 맞춘다.
 *
 * <p>🐛 예전: 장바구니가 없으면 {@code couponUsed} 가 null 을 주는데 바로 다음 줄에서 {@code cart.getCartId()} 로 NPE 가 났다 — 그
 * 예외로 재시도 끝에 DLT 로 가 {@code KafkaDeadLetterPublished} 경보까지 울렸다. 장바구니가 없다는 것은 적용할 곳이 없다는 뜻일 뿐이다
 * (주문 뒤 비워졌거나 탈퇴) — 건너뛴다.
 *
 * <p>[JA] クーポン適用結果 — カートの割引後金額を合わせる。以前はカートがないと次の行で NPE になり DLT・警報まで行った。カートがなければ飛ばす。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponUseMessageListener {

    private final CartService cartService;

    void couponUsed(CouponUseRequest request) {
        Cart cart = cartService.couponUsed(request);
        if (cart == null) {
            log.info("쿠폰 적용 — 장바구니가 없어 건너뛴다. userId={}", request.getUserId());
            return;
        }
        log.debug("쿠폰 적용 — 할인 후 금액 반영. cartId={}", cart.getCartId());
    }
}
