package com.project.catalog_service.controller;

import com.project.common.exception.ClientMessage;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.project.catalog_service.dto.ContentDtos.BannerView;
import com.project.catalog_service.dto.EventDtos;
import com.project.catalog_service.dto.EventDtos.EventDetailView;
import com.project.catalog_service.dto.EventDtos.EventView;
import com.project.catalog_service.dto.HomeDtos.HomeView;
import com.project.catalog_service.dto.ContentDtos.PagedNotices;
import com.project.catalog_service.model.Event;
import com.project.catalog_service.service.BannerService;
import com.project.catalog_service.service.EventService;
import com.project.catalog_service.service.HomeViewCache;
import com.project.catalog_service.service.NoticeService;
import com.project.catalog_service.service.ProductService;

import lombok.RequiredArgsConstructor;

/**
 * 공개 조회 — 로그인 없이 열려 있다.
 *
 * <p>배너는 첫 화면이고 공지는 비회원도 봐야 한다. 게이트웨이에서도 이 경로에는
 * {@code JwtAuthorization} 을 붙이지 않는다.
 *
 * <p>여기에는 <b>조회만</b> 둔다. 쓰기는 전부 {@code /api/content/admin/**}(→
 * {@code AdminWriteInterceptor} 의 ADMIN_CONTENT_PREFIX)이다. 그 아래는 <b>읽기도</b>
 * 관리자 전용이라, 비공개 배너·초안 공지를 조회하는 엔드포인트도 거기 둬야 한다.
 */
@RestController
@RequestMapping("/api/content")
@RequiredArgsConstructor
public class ContentController {

    private final BannerService bannerService;
    /** 첫 화면 구성 — 파드 메모리에 잠깐(HomeViewCache). 재고는 아래에서 응답마다 반영한다. */
    private final HomeViewCache homeView;
    private final NoticeService noticeService;
    private final EventService eventService;
    private final ProductService productService;
    /** 카드의 재고 표시 — 지금 수량 − 결제 전 예약(docs/59 §5). 목록은 캐시에서 오므로 응답 직전에. */
    private final com.project.catalog_service.service.StockAvailability stockAvailability;

    /**
     * 첫 화면 구성 — 섹션과 그 상품까지 한 번에.
     *
     * <p>예전에는 프론트가 {@code /api/product/frontpage} 를 불렀는데, 그건 캐시 워밍
     * 함수({@code warmUpProductCaches})를 그대로 노출한 것이었다. 모든 카테고리의 상품을
     * 한 배열로 평탄화해 내려보냈고, 프론트가 {@code filter} 로 다시 나눴다. 무엇을 어떤
     * 순서로 보여줄지는 프론트엔드 코드에 박혀 있었다.
     */
    @GetMapping("/home")
    public HomeView home() {
        HomeView home = homeView.get();
        if (home == null || home.sections() == null || home.sections().isEmpty()) {
            return home;
        }
        // 섹션마다 읽지 않는다 — 모든 섹션의 상품 id 로 한 번 읽어 나눠 쓴다(섹션끼리 상품이 겹치기도 한다).
        java.util.List<com.project.common.dto.ProductDto> all = new java.util.ArrayList<>();
        home.sections().forEach(s -> { if (s.products() != null) all.addAll(s.products()); });
        var stock = stockAvailability.snapshot(com.project.catalog_service.service.StockAvailability.idsOf(all), true);
        return new HomeView(home.sections().stream()
                .map(s -> s.withProducts(stock.apply(s.products())))
                .toList());
    }

    @GetMapping("/banners")
    public List<BannerView> banners() {
        return bannerService.visible();
    }

    @GetMapping("/notices")
    public PagedNotices notices(@RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        // 상한 클램프는 서비스가 한다 — 컨트롤러가 하면 관리자 목록 경로에서 빠뜨린다.
        return noticeService.published(page, size);
    }

    @GetMapping("/notices/{id}")
    public ResponseEntity<?> notice(@PathVariable("id") Long id) {
        try {
            return ResponseEntity.ok(noticeService.publishedOne(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(message(e));
        }
    }

    // ── 이벤트 / 기획전 ──────────────────────────────────────────────────

    /** 지금 진행 중인 기획전. 초안과 예약분은 나오지 않는다. */
    @GetMapping("/events")
    public List<EventView> events() {
        return eventService.active().stream().map(EventView::of).toList();
    }

    /**
     * 기획전 상세 — 기획전 정보와 대상 상품.
     *
     * <p>상품의 할인율은 {@code EventPricing} 이 조회 시점에 합성해 준다. 여기서 따로
     * 계산하지 않는다 — 그러면 같은 규칙이 두 곳에 생기고 언젠가 어긋난다.
     *
     * <p>진행 중이 아니면 404 다. 403 이면 "그 slug 에 기획전이 있긴 하다"가 새어 나가
     * 준비 중인 기획전의 이름을 미리 알 수 있다.
     */
    @GetMapping("/events/{slug}")
    public ResponseEntity<?> event(@PathVariable("slug") String slug) {
        try {
            Event event = eventService.publicOne(slug);
            return ResponseEntity.ok(new EventDetailView(
                    EventView.of(event),
                    stockAvailability.apply(productService.getProductsByIds(EventDtos.productIdsOf(event)))));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(message(e));
        }
    }

    /**
     * 기획전에 딸린 쿠폰 — <b>coupon-service 가</b> 발급 전에 읽는다.
     *
     * <p>사람이 부르는 화면용 API 가 아니다. 그래도 공개 경로에 둔 이유: 여기 담기는
     * 것은 이미 공개된 기획전의 정보뿐이고({@code /events/{slug}} 가 같은 것을 준다),
     * 내부 전용으로 만들려면 Feign 호출에 관리자 자격을 실어야 하는데 그건 서비스 간
     * 호출에 관리자 권한을 흘리는 더 나쁜 선택이다.
     *
     * <p>진행 중이 아니거나 쿠폰이 없으면 404 다 — coupon-service 가 상태를 다시 판정하지
     * 않게 한다. 같은 규칙이 두 곳에 있으면 언젠가 어긋나고, 그때 이미 종료된 기획전의
     * 쿠폰이 계속 나간다.
     */
    @GetMapping("/events/{slug}/coupon-offer")
    public ResponseEntity<?> eventCouponOffer(@PathVariable("slug") String slug) {
        try {
            return ResponseEntity.ok(eventService.couponOffer(slug));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(message(e));
        }
    }

    static Map<String, String> message(RuntimeException e) {
        return Map.of("message", ClientMessage.of(e));
    }
}
