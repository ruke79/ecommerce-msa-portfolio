package com.project.userservice.repository.jpa;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

import com.project.userservice.model.Review;

import jakarta.transaction.Transactional;
import java.util.Optional;

@Repository
public interface ReviewRepository extends JpaRepository<Review, Long> {

    // --- 비관적 잠금 적용 ---
    // 리뷰를 조회하여 수정 또는 삭제할 때 락을 걸어 다른 트랜잭션을 차단합니다.
    //@Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Review> findById(Long id);
    
    /**
     * 한 상품의 리뷰 — 작성자 · 스타일을 함께 읽는다.
     *
     * <p>🐛 예전: 파생 쿼리라 리뷰를 읽은 뒤 리뷰마다 LAZY 인 {@code style}(OneToOne)과 {@code reviewedBy} 를 따로 불렀다 — 리뷰 300건짜리
     * 상품 상세 한 번에 쿼리 약 600개. 등록 · 삭제의 "내 리뷰 찾기" 도 작성자 프록시를 전부 깨웠다.
     */
    // [JA] 一商品のレビュー — 作成者 · スタイルを一緒に読む。以前はレビューごとに LAZY を別々に読んでいた(300 件で約 600 クエリ)。
    @Query("select r from Review r join fetch r.reviewedBy join fetch r.style where r.productId = :productId")
    List<Review> findByProductId(@Param("productId") Long productId);

    @Modifying
    @Query("delete from Review where reviewId = :id")
    void deleteById(@Param("id") Long id);

    /** 상품별 평균 평점 — 평점 대사(ReviewRatingReconciler)가 매일 다시 보낸다. 행: [productId(Long), avg(Double)]. */
    @Query("select r.productId, avg(r.rating) from Review r where r.productId is not null group by r.productId")
    List<Object[]> averageRatingByProduct();
}
