package com.project.userservice.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.project.common.constants.StatusMessages;
import com.project.common.util.FileUtil;
import com.project.userservice.client.CatalogServiceClient;
import com.project.userservice.dto.ReviewDto;
import com.project.userservice.dto.ReviewStyleDto;
import com.project.userservice.dto.ReviewerDto;

import com.project.userservice.model.Review;
import com.project.userservice.model.ReviewStyle;
import com.project.userservice.model.User;
import com.project.userservice.repository.jpa.ReviewRepository;
import com.project.userservice.repository.jpa.UserRepository;
import com.project.userservice.security.request.ReviewRequest;
import com.project.userservice.security.response.ReviewsResponse;
import com.project.userservice.service.ReviewLikeService.LikeSnapshot;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 리뷰 등록 · 삭제 · 조회.
 *
 * <p>🔒 <b>외부 호출(order 의 구매 확인 · S3 업로드 · catalog 의 평점)은 DB 트랜잭션 밖에서 한다.</b> 🐛 예전에는 클래스 전체가
 * {@code @Transactional} 이라 세 호출이 모두 DB 커넥션을 쥔 채 돌았다 — 풀은 5개(DB_POOL_SIZE)라 S3 가 느릴 때 리뷰 다섯 건이 동시에
 * 들어오면 로그인을 포함한 모든 DB 요청이 5초 뒤 실패했다. 평점도 커밋 전에 보냈다(롤백돼도 이미 반영). 이제 짧은 읽기 → 외부 호출 →
 * 짧은 쓰기 → 커밋 뒤 평점 순서다.
 */
