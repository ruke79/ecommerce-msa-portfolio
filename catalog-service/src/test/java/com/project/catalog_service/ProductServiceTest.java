package com.project.catalog_service;
import com.project.common.dto.ProductDto;
import com.project.common.dto.ProductInfoDto;
import com.project.catalog_service.mapper.ProductMapper;
import com.project.catalog_service.model.*;
import com.project.catalog_service.repository.jpa.data.CategoryRepository;
import com.project.catalog_service.repository.jpa.data.ProductColorRepository;
import com.project.catalog_service.repository.jpa.data.ProductQARepository;
import com.project.catalog_service.repository.jpa.data.ProductRepository;
import com.project.catalog_service.repository.jpa.data.ProductSizeRepository;
import com.project.catalog_service.repository.jpa.data.ProductSkuRepository;
import com.project.catalog_service.repository.jpa.data.SubcategoryRepository;
import com.project.catalog_service.service.ImageService;
import com.project.catalog_service.service.ProductService;
import com.project.common.dto.*;
import com.project.common.util.FileUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock private CategoryRepository categoryRepository;
    @Mock private SubcategoryRepository subCategoryRepository;
    @Mock private ProductRepository productRepository;
    @Mock private ProductSizeRepository productSizeRepository;
    @Mock private ProductSkuRepository productskuRepository;
    @Mock private ProductColorRepository productColorRepository;
    @Mock private ProductQARepository productQARepository;
    @Mock private ImageService imageService;
    @Mock private com.project.catalog_service.service.ProductIndexService productIndexService;
    @Mock private FileUtil fileUtil;

    /**
     * 기획전 할인 합성. 이 테스트들은 기획전을 다루지 않으므로 <b>아무것도 바꾸지 않는</b>
     * 동작으로 스텁한다 — 기본 목은 apply() 가 null 을 돌려줘 조회 결과가 통째로 사라진다.
     * (합성 규칙 자체는 EventPricingTest 가 본다.)
     */
    @Mock private com.project.catalog_service.service.EventPricing eventPricing;

    // @Spy 가 필요하다: warmUpProductCaches 테스트는 같은 객체의 다른 메서드를
    // 스텁/검증하는데, @InjectMocks 만으로는 실제 객체라 stubbing 시점에 진짜 메서드가
    // 호출돼 PageRequest.of(0, 0) → "Page size must not be less than one" 로 깨졌다.
    @Spy
    @InjectMocks
    private ProductService productService;

    // 테스트에 사용할 공통 객체
    private Product product;
    private ProductDto productDto;
    private Category category;
    private Subcategory subcategory;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(eventPricing.apply(
                        org.mockito.ArgumentMatchers.any(ProductDto.class)))
                .thenAnswer(i -> i.getArgument(0));
        org.mockito.Mockito.lenient().when(eventPricing.effectiveDiscount(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(i -> i.getArgument(1));

        category = Category.builder().categoryId(1L).categoryName("fashion").build();
        subcategory = Subcategory.builder().subcategoryId(1L).subcategoryName("men's clothing").category(category).build();
        
        product = Product.builder()
                .productId(1L)
                .name("TestProduct")
                .description("Test Description")
                .brand("TestBrand")
                .slug("test-product")
                .category(category)
                .build();
                
        // ProductDto 변환 로직에 필요한 관계 설정
        ProductSubcategory productSubcategory = ProductSubcategory.builder().product(product).subcategory(subcategory).build();
        List<ProductSubcategory> subcategories = new ArrayList<>();
        subcategories.add(productSubcategory);
        product.setSubcategories(subcategories);
        product.setDetails(Collections.emptySet());
        product.setQuestions(Collections.emptySet());
        product.setSkus(Collections.emptySet());

        productDto = ProductMapper.toDto(product);
    }
    
    @Test
    @DisplayName("warmUpProductCaches는 모든 카테고리/서브카테고리 조합에 대해 캐시를 워밍업해야 한다")
    void warmUpProductCaches_shouldCallServiceForEachPage() {
        // given

        
        Category category2 = Category.builder().categoryId(2L).categoryName("consumer electronics").build();
        Subcategory subcategory2 = Subcategory.builder().subcategoryId(2L).subcategoryName("smartphones").category(category2).build();
        
        when(categoryRepository.findAll()).thenReturn(Arrays.asList(category, category2));
        when(subCategoryRepository.findByCategory(category)).thenReturn(Collections.singletonList(subcategory));
        when(subCategoryRepository.findByCategory(category2)).thenReturn(Collections.singletonList(subcategory2));

        // @Spy 이므로 doReturn 형식으로 스텁해야 실제 메서드가 호출되지 않는다.
        doReturn(Collections.nCopies(10, productDto))
            .when(productService).getProductsByCategoryAndSubcategory(anyString(), anyString(), anyInt(), anyInt());

        // when
        List<ProductDto> result = productService.warmUpProductCaches(20);

        // then
        // 현재 구현은 서브카테고리마다 page=0 한 번만 조회한다. 메서드 안에 page++ 가 있지만
        // 이를 감싸는 루프가 없어 실행되지 않는 죽은 코드다 — 즉 "워밍업"은 각 조합의
        // 첫 페이지만 데운다(docs/14 B-16). 테스트는 상상한 동작이 아니라 실제 동작을 고정한다.
        verify(productService, times(1)).getProductsByCategoryAndSubcategory(
                eq(category.getCategoryName()), eq(subcategory.getSubcategoryName()), eq(0), eq(20));
        verify(productService, times(1)).getProductsByCategoryAndSubcategory(
                eq(category2.getCategoryName()), eq(subcategory2.getSubcategoryName()), eq(0), eq(20));
        assertEquals(20, result.size()); // 조합 2개 × 10건
    }

    @Test
    @DisplayName("getProductsByCategoryAndSubcategory는 페이지네이션된 결과를 반환해야 한다")
    void getProductsByCategoryAndSubcategory_shouldReturnPaginatedResults() {
        // given
        Page<Product> productPage = new PageImpl<>(Collections.singletonList(product));
        when(productRepository.findAllByCategory_CategoryNameAndSubcategories_Subcategory_SubcategoryName(anyString(), anyString(), any(PageRequest.class)))
                .thenReturn(productPage);

        // when
        List<ProductDto> result = productService.getProductsByCategoryAndSubcategory("TestCategory", "TestSubcategory", 0, 1);

        // then
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("TestProduct", result.get(0).getName());
        verify(productRepository, times(1)).findAllByCategory_CategoryNameAndSubcategories_Subcategory_SubcategoryName(anyString(), anyString(), any(PageRequest.class));
    }

    @Test
    @DisplayName("getProductById는 유효한 ID에 대해 ProductDto를 반환해야 한다")
    void getProductById_shouldReturnProductDto_whenProductExists() {
        // given
        when(productRepository.findById(anyLong())).thenReturn(Optional.of(product));
        
        // when
        ProductDto result = productService.getProductById(1L);
        
        // then
        assertNotNull(result);
        assertEquals("TestProduct", result.getName());
    }

    @Test
    @DisplayName("getProductById는 존재하지 않는 ID에 대해 null을 반환해야 한다")
    void getProductById_shouldReturnNull_whenProductDoesNotExist() {
        // given
        when(productRepository.findById(anyLong())).thenReturn(Optional.empty());

        // when
        ProductDto result = productService.getProductById(999L);
        
        // then
        assertNull(result);
    }
    
    @Test
    @DisplayName("updateRating는 별점 업데이트 후 캐시를 갱신해야 한다")
    void updateRating_shouldUpdateAndRefreshCache() {
        // given
        Product updatedProduct = Product.builder().productId(1L).rating(4.5F).name("UpdatedProduct").build();
        when(productRepository.findById(anyLong())).thenReturn(Optional.of(updatedProduct));
        
        // when
        ProductDto result = productService.updateRating(1L, 4.5F);

        // then
        verify(productRepository, times(1)).updateRating(1L, 4.5F);
        verify(productRepository, times(1)).findById(1L);
        assertNotNull(result);
        assertEquals(4.5F, result.getRating());
    }

    @Test
    @DisplayName("getCartProductInfo는 유효한 매개변수에 대해 ProductInfoDto를 반환해야 한다")
    void getCartProductInfo_shouldReturnProductInfoDto() {
        // given
        ProductSku sku = ProductSku.builder().skuproductId(1L).product(product).build();
        ProductSize size = ProductSize.builder().sizeId(1L).size("M").price(new BigDecimal("100.00")).quantity(10).sku(sku).build();
        ProductColor color = ProductColor.builder().colorId(1L).color("Red").colorImage("red.jpg").build();
        
        Set<ProductSku> skus = new HashSet<>();
        sku.setSizes(Collections.singletonList(size));
        sku.setColor(color);
        skus.add(sku);
        product.setSkus(skus);
        
        when(productRepository.findById(anyLong())).thenReturn(Optional.of(product));

        // when
        ProductInfoDto dto = productService.getCartProductInfo(1L, 0, 0);

        // then
        assertNotNull(dto);
        assertEquals("TestProduct", dto.getName());
        assertEquals(new BigDecimal("100.00").toPlainString(), dto.getPrice());
    }

    @Test
    @DisplayName("getCartProductInfo는 존재하지 않는 ID에 대해 null을 반환해야 한다")
    void getCartProductInfo_shouldReturnNull_whenProductDoesNotExist() {
        // given
        when(productRepository.findById(anyLong())).thenReturn(Optional.empty());

        // when
        ProductInfoDto dto = productService.getCartProductInfo(999L, 0, 0);

        // then
        assertNull(dto);
    }

    // ── addProduct 입력 검증 회귀 테스트 ─────────────────────────────────
    //
    // 이 메서드는 예전에 잘못된 입력을 두 가지 방식으로 조용히 처리했다:
    //   · Optional 을 `!= null` 로 검사(항상 참) → 없는 카테고리면 NoSuchElementException 500
    //   · 카테고리·부모를 못 찾으면 null 반환 → 컨트롤러가 200 OK 로 돌려줌
    // 지금은 전부 IllegalArgumentException 이어야 한다. 이 테스트들이 그 계약을 지킨다.

    private org.springframework.mock.web.MockMultipartFile img(String name) {
        return new org.springframework.mock.web.MockMultipartFile(name, name + ".jpg", "image/jpeg",
                new byte[] { 1, 2, 3 });
    }

    // ProductRequest 는 기본 생성자가 protected 라(@NoArgsConstructor(PROTECTED))
    // 다른 패키지에서 new 할 수 없다. 컨트롤러가 받는 실제 경로(Jackson 역직렬화)와
    // 같은 방식으로 만든다.
    private com.project.common.dto.catalog.request.ProductRequest request(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, com.project.common.dto.catalog.request.ProductRequest.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("addProduct: 이미지가 없으면 IllegalArgumentException — 500 이나 200+null 이 아니다")
    void addProduct_rejectsMissingImages() {
        com.project.common.dto.catalog.request.ProductRequest request = request("{}");

        assertThrows(IllegalArgumentException.class,
                () -> productService.addProduct(request, Collections.emptyList(), img("color")));
        assertThrows(IllegalArgumentException.class,
                () -> productService.addProduct(request, List.of(img("a")), null));
    }

    @Test
    @DisplayName("addProduct: 없는 카테고리 id 는 IllegalArgumentException — 예전엔 category.get() 이 터졌다")
    void addProduct_rejectsUnknownCategory() {
        com.project.common.dto.catalog.request.ProductRequest request =
                request("{\"name\":\"이름\",\"shippingFee\":\"0\",\"category\":\"999\"}");

        when(categoryRepository.findById(999L)).thenReturn(Optional.empty());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> productService.addProduct(request, List.of(img("a")), img("color")));
        assertTrue(e.getMessage().contains("카테고리"));
    }

    @Test
    @DisplayName("addProduct: 없는 부모 상품 id 는 IllegalArgumentException — 예전엔 200 OK + null 이었다")
    void addProduct_rejectsUnknownParent() {
        com.project.common.dto.catalog.request.ProductRequest request =
                request("{\"parent\":\"77\"}");

        when(productRepository.findById(77L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> productService.addProduct(request, List.of(img("a")), img("color")));
    }

    @Test
    @DisplayName("addProduct: 숫자가 아닌 배송비는 IllegalArgumentException — 예전엔 NumberFormatException 500")
    void addProduct_rejectsNonNumericShippingFee() {
        com.project.common.dto.catalog.request.ProductRequest request =
                request("{\"name\":\"이름\",\"shippingFee\":\"공짜\"}");


        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> productService.addProduct(request, List.of(img("a")), img("color")));
        assertTrue(e.getMessage().contains("배송비"));
    }

    // ── uniqueSlug ───────────────────────────────────────────────────────

    @Test
    @DisplayName("uniqueSlug: 충돌이 없으면 요청값 그대로")
    void uniqueSlug_keepsRequestedWhenFree() {
        when(productRepository.slugTaken("my-product")).thenReturn(false);
        assertEquals("my-product", productService.uniqueSlug("my-product", "무시됨"));
    }

    @Test
    @DisplayName("uniqueSlug: 충돌하면 -2, -3 … 접미사 — slug 는 상세 URL 이라 중복이면 어느 상품이 보일지 우연이 정한다")
    void uniqueSlug_appendsSuffixOnCollision() {
        when(productRepository.slugTaken("shoe")).thenReturn(true);
        when(productRepository.slugTaken("shoe-2")).thenReturn(true);
        when(productRepository.slugTaken("shoe-3")).thenReturn(false);

        assertEquals("shoe-3", productService.uniqueSlug("shoe", "무시됨"));
    }

    /**
     * 🐛 삭제한 상품의 slug — findBySlug 는 @SQLRestriction 으로 DELETED 행을 보지 못해 "비었다" 고 했지만, 유니크 인덱스(V6)는 그 행에도
     * 걸려 같은 이름으로 다시 등록하면 500 이었다. 삭제된 행까지 보는 slugTaken 으로 판정한다.
     */
    @Test
    @DisplayName("🔴 uniqueSlug: 삭제된 상품이 쓰던 slug 도 쓴 것으로 본다 — 다시 등록하면 -2")
    void uniqueSlug_countsDeletedProducts() {
        when(productRepository.slugTaken("tee")).thenReturn(true);                       // 그러나 인덱스에는 있다
        when(productRepository.slugTaken("tee-2")).thenReturn(false);

        assertEquals("tee-2", productService.uniqueSlug("tee", "무시됨"));
    }

    @Test
    @DisplayName("uniqueSlug: slug 가 비면 이름에서 만든다 — 한글 이름도 살아남아야 한다")
    void uniqueSlug_derivesFromNameWhenBlank() {

        assertEquals("test-product", productService.uniqueSlug(null, "Test Product"));
        // \p{IsAlphabetic} 은 유니코드 기준이라 한글이 살아남는다 — ASCII [a-z] 로
        // 좁혔다면 한글 상품명이 통째로 지워져 빈 slug 가 됐을 것이다.
        assertEquals("청바지-남성", productService.uniqueSlug(null, "청바지 남성"));
    }

    @Test
    @DisplayName("uniqueSlug: 이름마저 기호뿐이면 IllegalArgumentException — 빈 slug 로 저장하지 않는다")
    void uniqueSlug_rejectsUnsluggableName() {
        assertThrows(IllegalArgumentException.class,
                () -> productService.uniqueSlug(null, "!!!"));
    }

    // ── SKU 자동 생성 ────────────────────────────────────────────────────

    @Test
    @DisplayName("skuPrefix: ASCII 영숫자 3자, 못 채우면 PRD — SKU 는 불러 읽는 코드라 대문자 영숫자로 제한한다")
    void skuPrefix_rules() {
        assertEquals("FAS", ProductService.skuPrefix("fashion"));
        assertEquals("CON", ProductService.skuPrefix("consumer electronics"));
        assertEquals("PRD", ProductService.skuPrefix("청바지"));      // ASCII 밖
        assertEquals("PRD", ProductService.skuPrefix("tv"));          // 3자 미만
        assertEquals("PRD", ProductService.skuPrefix(null));
    }

    @Test
    @DisplayName("SKU 를 비우면 <접두사>-<시퀀스 6자리> 로 생성된다")
    void addProduct_generatesSkuWhenBlank() throws IOException {
        com.project.common.dto.catalog.request.ProductRequest req = request(
                "{\"name\":\"자동 SKU 상품\",\"shippingFee\":\"0\",\"category\":\"1\","
                        + "\"subCategories\":[],\"sizes\":[],"
                        + "\"color\":{\"color\":\"#fff\"}}");

        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));
        when(subCategoryRepository.findBySubcategoryIdIn(anyList())).thenReturn(Collections.emptyList());
        when(imageService.upload(any(MultipartFile.class), anyString())).thenReturn("https://s3/x.jpg");
        when(productskuRepository.nextSkuNumber()).thenReturn(100042L);
        when(productskuRepository.existsBySku("FAS-100042")).thenReturn(false);
        when(productskuRepository.save(any(ProductSku.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ProductSku saved = productService.addProduct(req, List.of(img("a")), img("color"));

        // category 는 setUp() 의 "fashion" — 접두사 FAS, 번호는 시퀀스 그대로.
        assertEquals("FAS-100042", saved.getSku());
    }

    /**
     * 🐛 관리자가 등록한 상품의 사이즈가 어느 스타일에도 속하지 않았다. 부모를 <b>버려지는 객체</b>에 걸고, 실제로 저장되는
     * 객체는 다시 만들어 부모가 비어 있었다({@code product_size.skuproduct_id} = NULL). 상세 · 질문도 같은 모양이었다.
     */
    @Test
    @DisplayName("🐛 등록한 사이즈 · 상세 · 질문이 부모를 가리킨 채 저장된다 — 저장 뒤 사이즈 SKU 코드를 채운다")
    void addProduct_sizesPointAtTheirStyle_andGetSkuCodes() throws IOException {
        com.project.common.dto.catalog.request.ProductRequest req = request(
                "{\"name\":\"러닝화\",\"shippingFee\":\"0\",\"category\":\"1\","
                        + "\"subCategories\":[],"
                        + "\"sizes\":[{\"size\":\"270\",\"quantity\":3,\"price\":100},"
                        + "{\"size\":\"280\",\"quantity\":1,\"price\":100}],"
                        + "\"details\":[{\"name\":\"소재\",\"value\":\"메시\"}],"
                        + "\"questions\":[{\"question\":\"q\",\"answer\":\"a\"}],"
                        + "\"color\":{\"color\":\"#000\"}}");

        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));
        when(subCategoryRepository.findBySubcategoryIdIn(anyList())).thenReturn(Collections.emptyList());
        when(imageService.upload(any(MultipartFile.class), anyString())).thenReturn("https://s3/x.jpg");
        when(productskuRepository.nextSkuNumber()).thenReturn(100042L);
        when(productskuRepository.existsBySku("FAS-100042")).thenReturn(false);
        // 저장이 id 를 준 것처럼 — 스타일 7, 사이즈 71 · 72
        when(productskuRepository.save(any(ProductSku.class))).thenAnswer(inv -> {
            ProductSku sku = inv.getArgument(0);
            sku.setSkuproductId(7L);
            long id = 71;
            for (com.project.catalog_service.model.ProductSize s : sku.getSizes()) {
                s.setSizeId(id++);
            }
            return sku;
        });
        when(productSizeRepository.skuCodesOf(7L)).thenReturn(List.of(
                new Object[] { 71L, "FAS-100042-270" }, new Object[] { 72L, "FAS-100042-280" }));

        ProductSku saved = productService.addProduct(req, List.of(img("a")), img("color"));

        assertEquals(2, saved.getSizes().size());
        for (com.project.catalog_service.model.ProductSize s : saved.getSizes()) {
            org.junit.jupiter.api.Assertions.assertSame(saved, s.getSku(), "사이즈가 부모 스타일을 가리켜야 한다");
        }
        assertEquals(List.of("FAS-100042-270", "FAS-100042-280"),
                saved.getSizes().stream().map(com.project.catalog_service.model.ProductSize::getSkuCode).toList());
        saved.getProduct().getDetails().forEach(d -> org.junit.jupiter.api.Assertions.assertSame(saved.getProduct(), d.getProduct()));
        saved.getProduct().getQuestions().forEach(q -> org.junit.jupiter.api.Assertions.assertSame(saved.getProduct(), q.getProduct()));
        assertEquals(1, saved.getProduct().getDetails().size());
        assertEquals(1, saved.getProduct().getQuestions().size());

        // 규칙은 DB 함수에만 — 저장한 행을 먼저 내보낸 뒤 부른다.
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(productskuRepository, productSizeRepository);
        order.verify(productskuRepository).save(any(ProductSku.class));
        order.verify(productSizeRepository).flush();
        order.verify(productSizeRepository).assignSkuCodes();
    }

    @Test
    @DisplayName("🔴 다른 카테고리의 하위 카테고리는 붙일 수 없다 — 시드 데이터의 연결 81% 가 그랬다(올리브유가 '스마트폰', V16)")
    void addProduct_rejectsSubcategoryOfAnotherCategory() {
        com.project.common.dto.catalog.request.ProductRequest req = request(
                "{\"name\":\"니트\",\"shippingFee\":\"0\",\"category\":\"1\","
                        + "\"subCategories\":[\"4\"],\"sizes\":[],"
                        + "\"color\":{\"color\":\"#fff\"}}");
        Category electronics = Category.builder().categoryId(2L).categoryName("consumer electronics").build();
        Subcategory smartphones = Subcategory.builder().subcategoryId(4L).subcategoryName("smartphones").category(electronics).build();

        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));   // fashion
        when(subCategoryRepository.findBySubcategoryIdIn(anyList())).thenReturn(List.of(smartphones));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> productService.addProduct(req, List.of(img("a")), img("color")));
        assertTrue(e.getMessage().contains("카테고리에 속하지 않습니다"));
        verify(productRepository, never()).save(any());
    }

    @Test
    @DisplayName("직접 넣은 SKU 가 이미 있으면 IllegalArgumentException — DB 제약(V7)에 닿기 전에 좋은 메시지로 끊는다")
    void addProduct_rejectsDuplicateProvidedSku() throws IOException {
        com.project.common.dto.catalog.request.ProductRequest req = request(
                "{\"name\":\"중복 SKU 상품\",\"shippingFee\":\"0\",\"category\":\"1\","
                        + "\"subCategories\":[],\"sizes\":[],\"sku\":\"FAS-000001\","
                        + "\"color\":{\"color\":\"#fff\"}}");

        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));
        when(subCategoryRepository.findBySubcategoryIdIn(anyList())).thenReturn(Collections.emptyList());
        when(productskuRepository.existsBySku("FAS-000001")).thenReturn(true);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> productService.addProduct(req, List.of(img("a")), img("color")));
        assertTrue(e.getMessage().contains("이미 사용 중인 SKU"));
        // 거절 경로에서는 업로드가 일어나지 않아야 한다 — 고아 이미지 예방의 첫 줄이다.
        verifyNoInteractions(imageService);
    }

    @Test
    @DisplayName("생성된 SKU 가 우연히 기존 값과 겹치면 다음 시퀀스 번호로 넘어간다")
    void addProduct_generatedSkuSkipsCollision() throws IOException {
        com.project.common.dto.catalog.request.ProductRequest req = request(
                "{\"name\":\"충돌 회피 상품\",\"shippingFee\":\"0\",\"category\":\"1\","
                        + "\"subCategories\":[],\"sizes\":[],"
                        + "\"color\":{\"color\":\"#fff\"}}");

        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));
        when(subCategoryRepository.findBySubcategoryIdIn(anyList())).thenReturn(Collections.emptyList());
        when(imageService.upload(any(MultipartFile.class), anyString())).thenReturn("https://s3/x.jpg");
        when(productskuRepository.nextSkuNumber()).thenReturn(100001L, 100002L);
        // 관리자가 손으로 FAS-100001 을 이미 만들어 둔 상황
        when(productskuRepository.existsBySku("FAS-100001")).thenReturn(true);
        when(productskuRepository.existsBySku("FAS-100002")).thenReturn(false);
        when(productskuRepository.save(any(ProductSku.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ProductSku saved = productService.addProduct(req, List.of(img("a")), img("color"));

        assertEquals("FAS-100002", saved.getSku());
    }

    // ── 수정·상태(품절·삭제) ─────────────────────────────────────────────

    private com.project.common.dto.catalog.request.ProductEditRequest updateReq(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, com.project.common.dto.catalog.request.ProductEditRequest.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("updateProduct: 채워진 필드만 바꾸고 slug 는 절대 바꾸지 않는다")
    void updateProduct_partialAndSlugStable() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        productService.updateProduct(1L, updateReq(
                "{\"name\":\"새 이름\",\"shippingFee\":\"2500\"}"));

        assertEquals("새 이름", product.getName());
        assertEquals(new BigDecimal("2500"), product.getShipping());
        assertEquals("TestBrand", product.getBrand());          // 안 보낸 필드는 그대로
        assertEquals("test-product", product.getSlug());        // URL 안정성 — 절대 불변
    }

    @Test
    @DisplayName("updateProduct: 숫자가 아닌 배송비는 400 감이지 500 감이 아니다")
    void updateProduct_rejectsBadShippingFee() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        assertThrows(IllegalArgumentException.class,
                () -> productService.updateProduct(1L, updateReq("{\"shippingFee\":\"무료\"}")));
        verify(productRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateStatus: 허용된 세 값만 받는다")
    void updateStatus_validatesValue() {
        assertThrows(IllegalArgumentException.class,
                () -> productService.updateStatus(1L, "HIDDEN"));

        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        assertEquals("SOLD_OUT", productService.updateStatus(1L, "SOLD_OUT").getStatus());
    }

    @Test
    @DisplayName("getCartProductInfo: 품절 상품은 null — 장바구니에 담기지 않는다")
    void cartInfo_blocksSoldOut() {
        product.setStatus("SOLD_OUT");
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        assertNull(productService.getCartProductInfo(1L, 0, 0));
    }

    @Test
    @DisplayName("updateStatus: DELETED 는 ES 색인에서 빠지고, 그 외 상태는 재색인된다")
    void updateStatus_syncsSearchIndex() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        productService.updateStatus(1L, "SOLD_OUT");
        verify(productIndexService).index(any(Product.class));

        productService.updateStatus(1L, "DELETED");
        // DB 조회는 @SQLRestriction 이 걸러 주지만 ES 는 별도 저장소다 — 직접 지워야
        // 삭제된 상품이 검색에 유령으로 남지 않는다.
        verify(productIndexService).remove(1L);
    }
}
