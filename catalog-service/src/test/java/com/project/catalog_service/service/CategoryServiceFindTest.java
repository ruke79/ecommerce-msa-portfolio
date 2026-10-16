package com.project.catalog_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.catalog_service.model.Category;
import com.project.catalog_service.repository.jpa.data.CategoryRepository;
import com.project.catalog_service.repository.jpa.data.SubcategoryRepository;

/**
 * 카테고리 있음 판정. 🐛 예전에는 {@code null != Optional} 이라 늘 참 — 카테고리 만들기가 매번 "이미 있다" 로 막혔다.
 *
 * <p>[JA] カテゴリの存在判定。以前は Optional を null と比べて常に真だった。
 */
@DisplayName("CategoryService.findCategory — 있음 판정")
class CategoryServiceFindTest {

    @Test
    @DisplayName("🔴 없는 카테고리는 false, 있는 카테고리는 true")
    void absentIsFalse() {
        CategoryRepository categories = mock(CategoryRepository.class);
        when(categories.findByCategoryName("new")).thenReturn(Optional.empty());
        when(categories.findByCategoryName("old")).thenReturn(Optional.of(mock(Category.class)));
        CategoryService service = new CategoryService(categories, mock(SubcategoryRepository.class));

        assertThat(service.findCategory("new")).isFalse();
        assertThat(service.findCategory("old")).isTrue();
    }
}
