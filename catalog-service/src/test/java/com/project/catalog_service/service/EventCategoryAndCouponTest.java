package com.project.catalog_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.project.catalog_service.dto.EventDtos.EventAdminView;
import com.project.catalog_service.dto.EventDtos.EventCategoryRequest;
import com.project.catalog_service.dto.EventDtos.EventCouponOffer;
import com.project.catalog_service.dto.EventDtos.EventProductRequest;
import com.project.catalog_service.dto.EventDtos.EventRequest;
import com.project.catalog_service.model.Category;
import com.project.catalog_service.model.Event;
import com.project.catalog_service.model.EventCategory;
import com.project.catalog_service.model.EventProduct;
import com.project.catalog_service.model.EventStatus;
import com.project.catalog_service.model.Product;
import com.project.catalog_service.repository.jpa.data.CategoryRepository;
import com.project.catalog_service.repository.jpa.data.EventRepository;
import com.project.catalog_service.repository.jpa.data.ProductRepository;
import com.project.common.constants.coupon.CouponScope;
import com.project.common.constants.event.AudienceSegment;

/**
 * 기획전의 세 가지 확장 — 카테고리 단위 대상 · 대상 고객군 · 딸린 쿠폰.
 *
 * <p>셋은 따로 보이지만 <b>하나의 결정</b>에서 나온다: 세그먼트 할인을 진열가가 아니라
 * 쿠폰으로 준다는 것. 그래서 고객군은 쿠폰에만 붙고, 쿠폰의 적용 범위는 기획전의 대상
 * (상품 또는 카테고리)을 그대로 물려받는다.
 */
@DisplayName("기획전 — 카테고리 · 고객군 · 쿠폰")
class EventCategoryAndCouponTest {

    private EventRepository eventRepository;
    private ProductRepository productRepository;
    private CategoryRepository categoryRepository;
    private EventService service;

