package com.project.coupon_service.client;

import java.util.List;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import com.project.common.dto.ProductDto;
import com.project.coupon_service.config.FeignConfig;

/**
 * 장바구니에 든 상품들의 <b>카테고리</b>를 알아내기 위한 클라이언트.
 *
 * <p>카테고리 제약 쿠폰은 "이 줄의 상품이 대상 카테고리에 속하는가"를 판정해야 한다.
 * 장바구니 줄에는 상품 id 만 있으므로 카테고리는 catalog-service 가 안다.
 *
 * <p>줄마다 한 번씩 부르지 않는다 — 배치 엔드포인트를 쓴다. cart-service 가 같은 이유로
 * 이미 쓰고 있고, 상한도 서버가 갖고 있다({@code MAX_BATCH_IDS = 100}). 찾지 못한 id 는
 * 응답 목록에서 빠지므로, 그런 상품은 대상이 아닌 것으로 취급한다.
 */
@FeignClient(name = "catalog-service", url = "${feign.catalog-url}", configuration = FeignConfig.class)
public interface CatalogServiceClient {

    @GetMapping("/api/products/info")
    ResponseEntity<List<ProductDto>> getProductsInfo(@RequestParam("ids") List<Long> ids);

    /**
     * 기획전에 딸린 쿠폰의 조건.
     *
     * <p><b>왜 받을 때마다 물어보나.</b> 기획전은 catalog 의 것이다. 시작할 때 카프카로
     * 밀어 두면 기획전이 일찍 종료됐을 때 이쪽이 모르고 계속 발급한다. 수령은 사람이
     * 버튼을 누를 때만 일어나는 드문 동작이라, <b>그때 물어보는</b> 편이 항상 옳고 충분히
     * 싸다.
     *
     * <p>진행 중이 아니거나 쿠폰이 없으면 404 다 — 상태 판정을 이쪽에서 다시 하지 않는다.
     */
    @GetMapping("/api/content/events/{slug}/coupon-offer")
    ResponseEntity<EventCouponOffer> getEventCouponOffer(@PathVariable("slug") String slug);

    /**
     * catalog 가 내려주는 기획전 쿠폰 조건.
     *
     * <p>catalog 의 DTO 를 공유하지 않고 여기 다시 적는다. 공유하면 이 서비스가 카탈로그
     * 도메인의 스키마 변경에 끌려다닌다 — 알림 서비스가 JsonNode 로 읽는 것과 같은
     * 이유다. 필요한 필드만 받고, 늘어난 필드는 무시한다.
     */
    record EventCouponOffer(
            Long eventId,
            String slug,
            String title,
            String audience,
            String couponName,
            int couponDiscount,
            int couponValidDays,
            String couponScope,
            List<String> couponTargets) {
    }
}
