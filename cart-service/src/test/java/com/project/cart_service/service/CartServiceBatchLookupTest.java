package com.project.cart_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.cart_service.client.CatalogServiceClient;
import com.project.cart_service.model.Cart;
import com.project.cart_service.model.CartProduct;
import com.project.cart_service.repository.jpa.CartProductRepository;
import com.project.cart_service.repository.jpa.CartRepository;
import com.project.common.dto.ProductDto;
import com.project.common.dto.ProductInfoDto;
import com.project.common.dto.ProductSizeDto;
import com.project.common.dto.ProductSkuDto;
import com.project.common.dto.catalog.request.ProductInfoRequest;

/**
 * 장바구니 계산이 catalog 를 <b>항목 수와 무관하게 한 번</b> 부르는지.
 *
 * <p>예전에는 항목마다 {@code getProductInfo} 였다 — 항목 N 개면 Feign 왕복 N 번. 배치
 * 엔드포인트로 바꾼 뒤 누군가 편의상 단건 호출로 되돌리면 컴파일은 그대로 되고 기능도
 * 그대로 동작한다. 호출 횟수를 세어야만 보인다. 그래서 여기서 센다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CartService · catalog 배치 조회")
class CartServiceBatchLookupTest {

    @Mock CartRepository cartRepository;
    @Mock CartProductRepository cartProductRepository;
    @Mock CatalogServiceClient catalogServiceClient;
    @Spy ObjectMapper objectMapper = new ObjectMapper();
    @Mock RedisTemplate<String, Object> redisTemplate;

    @InjectMocks CartService cartService;

    private static ProductDto product(String id, String size, String price, int discount, String shipping) {
        return ProductDto.builder()
                .id(id)
                .name("p" + id)
                .shipping(shipping)
                .skus(List.of(ProductSkuDto.builder()
                        .discount(discount)
                        .sizes(List.of(ProductSizeDto.builder().size(size).price(new BigDecimal(price)).build()))
                        .build()))
                .build();
    }

    private static ProductInfoRequest requestOf(ProductInfoDto... items) {
        ProductInfoRequest request = mock(ProductInfoRequest.class);
        when(request.getProducts()).thenReturn(List.of(items));
        return request;
    }

    @Test
    @DisplayName("항목 셋(같은 상품 둘 포함) → getProductsInfo 한 번, 중복 없는 id 로; 단건 getProductInfo 는 0번")
    void updateCart_fetchesCatalogOnce_withDistinctIds() {
        when(catalogServiceClient.getProductsInfo(List.of(1L, 2L)))
                .thenReturn(ResponseEntity.ok(List.of(
                        product("1", "M", "100", 0, "5"),
                        product("2", "L", "80", 10, "0"))));

        List<ProductInfoDto> result = cartService.updateCart(requestOf(
                ProductInfoDto.builder().id("1").style(0).size("M").qty(2).build(),
                ProductInfoDto.builder().id("1").style(0).size("M").qty(1).build(),   // 같은 상품, 다른 줄
                ProductInfoDto.builder().id("2").style(0).size("L").qty(1).build()));

        // 왕복 횟수 — 이 테스트의 존재 이유.
        verify(catalogServiceClient, times(1)).getProductsInfo(List.of(1L, 2L));
        verify(catalogServiceClient, never()).getProductInfo(anyString());

        // 계산은 예전과 같아야 한다.
        assertThat(result).hasSize(3);
        assertThat(result.get(0).getPriceBefore()).isEqualTo("100");
        assertThat(result.get(0).getPrice()).isEqualTo("100");
        assertThat(result.get(0).getShipping()).isEqualTo("5");
        assertThat(result.get(2).getPriceBefore()).isEqualTo("80");
        assertThat(result.get(2).getPrice()).isEqualTo("72.00");       // 80 × 90%
    }

    @Test
    @DisplayName("🔴 할인은 퍼센트 — 50% 면 반값(옛 식 price − price/d 는 9,800 원 = 2% 할인이었다)")
    void discountIsPercent() {
        when(catalogServiceClient.getProductsInfo(List.of(1L, 2L)))
                .thenReturn(ResponseEntity.ok(List.of(
                        product("1", "M", "10000", 50, "0"),
                        product("2", "L", "10000", 5, "0"))));

        List<ProductInfoDto> result = cartService.updateCart(requestOf(
                ProductInfoDto.builder().id("1").style(0).size("M").qty(1).build(),
                ProductInfoDto.builder().id("2").style(0).size("L").qty(1).build()));

        assertThat(result.get(0).getPrice()).isEqualTo("5000.00");
        assertThat(result.get(1).getPrice()).isEqualTo("9500.00");
        assertThat(result.get(1).getPriceBefore()).isEqualTo("10000");
    }

    @Test
    @DisplayName("🔴 loadCart 는 catalog 가 준 줄 정보를 빠짐없이 돌려준다 — skuCode 포함(손으로 쓴 복사 생성자가 V17 때 빠뜨렸다)")
    void loadCart_keepsEveryFieldFromCatalog() {
        Cart cart = new Cart(7L);
        cart.setCartProducts(List.of(CartProduct.builder().productId(2L).style(0).size("S").qty(1)._uid("u1").build()));
        when(cartRepository.findByUserId(7L)).thenReturn(Optional.of(cart));
        when(catalogServiceClient.getProductsInfo(List.of(2L)))
                .thenReturn(ResponseEntity.ok(List.of(product("2", "S", "50", 0, "0"))));
        when(catalogServiceClient.getCartProductsInfo(any())).thenReturn(ResponseEntity.ok(Map.of(
                "2:0:0", ProductInfoDto.builder().id("2").style(0).size("S").name("p2")
                        .sku("FAS-2").skuCode("FAS-2-S").slug("p2-slug").brand("b").build())));

        ProductInfoDto line = cartService.loadCart(7L).get(0);

        assertThat(line.getSkuCode()).as("catalog 가 준 사이즈 SKU").isEqualTo("FAS-2-S");
        assertThat(line.getSku()).isEqualTo("FAS-2");
        assertThat(line.getSlug()).isEqualTo("p2-slug");
        assertThat(line.getBrand()).isEqualTo("b");
    }

    @Test
    @DisplayName("loadCart: 항목 셋 → 상품 배치 1회 + 장바구니 정보 배치 1회 (예전엔 2N). 단건 둘은 0번")
    void loadCart_makesExactlyTwoCatalogCalls_regardlessOfItemCount() {
        Cart cart = new Cart(7L);
        cart.setCartProducts(List.of(
                CartProduct.builder().productId(1L).style(0).size("M").qty(2)._uid("u1").build(),
                CartProduct.builder().productId(1L).style(0).size("L").qty(1)._uid("u2").build(),   // 같은 상품, 다른 사이즈
                CartProduct.builder().productId(2L).style(0).size("S").qty(1)._uid("u3").build()));
        when(cartRepository.findByUserId(7L)).thenReturn(Optional.of(cart));

        ProductDto p1 = ProductDto.builder().id("1").shipping("5")
                .skus(List.of(ProductSkuDto.builder().discount(0).sizes(List.of(
                        ProductSizeDto.builder().size("M").price(new BigDecimal("100")).build(),
                        ProductSizeDto.builder().size("L").price(new BigDecimal("110")).build())).build()))
                .build();
        ProductDto p2 = product("2", "S", "50", 0, "0");
        when(catalogServiceClient.getProductsInfo(List.of(1L, 2L))).thenReturn(ResponseEntity.ok(List.of(p1, p2)));

        // 장바구니 정보 배치 — 키는 productId:style:sizeIndex. L 은 p1 의 사이즈 목록에서 1번째다.
        when(catalogServiceClient.getCartProductsInfo(any())).thenReturn(ResponseEntity.ok(Map.of(
                "1:0:0", ProductInfoDto.builder().id("1").style(0).size("M").name("p1").build(),
                "1:0:1", ProductInfoDto.builder().id("1").style(0).size("L").name("p1").build(),
                "2:0:0", ProductInfoDto.builder().id("2").style(0).size("S").name("p2").build())));

        List<ProductInfoDto> result = cartService.loadCart(7L);

        // 왕복 횟수.
        verify(catalogServiceClient, times(1)).getProductsInfo(List.of(1L, 2L));
        // 배치 요청에 세 항목이 productId:style:sizeIndex 로 정확히 담겼는가.
        verify(catalogServiceClient, times(1)).getCartProductsInfo(List.of("1:0:0", "1:0:1", "2:0:0"));
        verify(catalogServiceClient, never()).getProductInfo(anyString());
        verify(catalogServiceClient, never()).getCartProductInfo(anyString(), anyInt(), anyInt());

        // 계산은 예전과 같아야 한다.
        assertThat(result).hasSize(3);
        assertThat(result.get(1).getPriceBefore()).isEqualTo("110");
        assertThat(result.get(1).getQty()).isEqualTo(1);
        assertThat(result.get(1).get_uid()).isEqualTo("u2");
        assertThat(result.get(2).getShipping()).isEqualTo("0");
    }

    @Test
    @DisplayName("getCart(캐시 미스): 항목 셋(색상 둘) → getColorsInfo 한 번, 단건 getColorInfo 0번")
    @SuppressWarnings("unchecked")
    void getCart_fetchesColorsOnce() {
        org.springframework.data.redis.core.ValueOperations<String, Object> ops =
                mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.get("carts::7")).thenReturn(null);                        // 캐시 미스 — 키는 사용자 id

        Cart cart = new Cart(7L);
        cart.setCartProducts(List.of(
                CartProduct.builder().cartproductId(11L).productId(1L).colorId(1L).price(BigDecimal.TEN).qty(1).build(),
                CartProduct.builder().cartproductId(12L).productId(1L).colorId(1L).price(BigDecimal.TEN).qty(1).build(),  // 같은 색
                CartProduct.builder().cartproductId(13L).productId(2L).colorId(2L).price(BigDecimal.TEN).qty(1).build()));
        when(cartRepository.findByUserId(7L)).thenReturn(Optional.of(cart));

        when(catalogServiceClient.getColorsInfo(List.of(1L, 2L))).thenReturn(ResponseEntity.ok(List.of(
                com.project.common.dto.ProductColorDto.builder().id("1").color("red").build(),
                com.project.common.dto.ProductColorDto.builder().id("2").color("blue").build())));

        var dto = cartService.getCart(7L);

        verify(catalogServiceClient, times(1)).getColorsInfo(List.of(1L, 2L));
        verify(catalogServiceClient, never()).getColorInfo(anyString());
        assertThat(dto.getProducts()).hasSize(3);
        assertThat(dto.getProducts().get(0).getColor().getColor()).isEqualTo("red");
        assertThat(dto.getProducts().get(2).getColor().getColor()).isEqualTo("blue");
    }

    @Test
    @DisplayName("catalog 가 모르는 id 는 결과에서 빠지고, 그 항목은 명확한 예외가 된다")
    void updateCart_failsClearly_whenCatalogOmitsAnId() {
        when(catalogServiceClient.getProductsInfo(List.of(1L, 3L)))
                .thenReturn(ResponseEntity.ok(List.of(product("1", "M", "100", 0, "0"))));

        assertThatThrownBy(() -> cartService.updateCart(requestOf(
                ProductInfoDto.builder().id("1").style(0).size("M").qty(1).build(),
                ProductInfoDto.builder().id("3").style(0).size("M").qty(1).build())))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Product not found: 3");
    }
}
