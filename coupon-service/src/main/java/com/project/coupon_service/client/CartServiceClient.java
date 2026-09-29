package com.project.coupon_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

import com.project.common.dto.CartDto;
import com.project.coupon_service.config.FeignConfig;

/**
 * 장바구니를 <b>서버에서</b> 읽기 위한 클라이언트.
 *
 * <p>제약 있는 쿠폰은 "장바구니에 어떤 상품이 들었는가"를 알아야 판정할 수 있다. 그 목록을
 * 클라이언트가 보내게 하면 안 된다 — 할인 대상 상품이 담겨 있다고 <b>주장</b>하면 그대로
 * 믿게 된다. 게이트웨이가 검증한 신원으로 장바구니를 직접 읽는다.
 *
 * <p><b>{@code User-Id} 를 명시적으로 싣는다</b>(예전에는 {@code User-Email} — cart 의 신원이 id 로 바뀌었다,
 * docs/48 §6-⑥). {@link FeignConfig} 의 인터셉터는
 * {@code Authorization} 만 전달하고 신원 헤더는 전달하지 않는다. 그리고 Feign 호출은
 * 게이트웨이를 거치지 않고 서비스로 직접 간다({@code FEIGN_CART_URL} 은 클러스터 내부
 * 주소다). 그래서 게이트웨이가 넣어 주던 신원 헤더가 이 경로에는 없다 — 부르는 쪽이
 * 넣어야 한다.
 *
 * <p>넣는 값은 <b>게이트웨이가 검증한 신원에서 구한 것</b>이지 요청 본문에서 온 값이 아니다. 본문의 값을 그대로
 * 흘려보내면 남의 장바구니를 읽게 된다.
 */
@FeignClient(name = "cart-service", url = "${feign.cart-url}", configuration = FeignConfig.class)
public interface CartServiceClient {

    @GetMapping("api/cart/checkout")
    ResponseEntity<CartDto> getCart(@RequestHeader("User-Id") Long userId);
}
