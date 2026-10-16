package com.project.catalog_service.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.project.catalog_service.model.Event;
import com.project.catalog_service.model.EventProduct;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 이벤트 / 기획전 DTO. */
public final class EventDtos {

    private EventDtos() {
    }

    /** 대상 카테고리 하나. */
    public record EventCategoryRequest(
            @NotNull Long categoryId,
            @Min(0) @Max(100) int discountRate) {
    }

    /** 대상 상품 하나. */
    public record EventProductRequest(
            @NotNull Long productId,
            /*
             * 0~100 퍼센트.
             *
             * 상한을 100 으로 두는 것은 형식 검증이 아니라 **가격 보호**다. 101 이 들어오면
             * 할인가가 음수가 되어 주문 금액이 마이너스가 된다. 하한도 마찬가지로 0 미만이면
             * 가격이 정가보다 올라간다.
             */
            @Min(0) @Max(100) int discountRate) {
    }

    /**
     * 기획전 생성·수정.
     *
     * <p>{@code status} 를 받지 않는다. 상태는 스케줄러가 시각에 맞춰 넘기는 것이고,
     * 관리자는 <b>확정/취소</b>만 한다({@code publish} 플래그). 임의의 상태를 직접 쓰게 하면
     * "끝난 기획전을 ACTIVE 로 되돌리기" 같은 조합이 생기고, 그때 할인이 어떻게 되는지
     * 아무도 정의하지 않았다.
     */
    public record EventRequest(
            @NotBlank @Size(max = 200) String title,
            @Size(max = 200) String slug,
            @Size(max = 2000) String description,
            @Size(max = 1000) String bannerImageUrl,
            @Size(max = 1000) String linkUrl,
            LocalDateTime startsAt,
            LocalDateTime endsAt,
            /** true 면 DRAFT → SCHEDULED. false 면 초안으로 되돌린다. */
            Boolean publish,
            // 🔒 @Valid — 🐛 예전에는 없어서 안쪽 레코드의 @Min(0) @Max(100) 이 실행되지 않았다. 할인율 150 이 저장되면 Discount 가
            //    100 에서 잘라 0원 판매가 됐다(배지는 "-150%"). [JA] @Valid がなく内側の 0〜100 検証が走らなかった(150% → 0 円販売)。
            List<@jakarta.validation.Valid EventProductRequest> products,
            List<@jakarta.validation.Valid EventCategoryRequest> categories,
            /**
             * 쿠폰을 받을 수 있는 고객군(ALL · NEW_USER · EXISTING_USER).
             *
             * <p>🔴 진열가는 가르지 않는다 — {@code AudienceSegment} 주석 참조.
             */
            String audience,
            /** 비우면 이 기획전에는 쿠폰이 없다. */
            @Size(max = 120) String couponName,
            @Min(0) @Max(100) Integer couponDiscount,
            @Min(1) @Max(365) Integer couponValidDays) {
    }

    /** 관리자 목록·상세. 초안과 끝난 것까지 전부 보인다. */
    public record EventAdminView(
            Long id,
            String title,
            String slug,
            String description,
            String bannerImageUrl,
            String linkUrl,
            String status,
            LocalDateTime startsAt,
            LocalDateTime endsAt,
            LocalDateTime createdAt,
            int productCount,
            List<EventProductRequest> products,
            List<EventCategoryRequest> categories,
            String audience,
            String couponName,
            Integer couponDiscount,
            Integer couponValidDays) {

        public static EventAdminView of(Event e) {
            return new EventAdminView(
                    e.getEventId(),
                    e.getTitle(),
                    e.getSlug(),
                    e.getDescription(),
                    e.getBannerImageUrl(),
                    e.getLinkUrl(),
                    e.getStatus().name(),
                    e.getStartsAt(),
                    e.getEndsAt(),
                    e.getCreatedAt(),
                    // 카테고리까지 세지 않는다 — 카테고리는 상품으로 펼쳐지므로 실제 대상
                    // 수는 조회 시점에야 정해진다. 여기서 합치면 목록의 "대상 N개" 가
                    // 화면마다 다른 뜻이 된다.
                    e.getProducts().size(),
                    e.getProducts().stream()
                            .map(p -> new EventProductRequest(p.getProductId(), p.getDiscountRate()))
                            .toList(),
                    e.getCategories().stream()
                            .map(c -> new EventCategoryRequest(c.getCategoryId(), c.getDiscountRate()))
                            .toList(),
                    e.getAudience().name(),
                    e.getCouponName(),
                    e.getCouponDiscount(),
                    e.getCouponValidDays());
        }
    }

