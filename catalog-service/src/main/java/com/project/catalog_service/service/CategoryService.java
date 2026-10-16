package com.project.catalog_service.service;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.project.common.dto.CategoryDto;
import com.project.common.dto.SubCategoryDto;
import com.project.catalog_service.model.Category;
import com.project.catalog_service.model.Subcategory;
import com.project.catalog_service.repository.jpa.data.CategoryRepository;
import com.project.catalog_service.repository.jpa.data.SubcategoryRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;



@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;

    private final SubcategoryRepository subCategoryRepository;

    
    public boolean findSubCategory(String subCategoryName, String categoryId) {

        return null != subCategoryRepository.findBySubcategoryNameAndCategory_CategoryId(subCategoryName,
                Long.parseLong(categoryId));
    }

    public boolean findCategory(String categoryName) {
        // 🐛 예전: null != Optional — Optional 은 null 이 아니라 언제나 참이었다. 카테고리 만들기(POST /api/category/category)가
        //    매번 400 "이미 있는 카테고리" 로 막혔다. [JA] Optional を null と比べており常に真で、カテゴリ作成が毎回 400 だった。
        return categoryRepository.findByCategoryName(categoryName).isPresent();
    }

    
    public CategoryDto createCategory(String name, String slug) {

        Category category = categoryRepository.findByCategoryName(name).orElse(null);

        if (null == category) {

            category = new Category(name, slug);            
            category = categoryRepository.save(category);

        }

        return CategoryDto.builder()
                .id(Long.toString(category.getCategoryId()))
                .name(category.getCategoryName())
                .slug(category.getSlug()).build();
    }

    public SubCategoryDto createSubCategory(String subCategoryName, String categoryId, String slug) {

        Category category = categoryRepository
                .findById(Long.parseLong(categoryId))
                .orElseThrow(() -> new RuntimeException("Category not found"));

        Subcategory subcategory = new Subcategory(subCategoryName, slug, category);
        
        subCategoryRepository.save(subcategory);

        return SubCategoryDto.builder()
                .name(subCategoryName)
                .parent(CategoryDto.builder()
                        .id(categoryId)
                        .name(category.getCategoryName())
                        .slug(category.getSlug())
                        .build())
                .build();
    }

}
