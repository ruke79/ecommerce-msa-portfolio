package com.project.catalog_service.repository.jpa.data;

import java.util.List;


import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import com.project.catalog_service.model.Subcategory;
import com.project.catalog_service.model.Category;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;

@Repository
public interface  SubcategoryRepository extends JpaRepository<Subcategory, Long>  {

    List<Subcategory> findByCategory(Category category);

    // --- 낙관적 잠금 적용 ---
    //@Lock(LockModeType.OPTIMISTIC)
    List<Subcategory> findByCategory_CategoryNameAndSubcategoryNameIn(String categoryName, List<String> subcategoryName);

    // 기존 메서드들은 유지
    List<Subcategory> findBySubcategoryIdIn(List<Long> subcategoryName);    
    Subcategory findBySubcategoryNameAndCategory_CategoryId(String name, Long parent);

    /** 하위 카테고리의 상위 카테고리 id — 검색이 "다른 상위의 하위" 조합을 DB 에 묻지 않게 한다(SearchService). */
    @Query("SELECT s.category.categoryId FROM Subcategory s WHERE s.subcategoryId = :subcategoryId")
    java.util.Optional<Long> findParentCategoryId(@org.springframework.data.repository.query.Param("subcategoryId") Long subcategoryId);

     // JPQL을 사용하여 Subcategory와 Category를 함께 조회합니다.
     // Subcategory를 조회할 때 연관된 Category 엔티티를 즉시 로딩하도록 쿼리를 수정합니다
    @Override
    @Query("SELECT s FROM Subcategory s JOIN FETCH s.category") // s.category는 Subcategory 엔티티의 필드명입니다.
    List<Subcategory> findAll();
}