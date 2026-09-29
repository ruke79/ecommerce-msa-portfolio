package com.project.catalog_service.batch.processor;

import com.project.catalog_service.dto.data.ProductQACsvDto;
import com.project.catalog_service.model.Product;
import com.project.catalog_service.model.ProductQA;
import com.project.catalog_service.repository.jpa.data.ProductRepository;

import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ProductQAItemProcessor implements ItemProcessor<ProductQACsvDto, ProductQA> {
    
    private final ProductRepository productRepository;
    
    @Override
    public ProductQA process(ProductQACsvDto item) throws Exception {
        Product product = productRepository.findById(item.getProduct_id())
            .orElseThrow(() -> new IllegalArgumentException("Invalid product_id: " + item.getProduct_id()));
        
        return ProductQA.builder()
            //.qaId(item.getQa_id())
            .question(item.getQuestion())
            .answer(item.getAnswer())
            .product(product)
            .version(0L) // 초기 버전 설정
            .build();
    }
}
