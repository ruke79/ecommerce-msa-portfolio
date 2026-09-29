package com.project.catalog_service.batch.processor;

import com.project.catalog_service.dto.data.ProductSizeCsvDto;
import com.project.catalog_service.model.ProductSku;
import com.project.catalog_service.repository.jpa.data.ProductSkuRepository;
import com.project.catalog_service.model.ProductSize;

import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

@Component
@RequiredArgsConstructor
public class ProductSizeItemProcessor implements ItemProcessor<ProductSizeCsvDto, ProductSize> {

    private final ProductSkuRepository productSkuRepository;

    @Override
    public ProductSize process(ProductSizeCsvDto item) throws Exception {
        ProductSku sku = productSkuRepository.findById(item.getSkuproduct_id())
            .orElseThrow(() -> new IllegalArgumentException("Invalid skuproduct_id: " + item.getSkuproduct_id()));
        
        return ProductSize.builder()
            //.sizeId(item.getSize_id())
            .size(item.getSize())
            .quantity(item.getQuantity())
            .price(new BigDecimal(item.getPrice()))
            .sku(sku)
            .version(0L) // 초기 버전 설정
            .build();
    }
}