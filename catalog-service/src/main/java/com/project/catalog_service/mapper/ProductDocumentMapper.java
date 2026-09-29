package com.project.catalog_service.mapper;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import com.project.catalog_service.document.ProductDocument;
import com.project.catalog_service.model.Product;

/**
 * Product → ProductDocument — 배치 색인과 실시간 단건 색인이 **같은 매핑**을 쓴다.
 *
 * <p>예전에는 이 매핑이 배치 잡의 람다 안에만 있었다. 실시간 색인을 따로 쓰면 두 매핑이
 * 반드시 어긋난다 — 배치로 색인된 상품과 등록 직후 색인된 상품이 다른 모양이 되고,
 * 그 차이는 검색 결과가 이상해질 때까지 아무도 모른다.
 *
 * <p>null 방어가 배치 람다보다 강하다: shipping·category·컬렉션이 비어 있는 상품이
 * 하나만 있어도 배치 전체가 NPE 로 죽는 형태였다(같은 무방비 역참조로 목록 API 가
 * 500 났던 이력 — docs/14 B-15).
 */
public final class ProductDocumentMapper {

    private ProductDocumentMapper() {
    }

    public static ProductDocument toDocument(Product product) {
        List<ProductDocument.ProductSubcategoryDocument> subcategoryDocuments =
                product.getSubcategories() == null ? Collections.emptyList()
                        : product.getSubcategories().stream()
                                .filter(sub -> sub.getSubcategory() != null)
                                .map(sub -> ProductDocument.ProductSubcategoryDocument.builder()
                                        .id(sub.getSubcategory().getSubcategoryId().toString())
                                        .name(sub.getSubcategory().getSubcategoryName())
                                        .build())
                                .collect(Collectors.toList());

        List<ProductDocument.ProductSkuDocument> skuDocuments =
                product.getSkus() == null ? Collections.emptyList()
                        : product.getSkus().stream()
                                .map(sku -> ProductDocument.ProductSkuDocument.builder()
                                        .sku(sku.getSku())
                                        .color(sku.getColor() != null ? sku.getColor().getColor() : null)
                                        .price(sku.getSizes() != null && !sku.getSizes().isEmpty()
                                                ? sku.getSizes().get(0).getPrice().floatValue()
                                                : 0.0f)
                                        .discount(sku.getDiscount())
                                        .build())
                                .collect(Collectors.toList());

        return ProductDocument.builder()
                .productId(product.getProductId().toString())
                .name(product.getName())
                .description(product.getDescription())
                .brand(product.getBrand())
                .category(product.getCategory() != null ? product.getCategory().getCategoryName() : null)
                .subcategories(subcategoryDocuments)
                .skus(skuDocuments)
                .rating(product.getRating())
                .shipping(product.getShipping() != null ? product.getShipping().floatValue() : 0.0f)
                .createdAt(product.getCreatedAt())
                .build();
    }
}
