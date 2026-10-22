package com.project.catalog_service.repository.jpa.data;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.project.catalog_service.model.Product;
import com.project.catalog_service.model.ProductDetails;

import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.websocket.server.PathParam;

import java.util.List;

// PostgreSQL does not support full-text search with MATCH AGAINST.
@Repository
public interface ProductRepository extends JpaRepository<Product, Long>, QuerydslPredicateExecutor<Product> {

        // --- 낙관적 잠금 적용 예제 ---
        // 낙관적 잠금은 엔티티의 @Version 필드만 있으면 자동으로 동작하지만, 명시적으로 락 모드를 지정할 수도 있습니다.
        //@Lock(LockModeType.OPTIMISTIC)
        List<Product> findBySlug(String slug);

        public List<Product> findByName(String name);

        public Page<Product> findAllByCategory_CategoryId(Long cateogry, Pageable page);

        public Page<Product> findAllByCategory_CategoryName(String cateogry, Pageable page);

        public Page<Product> findAllByCategory_CategoryNameAndSubcategories_Subcategory_SubcategoryName(
                        String categoryName, String subcategoryName, Pageable pageable);

        public Page<Product> findAll(Pageable page);

        public Page<Product> findAllByCategory_CategoryNameOrderByProductIdDesc(String categoryName, Pageable page);

        public Page<Product> findAllByCategory_CategoryNameAndProductIdLessThanOrderByProductIdDesc(String categoryName,
                        Long id, Pageable page);

        /**
         * 카테고리 구분 없이 전체를 id 내림차순으로 — 관리자 목록의 커서 페이징용.
         *
         * <p>기존 커서 메서드는 둘 다 카테고리가 필수라 "전 상품"을 훑을 수 없었다. 그래서
         * 관리자 화면은 {@code warmUpProductCaches} 를 거쳐 <b>카탈로그 전체를 한 배열로</b>
         * 받고 있었다(그리고 그 결과가 Redis 키 하나에 통째로 들어갔다).
         *
         * <p>정렬 기준이 id 내림차순인 이유: TSID 는 시간 순으로 증가하므로 최신 등록이
         * 먼저 온다. 관리자가 방금 올린 상품을 찾는 것이 가장 흔한 용도다.
         */
        public Page<Product> findAllByOrderByProductIdDesc(Pageable page);

        public Page<Product> findAllByProductIdLessThanOrderByProductIdDesc(Long id, Pageable page);

        @Modifying
        @Query("Update Product p Set p.rating = :rating WHERE p.productId = :productId")
        int updateRating(@Param("productId") Long id, @Param("rating") float rating);

        // PostgreSQL does not support MATCH AGAINST.
        // We will use the ILIKE operator for case-insensitive pattern matching.
        /**
         * 이 slug 를 쓰는 행이 있는가 — <b>삭제(DELETED)된 상품까지.</b> 엔티티의 {@code @SQLRestriction} 을 거치지 않으려고 네이티브다.
         * 🐛 {@code findBySlug} 로 보면 삭제한 상품의 slug 가 비어 보이는데 유니크 인덱스(V6 uq_product_slug)는 그 행에도 걸려,
         * 삭제한 상품과 같은 이름으로 다시 등록하면 500 이었다.
         */
        @Query(value = "select exists(select 1 from product.product where slug = :slug)", nativeQuery = true)
        boolean slugTaken(@org.springframework.data.repository.query.Param("slug") String slug);

        /**
         * 여러 카테고리의 상품을 <b>한 번에</b>. 기획전 할인 합성이 쓴다.
         *
         * <p>카테고리마다 위의 단건 쿼리를 부르면 진행 중인 기획전의 카테고리 수만큼
         * 왕복이 되고, 그게 1분마다 반복된다.
         *
         * <p>{@code (categoryId, productId)} 를 함께 돌려주는 이유: 카테고리별로 할인율이
         * 다르므로 어느 카테고리에서 온 상품인지 알아야 한다.
         */
        @Query("select p.category.categoryId, p.productId from Product p where p.category.categoryId in :categoryIds")
        public List<Object[]> findProductIdsByCategoryIds(@Param("categoryIds") java.util.Collection<Long> categoryIds);

