package com.project.cart_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.cart_service.client.CatalogServiceClient;
import com.project.cart_service.model.Cart;
import com.project.cart_service.model.CartProduct;
import com.project.cart_service.repository.jpa.CartProductRepository;
import com.project.cart_service.repository.jpa.CartRepository;
import com.project.common.dto.CartDto;
import com.project.common.dto.ProductColorDto;
import com.project.common.dto.ProductDto;
import com.project.common.dto.ProductInfoDto;
import com.project.common.dto.ProductSizeDto;
import com.project.common.dto.ProductSkuDto;
import com.project.common.dto.cart.request.CartRequest;

/**
 * 서버가 장바구니의 기준이다 — 담기·수량·삭제가 바로 서버에 반영되고, 체크아웃은 고른 줄을 <b>표시</b>만 한다(docs/40 0-b).
 *
 * <h3>🐛 회귀 대상</h3>
 * <ul>
 *   <li>체크아웃이 서버 장바구니를 고른 줄로 <b>통째로 바꿨다</b> — 고르지 않은 줄은 서버에서 사라지고, 다른 기기에서는 옛 장바구니가 보였다.</li>
 *   <li>catalog 가 모르는 줄이 하나 있으면 장바구니 전체가 예외였다 — 그 줄을 빼는 요청까지 롤백됐다.</li>
 * </ul>
 */
@DisplayName("cart-service — 서버 기준 장바구니")
class CartServerAuthoritativeTest {

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

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        cartRepository = mock(CartRepository.class);
        catalog = mock(CatalogServiceClient.class);
        service = new CartService(cartRepository, mock(CartProductRepository.class), catalog, new ObjectMapper(),
                mock(RedisTemplate.class));

        stored = new Cart[1];
        when(cartRepository.findByUserId(7L)).thenAnswer(i -> Optional.ofNullable(stored[0]));
        when(cartRepository.save(any(Cart.class))).thenAnswer(i -> stored[0] = i.getArgument(0));
        org.mockito.Mockito.doAnswer(i -> stored[0] = null).when(cartRepository).delete(any(Cart.class));

