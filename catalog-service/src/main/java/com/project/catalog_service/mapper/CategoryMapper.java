package com.project.catalog_service.mapper;

import com.project.catalog_service.model.Category;
import com.project.common.dto.CategoryDto;

public class CategoryMapper {
    public static CategoryDto toDto(Category category) {
        // ... 기존 코드 ...
        if (category == null) {
            return null;
        }
        return CategoryDto.builder()
                .id(Long.toString(category.getCategoryId()))
                .name(category.getCategoryName())
                .slug(category.getSlug())
                .build();
    }

    public static Category toEntity(CategoryDto dto) {
        if (dto == null) {
            return null;
        }
        return Category.builder()
                .categoryId(dto.getId() != null ? Long.parseLong(dto.getId()) : null)
                .categoryName(dto.getName())
                .slug(dto.getSlug())
                .build();
    }
}