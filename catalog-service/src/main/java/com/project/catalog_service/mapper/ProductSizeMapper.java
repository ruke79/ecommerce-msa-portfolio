package com.project.catalog_service.mapper;

import com.project.catalog_service.model.ProductSize;
import com.project.common.dto.ProductSizeDto;

public class ProductSizeMapper {
    public static ProductSizeDto toDto(ProductSize size) {
        if (size == null) {
            return null;
        }
        return ProductSizeDto.builder()
                .id(Long.toString(size.getSizeId()))
                .size(size.getSize())
                .skuCode(size.getSkuCode())
                .quantity(size.getQuantity())
                .price(size.getPrice())
                .build();
    }

    // Dto에서 Entity로 변환하는 메서드 추가
    public static ProductSize toEntity(ProductSizeDto dto) {
        if (dto == null) {
            return null;
        }
        return ProductSize.builder()
                .sizeId(dto.getId() != null ? Long.parseLong(dto.getId()) : null)
                .size(dto.getSize())
                .quantity(dto.getQuantity())
                .price(dto.getPrice())
                .build();
    }
}