    @BeforeEach
    void setUp() {
        eventRepository = mock(EventRepository.class);
        productRepository = mock(ProductRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        when(eventRepository.save(any(Event.class))).thenAnswer(i -> i.getArgument(0));
        when(eventRepository.existsBySlug(anyString())).thenReturn(false);
        service = new EventService(eventRepository, productRepository, categoryRepository);
    }

    private void productsExist(Long... ids) {
        List<Product> products = java.util.Arrays.stream(ids)
                .<Product>map(id -> Product.builder().productId(id).build())
                .toList();
        when(productRepository.findAllById(any())).thenReturn(products);
    }

    private void categoriesExist(Long... ids) {
        List<Category> categories = java.util.Arrays.stream(ids)
                .<Category>map(id -> Category.builder().categoryId(id).build())
                .toList();
        when(categoryRepository.findAllById(any())).thenReturn(categories);
    }

    private static EventRequest with(List<EventProductRequest> products,
            List<EventCategoryRequest> categories, String audience,
            String couponName, Integer couponDiscount, Integer validDays) {
        return new EventRequest("가을 세일", null, null, null, null, null, null, null,
                products, categories, audience, couponName, couponDiscount, validDays);
    }

    @Nested
    @DisplayName("카테고리 단위 대상")
    class CategoryTargets {

        @Test
        @DisplayName("카테고리를 담을 수 있다")
        void acceptsCategories() {
            categoriesExist(3L);

            EventAdminView view = service.create(
                    with(List.of(), List.of(new EventCategoryRequest(3L, 20)), null, null, null, null));

            assertThat(view.categories()).hasSize(1);
            assertThat(view.categories().get(0).discountRate()).isEqualTo(20);
        }

        /**
         * 없는 카테고리를 그냥 저장하면 증상이 "그 카테고리만 할인이 안 걸린다"로
         * 나타나는데, 화면의 대상 목록에는 멀쩡히 들어 있어 무엇이 틀렸는지 알 수 없다.
         */
        @Test
        @DisplayName("없는 카테고리는 거절한다")
        void rejectsUnknownCategories() {
            categoriesExist(3L);   // 99 는 없다

            assertThatThrownBy(() -> service.create(with(List.of(),
                    List.of(new EventCategoryRequest(3L, 20), new EventCategoryRequest(99L, 20)),
                    null, null, null, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("99");
        }

        @Test
        @DisplayName("같은 카테고리가 두 번 오면 하나로 합친다")
        void deduplicates() {
            categoriesExist(3L);

            EventAdminView view = service.create(with(List.of(),
                    List.of(new EventCategoryRequest(3L, 20), new EventCategoryRequest(3L, 40)),
                    null, null, null, null));

            assertThat(view.categories()).hasSize(1);
            assertThat(view.categories().get(0).discountRate()).isEqualTo(40);
        }

        /** 같은 카테고리 안에서 일부만 다른 할인율을 주는 흔한 조합이다. */
        @Test
        @DisplayName("상품과 카테고리를 함께 담을 수 있다")
        void productsAndCategoriesCoexist() {
            productsExist(42L);
            categoriesExist(3L);

            EventAdminView view = service.create(with(
                    List.of(new EventProductRequest(42L, 40)),
                    List.of(new EventCategoryRequest(3L, 20)),
                    null, null, null, null));

            assertThat(view.products()).hasSize(1);
            assertThat(view.categories()).hasSize(1);
        }

        /**
         * 카테고리는 상품으로 펼쳐지므로 실제 대상 수는 조회 시점에야 정해진다. 여기에
         * 합치면 목록의 "대상 N개" 가 화면마다 다른 뜻이 된다.
         */
        @Test
        @DisplayName("목록의 대상 수는 상품만 센다")
        void productCountExcludesCategories() {
            productsExist(42L);
            categoriesExist(3L);

            EventAdminView view = service.create(with(
                    List.of(new EventProductRequest(42L, 40)),
                    List.of(new EventCategoryRequest(3L, 20)),
                    null, null, null, null));

            assertThat(view.productCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("카테고리가 없으면 조회하지 않는다")
        void noCategoriesMeansNoLookup() {
            productsExist(42L);

            service.create(with(List.of(new EventProductRequest(42L, 40)), List.of(),
                    null, null, null, null));

            verify(categoryRepository, never()).findAllById(any());
        }
    }

    @Nested
    @DisplayName("딸린 쿠폰")
    class Coupon {

        @Test
        @DisplayName("이름과 할인율이 있으면 쿠폰이 붙는다")
        void attachesCoupon() {
            EventAdminView view = service.create(
                    with(List.of(), List.of(), null, "가을 추가 10%", 10, 7));

            assertThat(view.couponName()).isEqualTo("가을 추가 10%");
            assertThat(view.couponDiscount()).isEqualTo(10);
            assertThat(view.couponValidDays()).isEqualTo(7);
        }

        /**
         * 🔴 0% 쿠폰이 발급되면 사용자는 "쿠폰 받기"를 눌러 받아 놓고도 결제 때 아무 일이
         * 일어나지 않는 것을 본다.
         */
        @Test
        @DisplayName("이름만 있고 할인율이 없으면 거절한다")
        void nameWithoutDiscountIsRejected() {
            assertThatThrownBy(() -> service.create(
                    with(List.of(), List.of(), null, "이름뿐인 쿠폰", null, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("이름과 할인율");
        }

        /** 이름이 곧 키다(coupon 의 uk_coupon_name_user_id). 없으면 식별할 수 없다. */
        @Test
        @DisplayName("할인율만 있고 이름이 없어도 거절한다")
        void discountWithoutNameIsRejected() {
            assertThatThrownBy(() -> service.create(
                    with(List.of(), List.of(), null, null, 10, null)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        /** 기간을 정하지 않았다고 받자마자 만료된 쿠폰이 나가면 안 된다. */
        @Test
        @DisplayName("유효기간을 비우면 기본값을 쓴다")
        void defaultsTheValidity() {
            EventAdminView view = service.create(
                    with(List.of(), List.of(), null, "쿠폰", 10, null));

            assertThat(view.couponValidDays()).isEqualTo(EventService.DEFAULT_COUPON_VALID_DAYS);
        }

        @Test
        @DisplayName("0 이하의 유효기간도 기본값으로 바꾼다")
        void zeroValidityIsReplaced() {
            EventAdminView view = service.create(
                    with(List.of(), List.of(), null, "쿠폰", 10, 0));

            assertThat(view.couponValidDays()).isEqualTo(EventService.DEFAULT_COUPON_VALID_DAYS);
        }

        /** 저장할 때 모르는 고객군을 ALL 로 읽으면 VIP 전용 쿠폰이 전원에게 열린다. */
        @Test
        @DisplayName("🔴 모르는 고객군(오타)은 거절한다 — 조용히 ALL(전원)로 저장되지 않는다")
        void unknownAudienceIsRejected() {
            assertThatThrownBy(() -> service.create(
                    with(List.of(), List.of(), "VIP", "VIP 전용 20%", 20, 7)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("알 수 없는 대상 고객군");
        }

        @Test
        @DisplayName("쿠폰이 없으면 고객군은 ALL 로 되돌린다 — 아무것도 전용이 아닌데 전용이라고 보이면 안 된다")
        void audienceIsResetWithoutACoupon() {
            EventAdminView view = service.create(
                    with(List.of(), List.of(), "NEW_USER", null, null, null));

            assertThat(view.audience()).isEqualTo("ALL");
        }

        @Test
        @DisplayName("쿠폰이 있으면 고객군이 유지된다")
        void audienceIsKeptWithACoupon() {
            EventAdminView view = service.create(
                    with(List.of(), List.of(), "NEW_USER", "신규 전용 20%", 20, null));

            assertThat(view.audience()).isEqualTo("NEW_USER");
        }
    }

    @Nested
    @DisplayName("쿠폰 조건 내려주기")
    class CouponOffer {

        private Event active(String couponName, Integer discount,
                List<EventProduct> products, List<EventCategory> categories) {
            Event event = Event.builder()
                    .eventId(7L).title("가을 세일").slug("autumn").status(EventStatus.ACTIVE)
                    .audience(AudienceSegment.NEW_USER)
                    .couponName(couponName).couponDiscount(discount).couponValidDays(7)
                    .build();
            event.replaceProducts(products);
            event.replaceCategories(categories);
            when(eventRepository.findBySlug("autumn")).thenReturn(Optional.of(event));
            return event;
        }

        /**
         * 🔴 "이 기획전의 쿠폰" 이 기획전과 무관한 상품에 먹으면 관리자가 의도한 할인
         * 폭을 넘는다. 쿠폰 범위는 기획전 대상을 그대로 물려받아야 한다.
         */
        @Test
        @DisplayName("상품을 지정한 기획전의 쿠폰은 그 상품에만 쓸 수 있다")
        void productScopedCoupon() {
            active("쿠폰", 10, List.of(new EventProduct(42L, 30)), List.of());

            EventCouponOffer offer = service.couponOffer("autumn");

            assertThat(offer.couponScope()).isEqualTo(CouponScope.PRODUCT.name());
            assertThat(offer.couponTargets()).containsExactly("42");
        }

        @Test
        @DisplayName("카테고리만 지정했으면 카테고리 범위다")
        void categoryScopedCoupon() {
            active("쿠폰", 10, List.of(), List.of(new EventCategory(3L, 20)));

            EventCouponOffer offer = service.couponOffer("autumn");

            assertThat(offer.couponScope()).isEqualTo(CouponScope.CATEGORY.name());
            assertThat(offer.couponTargets()).containsExactly("3");
        }

        /** 둘 다 있으면 좁은 쪽 — 카테고리로 넓히면 상품을 고른 의도가 사라진다. */
        @Test
        @DisplayName("상품과 카테고리가 둘 다 있으면 상품 범위다")
        void productWinsOverCategory() {
            active("쿠폰", 10, List.of(new EventProduct(42L, 30)), List.of(new EventCategory(3L, 20)));

            assertThat(service.couponOffer("autumn").couponScope())
                    .isEqualTo(CouponScope.PRODUCT.name());
        }

        @Test
        @DisplayName("대상이 없으면 전 상품에 쓸 수 있다 — 기획전이 사실상 쿠폰 배포 수단이다")
        void noTargetsMeansAllScope() {
            active("쿠폰", 10, List.of(), List.of());

            EventCouponOffer offer = service.couponOffer("autumn");

            assertThat(offer.couponScope()).isEqualTo(CouponScope.ALL.name());
            assertThat(offer.couponTargets()).isEmpty();
        }

        @Test
        @DisplayName("고객군을 함께 내려준다")
        void carriesTheAudience() {
            active("쿠폰", 10, List.of(), List.of());

            assertThat(service.couponOffer("autumn").audience()).isEqualTo("NEW_USER");
        }

        @Test
        @DisplayName("쿠폰이 없는 기획전은 조건을 내려주지 않는다")
        void noCouponMeansNoOffer() {
            active(null, null, List.of(), List.of());

            assertThatThrownBy(() -> service.couponOffer("autumn"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        /**
         * 🔴 coupon-service 가 상태를 다시 판정하지 않게 한다 — 같은 규칙이 두 곳에
         * 있으면 언젠가 어긋나고, 그때 이미 종료된 기획전의 쿠폰이 계속 나간다.
         */
        @Test
        @DisplayName("진행 중이 아니면 조건 자체를 주지 않는다")
        void endedEventOffersNothing() {
            Event event = Event.builder()
                    .eventId(7L).slug("autumn").status(EventStatus.ENDED)
                    .couponName("쿠폰").couponDiscount(10).build();
            when(eventRepository.findBySlug("autumn")).thenReturn(Optional.of(event));

            assertThatThrownBy(() -> service.couponOffer("autumn"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("고객군 판정")
    class Matching {

        @Test
        @DisplayName("ALL 은 세그먼트 목록과 무관하게 통과한다")
        void allAlwaysMatches() {
            assertThat(AudienceSegment.ALL.matches(List.of())).isTrue();
            assertThat(AudienceSegment.ALL.matches(null)).isTrue();
        }

        @Test
        @DisplayName("목록에 있으면 통과, 없으면 거절")
        void membership() {
            assertThat(AudienceSegment.NEW_USER.matches(List.of("NEW_USER"))).isTrue();
            assertThat(AudienceSegment.NEW_USER.matches(List.of("EXISTING_USER"))).isFalse();
        }

        /** user-service 가 소문자로 내려보내도 판정이 달라지면 안 된다. */
        @Test
        @DisplayName("대소문자와 공백을 가리지 않는다")
        void lenientComparison() {
            assertThat(AudienceSegment.NEW_USER.matches(List.of(" new_user "))).isTrue();
        }

        @Test
        @DisplayName("세그먼트를 모르면(빈 목록) 대상이 아니다 — 판정 실패 시 주지 않는다")
        void unknownMeansNotEligible() {
            assertThat(AudienceSegment.NEW_USER.matches(List.of())).isFalse();
            assertThat(AudienceSegment.NEW_USER.matches(null)).isFalse();
            assertThat(AudienceSegment.NEW_USER.matches(java.util.Arrays.asList((String) null))).isFalse();
        }
    }
}
