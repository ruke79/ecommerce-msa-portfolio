package com.project.catalog_service.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.catalog_service.model.Category;
import com.project.catalog_service.model.Product;
import com.project.common.dto.ProductDto;

/**
 * ProductMapper 널 안전성 회귀 테스트 (docs/14 B-15).
 *
 * <p>이 매퍼는 nullable 컬럼과 연관 컬렉션을 <b>무방비로 역참조</b>하고 있었다. 상품 한 건만
 * 조건에 걸려도 목록 조회 응답 전체가 NPE 로 500 이 됐다 — 카탈로그 서비스에서 가장 자주 호출되는
 * 경로다. 컬렉션은 부분적으로 채워진 엔티티(신규 생성 직후, 분리된 엔티티, 일부 조회 경로)에서
 * 실제로 null 이 될 수 있다.
 *
 * <p>[JA] ProductMapper の null 安全性の回帰テスト(docs/14 B-15)。
 * <p>このマッパーは nullable なカラムと関連コレクションを<b>無防備に逆参照</b>していた。商品が一件条件に引っかかるだけで一覧照会の応答全体が NPE で 500 になった
 * — カタログサービスで最も頻繁に呼ばれる経路である。コレクションは部分的に埋まったエンティティ(新規作成直後、分離されたエンティティ、一部の照会経路)で実際に null になりうる。
 */
class ProductMapperNullSafetyTest {

    private Product minimalProduct() {
        Category category = Category.builder().categoryId(1L).categoryName("fashion").slug("fashion").build();
        return Product.builder()
                .productId(1L)
                .name("테스트 상품")
                .description("설명")
                .brand("브랜드")
                .slug("test-product")
                .category(category)
                .build();
    }

    @Test
    @DisplayName("shipping 이 null 이어도 매핑이 실패하면 안 된다")
    void toDto_toleratesNullShipping() {
        // shipping 은 nullable 컬럼이다. 배송비가 비어 있는 상품 하나가 목록에 섞이면
        // getShipping().toPlainString() 이 NPE 를 내며 조회 전체를 500 으로 만들었다.
        // [JA] shipping は nullable なカラムである。送料が空の商品が一つ一覧に混じると、getShipping().toPlainString() が NPE を出して照会全体を 500 にしていた。
        Product product = minimalProduct();
        product.setSubcategories(Collections.emptyList());
        product.setDetails(Collections.emptySet());
        product.setQuestions(Collections.emptySet());
        product.setSkus(Collections.emptySet());

        ProductDto dto = ProductMapper.toDto(product);

        assertThat(dto).isNotNull();
        assertThat(dto.getShipping()).isNull();
        assertThat(dto.getName()).isEqualTo("테스트 상품");
    }

    @Test
    @DisplayName("연관 컬렉션이 전부 null 이어도 빈 리스트로 매핑되어야 한다")
    void toDto_toleratesNullCollections() {
        // 컬렉션을 하나도 세팅하지 않은 상태 — 무방비 .stream() 이면 여기서 NPE 가 났다.
        // [JA] コレクションを一つも設定していない状態 — 無防備な .stream() ならここで NPE が出ていた。
        Product product = minimalProduct();

        ProductDto dto = ProductMapper.toDto(product);

        assertThat(dto).isNotNull();
        assertThat(dto.getSubCategories()).isEmpty();
        assertThat(dto.getDetails()).isEmpty();
        assertThat(dto.getQuestions()).isEmpty();
        assertThat(dto.getSkus()).isEmpty();
    }

    @Test
    @DisplayName("createdAt 이 null 이어도 매핑이 실패하면 안 된다")
    void toDto_toleratesNullCreatedAt() {
        Product product = minimalProduct();
        product.setCreatedAt(null);

        assertThatCode(() -> ProductMapper.toDto(product)).doesNotThrowAnyException();
        assertThat(ProductMapper.toDto(product).getCreatedAt()).isNull();
    }

    @Test
    @DisplayName("값이 모두 채워져 있으면 그대로 매핑된다")
    void toDto_mapsPopulatedValues() {
        Product product = minimalProduct();
        product.setShipping(new BigDecimal("5.50"));
        product.setCreatedAt(LocalDateTime.of(2026, 7, 27, 12, 0));

        ProductDto dto = ProductMapper.toDto(product);

        assertThat(dto.getShipping()).isEqualTo("5.50");
        assertThat(dto.getCreatedAt()).isNotNull();
        assertThat(dto.getCategory().getName()).isEqualTo("fashion");
    }
}
