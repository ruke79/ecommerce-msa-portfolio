package com.project.catalog_service.repository.elasticsearch;

import com.project.catalog_service.document.ProductDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

import java.util.List;

public interface ProductDocumentRepository extends ElasticsearchRepository<ProductDocument, String> {
    List<ProductDocument> findByBrand(String brand);
    List<ProductDocument> findByCategory(String category);
}