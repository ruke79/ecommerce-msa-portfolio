package com.project.catalog_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.project.catalog_service.repository.jpa.data.EventRepository;
import com.project.catalog_service.repository.jpa.data.ProductRepository;
import com.project.common.dto.ProductDto;
import com.project.common.dto.ProductSkuDto;

/**
 * 기획전 할인이 상품 가격에 합성되는 규칙.
 *
 * <p>이 규칙들이 틀리면 <b>고객이 내는 금액이 틀린다.</b> 조용히 틀리는 종류라 — 예외도
 * 로그도 없고 숫자만 다르다 — 여기서 표로 고정한다.
 */
@DisplayName("기획전 할인 합성")
class EventPricingTest {

    private EventRepository eventRepository;
    private ProductRepository productRepository;
    private EventPricing pricing;

    @BeforeEach
    void setUp() {
        eventRepository = mock(EventRepository.class);
        productRepository = mock(ProductRepository.class);
        when(productRepository.findProductIdsByCategoryIds(any())).thenReturn(List.of());
        pricing = new EventPricing(eventRepository, productRepository);
    }

    /** 리포지터리가 돌려주는 {@code [productId, discountRate]} 행. */
    private void activeDiscounts(Object[]... rows) {
        when(eventRepository.activeProductDiscounts()).thenReturn(List.of(rows));
        when(eventRepository.activeCategoryDiscounts()).thenReturn(List.of());
        pricing.refresh();
    }

    @Nested
    @DisplayName("적용할 할인율 고르기")
    class ChoosingTheRate {

        /**
         * 🔴 이 선택이 이 기능의 핵심이다.
         *
         * <p>대안 둘 다 실제로 문제를 만든다:
         * <ul>
         *   <li><b>기획전이 덮어쓴다</b> — 상시 40% 상품을 20% 기획전에 넣으면 가격이
         *       <b>오른다.</b> 관리자는 세일을 추가했는데 고객은 비싸진 것을 본다.</li>
         *   <li><b>누적</b> — 40% + 20% 가 52% 가 된다. 아무도 그 숫자를 의도하지 않았다.</li>
         * </ul>
         */
        @Test
        @DisplayName("상시 할인이 더 크면 상시 할인을 쓴다 — 기획전에 넣었는데 비싸지면 안 된다")
        void keepsTheLargerBaseDiscount() {
            activeDiscounts(new Object[] { 42L, 20 });

            assertThat(pricing.effectiveDiscount(42L, 40)).isEqualTo(40);
        }

        @Test
        @DisplayName("기획전 할인이 더 크면 기획전 할인을 쓴다")
        void takesTheLargerEventDiscount() {
            activeDiscounts(new Object[] { 42L, 50 });

            assertThat(pricing.effectiveDiscount(42L, 10)).isEqualTo(50);
        }

        @Test
        @DisplayName("누적하지 않는다 — 40% 와 20% 는 52% 가 아니다")
        void doesNotStack() {
            activeDiscounts(new Object[] { 42L, 20 });

            assertThat(pricing.effectiveDiscount(42L, 40)).isNotEqualTo(52);
        }

        @Test
        @DisplayName("대상이 아닌 상품은 상시 할인 그대로")
        void untouchedWhenNotInAnyEvent() {
            activeDiscounts(new Object[] { 42L, 50 });

            assertThat(pricing.effectiveDiscount(99L, 10)).isEqualTo(10);
        }

        /**
         * 기획전 둘이 같은 상품을 겹쳐 잡을 수 있다(DB 가 막지 않는다 — 복합 키는 기획전
         * 하나 안에서만 유일하다). 더하면 30% 둘이 51% 가 된다.
         */
        @Test
        @DisplayName("기획전이 겹치면 높은 쪽 하나만 — 두 개를 더하지 않는다")
        void overlappingEventsTakeTheMax() {
            activeDiscounts(new Object[] { 42L, 30 }, new Object[] { 42L, 20 });

            assertThat(pricing.effectiveDiscount(42L, 0)).isEqualTo(30);
        }

