package com.project.catalog_service.mapper;

import com.project.catalog_service.model.ProductSubcategory;
import com.project.catalog_service.model.Subcategory;
import com.project.common.dto.SubCategoryDto;
import com.project.common.dto.CategoryDto;

public class ProductSubcategoryMapper {
    public static SubCategoryDto toDto(ProductSubcategory productSubcategory) {
        if (productSubcategory == null || productSubcategory.getSubcategory() == null) {
            return null;
        }
        return SubCategoryDto.builder()
                .id(Long.toString(productSubcategory.getSubcategory().getSubcategoryId()))
                .parent(CategoryMapper.toDto(productSubcategory.getSubcategory().getCategory()))
                .name(productSubcategory.getSubcategory().getSubcategoryName())
                .slug(productSubcategory.getSubcategory().getSlug())
                .build();
    }

    // Dto에서 Entity로 변환하는 메서드 추가
    public static ProductSubcategory toEntity(SubCategoryDto dto) {
        if (dto == null) {
            return null;
        }
        Subcategory subcategory = Subcategory.builder()
                .subcategoryId(dto.getId() != null ? Long.parseLong(dto.getId()) : null)
                .subcategoryName(dto.getName())
                .slug(dto.getSlug())
                .category(CategoryMapper.toEntity(dto.getParent()))
                .build();

        return ProductSubcategory.builder()
                .subcategory(subcategory)
                .build();
    }
}