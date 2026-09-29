package com.project.catalog_service.batch.processor;

import com.project.catalog_service.dto.data.ProductCsvDto;
import com.project.catalog_service.model.Category;
import com.project.catalog_service.model.Product;
import com.project.catalog_service.repository.jpa.data.CategoryRepository;

import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

@Component
@RequiredArgsConstructor
public class ProductItemProcessor implements ItemProcessor<ProductCsvDto, Product> {

    private final CategoryRepository categoryRepository;

    @Override
    public Product process(ProductCsvDto item) throws Exception {
        Category category = categoryRepository.findById(item.getCategory_id())
            .orElseThrow(() -> new IllegalArgumentException("Invalid category_id: " + item.getCategory_id()));
        
        return Product.builder()
            //.productId(item.getProduct_id())
            .name(item.getName())
            .description(item.getDescription())
            .brand(item.getBrand())
            .slug(item.getSlug())
            .category(category)
            .refundPolicy(item.getRefund_policy())
            .rating(item.getRating())
            .shipping(new BigDecimal(item.getShipping()))
            .version(0L) // 초기 버전 설정
            .build();
    }
}
