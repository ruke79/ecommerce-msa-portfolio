package com.project.cart_service.message.consumer;


import org.springframework.stereotype.Service;

import com.project.cart_service.model.Cart;
import com.project.cart_service.service.CartService;
import com.project.common.message.dto.request.CartRollbackRequest;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 결제 실패 보상(cart-rollback) — 장바구니의 쿠폰 적용액을 지운다.
 *
 * <p>🐛 예전: 장바구니가 없으면 {@code cartRollback} 이 null 을 주는데 로그 다음 줄에서 {@code cart.getCartId()} 로 NPE 가 났다 —
 * 재시도 끝에 {@code cart-rollback.DLT} 로 가 {@code KafkaDeadLetterPublished} 경보까지 울렸다(coupon-use 리스너에서 고친 것과 같은
 * 결함이 여기 남아 있었다). 장바구니가 없다는 것은 되돌릴 쿠폰 적용액이 없다는 뜻일 뿐이다(마지막 줄을 빼 행이 지워졌거나 · 탈퇴 ·
 * 나중 주문의 cart-empty 뒤에 온 재전달) — 건너뛴다.
 *
 * <p>[JA] 決済失敗の補償 — カートのクーポン適用額を消す。以前はカートがないとログの次の行で NPE になり DLT・警報まで行った(coupon-use
 *      で直したのと同じ欠陥)。カートがなければ戻すものがないだけ — 飛ばす。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartRollbackMessageListener {

    private final CartService cartService;

    @Transactional
    public void cartRollback(CartRollbackRequest request) {
        Cart cart = cartService.cartRollback(request);
        if (cart == null) {
            log.info("결제 실패 보상 — 장바구니가 없어 건너뛴다. userId={}", request.getUserId());
            return;
        }
        log.debug("결제 실패 보상 — 쿠폰 적용액을 지웠다. cartId={}", cart.getCartId());
    }

}
