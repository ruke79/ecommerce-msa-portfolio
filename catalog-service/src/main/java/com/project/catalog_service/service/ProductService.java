package com.project.catalog_service.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import java.util.stream.Collectors;

import org.apache.commons.codec.binary.Base64;
import org.checkerframework.checker.units.qual.s;
import org.springframework.beans.BeansException;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.data.domain.Page;

import org.springframework.data.domain.PageRequest;

import org.springframework.stereotype.Service;

import org.springframework.web.multipart.MultipartFile;

import com.project.common.dto.catalog.request.CartProductInfoRequest;
import com.project.common.dto.ProductColorDto;
import com.project.common.dto.ProductDto;
import com.project.common.dto.ProductDetailsDto;
import com.project.common.dto.ProductInfoDto;
import com.project.common.dto.ProductQADto;
import com.project.common.dto.ProductSkuDto;
import com.project.common.dto.ProductSizeDto;
import com.project.common.dto.SubCategoryDto;
import com.project.common.dto.catalog.request.ProductAddRequest;
import com.project.common.dto.catalog.request.ProductRequest;
import com.project.common.message.dto.request.OrderProductRequest;
import com.project.common.message.dto.request.ProductUpdateRequest;
import com.project.catalog_service.model.Product;
import com.project.catalog_service.model.Category;
import com.project.catalog_service.model.ProductColor;
import com.project.catalog_service.model.ProductDetails;
import com.project.catalog_service.model.ProductQA;
import com.project.catalog_service.model.ProductSize;
import com.project.catalog_service.model.ProductSku;
import com.project.catalog_service.model.ProductSubcategory;
import com.project.catalog_service.model.Subcategory;
import com.project.catalog_service.repository.jpa.data.CategoryRepository;
import com.project.catalog_service.repository.jpa.data.ProductColorRepository;
import com.project.catalog_service.repository.jpa.data.ProductQARepository;
import com.project.catalog_service.repository.jpa.data.ProductRepository;
import com.project.catalog_service.repository.jpa.data.ProductSizeRepository;
import com.project.catalog_service.repository.jpa.data.ProductSkuRepository;
import com.project.catalog_service.repository.jpa.data.SubcategoryRepository;
import com.project.catalog_service.util.CursorPagenation;
import com.project.common.util.FileUtil;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.catalog_service.dto.response.ProductResponse;
import com.project.catalog_service.mapper.ProductColorMapper;
import com.project.catalog_service.mapper.ProductDetailsMapper;
import com.project.catalog_service.mapper.ProductMapper;
import com.project.catalog_service.mapper.ProductQAMapper;
import com.project.catalog_service.mapper.ProductSizeMapper;

