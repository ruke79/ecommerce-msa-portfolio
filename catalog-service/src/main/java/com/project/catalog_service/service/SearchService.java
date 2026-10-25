package com.project.catalog_service.service;

import com.project.catalog_service.dto.SearchResultDto;

import com.project.catalog_service.mapper.CategoryMapper;
import com.project.catalog_service.mapper.ProductMapper;
import com.project.catalog_service.mapper.SubcategoryMapper;
import com.project.catalog_service.model.Product;
import com.project.catalog_service.model.QProduct;
import com.project.catalog_service.model.QProductColor;
import com.project.catalog_service.model.QProductDetails;
import com.project.catalog_service.model.QProductSize;
import com.project.catalog_service.model.QProductSku;
import com.project.catalog_service.model.QProductSubcategory;
import com.project.catalog_service.repository.jpa.data.CategoryRepository;
import com.project.catalog_service.repository.jpa.data.ProductRepository;
import com.project.catalog_service.repository.jpa.data.ProductSkuRepository;
import com.project.catalog_service.repository.jpa.data.SubcategoryRepository;
import com.project.common.dto.CategoryDto;
import com.project.common.dto.ProductDto;
import com.project.common.dto.SubCategoryDto;
import com.project.common.dto.catalog.request.SearchParamsRequest;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SearchService {

    private final CategoryRepository categoryRepository;
    private final SubcategoryRepository subCategoryRepository;
    private final ProductRepository productRepository;
    private final ProductSkuRepository productSkuRepository;
    private final JPAQueryFactory queryFactory;

    /**
     * 검색 결과에도 기획전 할인을 얹는다.
     *
     * <p>여기를 빠뜨리면 같은 상품이 <b>검색 결과에서는 정가, 상세 화면에서는 할인가</b>로
     * 보인다. 사용자는 목록에서 보고 들어왔는데 가격이 달라진 것을 본다.
     */
    private final EventPricing eventPricing;

    /** 검색의 DB 작업 격벽 — 자리를 얻은 뒤에 시간 상한 트랜잭션을 연다(캐시가 맞으면 오지 않는다). */
    private final SearchBulkhead searchBulkhead;

    // 첫 쪽의 필터 메뉴(브랜드 · 색상 · 사이즈 · 상세)는 여기서 만들지 않는다 — 결과 캐시 밖에서 SearchFilterFacets.attach 가 붙인다
    // (SearchController). 🐛 2026-10-20 전에는 이 캐시된 계산 안에서 돌아 1,000만 상품에서 질의 하나가 1분 넘게 커넥션을 쥐었다.

    // @RequiredArgsConstructor가 있으므로 별도의 @Autowired 생성자는 필요 없습니다.
    // private final 필드들에 대한 생성자를 자동으로 생성합니다.

    private Pageable createPageRequest(int page, int size) {
        return PageRequest.of(page, size);
    }

    /**
     * ES 관련도 검색으로 후보 productId 를 조회한다.
     * @return 이 검색이 ES 로 가지 않음(엔진 POSTGRES · ES 빈 없음) 또는 조회 실패 시 null (호출자가 LIKE 경로로 폴백),
     *         정상 조회 시 id 목록(비어 있을 수 있음 — "매칭 없음" 의미)
     */
    private List<Long> searchIdsViaEs(String term, SearchEngine engine) {
        if (engine != SearchEngine.ES || esProductSearchService == null) {
            return null;
        }
        try {
            return esProductSearchService.searchProductIds(term, ES_CANDIDATE_LIMIT);
        } catch (Exception e) {
            log.warn("ES 검색 실패 — Postgres LIKE 경로로 폴백합니다: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 가격·사이즈·색상 조건 — 그 조건에 맞는 SKU(사이즈 줄)가 하나라도 있는 상품. 조건이 없으면 null(조건 없음).
     *
     * <p>가격은 <b>화면에 보이는 가격</b>으로 본다 — 사이즈 정가에 상시 할인과 기획전 할인 중 큰 쪽을 적용한 값
     * (상품 화면·목록 카드·장바구니와 같은 {@link ProductService#discounted} 의 식: {@code 정가 × (100 − 율) / 100}, 소수 2자리 반올림).
     * 🐛 예전에는 정가로 걸러, 카드에 $70 으로 보이는 상품이 "$80 이하" 에서 빠지고 "$90 이상" 에 나왔다(docs/40 §22).
     *
     * <p>🐛 2026-10-19 데이터 규모 측정(상품 100만, docs/83 §5): 예전에는 조건에 맞는 후보를 <b>카테고리와 상관없이 전부</b> 앱으로
     * 읽었다 — 가격대는 하한 이상인 사이즈 줄 전부(수백만 줄)를 읽어 할인가를 앱에서 따졌고, 맞는 상품 id 를 {@code IN (…)} 으로 다시
     * 보냈다. 필터 검색을 초당 3번 섞자 catalog 3개가 20초 만에 {@code OutOfMemoryError: Java heap space} 로 쓰러졌다(실패 93.76%,
     * 힙 512MB). 이제 상관 서브쿼리({@code EXISTS})로 DB 안에서 판정한다 — 바깥 질의의 카테고리 조건과 함께 돌고, 앱으로 오는 것은
     * 그 쪽의 상품 10개와 개수뿐이다. 맞는 것이 없으면 0건이다("없음"이 "전부"로 뒤집히지 않는다).
     *
     * <p>기획전 할인은 SQL 이 모르는 메모리의 맵이라, 펼치기 전의 규칙({@link EventPricing#rules()} — 상품 지정 · 카테고리 지정)을
     * 그 상품의 율을 고르는 CASE 로 싣는다. 높은 율부터 보므로 겹치면 높은 쪽 — {@code EventPricing.refresh} 와 같은 규칙이다.
     * 사이즈 · 색상은 값마다 앞부분 일치(대소문자 무시, 글자 그대로 — {@link #likeLiteral}).
     *
     * <p>[JA] 価格 · サイズ · 色の条件 — 合う SKU が一つでもある商品。以前は候補をカテゴリに関係なく全部アプリに読み、合う id を IN で
     *      送り直していた(100 万商品で catalog がヒープ不足で倒れた)。今は相関サブクエリ(EXISTS)で DB の中で判定する。
     */
    com.querydsl.core.types.Predicate skuFilter(SearchParamsRequest params) {
        if (!hasSkuFilter(params)) {
            return null;
        }
        java.math.BigDecimal low = params.getLowPrice();
        java.math.BigDecimal high = params.getHighPrice();
        List<String> sizes = filterValues(params.getSize());
        List<String> colors = filterValues(params.getColor());
        boolean priced = low != null || high != null;

        QProduct product = QProduct.product;
        QProductSku sku = new QProductSku("filterSku");
        QProductSize size = new QProductSize("filterSize");
        QProductColor color = new QProductColor("filterColor");
        com.querydsl.jpa.JPQLQuery<Integer> sub = JPAExpressions.selectOne().from(sku);
        BooleanBuilder where = new BooleanBuilder(sku.product.productId.eq(product.productId));
        if (priced || !sizes.isEmpty()) {
            sub.join(sku.sizes, size);
            where.and(startsWithAny(size.size, sizes));
        }
        if (!colors.isEmpty()) {
            sub.join(sku.color, color);
            where.and(startsWithAny(color.color, colors));
        }
        if (priced) {
            com.querydsl.core.types.dsl.NumberExpression<java.math.BigDecimal> shown =
                    shownPrice(size.price, sku.discount, eventRate(product));
            if (low != null) {
                // 할인가는 정가를 넘지 않는다 — 정가 조건은 늘 참이고, 할인가를 셈하기 전에 줄을 거른다
                where.and(size.price.goe(low)).and(shown.goe(low));
            }
            if (high != null) {
                where.and(shown.loe(high));
            }
        }
        return sub.where(where).exists();
    }

    /** 화면의 할인가 — {@code round(정가 × (100 − least(greatest(상시, 기획전), 100)) / 100, 2)}(common Discount.apply 와 같은 값). */
    private static com.querydsl.core.types.dsl.NumberExpression<java.math.BigDecimal> shownPrice(
            com.querydsl.core.types.dsl.NumberPath<java.math.BigDecimal> price,
            com.querydsl.core.types.dsl.NumberPath<Integer> skuDiscount,
            com.querydsl.core.types.dsl.NumberExpression<Integer> eventRate) {
        return com.querydsl.core.types.dsl.Expressions.numberTemplate(java.math.BigDecimal.class,
                "round({0} * (100 - least(greatest(coalesce({1}, 0), {2}), 100)) / 100, 2)", price, skuDiscount, eventRate);
    }

    /**
     * 그 상품의 기획전 할인율 — 상품 지정 · 카테고리 지정 중 높은 쪽, 없으면 0. 규칙이 없으면(대부분의 때) 상수 0.
     * 상품 지정은 많아야 수천 건이고 카테고리 지정은 카테고리 수만큼이라 바인드 변수 한도(65,535) 안이다 — 펼친 맵을 싣지 않는 이유.
     */
    private com.querydsl.core.types.dsl.NumberExpression<Integer> eventRate(QProduct product) {
        EventPricing.Rules rules = eventPricing.rules();
        if (rules == null || rules.isEmpty()) {
            return com.querydsl.core.types.dsl.Expressions.numberTemplate(Integer.class, "0");
        }
        java.util.TreeMap<Integer, List<Long>> productsByRate = new java.util.TreeMap<>(java.util.Comparator.reverseOrder());
        java.util.TreeMap<Integer, List<Long>> categoriesByRate = new java.util.TreeMap<>(java.util.Comparator.reverseOrder());
        rules.products().forEach((id, rate) -> productsByRate.computeIfAbsent(rate, r -> new ArrayList<>()).add(id));
        rules.categories().forEach((id, rate) -> categoriesByRate.computeIfAbsent(rate, r -> new ArrayList<>()).add(id));
        java.util.TreeSet<Integer> rates = new java.util.TreeSet<>(java.util.Comparator.reverseOrder());
        rates.addAll(productsByRate.keySet());
        rates.addAll(categoriesByRate.keySet());

        com.querydsl.core.types.dsl.CaseBuilder.Cases<Integer, com.querydsl.core.types.dsl.NumberExpression<Integer>> cases = null;
        for (Integer rate : rates) {
            BooleanBuilder applies = new BooleanBuilder();
            List<Long> ids = productsByRate.get(rate);
            if (ids != null) {
                applies.or(product.productId.in(ids));
            }
            List<Long> categoryIds = categoriesByRate.get(rate);
            if (categoryIds != null) {
                applies.or(product.category.categoryId.in(categoryIds));
            }
            cases = cases == null ? new com.querydsl.core.types.dsl.CaseBuilder().when(applies).then(rate) : cases.when(applies).then(rate);
        }
        return cases.otherwise(0);
    }

    /** 값 중 하나로 시작하면 참(대소문자 무시, 글자 그대로). 값이 없으면 null(조건 없음). */
    private static com.querydsl.core.types.Predicate startsWithAny(com.querydsl.core.types.dsl.StringPath path, List<String> values) {
        if (values.isEmpty()) {
            return null;
        }
        BooleanBuilder any = new BooleanBuilder();
        for (String v : prefixLikes(values)) {
            any.or(path.likeIgnoreCase(v, '!'));
        }
        return any;
    }

    /** 앞부분 일치 LIKE 패턴 — {@code 값%}, 값 안의 {@code %} · {@code _} · {@code !} 는 글자({@link #likeLiteral}, 이스케이프 {@code !}). */
    static List<String> prefixLikes(List<String> values) {
        return values.stream().map(v -> likeLiteral(v) + "%").toList();
    }

    /**
     * 가격·사이즈·색상 중 하나라도 걸었는가.
     *
     * <p>걸지 않았으면 SKU 조건을 <b>만들지 않는다.</b> 조건 없는 SKU 질의는 상품 전량을
     * 훑는데, 그 결과는 "모든 상품" 이라 필터로서 아무 의미가 없다.
     */
    private static boolean hasSkuFilter(SearchParamsRequest params) {
        return params.getLowPrice() != null
                || params.getHighPrice() != null
                || !filterValues(params.getSize()).isEmpty()
                || !filterValues(params.getColor()).isEmpty();
    }

    /** 다중 선택 필터 하나에 받는 값의 상한 · 값 하나의 길이 상한. */
    static final int MAX_FILTER_VALUES = 20;
    static final int MAX_FILTER_VALUE_LENGTH = 100;

    /**
     * 화면이 보내는 다중 선택 값({@code ^Red|^Blue} — browse.jsx 의 createRegex)을 <b>글자 그대로의 값 목록</b>으로 푼다.
     * 앞의 {@code ^} 를 떼고 {@code |} 로 나눈다. 정규식으로 읽지 않는다.
     *
     * <p>🐛 예전에는 이 문자열을 그대로 썼다:
     * <ul>
     *   <li>사이즈 · 색상은 SQL {@code ~*} 의 <b>정규식</b>이 됐다 — 익명 요청이 정규식을 고른다. {@code size=(} 는
     *       "invalid regular expression" 500, 역참조 · 중첩 반복은 그 SKU 표 전체에 대한 비싼 매칭이었다</li>
     *   <li>브랜드 · 스타일 · 소재 · 성별은 LIKE {@code '%^Nike|^Adidas%'} 가 됐다 — {@code ^} · {@code |} 를 글자로 찾으니
     *       <b>화면에서 고른 필터가 한 번도 맞지 않았다</b>(언제나 0건)</li>
     * </ul>
     *
     * <p>[JA] 画面が送る複数選択値({@code ^Red|^Blue})を文字どおりの値リストにする。以前は SQL の正規表現(500 · 高コスト)や
     * LIKE '%^Nike|^Adidas%'(常に 0 件)になっていた。
     */
    static List<String> filterValues(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        java.util.LinkedHashSet<String> values = new java.util.LinkedHashSet<>();
        for (String part : raw.split("\\|")) {
            String v = part.strip();
            while (v.startsWith("^")) {
                v = v.substring(1).strip();
            }
            if (!v.isEmpty() && v.length() <= MAX_FILTER_VALUE_LENGTH) {
                values.add(v);
            }
            if (values.size() >= MAX_FILTER_VALUES) {
                break;
            }
        }
        return List.copyOf(values);
    }

    /** LIKE 의 {@code %} · {@code _} · 이스케이프 문자를 글자로 — 값 안의 {@code _} 가 "아무 한 글자" 가 되지 않게. */
    static String likeLiteral(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    /**
     * 상품 이름에 검색어가 들어 있는가 — 양쪽을 DB 함수 {@code product.search_key}(NFKC + 소문자, catalog V23)로 맞춰 비교한다.
     * 그 식 인덱스(idx_product_name_search_key_trgm)를 쓴다. 검색어의 {@code %} · {@code _} 는 글자로(예전에는 {@code %} 하나가 전부를 맞혔다).
     * [JA] 商品名に検索語が含まれるか — 両側を product.search_key(NFKC + 小文字)で揃えて比較する(式インデックスを使う)。
     */
    static com.querydsl.core.types.Predicate nameMatches(com.querydsl.core.types.dsl.StringPath name, String search) {
        return com.querydsl.core.types.dsl.Expressions.booleanTemplate(
                "search_key({0}) like concat('%', search_key({1}), '%') escape '!'",
                name, likeLiteral(search));
    }

    /** 값 중 하나라도 포함하면 참. 값이 없으면 null(조건 없음). */
    private static com.querydsl.core.types.Predicate containsAny(com.querydsl.core.types.dsl.StringPath path, List<String> values) {
        if (values.isEmpty()) {
            return null;
        }
        BooleanBuilder any = new BooleanBuilder();
        for (String v : values) {
            any.or(path.likeIgnoreCase("%" + likeLiteral(v) + "%", '!'));
        }
        return any;
    }

    /** 스타일 · 소재 · 성별 중 하나라도 값이 있는가 — {@link #buildSearchPredicate} 의 5번 조건이 생기는가와 같다. */
    static boolean hasDetailsFilter(SearchParamsRequest params) {
        return !filterValues(params.getStyle()).isEmpty()
                || !filterValues(params.getMaterial()).isEmpty()
                || !filterValues(params.getGender()).isEmpty();
    }

    /** category 파라미터가 숫자 id면 Long으로, 이름이거나 비어있으면 null을 반환한다(이름 검색은 별도 경로). */
    static Long parseCategoryId(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(category.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    
    // 페이지 크기 상한: 무제한 pageSize로 인한 대량 조회/deep pagination(DoS) 방지.
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;

    // ES 관련도 검색으로 가져올 후보 productId 상한 (파셋/필터가 이후 Postgres 에서 더 걸러냄)
    private static final int ES_CANDIDATE_LIMIT = 1000;

    // (docs/07 R-C1) search.engine=es 일 때만 존재하는 선택적 빈.
    // 존재하고 이 검색의 엔진이 ES 면(docs/74 — 기능 플래그) 텍스트 검색을 "ES 관련도 검색 → id IN 조건" 으로 전환하고,
    // ES 장애 시에는 기존 LIKE 경로로 폴백한다. 테스트 주입을 위해 package-private.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    EsProductSearchService esProductSearchService;

    /**
     * @param engine 텍스트 검색 엔진 — {@link SearchEngineSelector} 가 정한다(docs/74). <b>캐시 키에 들어간다.</b>
     *               같은 검색이라도 ES 와 LIKE 의 결과는 다르다. 키에 없으면 비율을 바꾼 뒤 최대 60분(search_cache TTL)
     *               동안 반대쪽 엔진의 결과가 나가고, 추적의 변형 태그와 실제 결과가 어긋난다.
     *               [JA] キャッシュキーに含まれる。同じ検索でも ES と LIKE の結果は違う。キーになければ比率を変えた後
     *               最大 60 分間、反対側のエンジンの結果が返り、トレースの変形タグと実際の結果が食い違う。
     *
     * <p>{@code sync = true} — 만료 순간 같은 검색을 파드마다 한 번만 계산한다({@code SingleFlightCache}). 🐛 2026-10-19 mixed
     * {@code 160904Z}(docs/83 §5): 한꺼번에 채워진 키 48개가 같은 초에 만료되자 키마다 들어온 요청이 모두 DB 에서 다시 계산해 20초 동안
     * 커넥션 대기 22 · 서버 p95 1.5초.
     *
     * <p>트랜잭션(= 커넥션)은 여기서 열지 않는다({@code NOT_SUPPORTED}) — 캐시를 본 뒤 {@link SearchBulkhead} 가 자리를 얻고 나서 연다
     * (읽기 전용 · 시간 상한 {@code catalog.search.query-timeout-seconds}). 🐛 2026-10-20 실습: 필터 검색이 커넥션을 1 ~ 4초씩 쥐어 풀이
     * 늘 차고 home · 상품까지 5xx 였다. [JA] トランザクション(接続)はここでは開かない — 隔壁が席を得てから開く。
     */
    @Cacheable(value = "search_cache", cacheManager = "redisCacheManager", sync = true)
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public SearchResultDto searchProducts(SearchParamsRequest params, SearchEngine engine) {
        return searchBulkhead.run(hasSkuFilter(params), () -> search(params, engine));
    }

    private SearchResultDto search(SearchParamsRequest params, SearchEngine engine) {
        // category는 숫자 id 또는 이름(name)일 수 있다. 숫자가 아니면 id로 파싱하지 않고 null 처리
        // (기존 Long.parseLong은 이름 검색 시 NumberFormatException → 500을 유발했다).
        Long categoryId = parseCategoryId(params.getCategory());

        // 1. SKU(가격·색상·사이즈) 필터를 **건 경우에만** 조건(EXISTS)을 만든다 — null 은 "이 필터를 걸지 않았다"는 뜻이다.
        //
        // 🔴 예전에는 조건이 없어도 매번 후보 id 질의를 돌렸다(상품 전량의 id 를 읽어 `in (...)` 에 넣었다).
        //    건 경우에도 후보를 앱으로 읽었다 — 100만 상품에서 힙 부족(skuFilter 주석).
        com.querydsl.core.types.Predicate skuFilter = skuFilter(params);

        // 2. Querydsl을 사용하여 모든 검색 조건(상품명, 브랜드, 상세 정보, 평점)을 조합하여 최종 상품 목록 조회
        // SKU 조건은 상관 서브쿼리(EXISTS)로 같은 WHERE 에 들어간다.
        QProduct product = QProduct.product;
        QProductDetails productDetails = QProductDetails.productDetails;
        BooleanBuilder builder = buildSearchPredicate(params, categoryId, skuFilter, engine);

        // 상세(스타일 · 소재 · 성별) 필터가 있을 때만 상세 표를 조인한다 — 🐛 예전에는 늘 조인해 상품마다 상세 줄 수만큼 불린 뒤
        // count(distinct) · select distinct 로 다시 접었다(100만 상품의 큰 카테고리에서 개수 세기가 그만큼 무거웠다). 결과는 같다.
        // [JA] 詳細フィルターがあるときだけ詳細テーブルを結合する。以前は常に結合して distinct で畳み直していた。
        boolean joinDetails = hasDetailsFilter(params);
        com.querydsl.jpa.impl.JPAQuery<Long> countQuery = queryFactory
                .select(joinDetails ? product.countDistinct() : product.count())
                .from(product);
        if (joinDetails) {
            countQuery.leftJoin(product.details, productDetails);
        }
        long totalProducts = countQuery.where(builder).fetchOne();

        // page는 1-기반 입력. 0/음수 입력 시 PageRequest.of(-1,..)가 예외를 던지므로 안전하게 클램프.
        int safePage = Math.max(1, params.getPage()) - 1;
        int safeSize = params.getPageSize() <= 0
                ? DEFAULT_PAGE_SIZE
                : Math.min(params.getPageSize(), MAX_PAGE_SIZE);
        Pageable pageRequest = createPageRequest(safePage, safeSize);

        com.querydsl.jpa.impl.JPAQuery<Product> listQuery = queryFactory.selectFrom(product);
        if (joinDetails) {
            listQuery.distinct().leftJoin(product.details, productDetails);
        }
        List<Product> products = listQuery
                .where(builder)
                // 🔒 순서를 고정한다 — 🐛 예전에는 ORDER BY 없이 OFFSET/LIMIT 이라, 무한 스크롤의 2페이지가 1페이지와 겹치거나
                //    상품을 건너뛸 수 있었다(PostgreSQL 은 순서 없는 결과의 순서를 보장하지 않는다 — 계획 · 갱신에 따라 바뀐다).
                //    최신 등록 순. [JA] 順序を固定する — 以前は ORDER BY なしの OFFSET/LIMIT で、ページが重複 · 欠落し得た。
                .orderBy(product.productId.desc())
                .offset(pageRequest.getOffset())
                .limit(pageRequest.getPageSize())
                .fetch();

        List<ProductDto> pageContent = eventPricing.apply(products.stream()
                .map(ProductMapper::toDto)
                .collect(Collectors.toList()));

        Page<ProductDto> productPage = new PageImpl<>(pageContent, pageRequest, totalProducts);

        /*
         * 3. 필터링 메뉴에 표시할 정보 조회
         *
         * 🐛 예전에는 **조건 없이 매번** 이 여섯 개를 다 읽었다. 그중 둘은
         * `findAll()` — 카테고리 전체와 하위 카테고리 전체다. 그런데 화면은 첫 페이지의
         * 것만 쓰고 나머지는 버린다(browse.jsx 가 `pages[0]` 에서만 읽는다).
         *
         * 무한 스크롤을 붙인 뒤로 이게 더 도드라졌다 — 2·3·4페이지를 받을 때마다 쓰지도
         * 않을 카테고리 전체가 따라왔다. 상품 20개를 더 받자고 카테고리·하위카테고리·
         * 색상·사이즈·상세·브랜드 여섯 질의를 더 돌린 셈이다.
         *
         * 필요 없다고 말한 요청에는 만들지 않는다. 기본값은 true 라 이 값을 모르는
         * 호출부의 동작은 그대로다.
         */
        SearchResultDto.SearchResultDtoBuilder result = SearchResultDto.builder()
                .product(productPage)
                .totalProducts((int) totalProducts);

        // 메뉴 넷(색상 · 사이즈 · 상세 · 브랜드)은 빈 목록으로 두고 SearchFilterFacets.attach 가 채운다 — 캐시된 이 결과에 넣으면
        // 60분 동안 그 메뉴가 그대로고, 계산이 늦으면 검색 전체가 그만큼 늦다.
        if (params.isIncludeFilters()) {
            List<CategoryDto> categoryDtos = categoryRepository.findAll().stream().map(CategoryMapper::toDto).collect(Collectors.toList());
            List<SubCategoryDto> subCategoryDtos = subCategoryRepository.findAll().stream().map(SubcategoryMapper::toDto).collect(Collectors.toList());

            result.categories(categoryDtos)
                  .subCategories(subCategoryDtos)
                  .colors(List.of())
                  .sizes(List.of())
                  .details(List.of())
                  .brandsDB(List.of());
        } else {
            // null 이 아니라 빈 목록을 준다. 받는 쪽이 길이를 재거나 순회할 때
            // null 검사를 빠뜨려도 터지지 않는다.
            result.categories(List.of())
                  .subCategories(List.of())
                  .colors(List.of())
                  .sizes(List.of())
                  .details(List.of())
                  .brandsDB(List.of());
        }

        // 4. 결과 DTO 구성 및 반환
        return result.build();
    }

    private BooleanBuilder buildSearchPredicate(SearchParamsRequest params, Long categoryId, com.querydsl.core.types.Predicate skuFilter,
            SearchEngine engine) {
        QProduct product = QProduct.product;
        QProductDetails productDetails = QProductDetails.productDetails;
        BooleanBuilder builder = new BooleanBuilder();

        // 1. SKU 조건 (Price, Color, Size — skuFilter)
        //
        // 🔴 예전 조건은 `!= null && !isEmpty()` 였다. 즉 <b>필터가 아무것도 못 찾으면
        //    조건 자체를 버렸다</b> — "맞는 게 없다" 가 "제한이 없다" 로 뒤집힌다.
        //    사용자에게는 이렇게 보인다: 가격대를 좁힐수록 상품이 줄다가, 맞는 게 하나도
        //    없는 구간을 고르는 순간 <b>카탈로그 전체</b>가 돌아온다. 색상·사이즈도 같다.
        //
        //    null(필터를 걸지 않음)과 빈 목록(걸었는데 매칭 없음)을 반드시 갈라야 한다.
        //
        //    지금은 EXISTS 라 맞는 SKU 가 없으면 그 상품이 빠질 뿐이다 — 조건이 사라질 수 없다(skuFilter).
        if (skuFilter != null) {
            builder.and(skuFilter);
        }

        // 2. categoryId 조건
        if (categoryId != null) {
            builder.and(product.category.categoryId.eq(categoryId));
        } else if (params.getCategory() != null && !params.getCategory().isBlank()) {
            // 🐛 예전에는 여기서 **아무 일도 하지 않았다.** parseCategoryId 가 숫자가
            //    아니면 null 을 돌려주고, 그 다음 이름으로 거르는 코드는 어디에도 없었다
            //    (주석에는 "이름 검색은 별도 경로" 라고 적혀 있지만 그런 경로가 없다).
            //    그래서 category=shoes 처럼 이름으로 들어오면 **필터가 조용히 사라지고
            //    전체 상품이 나왔다** — 사용자에게는 "카테고리를 골랐는데 다 나온다"다.
            builder.and(product.category.categoryName.equalsIgnoreCase(params.getCategory().trim()));
        }

        // 2-1. subCategory 조건
        //
        // 🐛 하위 카테고리는 **화면에 그려지기만 했다.** 누를 수도 없었고(onClick 없음),
        //    누를 수 있었다 해도 보낼 파라미터도 여기 조건도 없었다.
        //
        // category 와 AND 로 묶인다 — 상위를 고른 뒤 그 안에서 좁히는 것이 화면의 모양이다.
        Long subCategoryId = parseCategoryId(params.getSubCategory());
        if (subCategoryId != null) {
            // exists 서브쿼리가 아니라 조인이면 상품이 하위 카테고리 수만큼 중복된다.
            QProductSubcategory link = QProductSubcategory.productSubcategory;
            builder.and(JPAExpressions.selectOne()
                    .from(link)
                    .where(link.product.eq(product)
                            .and(link.subcategory.subcategoryId.eq(subCategoryId)))
                    .exists());
        }

        // 3. name 조건 — 이 검색의 엔진이 ES 면 관련도 검색으로 후보 id 를 구해 IN 으로 좁힌다 (docs/07 R-C1, docs/74).
        //    POSTGRES · ES 장애 시에는 기존 LIKE 경로 유지(폴백).
        if (params.getSearch() != null && !params.getSearch().isEmpty()) {
            List<Long> esIds = searchIdsViaEs(params.getSearch(), engine);
            if (esIds == null) {
                // ES 비활성 또는 실패 → 선행 와일드카드 LIKE. 양쪽을 product.search_key(NFKC + 소문자, V23)로 비교한다 —
                // 전각 · 반각으로 친 검색어가 원문 그대로 저장된 이름을 찾는다(검색어는 SearchParamsRequest 가 이미 NFKC).
                // [JA] 両側を product.search_key(NFKC + 小文字)で比較 — 全角 · 半角の検索語が原文の名前を見つける。
                builder.and(nameMatches(product.name, params.getSearch()));
            } else if (esIds.isEmpty()) {
                // ES 가 "매칭 없음"이라고 답함 → 빈 결과 (IN () 는 SQL 오류이므로 불가능 조건 사용)
                builder.and(product.productId.eq(-1L));
            } else {
                builder.and(product.productId.in(esIds));
            }
        }

        // 4. brand 조건
        com.querydsl.core.types.Predicate brand = containsAny(product.brand, filterValues(params.getBrand()));
        if (brand != null) {
            builder.and(brand);
        }

        // 5. style, material, gender 조건 (OR 로직) — 값마다 글자 그대로 포함(filterValues)
        BooleanBuilder detailsBuilder = new BooleanBuilder();
        for (String raw : new String[] { params.getStyle(), params.getMaterial(), params.getGender() }) {
            com.querydsl.core.types.Predicate any = containsAny(productDetails.value, filterValues(raw));
            if (any != null) {
                detailsBuilder.or(any);
            }
        }
        if (detailsBuilder.hasValue()) {
            builder.and(detailsBuilder);
        }

        // 6. rating 조건
        if (params.getRating() != 0.0f) {
            builder.and(product.rating.goe(params.getRating()));
        }

        // 7. shipping 조건
        //
        // 🐛 `SearchParamsRequest.shipping` 은 <b>받기만 하고 한 번도 쓰이지 않았다.</b>
        //    화면의 "무료배송" 체크박스는 URL 을 바꾸고 요청에도 실려 갔지만, 검색 조건에는
        //    들어간 적이 없다 — 별점 필터와 같은 모양의 결함이다(docs/40).
        //
        //    화면이 보내는 값은 0(무료)뿐이지만 조건은 "그 값 이하"로 둔다. 나중에
        //    "배송비 3,000원 이하" 같은 것을 붙일 때 서버를 다시 고치지 않아도 된다.
        //    배송비가 null 인 상품은 제외된다 — 모르는 값을 무료라고 말하지 않는다.
        if (params.getShipping() != null) {
            builder.and(product.shipping.loe(params.getShipping()));
        }

        return builder;
    }

    @Cacheable(value = "products_by_category", key = "#category + '_' + #pageRequest.pageNumber", cacheManager = "redisCacheManager", sync = true)
    public List<Product> findAllByCategory(Long category, Pageable pageRequest) {

        Page<Product> productPage = productRepository.findAllByCategory_CategoryId(category, pageRequest);

        return productPage.getContent();
    }
}