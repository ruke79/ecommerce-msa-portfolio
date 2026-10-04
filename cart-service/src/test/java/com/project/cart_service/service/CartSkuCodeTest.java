package com.project.cart_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.cart_service.client.CatalogServiceClient;
import com.project.cart_service.model.Cart;
import com.project.cart_service.model.CartProduct;
import com.project.cart_service.repository.jpa.CartProductRepository;
import com.project.cart_service.repository.jpa.CartRepository;
import com.project.common.dto.CartDto;
import com.project.common.dto.CartProductDto;
import com.project.common.dto.ProductColorDto;
import com.project.common.dto.ProductDto;
import com.project.common.dto.ProductInfoDto;
import com.project.common.dto.ProductSizeDto;
import com.project.common.dto.ProductSkuDto;

/**
 * 장바구니 줄의 SKU — 사이즈 한 줄의 재고 단위 코드(catalog V17)를 적어 두고, 주문(/checkout)이 그대로 가져간다.
 *
 * <p>줄의 식별은 여전히 (상품, 색상, 사이즈 이름)이다. SKU 는 기록이라, 값의 출처가 <b>catalog</b> 여야 한다 — 본문을 믿으면
 * 주문 품목 · 포장 목록에 고객이 쓴 코드가 찍힌다.
 */
@DisplayName("cart-service — 장바구니 줄의 SKU")
class CartSkuCodeTest {

    private CartRepository cartRepository;
    private CatalogServiceClient catalog;
    private ValueOperations<String, Object> cache;
    private CartService service;
    private Cart[] stored;

