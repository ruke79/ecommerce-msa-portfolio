package com.project.catalog_service.repository.elasticsearch;

import com.project.catalog_service.document.ProductQADocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

import java.util.List;

public interface ProductQADocumentRepository extends ElasticsearchRepository<ProductQADocument, String> {
    List<ProductQADocument> findByProductId(String productId);
}