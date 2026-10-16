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
 * 장바구니 캐시 — 만료가 있고, 커밋 뒤 갱신이 실패하면 키를 지운다.
 *
 * <p>🐛 예전: 만료 없이 썼고, 커밋 뒤 갱신이 실패하면 옛 장바구니가 영영 남아 주문이 옛 줄 · 옛 합계로 만들어졌다.
 *
 * <p>[JA] カートキャッシュ — 有効期限があり、コミット後の更新が失敗すればキーを消す。
 */
@org.junit.jupiter.api.DisplayName("cart-service — 장바구니 캐시 만료 · 갱신 실패")
class CartCacheTtlTest {
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

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("🔴 조회가 채우는 캐시는 만료(30분)를 붙여 쓴다")
    void cacheWritesHaveTtl() {
        service.putItem(7L, item("270", 1, 5L));
        persisted();

        service.getCart(7L);

        org.mockito.Mockito.verify(cache, org.mockito.Mockito.atLeastOnce())
                .set(org.mockito.ArgumentMatchers.eq(CartService.cacheKey(7L)), any(), org.mockito.ArgumentMatchers.eq(CartService.CACHE_TTL));
        org.mockito.Mockito.verify(cache, org.mockito.Mockito.never()).set(any(), any());
    }

    /**
     * 🔴 바꾼 뒤에는 커밋 뒤 캐시를 <b>지운다</b> — 다시 만들지 않는다. 예전에는 afterCommit 에서 새 DTO 를 만들었고 그 변환이 catalog 를
     * 불렀다: afterCommit 은 트랜잭션 자원을 정리하기 전이라 DB 커넥션을 쥔 채 기다렸다(B-87). 옛 값도 남지 않는다.
     */
    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("🔴 바꾼 뒤 커밋되면 캐시를 지운다 — 커밋 뒤에는 catalog 를 부르지 않는다(커넥션을 쥔 채 기다리지 않는다)")
    void commitEvictsWithoutCallingCatalog() {
        service.putItem(7L, item("270", 1, 5L));
        persisted();
        org.mockito.Mockito.clearInvocations(catalog, cache);

        org.springframework.transaction.support.TransactionSynchronizationUtils.triggerAfterCommit();

        org.mockito.Mockito.verify(redis).delete(CartService.cacheKey(7L));
        org.mockito.Mockito.verifyNoInteractions(catalog);
        org.mockito.Mockito.verify(cache, org.mockito.Mockito.never()).set(any(), any(), any(java.time.Duration.class));
    }
}