// [JA] 外部呼び出し(購入確認 · S3 · 評点)は DB トランザクションの外で行う。以前はクラス全体が @Transactional で、プール 5 本を握ったまま
//      呼んでいた — S3 が遅いと全 DB リクエストが失敗した。今は短い読み → 外部呼び出し → 短い書き → コミット後に評点。
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

    private final UserRepository userRepository;

    private final ReviewRepository reviewRepository;

    private final ImageService imageService;

    private final CatalogServiceClient catalogServiceClient;

    private final ReviewLikeService reviewLikeService;

    /** 리뷰 작성 자격(받은 상품인가)을 묻는다. docs/57 §13. */
    private final com.project.userservice.client.OrderServiceClient orderServiceClient;

    /** 짧은 DB 구간 — 외부 호출을 그 밖에 두기 위해 메서드 단위 @Transactional 대신 쓴다. */
    private final org.springframework.transaction.support.TransactionTemplate tx;

    /**
     * 받은 상품만 리뷰를 쓴다 — 켜고 끌 수 있게 둔다(시연 환경에서 배송까지 기다리기 어려울 때). 기본은 켠다.
     *
     * <p>🐛 검증이 없었다(docs/38 §1.7). 로그인한 아무나 사지 않은 상품에 별점을 남겨 평균을 움직일 수 있었다.
     */
    @org.springframework.beans.factory.annotation.Value("${review.require-purchase:true}")
    private boolean requirePurchase = true;

    /**
     * 리뷰 → DTO 변환. <b>이 메서드 하나만</b> 존재한다.
     *
     * <p>예전에는 조회·등록·삭제 세 경로가 같은 빌더를 네 벌 복사해 두고 있었다. 그중
     * 등록·삭제 쪽 사본은 {@code reviewedBy} 에 <b>요청한 사람</b>을 넣고 있어서, 리뷰를
     * 하나 쓰면 화면의 모든 리뷰 작성자 이름이 자기 이름으로 바뀌어 보였다. 좋아요 수처럼
     * 필드를 하나 더 붙이는 순간 그런 사본은 반드시 어긋나므로, 붙이기 전에 합쳤다.
     */
    private ReviewDto toDto(Review review, LikeSnapshot likes, Long viewerId) {
        User author = review.getReviewedBy();

        return ReviewDto.builder()
                .reviewId(review.getReviewId())
                .fit(review.getFit())
                .images(review.getImages())
                .rating(review.getRating())
                .review(review.getReview())
                .style(ReviewStyleDto.builder()
                        .color(review.getStyle().getColor())
                        .image(review.getStyle().getImage()).build())
                .size(review.getSize())
                .reviewedBy(new ReviewerDto(author.getUsername(), author.getImage()))
                .likeCount(likes.count(review.getReviewId()))
                .likedByMe(likes.likedBy(review.getReviewId()))
                .mine(viewerId != null && author != null && viewerId.equals(author.getUserId()))
                .build();
    }

    /**
     * 목록 변환. 좋아요는 리뷰마다 세지 않고 {@link ReviewLikeService#snapshot} 으로 한 번에
     * 모아 온다 — 리뷰 수만큼 쿼리가 늘어나지 않게 하려는 것이다.
     *
     * @param viewerId 비로그인 조회면 null
     */
    private List<ReviewDto> toDtos(List<Review> reviews, Long viewerId) {
        List<Long> reviewIds = new ArrayList<>(reviews.size());
        for (Review r : reviews) {
            reviewIds.add(r.getReviewId());
        }

        LikeSnapshot likes = reviewLikeService.snapshot(reviewIds, viewerId);

        List<ReviewDto> dtos = new ArrayList<>(reviews.size());
        for (Review r : reviews) {
            dtos.add(toDto(r, likes, viewerId));
        }
        return dtos;
    }

    /**
     * 리뷰의 필수 항목. 화면도 같은 것을 먼저 막지만, 서버가 믿을 것은 아니다 — 예전에는 style 이 빠지면
     * {@code request.getStyle().getColor()} 에서 NPE 가 나 "리뷰를 등록하지 못했습니다" 로만 보였다.
     */
    static void validate(ReviewRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("리뷰 내용을 입력해 주세요.");
        }
        // 사이즈는 비어 있어도 된다 — 사이즈 없는 상품(식품 등)은 고를 사이즈가 없다. 예전에는 필수라 그런 상품에는 리뷰를 쓸
        // 수 없었다. 사이즈가 있는 상품은 화면이 고르게 한다.
        if (request.getSize() == null) {
            request.setSize("");
        }
        if (request.getStyle() == null || request.getStyle().getColor() == null || request.getStyle().getColor().isBlank()) {
            throw new IllegalArgumentException("스타일(색상)을 선택해 주세요.");
        }
        // 착용감은 사이즈가 있을 때만 묻는다 — 사이즈 없는 상품(올리브오일)에 "정사이즈" 는 말이 되지 않는다. 사이즈를
        // 골랐으면 여전히 필수다.
        if (request.getFit() == null || request.getFit().isBlank()) {
            if (!request.getSize().isBlank()) {
                throw new IllegalArgumentException("착용감을 선택해 주세요.");
            }
            request.setFit("");
        }
        if (request.getRating() < 0.5F || request.getRating() > 5F) {
            throw new IllegalArgumentException("별점을 0.5~5 사이로 선택해 주세요.");
        }
        if (request.getReview() == null || request.getReview().isBlank()) {
            throw new IllegalArgumentException("리뷰 내용을 입력해 주세요.");
        }
        if (request.getReview().length() > 2000) {
            throw new IllegalArgumentException("리뷰는 2,000자까지 쓸 수 있습니다.");
        }
    }

    /**
     * 내가 이 상품에 쓴 리뷰를 찾는다. 없으면 null.
     *
     * <p>예전에는 이 자리에 {@code .findFirst().get()} 이 있었다. {@code Optional.get()} 은
     * 비어 있으면 {@code NoSuchElementException} 을 던지므로, <b>이미 다른 사람의 리뷰가 있는
     * 상품에 내가 처음 리뷰를 쓰면</b> 그대로 터졌다(컨트롤러가 RuntimeException 을 잡아
     * 400 으로 바꾸므로 원인도 보이지 않았다). 결과적으로 상품당 <b>첫 리뷰어 한 명만</b>
     * 리뷰를 쓸 수 있었다. 바로 아래 {@code if (null != review)} 는 {@code get()} 이 null 을
     * 돌려줄 수 없으므로 죽은 코드였다 — 원래 의도가 "없을 수도 있다"였음을 보여 준다.
     */
    private Review findMyReview(List<Review> reviews, User user) {
        return reviews.stream()
                .filter(r -> r.getReviewedBy().equals(user))
                .findFirst()
                .orElse(null);
    }

    /**
     * 상품 평점을 리뷰 목록으로 다시 계산해 catalog 에 알린다. 목록이 비면 0 이다.
     *
     * <p>🐛 실패하면 <b>리뷰까지 롤백됐다</b> — 이 호출이 리뷰 저장과 같은 트랜잭션 안에서 예외를 던졌다. 그리고
     * catalog 가 신원 헤더 없는 이 호출을 401 로 막고 있어서(docs/57 §3) 실제로 늘 실패했다. 평점은 리뷰에서 다시
     * 계산할 수 있는 <b>파생 값</b>이다 — 반영이 실패해도 리뷰는 남기고, 다음 리뷰가 다시 계산해 보낸다.
     */
    private static float ratingOf(List<Review> reviews) {
        if (reviews.isEmpty()) {
            // 0 으로 나누면 NaN 이 된다. 예전 코드는 첫 리뷰를 쓸 때 비어 있는 목록으로
            // 나눠서 NaN 을 catalog 로 보냈다.
            return 0.0F;
        }
        float totalPoints = reviews.stream().map(Review::getRating).reduce(0F, Float::sum);
        return totalPoints / reviews.size();
    }

    /** 커밋 뒤에 부른다 — 롤백될 리뷰의 평점을 먼저 보내지 않게. */
    private void publishRating(Long productId, float rating) {
        try {
            catalogServiceClient.updateRating(productId, rating);
        } catch (RuntimeException e) {
            log.warn("상품 평점 반영 실패 — 리뷰는 저장한다. productId={} ({})", productId, e.getClass().getSimpleName());
        }
    }

    /** 쓰기 구간의 결과 — 커밋 뒤 보낼 평점과 응답. */
    private record Written(float rating, List<ReviewDto> dtos) {
    }

    /**
     * 받은 상품인가 — 아니면 403, 확인하지 못하면 503(모르면 받지 않는다: 평점은 사지 않은 사람이 움직이면 안 되는 값이다).
     */
    private void requireReceived(Long userId, Long productId) {
        Boolean purchased;
        try {
            purchased = orderServiceClient.purchased(userId, productId).get("purchased");
        } catch (RuntimeException e) {
            log.warn("리뷰 자격 확인 실패 — 받지 않는다. userId={} productId={} ({})",
                    userId, productId, e.getClass().getSimpleName());
            throw ReviewNotAllowedException.checkUnavailable();
        }
        if (!Boolean.TRUE.equals(purchased)) {
            throw ReviewNotAllowedException.notPurchased();
        }
    }

    /** 업로드된 이미지를 저장하고 URL 목록을 돌려준다. 이미지가 없으면 null(= 기존 값 유지). */
    private List<String> uploadImages(List<MultipartFile> images) throws IOException {
        if (null == images || images.isEmpty()) {
            return null;
        }
        List<String> imageUrls = new ArrayList<>(images.size());
        for (MultipartFile image : images) {
            // 🐛 예전: 키가 난수 하나뿐이었다 — images/ 접두사도 확장자도 없어 버킷의 공개 읽기 정책(images/*) 밖이라, 업로드는 되는데
            //    화면에서는 403(깨진 이미지)이었다. 프로필 이미지와 같은 규칙으로(확장자 검사 포함).
            String key = FileUtil.getFilePath(image, "review/" + FileUtil.getRandomFilename(), "images");
            imageUrls.add(imageService.upload(image, key));
        }
        return imageUrls;
    }

    /**
     * 내 리뷰를 지운다.
     *
     * <p>🐛 예전에는 내가 쓴 리뷰가 없어도 아무것도 하지 않고 현재 목록을 200 으로 돌려줬다 — "지울 것이 없는 것은 오류가
     * 아니다(멱등)" 라는 생각이었지만, 화면은 200 을 받고 <b>"리뷰가 삭제되었습니다"</b> 를 띄웠다. 손님은 지운 적 없는 리뷰가
     * 지워졌다고 안내받는다. 지울 것이 없으면 그렇다고 말한다(404, "삭제할 리뷰가 없습니다.").
     *
     * @throws NoSuchElementException 회원이 없거나, 이 상품에 내 리뷰가 없다
     */
    public List<ReviewDto> deleteReview(String username, Long id) {
        Written w = tx.execute(status -> {
            User user = userRepository.findByUsername(username)
                    .orElseThrow(() -> new NoSuchElementException(StatusMessages.USER_NOT_FOUND));

            List<Review> reviews = reviewRepository.findByProductId(id);

            Review mine = findMyReview(reviews, user);
            if (mine == null) {
                throw new NoSuchElementException(StatusMessages.REVIEW_NOT_FOUND);
            }

            reviewRepository.deleteById(mine.getReviewId());
            reviews.remove(mine);
            return new Written(ratingOf(reviews), toDtos(reviews, user.getUserId()));
        });
        publishRating(id, w.rating());
        return w.dtos();
    }

    /**
     * @param viewerUsername 로그인한 사람의 사용자명. 비로그인 조회면 null — 리뷰는 누구나
     *                       볼 수 있고, 그때는 "내가 눌렀는지"를 물을 대상이 없으므로
     *                       {@code likedByMe} 가 전부 false 로 내려간다.
     */
    @Transactional(readOnly = true)
    public ReviewsResponse getReviews(Long id, String viewerUsername) {

        List<Review> reviews = reviewRepository.findByProductId(id);

        Long viewerId = viewerUsername == null ? null
                : userRepository.findByUsername(viewerUsername).map(User::getUserId).orElse(null);

        List<ReviewDto> dtos = toDtos(reviews, viewerId);

        ReviewsResponse result = new ReviewsResponse();
        result.setNum_reviews(dtos.size());
        result.setReviews(dtos);

        return result;
    }


    /**
     * 리뷰 등록/수정. 한 사람이 한 상품에 남기는 리뷰는 하나이므로, 이미 썼으면 덮어쓴다.
     *
     * <p><b>분기 기준은 "상품에 리뷰가 있는가"가 아니라 "내가 썼는가"다.</b> 예전에는
     * 전자로 갈라서, 남이 이미 리뷰를 쓴 상품에 내가 처음 리뷰를 쓰면 수정 분기로 들어간 뒤
     * 내 리뷰를 찾지 못해 예외로 끝났다({@link #findMyReview} 주석 참조).
     */
    public List<ReviewDto> addReview(String username, Long id, ReviewRequest request, List<MultipartFile> images)
            throws IOException {

        validate(request);

        // ① 짧은 읽기 — 누구인가, 이미 썼는가.
        Long[] who = tx.execute(status -> {
            User user = userRepository.findByUsername(username)
                    .orElseThrow(() -> new NoSuchElementException(StatusMessages.USER_NOT_FOUND));
            Review mine = findMyReview(reviewRepository.findByProductId(id), user);
            return new Long[] {user.getUserId(), mine == null ? null : mine.getReviewId()};
        });
        Long userId = who[0];
        boolean isNew = who[1] == null;

        // ② 트랜잭션 밖 — 자격 확인(order) · 이미지 업로드(S3). 새 리뷰만 묻는다 — 이미 쓴 리뷰를 고치는 것은 그때 이미 자격을
        //    확인했다. 이미지를 올리기 전에 본다(거절될 것을 올리지 않는다).
        if (isNew && requirePurchase) {
            requireReceived(userId, id);
        }
        // 이미지를 새로 올리지 않았으면 null 이고, 그때는 기존 이미지를 그대로 둔다.
        List<String> imageUrls = uploadImages(images);

        // ③ 짧은 쓰기.
        Written w = tx.execute(status -> {
            User user = userRepository.findByUsername(username)
                    .orElseThrow(() -> new NoSuchElementException(StatusMessages.USER_NOT_FOUND));
            List<Review> reviews = reviewRepository.findByProductId(id);
            Review mine = findMyReview(reviews, user);
            if (mine == null && !isNew && requirePurchase) {
                // ①과 ③ 사이에 내 리뷰가 지워졌다 — 새 리뷰가 되는데 자격은 확인하지 않았다. 드문 경합이라 다시 하게 한다.
                throw ReviewNotAllowedException.checkUnavailable();
            }

            ReviewStyle style = ReviewStyle.builder()
                    .color(request.getStyle().getColor())
                    .image(request.getStyle().getImage())
                    .build();

            if (null != mine) {

                mine.setProductId(id);
                mine.setReview(request.getReview());
                mine.setFit(request.getFit());
                mine.setSize(request.getSize());
                mine.setRating(request.getRating());
                // 🐛 예전: 수정할 때마다 새 ReviewStyle 로 바꿔 끼웠다 — 옛 review_style 행이 주인 없이 남았다(cascade 가 PERSIST 뿐).
                //    있으면 그 행을 고쳐 쓴다.
                if (mine.getStyle() != null) {
                    mine.getStyle().setColor(style.getColor());
                    mine.getStyle().setImage(style.getImage());
                } else {
                    mine.setStyle(style);
                }

                if (null != imageUrls) {
                    mine.setImages(imageUrls);
                }

                reviewRepository.save(mine);

            } else {

                Review.ReviewBuilder<?, ?> rBuilder = Review.builder()
                        .fit(request.getFit())
                        .rating(request.getRating())
                        .review(request.getReview())
                        .style(style)
                        .size(request.getSize())
                        .reviewedBy(user)
                        .productId(id);

                if (null != imageUrls) {
                    rBuilder.images(imageUrls);
                }

                Review created = rBuilder.build();

                user.getReviews().add(created);

                reviewRepository.save(created);

                // 방금 쓴 리뷰가 평균 계산과 응답 목록에 들어가야 한다. 예전에는 여기서
                // 빠져 있어서, 리뷰를 쓴 직후 화면에 자기 리뷰가 보이지 않았고 평점도
                // 갱신 전 목록으로 계산됐다.
                reviews.add(created);
            }
            return new Written(ratingOf(reviews), toDtos(reviews, user.getUserId()));
        });

        // ④ 커밋 뒤 — 평점(catalog).
        publishRating(id, w.rating());
        return w.dtos();
    }

}
