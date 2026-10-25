package com.project.cart_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.cart_service.client.CatalogServiceClient;
import com.project.cart_service.repository.jpa.CartProductRepository;
import com.project.cart_service.repository.jpa.CartRepository;
import com.project.common.dto.ProductColorDto;
import com.project.common.dto.ProductDto;
import com.project.common.dto.ProductInfoDto;
import com.project.common.dto.ProductSizeDto;
import com.project.common.dto.ProductSkuDto;
import com.project.common.dto.cart.request.CartRequest;

/**
 * 실제 JPA(Hibernate + H2)로 — 담기·체크아웃이 <b>DB 에 남는가</b>. 요청마다 트랜잭션을 따로 연다(운영의 요청 하나 = 트랜잭션 하나).
 *
 * <h3>🐛 회귀 대상 — "에러는 없는데 담기가 안 된다"</h3>
 *
 * <p>담기는 새 줄을 장바구니 컬렉션에 더하기만 하고 저장은 연쇄(cascade)에 맡겼다. 응답(같은 트랜잭션의 메모리 상태)에는 줄이 보이지만
 * 다음 요청이 DB 에서 읽으면 없었다 — 장바구니 화면이 서버 것으로 바꾸는 순간 사라졌다. 목(mock) 저장소 테스트로는 보이지 않는다.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:cart;MODE=PostgreSQL;INIT=CREATE SCHEMA IF NOT EXISTS cart",
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("cart-service — 담기가 DB 에 남는가(JPA)")
class CartPersistenceJpaTest {

    @Autowired CartRepository cartRepository;
    @Autowired CartProductRepository cartProductRepository;
    @Autowired PlatformTransactionManager txManager;

    private CartService service;
    private TransactionTemplate tx;
    /** catalog 를 부를 때마다 — 그때 트랜잭션이 열려 있었는가(B-87). */
    private final List<Boolean> catalogInTx = new ArrayList<>();
    private static long nextUser = 1000;
    private long userId;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        CatalogServiceClient catalog = mock(CatalogServiceClient.class);
        when(catalog.getProductsInfo(anyList())).thenAnswer(i -> {
            catalogInTx.add(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            List<Long> ids = i.getArgument(0);
            List<ProductDto> found = new ArrayList<>();
            for (Long id : ids) {
                found.add(ProductDto.builder().id(Long.toString(id)).name("p" + id).shipping("0")
                        .skus(List.of(ProductSkuDto.builder().discount(0).images(List.of("img"))
                                .color(ProductColorDto.builder().id("5").build())
                                .sizes(List.of(ProductSizeDto.builder().size("M").price(new BigDecimal("10")).build()))
                                .build()))
                        .build());
            }
            return ResponseEntity.ok(found);
        });
        when(catalog.getCartProductsInfo(anyList())).thenAnswer(i -> {
            catalogInTx.add(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            Map<String, ProductInfoDto> found = new HashMap<>();
            for (String k : (List<String>) i.getArgument(0)) {
                found.put(k, ProductInfoDto.builder().id(k.split(":")[0]).style(0).size("M")
                        .color(ProductColorDto.builder().id("5").build()).build());
            }
            return ResponseEntity.ok(found);
        });
        when(catalog.getColorsInfo(anyList())).thenAnswer(i -> {
            catalogInTx.add(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            return ResponseEntity.ok(List.of());
        });

        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));

        service = new CartService(cartRepository, cartProductRepository, catalog, new ObjectMapper(), redis);
        tx = new TransactionTemplate(txManager);
        userId = nextUser++;
    }

    private static ProductInfoDto item(long productId, int qty) {
        return ProductInfoDto.builder().id(Long.toString(productId)).style(0).size("M").qty(qty)._uid("u" + productId).build();
    }

    private List<ProductInfoDto> inNewRequest(java.util.function.Supplier<List<ProductInfoDto>> call) {
        return tx.execute(s -> call.get());
    }

    @Test
    @DisplayName("🔴 장바구니가 없을 때 담으면 — 다음 요청에서도 그 줄이 있다")
    void firstPutIsPersisted() {
        inNewRequest(() -> service.putItem(userId, item(1, 2)));

        List<ProductInfoDto> later = inNewRequest(() -> service.loadCart(userId));
        assertThat(later).extracting(ProductInfoDto::getId).containsExactly("1");
        assertThat(later.get(0).getQty()).isEqualTo(2);
    }

    @Test
    @DisplayName("🔴 장바구니가 있을 때 새 상품을 담으면 — 다음 요청에서도 두 줄")
    void secondPutIsPersisted() {
        inNewRequest(() -> service.putItem(userId, item(1, 1)));
        inNewRequest(() -> service.putItem(userId, item(2, 1)));

        assertThat(inNewRequest(() -> service.loadCart(userId))).extracting(ProductInfoDto::getId).containsExactly("1", "2");
    }

    @Test
    @DisplayName("수량 바꾸기·빼기도 DB 에 남는다. 마지막 줄을 빼면 장바구니가 없다")
    void qtyAndRemoveArePersisted() {
        inNewRequest(() -> service.putItem(userId, item(1, 1)));
        inNewRequest(() -> service.putItem(userId, item(2, 1)));

        inNewRequest(() -> service.setItemQty(userId, 1L, 5L, "M", 4));
        inNewRequest(() -> service.removeItem(userId, 2L, 5L, "M"));
        List<ProductInfoDto> later = inNewRequest(() -> service.loadCart(userId));
        assertThat(later).extracting(ProductInfoDto::getId).containsExactly("1");
        assertThat(later.get(0).getQty()).isEqualTo(4);

        inNewRequest(() -> service.removeItem(userId, 1L, 5L, "M"));
        assertThat(inNewRequest(() -> service.loadCart(userId))).as("장바구니가 없으면 빈 목록 — null 이면 본문이 비어 화면이 깨진다").isEmpty();
    }

    @Test
    @DisplayName("체크아웃은 줄을 지우지 않고 고른 줄만 표시한다 — 다음 요청에서도")
    void checkoutSelectionIsPersisted() {
        inNewRequest(() -> service.putItem(userId, item(1, 1)));
        inNewRequest(() -> service.putItem(userId, item(2, 1)));

        CartRequest request = mock(CartRequest.class);
        when(request.getProducts()).thenReturn(List.of(item(2, 1)));
        tx.executeWithoutResult(s -> service.saveCart(request, userId));

        tx.executeWithoutResult(s -> {
            var cart = cartRepository.findByUserId(userId).orElseThrow();
            assertThat(cart.getCartProducts()).hasSize(2);
            assertThat(cart.getCartProducts()).filteredOn(p -> p.isSelected()).extracting(p -> p.getProductId()).containsExactly(2L);
            assertThat(cart.getCartTotal()).isEqualByComparingTo("10");
        });
    }

    /** 🐛 화면이 만든 _uid 가 겹쳤다(이름이 같은 다른 상품) — 하나를 고르면 전부 선택됐다. 식별자는 줄 id 다. */
    @Test
    @DisplayName("🔴 화면이 준 _uid 가 겹쳐도 줄마다 다른 식별자(줄 id)로 돌려주고, 하나만 빼면 나머지는 남는다")
    void lineIdentifiersAreUnique() {
        inNewRequest(() -> service.putItem(userId, ProductInfoDto.builder().id("1").style(0).size("M").qty(1)._uid("Digital Camera_0_0").build()));
        inNewRequest(() -> service.putItem(userId, ProductInfoDto.builder().id("2").style(0).size("M").qty(1)._uid("Digital Camera_0_0").build()));
        inNewRequest(() -> service.putItem(userId, ProductInfoDto.builder().id("3").style(0).size("M").qty(1)._uid("Digital Camera_0_0").build()));

        List<ProductInfoDto> now = inNewRequest(() -> service.loadCart(userId));
        assertThat(now).extracting(ProductInfoDto::get_uid).doesNotHaveDuplicates().doesNotContain("Digital Camera_0_0");

        inNewRequest(() -> service.removeItem(userId, 2L, 5L, "M"));
        assertThat(inNewRequest(() -> service.loadCart(userId))).extracting(ProductInfoDto::getId).containsExactly("1", "3");
    }

    /**
     * 🔴 B-87: 운영처럼 — 요청 트랜잭션 없이(open-in-view 끔) 서비스가 스스로 짧은 트랜잭션을 연다. catalog 는 그 밖에서만 부르고,
     * 트랜잭션 밖에서 읽은 장바구니도 줄을 쓸 수 있다(findByUserId 의 @EntityGraph).
     *
     * <p>🐛 예전에는 담기 · 수량 · 저장 · 조회가 트랜잭션 안에서 catalog 를 불렀고, 커밋 뒤 캐시 갱신도 커넥션을 쥔 채 catalog 를 불렀다.
     */
    @Test
    @DisplayName("🔴 B-87: 담기 · 수량 · 저장 · 조회 · 체크아웃의 catalog 호출은 트랜잭션 밖 — 그래도 DB 에 남고 줄을 읽는다")
    void catalogIsCalledOutsideTransactions() {
        service.setTransactionManager(txManager);
            service.putItem(userId, item(1, 2));
            service.putItem(userId, item(2, 1));
            service.setItemQty(userId, 2L, 5L, "M", 3);
            CartRequest request = mock(CartRequest.class);
            when(request.getProducts()).thenReturn(List.of(item(1, 2)));
            service.saveCart(request, userId);
            List<ProductInfoDto> now = service.loadCart(userId);
            service.getCart(userId);
            service.getCartForCheckout(userId);

            assertThat(now).extracting(ProductInfoDto::getId).containsExactly("1", "2");
            assertThat(now.get(1).getQty()).isEqualTo(3);
            assertThat(catalogInTx).isNotEmpty().as("catalog 를 부르는 동안 트랜잭션이 없어야 한다").containsOnly(false);
    }
}

