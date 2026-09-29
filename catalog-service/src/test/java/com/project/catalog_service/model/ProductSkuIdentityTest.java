package com.project.catalog_service.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.persistence.OrderBy;

/**
 * SKU·사이즈는 <b>인덱스</b>로 주고받는다(?style=1&amp;size=0, 장바구니의 style) — 순서와 같음이 흔들리면 같은 인덱스가 경로마다
 * 다른 행을 가리킨다.
 */
@DisplayName("ProductSku — 같음과 순서")
class ProductSkuIdentityTest {

    private static ProductSku sku(Long id) {
        ProductSku s = ProductSku.builder().sku("FOO-" + id).build();
        s.setSkuproductId(id);
        return s;
    }

    @Test
    @DisplayName("🔴 감사 필드가 같아도 id 가 다르면 다른 SKU 다 — Set 에서 합쳐지지 않는다")
    void distinctIdsAreDistinct() {
        LocalDateTime t = LocalDateTime.of(2025, 8, 3, 0, 0);
        ProductSku a = sku(1L);
        ProductSku b = sku(2L);
        a.setCreatedAt(t);
        b.setCreatedAt(t);
        a.setVersion(0L);
        b.setVersion(0L);

        assertThat(a).isNotEqualTo(b);
        assertThat(new HashSet<>(Set.of(a, b))).hasSize(2);
    }

    @Test
    @DisplayName("재고가 바뀌어 version·updatedAt 이 달라져도 같은 SKU 이고 hashCode 가 그대로다")
    void stableAcrossAuditChanges() {
        ProductSku a = sku(1L);
        Set<ProductSku> set = new HashSet<>(Set.of(a));
        int before = a.hashCode();

        a.setVersion(7L);
        a.setUpdatedAt(LocalDateTime.now());

        assertThat(a.hashCode()).isEqualTo(before);
        assertThat(set).contains(a);
        assertThat(a).isEqualTo(sku(1L));
    }

    @Test
    @DisplayName("저장 전(id 없음)에는 자기 자신하고만 같다")
    void transientOnlyEqualsItself() {
        ProductSku a = sku(null);
        ProductSku b = sku(null);
        assertThat(a).isEqualTo(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("상품의 SKU·SKU 의 사이즈에 순서가 정해져 있다(@OrderBy)")
    void collectionsAreOrdered() throws NoSuchFieldException {
        Field skus = Product.class.getDeclaredField("skus");
        Field sizes = ProductSku.class.getDeclaredField("sizes");
        assertThat(skus.getAnnotation(OrderBy.class)).isNotNull();
        assertThat(skus.getAnnotation(OrderBy.class).value()).isEqualTo("skuproductId ASC");
        assertThat(sizes.getAnnotation(OrderBy.class)).isNotNull();
        assertThat(sizes.getAnnotation(OrderBy.class).value()).isEqualTo("sizeId ASC");
    }
}
