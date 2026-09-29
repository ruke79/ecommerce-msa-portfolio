package com.project.userservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import com.project.userservice.dto.ReviewLikeDto;
import com.project.userservice.model.ReviewLike;
import com.project.userservice.model.User;
import com.project.userservice.repository.jpa.ReviewLikeRepository;
import com.project.userservice.repository.jpa.ReviewRepository;
import com.project.userservice.repository.jpa.UserRepository;
import com.project.userservice.service.ReviewLikeService.LikeSnapshot;

/**
 * 좋아요 토글의 <b>판단</b>을 고정한다.
 *
 * <p>여기서 검증하지 <b>않는</b> 것: 유니크 제약 자체(실제 DB 가 필요하다). 대신 그 제약이
 * 터졌을 때 서비스가 어떻게 해석하는지를 검증한다 — 동시 클릭을 오류로 돌려주면 사용자
 * 화면에서는 "눌렀는데 실패했다고 나오고, 새로고침하면 눌려 있다"가 된다.
 */
@DisplayName("리뷰 좋아요")
class ReviewLikeServiceTest {

    private static final String USERNAME = "tester";
    private static final Long USER_ID = 7L;
    private static final Long REVIEW_ID = 100L;

    private ReviewLikeRepository likeRepository;
    private ReviewRepository reviewRepository;
    private UserRepository userRepository;
    private ReviewLikeService service;

    @BeforeEach
    void setUp() {
        likeRepository = mock(ReviewLikeRepository.class);
        reviewRepository = mock(ReviewRepository.class);
        userRepository = mock(UserRepository.class);

        User user = new User(USERNAME, "tester@example.com", "pw");
        user.setUserId(USER_ID);
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(user));
        when(reviewRepository.existsById(REVIEW_ID)).thenReturn(true);

        service = new ReviewLikeService(likeRepository, reviewRepository, userRepository);
    }

    @Test
    @DisplayName("처음 누르면 저장하고 눌림 상태를 돌려준다")
    void firstClickLikes() {
        when(likeRepository.deleteByReviewIdAndUserId(REVIEW_ID, USER_ID)).thenReturn(0);
        when(likeRepository.countByReviewId(REVIEW_ID)).thenReturn(1L);

        ReviewLikeDto result = service.toggle(USERNAME, REVIEW_ID);

        verify(likeRepository).saveAndFlush(any(ReviewLike.class));
        assertThat(result.likedByMe()).isTrue();
        assertThat(result.likeCount()).isEqualTo(1L);
        assertThat(result.reviewId()).isEqualTo(REVIEW_ID);
    }

    @Test
    @DisplayName("이미 눌렀으면 지우고, 다시 저장하지 않는다")
    void secondClickUnlikes() {
        // 삭제가 1행을 지웠다 = 눌려 있었다. 별도 조회 없이 이 값만으로 방향이 정해진다.
        when(likeRepository.deleteByReviewIdAndUserId(REVIEW_ID, USER_ID)).thenReturn(1);
        when(likeRepository.countByReviewId(REVIEW_ID)).thenReturn(0L);

        ReviewLikeDto result = service.toggle(USERNAME, REVIEW_ID);

        verify(likeRepository, never()).saveAndFlush(any(ReviewLike.class));
        assertThat(result.likedByMe()).isFalse();
        assertThat(result.likeCount()).isZero();
    }

    @Test
    @DisplayName("동시에 두 번 눌려 유니크 제약이 터져도 오류가 아니라 '눌림'이다")
    void concurrentDuplicateIsTreatedAsLiked() {
        when(likeRepository.deleteByReviewIdAndUserId(REVIEW_ID, USER_ID)).thenReturn(0);
        when(likeRepository.saveAndFlush(any(ReviewLike.class)))
                .thenThrow(new DataIntegrityViolationException("uk_review_like_review_user"));
        when(likeRepository.countByReviewId(REVIEW_ID)).thenReturn(1L);

        ReviewLikeDto result = service.toggle(USERNAME, REVIEW_ID);

        // 원하던 최종 상태는 이미 다른 요청이 만들었다. 사용자에게 실패를 보일 이유가 없다.
        assertThat(result.likedByMe()).isTrue();
        assertThat(result.likeCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("저장한 행에는 대상 리뷰와 누른 사람이 함께 들어간다")
    void savedRowCarriesBothIds() {
        when(likeRepository.deleteByReviewIdAndUserId(REVIEW_ID, USER_ID)).thenReturn(0);
        when(likeRepository.countByReviewId(REVIEW_ID)).thenReturn(1L);

        service.toggle(USERNAME, REVIEW_ID);

        org.mockito.ArgumentCaptor<ReviewLike> saved =
                org.mockito.ArgumentCaptor.forClass(ReviewLike.class);
        verify(likeRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getReviewId()).isEqualTo(REVIEW_ID);
        assertThat(saved.getValue().getUserId()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("없는 리뷰에는 좋아요가 쌓이지 않는다")
    void unknownReviewIsRejected() {
        when(reviewRepository.existsById(999L)).thenReturn(false);

        assertThatThrownBy(() -> service.toggle(USERNAME, 999L))
                .isInstanceOf(IllegalArgumentException.class);

        verify(likeRepository, never()).saveAndFlush(any(ReviewLike.class));
        verify(likeRepository, never()).deleteByReviewIdAndUserId(anyLong(), anyLong());
    }

    @Test
    @DisplayName("좋아요가 하나도 없는 리뷰는 0으로 읽힌다 — 집계 결과에 아예 없기 때문")
    void snapshotFillsMissingCountsWithZero() {
        // List.of(Object[]) 는 가변인자로 풀려 List<Object> 가 된다 — 타입을 못 박아야 한다.
        when(likeRepository.countByReviewIds(any()))
                .thenReturn(List.<Object[]>of(new Object[] { 1L, 3L }));
        when(likeRepository.findLikedReviewIds(eq(USER_ID), any())).thenReturn(List.of(1L));

        LikeSnapshot snapshot = service.snapshot(List.of(1L, 2L), USER_ID);

        assertThat(snapshot.count(1L)).isEqualTo(3L);
        assertThat(snapshot.count(2L)).isZero();
        assertThat(snapshot.likedBy(1L)).isTrue();
        assertThat(snapshot.likedBy(2L)).isFalse();
    }

    @Test
    @DisplayName("비로그인 조회는 '내 좋아요'를 묻지 않는다 — 물을 대상이 없다")
    void anonymousViewerSkipsTheLikedQuery() {
        when(likeRepository.countByReviewIds(any()))
                .thenReturn(List.<Object[]>of(new Object[] { 1L, 5L }));

        LikeSnapshot snapshot = service.snapshot(List.of(1L), null);

        assertThat(snapshot.count(1L)).isEqualTo(5L);
        assertThat(snapshot.likedBy(1L)).isFalse();
        verify(likeRepository, never()).findLikedReviewIds(any(), any());
    }

    @Test
    @DisplayName("리뷰가 없으면 쿼리를 아예 보내지 않는다 — 빈 IN 절은 DB 마다 다르게 깨진다")
    void emptyListShortCircuits() {
        LikeSnapshot snapshot = service.snapshot(List.of(), USER_ID);

        assertThat(snapshot.counts()).isEmpty();
        verify(likeRepository, never()).countByReviewIds(any());
        verify(likeRepository, never()).findLikedReviewIds(any(), any());
    }
}
