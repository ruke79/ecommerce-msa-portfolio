package com.project.cart_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.cart_service.client.CatalogServiceClient;
import com.project.cart_service.model.Cart;
import com.project.cart_service.model.CartProduct;
import com.project.cart_service.repository.jpa.CartProductRepository;
import com.project.cart_service.repository.jpa.CartRepository;
import com.project.common.message.dto.request.CartEmptyItem;
import com.project.common.message.dto.request.CartEmptyRequest;

/**
 * cart-empty 는 <b>주문한 품목만</b> 뺀다.
 *
 * <h3>🐛 회귀 대상 — 환불했더니 환불과 무관한 물건까지 사라졌다</h3>
 *
 * <p>사라진 것은 환불이 아니라 그 전 결제의 cart-empty 였다. 결제 응답 뒤 사가가 비동기로
 * "이 사용자의 장바구니를 통째로 지워라" 를 보냈고, 확정이 늦어지는 사이 손님이 새로 담은
 * 물건이 함께 지워졌다. 이제 발행자가 주문의 품목을 싣고 여기서는 그 줄만 뺀다.
 */
@DisplayName("cart-service — 주문한 품목만 빼기")
class CartEmptyLinesTest {

    private CartRepository cartRepository;
    private RedisTemplate<String, Object> redisTemplate;
    private CartService service;
    private Cart cart;

    /**
     * 🐛 이 파일의 테스트 6개는 처음부터(5eb6671) 전부 실패했다 — "Transaction synchronization is not active".
     *
     * <p>{@code cartEmpty} 는 캐시 삭제를 <b>커밋 뒤로</b> 미루려고 트랜잭션 동기화를 등록한다. 운영에서는 클래스의
     * {@code @Transactional} 프록시가 트랜잭션을 열어 두므로 문제가 없다. 그런데 테스트는 서비스를 {@code new} 로
     * 만들어 프록시를 거치지 않았고, 동기화가 없는 상태에서 등록하다 죽었다. CI 의 첫 단계가 이 때문에 빨간불이었다.
     *
     * <p>고친 것: 프록시가 여는 트랜잭션의 동기화를 테스트가 직접 연다. 코드를 "동기화가 없으면 바로 지운다" 로 바꾸는
     * 방법도 있지만, 그러면 운영에서 절대 타지 않는 분기가 생기고 — 트랜잭션 밖에서 불리는 실수를 조용히 삼킨다.
     */
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
        redisTemplate = mock(RedisTemplate.class);
        service = new CartService(cartRepository, mock(CartProductRepository.class),
                mock(CatalogServiceClient.class), new ObjectMapper(), redisTemplate);

