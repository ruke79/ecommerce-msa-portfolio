package com.project.catalog_service.batch.processor;

import com.project.catalog_service.dto.data.CategoryCsvDto;
import com.project.catalog_service.model.Category;
import com.project.catalog_service.repository.jpa.data.CategoryRepository;

import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

@Component
public class CategoryItemProcessor implements ItemProcessor<CategoryCsvDto, Category> {
    
    // private final CategoryRepository categoryRepository;

    // public CategoryItemProcessor(CategoryRepository categoryRepository) {
    //     this.categoryRepository = categoryRepository;
    // }

    @Override
    public Category process(CategoryCsvDto item) throws Exception {
        // // 데이터베이스에서 해당 ID의 Category가 이미 존재하는지 확인
        // return categoryRepository.findById(item.getCategory_id())
        //     .map(existingCategory -> {
        //         // 이미 존재하면 필드만 업데이트 (OptimisticLockException 회피)
        //         existingCategory.setCategoryName(item.getCategory_name());
        //         existingCategory.setSlug(item.getSlug());
        //         return existingCategory;
        //     })
        //     .orElseGet(() -> {
        //         // 존재하지 않으면 새로운 Category 엔티티 생성
        //         return Category.builder()
        //                 .categoryId(item.getCategory_id())
        //                 .categoryName(item.getCategory_name())
        //                 .slug(item.getSlug())
        //                 .version(0L) // 초기 버전 설정
        //                 .build();
        //     });
                return Category.builder()
                        //.categoryId(item.getCategory_id())
                        .categoryName(item.getCategory_name())
                        .slug(item.getSlug())
                        .version(0L) // 초기 버전 설정
                        .build();
    }
}
