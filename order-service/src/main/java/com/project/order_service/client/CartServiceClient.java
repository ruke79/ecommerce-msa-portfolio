package com.project.order_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

import com.project.order_service.config.FeignConfig;


@FeignClient(name = "cart-service", url = "${feign.cart-url}", configuration = FeignConfig.class)
public interface CartServiceClient {

    @GetMapping("api/cart/{cartProductId}")
    String getProductId(@PathVariable("cartProductId") String cartProdcutId);

    /**
     * 주문에 쓸 장바구니를 읽는다.
     *
     * <h3>🐛 이 호출은 성립한 적이 없었다</h3>
     *
     * <p>예전 시그니처는 이랬다:
     *
     * <pre>{@code
     * ResponseEntity<?> getCart(@RequestParam("userId") String email,
     *                           @RequestParam("Authorization") String token);
     * }</pre>
     *
     * <p>그런데 cart-service 쪽은 보안 강화 때 <b>쿼리 파라미터를 버리고 게이트웨이가
     * 검증한 헤더를 읽도록</b> 바뀌었다 — {@code @RequestHeader("User-Email")} 이 없으면
     * 401 이다. 이쪽은 그대로여서 <b>언제나 401</b> 이었다.
     *
     * <pre>
     * GET /api/cart/checkout?userId=…&amp;Authorization=Bearer%20… -> 401 {"message":"로그인이 필요합니다."}
     * </pre>
     *
     * <p>프론트가 결제 화면을 열 때 하는 호출은 <b>게이트웨이를 지나며 헤더가 붙으므로</b>
     * 멀쩡했다. 깨진 것은 <b>서비스 간 직접 호출뿐</b>이라, cart-service 로그에는 정상
     * 호출만 보였다 — 실패한 호출은 컨트롤러 본문에 들어가기도 전에 끝나 흔적이 없었다.
     *
     * <p>그 401 이 왜 {@code {"message": null}} 로 보였는지는 docs/47 §5-2 에 있다.
     *
     * <h3>🔒 토큰을 쿼리 파라미터로 보내고 있었다</h3>
     *
     * <p>{@code Authorization=Bearer%20eyJ…} 가 <b>URL 에</b> 들어갔다. URL 은 어디에나
     * 남는다 — 액세스 로그, 프록시, 예외 메시지, Referer. 실제로 order-service 의 에러
     * 로그에 액세스 토큰 전문이 찍혔다.
     *
     * <p>게다가 <b>필요도 없었다.</b> {@link FeignConfig} 의 인터셉터가 들어온 요청의
     * {@code Authorization} <b>헤더</b>를 그대로 이어 붙인다. 파라미터를 지우는 것으로
     * 끝난다 — 여기서 헤더로 다시 선언하면 인터셉터와 겹쳐 같은 헤더가 두 번 간다.
     */
    // 신원은 사용자 id 다 — cart 는 장바구니를 id 로 저장하고 캐시 키도 id 다. 예전에는 User-Email 을 실었고, cart 가
    // 그 이메일로 user-service 에 id 를 다시 물었다(docs/48 §6-⑥).
    @GetMapping("api/cart/checkout")
    ResponseEntity<?> getCart(@RequestHeader("User-Id") Long userId);
}
