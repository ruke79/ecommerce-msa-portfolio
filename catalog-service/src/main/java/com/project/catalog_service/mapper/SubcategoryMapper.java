package com.project.catalog_service.mapper;

import com.project.catalog_service.model.Subcategory;
import com.project.common.dto.SubCategoryDto;

public class SubcategoryMapper {
    public static SubCategoryDto toDto(Subcategory subcategory) {
        if (subcategory == null) {
            return null;
        }
        return SubCategoryDto.builder()
                .id(Long.toString(subcategory.getSubcategoryId()))
                .parent(CategoryMapper.toDto(subcategory.getCategory()))
                .name(subcategory.getSubcategoryName())
                .slug(subcategory.getSlug())
                .build();
    }

    public static Subcategory toEntity(SubCategoryDto dto) {
        if (dto == null) {
            return null;
        }
        return Subcategory.builder()
                .subcategoryId(dto.getId() != null ? Long.parseLong(dto.getId()) : null)
                .category(CategoryMapper.toEntity(dto.getParent()))
                .subcategoryName(dto.getName())
                .slug(dto.getSlug())
                .build();
    }
}