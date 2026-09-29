package com.project.catalog_service.repository.elasticsearch;

import com.project.catalog_service.document.CategoryDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

import java.util.List;

public interface CategoryDocumentRepository extends ElasticsearchRepository<CategoryDocument, String> {
    List<CategoryDocument> findByCategoryName(String categoryName);
    List<CategoryDocument> findBySlug(String slug);
}