        @Test
        @DisplayName("상품 id 가 없으면 상시 할인 그대로 — 터지지 않는다")
        void nullProductIdIsSafe() {
            activeDiscounts(new Object[] { 42L, 30 });

            assertThat(pricing.effectiveDiscount(null, 15)).isEqualTo(15);
        }
    }

    @Nested
    @DisplayName("DTO 에 얹기")
    class ApplyingToDto {

        private ProductDto product(String id, int... skuDiscounts) {
            List<ProductSkuDto> skus = java.util.Arrays.stream(skuDiscounts)
                    .mapToObj(d -> ProductSkuDto.builder().id("1").discount(d).build())
                    .toList();
            return ProductDto.builder().id(id).skus(new java.util.ArrayList<>(skus)).build();
        }

        @Test
        @DisplayName("SKU 마다 각자의 상시 할인과 비교해 얹는다")
        void appliesPerSku() {
            activeDiscounts(new Object[] { 42L, 30 });

            ProductDto dto = pricing.apply(product("42", 10, 50));

            // 10% SKU 는 기획전이 이기고, 50% SKU 는 상시 할인이 이긴다.
            assertThat(dto.getSkus()).extracting(ProductSkuDto::getDiscount)
                    .containsExactly(30, 50);
        }

        @Test
        @DisplayName("대상이 아니면 손대지 않는다")
        void leavesNonTargetsAlone() {
            activeDiscounts(new Object[] { 42L, 30 });

            ProductDto dto = pricing.apply(product("99", 10));

            assertThat(dto.getSkus()).extracting(ProductSkuDto::getDiscount).containsExactly(10);
        }

        /**
         * id 는 문자열이다(ProductDto). 숫자가 아닌 값이 들어와도 조회 전체가 500 이
         * 되면 안 된다 — 상품 하나 때문에 목록이 통째로 죽는다.
         */
        @Test
        @DisplayName("id 가 숫자가 아니어도 터지지 않는다")
        void nonNumericIdIsSafe() {
            activeDiscounts(new Object[] { 42L, 30 });

            assertThat(pricing.apply(product("abc", 10))).isNotNull();
        }

        @Test
        @DisplayName("null 과 빈 목록도 그대로 통과한다")
        void nullsAreSafe() {
            activeDiscounts(new Object[] { 42L, 30 });

            assertThat(pricing.apply((ProductDto) null)).isNull();
            assertThat(pricing.apply(ProductDto.builder().id("42").build())).isNotNull();
            assertThat(pricing.apply((List<ProductDto>) null)).isNull();
        }

        @Test
        @DisplayName("목록 전체에 적용한다")
        void appliesToAList() {
            activeDiscounts(new Object[] { 42L, 30 });

            List<ProductDto> products = pricing.apply(
                    new java.util.ArrayList<>(List.of(product("42", 0), product("99", 0))));

            assertThat(products.get(0).getSkus().get(0).getDiscount()).isEqualTo(30);
            assertThat(products.get(1).getSkus().get(0).getDiscount()).isZero();
        }
    }

    /**
     * 카테고리 단위 대상.
     *
     * <p>🔴 <b>펼친 결과를 저장하지 않는다.</b> 저장하면 그 순간 목록이 굳어, 기획전이
     * 시작된 뒤 그 카테고리에 등록된 상품이 빠진다 — 관리자는 카테고리를 걸었다고
     * 생각하는데 신상품만 정가로 남고, 그 사실은 고객 문의로야 드러난다.
     */
    @Nested
    @DisplayName("카테고리 펼치기")
    class ExpandingCategories {

        /** 카테고리 대상과, 그 카테고리에 속한 상품들을 준비한다. */
        private void activeCategories(List<Object[]> categoryRows, List<Object[]> productRows) {
            when(eventRepository.activeProductDiscounts()).thenReturn(List.of());
            when(eventRepository.activeCategoryDiscounts()).thenReturn(categoryRows);
            when(productRepository.findProductIdsByCategoryIds(any())).thenReturn(productRows);
            pricing.refresh();
        }

