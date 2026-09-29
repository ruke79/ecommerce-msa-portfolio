package com.project.userservice.model;

import java.time.LocalDateTime;

import io.hypersistence.utils.hibernate.id.Tsid;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 리뷰 좋아요 한 건 = (리뷰, 사용자) 한 쌍.
 *
 * <p><b>왜 Review 의 컬렉션이 아닌가.</b> {@code Review} 에 {@code @OneToMany List<ReviewLike>}
 * 를 달면 상품 리뷰 목록을 그릴 때마다 리뷰 수만큼 추가 쿼리가 나가고(N+1), 인기 리뷰의
 * 좋아요 수천 건을 전부 메모리에 올린 뒤 {@code size()} 를 부르게 된다. 화면이 필요한 것은
 * <b>개수</b>와 <b>내가 눌렀는지</b> 두 가지뿐이라, 양쪽 다 집계 쿼리 한 번으로 얻는 편이
 * 싸다({@code ReviewLikeRepository.countByReviewIds}).
 *
 * <p><b>왜 {@link BaseEntity} 를 상속하지 않는가.</b> 좋아요는 만들거나 지울 뿐 수정되지
 * 않는다. {@code @Version}(낙관적 잠금)·{@code updatedAt}·{@code updatedBy} 는 갱신이 있어야
 * 의미가 있는 필드라 여기서는 영원히 비어 있게 된다. 누가 언제 눌렀는지는 {@code userId} 와
 * {@code createdAt} 으로 충분하다.
 *
 * <p><b>중복 방지는 유니크 제약이 한다.</b> 서비스 계층의 "있으면 삭제, 없으면 삽입" 검사는
 * 동시 요청 사이에서 갈라진다. 제약이 최종 방어선이고, 서비스는 제약 위반을 오류가 아니라
 * "다른 요청이 먼저 눌렀다"는 신호로 해석한다.
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "review_like", schema = "users",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_review_like_review_user", columnNames = { "review_id", "user_id" }),
        indexes = {
                @Index(name = "idx_review_like_review", columnList = "review_id"),
                @Index(name = "idx_review_like_user", columnList = "user_id")
        })
public class ReviewLike {

    @Id
    @Tsid
    @Column(name = "like_id")
    private Long likeId;

    /**
     * 연관 매핑(@ManyToOne) 대신 식별자만 들고 있다. 좋아요를 누르거나 취소할 때 리뷰
     * 엔티티를 적재할 이유가 없고, 집계 쿼리도 식별자만 있으면 된다.
     */
    @Column(name = "review_id", nullable = false)
    private Long reviewId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void stampCreatedAt() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
