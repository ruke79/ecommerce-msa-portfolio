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
import com.project.catalog_service.dto.SearchResultDto;
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
        // 필터 메뉴는 이 계산 밖이다(SearchFilterFacets.attach — 컨트롤러가 붙인다).
        service = new SearchService(categoryRepository, subCategoryRepository,
                productRepository, productSkuRepository, queryFactory, eventPricing, SearchBulkheadTest.direct());

        // querydsl 체이닝(빌더 패턴)은 RETURNS_SELF 로 체인을 그대로 흘려보낸다.
        countQuery = mock(JPAQuery.class, withSettings().defaultAnswer(Answers.RETURNS_SELF));
        when(countQuery.fetchOne()).thenReturn(0L);
        listQuery = mock(JPAQuery.class, withSettings().defaultAnswer(Answers.RETURNS_SELF));
        when(listQuery.fetch()).thenReturn(Collections.emptyList());

        when(queryFactory.select(any(com.querydsl.core.types.Expression.class))).thenReturn(countQuery);
        when(queryFactory.selectFrom(any())).thenReturn(listQuery);

        when(eventPricing.rules()).thenReturn(new EventPricing.Rules(java.util.Map.of(), java.util.Map.of()));
        when(categoryRepository.findAll()).thenReturn(Collections.emptyList());
        when(subCategoryRepository.findAll()).thenReturn(Collections.emptyList());
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
        // 🔴 메뉴 질의는 캐시된 이 계산 안에서 돌지 않는다 — 1,000만 상품에서 1분 넘는 질의가 검색 응답 · 커넥션을 붙잡았다(2026-10-20)
        verify(productRepository, org.mockito.Mockito.never()).findDistinctBrandsByCategoryName(any());
        verify(productSkuRepository, org.mockito.Mockito.never()).findAllSizes();
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

        service.searchProducts(params, SearchEngine.POSTGRES);

        // 조건이 사라지지 않았다 — SKU 조건(EXISTS)이 목록 · 개수의 WHERE 에 들어가 있다. 맞는 SKU 가 없으면 그 상품이 빠질 뿐이다
        // (2026-10-19 까지는 후보 id 목록이었고, 빈 목록이면 productId = -1 을 넣었다 — 실제 DB 의 판정은 SearchSkuFilterScaleTest).
        org.mockito.ArgumentCaptor<com.querydsl.core.types.Predicate> captor =
                org.mockito.ArgumentCaptor.forClass(com.querydsl.core.types.Predicate.class);
        verify(listQuery).where(captor.capture());

        org.assertj.core.api.Assertions.assertThat(jpql(captor.getValue()))
                .contains("exists").contains("filterSize.price >= 500").doesNotContain("product.productId in");
    }

    /**
     * 가격·색상·사이즈를 하나도 걸지 않았으면 그 질의를 <b>돌리지 않는다.</b>
     *
     * <p>조건 없는 그 질의는 상품 전량의 id 를 읽어 오는데, 결과가 "모든 상품" 이라
     * 필터로서 의미가 없다. 그런데도 매 요청 풀스캔 + 거대한 IN 절이 붙었다.
     */
    // ---------------------------------------------------------------- 가격 필터는 화면의 가격으로 (docs/40 §22)

    /** EXISTS 서브쿼리까지 펼친 JPQL — 상수는 값으로 바꿔 넣는다(Predicate.toString 은 서브쿼리를 그리지 않는다). */
    private static String jpql(com.querydsl.core.types.Predicate p) {
        com.querydsl.jpa.JPQLSerializer out = new com.querydsl.jpa.JPQLSerializer(com.querydsl.jpa.JPQLTemplates.DEFAULT);
        out.handle(p);
        String text = out.toString();
        java.util.List<Object> constants = out.getConstants();
        for (int i = constants.size(); i >= 1; i--) {
            text = text.replace("?" + i, String.valueOf(constants.get(i - 1)));
        }
        return text;
    }

    private TestParams priceBetween(String low, String high) {
        TestParams params = new TestParams();
        params.setPage(1);
        params.setPageSize(10);
        params.setLowPrice(low == null ? null : new java.math.BigDecimal(low));
        params.setHighPrice(high == null ? null : new java.math.BigDecimal(high));
        return params;
    }

    /**
     * 할인가 계산은 이제 SQL 안이다 — 값의 판정(상시 30% · 기획전 · 겹침)은 실제 PostgreSQL 의 SearchSkuFilterScaleTest 가 본다.
     * 여기서는 기획전 규칙이 <b>높은 율부터</b> CASE 로 실리는지(겹치면 높은 쪽 — EventPricing.refresh 의 규칙)만 본다.
     */
    @Test
    @DisplayName("🔴 기획전 규칙은 펼치지 않고 높은 율부터 CASE 로 — 상품 지정 · 카테고리 지정, 앱이 후보를 읽지 않는다")
    void eventRulesGoIntoSqlHighestFirst() {
        when(eventPricing.rules()).thenReturn(new EventPricing.Rules(java.util.Map.of(3L, 50, 4L, 10), java.util.Map.of(7L, 20)));

        String sql = jpql(service.skuFilter(priceBetween(null, "60")));

        org.assertj.core.api.Assertions.assertThat(sql).contains("case when").contains("product.productId = 3")
                .contains("product.category.categoryId = 7").contains("product.productId = 4")
                .as("할인가 = 정가 × (100 − 큰 율) / 100").contains("round(filterSize.price * (100 - least(greatest(coalesce(filterSku.discount, 0), case when");
        org.assertj.core.api.Assertions.assertThat(sql.indexOf("then 50")).as("높은 율부터").isLessThan(sql.indexOf("then 20"));
        org.assertj.core.api.Assertions.assertThat(sql.indexOf("then 20")).isLessThan(sql.indexOf("then 10"));
        org.mockito.Mockito.verifyNoInteractions(productSkuRepository);
    }

    @Test
    @DisplayName("기획전이 없으면 율은 상수 0 — CASE 를 싣지 않는다")
    void noEventRulesMeansConstantZero() {
        String sql = jpql(service.skuFilter(priceBetween("10", "60")));
        org.assertj.core.api.Assertions.assertThat(sql).doesNotContain("case when").contains("filterSize.price >= 10");
    }

    @Test
    @DisplayName("가격을 고르지 않으면(사이즈·색상만) 가격식이 없다 — 색상만이면 사이즈 줄도 조인하지 않는다")
    void sizeOrColorOnlyHasNoPriceExpression() {
        TestParams size = priceBetween(null, null);
        size.setSize("M");
        org.assertj.core.api.Assertions.assertThat(jpql(service.skuFilter(size))).contains("filterSize.size").doesNotContain("round(");

        TestParams color = priceBetween(null, null);
        color.setColor("Red");
        org.assertj.core.api.Assertions.assertThat(jpql(service.skuFilter(color))).contains("filterColor.color").doesNotContain("filterSize");
    }

    /**
     * 🐛 2026-10-25 운영: 가격 "4 ~ 0"(하한 > 상한)은 맞는 SKU 가 없어 목록 질의가 카테고리의 상품을 끝까지 훑었다 — 5초 상한 503.
     * 그런 범위는 DB 에 묻지 않고 0건이다. 같은 값(4 ~ 4) · 0 ~ 0 · 상한만은 예전처럼 묻는다.
     */
    @Test
    @DisplayName("하한 > 상한 · 음수 상한의 가격 범위는 DB 에 묻지 않고 0건")
    void emptyPriceRangeSkipsTheDatabase() {
        for (String[] range : new String[][] {{"4", "0"}, {"100", "99.99"}, {null, "-1"}}) {
            TestParams params = new TestParams();
            params.setPage(1);
            params.setPageSize(10);
            params.setCategory("16");
            params.setLowPrice(range[0] == null ? null : new java.math.BigDecimal(range[0]));
            params.setHighPrice(new java.math.BigDecimal(range[1]));

            SearchResultDto result = service.searchProducts(params, SearchEngine.POSTGRES);

            org.assertj.core.api.Assertions.assertThat(result.getTotalProducts()).as(String.join("_", String.valueOf(range[0]), range[1])).isZero();
            org.assertj.core.api.Assertions.assertThat(result.getProduct().getContent()).isEmpty();
        }
        verify(listQuery, org.mockito.Mockito.never()).fetch();
        verify(countQuery, org.mockito.Mockito.never()).fetchOne();
    }

    @Test
    @DisplayName("하한 = 상한 · 0 ~ 0 · 한쪽만 있는 가격 범위는 예전처럼 DB 에 묻는다")
    void possiblePriceRangesStillQuery() {
        for (String[] range : new String[][] {{"4", "4"}, {"0", "0"}, {"4", null}, {null, "4"}}) {
            TestParams params = new TestParams();
            params.setPage(1);
            params.setPageSize(10);
            params.setLowPrice(range[0] == null ? null : new java.math.BigDecimal(range[0]));
            params.setHighPrice(range[1] == null ? null : new java.math.BigDecimal(range[1]));
            org.assertj.core.api.Assertions.assertThat(SearchService.emptyPriceRange(params)).isFalse();
            service.searchProducts(params, SearchEngine.POSTGRES);
        }
        verify(listQuery, org.mockito.Mockito.times(4)).fetch();
    }

    @Test
    @DisplayName("가격·색상·사이즈를 고르지 않으면 SKU 질의를 돌리지 않는다")
    void noSkuFilter_skipsTheScan() {
        TestParams params = new TestParams();
        params.setPage(1);
        params.setPageSize(10);

        org.assertj.core.api.Assertions.assertThat(service.skuFilter(params)).isNull();
        service.searchProducts(params, SearchEngine.POSTGRES);

        org.mockito.ArgumentCaptor<com.querydsl.core.types.Predicate> captor =
                org.mockito.ArgumentCaptor.forClass(com.querydsl.core.types.Predicate.class);
        verify(listQuery).where(captor.capture());
        org.assertj.core.api.Assertions.assertThat(String.valueOf(captor.getValue())).doesNotContain("exists");
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
    @DisplayName("category가 숫자면 id 경로(findDistinctBrandsByCategoryId)를 탄다")
    void categoryById_usesIdPath() {
        TestParams params = new TestParams();
        params.setPage(1);
        params.setPageSize(10);
        params.setCategory("123");
        params.setIncludeFilters(true);
        when(productRepository.findDistinctBrandsByCategoryId(123L)).thenReturn(Collections.emptyList());
        when(productSkuRepository.findColorsByCategoryId(123L)).thenReturn(Collections.emptyList());
        when(productSkuRepository.findSizesByCategoryId(123L)).thenReturn(Collections.emptyList());
        when(productDetailsRepository.findDistinctNameValuesByCategoryId(123L)).thenReturn(Collections.emptyList());

        // 메뉴는 결과 밖에서 붙는다(SearchController 와 같은 순서) — 바로 실행하는 실행기로
        SearchFilterFacets facets = new SearchFilterFacets(
                new FacetQueries(productRepository, productSkuRepository, productDetailsRepository),
                new org.springframework.cache.concurrent.ConcurrentMapCacheManager(), Runnable::run,
                java.time.Duration.ofMinutes(10), java.time.Duration.ofSeconds(1), java.time.Clock.systemUTC());
        facets.attach(service.searchProducts(params, SearchEngine.POSTGRES), params);

        verify(productRepository).findDistinctBrandsByCategoryId(123L);
        // 색상 · 사이즈 · 상세도 카테고리 조인 질의 — 상품 id 목록을 앱으로 가져와 IN 으로 보내지 않는다
        verify(productSkuRepository).findColorsByCategoryId(123L);
        verify(productSkuRepository).findSizesByCategoryId(123L);
        verify(productDetailsRepository).findDistinctNameValuesByCategoryId(123L);
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
    @DisplayName("🔴 사이즈 · 색상은 값마다 앞부분 일치 LIKE — % · _ · 이스케이프 문자는 글자(익명 요청이 패턴을 고르지 못한다)")
    void prefixLikesEscapeWildcards() {
        org.assertj.core.api.Assertions.assertThat(SearchService.prefixLikes(SearchService.filterValues("^XL|^M"))).containsExactly("XL%", "M%");
        org.assertj.core.api.Assertions.assertThat(SearchService.prefixLikes(SearchService.filterValues("^100%_a!")))
                .containsExactly("100!%!_a!!%");
        org.assertj.core.api.Assertions.assertThat(SearchService.prefixLikes(java.util.List.of())).isEmpty();
    }

    @Test
    @DisplayName("🔴 사이즈 · 색상만 걸어도 가둔 패턴이 SQL 로 간다 — 들어온 문자열 그대로가 아니다")
    void skuFilterSendsEscapedPattern() {
        TestParams params = priceBetween(null, null);
        params.setColor("^.*|^(");

        String sql = jpql(service.skuFilter(params));

        org.assertj.core.api.Assertions.assertThat(sql).contains(".*%").contains("(%").doesNotContain("~");
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
