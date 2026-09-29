package com.project.userservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.project.userservice.client.CatalogServiceClient;
import com.project.userservice.dto.ReviewDto;
import com.project.userservice.model.Review;
import com.project.userservice.model.ReviewStyle;
import com.project.userservice.model.User;
import com.project.userservice.repository.jpa.ReviewRepository;
import com.project.userservice.repository.jpa.UserRepository;
import com.project.userservice.security.request.ReviewRequest;
import com.project.userservice.service.ReviewLikeService.LikeSnapshot;

/**
 * 리뷰 등록/삭제의 <b>분기</b>를 고정한다.
 *
 * <p>여기서 잡으려는 것은 하나다: "이 상품에 리뷰가 있는가"와 "내가 썼는가"는 다른
 * 질문이라는 것. 둘을 혼동하면 남이 먼저 리뷰를 쓴 상품에는 아무도 리뷰를 못 쓰게 된다.
 */
@DisplayName("리뷰 등록·삭제")
class ReviewServiceTest {

    private static final Long PRODUCT_ID = 42L;

    private UserRepository userRepository;
    private ReviewRepository reviewRepository;
    private ImageService imageService;
    private CatalogServiceClient catalogServiceClient;
    private ReviewLikeService reviewLikeService;
    private com.project.userservice.client.OrderServiceClient orderServiceClient;
    private ReviewService service;

    private User me;
    private User someoneElse;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        reviewRepository = mock(ReviewRepository.class);
        imageService = mock(ImageService.class);
        catalogServiceClient = mock(CatalogServiceClient.class);
        reviewLikeService = mock(ReviewLikeService.class);
        orderServiceClient = mock(com.project.userservice.client.OrderServiceClient.class);
        // 기본은 받은 상품 — 자격 검사는 아래 전용 테스트가 본다.
        when(orderServiceClient.purchased(anyLong(), anyLong())).thenReturn(java.util.Map.of("purchased", true));

        when(reviewLikeService.snapshot(any(), any())).thenReturn(LikeSnapshot.EMPTY);

        me = user(1L, "me");
        someoneElse = user(2L, "other");
        when(userRepository.findByUsername("me")).thenReturn(Optional.of(me));

