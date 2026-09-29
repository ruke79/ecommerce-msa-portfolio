package com.project.userservice.repository.jpa;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.project.userservice.model.ReviewLike;

@Repository
public interface ReviewLikeRepository extends JpaRepository<ReviewLike, Long> {

    long countByReviewId(Long reviewId);

    boolean existsByReviewIdAndUserId(Long reviewId, Long userId);

    /**
     * 좋아요 취소. <b>지운 행 수를 돌려주는 것이 핵심</b>이다 — 토글은 "조회 후 분기" 없이
     * 이 반환값만으로 방향을 정한다(0 이면 아직 안 눌렀다는 뜻이므로 삽입). 조회와 삭제
     * 사이가 벌어질 여지를 없앤다.
     *
     * <p>{@code @Modifying} 쿼리는 스스로 트랜잭션을 열지 않으므로 여기서 선언한다.
     * 서비스가 의도적으로 클래스 수준 {@code @Transactional} 을 쓰지 않기 때문이다
     * ({@code ReviewLikeService} 주석 참조).
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from ReviewLike l where l.reviewId = :reviewId and l.userId = :userId")
    int deleteByReviewIdAndUserId(@Param("reviewId") Long reviewId, @Param("userId") Long userId);

    /**
     * 리뷰 목록의 좋아요 수를 <b>한 번에</b> 센다.
     *
     * <p>리뷰마다 {@code countByReviewId} 를 부르면 리뷰 20건짜리 상품 페이지에서 쿼리가
     * 20번 나간다. 좋아요가 하나도 없는 리뷰는 결과에 나타나지 않으므로, 호출부는 없는
     * 키를 0 으로 읽어야 한다.
     *
     * @return {@code [reviewId, count]} 쌍의 목록
     */
    @Query("select l.reviewId, count(l) from ReviewLike l "
            + "where l.reviewId in :reviewIds group by l.reviewId")
    List<Object[]> countByReviewIds(@Param("reviewIds") Collection<Long> reviewIds);

    /**
     * 회원 탈퇴 최종 처리용. 좋아요는 "누가 눌렀는지"가 곧 데이터라 탈퇴하면 지운다.
     * 리뷰 본문과 달리 익명화할 내용 자체가 없다.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("delete from ReviewLike l where l.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);

    /** 이 사용자가 누른 리뷰 id 만. "내가 눌렀는지"를 리뷰 수와 무관하게 쿼리 한 번으로 얻는다. */
    @Query("select l.reviewId from ReviewLike l "
            + "where l.userId = :userId and l.reviewId in :reviewIds")
    List<Long> findLikedReviewIds(@Param("userId") Long userId,
                                  @Param("reviewIds") Collection<Long> reviewIds);
}
