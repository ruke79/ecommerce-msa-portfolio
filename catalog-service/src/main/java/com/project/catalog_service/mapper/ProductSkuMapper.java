package com.project.catalog_service.mapper;

import com.project.catalog_service.model.ProductColor;
import com.project.catalog_service.model.ProductSize;
import com.project.catalog_service.model.ProductSku;
import com.project.common.dto.ProductColorDto;
import com.project.common.dto.ProductSkuDto;
import com.project.common.dto.ProductSizeDto;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class ProductSkuMapper {
    public static ProductSkuDto toDto(ProductSku sku) {
        if (sku == null) {
            return null;
        }

        List<ProductSizeDto> sizes = sku.getSizes().stream()
                .map(ProductSizeMapper::toDto)
                .collect(Collectors.toList());

        ProductColorDto color = ProductColorMapper.toDto(sku.getColor());

        return ProductSkuDto.builder()
                .id(Long.toString(sku.getSkuproductId()))
                .sku(sku.getSku())
                .images(sku.getImages())
                .discount(sku.getDiscount())
                .sold(sku.getSold())
                .sizes(sizes)
                .color(color)
                .build();
    }

    // Dto에서 Entity로 변환하는 메서드 추가
    public static ProductSku toEntity(ProductSkuDto dto) {
        if (dto == null) {
            return null;
        }

        List<ProductSize> sizes = dto.getSizes().stream()
                .map(ProductSizeMapper::toEntity)
                .collect(Collectors.toList());

        ProductColor color = ProductColorMapper.toEntity(dto.getColor());

        return ProductSku.builder()
                .skuproductId(dto.getId() != null ? Long.parseLong(dto.getId()) : null)
                .sku(dto.getSku())
                .images(dto.getImages())
                .discount(dto.getDiscount())
                .sold(dto.getSold())
                .sizes(sizes)
                .color(color)
                .build();
    }
}