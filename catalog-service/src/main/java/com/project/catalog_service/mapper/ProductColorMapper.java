package com.project.catalog_service.mapper;

import com.project.catalog_service.model.ProductColor;
import com.project.common.dto.ProductColorDto;

public class ProductColorMapper {
    public static ProductColorDto toDto(ProductColor color) {
        if (color == null) {
            return null;
        }
        return ProductColorDto.builder()
                .id(Long.toString(color.getColorId()))
                .color(color.getColor())
                .colorImage(color.getColorImage())
                .build();
    }
    
    // Dto에서 Entity로 변환하는 메서드 추가
    public static ProductColor toEntity(ProductColorDto dto) {
        if (dto == null) {
            return null;
        }
        return ProductColor.builder()
                .colorId(dto.getId() != null ? Long.parseLong(dto.getId()) : null)
                .color(dto.getColor())
                .colorImage(dto.getColorImage())
                .build();
    }
}