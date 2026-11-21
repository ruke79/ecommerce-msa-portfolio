package com.project.catalog_service.repository.jpa.data;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.project.catalog_service.model.Category;
import com.project.catalog_service.model.Subcategory;
import java.util.List;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import java.util.Optional;

@Repository
public interface CategoryRepository extends JpaRepository<Category, Long> {

    // --- 비관적 잠금 적용 ---
    // 카테고리 정보가 수정되는 동안 다른 트랜잭션이 접근하지 못하도록 락을 겁니다.
    //@Lock(LockModeType.PESSIMISTIC_WRITE)
    // [JA] --- 悲観ロックの適用 ---
    //      カテゴリ情報が更新されている間、他のトランザクションがアクセスできないようロックをかける。
    Optional<Category> findById(Long id);

    // --- 낙관적 잠금 적용 ---
    //@Lock(LockModeType.OPTIMISTIC)
    // [JA] --- 楽観ロックの適用 ---
    public Optional<Category> findByCategoryName(String categoryName);

    // 기존 메서드들은 유지
    // [JA] 既存のメソッドはそのまま維持
    @Query("select categoryName from Category")
    public List<String> findAllCategoryNames();

    /** 모든 카테고리 id — 필터 메뉴 미리 채우기(FacetWarmer). [JA] 全カテゴリの id。 */
    @Query("select categoryId from Category order by categoryId")
    public List<Long> findAllCategoryIds();

    @Query("select categoryId from Category where categoryName = :name")
    public Long findIdByCategoryName(@Param("name") String categoryName);

    @Query(value ="SELECT s FROM Category c INNER JOIN Subcategory s on c.categoryId = s.category.categoryId WHERE (:categoryId is null or c.categoryId = :categoryId)")    
    public List<Subcategory> findSubCategoriesByCategoryId(@Param("categoryId") Long categoryId);
}