        // catalog — 상품 1(M·L), 2(사이즈 없음), 색상은 SKU 가 정한다(5, 6)
        when(catalog.getProductsInfo(anyList())).thenAnswer(i -> {
            List<Long> ids = i.getArgument(0);
            List<ProductDto> found = new ArrayList<>();
            if (ids.contains(1L)) found.add(product("1", 5L, "10", Map.of("M", "20.00", "L", "25.00")));
            if (ids.contains(2L)) found.add(product("2", 6L, "0", Map.of("", "30.00")));
            return ResponseEntity.ok(found);
        });
        when(catalog.getCartProductsInfo(anyList())).thenAnswer(i -> {
            List<String> keys = i.getArgument(0);
            Map<String, ProductInfoDto> found = new HashMap<>();
            for (String k : keys) {
                String[] parts = k.split(":");
                found.put(k, ProductInfoDto.builder().id(parts[0]).style(0).name("p" + parts[0])
                        .color(ProductColorDto.builder().id(parts[0].equals("1") ? "5" : "6").build()).build());
            }
            return ResponseEntity.ok(found);
        });
    }

    private static ProductDto product(String id, Long colorId, String shipping, Map<String, String> sizes) {
        List<ProductSizeDto> list = new ArrayList<>();
        sizes.entrySet().stream().sorted(Map.Entry.comparingByKey(java.util.Comparator.reverseOrder()))
                .forEach(e -> list.add(ProductSizeDto.builder().size(e.getKey()).price(new BigDecimal(e.getValue())).build()));
        return ProductDto.builder().id(id).name("p" + id).shipping(shipping)
                .skus(List.of(ProductSkuDto.builder().discount(0).images(List.of("img" + id))
                        .color(ProductColorDto.builder().id(Long.toString(colorId)).build())
                        .sizes(list).build()))
                .build();
    }

    private static ProductInfoDto item(String id, String size, int qty) {
        // 본문의 색상은 믿지 않는다 — 일부러 엉뚱한 값을 싣는다
        return ProductInfoDto.builder().id(id).style(0).size(size).qty(qty)._uid("u" + id + size)
                .color(ProductColorDto.builder().id("999").build()).build();
    }

    private static CartRequest checkout(ProductInfoDto... items) {
        CartRequest request = mock(CartRequest.class);
        when(request.getProducts()).thenReturn(List.of(items));
        return request;
    }

    @Test
    @DisplayName("담기 — 장바구니가 없으면 만들고, 색상은 SKU 에서(본문 무시), 응답은 지금 장바구니 전체")
    void putCreatesCartAndLine() {
        List<ProductInfoDto> now = service.putItem(7L, item("1", "M", 2));

        assertThat(stored[0].getCartProducts()).singleElement().satisfies(line -> {
            assertThat(line.getProductId()).isEqualTo(1L);
            assertThat(line.getColorId()).isEqualTo(5L);
            assertThat(line.getQty()).isEqualTo(2);
            assertThat(line.isSelected()).isTrue();
        });
        assertThat(stored[0].getCartTotal()).isEqualByComparingTo("50.00");   // 20×2 + 배송 10
        assertThat(now).singleElement().satisfies(p -> assertThat(p.getQty()).isEqualTo(2));
    }

    @Test
    @DisplayName("같은 줄(상품·색상·사이즈)을 다시 담으면 줄이 늘지 않고 수량이 그 값이 된다. 사이즈가 다르면 다른 줄")
    void putSameLineReplacesQty() {
        service.putItem(7L, item("1", "M", 2));
        service.putItem(7L, item("1", "M", 3));
        service.putItem(7L, item("1", "L", 1));

        assertThat(stored[0].getCartProducts()).hasSize(2);
        assertThat(stored[0].getCartProducts()).filteredOn(l -> "M".equals(l.getSize()))
                .singleElement().extracting(CartProduct::getQty).isEqualTo(3);
    }

    @Test
    @DisplayName("수량 0 이하·상품 없음·catalog 가 모르는 상품은 담지 않는다")
    void putRejectsBadInput() {
        assertThatThrownBy(() -> service.putItem(7L, item("1", "M", 0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.putItem(7L, ProductInfoDto.builder().qty(1).build()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.putItem(7L, item("3", "M", 1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("상품을 찾을 수 없습니다.");
    }

    @Test
    @DisplayName("수량 바꾸기 — 빈 사이즈도 같은 줄로 찾는다. 0 이면 뺀다")
    void setQtyAndZeroRemoves() {
        service.putItem(7L, item("1", "M", 1));
        service.putItem(7L, item("2", "", 1));

        service.setItemQty(7L, 2L, 6L, null, 4);
        assertThat(stored[0].getCartProducts()).filteredOn(l -> l.getProductId() == 2L)
                .singleElement().extracting(CartProduct::getQty).isEqualTo(4);

        service.setItemQty(7L, 2L, 6L, "", 0);
        assertThat(stored[0].getCartProducts()).extracting(CartProduct::getProductId).containsExactly(1L);
    }

    @Test
    @DisplayName("마지막 줄을 빼면 장바구니를 지운다. 없는 장바구니·없는 줄은 그대로(여러 번 불러도 같다)")
    void removeLastLineDeletesCart() {
        service.putItem(7L, item("1", "M", 1));

        service.removeItem(7L, 1L, 99L, "M");                   // 색상이 다르면 다른 줄
        assertThat(stored[0]).isNotNull();

        List<ProductInfoDto> now = service.removeItem(7L, 1L, 5L, "M");
        assertThat(stored[0]).isNull();
        assertThat(now).isNull();                               // loadCart 와 같다 — 장바구니 없음

        assertThat(service.removeItem(7L, 1L, 5L, "M")).isEmpty();
    }

    /** 🔴 핵심 — 체크아웃이 고르지 않은 줄을 지우지 않는다. */
    @Test
    @DisplayName("🔴 체크아웃은 고른 줄만 selected — 고르지 않은 줄은 남고, 총액·/checkout 은 고른 줄로만")
    void checkoutMarksSelectionWithoutDeleting() {
        service.putItem(7L, item("1", "M", 1));
        service.putItem(7L, item("2", "", 2));

        service.saveCart(checkout(item("1", "M", 1)), 7L);

        assertThat(stored[0].getCartProducts()).hasSize(2);
        assertThat(stored[0].getCartProducts()).filteredOn(CartProduct::isSelected)
                .extracting(CartProduct::getProductId).containsExactly(1L);
        assertThat(stored[0].getCartTotal()).isEqualByComparingTo("30.00");   // 20 + 배송 10

        long id = 100;
        for (CartProduct line : stored[0].getCartProducts()) {
            line.setCartproductId(id++);                        // JPA 가 채우는 값
        }
        when(catalog.getColorsInfo(anyList())).thenReturn(ResponseEntity.ok(List.of()));
        @SuppressWarnings("unchecked")
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        org.springframework.data.redis.core.ValueOperations<String, Object> ops =
                mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        CartDto checkoutView = new CartService(cartRepository, mock(CartProductRepository.class), catalog,
                new ObjectMapper(), redis).getCart(7L);
        assertThat(checkoutView.getProducts()).extracting(p -> p.getProductId()).containsExactly("1");

        // 다음 체크아웃에서 다시 고르면 다시 들어간다
        service.saveCart(checkout(item("2", "", 2)), 7L);
        assertThat(stored[0].getCartProducts()).filteredOn(CartProduct::isSelected)
                .extracting(CartProduct::getProductId).containsExactly(2L);
    }

    @Test
    @DisplayName("체크아웃에 장바구니에 없던 줄이 오면(로그인 전 담은 것) 더해서 고른다")
    void checkoutAddsMissingLine() {
        service.putItem(7L, item("1", "M", 1));

        service.saveCart(checkout(item("2", "", 1)), 7L);

        assertThat(stored[0].getCartProducts()).hasSize(2);
        assertThat(stored[0].getCartProducts()).filteredOn(CartProduct::isSelected)
                .extracting(CartProduct::getProductId).containsExactly(2L);
    }

    /** 🔴 catalog 가 모르는 줄이 장바구니를 막지 않는다. */
    @Test
    @DisplayName("🔴 catalog 가 모르는 줄은 응답에서만 빠지고, 나머지 줄은 보이고, 그 줄도 뺄 수 있다")
    void unknownLineDoesNotBlockTheCart() {
        service.putItem(7L, item("1", "M", 1));
        CartProduct gone = CartProduct.builder().productId(3L).colorId(8L).size("M").qty(1)
                .price(BigDecimal.ONE).shipping(BigDecimal.ZERO).build();
        stored[0].getCartProducts().add(gone);

        assertThat(service.loadCart(7L)).extracting(ProductInfoDto::getId).containsExactly("1");

        List<ProductInfoDto> now = service.removeItem(7L, 3L, 8L, "M");
        assertThat(now).extracting(ProductInfoDto::getId).containsExactly("1");
        assertThat(stored[0].getCartProducts()).hasSize(1);
        verify(cartRepository, never()).delete(any(Cart.class));
    }

    /** 🔴 운영에서 겪은 것 — 옛 스키마의 줄(product_id 비어 있음) 하나 때문에 담기가 전부 400 이었다. */
    @Test
    @DisplayName("🔴 상품 id 없는 옛 줄이 있어도 담기가 되고, 그 줄은 지워진다. 읽기·/checkout 에는 나오지 않는다")
    void legacyLineWithoutProductIdIsDropped() {
        service.putItem(7L, item("1", "M", 1));
        CartProduct legacy = CartProduct.builder().colorId(8L).size("M").qty(1)
                .price(new BigDecimal("99")).shipping(BigDecimal.ZERO).build();   // productId 없음
        stored[0].getCartProducts().add(legacy);

        // 읽기 — 숨긴다(예전에는 Long.toString(null) 에서 NPE)
        assertThat(service.loadCart(7L)).extracting(ProductInfoDto::getId).containsExactly("1");

        // 쓰기 — 지운다
        List<ProductInfoDto> now = service.putItem(7L, item("2", "", 1));
        assertThat(now).extracting(ProductInfoDto::getId).containsExactly("1", "2");
        assertThat(stored[0].getCartProducts()).extracting(CartProduct::getProductId).containsExactly(1L, 2L);
        assertThat(stored[0].getCartTotal()).isEqualByComparingTo("60.00");   // 20+10 + 30+0 — 옛 줄의 99 는 없다
    }

    @Test
    @DisplayName("수량 바꾸기가 없는 줄을 가리켜도 옛 줄은 지워진다")
    void setQtyAlsoDropsLegacyLines() {
        service.putItem(7L, item("1", "M", 1));
        stored[0].getCartProducts().add(CartProduct.builder().qty(1).price(BigDecimal.ONE).shipping(BigDecimal.ZERO).build());

        service.setItemQty(7L, 9L, 9L, "M", 2);

        assertThat(stored[0].getCartProducts()).extracting(CartProduct::getProductId).containsExactly(1L);
    }
}
