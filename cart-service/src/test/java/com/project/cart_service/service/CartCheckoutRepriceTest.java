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
 * 체크아웃(주문의 근거)은 고른 줄의 가격을 catalog 의 지금 값으로 맞춘다.
 *
 * <p>🐛 예전: 담을 때의 가격 그대로 — 할인 중에 담아 두면 할인이 끝난 뒤에도 할인가로 결제됐다.
 *
 * <p>[JA] チェックアウトは選んだ行の価格を catalog の現在値に合わせる。以前は入れた時の価格のままだった。
 */
@org.junit.jupiter.api.DisplayName("cart-service — 체크아웃 가격 재확인")
class CartCheckoutRepriceTest {
    private RedisTemplate<String, Object> redis;


    private CartRepository cartRepository;
    private CatalogServiceClient catalog;
    private ValueOperations<String, Object> cache;
    private CartService service;
    private Cart[] stored;

    @BeforeEach
    void openTransactionSynchronization() {
        TransactionSynchronizationManager.initSynchronization();
    }

    @org.junit.jupiter.api.AfterEach
    void closeTransactionSynchronization() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        cartRepository = mock(CartRepository.class);
        catalog = mock(CatalogServiceClient.class);
        redis = mock(RedisTemplate.class);
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

    /** 저장된 줄처럼 id 를 붙인다 — 목 저장소는 TSID 를 매기지 않는다(캐시 DTO 는 줄 id 를 싣는다). */
    private void persisted() {
        long id = 100;
        for (CartProduct line : stored[0].getCartProducts()) {
            org.springframework.test.util.ReflectionTestUtils.setField(line, "cartproductId", id++);
        }
    }

    private static ProductInfoDto item(String size, int qty, Long colorId) {
        return ProductInfoDto.builder().id("1").style(0).size(size).qty(qty)
                .color(ProductColorDto.builder().id(Long.toString(colorId)).build()).build();
    }

    private void catalogPrice(String price, int discount) {
        when(catalog.getProductsInfo(anyList())).thenAnswer(i -> ResponseEntity.ok(List.of(ProductDto.builder()
                .id("1").name("러닝화").shipping("0")
                .skus(List.of(ProductSkuDto.builder().sku("RUN-BLK").discount(discount).images(List.of("img"))
                        .color(ProductColorDto.builder().id("5").build())
                        .sizes(List.of(ProductSizeDto.builder().size("270").skuCode("RUN-BLK-270").price(new BigDecimal(price)).build()))
                        .build()))
                .build())));
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("🔴 할인이 끝났으면 체크아웃의 가격 · 합계는 지금 값 — 담을 때의 할인가가 아니다")
    void checkoutUsesCurrentPrice() {
        catalogPrice("100", 30);   // 담을 때: 30% 할인 → 70
        service.putItem(7L, item("270", 2, 5L));
        assertThat(stored[0].getCartTotal()).isEqualByComparingTo("140");
        persisted();
        org.springframework.transaction.support.TransactionSynchronizationUtils.triggerAfterCommit();

        catalogPrice("100", 0);   // 할인 끝
        service.getCartForCheckout(7L);

        assertThat(stored[0].getCartProducts()).singleElement().satisfies(l -> assertThat(l.getPrice()).isEqualByComparingTo("100"));
        assertThat(stored[0].getCartTotal()).isEqualByComparingTo("200");
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("catalog 에 닿지 못하면 저장된 가격 그대로 — 주문을 막지 않는다")
    void catalogDownKeepsStoredPrice() {
        catalogPrice("100", 30);
        service.putItem(7L, item("270", 2, 5L));
        persisted();
        when(catalog.getProductsInfo(anyList())).thenThrow(new RuntimeException("catalog down"));

        service.getCartForCheckout(7L);

        assertThat(stored[0].getCartTotal()).isEqualByComparingTo("140");
    }
}