//import jakarta.mail.Multipart;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ProductService  {

    
    private final CategoryRepository categoryRepository;

    private final SubcategoryRepository subCategoryRepository;

    private final ProductRepository productRepository;

    private final ProductSizeRepository productSizeRepository;

    private final ProductSkuRepository productskuRepository;

    private final ProductColorRepository productColorRepository;

    private final ProductQARepository productQARepository;

    private final ImageService imageService;

    // 실시간 ES 반영. search.engine=es 가 아니면 내부에서 no-op 이다.
    private final ProductIndexService productIndexService;

    /**
     * 진행 중인 기획전 할인을 조회 결과에 얹는다.
     *
     * <p>DB 의 {@code ProductSku.discount} 는 <b>기획전과 무관한 상시 할인</b> 하나의 뜻만
     * 갖는다. 기획전이 시작할 때 그 값을 덮어쓰면 종료 시 복원이 그 사이의 수정을 덮기
     * 때문이다(EventPricing 주석). 합성은 {@link #convertToDto} 한 곳에서만 한다 — 새
     * 조회 경로가 생겨도 그 funnel 을 지나면 자동으로 적용된다.
     *
     * <p>캐시에 합성된 값이 들어가지만, 기획전이 시작·종료할 때 {@code EventScheduler} 가
     * 상품 캐시를 비운다. 그래서 stale 구간은 상태 전환 시점 하나뿐이다.
     */
    private final EventPricing eventPricing;

    /** 재고가 0 을 건너면(품절·재입고) 알린다 — docs/53. */
    private final com.project.catalog_service.message.producer.StockEventProducer stockEvents;

    // private final ObjectMapper objectMapper;

    // @Resource(name = "redisTemplate")
    // private ValueOperations<String, Object> productsOps;
    

    @Cacheable(value = "products", key = "#categoryName + '_' + #cursor + '_' + #pageSize")
    public List<ProductDto> getProductsByCategory(String categoryName, Long cursor, int pageSize) {
        PageRequest pageRequest = PageRequest.of(0, pageSize + 1);

        Page<Product> productsPage;
        if (cursor < 0) {
            productsPage = productRepository.findAllByCategory_CategoryNameOrderByProductIdDesc(categoryName,
                    pageRequest);
        } else {
            productsPage = productRepository.findAllByCategory_CategoryNameAndProductIdLessThanOrderByProductIdDesc(
                    categoryName, cursor, pageRequest);
        }
        
        CursorPagenation<Product> productCursor = CursorPagenation.of(productsPage.getContent(), pageSize);
        // ProductResponse 는 static 팩토리라 매퍼를 직접 쓴다 — convertToDto funnel 을 타지
        // 않으므로 여기서 기획전 할인을 얹는다.
        return eventPricing.apply(
                ProductResponse.of(productCursor, productsPage.getContent().size()).getContents());
    }
    /**
     * 관리자 상품 목록 — 커서 페이징.
     *
     * <p>🐛 <b>예전에는 관리자 화면이 카탈로그 전체를 한 번에 받았다.</b>
     * {@code GET /api/product/products} → {@code warmUpProductCaches(20)} 인데, 이름과 달리
     * 이것은 전 카테고리 × 전 서브카테고리를 돌며 <b>모든 상품을 한 배열로</b> 만든다.
     * 게다가 {@code @Cacheable(key = "'warmup'")} 이라 <b>카탈로그 전체가 Redis 키 하나</b>에
     * 들어갔다. 상품이 250개라 견디고 있었을 뿐, 규모가 커지면
     *
     * <ul>
     *   <li>응답 JSON 이 수 MB → 게이트웨이 타임아웃, 브라우저 메모리</li>
     *   <li>Redis 키 하나가 수십 MB → maxmemory 정책에 따라 다른 키를 밀어내거나
     *       이 키가 evict 되어 매번 전 카탈로그를 다시 조회</li>
     * </ul>
     *
     * <p><b>캐시하지 않는다.</b> 관리자 목록은 방금 등록한 상품이 보여야 하는 화면이고,
     * 커서마다 키가 갈려 캐시 적중률도 낮다. 무엇보다 위의 Redis 문제가 캐시 때문이었다.
     *
     * @param cursor 마지막으로 받은 상품 id. 첫 페이지는 음수({@code -1}).
     */
    @Transactional(readOnly = true)
    public ProductResponse getProductsPage(Long cursor, int pageSize) {
        // 상한을 둔다. pageSize=100000 이 오면 결국 전체 조회가 되어 고친 의미가 없다.
        int safeSize = Math.max(1, Math.min(pageSize, MAX_ADMIN_PAGE_SIZE));

        // +1 을 더 읽어 "다음이 있는가" 를 판정한다(CursorPagenation).
        PageRequest pageRequest = PageRequest.of(0, safeSize + 1);

        Page<Product> page = (cursor == null || cursor < 0)
                ? productRepository.findAllByOrderByProductIdDesc(pageRequest)
                : productRepository.findAllByProductIdLessThanOrderByProductIdDesc(cursor, pageRequest);

        CursorPagenation<Product> cursorPage = CursorPagenation.of(page.getContent(), safeSize);

        // 전체 건수는 목록에 "총 N개" 를 보여주기 위한 것이다. 커서 페이징은 총계를 몰라도
        // 동작하므로, 한 번만(첫 페이지에서만) 센다 — 페이지마다 count(*) 를 돌릴 이유가 없다.
        long total = (cursor == null || cursor < 0) ? productRepository.count() : -1;

        ProductResponse response = ProductResponse.of(cursorPage, total);
        // 관리자 목록도 고객이 보는 것과 같은 할인을 보여야 한다. 여기만 정가면 관리자는
        // 기획전이 걸린 줄 모르고 상품을 수정한다.
        eventPricing.apply(response.getContents());
        return response;
    }

    /** 관리자 목록 한 페이지의 상한. */
    public static final int MAX_ADMIN_PAGE_SIZE = 100;

    @Cacheable(value = "products", key = "#productName")
    public List<ProductDto> getProductsByName(String productName) {

        List<Product> products = productRepository.findByName(productName);

        List<ProductDto> result = new ArrayList<>();

        if (products != null) {

            for (Product p : products) {
                ProductDto dto = convertToDto(p);
                result.add(dto);
            }

            return result;
        }
        return null;
    }

    

    @Cacheable(value = "products", key = "'all'")
    public List<ProductDto> getProducts() {
        List<Product> products = productRepository.findAll();
        return products.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }


     
    @Cacheable(value = "products", key = "'warmup'")
    public List<ProductDto> warmUpProductCaches(int pageSize) {
        
        log.info("Starting to warm up product caches...");        

        // 1. 모든 카테고리 목록을 가져옵니다.
        List<Category> allCategories = categoryRepository.findAll();
        List<ProductDto> products = new ArrayList<>();

        for (Category category : allCategories) {
            // 2. 현재 카테고리에 속한 모든 서브카테고리 목록을 가져옵니다.
            List<Subcategory> allSubcategories = subCategoryRepository.findByCategory(category);
            
            for (Subcategory subcategory : allSubcategories) {
                int page = 0;
                
                    log.info("Fetching products for Category: {}, Subcategory: {}, Page: {}",
                            category.getCategoryName(), subcategory.getSubcategoryName(), page);

                    // 3. getProductsByCategoryAndSubcategory 메소드를 호출하여 캐시를 채웁니다.
                    products.addAll( getProductsByCategoryAndSubcategory(
                            category.getCategoryName(), subcategory.getSubcategoryName(), page, pageSize));

                
                    page++; // 다음 페이지로 이동
                
            }
        }
        log.info("Product cache warm-up completed.");
        return products;
    }

     /**
     * 카테고리 및 서브카테고리별로 상품을 10개씩 페이지네이션하여 조회합니다.
     * @param categoryName
     * @param subcategoryName
     * @param page
     * @return
     */
    // ⚠️ 키에 pageSize 가 **빠져 있었다.** /api/product/frontpage 는 pageSize=4 로,
    //    /api/product/products 는 20 으로 같은 메서드를 부르는데 키가 같아 **한 엔트리를
    //    공유했다** — 먼저 호출된 쪽이 이겨서, 홈에 20개가 깔리거나 목록에 4개만 나왔다.
    //    캐시가 비어 있을 때만 재현되니 "가끔 개수가 다르다"로 보였다.
    //    같은 파일의 getProductsByCategory(114행)는 처음부터 pageSize 를 키에 넣고 있었다.
    @Cacheable(value = "products",
            key = "#categoryName + '_' + #subcategoryName + '_' + #page + '_' + #pageSize")
    public List<ProductDto> getProductsByCategoryAndSubcategory(String categoryName, String subcategoryName, int page, int pageSize) {
         // 기본 페이지 크기를 10으로 설정합니다.
        // 한 페이지에 1개씩 가져오도록 PageRequest 객체를 생성합니다.
        PageRequest pageRequest = PageRequest.of(page, pageSize);

        // ProductRepository에 새로운 쿼리 메소드를 추가하여 사용해야 합니다.
        // 이 쿼리 메소드는 categoryName과 subcategoryName을 기준으로 결과를 필터링합니다.
        // 이 메소드명은 JPA 규칙을 따르며, ProductSubcategory 엔티티를 통해 조인됩니다.
        Page<Product> productsPage = productRepository
                .findAllByCategory_CategoryNameAndSubcategories_Subcategory_SubcategoryName(
                        categoryName, subcategoryName, pageRequest);

        return productsPage.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    @Cacheable(value = "product", key = "#productId")
    public ProductDto getProductById(Long productId) {
        return productRepository.findById(productId)
                .map(this::convertToDto)
                .orElse(null);
    }

    /**
     * 여러 상품을 <b>한 번에</b> 조회한다 — cart-service 가 장바구니 항목 수만큼
     * {@link #getProductById} 를 직렬로 부르던 것을 대체한다(docs/38 §1.13).
     *
     * <p>캐시를 타지 않는다. {@code @Cacheable} 은 id 하나에 DTO 하나를 묶는 구조라 목록에는
     * 맞지 않고, 여기서 항목별로 캐시를 뒤지면 결국 항목 수만큼 왕복이 되어 배치의 의미가
     * 없어진다. DB 한 번(IN 절) + {@code @BatchSize} 로 묶인 컬렉션 조회가 항목 수와 무관하게
     * 상수 번이라 그편이 낫다. 찾지 못한 id 는 결과에서 그냥 빠진다 — 호출자가 id 로 대조한다.
     * (DELETED 는 {@code @SQLRestriction} 이 거른다.)
     */
    @Transactional(readOnly = true)
    public List<ProductDto> getProductsByIds(Collection<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return List.of();
        }
        return productRepository.findAllById(productIds).stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    @Cacheable(value = "products", key = "#slug")
    public List<ProductDto> getProductsBySlug(String slug) {
        List<Product> products = productRepository.findBySlug(slug);
        return products.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }
    
    // 상품 변경 시 검색/필터 관련 캐시(TTL 무효화만으로는 최대 60분 stale)를 즉시 무효화한다.
    @CacheEvict(value = {"search_cache", "colors_cache", "sizes_cache", "details_cache", "products_by_category"}, allEntries = true)
    @CachePut(value = "product", key = "#productId")
    public ProductDto updateRating(Long productId, float rating) {
        productRepository.updateRating(productId, rating);
        Product product = productRepository.findById(productId).orElseThrow();
        return convertToDto(product);
    }

    
    public List<ProductSku> load(List<ProductAddRequest> products, List<MultipartFile> images,
            List<MultipartFile> colorImages) throws IOException {

        List<ProductSku> result = new ArrayList<>();

        int i = 0;
        for (ProductAddRequest pr : products) {

            ProductSku sku = null;

            sku = load(pr, images.get(i), colorImages.get(i));
            result.add(sku);
            i++;
        }

        return result;
    }

    
    public ProductSku load(ProductAddRequest request, MultipartFile images, MultipartFile colorImage)
            throws IOException {
        Product product = createOrFindProduct(request);
        return loadSku(request, product, images, colorImage);
    }

    // private 이라 @Transactional · @CachePut 이 돌지 않았다 — 그리고 "product" 캐시에 엔티티를 넣으려 했다. 둘 다 지웠다.
    private Product createOrFindProduct(ProductAddRequest request) {
        if (request.getParent() != null && !request.getParent().isEmpty()) {
            return productRepository.findById(Long.parseLong(request.getParent()))
                    .orElseThrow(() -> new IllegalArgumentException("Parent product not found"));
        } else {
            Category category = createOrFindCategory(request.getCategory().getName(), request.getCategory().getSlug());
            List<Subcategory> subcategories = createOrFindSubcategories(category, request.getSubCategories());

            Product product = Product.builder()
                    .brand(request.getBrand())
                    .name(request.getName())
                    .description(request.getDescription())
                    .slug(request.getSlug())
                    .shipping(new BigDecimal(request.getShippingFee()))
                    .category(category)
                    .build();

            List<ProductSubcategory> productSubcategories = subcategories.stream()
                    .map(sub -> ProductSubcategory.builder().product(product).subcategory(sub).build())
                    .collect(Collectors.toList());
            product.setSubcategories(productSubcategories);

            productRepository.save(product);
            return product;
        }
    }

    private Category createOrFindCategory(String categoryName, String slug) {
        return categoryRepository.findByCategoryName(categoryName)
                .orElseGet(() -> categoryRepository.save(new Category(categoryName, slug)));
    }

    private List<Subcategory> createOrFindSubcategories(Category category, List<SubCategoryDto> subCategoryDtos) {
        List<String> subcategoryNames = subCategoryDtos.stream()
                .map(SubCategoryDto::getName)
                .collect(Collectors.toList());
        List<Subcategory> existingSubcategories = subCategoryRepository
                .findByCategory_CategoryNameAndSubcategoryNameIn(category.getCategoryName(), subcategoryNames);

        List<Subcategory> newSubcategories = subCategoryDtos.stream()
                .filter(dto -> existingSubcategories.stream()
                        .noneMatch(existing -> existing.getSubcategoryName().equals(dto.getName())))
                .map(dto -> new Subcategory(dto.getName(), dto.getSlug(), category))
                .collect(Collectors.toList());

        subCategoryRepository.saveAll(newSubcategories);
        existingSubcategories.addAll(newSubcategories);
        return existingSubcategories;
    }

    @Transactional
    // 신규 상품 등록 시 검색/필터 캐시를 무효화하지 않으면 최대 60분(search_cache TTL) 동안
    // 등록한 상품이 검색 결과에 나타나지 않는다.
    // 🐛 예전 @CachePut(value = "product", key = "#result.productId") — 돌려주는 ProductSku 에는 productId 가 없어 프록시에서
    //    SpEL 예외(EL1008E)로 등록이 500 이 됐고, 됐더라도 ProductDto 를 담는 "product" 캐시에 엔티티를 넣었다. 부모 상품의
    //    캐시를 <b>지운다</b> — 다음 조회가 새 변형까지 담아 다시 만든다(ProductCacheKeyTest).
    @org.springframework.cache.annotation.Caching(evict = {
            @CacheEvict(value = {"search_cache", "colors_cache", "sizes_cache", "details_cache", "products_by_category"}, allEntries = true),
            @CacheEvict(value = "product", key = "#result.product.productId")
    })
    // ⚠️ 이 메서드는 예전에 세 가지 방식으로 조용히 잘못됐다. 전부 실배포에서 확인된
    //    형태라 고친 이유를 남긴다:
    //
    //    · `Optional<Category> category` 를 `if (category != null)` 로 검사했다 —
    //      Optional 은 null 이 아니므로 **항상 참**이고, 없는 카테고리면 `category.get()`
    //      이 NoSuchElementException 으로 500 을 냈다. 검증이 아니라 폭발이었다.
    //    · 카테고리·부모를 못 찾으면 `return null` — 컨트롤러가 그걸 **200 OK 로**
    //      돌려줘서 화면은 "등록 완료"를 띄우는데 실제로는 아무것도 안 만들어졌다.
    //    · slug 에 아무 제약이 없어 같은 이름을 두 번 등록하면 상세 조회(`findBySlug`)가
    //      임의의 것을 돌려줬다.
    //
    //    이제 잘못된 입력은 IllegalArgumentException 으로 던진다(컨트롤러가 400 으로
    //    변환). null 반환 경로는 없다.
    public ProductSku addProduct(ProductRequest request, List<MultipartFile> images, MultipartFile colorImage)
            throws IOException {

        if (images == null || images.isEmpty()) {
            // createSku 가 images 를 그대로 순회하고 colorImage 를 무조건 업로드한다.
            // 시그니처의 required=false 는 거짓말이다 — 여기서 명확히 끊는다.
            throw new IllegalArgumentException("이미지가 없습니다 — 최소 1장이 필요합니다");
        }
        if (colorImage == null || colorImage.isEmpty()) {
            throw new IllegalArgumentException("색상(스타일) 이미지가 없습니다");
        }

        if (request.getParent() != null && request.getParent().length() > 0) {

            Product parent = productRepository.findById(parseId(request.getParent(), "parent"))
                    .orElseThrow(() -> new IllegalArgumentException(
                            "부모 상품이 없습니다: id=" + request.getParent()));

            ProductSku newSku = createSku(request, parent, images, colorImage, false);   // 변형 — 상품 정보는 그대로
            productRepository.save(parent);
            productIndexService.index(parent);
            return newSku;
        }

        Product product = Product.builder()
                .brand(request.getBrand())
                .name(request.getName())
                .description(request.getDescription())
                .slug(uniqueSlug(request.getSlug(), request.getName()))
                .build();

        try {
            product.setShipping(new BigDecimal(request.getShippingFee()));
        } catch (NumberFormatException | NullPointerException e) {
            throw new IllegalArgumentException(
                    "배송비가 숫자가 아닙니다: " + request.getShippingFee());
        }

        Category category = categoryRepository.findById(parseId(request.getCategory(), "category"))
                .orElseThrow(() -> new IllegalArgumentException(
                        "카테고리가 없습니다: id=" + request.getCategory()));
        product.setCategory(category);

        List<Long> subcategoryIds = new ArrayList<>();
        if (request.getSubCategories() != null) {
            for (String subcategory : request.getSubCategories()) {
                subcategoryIds.add(parseId(subcategory, "subCategory"));
            }
        }

        List<Subcategory> subCategories = subCategoryRepository.findBySubcategoryIdIn(subcategoryIds);
        if (subCategories.size() != subcategoryIds.size()) {
            // 일부만 존재하면 "찾은 것만" 붙이고 조용히 넘어가는 편이 더 나쁘다 —
            // 화면에서 고른 분류와 실제 저장이 어긋난 채 성공한 것으로 보인다.
            throw new IllegalArgumentException(
                    "존재하지 않는 서브카테고리가 있습니다: 요청 " + subcategoryIds.size()
                            + "개 중 " + subCategories.size() + "개만 찾음");
        }
        // 🐛 하위 카테고리가 상품의 카테고리에 속하는지 보지 않았다. 시드 데이터의 연결 81% 가 다른 카테고리였다
        //    (올리브유가 "테이블 · 스마트폰" 에) — 검색 화면의 하위 카테고리 필터가 엉뚱한 상품을 보여 준 원인이다(V16).
        for (Subcategory sub : subCategories) {
            if (sub.getCategory() == null || !sub.getCategory().getCategoryId().equals(category.getCategoryId())) {
                throw new IllegalArgumentException("서브카테고리가 상품의 카테고리에 속하지 않습니다: " + sub.getSubcategoryId());
            }
        }

        List<ProductSubcategory> list = new ArrayList<>();
        for (Subcategory sub : subCategories) {
            list.add(ProductSubcategory.builder()
                    .product(product)
                    .subcategory(sub)
                    .build());
        }
        product.setSubcategories(list);

        productRepository.save(product);
        ProductSku created = createSku(request, product, images, colorImage, true);
        // 색인은 SKU(이미지·가격)까지 저장된 뒤에 — 문서에 SKU 목록이 들어간다.
        productIndexService.index(product);
        return created;
    }

    /**
     * SKU 자동 생성: {@code <카테고리 접두사 3자>-<시퀀스 6자리>} — 예: {@code FOO-000123}.
     *
     * <p>접두사는 사람이 읽을 힌트일 뿐이다 — 카테고리가 바뀌어도 SKU 는 그대로 둔다.
     * 속성을 코드에 새기는 순간 속성이 변할 때 코드가 거짓말을 하게 되므로, 신뢰하는
     * 정보는 전부 DB 에 있고 코드는 식별만 한다.
     *
     * <p>번호는 DB 시퀀스라 레플리카가 몇 개든 충돌하지 않는다. 그래도 존재 검사를
     * 하는 이유: 관리자가 손으로 넣은 SKU 가 우연히 같은 모양(예: FOO-000200)일 수
     * 있다. 그 경우 다음 번호로 넘어간다.
     */
    private String generateSku(Product product) {
        String prefix = skuPrefix(
                product.getCategory() != null ? product.getCategory().getCategoryName() : null);
        for (int attempt = 0; attempt < 10; attempt++) {
            Long n = productskuRepository.nextSkuNumber();
            String candidate = String.format("%s-%06d", prefix, n);
            if (!productskuRepository.existsBySku(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "SKU 생성이 10회 연속 충돌했습니다 — product_sku_seq 시퀀스 상태를 확인하세요");
    }

    /**
     * 카테고리 이름에서 접두사 3자를 뽑는다. ASCII 영숫자만 쓴다 — SKU 는 창고 라벨·
     * 전화 응대에서 불러 읽는 코드라 대문자 영숫자로 제한하는 것이 관례다. 한글 등
     * ASCII 밖 이름이라 3자를 못 채우면 중립 접두사 PRD 로 떨어진다.
     */
    public static String skuPrefix(String categoryName) {
        if (categoryName == null) {
            return "PRD";
        }
        String alnum = categoryName.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        return alnum.length() >= 3 ? alnum.substring(0, 3) : "PRD";
    }

    /**
     * 상품 기본 정보 수정. 비어 있지 않은 필드만 반영한다(부분 수정).
     *
     * <p>slug 는 **바꾸지 않는다** — 상세 페이지 URL 이라 바꾸면 검색엔진·공유 링크·
     * 장바구니의 기존 참조가 전부 404 가 된다. 이름을 고쳐도 주소는 남는다.
     */
    @org.springframework.cache.annotation.CacheEvict(
            value = { "products", "product", "search_cache", "products_by_category" }, allEntries = true)
    public ProductDto updateProduct(Long productId, com.project.common.dto.catalog.request.ProductEditRequest request) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("상품이 없습니다: id=" + productId));

        if (request.getName() != null && !request.getName().isBlank()) {
            product.setName(request.getName().trim());
        }
        if (request.getDescription() != null && !request.getDescription().isBlank()) {
            product.setDescription(request.getDescription().trim());
        }
        if (request.getBrand() != null && !request.getBrand().isBlank()) {
            product.setBrand(request.getBrand().trim());
        }
        if (request.getShippingFee() != null && !request.getShippingFee().isBlank()) {
            try {
                product.setShipping(new BigDecimal(request.getShippingFee().trim()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("배송비가 숫자가 아닙니다: " + request.getShippingFee());
            }
        }
        if (request.getRefundPolicy() != null && !request.getRefundPolicy().isBlank()) {
            product.setRefundPolicy(request.getRefundPolicy().trim());
        }

        Product saved = productRepository.save(product);
        productIndexService.index(saved);
        return ProductMapper.toDto(saved);
    }

    /**
     * 상태 변경 — 품절(SOLD_OUT)·복귀(ACTIVE)·삭제(DELETED)가 전부 이 경로다.
     *
     * <p>DELETED 로 바꾼 뒤에는 이 메서드로도 **되돌릴 수 없다** — @SQLRestriction 이
     * findById 에서 이미 걸러 "상품이 없습니다" 가 된다. 의도된 동작이다(V8 주석).
     */
    @org.springframework.cache.annotation.CacheEvict(
            value = { "products", "product", "search_cache", "products_by_category" }, allEntries = true)
    public ProductDto updateStatus(Long productId, String status) {
        if (!"ACTIVE".equals(status) && !"SOLD_OUT".equals(status) && !"DELETED".equals(status)) {
            throw new IllegalArgumentException(
                    "status 는 ACTIVE·SOLD_OUT·DELETED 중 하나여야 합니다: " + status);
        }
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("상품이 없습니다: id=" + productId));

        product.setStatus(status);
        Product saved = productRepository.save(product);

        // 품절은 검색에 남긴다(목록과 같은 동작 — 보이되 담기지 않는다).
        // 삭제만 색인에서 뺀다 — DB 조회는 @SQLRestriction 이 걸러 주지만 ES 는
        // 별도 저장소라 직접 지워야 한다.
        if ("DELETED".equals(status)) {
            productIndexService.remove(productId);
        } else {
            productIndexService.index(saved);
        }
        return ProductMapper.toDto(saved);
    }

    private static Long parseId(String raw, String field) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException | NullPointerException e) {
            throw new IllegalArgumentException(field + " id 가 숫자가 아닙니다: " + raw);
        }
    }

    /**
     * slug 를 정하고 **유일하게** 만든다.
     *
     * <p>클라이언트가 준 slug 를 신뢰하지 않는 이유: 비거나, 같은 이름의 상품이 이미
     * 있을 수 있다. slug 는 상세 페이지 URL 이라 중복이면 {@code findBySlug} 가 여러
     * 건을 돌려주고 어느 것이 보일지는 우연이 정한다. V6 마이그레이션의 UNIQUE 제약이
     * 최후 방어선이고, 여기서는 충돌 시 "-2", "-3" … 를 붙여 제약에 걸리기 전에 피한다.
     */
    public String uniqueSlug(String requested, String name) {
        String base = (requested == null || requested.isBlank())
                ? String.valueOf(name).trim().toLowerCase().replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", "-")
                        .replaceAll("(^-|-$)", "")
                : requested.trim();
        if (base.isBlank()) {
            throw new IllegalArgumentException("slug 를 만들 수 없습니다 — 상품 이름을 확인하세요");
        }
        String candidate = base;
        int n = 1;
        while (!productRepository.findBySlug(candidate).isEmpty()) {
            n++;
            candidate = base + "-" + n;
            if (n > 100) {
                // 같은 이름이 100개면 데이터가 아니라 다른 문제다. 무한루프 방지.
                throw new IllegalStateException("slug 충돌이 100회를 넘었습니다: " + base);
            }
        }
        return candidate;
    }
    
    /**
     * 요청의 사이즈들을 엔티티로 — <b>부모(스타일)를 가리킨 채로.</b>
     *
     * <p>🐛 예전 코드는 이랬다.
     * <pre>
     * request.getSizes().forEach(size -&gt; ProductSizeMapper.toEntity(size).setSku(skuProject));  // ①
     * skuProject.setSizes(request.getSizes().stream().map(ProductSizeMapper::toEntity).toList()); // ②
     * </pre>
     * ①은 매번 <b>새 객체</b>를 만들어 부모를 걸고 그대로 버린다. 실제로 저장되는 것은 ②가 <b>다시</b> 만든 객체라
     * 부모가 비어 있다. {@code sizes} 는 {@code mappedBy="sku"} — 외래 키를 가진 쪽은 사이즈이므로, 사이즈의
     * {@code sku} 가 비면 {@code product_size.skuproduct_id} 가 NULL 로 저장된다. 그 사이즈는 어느 스타일에도
     * 속하지 않게 되어 <b>관리자가 등록한 상품은 사이즈가 없는 상품이 됐다</b> — 상세 화면에 가격이 없고 장바구니에
     * 담을 수 없다. 상세 · 질문도 같은 모양({@code mappedBy="product"})이라 함께 고친다.
     */
    private static List<ProductSize> sizesOf(List<ProductSizeDto> requested, ProductSku parent) {
        if (requested == null) {
            return new ArrayList<>();
        }
        List<ProductSize> sizes = new ArrayList<>();
        for (ProductSizeDto dto : requested) {
            ProductSize size = ProductSizeMapper.toEntity(dto);
            if (size == null) {
                continue;
            }
            size.setSku(parent);
            sizes.add(size);
        }
        return sizes;
    }

    /**
     * {@link #sizesOf} 와 같은 이유 — 저장되는 객체에 부모를 건다. <b>새 상품에만</b> 쓴다.
     *
     * <p>🐛 예전: 변형(기존 상품에 스타일 추가) · 대량 적재의 부모에서도 불렀고, 컬렉션을 통째로 갈아 끼워 flush 가
     * "orphan deletion 컬렉션" 예외로 거절됐다 — 변형 등록이 늘 500 이었다. 설령 통과했어도 부모의 상세를 변형 양식의
     * 빈 줄로 덮어썼을 것이다. 변형은 스타일을 더할 뿐 상품 정보를 바꾸지 않는다. 내용은 {@link Product#replaceDetails} 로 바꾼다.
     */
    private static void attachDetailsAndQuestions(Product product, Collection<ProductDetailsDto> details,
            Collection<ProductQADto> questions) {
        if (details != null) {
            Set<ProductDetails> entities = new HashSet<>();
            for (ProductDetailsDto dto : details) {
                ProductDetails d = ProductDetailsMapper.toEntity(dto);
                if (d != null) {
                    d.setProduct(product);
                    entities.add(d);
                }
            }
            product.replaceDetails(entities);
        }
        if (questions != null) {
            Set<ProductQA> entities = new HashSet<>();
            for (ProductQADto dto : questions) {
                ProductQA q = ProductQAMapper.toEntity(dto);
                if (q != null) {
                    q.setProduct(product);
                    entities.add(q);
                }
            }
            product.replaceQuestions(entities);
        }
    }

    /**
     * 방금 저장한 스타일의 사이즈마다 SKU 코드를 채운다(V17 의 DB 함수 — 규칙은 거기에만 있다).
     *
     * <p>함수는 DB 의 행을 고치므로 메모리의 엔티티는 모른다. 이 엔티티로 곧바로 응답 · 색인 · 캐시를 만들기 때문에
     * 채운 값을 다시 읽어 옮겨 적는다.
     */
    private ProductSku withSizeSkuCodes(ProductSku saved) {
        productSizeRepository.flush();   // 네이티브 함수가 방금 INSERT 한 행을 보도록
        productSizeRepository.assignSkuCodes();
        if (saved.getSkuproductId() == null || saved.getSizes() == null) {
            return saved;
        }
        Map<Long, String> codes = new HashMap<>();
        for (Object[] row : productSizeRepository.skuCodesOf(saved.getSkuproductId())) {
            codes.put(((Number) row[0]).longValue(), (String) row[1]);
        }
        for (ProductSize size : saved.getSizes()) {
            if (size.getSizeId() != null) {
                size.setSkuCode(codes.get(size.getSizeId()));
            }
        }
        return saved;
    }

    private ProductSku loadSku(ProductAddRequest request, Product product, MultipartFile image,
            MultipartFile colorImage) throws IOException {

        ProductSku skuProject = ProductSku.builder()
                .sku(request.getSku())
                .build();

        if (request.getDiscount() != null)
            skuProject.setDiscount(Integer.parseInt(request.getDiscount()));

        // ArrayList<String> bytes = new ArrayList<String>();
        // for(MultipartFile image : images) {
        // bytes.add(encodeFileToBase64((image)));

        // }
        // if (bytes.size() > 0)
        // skuProject.setImages(bytes);
        // skuProject.getColor().setColorImage(encodeFileToBase64(colorImage));

        List<String> imageUrls = new ArrayList<String>();

        String filename = FileUtil.getRandomFilename();
        String filepath = imageService.upload(image, filename);
        imageUrls.add(filepath);

        skuProject.setImages(imageUrls);

        filename = "color_" + FileUtil.getRandomFilename();
        String colorImageUrl = imageService.upload(colorImage, filename);

        skuProject.setColor(ProductColor.builder().colorImage(colorImageUrl)
                .color(request.getColor().getColor()).build());

        productColorRepository.save(skuProject.getColor());

        skuProject.setSizes(sizesOf(request.getSizes(), skuProject));
        // 부모에 붙이는 변형이면 상품 정보는 건드리지 않는다(createSku 와 같은 이유).
        if (request.getParent() == null || request.getParent().isEmpty()) {
            attachDetailsAndQuestions(product, request.getDetails(), request.getQuestions());
        }

        skuProject.setProduct(product);
        product.getSkus().add(skuProject);
        return withSizeSkuCodes(productskuRepository.save(skuProject));

    }

    private ProductSku createSku(ProductRequest request, Product product, List<MultipartFile> images,
            MultipartFile colorImage, boolean newProduct) throws IOException {

        // SKU: 비우면 생성하고, 주면 중복을 거절한다. 어느 쪽이든 유일성은 V7 의
        // UNIQUE 인덱스가 최후 방어선이다 — 여기 검사는 좋은 에러 메시지를 위한 것이다.
        String sku;
        if (request.getSku() == null || request.getSku().isBlank()) {
            sku = generateSku(product);
        } else {
            sku = request.getSku().trim();
            if (productskuRepository.existsBySku(sku)) {
                throw new IllegalArgumentException(
                        "이미 사용 중인 SKU 입니다: " + sku + " — 비워 두면 자동 생성됩니다");
            }
        }

        ProductSku skuProject = ProductSku.builder()
                .sku(sku)
                .build();

        if (request.getDiscount() != null && !request.getDiscount().isBlank()) {
            try {
                skuProject.setDiscount(Integer.parseInt(request.getDiscount()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("할인율이 숫자가 아닙니다: " + request.getDiscount());
            }
        }

        skuProject.setColor( ProductColorMapper.toEntity(request.getColor()));

        // ⚠️ 여기서부터는 S3 에 부수효과가 생긴다. 업로드 **뒤에** 어떤 단계든 실패하면
        //    (엔티티 매핑, DB 저장, 다음 업로드…) 이미 올라간 파일은 아무도 참조하지
        //    않는 고아가 된다 — 눈에 안 보이고 요금만 나간다. 그래서 올린 것을 전부
        //    기록해 두고, 실패하면 지운 뒤 원래 예외를 그대로 다시 던진다.
        List<String> uploaded = new ArrayList<>();
        try {
            List<String> imageUrls = new ArrayList<String>();

            for (MultipartFile image : images) {
                String filename = FileUtil.getRandomFilename();
                String filepath = imageService.upload(image, filename);
                uploaded.add(filepath);
                imageUrls.add(filepath);
            }

            skuProject.setImages(imageUrls);

            String filename = "color_" + FileUtil.getRandomFilename();
            String colorImageUrl = imageService.upload(colorImage, filename);
            uploaded.add(colorImageUrl);

            skuProject.getColor().setColorImage(colorImageUrl);

            skuProject.setSizes(sizesOf(request.getSizes(), skuProject));
            if (newProduct) {
                attachDetailsAndQuestions(product, request.getDetails(), request.getQuestions());
            }

            skuProject.setProduct(product);
            // 양쪽을 맞춘다 — 예전엔 상품의 skus 에 넣지 않아, 바로 뒤의 검색 색인이 SKU 없는 문서를 만들었다.
            product.getSkus().add(skuProject);

            return withSizeSkuCodes(productskuRepository.save(skuProject));

        } catch (Exception e) {
            for (String url : uploaded) {
                imageService.deleteByUrl(url);
            }
            throw e;
        }
    }

    private String encodeFileToBase64(MultipartFile file) {
        try {

            byte[] fileBytes = file.getBytes();
            return Base64.encodeBase64String(fileBytes);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    private byte[] decodeBase64ToFile(String encodedString) {
        return Base64.decodeBase64(encodedString);
    }

    /**
     * 색상 여러 개를 <b>한 번에</b> — order-service 가 주문 상품마다, cart-service 가 장바구니
     * 항목마다 {@link #getColorAttributeInfo} 를 부르던 것의 대체다(docs/38 §1.13). 주문 목록
     * 한 페이지(주문 10건 × 상품 3개)가 색상 조회 30번이었다. 못 찾은 id 는 결과에서 빠진다.
     */
    @Transactional(readOnly = true)
    public List<ProductColorDto> getColorsByIds(Collection<Long> colorIds) {
        if (colorIds == null || colorIds.isEmpty()) {
            return List.of();
        }
        return productColorRepository.findAllById(colorIds).stream()
                .map(color -> ProductColorDto.builder()
                        .id(Long.toString(color.getColorId()))
                        .color(color.getColor())
                        .colorImage(color.getColorImage())
                        .build())
                .collect(Collectors.toList());
    }

    public ProductColorDto getColorAttributeInfo(Long colorId) {

        ProductColor color = productColorRepository.findById(colorId).orElse(null);

        if (null != color) {

            ProductColorDto dto = ProductColorDto.builder()
                    .id(Long.toString(color.getColorId()))
                    .color(color.getColor())
                    .colorImage(color.getColorImage())
                    .build();

            return dto;

        }

        log.info("color is null : " + Long.toString(colorId));

        return null;
    }

    public ProductInfoDto getCartProductInfo(Long productId, int style, int size) {
        Optional<Product> data = productRepository.findById(productId);
        if (data.isEmpty()) {
            log.info("product is null : " + Long.toString(productId));
            return null;
        }
        return toCartProductInfo(data.get(), style, size);
    }

    /**
     * 장바구니 항목 여러 개의 (상품, 스타일, 사이즈) 정보를 <b>한 번에</b> — cart-service 의
     * loadCart 가 항목마다 {@link #getCartProductInfo} 를 부르던 것의 대체다(docs/38 §1.13).
     *
     * <p>상품은 중복 없는 id 로 한 번에 읽고({@code findAllById} + {@code @BatchSize} 컬렉션),
     * 항목별 계산은 단건과 같은 {@link #toCartProductInfo} 를 쓴다. 못 찾은 항목(없는 상품,
     * 품절, 잘못된 스타일/사이즈 인덱스)은 결과 맵에서 빠질 뿐이다 — 한 항목 때문에 장바구니
     * 전체가 실패하지 않게 하고, 호출자가 키로 대조한다.
     */
    @Transactional(readOnly = true)
    public Map<String, ProductInfoDto> getCartProductsInfo(List<CartProductInfoRequest.Item> items) {
        if (items == null || items.isEmpty()) {
            return Map.of();
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (CartProductInfoRequest.Item item : items) {
            if (item.getProductId() != null) {
                ids.add(item.getProductId());
            }
        }
        Map<Long, Product> products = new HashMap<>();
        for (Product product : productRepository.findAllById(ids)) {
            products.put(product.getProductId(), product);
        }

        Map<String, ProductInfoDto> result = new LinkedHashMap<>();
        for (CartProductInfoRequest.Item item : items) {
            Product product = products.get(item.getProductId());
            if (product == null) {
                log.info("product is null : {}", item.getProductId());
                continue;
            }
            try {
                ProductInfoDto dto = toCartProductInfo(product, item.getStyle(), item.getSize());
                if (dto != null) {
                    result.put(item.key(), dto);
                }
            } catch (IndexOutOfBoundsException e) {
                // 단건 API 에서는 400 이 되던 경우. 배치에서는 그 항목만 뺀다.
                log.info("잘못된 스타일/사이즈 인덱스: {}", item.key());
            }
        }
        return result;
    }

    /** 단건·배치가 공유하는 항목 하나의 계산. 품절이거나 SKU 가 없으면 null. */
    private ProductInfoDto toCartProductInfo(Product product, int style, int size) {
        // 품절(SOLD_OUT) 상품은 장바구니에 담기지 않아야 한다. 이 메서드는 cart-service
        // 가 담기 직전에 부르는 상품 정보 조회라, 여기서 null 을 돌려주면 담기가
        // "상품 없음"과 같은 경로로 실패한다 — cart 쪽 코드는 이미 null 을 처리한다.
        // (DELETED 는 @SQLRestriction 이 위 findById 에서 이미 걸렀다.)
        if ("SOLD_OUT".equals(product.getStatus())) {
            log.info("품절 상품의 장바구니 담기 차단. productId={}", product.getProductId());
            return null;
        }

        Optional<ProductSku> optionalSku = product.getSkus().stream()
                .skip(style) // get(style)과 유사하게 특정 순서의 요소를 건너뛰지만, Set은 순서가 없으므로 정확한 결과를 보장하지 않습니다.
                .findFirst();

        if (optionalSku.isPresent()) {

            ProductSku sku = optionalSku.get();

            // 진행 중인 기획전이 있으면 그 할인율을 얹는다. 이 값이 **실제로 청구되는
            // 금액**을 정한다 — 화면과 다른 값을 쓰면 상세에서 본 가격과 결제 금액이
            // 어긋난다(EventPricing 은 두 경로 모두 같은 맵을 본다).
            int discountPercent = eventPricing.effectiveDiscount(product.getProductId(), sku.getDiscount());
            BigDecimal priceBefore = sku.getSizes().get(size).getPrice();
            BigDecimal price = discounted(priceBefore, discountPercent);

            ProductColorDto color = ProductColorDto.builder()
                    .id(Long.toString(sku.getColor().getColorId()))
                    .color(sku.getColor().getColor())
                    .colorImage(sku.getColor().getColorImage())
                    .build();

            List<String> subcategoryIds = product.getSubcategories().stream().map(productSubcategory -> {
                return Long.toString(productSubcategory.getSubcategory().getSubcategoryId());
            })
                    .collect(Collectors.toList());

            List<ProductDetailsDto> details = product.getDetails().stream().map(detail -> {
                return ProductDetailsDto.builder()
                        .name(detail.getName())
                        .value(detail.getValue()).build();
            }).collect(Collectors.toList());

            List<ProductQADto> questions = product.getQuestions().stream().map(q -> {
                return ProductQADto.builder()
                        .question(q.getQuestion())
                        .answer(q.getAnswer())
                        .build();
            }).collect(Collectors.toList());

            ProductInfoDto dto = ProductInfoDto.builder()
                    .id(Long.toString(product.getProductId()))
                    .style(style)
                    .name(product.getName())
                    .description(product.getDescription())
                    .slug(product.getSlug())
                    .sku(sku.getSku())
                    // 사이즈 한 줄의 SKU(V17) — 장바구니 → 주문 품목이 이 값을 들고 간다.
                    .skuCode(sku.getSizes().get(size).getSkuCode())
                    .brand(product.getBrand())
                    // ProductMapper 와 같은 무방비 역참조 — shipping 은 nullable 이라
                    // 배송비 없는 상품이 장바구니에 담기면 500 이 났다(docs/14 B-15).
                    .shipping(product.getShipping() != null ? product.getShipping().toPlainString() : null)
                    .images(sku.getImages())
                    .color(color)
                    .size(sku.getSizes().get(size).getSize())
                    .price(price.toPlainString())
                    .priceBefore(priceBefore.toPlainString())
                    .qty(1)
                    .quantity(sku.getSizes().get(size).getQuantity())
                    .category(Long.toString(product.getCategory().getCategoryId()))
                    .subCategories(subcategoryIds)
                    .questions(questions)
                    .details(details)
                    .discount(discountPercent)
                    .build();

            return dto;
        }
        log.info("sku not found : productId={} style={}", product.getProductId(), style);
        return null;
    }

    /**
     * 할인가. {@code discountPercent} 는 <b>퍼센트</b>다(시드 데이터가 5~70 을 넣는다).
     *
     * <p>🐛 <b>예전 식은 {@code price - price / discount} 였다.</b> "정가에서 정가를 할인율로
     * 나눈 값을 뺀다"는 뜻이라 아무 의미가 없고, 결과적으로 <b>할인율이 클수록 할인 폭이
     * 작아졌다</b>: 10,000원에 50% 할인이면 9,800원(=2% 할인), 70% 할인이면 9,857원(=1.4%).
     *
     * <p>프론트엔드(`util/pricing.js`)는 이미 고쳐져 있었고 <b>여기만 남아 있었다.</b> 그래서
     * 상세 화면은 50% 할인가를 보여 주는데 장바구니에 담으면 2% 할인가가 담겼다 — 같은
     * 상품의 가격이 화면마다 달랐다. 기획전 할인도 이 경로를 지나므로 먼저 바로잡는다.
     *
     * <p>{@code RoundingMode.HALF_UP} · 소수점 2자리는 통화 금액의 관례다. 100 을 넘는 값이
     * 들어와도 가격이 음수가 되지 않게 자른다.
     */
    static BigDecimal discounted(BigDecimal priceBefore, int discountPercent) {
        // 식은 common 의 하나뿐 — cart-service 가 옛 식을 따로 갖고 있다가 결제 금액이 달라졌다(Discount 주석).
        return com.project.common.pricing.Discount.apply(priceBefore, discountPercent);
    }

    /**
     * 엔티티 → DTO 의 <b>유일한</b> 통로.
     *
     * <p>기획전 할인을 여기서 얹는다. 예전에는 조회 경로마다 {@code ProductMapper::toDto} 를
     * 직접 불렀는데, 그러면 새 경로가 생길 때마다 합성을 한 줄씩 더해야 하고 빠뜨리면
     * <b>그 화면만 정가를 보여 준다</b> — 같은 상품이 목록에서는 30% 할인인데 상세에서는
     * 정가인 식이다. 통로를 하나로 두면 빠뜨릴 곳이 없다.
     */
    private ProductDto convertToDto(Product product) {
        return eventPricing.apply(ProductMapper.toDto(product));
    }

    private Product getProductModel(Long productId) {
        Optional<Product> product = productRepository.findById(productId);
        if (product.isPresent()) {
            return product.get();
        }
        return null;
    }

    private ProductSize getProductSizeModel(Long skuId, String size) {
        Optional<ProductSize> productSize = productSizeRepository.findFirstBySkuSkuproductIdAndSizeOrderBySizeIdAsc(skuId, size);
        if (productSize.isPresent()) {
            return productSize.get();
        }
        return null;
    }

    // @Transactional
    // private ProductSku updateSoldValue(Long productId, String slug, String size,
    // Long colorId, int qty) {

    // ProductSku sku = getProductSkuModel(productId, size, colorId);
    // if (sku != null) {
    // int sold = sku.getSold() + qty;
    // sku.setSold(sold);

    // List<ProductDto> cachedData =
    // objectMapper.convertValue(productsOps.get("products:"+slug),
    // new TypeReference<List<ProductDto>>() {});

    // if (!cachedData.isEmpty()) {

    // cachedData.get(0).getSkus().stream().forEach(
    // item -> {
    // if (item.hasSize(size) && item.hasColor(Long.toString(colorId))) {
    // item.setSold(sold);
    // }
    // }
    // );

    // productsOps.set("products:"+slug, cachedData);

    // }

    // sku = productskuRepository.save(sku);
    // return sku;
    // }

    // return null;
    // }

    // @Transactional
    // private ProductSize updateProductSize_Qty(String slug, Long skuId, String
    // size, int qty) {
    // ProductSize productSize = getProductSizeModel(skuId, size);

    // if (productSize != null) {

    // if (productSize.getQuantity() >= qty) {

    // int currQty = productSize.getQuantity() - qty;
    // productSize.setQuantity(currQty);

    // List<ProductDto> cachedData =
    // objectMapper.convertValue(productsOps.get("products:"+slug),
    // new TypeReference<List<ProductDto>>() {});

    // if (!cachedData.isEmpty()) {
    // cachedData.get(0).getSkus().stream().forEach(
    // item -> {
    // if (item.hasSize(size)) {
    // item.getSize(size).setQuantity(currQty);
    // }
    // }
    // );

    // productsOps.set("products:"+slug, cachedData);

    // }

    // productSize = productSizeRepository.save(productSize);
    // return productSize;
    // }
    // }

    // return null;
    // }

    // A transactional public method that orchestrates the update
    @Transactional
    // 판매량/재고 변경(주문 성공 Kafka 소비 경로)도 검색 필터(재고·판매량 기반)에 영향 → 캐시 무효화.
    @CacheEvict(value = {"search_cache", "colors_cache", "sizes_cache", "details_cache", "products_by_category"}, allEntries = true)
    // "product" 캐시는 ProductDto 를 담는다 — 여기 있던 @CachePut 은 ProductSku 를 넣으려 했다(자기 호출이라 실제로는 돌지 않았다).
    public ProductSku updateSoldValue(Long productId, String slug, String size, Long colorId, int qty) {
        Optional<ProductSku> optionalSku = productskuRepository
                .findForStockUpdate(productId, size, colorId);

        if (optionalSku.isPresent()) {
            ProductSku sku = optionalSku.get();
            // qty 가 음수면 재고 복원(환불·반품 입고)이다 — 판매량을 뺀다. 0 아래로는 가지
            // 않는다: 복원 이벤트가 차감보다 먼저 도착하는 순서 역전은 이론상 가능하고,
            // 그때 판매량이 음수로 화면에 찍히는 것보다 0 이 낫다.
            int sold = Math.max(0, sku.getSold() + qty);
            sku.setSold(sold);

            // 1. Database Update
            productskuRepository.save(sku);

            // 2. Cache Update
            // This method call will trigger the @CachePut and update the cache.
            updateProductDtoCache(slug, sku);

            return sku;
        }
        return null;
    }

    // This method updates the cache with the latest data.
    // It is public so that the @CachePut annotation can be applied by Spring.
    @Transactional
    @CachePut(value = "products", key = "#slug")
    public List<ProductDto> updateProductDtoCache(String slug, ProductSku updatedSku) {
        // Here, we re-fetch the product from the DB or a separate method
        // to ensure the DTO list contains the latest data.
        List<Product> products = productRepository.findBySlug(slug);

        // Convert entities to DTOs and update the specific SKU's sold value
        List<ProductDto> updatedDtos = products.stream()
                .map(product -> {
                    ProductDto dto = convertToDto(product);
                    // Find and update the specific SKU in the DTO list
                    dto.getSkus().stream()
                            .filter(skuDto -> skuDto.getSku().equals(updatedSku.getSku()))
                            .findFirst()
                            .ifPresent(skuDto -> skuDto.setSold(updatedSku.getSold()));
                    return dto;
                })
                .collect(Collectors.toList());

        return updatedDtos;
    }

    @Transactional
    @CacheEvict(value = {"search_cache", "colors_cache", "sizes_cache", "details_cache", "products_by_category"}, allEntries = true)
    // "product" 캐시에 ProductSize 를 넣으려던 @CachePut 을 지웠다(위와 같은 이유).
    public ProductSize updateProductSize_Qty(String slug, Long skuId, String size, int qty) {
        ProductSize productSize = getProductSizeModel(skuId, size);

        if (productSize != null) {
            // qty 가 음수면 재고 복원이다 — 아래 조건은 언제나 참이고 수량이 |qty| 만큼 는다.
            if (productSize.getQuantity() >= qty) {
                int before = productSize.getQuantity();
                int currQty = productSize.getQuantity() - qty;
                productSize.setQuantity(currQty);

                // 데이터베이스 저장
                productSize = productSizeRepository.save(productSize);
                // 0 을 건넜으면(품절·재입고) 커밋 뒤에 알린다(docs/53).
                stockEvents.onQuantityChanged(variantOf(productSize), before, currQty);

                // 데이터베이스 업데이트 후 캐시를 갱신하는 메서드 호출
                refreshProductsCache(slug);

                return productSize;
            }
        }
        return null;
    }

    /** 이벤트에 실을 옵션 정보 — 커밋 전(영속성 컨텍스트 안)에 읽는다. */
    private static com.project.catalog_service.message.producer.StockEventProducer.Variant variantOf(ProductSize size) {
        ProductSku sku = size.getSku();
        Product product = sku == null ? null : sku.getProduct();
        ProductColor color = sku == null ? null : sku.getColor();
        return new com.project.catalog_service.message.producer.StockEventProducer.Variant(
                product == null ? null : product.getProductId(),
                product == null ? null : product.getSlug(),
                product == null ? null : product.getName(),
                color == null ? null : color.getColorId(),
                color == null ? null : color.getColor(),
                size.getSize());
    }

    // ── 관리자 재고 (docs/53) ──────────────────────────────────────────────

    /** 관리자 재고 목록 한 줄. */
    public record StockRow(Long sizeId, Long productId, String name, String slug, Long colorId, String color,
            String size, String skuCode, int quantity) {
    }

    /** 한 번에 돌려주는 최대 줄 수 — 관리자 화면 한 장. */
    public static final int MAX_STOCK_ROWS = 200;

    /**
     * 재고 목록. {@code soldOutOnly} 면 품절(0 이하)만 — "채울 것" 목록이다. 상품 id·사이즈 id 순.
     */
    @Transactional(readOnly = true)
    public List<StockRow> stockRows(boolean soldOutOnly, Long afterSizeId) {
        return productSizeRepository.findStockRows(soldOutOnly, afterSizeId == null ? -1L : afterSizeId,
                        org.springframework.data.domain.PageRequest.of(0, MAX_STOCK_ROWS))
                .stream().map(ProductService::rowOf).collect(Collectors.toList());
    }

    /**
     * 관리자가 재고를 <b>정한다</b>(더하기가 아니라 그 값으로). 0 을 건너면 품절·재입고가 나간다 — 채우면
     * 재입고 알림을 신청한 손님에게 알림이 간다.
     *
     * @return 바뀐 줄. 그런 사이즈가 없으면 null
     */
    @Transactional
    @CacheEvict(value = {"search_cache", "colors_cache", "sizes_cache", "details_cache", "products_by_category"}, allEntries = true)
    public StockRow setStock(Long sizeId, int quantity) {
        if (quantity < 0) {
            throw new IllegalArgumentException("재고는 0 이상이어야 합니다: " + quantity);
        }
        ProductSize size = productSizeRepository.findById(sizeId).orElse(null);
        if (size == null) {
            return null;
        }
        int before = size.getQuantity();
        size.setQuantity(quantity);
        productSizeRepository.save(size);
        stockEvents.onQuantityChanged(variantOf(size), before, quantity);
        StockRow row = rowOf(size);
        log.info("관리자 재고 조정: sizeId={} productId={} {} → {}", sizeId, row.productId(), before, quantity);
        if (row.slug() != null) {
            refreshProductsCache(row.slug());
        }
        return row;
    }

    private static StockRow rowOf(ProductSize size) {
        var v = variantOf(size);
        return new StockRow(size.getSizeId(), v.productId(), v.name(), v.slug(), v.colorId(), v.color(),
                size.getSize(), size.getSkuCode(), size.getQuantity());
    }

    /**
     * @CachePut 어노테이션을 사용하여 Redis 캐시를 갱신합니다.
     *           이 메서드는 오직 캐시를 업데이트하는 역할만 담당합니다.
     */
    @Transactional
    @CachePut(value = "products", key = "#slug")
    public List<ProductDto> refreshProductsCache(String slug) {
        log.info("Refreshing cache for slug: {}", slug);
        List<Product> products = productRepository.findBySlug(slug);
        return products.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    /**
     * 주문 결제 뒤의 재고 차감, 그리고 그 <b>역</b>인 재고 복원(환불·반품 입고).
     *
     * <p>{@code request.restock} 이 참이면 같은 항목 목록을 부호만 바꿔 적용한다 — 사이즈
     * 수량이 돌아오고 판매량이 빠진다. 별도 경로를 두지 않은 이유: 차감과 복원은 정확히
     * 같은 SKU·사이즈를 찾아 같은 두 필드를 만지는 일이고, 경로가 둘이면 한쪽만 고쳐지는
     * 날이 온다(CAT-13 이 그런 종류의 결함이었다). 멱등성은 리스너의 {@code eventId} 가
     * 지킨다 — 차감은 {@code order-<id>}, 복원은 {@code restock-<id>} 라 각각 한 번이다.
     *
     * <h3>🔴 재고가 모자랄 때 — 초과 판매</h3>
     *
     * <p>재고는 <b>결제 뒤에</b> 차감한다. 마지막 1개를 두 사람이 동시에 결제하면 뒤의 차감은 모자란다. 예전에는 그때
     * {@code updateProductSize_Qty} 가 조용히 null 을 돌려주고 리스너가 로그 한 줄을 남기는 것이 전부였다 — 주문은
     * 결제 완료로 진행되고, 환불도 경보도 없었다. 🐛 게다가 판매량은 수량을 확인하기 <b>전에</b> 올라가서, 차감이
     * 실패해도 판매량만 늘었다.
     *
     * <p>이제 줄마다 SKU 를 잠근 채({@code findForStockUpdate}) 남은 수량을 보고 <b>있는 만큼만</b> 배정한다. 판매량도
     * 배정한 만큼만 오른다. 모자란 수는 결과의 {@code shortages} 로 돌려주고, 리스너가 같은 트랜잭션에서 부족
     * 기록(아웃박스)을 남긴다 — order 가 그 수만큼 자동 환불한다(docs/53 §초과 판매). 상품·SKU·사이즈를 찾지 못한
     * 줄은 전부 모자란 것으로 본다(보낼 수 없다).
     */
    @Transactional
    // 주문 성공 시 판매량/재고가 바뀌므로 검색/필터 캐시를 무효화한다.
    // (updateSoldValue/updateProductSize_Qty는 여기서 this로 자기호출되어 그쪽 애노테이션은 프록시를 타지 않으므로,
    //  실제 외부 진입점인 이 메서드에 무효화를 건다.)
    @CacheEvict(value = {"search_cache", "colors_cache", "sizes_cache", "details_cache", "products_by_category"}, allEntries = true)
    public StockApplyResult productUpdated(ProductUpdateRequest request) {
        return productUpdated(request, HeldQuantities.NONE);
    }

    /**
     * @param held 이 차감이 쓸 수 없는 수 — <b>다른</b> 주문이 결제 전에 잡아 둔 수(docs/59). 결제 확정 차감은 자기 주문의
     *             예약을 쓰므로 그것은 빼고 넘어온다(StockReservationService.heldByOthers).
     */
    @Transactional
    @CacheEvict(value = {"search_cache", "colors_cache", "sizes_cache", "details_cache", "products_by_category"}, allEntries = true)
    public StockApplyResult productUpdated(ProductUpdateRequest request, HeldQuantities held) {

        ProductSize lastUpdated = null;
        List<ShortLine> shortages = new ArrayList<>();

        if (request == null || request.getOrderProducts() == null || request.getOrderProducts().isEmpty()) {
            return new StockApplyResult(null, List.of());
        }

        for (OrderProductRequest item : request.getOrderProducts()) {
            int qty = Math.abs(item.getQty());
            Product product = getProductModel(item.getProductId());

            if (request.isRestock()) {
                // 복원 — 수량이 돌아오고 판매량이 빠진다. 모자랄 일이 없다.
                if (product == null) {
                    continue;
                }
                ProductSku sku = updateSoldValue(product.getProductId(), product.getSlug(), item.getSize(),
                        item.getColorId(), -qty);
                if (sku != null) {
                    lastUpdated = updateProductSize_Qty(product.getSlug(), sku.getSkuproductId(), item.getSize(), -qty);
                }
                continue;
            }

            // 차감 — 있는 만큼만 배정한다. 잠금은 이 트랜잭션이 끝날 때까지 쥔다(같은 SKU 의 동시 차감은 줄을 선다).
            // 남은 수 = 잠근 뒤 읽은 실물 − 다른 주문이 결제 전에 잡아 둔 수(docs/59).
            int available = product == null ? 0
                    : Math.max(0, availableForUpdate(product.getProductId(), item.getSize(), item.getColorId())
                            - (held == null ? 0 : held.heldByOthers(product.getProductId(), item.getColorId(), item.getSize())));
            int allocated = Math.min(available, qty);
            if (allocated > 0) {
                ProductSku sku = updateSoldValue(product.getProductId(), product.getSlug(), item.getSize(),
                        item.getColorId(), allocated);
                if (sku != null) {
                    ProductSize updated = updateProductSize_Qty(product.getSlug(), sku.getSkuproductId(),
                            item.getSize(), allocated);
                    if (updated != null) {
                        lastUpdated = updated;
                    } else {
                        allocated = 0;   // 잠금 아래에서 확인했으므로 오지 않는다 — 와도 모자란 것으로 센다
                    }
                } else {
                    allocated = 0;
                }
            }
            if (allocated < qty) {
                shortages.add(new ShortLine(item.getProductId(), item.getColorId(), item.getSize(), qty, qty - allocated));
            }
        }
        return new StockApplyResult(lastUpdated, List.copyOf(shortages));
    }

    /**
     * 이 옵션의 남은 수량 — SKU 를 잠근 뒤에 읽는다. 없는 SKU·사이즈는 0. 음수(예전 초과 판매의 흔적)도 0 으로 본다.
     */
    int availableForUpdate(Long productId, String size, Long colorId) {
        Optional<ProductSku> sku = productskuRepository.findForStockUpdate(productId, size, colorId);
        if (sku.isEmpty()) {
            return 0;
        }
        ProductSize productSize = getProductSizeModel(sku.get().getSkuproductId(), size);
        return productSize == null ? 0 : Math.max(0, productSize.getQuantity());
    }

    /** 다른 주문이 결제 전에 잡아 둔 수 — 옵션(상품·색상·사이즈)별(docs/59). */
    @FunctionalInterface
    public interface HeldQuantities {
        HeldQuantities NONE = (productId, colorId, size) -> 0;

        int heldByOthers(Long productId, Long colorId, String size);
    }

    /** 재고 반영 결과 — 마지막으로 바뀐 사이즈 행(없으면 null)과 모자란 줄들. */
    public record StockApplyResult(ProductSize lastUpdated, List<ShortLine> shortages) {
        public boolean hasShortage() {
            return shortages != null && !shortages.isEmpty();
        }
    }

    /** 모자란 줄 — 주문한 수와 그중 보낼 수 없는 수. */
    public record ShortLine(Long productId, Long colorId, String size, int orderedQty, int shortQty) {
    }
}
