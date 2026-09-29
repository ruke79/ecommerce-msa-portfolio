package com.project.catalog_service.mapper;

import com.project.catalog_service.model.ProductDetails;
import com.project.common.dto.ProductDetailsDto;

public class ProductDetailsMapper {
    public static ProductDetailsDto toDto(ProductDetails details) {
        if (details == null) {
            return null;
        }
        return ProductDetailsDto.builder()
                .name(details.getName())
                .value(details.getValue())
                .build();
    }

    // Dto에서 Entity로 변환하는 메서드 추가
    public static ProductDetails toEntity(ProductDetailsDto dto) {
        if (dto == null) {
            return null;
        }
        return ProductDetails.builder()
                .name(dto.getName())
                .value(dto.getValue())
                .build();
    }
}