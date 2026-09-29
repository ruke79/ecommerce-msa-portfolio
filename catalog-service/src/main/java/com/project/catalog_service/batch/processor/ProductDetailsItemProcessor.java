package com.project.catalog_service.batch.processor;

import com.project.catalog_service.dto.data.ProductDetailsCsvDto;
import com.project.catalog_service.model.Product;
import com.project.catalog_service.model.ProductDetails;
import com.project.catalog_service.repository.jpa.data.ProductRepository;

import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ProductDetailsItemProcessor implements ItemProcessor<ProductDetailsCsvDto, ProductDetails> {

    private final ProductRepository productRepository;

    @Override
    public ProductDetails process(ProductDetailsCsvDto item) throws Exception {
        Product product = productRepository.findById(item.getProduct_id())
            .orElseThrow(() -> new IllegalArgumentException("Invalid product_id: " + item.getProduct_id()));
        
        return ProductDetails.builder()
            //.pdetailId(item.getPdetail_id())
            .name(item.getName())
            .value(item.getValue())
            .product(product)
            .version(0L) // 초기 버전 설정
            .build();
    }
}