package com.project.catalog_service.mapper;

import com.project.catalog_service.model.*;
import com.project.common.dto.*;

import java.util.stream.Collectors;
import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import java.math.BigDecimal;

public class ProductMapper {

    /** null 컬렉션을 빈 리스트로 취급해 매핑한다(엔티티가 부분적으로 채워진 경우 대비). */
    private static <E, D> List<D> mapList(java.util.Collection<E> source,
                                          java.util.function.Function<E, D> mapper) {
        if (source == null) {
            return new ArrayList<>();
        }
        return source.stream().map(mapper).collect(Collectors.toList());
    }

    // ... (기존 toDto 메서드는 유지) ...
    public static ProductDto toDto(Product product) {
        // ... 기존 코드 ...
        return ProductDto.builder()
                .id(Long.toString(product.getProductId()))
                .name(product.getName())
                .description(product.getDescription())
                .brand(product.getBrand())
                .slug(product.getSlug())
                .category(CategoryMapper.toDto(product.getCategory()))
                // 연관 컬렉션은 엔티티가 부분적으로만 채워진 경우(신규 생성 직후, 분리된
                // 엔티티, 일부 조회 경로) null 일 수 있다. 무방비 .stream() 이라 그런 상품이
                // 하나만 섞여도 조회 응답 전체가 NPE 로 500 이 됐다(docs/14 B-15).
                .subCategories(mapList(product.getSubcategories(), ProductSubcategoryMapper::toDto))
                .details(mapList(product.getDetails(), ProductDetailsMapper::toDto))
                .questions(mapList(product.getQuestions(), ProductQAMapper::toDto))
                .skus(mapList(product.getSkus(), ProductSkuMapper::toDto))
                .refundPolicy(product.getRefundPolicy())
                .status(product.getStatus())
                .rating(product.getRating())
                // shipping 은 nullable 컬럼이고 createdAt 도 DB 에서 NULL 로 읽힐 수 있다.
                // 무방비 역참조라 배송비가 비어 있는 상품 하나만 있어도 목록 조회 전체가
                // NPE 로 500 이 됐다(docs/14 B-15).
                .shipping(product.getShipping() != null ? product.getShipping().toPlainString() : null)
                .createdAt(product.getCreatedAt() != null ? product.getCreatedAt().toString() : null)
                .build();
    }
    
    /**
     * ProductDto를 Product 엔티티로 변환합니다.
     * @param dto 변환할 ProductDto
     * @return 변환된 Product 엔티티
     */
    public static Product toEntity(ProductDto dto) {
        if (dto == null) {
            return null;
        }

        List<ProductSubcategory> subCategories = dto.getSubCategories().stream()
                .map(ProductSubcategoryMapper::toEntity)
                .collect(Collectors.toList());

        Set<ProductDetails> details = dto.getDetails().stream()
                .map(ProductDetailsMapper::toEntity)
                .collect(Collectors.toSet());

        Set<ProductQA> questions = dto.getQuestions().stream()
                .map(ProductQAMapper::toEntity)
                .collect(Collectors.toSet());

        Set<ProductSku> skus = dto.getSkus().stream()
                .map(ProductSkuMapper::toEntity)
                .collect(Collectors.toSet());

        return Product.builder()
                .productId(dto.getId() != null ? Long.parseLong(dto.getId()) : null)
                .name(dto.getName())
                .description(dto.getDescription())
                .brand(dto.getBrand())
                .slug(dto.getSlug())
                .category(CategoryMapper.toEntity(dto.getCategory()))
                .subcategories(subCategories)
                .details(details)
                .questions(questions)
                .skus(skus)
                .refundPolicy(dto.getRefundPolicy())
                .rating(dto.getRating())
                .shipping(new BigDecimal(dto.getShipping()))
                .build();
    }
}