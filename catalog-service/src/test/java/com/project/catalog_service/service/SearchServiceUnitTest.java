package com.project.catalog_service.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.project.catalog_service.repository.jpa.data.CategoryRepository;
import com.project.catalog_service.repository.jpa.data.ProductDetailsRepository;
import com.project.catalog_service.repository.jpa.data.ProductRepository;
import com.project.catalog_service.repository.jpa.data.ProductSkuRepository;
import com.project.catalog_service.repository.jpa.data.SubcategoryRepository;
import com.project.common.dto.catalog.request.SearchParamsRequest;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;

/**
 * 검색 파라미터 방어 로직 회귀 테스트 (docs/06 CAT-3, CAT-4).
 *
 * 기존 버그:
 *  - page=0(기본값)일 때 PageRequest.of(-1, ...) → IllegalArgumentException(500)
 *  - pageSize 무제한 → deep pagination/대량 조회 DoS
 *  - category에 이름(문자열)이 오면 Long.parseLong → NumberFormatException(500)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SearchServiceUnitTest {

    @Mock CategoryRepository categoryRepository;
    @Mock SubcategoryRepository subCategoryRepository;
    @Mock ProductRepository productRepository;
    @Mock ProductSkuRepository productSkuRepository;
    @Mock ProductDetailsRepository productDetailsRepository;
    @Mock JPAQueryFactory queryFactory;
    EventPricing eventPricing;

    SearchService service;

    @SuppressWarnings("rawtypes")
    JPAQuery countQuery;
    @SuppressWarnings("rawtypes")
    JPAQuery listQuery;

    /** SearchParamsRequest 는 protected 기본 생성자만 있어 테스트용 하위 클래스로 생성한다. */
    static class TestParams extends SearchParamsRequest {
        TestParams() { super(); }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @BeforeEach
    void setUp() {
        eventPricing = mock(EventPricing.class);
        // 기획전이 없으면 상시 할인 그대로 — 실제 EventPricing.effectiveDiscount 와 같은 규칙.
        when(eventPricing.effectiveDiscount(any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(i -> i.getArgument(1));
        service = new SearchService(categoryRepository, subCategoryRepository,
                productRepository, productSkuRepository, productDetailsRepository, queryFactory,
                eventPricing);

        // querydsl 체이닝(빌더 패턴)은 RETURNS_SELF 로 체인을 그대로 흘려보낸다.
        countQuery = mock(JPAQuery.class, withSettings().defaultAnswer(Answers.RETURNS_SELF));
        when(countQuery.fetchOne()).thenReturn(0L);
        listQuery = mock(JPAQuery.class, withSettings().defaultAnswer(Answers.RETURNS_SELF));
        when(listQuery.fetch()).thenReturn(Collections.emptyList());

        when(queryFactory.select(any(com.querydsl.core.types.Expression.class))).thenReturn(countQuery);
        when(queryFactory.selectFrom(any())).thenReturn(listQuery);

        when(productSkuRepository.findProductIDBySizeAndPriceAndColor(any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());
        when(productSkuRepository.findColorsByProductId(any())).thenReturn(Collections.emptyList());
        when(productSkuRepository.findSizesByProductId(any())).thenReturn(Collections.emptyList());
        when(productRepository.findBrandsByCategoryName(any())).thenReturn(Collections.emptyList());
        when(productRepository.findBrandsByCategoryId(any())).thenReturn(Collections.emptyList());
        when(productRepository.findProductIDsByCategoryId(any())).thenReturn(Collections.emptyList());
        when(categoryRepository.findAll()).thenReturn(Collections.emptyList());
        when(subCategoryRepository.findAll()).thenReturn(Collections.emptyList());
        when(productDetailsRepository.findDistinctAll()).thenReturn(Collections.emptyList());
        when(productDetailsRepository.findDistinctAllByProductProductIdIn(any()))
                .thenReturn(Collections.emptyList());
    }

    @Test
    @DisplayName("page=0/pageSize=0 기본 요청이 500 없이 안전한 기본값(offset 0, limit 20)으로 클램프된다")
    void defaultPageParams_areClamped() {
        TestParams params = new TestParams(); // page=0, pageSize=0

        assertThatCode(() -> service.searchProducts(params, SearchEngine.POSTGRES)).doesNotThrowAnyException();

        verify(listQuery).offset(0L);
        verify(listQuery).limit(20L);
    }

    @Test
    @DisplayName("pageSize 폭주(99999)는 상한 100으로 클램프된다 (deep pagination DoS 방지)")
    void oversizedPageSize_isCapped() {
        TestParams params = new TestParams();
        params.setPage(1);
        params.setPageSize(99999);

        service.searchProducts(params, SearchEngine.POSTGRES);

        verify(listQuery).limit(100L);
    }

    @Test
    @DisplayName("category에 이름(문자열)이 와도 NumberFormatException 없이 이름 검색 경로를 탄다")
    void categoryByName_doesNotThrow() {
        TestParams params = new TestParams();
        params.setPage(1);
        params.setPageSize(10);
        params.setCategory("shoes");

        assertThatCode(() -> service.searchProducts(params, SearchEngine.POSTGRES)).doesNotThrowAnyException();
        verify(productRepository).findBrandsByCategoryName("shoes");
    }

    @Test
    @DisplayName("ES 활성화 시 텍스트 검색은 ES 관련도 검색(searchProductIds)을 사용한다 (docs/07 R-C1)")
    void esEnabled_textSearch_usesEs() {
        EsProductSearchService es = mock(EsProductSearchService.class);
        org.mockito.Mockito.when(es.searchProductIds(org.mockito.ArgumentMatchers.eq("shoes"),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(java.util.List.of(1L, 2L));
        service.esProductSearchService = es;

        TestParams params = new TestParams();
        params.setPage(1);
        params.setPageSize(10);
        params.setSearch("shoes");

        assertThatCode(() -> service.searchProducts(params, SearchEngine.ES)).doesNotThrowAnyException();
        verify(es).searchProductIds(org.mockito.ArgumentMatchers.eq("shoes"),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("ES 장애 시 예외 없이 기존 LIKE 경로로 폴백한다")
    void esFailure_fallsBackToLike() {
        EsProductSearchService es = mock(EsProductSearchService.class);
        org.mockito.Mockito.when(es.searchProductIds(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new RuntimeException("es down"));
        service.esProductSearchService = es;

        TestParams params = new TestParams();
        params.setPage(1);
        params.setPageSize(10);
        params.setSearch("shoes");

        assertThatCode(() -> service.searchProducts(params, SearchEngine.ES)).doesNotThrowAnyException();
    }

    /**
     * 🔴 ES 가 이 배포에 있어도(빈 존재), 이 검색의 엔진이 POSTGRES 면 ES 를 부르지 않는다(docs/74).
     *
     * <p>예전에는 빈이 있으면 무조건 ES 였다. 기능 플래그로 일부 검색만 ES 로 보내려면 이 분기가 엔진을 따라야
     * 한다 — 따르지 않으면 플래그를 0% 로 내려도 모든 검색이 ES 로 가고, 추적의 변형 태그(postgres)는 거짓말이 된다.
     */
    @Test
    @DisplayName("🔴 ES 빈이 있어도 엔진이 POSTGRES 면 LIKE 경로로 간다 (기능 플래그 docs/74)")
    void esDeployedButEnginePostgres_usesLike() {
        EsProductSearchService es = mock(EsProductSearchService.class);
        service.esProductSearchService = es;

        TestParams params = new TestParams();
        params.setPage(1);
        params.setPageSize(10);
        params.setSearch("shoes");

        service.searchProducts(params, SearchEngine.POSTGRES);

        org.mockito.Mockito.verifyNoInteractions(es);
        org.mockito.ArgumentCaptor<com.querydsl.core.types.Predicate> captor =
                org.mockito.ArgumentCaptor.forClass(com.querydsl.core.types.Predicate.class);
        verify(listQuery).where(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().toString()).containsIgnoringCase("like");
    }

    /**
     * 🔴 회귀 대상: <b>맞는 게 하나도 없으면 카탈로그 전체가 나왔다.</b>
     *
     * <p>예전 조건은 {@code if (ids != null && !ids.isEmpty())} 였다. 가격·색상·사이즈
     * 필터가 아무것도 못 찾으면 그 조건을 <b>통째로 버린다</b> — "맞는 게 없다" 가
     * "제한이 없다" 로 뒤집힌다.
     *
     * <p>화면에서는 이렇게 보인다: 가격대를 좁힐수록 상품이 줄다가, 맞는 게 없는 구간을
     * 고르는 순간 <b>전체 상품</b>이 돌아온다. "가격 필터가 동작하지 않는다" 는 보고의
     * 절반이 이것이다(나머지 절반은 프론트가 빈 상한을 0 으로 보내던 것).
     *
     * <p>여기서 보는 것은 <b>질의가 실행됐다</b>는 사실이 아니라 <b>필터를 건 요청이
     * 빈 결과를 냈을 때 무엇이 나오는가</b> 다. 목 JPAQuery 로는 최종 SQL 을 볼 수 없으므로
     * BooleanBuilder 를 직접 만들어 확인한다.
     */
    @Test
    @DisplayName("🔴 가격 필터가 아무것도 못 찾으면 '전체'가 아니라 '없음'이다")
    void emptySkuFilterMeansNoProducts_notEverything() {
        TestParams params = new TestParams();
        params.setPage(1);
        params.setPageSize(10);
        params.setLowPrice(new java.math.BigDecimal("500"));

        // 이 가격대에 맞는 SKU 가 하나도 없다.
        when(productSkuRepository.findProductIDBySizeAndPriceAndColor(any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        service.searchProducts(params, SearchEngine.POSTGRES);

        // 조건이 사라지지 않았다 — 불가능 조건(productId = -1)이 들어가 있어야 한다.
        org.mockito.ArgumentCaptor<com.querydsl.core.types.Predicate> captor =
                org.mockito.ArgumentCaptor.forClass(com.querydsl.core.types.Predicate.class);
        verify(listQuery).where(captor.capture());

        org.assertj.core.api.Assertions.assertThat(captor.getValue().toString())
                .contains("productId = -1");
    }

    /**
     * 가격·색상·사이즈를 하나도 걸지 않았으면 그 질의를 <b>돌리지 않는다.</b>
     *
     * <p>조건 없는 그 질의는 상품 전량의 id 를 읽어 오는데, 결과가 "모든 상품" 이라
     * 필터로서 의미가 없다. 그런데도 매 요청 풀스캔 + 거대한 IN 절이 붙었다.
     */
    // ---------------------------------------------------------------- 가격 필터는 화면의 가격으로 (docs/40 §22)

    private static Object[] row(long productId, String price, int discount) {
        return new Object[] { productId, new java.math.BigDecimal(price), discount };
    }

    private TestParams priceBetween(String low, String high) {
        TestParams params = new TestParams();
        params.setPage(1);
        params.setPageSize(10);
        params.setLowPrice(low == null ? null : new java.math.BigDecimal(low));
        params.setHighPrice(high == null ? null : new java.math.BigDecimal(high));
        return params;
    }

    /** 🔴 카드에 $70(정가 $100, 30% 할인)으로 보이는 상품은 "$80 이하" 에 나와야 한다 — 예전에는 정가로 걸러 빠졌다. */
    @Test
    @DisplayName("🔴 가격 필터는 할인이 반영된 가격으로 거른다 — 화면에 보이는 가격")
    void priceFilterUsesTheShownPrice() {
        when(productSkuRepository.findPriceCandidates(any(), any(), any()))
                .thenReturn(java.util.List.of(row(1L, "100.00", 30), row(2L, "85.00", 0)));

        org.assertj.core.api.Assertions.assertThat(service.skuFilterIds(priceBetween(null, "80")))
                .containsExactly(1L);
        org.assertj.core.api.Assertions.assertThat(service.skuFilterIds(priceBetween("75", null)))
                .containsExactly(2L);
    }

    @Test
    @DisplayName("기획전 할인도 반영한다 — 상시 할인보다 크면 그쪽")
    void priceFilterIncludesEventDiscount() {
        when(eventPricing.effectiveDiscount(eq(3L), org.mockito.ArgumentMatchers.anyInt())).thenReturn(50);
        when(productSkuRepository.findPriceCandidates(any(), any(), any()))
                .thenReturn(java.util.List.<Object[]>of(row(3L, "100.00", 10)));

        org.assertj.core.api.Assertions.assertThat(service.skuFilterIds(priceBetween(null, "60")))
                .containsExactly(3L);
    }

    @Test
    @DisplayName("가격을 고르지 않으면(사이즈·색상만) 예전 질의 그대로")
    void sizeOnlyUsesThePlainQuery() {
        TestParams params = priceBetween(null, null);
        params.setSize("M");
        when(productSkuRepository.findProductIDBySizeAndPriceAndColor(any(), any(), eq("^(M)"), any()))
                .thenReturn(java.util.List.of(9L));

        org.assertj.core.api.Assertions.assertThat(service.skuFilterIds(params)).containsExactly(9L);
        org.mockito.Mockito.verify(productSkuRepository, org.mockito.Mockito.never()).findPriceCandidates(any(), any(), any());
    }

    @Test
    @DisplayName("가격·색상·사이즈를 고르지 않으면 SKU 질의를 돌리지 않는다")
    void noSkuFilter_skipsTheScan() {
        TestParams params = new TestParams();
        params.setPage(1);
        params.setPageSize(10);

        service.searchProducts(params, SearchEngine.POSTGRES);

        org.mockito.Mockito.verify(productSkuRepository, org.mockito.Mockito.never())
                .findProductIDBySizeAndPriceAndColor(any(), any(), any(), any());
    }

    /**
     * 🐛 {@code shipping} 은 <b>받기만 하고 한 번도 쓰이지 않았다.</b> 화면의 "무료배송"
     * 체크박스는 URL 을 바꾸고 요청에도 실려 갔지만 검색 조건에는 들어간 적이 없다.
     */
    @Test
    @DisplayName("🔴 무료배송 조건이 실제로 질의에 들어간다")
    void shippingFilterIsApplied() {
        TestParams params = new TestParams();
        params.setPage(1);
        params.setPageSize(10);
        params.setShipping(java.math.BigDecimal.ZERO);

        service.searchProducts(params, SearchEngine.POSTGRES);

        org.mockito.ArgumentCaptor<com.querydsl.core.types.Predicate> captor =
                org.mockito.ArgumentCaptor.forClass(com.querydsl.core.types.Predicate.class);
        verify(listQuery).where(captor.capture());

        org.assertj.core.api.Assertions.assertThat(captor.getValue().toString())
                .contains("shipping <= 0");
    }

    @Test
    @DisplayName("category가 숫자면 id 경로(findBrandsByCategoryId)를 탄다")
    void categoryById_usesIdPath() {
        TestParams params = new TestParams();
        params.setPage(1);
        params.setPageSize(10);
        params.setCategory("123");

        service.searchProducts(params, SearchEngine.POSTGRES);

        verify(productRepository).findBrandsByCategoryId(123L);
    }

    // ---------------------------------------------------------------- 다중 선택 값은 글자 그대로 (정규식 · LIKE 주입 없음)

    @Test
    @DisplayName("🔴 화면의 ^a|^b 는 값 목록으로 — 빈 값 · 너무 긴 값은 버리고, 개수는 상한까지")
    void filterValuesAreLiteralList() {
        org.assertj.core.api.Assertions.assertThat(SearchService.filterValues("^Red|^Navy Blue|^Red| |^")).containsExactly("Red", "Navy Blue");
        org.assertj.core.api.Assertions.assertThat(SearchService.filterValues("M")).containsExactly("M");
        org.assertj.core.api.Assertions.assertThat(SearchService.filterValues(null)).isEmpty();
        org.assertj.core.api.Assertions.assertThat(SearchService.filterValues("x".repeat(101))).isEmpty();
        org.assertj.core.api.Assertions.assertThat(SearchService.filterValues("^a|".repeat(50))).hasSize(1);
        String many = java.util.stream.IntStream.range(0, 50).mapToObj(i -> "^v" + i).collect(java.util.stream.Collectors.joining("|"));
        org.assertj.core.api.Assertions.assertThat(SearchService.filterValues(many)).hasSize(SearchService.MAX_FILTER_VALUES);
    }

    @Test
    @DisplayName("🔴 사이즈 · 색상 정규식은 메타문자를 모두 가둔다 — 익명 요청이 정규식을 고르지 못한다")
    void prefixRegexEscapesMetacharacters() {
        org.assertj.core.api.Assertions.assertThat(SearchService.prefixRegex(SearchService.filterValues("^XL|^M"))).isEqualTo("^(XL|M)");
        org.assertj.core.api.Assertions.assertThat(SearchService.prefixRegex(SearchService.filterValues("(a+)+$")))
                .isEqualTo("^(\\(a\\+\\)\\+\\$)");
        org.assertj.core.api.Assertions.assertThat(SearchService.prefixRegex(java.util.List.of())).isNull();
    }

    @Test
    @DisplayName("🔴 사이즈 · 색상만 걸어도 가둔 정규식이 SQL 로 간다 — 들어온 문자열 그대로가 아니다")
    void skuFilterSendsEscapedRegex() {
        TestParams params = priceBetween(null, null);
        params.setColor("^.*|^(");

        service.skuFilterIds(params);

        verify(productSkuRepository).findProductIDBySizeAndPriceAndColor(any(), any(), eq(null), eq("^(\\.\\*|\\()"));
    }

    @Test
    @DisplayName("🔴 브랜드 · 스타일은 값마다 포함 검색 — 예전에는 LIKE '%^Nike|^Adidas%' 라 늘 0건")
    void brandAndDetailsMatchEachValue() {
        TestParams params = new TestParams();
        params.setPage(1);
        params.setPageSize(10);
        params.setBrand("^Nike|^Adi_das");
        params.setStyle("^Casual");

        service.searchProducts(params, SearchEngine.POSTGRES);

        org.mockito.ArgumentCaptor<com.querydsl.core.types.Predicate> captor =
                org.mockito.ArgumentCaptor.forClass(com.querydsl.core.types.Predicate.class);
        verify(listQuery).where(captor.capture());
        String where = captor.getValue().toString();
        org.assertj.core.api.Assertions.assertThat(where)
                .contains("%nike%").contains("%adi!_das%").contains("%casual%")
                .doesNotContain("^").doesNotContain("%nike|");
    }

    @Test
    @DisplayName("🔴 페이지는 정해진 순서로 자른다 — ORDER BY 없는 OFFSET 은 페이지가 겹치거나 빠진다")
    void pagesAreOrdered() {
        TestParams params = new TestParams();
        params.setPage(2);
        params.setPageSize(10);

        service.searchProducts(params, SearchEngine.POSTGRES);

        verify(listQuery).orderBy(com.project.catalog_service.model.QProduct.product.productId.desc());
    }
}