    @BeforeEach
    void openTransactionSynchronization() {
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void closeTransactionSynchronization() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        cartRepository = mock(CartRepository.class);
        catalog = mock(CatalogServiceClient.class);
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        cache = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(cache);
        service = new CartService(cartRepository, mock(CartProductRepository.class), catalog, new ObjectMapper(), redis);

        stored = new Cart[1];
        when(cartRepository.findByUserId(7L)).thenAnswer(i -> Optional.ofNullable(stored[0]));
        when(cartRepository.save(any(Cart.class))).thenAnswer(i -> stored[0] = i.getArgument(0));
        // 처음 담기 — 없을 때만 만든다(CartRepository.insertIfAbsent, V5)
        when(cartRepository.insertIfAbsent(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(i -> {
                    if (stored[0] != null) {
                        return 0;
                    }
                    stored[0] = new Cart(i.getArgument(1));
                    return 1;
                });

        // 상품 1 — 스타일 둘(검정 5, 흰색 6). 스타일마다 사이즈 줄의 SKU 가 다르다.
        when(catalog.getProductsInfo(anyList())).thenAnswer(i -> ResponseEntity.ok(List.of(ProductDto.builder()
                .id("1").name("러닝화").shipping("0")
                .skus(List.of(style(5L, "RUN-BLK"), style(6L, "RUN-WHT")))
                .build())));
        when(catalog.getColorsInfo(anyList())).thenReturn(ResponseEntity.ok(List.of()));
        when(catalog.getCartProductsInfo(anyList())).thenReturn(ResponseEntity.ok(java.util.Map.of()));
    }

    private static ProductSkuDto style(Long colorId, String code) {
        return ProductSkuDto.builder().sku(code).discount(0).images(List.of("img"))
                .color(ProductColorDto.builder().id(Long.toString(colorId)).build())
                .sizes(List.of(
                        ProductSizeDto.builder().size("270").skuCode(code + "-270").price(new BigDecimal("100")).build(),
                        ProductSizeDto.builder().size("280").skuCode(code + "-280").price(new BigDecimal("100")).build()))
                .build();
    }

    @Test
    @DisplayName("담으면 그 사이즈 줄의 SKU 를 catalog 에서 적는다 — 본문의 skuCode 는 무시")
    void putRecordsTheSizeSkuFromCatalog() {
        ProductInfoDto body = ProductInfoDto.builder().id("1").style(1).size("280").qty(1)
                .skuCode("FORGED-999")   // 고객이 보낸 값
                .color(ProductColorDto.builder().id("6").build()).build();

        service.putItem(7L, body);

        assertThat(stored[0].getCartProducts()).singleElement()
                .satisfies(line -> assertThat(line.getSkuCode()).isEqualTo("RUN-WHT-280"));
    }

    @Test
    @DisplayName("다시 담으면(같은 줄) SKU 도 지금 값으로")
    void reputRefreshesTheSku() {
        service.putItem(7L, ProductInfoDto.builder().id("1").style(0).size("270").qty(1)
                .color(ProductColorDto.builder().id("5").build()).build());
        stored[0].getCartProducts().get(0).setSkuCode(null);   // 코드가 생기기 전에 담긴 줄처럼

        service.putItem(7L, ProductInfoDto.builder().id("1").style(0).size("270").qty(3)
                .color(ProductColorDto.builder().id("5").build()).build());

        assertThat(stored[0].getCartProducts()).singleElement()
                .satisfies(line -> assertThat(line.getSkuCode()).isEqualTo("RUN-BLK-270"));
    }

    /**
     * 코드가 생기기 전에 담긴 줄은 SKU 가 비어 있다. 주문 품목이 빈 SKU 로 남지 않게 /checkout 을 만들 때 catalog 에서
     * 채운다 — 스타일은 저장된 순번이 아니라 <b>색상 id</b> 로 고른다(순번은 색상이 추가되면 밀린다).
     */
    @Test
    @DisplayName("SKU 가 빈 옛 줄은 /checkout 을 만들 때 catalog 에서 채운다 — 스타일은 색상 id 로")
    void checkoutFillsMissingSkuByColor() {
        Cart cart = cartWith(line(1L, 6L, "270", 0 /* 순번은 일부러 틀리게 */, null));
        stored[0] = cart;

        CartDto dto = service.getCart(7L);

        assertThat(dto.getProducts()).singleElement()
                .satisfies(p -> assertThat(p.getSkuCode()).isEqualTo("RUN-WHT-270"));
    }

    @Test
    @DisplayName("줄마다 SKU 가 있으면 catalog 상품 조회를 하지 않는다")
    void checkoutDoesNotCallCatalogWhenComplete() {
        stored[0] = cartWith(line(1L, 5L, "280", 0, "RUN-BLK-280"));

        CartDto dto = service.getCart(7L);

        assertThat(dto.getProducts()).singleElement()
                .satisfies(p -> assertThat(p.getSkuCode()).isEqualTo("RUN-BLK-280"));
        verify(catalog, never()).getProductsInfo(anyList());
    }

    /** 배포 전에 만든 캐시에는 SKU 가 없다. 그대로 돌려주면 주문 품목의 SKU 가 비므로, 그런 캐시는 쓰지 않는다. */
    @Test
    @DisplayName("SKU 가 빈 줄이 든 캐시는 쓰지 않고 다시 만든다")
    void staleCacheWithoutSkuIsRebuilt() {
        CartDto stale = CartDto.builder().userId("7")
                .products(List.of(CartProductDto.builder().productId("1").size("270").qty(1).build()))
                .build();
        when(cache.get(any())).thenReturn(stale);
        stored[0] = cartWith(line(1L, 5L, "270", 0, "RUN-BLK-270"));

        CartDto dto = service.getCart(7L);

        assertThat(dto).isNotSameAs(stale);
        assertThat(dto.getProducts()).singleElement()
                .satisfies(p -> assertThat(p.getSkuCode()).isEqualTo("RUN-BLK-270"));
    }

    @Test
    @DisplayName("SKU 가 다 있는 캐시는 그대로 쓴다")
    void completeCacheIsUsed() {
        CartDto fresh = CartDto.builder().userId("7")
                .products(List.of(CartProductDto.builder().productId("1").skuCode("RUN-BLK-270").build()))
                .build();
        when(cache.get(any())).thenReturn(fresh);

        assertThat(service.getCart(7L)).isSameAs(fresh);
        verify(cartRepository, never()).findByUserId(7L);
    }

    private static CartProduct line(Long productId, Long colorId, String size, int style, String skuCode) {
        return CartProduct.builder().cartproductId(100L + colorId).productId(productId).colorId(colorId).size(size)
                .style(style).qty(1).price(new BigDecimal("100")).shipping(BigDecimal.ZERO).skuCode(skuCode)
                .name("러닝화").build();
    }

    private static Cart cartWith(CartProduct... lines) {
        Cart cart = new Cart(7L);
        cart.setCartTotal(BigDecimal.ZERO);
        cart.setTotalAfterDiscount(BigDecimal.ZERO);
        cart.setCartProducts(new ArrayList<>(List.of(lines)));
        for (CartProduct l : lines) {
            l.setCart(cart);
        }
        return cart;
    }
}