        @Test
        @DisplayName("카테고리에 속한 상품 전부에 할인이 걸린다")
        void categoryAppliesToItsProducts() {
            activeCategories(
                    List.<Object[]>of(new Object[] { 3L, 20 }),
                    List.<Object[]>of(new Object[] { 3L, 42L }, new Object[] { 3L, 43L }));

            assertThat(pricing.effectiveDiscount(42L, 0)).isEqualTo(20);
            assertThat(pricing.effectiveDiscount(43L, 0)).isEqualTo(20);
        }

        @Test
        @DisplayName("다른 카테고리의 상품은 건드리지 않는다")
        void otherCategoriesAreUntouched() {
            activeCategories(
                    List.<Object[]>of(new Object[] { 3L, 20 }),
                    List.<Object[]>of(new Object[] { 3L, 42L }));

            assertThat(pricing.effectiveDiscount(99L, 0)).isZero();
        }

        /**
         * "카테고리 전체 20%, 그중 이 상품만 40%" 가 자연스럽게 표현돼야 한다. 순서에
         * 따라 결과가 달라지면 관리자는 어느 쪽이 이길지 예측할 수 없다.
         */
        @Test
        @DisplayName("상품 지정이 카테고리보다 크면 상품 쪽이 이긴다")
        void productOverridesCategoryWhenDeeper() {
            when(eventRepository.activeProductDiscounts()).thenReturn(List.<Object[]>of(new Object[] { 42L, 40 }));
            when(eventRepository.activeCategoryDiscounts()).thenReturn(List.<Object[]>of(new Object[] { 3L, 20 }));
            when(productRepository.findProductIdsByCategoryIds(any()))
                    .thenReturn(List.<Object[]>of(new Object[] { 3L, 42L }));
            pricing.refresh();

            assertThat(pricing.effectiveDiscount(42L, 0)).isEqualTo(40);
        }

        @Test
        @DisplayName("카테고리가 상품 지정보다 크면 카테고리 쪽이 이긴다 — 순서와 무관하다")
        void categoryWinsWhenDeeper() {
            when(eventRepository.activeProductDiscounts()).thenReturn(List.<Object[]>of(new Object[] { 42L, 10 }));
            when(eventRepository.activeCategoryDiscounts()).thenReturn(List.<Object[]>of(new Object[] { 3L, 50 }));
            when(productRepository.findProductIdsByCategoryIds(any()))
                    .thenReturn(List.<Object[]>of(new Object[] { 3L, 42L }));
            pricing.refresh();

            assertThat(pricing.effectiveDiscount(42L, 0)).isEqualTo(50);
        }

        @Test
        @DisplayName("카테고리 둘이 겹쳐도 높은 쪽 하나만")
        void overlappingCategoriesTakeTheMax() {
            activeCategories(
                    List.<Object[]>of(new Object[] { 3L, 20 }, new Object[] { 3L, 35 }),
                    List.<Object[]>of(new Object[] { 3L, 42L }));

            assertThat(pricing.effectiveDiscount(42L, 0)).isEqualTo(35);
        }

        /**
         * 카테고리 대상이 없는데도 상품을 조회하면, 대상 없는 기획전이 1분마다 카탈로그
         * 전체를 읽는 질의를 만든다.
         */
        @Test
        @DisplayName("카테고리 대상이 없으면 상품을 조회하지 않는다")
        void noCategoriesMeansNoProductQuery() {
            when(eventRepository.activeProductDiscounts()).thenReturn(List.<Object[]>of(new Object[] { 42L, 10 }));
            when(eventRepository.activeCategoryDiscounts()).thenReturn(List.of());

            pricing.refresh();

            verify(productRepository, never()).findProductIdsByCategoryIds(any());
        }

        @Test
        @DisplayName("카테고리 수만큼 질의하지 않는다 — 한 번에 묶어 읽는다")
        void queriesCategoriesInOneCall() {
            activeCategories(
                    List.<Object[]>of(new Object[] { 3L, 20 }, new Object[] { 4L, 30 }, new Object[] { 5L, 10 }),
                    List.of());

            verify(productRepository).findProductIdsByCategoryIds(any());
        }

