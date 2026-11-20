package com.project.coupon_service.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * coupon → cart 장바구니 조회의 계약. 받는 쪽(cart {@code CartController})은 {@code User-Id} 헤더로 신원을 읽는다
 * (docs/48 §6-⑥). 이름·타입이 어긋나면 쿠폰 견적이 전부 "장바구니를 확인할 수 없다" 로 거절된다.
 *
 * <p>[JA] coupon → cart のカート照会の契約。受け側(cart {@code CartController})は {@code User-Id} ヘッダーで身元を読む(docs/48 §6-⑥)。名前・型がずれるとクーポン見積もりがすべて「カートを確認できない」で拒否される。
 */
@DisplayName("coupon → cart 계약")
class CartClientContractTest {

    @Test
    @DisplayName("장바구니 조회는 신원을 User-Id 헤더(사용자 id)로 보낸다")
    void sendsUserIdHeader() throws Exception {
        Method getCart = CartServiceClient.class.getMethod("getCart", Long.class);
        RequestHeader header = getCart.getParameters()[0].getAnnotation(RequestHeader.class);

        assertThat(header).isNotNull();
        assertThat(header.value()).isEqualTo("User-Id");
    }

    /** 전환 릴리스가 끝났다 — 옛 cart 용으로 User-Email 을 싣던 호출이 되살아나면 이메일 신원도 함께 되살아난다. */
    // [JA] 移行リリースは終わった — 古い cart 用に User-Email を載せていた呼び出しが復活すると、メールの身元も一緒に復活する。
    @Test
    @DisplayName("어떤 호출도 User-Email 을 싣지 않는다")
    void noCallSendsTheEmailHeader() {
        for (Method m : CartServiceClient.class.getDeclaredMethods()) {
            for (var p : m.getParameters()) {
                RequestHeader h = p.getAnnotation(RequestHeader.class);
                if (h != null) {
                    assertThat(h.value()).as(m.getName()).isNotEqualToIgnoringCase("User-Email");
                }
            }
        }
    }
}