        /**
         * 필터 메뉴의 브랜드 — 카테고리 안에서 <b>중복 없이</b>.
         *
         * <p>🐛 2026-10-19 데이터 규모 측정(docs/83 §5): 예전 질의는 DISTINCT 없이 상품마다 한 줄이었다 — 100만 상품의 fashion 은
         * 브랜드 문자열 ~12만 개를 읽어 화면이 중복을 지웠다(브랜드는 ~400개). 그리고 같은 메뉴의 색상 · 사이즈 · 상세는 상품 id 를
         * 전부 읽어 {@code IN (:ids)} 로 다시 보냈다 — 바인드 변수 65,535 를 넘는 카테고리에서 PSQLException, 그 검색은 매번 실패했다.
         * 이제 메뉴는 id 를 앱으로 가져오지 않고 DB 안에서 조인 + DISTINCT 로 만든다(SearchFilterFacets).
         * [JA] フィルターメニューのブランド — カテゴリ内で重複なしに。以前は DISTINCT なしで商品ごとに一行だった。
         */
        @Query(value = "select distinct b.brand from product.product b where b.category_id = :categoryId", nativeQuery = true)
        public List<String> findDistinctBrandsByCategoryId(@Param("categoryId") Long categoryId);

        // PostgreSQL does not support MATCH AGAINST.
        // We will use the ILIKE operator for case-insensitive pattern matching.
        /** 카테고리를 이름으로 받은 검색(또는 카테고리 없이 — null 이면 전체)의 브랜드, 중복 없이. */
        @Query(value = "select distinct b.brand from product.category a inner join product.product b on a.category_id = b.category_id and (:categoryRegexp is null or a.category_name ILIKE :categoryRegexp)", nativeQuery = true)
        public List<String> findDistinctBrandsByCategoryName(@Param("categoryRegexp") String categoryRegexp);

        // Replaced MySQL's MATCH AGAINST with PostgreSQL's ILIKE and REGEXP_LIKE with
        // its equivalent
        // @Query(value = "select distinct a.* from product.product a " +
        // "left join product.product_details b on a.product_id = b.product_id " +
        // "where (:name is null or a.name ILIKE :name) " +
        // "AND a.product_id in :productIds " +
        // "AND (:categoryId is null or a.category_id = :categoryId) " +
        // "AND (:brand is null or a.brand ILIKE :brand) " +
        // "AND ((:style is null AND :material is null AND :gender is null) or (b.value
        // ILIKE :material or b.value ILIKE :style or b.value ILIKE :gender))"
        // +
        // "AND (:rating is null or a.rating >= :rating)", nativeQuery = true)
        // List<Product> findProductBySearchParams(@Param("name") String name,
        // @Param("categoryId") Long categoryId,
        // @Param("style") String style, @Param("brand") String brand,
        // @Param("material") String material,
        // @Param("gender") String gender, @Param("rating") float rating,
        // @Param("productIds") List<Long> productIds);
    // @Query(value = "select distinct a.* from product.product a " +
    //     "left join product.product_details b on a.product_id = b.product_id " +
    //     "where (:name is null or a.name ILIKE :name) " +
    //     "AND (:productIds IS NULL OR a.product_id IN (SELECT unnest(CAST(:productIds AS BIGINT[])))) " + // <-- unnest 사용
    //     "AND (:categoryId is null or a.category_id = :categoryId) " +
    //     "AND (:brand is null or a.brand ILIKE :brand) " +
    //     "AND ((:style is null AND :material is null AND :gender is null) or (b.value ILIKE :material or b.value ILIKE :style or b.value ILIKE :gender))" +
    //     "AND (:rating is null or a.rating >= :rating)", nativeQuery = true)
    // List<Product> findProductBySearchParams(@Param("name") String name, @Param("categoryId") Long categoryId,
    //     @Param("style") String style, @Param("brand") String brand, @Param("material") String material,
    //     @Param("gender") String gender, @Param("rating") float rating,
    //     @Param("productIds") List<Long> productIds);
    //      // Replaced REGEXP with SIMILAR TO or ~*
    //     @Query(value = "select count(distinct a.product_id) from product.product a " +
    //                     "left join product.product_details b on a.product_id = b.product_id " +
    //                     "where (:name is null or a.name ~* :name)" +
    //                     "AND (:productIds IS NULL OR a.product_id IN (SELECT unnest(CAST(:productIds AS BIGINT[])))) " + // <-- unnest 사용
    //                     "AND (:categoryId is null or a.category_id = :categoryId) " +
    //                     "AND ((:style is null AND :material is null AND :gender is null) or (b.value ~* :material or b.value ~* :style or b.value ~* :gender))"
    //                     +
    //                     "AND (:brand is null or a.brand ~* :brand)" +
    //                     "AND (:rating is null or a.rating >= :rating)", nativeQuery = true)
    //     int countProductsBySearchParams(@Param("name") String name, @Param("categoryId") Long categoryId,
    //                     @Param("style") String style, @Param("brand") String brand, @Param("material") String material,
    //                     @Param("gender") String gender, @Param("rating") float rating,
    //                     @Param("productIds") List<Long> productIds);
}

