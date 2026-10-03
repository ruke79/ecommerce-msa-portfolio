package com.project.catalog_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Caching;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import com.project.catalog_service.model.Product;
import com.project.catalog_service.model.ProductSku;
import com.project.common.dto.ProductDto;

/**
 * 🔴 "product" 캐시에는 ProductDto 만 — 키 식은 실제 반환값에서 풀린다.
 *
 * <p>🐛 예전: addProduct 의 {@code @CachePut(key = "#result.productId")} — 반환값 ProductSku 에 productId 가 없어 프록시에서
 * SpEL 예외(EL1008E)로 등록이 500 이 됐다. 몇 메서드는 ProductSku · ProductSize · Product 를 ProductDto 자리에 넣으려 했다.
 */
@DisplayName("상품 캐시 — 타입 · 키")
class ProductCacheKeyTest {

    private static boolean namesProduct(String[] names) {
        return Arrays.asList(names).contains("product");
    }

    @Test
    @DisplayName("🔴 \"product\" 캐시에 넣는(@CachePut) 메서드는 모두 ProductDto 를 돌려준다")
    void productCacheHoldsDtosOnly() {
        for (Method m : ProductService.class.getDeclaredMethods()) {
            CachePut put = m.getAnnotation(CachePut.class);
            if (put != null && (namesProduct(put.value()) || namesProduct(put.cacheNames()))) {
                assertThat(m.getReturnType()).as(m.getName()).isEqualTo(ProductDto.class);
            }
        }
    }

    @Test
    @DisplayName("🔴 addProduct 의 캐시 키는 반환한 SKU 에서 풀린다 — 부모 상품 id(옛 키는 EL1008E)")
    void addProductKeyResolves() throws Exception {
        Method add = Arrays.stream(ProductService.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("addProduct")).findFirst().orElseThrow();
        String key = Arrays.stream(add.getAnnotation(Caching.class).evict())
                .filter(e -> namesProduct(e.value())).map(CacheEvict::key).findFirst().orElseThrow();

        ProductSku sku = ProductSku.builder().product(Product.builder().productId(42L).build()).build();
        StandardEvaluationContext ctx = new StandardEvaluationContext();
        ctx.setVariable("result", sku);
        SpelExpressionParser parser = new SpelExpressionParser();

        assertThat(parser.parseExpression(key).getValue(ctx)).isEqualTo(42L);
        assertThatThrownBy(() -> parser.parseExpression("#result.productId").getValue(ctx))
                .hasMessageContaining("productId");
    }
}
