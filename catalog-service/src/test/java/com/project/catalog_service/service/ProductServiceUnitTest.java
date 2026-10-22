package com.project.catalog_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.cache.annotation.CacheEvict;

import com.project.catalog_service.model.Product;
import com.project.catalog_service.model.ProductColor;
import com.project.catalog_service.model.ProductSize;
import com.project.catalog_service.model.ProductSku;
import com.project.catalog_service.repository.jpa.data.CategoryRepository;
import com.project.catalog_service.repository.jpa.data.ProductColorRepository;
import com.project.catalog_service.repository.jpa.data.ProductQARepository;
import com.project.catalog_service.repository.jpa.data.ProductRepository;
import com.project.catalog_service.repository.jpa.data.ProductSizeRepository;
import com.project.catalog_service.repository.jpa.data.ProductSkuRepository;
import com.project.catalog_service.repository.jpa.data.SubcategoryRepository;
import com.project.common.message.dto.request.OrderProductRequest;
import com.project.common.message.dto.request.ProductUpdateRequest;

/**
 * 주문 성공 Kafka 소비 경로(productUpdated)의 재고/판매량 반영 회귀 테스트.
 *
 * 배경(docs/06 CAT-13): 기존 코드는 for 루프 안에서 return 하여 주문의 첫 항목만
 * 재고가 반영되고 나머지 항목은 조용히 누락되는 버그가 있었다.
 * 이 테스트는 "모든 주문 항목이 처리된다"를 고정한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductServiceUnitTest {

    @Mock CategoryRepository categoryRepository;
    @Mock SubcategoryRepository subCategoryRepository;
    @Mock ProductRepository productRepository;
    @Mock ProductSizeRepository productSizeRepository;
    @Mock ProductSkuRepository productskuRepository;
    @Mock ProductColorRepository productColorRepository;
    @Mock ProductQARepository productQARepository;
    @Mock ImageService imageService;
    @Mock com.project.catalog_service.service.ProductIndexService productIndexService;
    // 기획전 할인 합성. 이 테스트들은 할인을 다루지 않지만 생성자에 필요하다 —
    // 합성 규칙 자체는 EventPricingTest 가 본다.
    @Mock EventPricing eventPricing;
    // 품절·재입고 알림(docs/53). 전이 판정 자체는 StockEventProducerTest 가 본다 — 여기서는 호출만.
    @Mock com.project.catalog_service.message.producer.StockEventProducer stockEvents;

    ProductService service;

    @BeforeEach
    void setUp() {
        service = spy(new ProductService(
                categoryRepository, subCategoryRepository, productRepository,
                productSizeRepository, productskuRepository, productColorRepository,
                productQARepository, imageService, productIndexService, eventPricing,
                stockEvents));
    }

    private ProductUpdateRequest requestWithItems(int count) {
        ProductUpdateRequest request = new ProductUpdateRequest();
        request.setOrderProducts(
                java.util.stream.IntStream.rangeClosed(1, count)
                        .mapToObj(i -> new OrderProductRequest((long) i, 1, "M", (long) i))
                        .collect(java.util.stream.Collectors.toList()));
        return request;
    }

    @Test
    @DisplayName("productUpdated: 다중 항목 주문은 모든 항목의 재고/판매량이 반영된다 (CAT-13 회귀 방지)")
    void productUpdated_processesEveryOrderLine() {
        Product product = mock(Product.class);
        when(product.getProductId()).thenReturn(1L);
        when(product.getSlug()).thenReturn("slug");
        when(productRepository.findById(anyLong())).thenReturn(Optional.of(product));

        ProductSku sku = mock(ProductSku.class);
        when(sku.getSkuproductId()).thenReturn(10L);
        doReturn(sku).when(service).updateSoldValue(anyLong(), anyString(), anyString(), anyLong(), anyInt());
        doReturn(mock(ProductSize.class)).when(service)
                .updateProductSize_Qty(anyString(), anyLong(), anyString(), anyInt());
        doReturn(5).when(service).availableForUpdate(anyLong(), anyString(), anyLong());

        assertThat(service.productUpdated(requestWithItems(3)).hasShortage()).isFalse();

        // 핵심 검증: 3개 항목 전부 판매량/재고 갱신이 호출되어야 한다 (기존 버그: 1회만 호출)
        verify(service, times(3)).updateSoldValue(anyLong(), anyString(), anyString(), anyLong(), anyInt());
        verify(service, times(3)).updateProductSize_Qty(anyString(), anyLong(), anyString(), anyInt());
    }

    /**
     * 재고 복원(환불·반품 입고)은 같은 경로를 부호만 바꿔 탄다 — 수량이 돌아오고 판매량이
     * 빠진다. 경로가 둘이면 한쪽만 고쳐지는 날이 온다(CAT-13).
     */
    @Test
    @DisplayName("productUpdated: restock=true 면 같은 항목을 음수로 — 수량이 돌아오고 판매량이 빠진다")
    void productUpdated_restock_appliesNegativeDelta() {
        Product product = mock(Product.class);
        when(product.getProductId()).thenReturn(1L);
        when(product.getSlug()).thenReturn("slug");
        when(productRepository.findById(anyLong())).thenReturn(Optional.of(product));

        ProductSku sku = mock(ProductSku.class);
        when(sku.getSkuproductId()).thenReturn(10L);
        doReturn(sku).when(service).updateSoldValue(anyLong(), anyString(), anyString(), anyLong(), anyInt());
        doReturn(mock(ProductSize.class)).when(service)
                .updateProductSize_Qty(anyString(), anyLong(), anyString(), anyInt());

        ProductUpdateRequest request = new ProductUpdateRequest();
        request.setRestock(true);
        request.setOrderProducts(List.of(new OrderProductRequest(1L, 3, "M", 1L)));

        service.productUpdated(request);

        verify(service).updateSoldValue(1L, "slug", "M", 1L, -3);
        verify(service).updateProductSize_Qty("slug", 10L, "M", -3);
    }

    // ---------------------------------------------------------------- 잠금 순서

    private void twoProducts() {
        for (long id : new long[] {1L, 2L}) {
            Product p = mock(Product.class);
            when(p.getProductId()).thenReturn(id);
            when(p.getSlug()).thenReturn("slug-" + id);
            when(productRepository.findById(id)).thenReturn(Optional.of(p));
        }
        ProductSku sku = mock(ProductSku.class);
        when(sku.getSkuproductId()).thenReturn(10L);
        doReturn(sku).when(service).updateSoldValue(anyLong(), anyString(), anyString(), anyLong(), anyInt());
        doReturn(mock(ProductSize.class)).when(service)
                .updateProductSize_Qty(anyString(), anyLong(), anyString(), anyInt());
        doReturn(5).when(service).availableForUpdate(anyLong(), anyString(), anyLong());
    }

    /**
     * 🐛 차감 · 복원은 주문 줄 순서대로 SKU 를 잠갔다(예약만 정렬했다) — [B, A] 주문과 [A, B] 주문이 겹치면 교착(40P01), 끊긴
     * 결제 확정 차감은 재시도를 다 쓰면 DLT 로 갔다. 예약과 같은 (상품, 색상, 사이즈) 순서로 잠근다.
     */
    @Test
    @DisplayName("🔴 결제 확정 차감은 줄 순서와 무관하게 (상품, 색상, 사이즈) 순서로 잠근다 — 예약과 같은 순서")
    void deductionLocksInCanonicalOrder() {
        twoProducts();
        ProductUpdateRequest request = new ProductUpdateRequest();
        request.setEventId("order-9");
        request.setOrderProducts(List.of(new OrderProductRequest(2L, 1, "M", 5L), new OrderProductRequest(1L, 1, "S", 7L),
                new OrderProductRequest(1L, 1, "L", 5L)));

        service.productUpdated(request);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(service);
        order.verify(service).availableForUpdate(1L, "L", 5L);
        order.verify(service).availableForUpdate(1L, "S", 7L);
        order.verify(service).availableForUpdate(2L, "M", 5L);
    }

    @Test
    @DisplayName("🔴 복원도 같은 순서로 잠근다")
    void restockLocksInCanonicalOrder() {
        twoProducts();
        ProductUpdateRequest request = new ProductUpdateRequest();
        request.setRestock(true);
        request.setOrderProducts(List.of(new OrderProductRequest(2L, 1, "M", 5L), new OrderProductRequest(1L, 1, "M", 5L)));

        service.productUpdated(request);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(service);
        order.verify(service).updateSoldValue(1L, "slug-1", "M", 5L, -1);
        order.verify(service).updateSoldValue(2L, "slug-2", "M", 5L, -1);
    }

    // ---------------------------------------------------------------- 초과 판매 (docs/53)

    private Product product() {
        Product product = mock(Product.class);
        when(product.getProductId()).thenReturn(1L);
        when(product.getSlug()).thenReturn("slug");
        when(productRepository.findById(anyLong())).thenReturn(Optional.of(product));
        ProductSku sku = mock(ProductSku.class);
        when(sku.getSkuproductId()).thenReturn(10L);
        doReturn(sku).when(service).updateSoldValue(anyLong(), anyString(), anyString(), anyLong(), anyInt());
        doReturn(mock(ProductSize.class)).when(service)
                .updateProductSize_Qty(anyString(), anyLong(), anyString(), anyInt());
        return product;
    }

    private static ProductUpdateRequest order(int qty) {
        ProductUpdateRequest request = new ProductUpdateRequest();
        request.setEventId("order-7");
        request.setOrderProducts(List.of(new OrderProductRequest(1L, qty, "M", 5L)));
        return request;
    }

    /**
     * 🔴 핵심 — 3개 주문에 1개 남았다. 1개만 배정하고 2개를 모자란 것으로 돌려준다. 판매량도 1만 오른다(예전에는
     * 판매량이 수량 확인 전에 3 올랐고, 수량 차감은 조용히 실패했다).
     */
    @Test
    @DisplayName("🔴 모자라면 있는 만큼만 배정하고 나머지를 부족으로 돌려준다 — 판매량도 배정한 만큼만")
    void aShortLineAllocatesWhatIsLeft() {
        product();
        doReturn(1).when(service).availableForUpdate(1L, "M", 5L);

        ProductService.StockApplyResult result = service.productUpdated(order(3));

        verify(service).updateSoldValue(1L, "slug", "M", 5L, 1);
        verify(service).updateProductSize_Qty("slug", 10L, "M", 1);
        assertThat(result.shortages()).containsExactly(new ProductService.ShortLine(1L, 5L, "M", 3, 2));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("🔴 다른 주문이 결제 전에 잡아 둔 수는 쓸 수 없다 — 실물 5, 남의 예약 4 → 3개 주문에 1개만 배정 (docs/59)")
    void othersHoldsAreNotAvailable() {
        product();
        doReturn(5).when(service).availableForUpdate(1L, "M", 5L);

        ProductService.StockApplyResult result = service.productUpdated(order(3), (p, c, sz) -> 4);

        verify(service).updateSoldValue(1L, "slug", "M", 5L, 1);
        assertThat(result.shortages()).containsExactly(new ProductService.ShortLine(1L, 5L, "M", 3, 2));
    }

    @Test
    @DisplayName("재고가 0 이면 아무것도 바꾸지 않고 전부 부족이다 — 판매량도 그대로")
    void anEmptyLineChangesNothing() {
        product();
        doReturn(0).when(service).availableForUpdate(1L, "M", 5L);

        ProductService.StockApplyResult result = service.productUpdated(order(2));

        verify(service, never()).updateSoldValue(anyLong(), anyString(), anyString(), anyLong(), anyInt());
        verify(service, never()).updateProductSize_Qty(anyString(), anyLong(), anyString(), anyInt());
        assertThat(result.shortages()).containsExactly(new ProductService.ShortLine(1L, 5L, "M", 2, 2));
    }

    @Test
    @DisplayName("상품을 찾지 못한 줄은 전부 부족이다 — 보낼 수 없다")
    void aMissingProductIsShort() {
        when(productRepository.findById(anyLong())).thenReturn(Optional.empty());

        assertThat(service.productUpdated(order(2)).shortages())
                .containsExactly(new ProductService.ShortLine(1L, 5L, "M", 2, 2));
    }

    @Test
    @DisplayName("복원은 남은 수량을 보지 않는다 — 부족이 없다")
    void restockNeverShort() {
        product();
        ProductUpdateRequest request = order(3);
        request.setRestock(true);

        assertThat(service.productUpdated(request).hasShortage()).isFalse();
        verify(service, never()).availableForUpdate(anyLong(), anyString(), anyLong());
        verify(service).updateProductSize_Qty("slug", 10L, "M", -3);
    }

    @Test
    @DisplayName("남은 수량은 SKU 를 잠근 뒤에 읽는다 — 음수(예전 초과 판매의 흔적)는 0, 없는 SKU 도 0")
    void availableReadsUnderTheLock() {
        ProductSku sku = mock(ProductSku.class);
        when(sku.getSkuproductId()).thenReturn(10L);
        when(productskuRepository.findForStockUpdate(1L, "M", 5L)).thenReturn(Optional.of(sku));
        ProductSize size = mock(ProductSize.class);
        when(productSizeRepository.findFirstBySkuSkuproductIdAndSizeOrderBySizeIdAsc(10L, "M")).thenReturn(Optional.of(size));

        when(size.getQuantity()).thenReturn(4);
        assertThat(service.availableForUpdate(1L, "M", 5L)).isEqualTo(4);
        when(size.getQuantity()).thenReturn(-2);
        assertThat(service.availableForUpdate(1L, "M", 5L)).isZero();
        when(productskuRepository.findForStockUpdate(1L, "L", 5L)).thenReturn(Optional.empty());
        assertThat(service.availableForUpdate(1L, "L", 5L)).isZero();
    }

    /** 실제 산술 — 복원은 수량을 더하고, 판매량은 0 아래로 내려가지 않는다. */
    @Test
    @DisplayName("updateSoldValue/updateProductSize_Qty: 음수 델타는 수량을 늘리고 판매량은 0 에서 멈춘다")
    void negativeDelta_restoresQuantityAndClampsSold() {
        ProductSku sku = ProductSku.builder().sold(2).build();
        when(productskuRepository.findForStockUpdate(1L, "M", 5L)).thenReturn(Optional.of(sku));
        when(productskuRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        doReturn(List.of()).when(service).updateProductDtoCache(anyString(), any());

        assertThat(service.updateSoldValue(1L, "slug", "M", 5L, -3).getSold())
                .as("2 - 3 은 0 에서 멈춘다").isZero();

        ProductSize size = ProductSize.builder().quantity(4).build();
        when(productSizeRepository.findFirstBySkuSkuproductIdAndSizeOrderBySizeIdAsc(10L, "M")).thenReturn(Optional.of(size));
        when(productSizeRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        doReturn(List.of()).when(service).refreshProductsCache(anyString());

        assertThat(service.updateProductSize_Qty("slug", 10L, "M", -3).getQuantity()).isEqualTo(7);
    }

    /** 재고가 바뀔 때마다 그 전·후 수량과 옵션 정보로 알린다 — 0 을 건넜는지는 받는 쪽이 판정한다. */
    @Test
    @DisplayName("재고 변경은 전·후 수량과 옵션(상품·색상·사이즈)으로 품절·재입고 판정에 넘어간다 (docs/53)")
    void stockChangesAreHandedToTransitions() {
        Product product = Product.builder().productId(10L).slug("tee").name("티셔츠").build();
        ProductColor color = ProductColor.builder().colorId(3L).color("black").build();
        ProductSku sku = ProductSku.builder().product(product).color(color).build();
        ProductSize size = ProductSize.builder().sizeId(77L).size("M").quantity(1).sku(sku).build();
        when(productSizeRepository.findFirstBySkuSkuproductIdAndSizeOrderBySizeIdAsc(10L, "M")).thenReturn(Optional.of(size));
        when(productSizeRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        doReturn(List.of()).when(service).refreshProductsCache(anyString());

        service.updateProductSize_Qty("tee", 10L, "M", 1);   // 주문 1개 → 0

        verify(stockEvents).onQuantityChanged(
                new com.project.catalog_service.message.producer.StockEventProducer.Variant(10L, "tee", "티셔츠", 3L, "black", "M"),
                1, 0);
    }

    @Test
    @DisplayName("🔴 관리자 재고 조정 — 그 값으로 정하고, 0 에서 채우면 재입고로 넘어간다 (docs/53)")
    void adminSetsStock() {
        Product product = Product.builder().productId(10L).slug("tee").name("티셔츠").build();
        ProductSku sku = ProductSku.builder().product(product).build();
        ProductSize size = ProductSize.builder().sizeId(77L).size("L").quantity(0).sku(sku).build();
        when(productSizeRepository.findById(77L)).thenReturn(Optional.of(size));
        when(productSizeRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        doReturn(List.of()).when(service).refreshProductsCache(anyString());

        ProductService.StockRow row = service.setStock(77L, 12);

        assertThat(row.quantity()).isEqualTo(12);
        assertThat(row.productId()).isEqualTo(10L);
        assertThat(row.colorId()).as("색상 없는 상품").isNull();
        verify(stockEvents).onQuantityChanged(
                new com.project.catalog_service.message.producer.StockEventProducer.Variant(10L, "tee", "티셔츠", null, null, "L"),
                0, 12);
        assertThat(service.setStock(999L, 1)).as("없는 사이즈").isNull();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.setStock(77L, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("productUpdated: null/빈 요청은 아무 갱신도 하지 않는다")
    void productUpdated_nullRequest_noSideEffects() {
        assertThat(service.productUpdated(null).lastUpdated()).isNull();
        assertThat(service.productUpdated(null).hasShortage()).isFalse();
        verify(service, never()).updateSoldValue(anyLong(), anyString(), anyString(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("productUpdated/addProduct: 검색·필터 캐시 무효화(@CacheEvict) 계약 유지 (CAT-2/CAT-12 회귀 방지)")
    void cacheEvictionContract_onWriteEntryPoints() throws Exception {
        // 자기호출(self-invocation) 때문에 evict는 외부 진입점 메서드에 붙어 있어야 한다.
        Method productUpdated = ProductService.class.getMethod(
                "productUpdated", ProductUpdateRequest.class);
        CacheEvict evict = productUpdated.getAnnotation(CacheEvict.class);
        assertThat(evict).as("productUpdated에 @CacheEvict가 있어야 함").isNotNull();
        assertThat(evict.allEntries()).isTrue();
        assertThat(List.of(evict.value())).contains(
                "search_cache", "colors_cache", "sizes_cache", "details_cache", "brands_cache", "products_by_category");

        Method addProduct = ProductService.class.getMethod(
                "addProduct",
                com.project.common.dto.catalog.request.ProductRequest.class,
                List.class,
                org.springframework.web.multipart.MultipartFile.class);
        // 부모 상품의 "product" 캐시도 함께 지우므로 @Caching 안에 둘이 있다(ProductCacheKeyTest).
        org.springframework.cache.annotation.Caching caching =
                addProduct.getAnnotation(org.springframework.cache.annotation.Caching.class);
        assertThat(caching).as("addProduct에 @Caching(evict = …) 가 있어야 함").isNotNull();
        assertThat(java.util.Arrays.stream(caching.evict()).flatMap(e -> java.util.Arrays.stream(e.value())))
                .contains("search_cache", "product");
    }
}
