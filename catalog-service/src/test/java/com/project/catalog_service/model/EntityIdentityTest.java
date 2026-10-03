package com.project.catalog_service.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 🔴 다른 행은 다른 엔티티다 — 예전 BaseEntity {@code @Data} 는 equals 를 버전 · 시각만으로 만들어,
 * 같은 순간에 만든 상세 둘(한 SQL 로 적재한 시드 · 반복문에서 연달아 만든 새 줄)을 HashSet 이 하나로 합쳤다.
 */
@DisplayName("엔티티 동일성 — 감사 필드로 비교하지 않는다")
class EntityIdentityTest {

    @Test
    @DisplayName("🔴 생성 시각 · 버전이 같은 상세 둘이 Set 에 둘 다 남는다 · 종류가 다른 엔티티는 같지 않다")
    void sameAuditFieldsAreStillDifferentRows() {
        LocalDateTime t = LocalDateTime.of(2026, 10, 3, 12, 0);
        ProductDetails a = ProductDetails.builder().name("소재").value("면").build();
        ProductDetails b = ProductDetails.builder().name("원산지").value("한국").build();
        a.setCreatedAt(t);
        b.setCreatedAt(t);

        Set<ProductDetails> set = new HashSet<>();
        set.add(a);
        set.add(b);

        assertThat(set).hasSize(2);
        Category c = Category.builder().categoryName("x").slug("x").build();
        c.setCreatedAt(t);
        assertThat((Object) a).isNotEqualTo(c);
    }

    @Test
    @DisplayName("🔴 손으로 쓴 생성자도 컬렉션 기본값을 갖는다 — new Category / new Subcategory")
    void handWrittenConstructorsKeepDefaults() {
        Category category = new Category("의류", "clothes");
        assertThat(category.getSubCategories()).isNotNull();
        assertThat(new Subcategory("상의", "tops", category).getProducts()).isNotNull();
    }
}
