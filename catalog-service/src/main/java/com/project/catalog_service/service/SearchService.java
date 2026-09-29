package com.project.catalog_service.service;

import com.project.catalog_service.dto.SearchResultDto;

import com.project.catalog_service.mapper.CategoryMapper;
import com.project.catalog_service.mapper.ProductDetailsMapper;
import com.project.catalog_service.mapper.ProductMapper;
import com.project.catalog_service.mapper.SubcategoryMapper;
import com.project.catalog_service.model.Product;
import com.project.catalog_service.model.ProductDetails;
import com.project.catalog_service.model.QProduct;
import com.project.catalog_service.model.QProductDetails;
import com.project.catalog_service.model.QProductSubcategory;
import com.project.catalog_service.repository.jpa.data.CategoryRepository;
import com.project.catalog_service.repository.jpa.data.ProductDetailsRepository;
import com.project.catalog_service.repository.jpa.data.ProductRepository;
import com.project.catalog_service.repository.jpa.data.ProductSkuRepository;
import com.project.catalog_service.repository.jpa.data.SubcategoryRepository;
import com.project.common.dto.CategoryDto;
import com.project.common.dto.ProductDetailsDto;
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
    private final ProductDetailsRepository productDetailsRepository;
    private final JPAQueryFactory queryFactory;

    /**
     * 검색 결과에도 기획전 할인을 얹는다.
     *
     * <p>여기를 빠뜨리면 같은 상품이 <b>검색 결과에서는 정가, 상세 화면에서는 할인가</b>로
     * 보인다. 사용자는 목록에서 보고 들어왔는데 가격이 달라진 것을 본다.
     */
    private final EventPricing eventPricing;

    // @RequiredArgsConstructor가 있으므로 별도의 @Autowired 생성자는 필요 없습니다.
    // private final 필드들에 대한 생성자를 자동으로 생성합니다.

    private Pageable createPageRequest(int page, int size) {
        return PageRequest.of(page, size);
    }

    /**
     * ES 관련도 검색으로 후보 productId 를 조회한다.
     * @return ES 비활성(빈 없음) 또는 조회 실패 시 null (호출자가 LIKE 경로로 폴백),
     *         정상 조회 시 id 목록(비어 있을 수 있음 — "매칭 없음" 의미)
     */
    private List<Long> searchIdsViaEs(String term) {
        if (esProductSearchService == null) {
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
     * 가격·사이즈·색상 중 하나라도 걸었는가.
     *
     * <p>걸지 않았으면 SKU 질의를 <b>돌리지 않는다.</b> 조건 없는 그 질의는 상품 전량의
     * id 를 읽어 오는데, 그 결과는 "모든 상품" 이라 필터로서 아무 의미가 없다.
     */
    /**
     * 가격·사이즈·색상 조건에 맞는 상품 id.
     *
     * <p>가격은 <b>화면에 보이는 가격</b>으로 본다 — 사이즈 정가에 상시 할인과 기획전 할인 중 큰 쪽을 적용한 값
     * (상품 화면·목록 카드·장바구니와 같은 {@link ProductService#discounted}). 🐛 예전에는 정가로 걸러, 카드에 $70 으로
     * 보이는 상품이 "$80 이하" 에서 빠지고 "$90 이상" 에 나왔다(docs/40 §22).
     */
    List<Long> skuFilterIds(SearchParamsRequest params) {
        java.math.BigDecimal low = params.getLowPrice();
        java.math.BigDecimal high = params.getHighPrice();
        if (low == null && high == null) {
            return productSkuRepository.findProductIDBySizeAndPriceAndColor(null, null, params.getSize(), params.getColor());
        }
        java.util.LinkedHashSet<Long> ids = new java.util.LinkedHashSet<>();
        for (Object[] row : productSkuRepository.findPriceCandidates(low, params.getSize(), params.getColor())) {
            if (row == null || row[0] == null || row[1] == null) {
                continue;
            }
            long productId = ((Number) row[0]).longValue();
            java.math.BigDecimal listPrice = row[1] instanceof java.math.BigDecimal b ? b
                    : new java.math.BigDecimal(row[1].toString());
            int skuDiscount = row[2] == null ? 0 : ((Number) row[2]).intValue();
            java.math.BigDecimal shown = ProductService.discounted(listPrice,
                    eventPricing.effectiveDiscount(productId, skuDiscount));
            if ((low == null || shown.compareTo(low) >= 0) && (high == null || shown.compareTo(high) <= 0)) {
                ids.add(productId);
            }
        }
        return new java.util.ArrayList<>(ids);
    }

    private static boolean hasSkuFilter(SearchParamsRequest params) {
        return params.getLowPrice() != null
                || params.getHighPrice() != null
                || (params.getSize() != null && !params.getSize().isBlank())
                || (params.getColor() != null && !params.getColor().isBlank());
    }

    /** category 파라미터가 숫자 id면 Long으로, 이름이거나 비어있으면 null을 반환한다(이름 검색은 별도 경로). */
    private Long parseCategoryId(String category) {
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
    // 존재하면 텍스트 검색을 "ES 관련도 검색 → id IN 조건" 으로 전환하고,
    // ES 장애 시에는 기존 LIKE 경로로 폴백한다. 테스트 주입을 위해 package-private.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    EsProductSearchService esProductSearchService;

    @Cacheable(value = "search_cache", cacheManager = "redisCacheManager")
    public SearchResultDto searchProducts(SearchParamsRequest params) {
        // category는 숫자 id 또는 이름(name)일 수 있다. 숫자가 아니면 id로 파싱하지 않고 null 처리
        // (기존 Long.parseLong은 이름 검색 시 NumberFormatException → 500을 유발했다).
        Long categoryId = parseCategoryId(params.getCategory());

        // 1. SKU(가격·색상·사이즈) 필터를 **건 경우에만** 후보 id 를 구한다.
        //
        // 🔴 예전에는 조건이 없어도 매번 이 질의를 돌렸다. 조건이 없으면 상품 전량의 id 를
        //    읽어 와서 그대로 `in (...)` 에 넣는 것이라, 상품이 늘수록 커지는 쓸모없는
        //    풀스캔 + 거대한 IN 절이었다. null 은 "이 필터를 걸지 않았다"는 뜻이다.
        List<Long> productIdsFromSku = hasSkuFilter(params) ? skuFilterIds(params) : null;

        // 2. Querydsl을 사용하여 모든 검색 조건(상품명, 브랜드, 상세 정보, 평점)을 조합하여 최종 상품 목록 조회
        // 이때, productIdsFromSku는 IN 절로 활용됩니다.
        QProduct product = QProduct.product;
        QProductDetails productDetails = QProductDetails.productDetails;
        BooleanBuilder builder = buildSearchPredicate(params, categoryId, productIdsFromSku);

        long totalProducts = queryFactory.select(product.countDistinct())
                .from(product)
                .leftJoin(product.details, productDetails)
                .where(builder)
                .fetchOne();

        // page는 1-기반 입력. 0/음수 입력 시 PageRequest.of(-1,..)가 예외를 던지므로 안전하게 클램프.
        int safePage = Math.max(1, params.getPage()) - 1;
        int safeSize = params.getPageSize() <= 0
                ? DEFAULT_PAGE_SIZE
                : Math.min(params.getPageSize(), MAX_PAGE_SIZE);
        Pageable pageRequest = createPageRequest(safePage, safeSize);

        List<Product> products = queryFactory.selectFrom(product)
                .distinct()
                .leftJoin(product.details, productDetails)
                .where(builder)
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

        if (params.isIncludeFilters()) {
            List<String> brands = (categoryId != null) ? productRepository.findBrandsByCategoryId(categoryId)
                                                     : productRepository.findBrandsByCategoryName(params.getCategory());
            List<CategoryDto> categoryDtos = categoryRepository.findAll().stream().map(CategoryMapper::toDto).collect(Collectors.toList());
            List<SubCategoryDto> subCategoryDtos = subCategoryRepository.findAll().stream().map(SubcategoryMapper::toDto).collect(Collectors.toList());

            result.categories(categoryDtos)
                  .subCategories(subCategoryDtos)
                  .colors(getColors(categoryId))
                  .sizes(getSizes(categoryId))
                  .details(getDetails(categoryId))
                  .brandsDB(brands);
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

    private BooleanBuilder buildSearchPredicate(SearchParamsRequest params, Long categoryId, List<Long> productIdsFromSku) {
        QProduct product = QProduct.product;
        QProductDetails productDetails = QProductDetails.productDetails;
        BooleanBuilder builder = new BooleanBuilder();

        // 1. productIds 조건 (SKU, Price, Color, Size 필터링 결과)
        //
        // 🔴 예전 조건은 `!= null && !isEmpty()` 였다. 즉 <b>필터가 아무것도 못 찾으면
        //    조건 자체를 버렸다</b> — "맞는 게 없다" 가 "제한이 없다" 로 뒤집힌다.
        //    사용자에게는 이렇게 보인다: 가격대를 좁힐수록 상품이 줄다가, 맞는 게 하나도
        //    없는 구간을 고르는 순간 <b>카탈로그 전체</b>가 돌아온다. 색상·사이즈도 같다.
        //
        //    null(필터를 걸지 않음)과 빈 목록(걸었는데 매칭 없음)을 반드시 갈라야 한다.
        if (productIdsFromSku != null) {
            builder.and(productIdsFromSku.isEmpty()
                    // IN () 는 SQL 오류라 불가능 조건을 쓴다 — 아래 ES 경로와 같은 방식이다.
                    ? product.productId.eq(-1L)
                    : product.productId.in(productIdsFromSku));
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

        // 3. name 조건 — ES 활성화 시 관련도 검색으로 후보 id 를 구해 IN 으로 좁힌다 (docs/07 R-C1).
        //    ES 미사용/장애 시에는 기존 LIKE 경로 유지(폴백).
        if (params.getSearch() != null && !params.getSearch().isEmpty()) {
            List<Long> esIds = searchIdsViaEs(params.getSearch());
            if (esIds == null) {
                // ES 비활성 또는 실패 → 기존 동작(선행 와일드카드 LIKE)
                builder.and(product.name.likeIgnoreCase("%" + params.getSearch() + "%"));
            } else if (esIds.isEmpty()) {
                // ES 가 "매칭 없음"이라고 답함 → 빈 결과 (IN () 는 SQL 오류이므로 불가능 조건 사용)
                builder.and(product.productId.eq(-1L));
            } else {
                builder.and(product.productId.in(esIds));
            }
        }

        // 4. brand 조건
        if (params.getBrand() != null && !params.getBrand().isEmpty()) {
            builder.and(product.brand.likeIgnoreCase("%" + params.getBrand() + "%"));
        }

        // 5. style, material, gender 조건 (OR 로직)
        if (params.getStyle() != null || params.getMaterial() != null || params.getGender() != null) {
            BooleanBuilder detailsBuilder = new BooleanBuilder();
            if (params.getStyle() != null && !params.getStyle().isEmpty()) {
                detailsBuilder.or(productDetails.value.likeIgnoreCase("%" + params.getStyle() + "%"));
            }
            if (params.getMaterial() != null && !params.getMaterial().isEmpty()) {
                detailsBuilder.or(productDetails.value.likeIgnoreCase("%" + params.getMaterial() + "%"));
            }
            if (params.getGender() != null && !params.getGender().isEmpty()) {
                detailsBuilder.or(productDetails.value.likeIgnoreCase("%" + params.getGender() + "%"));
            }
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

    /**
     * 필터 메뉴의 색상. 카테고리가 없으면 전체, 있으면 그 카테고리 상품들의 것.
     *
     * <p>빈 목록은 여기서 거른다 — 상품이 없는 카테고리에 {@code in ()} 을 보내면 SQL 오류다.
     * (예전 {@code unnest(CAST(:ids AS BIGINT[]))} 는 반대로 상품이 <b>있을 때</b> 터졌다 —
     * ProductSkuRepository 주석.)
     */
    @Cacheable(value = "colors_cache", key = "#categoryId", cacheManager = "redisCacheManager")
    public List<String> getColors(Long categoryId) {
        if (categoryId == null) {
            return productSkuRepository.findAllColors();
        }
        List<Long> ids = productRepository.findProductIDsByCategoryId(categoryId);
        return ids.isEmpty() ? List.of() : productSkuRepository.findColorsByProductId(ids);
    }

    @Cacheable(value = "sizes_cache", key = "#categoryId", cacheManager = "redisCacheManager")
    public List<String> getSizes(Long categoryId) {
        if (categoryId == null) {
            return productSkuRepository.findAllSizes();
        }
        List<Long> ids = productRepository.findProductIDsByCategoryId(categoryId);
        return ids.isEmpty() ? List.of() : productSkuRepository.findSizesByProductId(ids);
    }
    
    @Cacheable(value = "details_cache", key = "#categoryId", cacheManager = "redisCacheManager")
    public List<ProductDetailsDto> getDetails(Long categoryId) {
        if (categoryId == null) {
            return productDetailsRepository.findDistinctAll().stream()
                    .map(ProductDetailsMapper::toDto)
                    .collect(Collectors.toList());
        }
        List<Long> ids = productRepository.findProductIDsByCategoryId(categoryId);
        return productDetailsRepository.findDistinctAllByProductProductIdIn(ids).stream()
                .map(ProductDetailsMapper::toDto)
                .collect(Collectors.toList());
    }

    @Cacheable(value = "products_by_category", key = "#category + '_' + #pageRequest.pageNumber", cacheManager = "redisCacheManager")
    public List<Product> findAllByCategory(Long category, Pageable pageRequest) {

        Page<Product> productPage = productRepository.findAllByCategory_CategoryId(category, pageRequest);

        return productPage.getContent();
    }
}