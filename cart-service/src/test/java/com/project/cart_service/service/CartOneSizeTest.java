package com.project.cart_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.cart_service.client.CatalogServiceClient;
import com.project.cart_service.model.Cart;
import com.project.cart_service.repository.jpa.CartProductRepository;
import com.project.cart_service.repository.jpa.CartRepository;
import com.project.common.dto.ProductColorDto;
import com.project.common.dto.ProductDto;
import com.project.common.dto.ProductInfoDto;
import com.project.common.dto.ProductSizeDto;
import com.project.common.dto.ProductSkuDto;

/**
 * 사이즈가 하나뿐인 상품(사이즈 이름이 빈 값) — catalog 에 NULL 로 있든 {@code ''} 로 있든 담긴다.
 *
 * <p>🐛 2026-10-18 order 부하 측정(docs/83 §5): 담기 실패 536건이 모두 {@code getSize().equals(...)} 의 NPE 였다 — 데모 생성기가
 * 빈 사이즈를 NULL 로 넣은 상품이었다. 원래 시드도 사이즈 줄의 80% 가 빈 값이다.
 *
 * <p>[JA] 単一サイズ商品(サイズ名が空) — catalog に NULL でも '' でもカートに入る。以前は NULL で NPE になっていた。
 */
@DisplayName("cart-service — 사이즈가 하나뿐인 상품")
class CartOneSizeTest {

    private CartRepository cartRepository;
    private CatalogServiceClient catalog;
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

    @SuppressWarnings("unchecked")
    private void catalogWithSize(String catalogSize) {
        cartRepository = mock(CartRepository.class);
        catalog = mock(CatalogServiceClient.class);
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
        service = new CartService(cartRepository, mock(CartProductRepository.class), catalog, new ObjectMapper(), redis);

        stored = new Cart[1];
        when(cartRepository.findByUserId(7L)).thenAnswer(i -> Optional.ofNullable(stored[0]));
        when(cartRepository.save(any(Cart.class))).thenAnswer(i -> stored[0] = i.getArgument(0));
        when(cartRepository.insertIfAbsent(anyLong(), anyLong())).thenAnswer(i -> {
            stored[0] = stored[0] != null ? stored[0] : new Cart(i.getArgument(1));
            return 1;
        });
        ProductSkuDto sku = ProductSkuDto.builder().sku("CAM-000001").discount(0).images(List.of("img"))
                .color(ProductColorDto.builder().id("5").build())
                .sizes(List.of(ProductSizeDto.builder().size(catalogSize).skuCode("CAM-000001").quantity(40)
                        .price(new BigDecimal("120.00")).build()))
                .build();
        when(catalog.getProductsInfo(anyList())).thenReturn(ResponseEntity.ok(List.of(ProductDto.builder()
                .id("1").name("Action Camera").shipping("0").skus(List.of(sku)).build())));
        when(catalog.getColorsInfo(anyList())).thenReturn(ResponseEntity.ok(List.of()));
        when(catalog.getCartProductsInfo(anyList())).thenReturn(ResponseEntity.ok(Map.of()));
    }

    @ParameterizedTest(name = "catalog 사이즈 {0} · 본문 사이즈 {1}")
    @CsvSource(value = {"NULL, NULL", "NULL, ''", "'', NULL", "'', ''"}, nullValues = "NULL")
    @DisplayName("🔴 빈 사이즈(NULL · '')끼리는 같은 사이즈 — 담기 · 가격 · SKU 가 그대로")
    void emptySizesMatch(String catalogSize, String bodySize) {
        catalogWithSize(catalogSize);

        service.putItem(7L, ProductInfoDto.builder().id("1").style(0).size(bodySize).qty(2)
                .color(ProductColorDto.builder().id("5").build()).build());

        assertThat(stored[0].getCartProducts()).singleElement().satisfies(line -> {
            assertThat(line.getQty()).isEqualTo(2);
            assertThat(line.getPrice()).isEqualByComparingTo("120.00");
            assertThat(line.getSkuCode()).isEqualTo("CAM-000001");
        });
    }
}