        service = new ReviewService(userRepository, reviewRepository, imageService,
                catalogServiceClient, reviewLikeService, orderServiceClient);
    }

    private static User user(Long id, String username) {
        User u = new User(username, username + "@example.com", "pw");
        u.setUserId(id);
        return u;
    }

    private static Review reviewBy(Long reviewId, User author, float rating) {
        return Review.builder()
                .reviewId(reviewId)
                .rating(rating)
                .review("좋아요")
                .size("M")
                .fit("True to size")
                .productId(PRODUCT_ID)
                .reviewedBy(author)
                .style(ReviewStyle.builder().color("black").image("img").build())
                .build();
    }

    private static ReviewRequest request(float rating) {
        ReviewRequest r = new ReviewRequest();
        r.setRating(rating);
        r.setReview("내 리뷰");
        r.setSize("L");
        r.setFit("Large");
        com.project.userservice.dto.ReviewStyleDto style =
                com.project.userservice.dto.ReviewStyleDto.builder()
                        .color("white").image("img2").build();
        r.setStyle(style);
        return r;
    }

    /** findByProductId 가 돌려주는 목록은 서비스가 직접 수정하므로 가변이어야 한다. */
    private void givenExistingReviews(Review... reviews) {
        when(reviewRepository.findByProductId(PRODUCT_ID))
                .thenReturn(new ArrayList<>(List.of(reviews)));
    }

    @Test
    @DisplayName("남이 먼저 쓴 상품에도 리뷰를 쓸 수 있다 — 예전에는 여기서 터졌다")
    void secondReviewerCanWrite() throws Exception {
        givenExistingReviews(reviewBy(100L, someoneElse, 4F));

        List<ReviewDto> result = service.addReview("me", PRODUCT_ID, request(5F), null);

        ArgumentCaptor<Review> saved = ArgumentCaptor.forClass(Review.class);
        verify(reviewRepository).save(saved.capture());
        assertThat(saved.getValue().getReviewedBy()).isEqualTo(me);
        // 남의 리뷰 + 내 리뷰 = 2건이 응답에 담긴다
        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("내가 이미 쓴 리뷰는 새로 만들지 않고 덮어쓴다")
    void ownReviewIsUpdatedInPlace() throws Exception {
        Review mine = reviewBy(100L, me, 2F);
        givenExistingReviews(mine, reviewBy(101L, someoneElse, 4F));

        List<ReviewDto> result = service.addReview("me", PRODUCT_ID, request(5F), null);

        assertThat(mine.getRating()).isEqualTo(5F);
        assertThat(mine.getReview()).isEqualTo("내 리뷰");
        assertThat(result).hasSize(2);   // 늘어나지 않는다
    }

    @Test
    @DisplayName("첫 리뷰의 평점은 NaN 이 아니다 — 빈 목록으로 나누던 자리")
    void firstReviewProducesRealRating() throws Exception {
        givenExistingReviews();

        service.addReview("me", PRODUCT_ID, request(5F), null);

        ArgumentCaptor<Float> rating = ArgumentCaptor.forClass(Float.class);
        verify(catalogServiceClient).updateRating(eq(PRODUCT_ID), rating.capture());
        assertThat(Float.isNaN(rating.getValue())).isFalse();
        assertThat(rating.getValue()).isEqualTo(5F);
    }

    @Test
    @DisplayName("🔴 catalog 에 평점 반영이 실패해도 리뷰는 저장된다 — 평점은 다시 계산할 수 있는 파생 값 (docs/57 §3)")
    void ratingFailureDoesNotLoseTheReview() throws Exception {
        givenExistingReviews();
        org.mockito.Mockito.doThrow(new RuntimeException("catalog 401"))
                .when(catalogServiceClient).updateRating(anyLong(), org.mockito.ArgumentMatchers.anyFloat());

        List<ReviewDto> result = service.addReview("me", PRODUCT_ID, request(5F), null);

        assertThat(result).hasSize(1);
        verify(reviewRepository).save(any(Review.class));
    }

    @Test
    @DisplayName("평점은 방금 쓴 리뷰까지 포함해 계산한다")
    void ratingIncludesTheNewReview() throws Exception {
        givenExistingReviews(reviewBy(100L, someoneElse, 3F));

        service.addReview("me", PRODUCT_ID, request(5F), null);

        ArgumentCaptor<Float> rating = ArgumentCaptor.forClass(Float.class);
        verify(catalogServiceClient).updateRating(eq(PRODUCT_ID), rating.capture());
        assertThat(rating.getValue()).isEqualTo(4F);   // (3 + 5) / 2
    }

    @Test
    @DisplayName("이미지를 새로 올리지 않으면 기존 이미지를 지우지 않는다")
    void omittedImagesAreKept() throws Exception {
        Review mine = reviewBy(100L, me, 2F);
        mine.setImages(List.of("keep-me"));
        givenExistingReviews(mine);

        service.addReview("me", PRODUCT_ID, request(5F), null);

        assertThat(mine.getImages()).containsExactly("keep-me");
    }

    @Test
    @DisplayName("🔴 내 리뷰가 없으면 삭제는 '없다' 고 말한다 — 예전에는 200 이라 화면이 '삭제되었습니다' 를 띄웠다")
    void deletingWithoutOwnReviewSaysSo() {
        givenExistingReviews(reviewBy(100L, someoneElse, 4F));

        assertThatThrownBy(() -> service.deleteReview("me", PRODUCT_ID))
                .isInstanceOf(java.util.NoSuchElementException.class)
                .hasMessage(com.project.common.constants.StatusMessages.REVIEW_NOT_FOUND);

        verify(reviewRepository, never()).deleteById(anyLong());
        // 남의 리뷰 평점을 건드릴 이유도 없다
        verify(catalogServiceClient, never()).updateRating(anyLong(), org.mockito.ArgumentMatchers.anyFloat());
    }

    @Test
    @DisplayName("리뷰마다 '내 리뷰인가' 를 알려 준다 — 화면이 삭제 버튼을 그릴지 정한다")
    void mineFlag() throws Exception {
        givenExistingReviews(reviewBy(100L, someoneElse, 4F));

        List<ReviewDto> result = service.addReview("me", PRODUCT_ID, request(5F), null);

        assertThat(result).extracting(ReviewDto::isMine).containsExactlyInAnyOrder(false, true);
    }

    @Test
    @DisplayName("필수 항목이 빠지면 무엇이 빠졌는지 말한다 — 예전에는 style 이 없으면 NPE 로 일반 실패였다")
    void validation() {
        ReviewRequest noStyle = request(5F);
        noStyle.setStyle(null);
        assertThatThrownBy(() -> service.addReview("me", PRODUCT_ID, noStyle, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("스타일(색상)을 선택해 주세요.");

        ReviewRequest noRating = request(0F);
        assertThatThrownBy(() -> service.addReview("me", PRODUCT_ID, noRating, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("별점을 0.5~5 사이로 선택해 주세요.");

        verify(reviewRepository, never()).save(any());
    }

    @Test
    @DisplayName("🔴 사이즈 없는 상품(식품 등)도 리뷰를 쓸 수 있다 — 사이즈·착용감 없이. 예전에는 사이즈가 필수라 막혔다")
    void sizelessProductCanBeReviewed() throws Exception {
        givenExistingReviews();
        ReviewRequest noSize = request(5F);
        noSize.setSize(null);
        noSize.setFit(null);   // 사이즈가 없으면 착용감도 묻지 않는다

        service.addReview("me", PRODUCT_ID, noSize, null);

        ArgumentCaptor<Review> saved = ArgumentCaptor.forClass(Review.class);
        verify(reviewRepository).save(saved.capture());
        assertThat(saved.getValue().getSize()).isEmpty();
        assertThat(saved.getValue().getFit()).isEmpty();
    }

    @Test
    @DisplayName("사이즈를 골랐으면 착용감은 여전히 필수다")
    void fitRequiredWhenSized() {
        ReviewRequest noFit = request(5F);
        noFit.setFit(" ");
        assertThatThrownBy(() -> service.addReview("me", PRODUCT_ID, noFit, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("착용감을 선택해 주세요.");
        verify(reviewRepository, never()).save(any());
    }

    @Test
    @DisplayName("마지막 리뷰를 지우면 평점이 0 이 된다")
    void deletingTheLastReviewResetsRating() {
        givenExistingReviews(reviewBy(100L, me, 4F));

        List<ReviewDto> result = service.deleteReview("me", PRODUCT_ID);

        verify(reviewRepository).deleteById(100L);
        verify(catalogServiceClient).updateRating(PRODUCT_ID, 0.0F);
        assertThat(result).isEmpty();
    }

    // ── 리뷰 자격 — 받은 상품만(docs/57 §13) ────────────────────────────────────

    @Test
    @DisplayName("🔴 받지 않은 상품에는 새 리뷰를 쓸 수 없다 — 저장도 평점 반영도 없다")
    void notPurchasedIsRejected() {
        givenExistingReviews();
        when(orderServiceClient.purchased(1L, PRODUCT_ID)).thenReturn(java.util.Map.of("purchased", false));

        assertThatThrownBy(() -> service.addReview("me", PRODUCT_ID, request(5), null))
                .isInstanceOf(ReviewNotAllowedException.class)
                .satisfies(e -> assertThat(((ReviewNotAllowedException) e).isUnavailable()).isFalse());
        verify(reviewRepository, never()).save(any());
        verify(catalogServiceClient, never()).updateRating(anyLong(), org.mockito.ArgumentMatchers.anyFloat());
    }

    @Test
    @DisplayName("🔴 구매 여부를 확인하지 못하면 받지 않는다(503) — 모르면 평점을 움직이지 않는다")
    void checkUnavailableIsRejected() {
        givenExistingReviews();
        when(orderServiceClient.purchased(anyLong(), anyLong())).thenThrow(new RuntimeException("down"));

        assertThatThrownBy(() -> service.addReview("me", PRODUCT_ID, request(5), null))
                .isInstanceOf(ReviewNotAllowedException.class)
                .satisfies(e -> assertThat(((ReviewNotAllowedException) e).isUnavailable()).isTrue());
        verify(reviewRepository, never()).save(any());
    }

    @Test
    @DisplayName("이미 쓴 리뷰를 고치는 것은 다시 묻지 않는다")
    void editingOwnReviewSkipsCheck() throws Exception {
        givenExistingReviews(reviewBy(10L, me, 3));
        when(orderServiceClient.purchased(anyLong(), anyLong())).thenThrow(new RuntimeException("down"));

        assertThatCode(() -> service.addReview("me", PRODUCT_ID, request(4), null)).doesNotThrowAnyException();
    }
}
