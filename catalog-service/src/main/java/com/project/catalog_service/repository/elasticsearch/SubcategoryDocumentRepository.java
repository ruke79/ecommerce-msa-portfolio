package com.project.catalog_service.repository.elasticsearch;

import com.project.catalog_service.document.SubcategoryDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

import java.util.List;

public interface SubcategoryDocumentRepository extends ElasticsearchRepository<SubcategoryDocument, String> {
    List<SubcategoryDocument> findBySubcategoryName(String subcategoryName);
    List<SubcategoryDocument> findBySlug(String slug);
}