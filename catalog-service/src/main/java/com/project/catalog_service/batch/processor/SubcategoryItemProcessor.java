package com.project.catalog_service.batch.processor;

import com.project.catalog_service.dto.data.SubcategoryCsvDto;
import com.project.catalog_service.model.Category;
import com.project.catalog_service.model.Subcategory;
import com.project.catalog_service.repository.jpa.data.CategoryRepository;

import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class SubcategoryItemProcessor implements ItemProcessor<SubcategoryCsvDto, Subcategory> {

    private final CategoryRepository categoryRepository;

    @Override
    public Subcategory process(SubcategoryCsvDto item) throws Exception {
        Category category = categoryRepository.findById(item.getCategory_id())
            .orElseThrow(() -> new IllegalArgumentException("Invalid category_id: " + item.getCategory_id()));
        
        return Subcategory.builder()
            //.subcategoryId(item.getSubcategory_id())
            .subcategoryName(item.getSubcategory_name())
            .slug(item.getSlug())
            .category(category)
            .version(0L) // 초기 버전 설정
            .build();
    }
}