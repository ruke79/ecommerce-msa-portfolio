package com.project.catalog_service.repository.elasticsearch;

import com.project.catalog_service.document.ProductDetailsDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

import java.util.List;

public interface ProductDetailsDocumentRepository extends ElasticsearchRepository<ProductDetailsDocument, String> {
    List<ProductDetailsDocument> findByProductId(String productId);
    List<ProductDetailsDocument> findByName(String name);
    List<ProductDetailsDocument> findByValue(String value);
}