        cart = new Cart() { };   // 생성자가 protected 다 — 익명 하위 클래스로 만든다
        cart.setUserId(7L);
        cart.setCartProducts(new ArrayList<>(List.of(
                line(10L, "M", 5L, 2, "20.00"),     // 주문한 것
                line(11L, "L", 6L, 1, "30.00"),     // 주문한 것
                line(12L, "S", 7L, 3, "10.00"))));  // 결제 뒤에 새로 담은 것 — 남아야 한다
        when(cartRepository.findByUserId(7L)).thenReturn(Optional.of(cart));
        when(cartRepository.save(any(Cart.class))).thenAnswer(i -> i.getArgument(0));
    }

    private static CartProduct line(Long productId, String size, Long colorId, int qty, String price) {
        return CartProduct.builder().productId(productId).size(size).colorId(colorId).qty(qty)
                .price(new BigDecimal(price)).shipping(BigDecimal.ZERO)._uid("u" + productId).build();
    }

    private static CartEmptyRequest ordered(CartEmptyItem... items) {
        return new CartEmptyRequest(7L, true, List.of(items));
    }

    /** 🔴 핵심 — 주문에 없던 줄은 남는다. */
    @Test
    @DisplayName("🔴 주문한 줄만 빠지고 새로 담은 줄은 남는다")
    void onlyOrderedLinesAreRemoved() {
        service.cartEmpty(ordered(new CartEmptyItem(10L, "M", 5L, 2), new CartEmptyItem(11L, "L", 6L, 1)));

        assertThat(cart.getCartProducts()).extracting(CartProduct::getProductId).containsExactly(12L);
        assertThat(cart.getCartTotal()).isEqualByComparingTo("30.00");
        verify(cartRepository, never()).delete(any(Cart.class));
    }

    @Test
    @DisplayName("주문 수량이 장바구니 수량보다 적으면 그만큼만 줄인다")
    void partialQuantityIsDecremented() {
        service.cartEmpty(ordered(new CartEmptyItem(12L, "S", 7L, 2)));

        CartProduct remaining = cart.getCartProducts().stream().filter(p -> p.getProductId() == 12L).findFirst().orElseThrow();
        assertThat(remaining.getQty()).isEqualTo(1);
        assertThat(cart.getCartProducts()).hasSize(3);
    }

    /** 같은 상품이라도 사이즈·색상이 다르면 다른 줄이다 — 담기 규칙과 같다. */
    @Test
    @DisplayName("사이즈·색상이 다르면 빼지 않는다")
    void differentSizeOrColorIsAnotherLine() {
        service.cartEmpty(ordered(new CartEmptyItem(10L, "L", 5L, 2)));

        assertThat(cart.getCartProducts()).hasSize(3);
    }

    @Test
    @DisplayName("주문한 줄이 전부면 장바구니 자체를 지운다 — 예전과 같은 끝 상태")
    void allLinesOrderedDeletesTheCart() {
        service.cartEmpty(ordered(new CartEmptyItem(10L, "M", 5L, 2), new CartEmptyItem(11L, "L", 6L, 1),
                new CartEmptyItem(12L, "S", 7L, 3)));

        verify(cartRepository).delete(cart);
    }

    /** 옛 발행자(품목 없음)는 예전처럼 전부 비운다 — 배포 순서와 무관하게 동작해야 한다. */
    @Test
    @DisplayName("품목이 없는 요청은 전부 비운다(옛 발행자 호환)")
    void legacyRequestEmptiesEverything() {
        service.cartEmpty(new CartEmptyRequest(7L, true));

        verify(cartRepository).delete(cart);
    }

    /**
     * 캐시는 <b>커밋 뒤에</b> 지운다. 커밋 전에 지우면 그 틈에 들어온 조회가 옛 장바구니를 다시 캐시에 넣는다
     * (order 의 결제 상태 캐시에서 실제로 겪은 것, docs/25 §11.12). 롤백되면 지우지 않는다.
     */
    @Test
    @DisplayName("캐시는 커밋 뒤에만 지운다 — 커밋 전·롤백이면 그대로. 키는 사용자 id")
    void cacheIsEvictedOnlyAfterCommit() {
        service.cartEmpty(ordered(new CartEmptyItem(10L, "M", 5L, 2)));
        verify(redisTemplate, never()).delete(anyString());

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(redisTemplate).delete("carts::7");
    }

    @Test
    @DisplayName("재전달로 이미 뺀 줄이 없어도 조용히 넘어간다")
    void redeliveryIsHarmless() {
        service.cartEmpty(ordered(new CartEmptyItem(99L, "M", 1L, 1)));

        assertThat(cart.getCartProducts()).hasSize(3);
        verify(cartRepository, never()).delete(any(Cart.class));
    }

    /** 🔴 at-least-once — 같은 주문의 비우기가 다시 와도 한 번만 뺀다(원장 cart-empty-<orderId>, V6). */
    @Test
    @DisplayName("🔴 같은 주문의 비우기가 다시 오면 건너뛴다 — 예전에는 주문하지 않은 수량까지 한 번 더 뺐다")
    void redeliveryOfTheSameOrderIsSkipped() {
        when(cartRepository.markProcessed("cart-empty-55")).thenReturn(1, 0);
        CartEmptyRequest request = new CartEmptyRequest(7L, true, List.of(new CartEmptyItem(12L, "S", 7L, 2)), 55L);

        service.cartEmpty(request);
        service.cartEmpty(request);

        CartProduct remaining = cart.getCartProducts().stream().filter(p -> p.getProductId() == 12L).findFirst().orElseThrow();
        assertThat(remaining.getQty()).as("3개 중 주문한 2개만 — 재전달로 1개 더 빠지지 않는다").isEqualTo(1);
    }
}
