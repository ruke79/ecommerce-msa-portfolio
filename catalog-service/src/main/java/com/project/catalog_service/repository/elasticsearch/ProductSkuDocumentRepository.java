package com.project.catalog_service.repository.elasticsearch;

import com.project.catalog_service.document.ProductSkuDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

import java.util.List;

public interface ProductSkuDocumentRepository extends ElasticsearchRepository<ProductSkuDocument, String> {
    List<ProductSkuDocument> findByProductId(String productId);
    ProductSkuDocument findBySku(String sku);
}