    /**
     * 기획전에 딸린 쿠폰 — coupon-service 가 발급 전에 읽는다.
     *
     * <p><b>왜 coupon-service 가 이걸 물어보는가.</b> 기획전은 catalog 의 것이고 쿠폰은
     * coupon 의 것이다. 카프카로 미리 밀어 두면 기획전이 일찍 종료됐을 때 쿠폰 쪽이
     * 모르고 계속 발급한다. 수령은 드문 동작이라 <b>받을 때 물어보는</b> 편이 항상 옳고
     * 충분히 싸다.
     *
     * <p>진행 중이 아니거나 쿠폰이 없는 기획전은 404 다 — coupon-service 가 상태를 다시
     * 판정하지 않게 한다. 같은 규칙이 두 곳에 있으면 언젠가 어긋난다.
     */
    public record EventCouponOffer(
            Long eventId,
            String slug,
            String title,
            String audience,
            String couponName,
            int couponDiscount,
            int couponValidDays,
            /** 쿠폰이 적용될 범위 — ALL · CATEGORY · PRODUCT. */
            String couponScope,
            /** 범위가 ALL 이 아닐 때의 대상 id 목록(문자열). */
            List<String> couponTargets) {
    }

    /**
     * 공개 목록 한 줄.
     *
     * <p>대상 상품 목록을 싣지 않는다 — 목록 화면은 배너와 제목만 그린다. 기획전마다
     * 상품 수백 개를 실어 보내면 응답이 그만큼 커지고, 정작 화면은 그걸 쓰지 않는다.
     */
    public record EventView(
            Long id,
            String title,
            String slug,
            String description,
            String bannerImageUrl,
            String linkUrl,
            LocalDateTime startsAt,
            LocalDateTime endsAt,
            /**
             * 받아 갈 쿠폰이 있으면 그 이름과 할인율. 없으면 둘 다 null.
             *
             * <p>화면이 "쿠폰 받기" 버튼을 그릴지 정하는 데 쓴다. 자격(고객군) 판정은
             * 여기서 하지 않는다 — 이 응답은 비회원도 받고 캐시될 수 있다. 받기를 눌렀을
             * 때 coupon-service 가 판정하고, 대상이 아니면 그때 이유를 말해 준다.
             */
            String couponName,
            Integer couponDiscount,
            /** 쿠폰 대상 고객군. 화면이 "신규 가입자 전용" 같은 안내를 그린다. */
            String audience) {

        public static EventView of(Event e) {
            return new EventView(
                    e.getEventId(),
                    e.getTitle(),
                    e.getSlug(),
                    e.getDescription(),
                    e.getBannerImageUrl(),
                    // 비워 두면 /event/<slug> 로 간다. 화면이 매번 같은 규칙을 다시 쓰지
                    // 않도록 여기서 채워 내려 준다.
                    e.getLinkUrl() != null && !e.getLinkUrl().isBlank()
                            ? e.getLinkUrl()
                            : "event/" + e.getSlug(),
                    e.getStartsAt(),
                    e.getEndsAt(),
                    e.offersCoupon() ? e.getCouponName() : null,
                    e.offersCoupon() ? e.getCouponDiscount() : null,
                    e.getAudience().name());
        }
    }

    /** 공개 상세 — 기획전 정보 + 대상 상품. */
    public record EventDetailView(
            EventView event,
            List<com.project.common.dto.ProductDto> products) {
    }

    /** 대상 상품 id 만 꺼낸다. */
    public static List<Long> productIdsOf(Event e) {
        return e.getProducts().stream().map(EventProduct::getProductId).toList();
    }
}