        @Test
        @DisplayName("카테고리에 상품이 없어도 터지지 않는다")
        void emptyCategoryIsSafe() {
            activeCategories(List.<Object[]>of(new Object[] { 3L, 20 }), List.of());

            assertThat(pricing.activeProductCount()).isZero();
        }
    }

    @Nested
    @DisplayName("갱신")
    class Refreshing {

        @Test
        @DisplayName("끝난 기획전의 할인은 갱신하면 사라진다")
        void endedEventsDisappearOnRefresh() {
            activeDiscounts(new Object[] { 42L, 30 });
            assertThat(pricing.effectiveDiscount(42L, 0)).isEqualTo(30);

            // 기획전이 끝나 ACTIVE 가 하나도 없다.
            activeDiscounts();

            assertThat(pricing.effectiveDiscount(42L, 0)).isZero();
            assertThat(pricing.activeProductCount()).isZero();
        }

        @Test
        @DisplayName("한 번도 갱신하지 않았으면 아무 할인도 얹지 않는다 — 기동 직후")
        void emptyBeforeFirstRefresh() {
            EventPricing fresh = new EventPricing(eventRepository, productRepository);

            assertThat(fresh.effectiveDiscount(42L, 10)).isEqualTo(10);
        }
    }

    /**
     * 🐛 회귀 대상: <b>백엔드의 할인가 계산식이 틀려 있었다.</b>
     *
     * <p>{@code price - price / discount} — "정가에서 정가를 할인율로 나눈 값을 뺀다"는
     * 뜻이라 아무 의미가 없고, <b>할인율이 클수록 할인 폭이 작아졌다.</b>
     *
     * <p>프론트엔드(`util/pricing.js`)는 이미 고쳐져 있었고 <b>이 경로만 남아 있었다.</b>
     * 그래서 상세 화면은 50% 할인가를 보여 주는데 장바구니에 담으면 2% 할인가가 담겼다 —
     * 같은 상품의 가격이 화면마다 달랐다. 기획전 할인도 이 계산을 지난다.
     */
    @Nested
    @DisplayName("할인가 계산")
    class DiscountedPrice {

        private BigDecimal at(String price, int percent) {
            return ProductService.discounted(new BigDecimal(price), percent);
        }

        @Test
        @DisplayName("50% 할인이면 절반이다 — 예전 식은 2% 만 깎았다")
        void halfOff() {
            assertThat(at("10000", 50)).isEqualByComparingTo("5000.00");
        }

        @Test
        @DisplayName("70% 할인이 50% 할인보다 싸다 — 예전 식은 반대였다")
        void deeperDiscountCostsLess() {
            assertThat(at("10000", 70)).isLessThan(at("10000", 50));
        }

        @Test
        @DisplayName("할인이 없으면 정가 그대로")
        void noDiscount() {
            assertThat(at("10000", 0)).isEqualByComparingTo("10000");
        }

        @Test
        @DisplayName("음수 할인율은 무시한다 — 가격이 정가보다 오르면 안 된다")
        void negativeIsIgnored() {
            assertThat(at("10000", -20)).isEqualByComparingTo("10000");
        }

        /** 데이터 오류로 100 을 넘는 값이 들어와도 주문 금액이 마이너스가 되면 안 된다. */
        @Test
        @DisplayName("100% 를 넘겨도 가격이 음수가 되지 않는다")
        void neverNegative() {
            assertThat(at("10000", 150)).isEqualByComparingTo("0.00");
            assertThat(at("10000", 100)).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("소수점 둘째 자리에서 반올림한다 — 통화 금액의 관례")
        void roundsToCents() {
            assertThat(at("9.99", 33)).isEqualByComparingTo("6.69");
        }

        @Test
        @DisplayName("가격이 없으면 그대로 돌려준다")
        void nullPriceIsSafe() {
            assertThat(ProductService.discounted(null, 50)).isNull();
        }
    }
}