// // MySQL Version 8.0+ supports full-text search with REGEXP
// @Repository
// public interface ProductRepository extends JpaRepository<Product, Long> {

// public List<Product> findBySlug(String slug);

// public List<Product> findByName(String name);

// public Page<Product> findAllByCategory_CategoryId(Long cateogry, Pageable
// page);
// public Page<Product> findAllByCategory_CategoryName(String cateogry, Pageable
// page);
// public Page<Product> findAll(Pageable page);

// public Page<Product>
// findAllByCategory_CategoryNameOrderByProductIdDesc(String categoryName,
// Pageable page);
// public Page<Product>
// findAllByCategory_CategoryNameAndProductIdLessThanOrderByProductIdDesc(String
// categoryName, Long id, Pageable page);

// @Modifying
// @Query("Update Product p Set p.rating = :rating WHERE p.productId =
// :productId")
// int updateRating(@Param("productId") Long id, @Param("rating") float rating);

// @Query(value = "select b.product_id from category a inner join product b
// where a.category_id = b.category_id and (:categoryRegexp is null or
// match(a.category_name) against (:categoryRegexp))", nativeQuery = true)
// public List<Long> findProductIDsByCategoryName(@Param("categoryRegexp")
// String categoryRegexp);

// @Query(value = "select b.product_id from category a inner join product b
// where a.category_id = b.category_id and (:categoryId is null or a.category_id
// = :categoryId)", nativeQuery = true)
// public List<Long> findProductIDsByCategoryId(@Param("categoryId") Long
// categoryId);

// @Query(value = "select b.brand from category a inner join product b where
// a.category_id = b.category_id and (:categoryId is null or a.category_id =
// :categoryId)", nativeQuery = true)
// public List<String> findBrandsByCategoryId(@Param("categoryId") Long
// categoryId);

// @Query(value = "select b.brand from category a inner join product b where
// a.category_id = b.category_id and (:categoryRegexp is null or
// match(a.category_name) against (:categoryRegexp))", nativeQuery = true)
// public List<String> findBrandsByCategoryName(@Param("categoryRegexp") String
// categoryRegexp);

// @Query(value = "select distinct a.* from product a " +
// "left join product_details b on a.product_id = b.product_id " +
// "where (:name is null or match(a.name) against (:name)) " +
// "AND a.product_id in :productIds " +
// "AND (:categoryId is null or a.category_id = :categoryId) " +
// "AND (:brand is null or match(a.brand) against (:brand)) " +
// "AND ((:style is null AND :material is null AND :gender is null) or ( b.value
// = :material or b.value = :style or b.value = :gender) )"
// +
// "AND (:rating is null or a.rating >= :rating)", nativeQuery = true)
// List<Product> findProductBySearchParams(@Param("name") String name,
// @Param("categoryId") Long categoryId,
// @Param("style") String style, @Param("brand") String brand,
// @Param("material") String material,
// @Param("gender") String gender, @Param("rating") float rating,
// @Param("productIds") List<Long> productIds);

// @Query(value = "select count(distinct a.product_id) from product a " +
// "left join product_details b on a.product_id = b.product_id " +
// "where (:name is null or a.name REGEXP :name)" +
// "AND a.product_id in :productIds " +
// "AND (:categoryId is null or a.category_id = :categoryId) " +
// "AND ((:style is null AND :material is null AND :gender is null) or
// (REGEXP_LIKE(b.value, :material) or REGEXP_LIKE(b.value, :style) or
// REGEXP_LIKE(b.value, :gender)))"
// +
// "AND (:brand is null or a.brand REGEXP :brand)" +
// "AND (:rating is null or a.rating >= :rating)", nativeQuery = true)

// int countProductsBySearchParams(@Param("name") String name,
// @Param("categoryId") Long categoryId,
// @Param("style") String style, @Param("brand") String brand,
// @Param("material") String material,
// @Param("gender") String gender, @Param("rating") float rating,
// @Param("productIds") List<Long> productIds);

// }
