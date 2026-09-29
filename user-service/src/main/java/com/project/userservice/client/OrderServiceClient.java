package com.project.userservice.client;

import java.util.List;
import java.util.Map;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.project.common.dto.order.PurchaseStatDto;

/**
 * 등급 산정용 구매 집계를 가져온다.
 *
 * <p>🔒 <b>인터셉터를 붙이지 않는다.</b> coupon-service 의 {@code FeignConfig} 는 들어온
 * 요청의 Authorization 헤더를 그대로 전달하는데, 이 호출은 <b>배치</b>라 전달할 요청
 * 자체가 없다. 그리고 게이트웨이를 거치지 않으므로 {@code User-Role} 헤더도 붙지 않고,
 * order-service 는 그걸 "내부 호출" 로 읽는다({@code /api/order/internal/purchase-stats}).
 *
 * <p>즉 이 클라이언트에 요청 헤더 전달 인터셉터가 붙는 순간 인가 판정이 바뀐다. 나중에
 * 공통 {@code FeignConfig} 를 만들어 전 클라이언트에 적용하고 싶어지면, 이 자리는
 * 예외로 남겨야 한다.
 */
@FeignClient(name = "order-service", url = "${feign.order-url}")
public interface OrderServiceClient {

    /**
     * {@code afterCustomerId} 다음 고객부터 최대 {@code size} 명의 구매 집계.
     *
     * <p>커서 방식이다. <b>끝은 빈 응답으로만 판정한다</b> — 저쪽에 응답 크기 상한이
     * 있어서 큰 {@code size} 는 말없이 잘려 돌아온다. 덜 찬 응답을 끝으로 읽으면 아직
     * 보지 않은 고객이 전부 강등된다({@link com.project.userservice.service.CustomerTierService}).
     */
    @GetMapping("api/order/internal/purchase-stats")
    Map<String, List<PurchaseStatDto>> purchaseStats(@RequestParam("months") int months,
            @RequestParam("afterCustomerId") long afterCustomerId,
            @RequestParam("size") int size);

    /**
     * 이 고객이 이 상품을 받았는가(배송 완료·구매 확정) — 리뷰 작성 자격(docs/57 §13). 응답은 {@code {"purchased": true|false}}.
     * 위와 같은 이유로 신원 헤더 없이 부른다(order-service 는 내부 호출로 읽는다).
     */
    @GetMapping("api/order/internal/purchased")
    Map<String, Boolean> purchased(@RequestParam("customerId") long customerId,
            @RequestParam("productId") long productId);
}
