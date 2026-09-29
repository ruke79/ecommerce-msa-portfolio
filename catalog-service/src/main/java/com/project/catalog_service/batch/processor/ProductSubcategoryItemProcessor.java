package com.project.catalog_service.batch.processor;

import com.project.catalog_service.dto.data.ProductSubcategoryCsvDto;
import com.project.catalog_service.model.Product;
import com.project.catalog_service.model.ProductSubcategory;
import com.project.catalog_service.model.Subcategory;
import com.project.catalog_service.repository.jpa.data.ProductRepository;
import com.project.catalog_service.repository.jpa.data.SubcategoryRepository;

import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ProductSubcategoryItemProcessor implements ItemProcessor<ProductSubcategoryCsvDto, ProductSubcategory> {
    
    private final ProductRepository productRepository;
    private final SubcategoryRepository subcategoryRepository;
    
    @Override
    public ProductSubcategory process(ProductSubcategoryCsvDto item) throws Exception {
        Product product = productRepository.findById(item.getProduct_id())
            .orElseThrow(() -> new IllegalArgumentException("Invalid product_id: " + item.getProduct_id()));
        Subcategory subcategory = subcategoryRepository.findById(item.getSubcategory_id())
            .orElseThrow(() -> new IllegalArgumentException("Invalid subcategory_id: " + item.getSubcategory_id()));
        
        return ProductSubcategory.builder()
           //.id(item.getId())
            .product(product)
            .subcategory(subcategory)
            .version(0L) // 초기 버전 설정
            .build();
    }
}