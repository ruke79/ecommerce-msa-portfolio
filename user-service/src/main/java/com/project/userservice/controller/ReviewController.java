package com.project.userservice.controller;

import com.project.common.exception.ClientMessage;

import java.io.IOException;
import java.util.List;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.project.common.constants.StatusMessages;
import com.project.common.response.MessageResponse;
import com.project.userservice.dto.ReviewDto;

import com.project.userservice.security.request.ReviewRequest;
import com.project.userservice.security.response.ReviewsResponse;
import com.project.userservice.service.ReviewLikeService;
import com.project.userservice.service.ReviewService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("api/review")
@RequiredArgsConstructor
public class ReviewController {


    private final ReviewService reviewService;

    private final ReviewLikeService reviewLikeService;

    
    /**
     * 리뷰 목록. 로그인하지 않아도 볼 수 있다.
     *
     * <p>{@code userDetails} 는 <b>있으면 쓰고 없으면 만다</b>. 로그인 상태라면 각 리뷰에
     * "내가 좋아요를 눌렀는지"({@code likedByMe})가 함께 내려가고, 비로그인이면 그 값이
     * 전부 false 다. 이 값이 없으면 프론트가 버튼을 빈 상태로만 그리게 되고, 새로고침할
     * 때마다 다시 누를 수 있는 것처럼 보인다.
     *
     * <p>[JA] レビュー一覧。ログインしなくても見られる。
     * {@code userDetails} は<b>あれば使い、なければ使わない</b>。ログイン状態なら各レビューに「自分がいいねを押したか」
     * ({@code likedByMe})が一緒に返り、未ログインならその値はすべて false。この値がないとフロントがボタンを空の状態でしか
     * 描けず、リロードのたびにまた押せるように見える。
     */
    @GetMapping("/reviews/{productId}")
    ResponseEntity<?> getReviews(@PathVariable("productId") Long productId,
            @AuthenticationPrincipal UserDetails userDetails) {


            try {

                String viewer = userDetails == null ? null : userDetails.getUsername();

                ReviewsResponse result = reviewService.getReviews(productId, viewer);

                return new ResponseEntity<>(result, HttpStatus.OK);
            } catch(RuntimeException e) {
                // 조회 실패다 — 예전에는 "Review registration failed"(등록 실패)라고 답했다.
                // 🐛 원인을 남기지 않아 운영에서 400 만 보였다(Review 엔티티의 생성자 누락, B-69). 리뷰 본문은 싣지 않는다.
                // [JA] 照会失敗である — 以前は "Review registration failed"(登録失敗)と答えていた。
                //      🐛 原因を残さず運用で 400 しか見えなかった(Review エンティティのコンストラクタ漏れ、B-69)。レビュー本文は載せない。
                log.warn("리뷰 목록 조회 실패 productId={}", productId, e);
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new MessageResponse(StatusMessages.REVIEW_LOAD_FAILED));
            }

    }

    /**
     * 리뷰 좋아요 토글 — 안 눌렀으면 누르고, 눌렀으면 취소한다.
     *
     * <p><b>왜 토글 하나인가.</b> 좋아요/취소를 두 엔드포인트로 나누면 클라이언트가 현재
     * 상태를 알고 있어야 올바른 쪽을 부를 수 있다. 화면이 낡은 상태를 들고 있으면(다른 탭에서
     * 이미 눌렀다든지) 요청이 엇나간다. 토글은 서버가 현재 상태를 보고 방향을 정하므로
     * 그런 어긋남이 없고, 결과로 <b>서버가 다시 센</b> 개수와 상태를 함께 돌려준다.
     *
     * <p>로그인은 필수다. 좋아요는 (리뷰, 사용자) 한 쌍으로 저장되므로 익명에게는 저장할
     * 신원이 없고, 신원 없이 허용하면 한 사람이 무한히 누를 수 있다.
     *
     * <p>[JA] レビューのいいねトグル — 押していなければ押し、押していれば取り消す。
     * <b>なぜトグル一つなのか。</b>いいね/取消を二つのエンドポイントに分けると、クライアントが現在の状態を
     * 知っていないと正しい方を呼べない。画面が古い状態を持っていると(別のタブで既に押したなど)リクエストが食い違う。
     * トグルはサーバーが現在の状態を見て方向を決めるのでそうした食い違いがなく、結果として<b>サーバーが数え直した</b>
     * 件数と状態を一緒に返す。
     * ログインは必須だ。いいねは(レビュー, ユーザー)の組で保存されるので匿名には保存する身元がなく、
     * 身元なしで許すと一人が無限に押せる。
     */
    @PostMapping("/like/{reviewId}")
    ResponseEntity<?> toggleLike(@PathVariable("reviewId") Long reviewId,
            @AuthenticationPrincipal UserDetails userDetails) {

        if (null == userDetails) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse(StatusMessages.LOGIN_REQUIRED));
        }

        try {
            return new ResponseEntity<>(
                    reviewLikeService.toggle(userDetails.getUsername(), reviewId), HttpStatus.OK);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new MessageResponse(ClientMessage.of(e)));
        }
    }



    @PostMapping("/add/{productId}")
    ResponseEntity<?> addReview(@PathVariable("productId") Long id, @RequestPart("review") ReviewRequest request, 
    @RequestPart(value="image", required=false) List<MultipartFile> images,    @AuthenticationPrincipal UserDetails userDetails) throws IOException  {
           
            if (null != userDetails) {
                String username = userDetails.getUsername();

                try {

                    List<ReviewDto> result = reviewService.addReview(username, id, request, images);

                    return new ResponseEntity<>(result, HttpStatus.OK);
                } catch (com.project.userservice.service.ReviewNotAllowedException e) {
                    // 받은 상품이 아니면 403, 구매 여부를 확인하지 못했으면 503(docs/57 §13).
                    // [JA] 受け取った商品でなければ 403、購入有無を確認できなければ 503(docs/57 §13)。
                    return ResponseEntity.status(e.isUnavailable() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.FORBIDDEN)
                            .body(new MessageResponse(ClientMessage.of(e)));
                } catch(RuntimeException e) {
                    // 검증 문구("사이즈를 선택해 주세요." 등)는 그대로, 그 밖의 오류는 일반 문구로(ClientMessage).
                    // [JA] 検証文言(「サイズを選択してください。」など)はそのまま、その他のエラーは一般文言で(ClientMessage)。
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new MessageResponse(ClientMessage.of(e, StatusMessages.REVIEW_REGISTRATION_FAILED)));
                }

            }
            else {
                // 🐛 로그인하지 않은 요청을 404 "User not found"(영어, 본문도 JSON 이 아닌 문자열)로 답했다.
                // [JA] 🐛 ログインしていないリクエストに 404 "User not found"(英語、本文も JSON ではなく文字列)で答えていた。
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new MessageResponse(StatusMessages.LOGIN_REQUIRED));
            }
            
        }

        
        @DeleteMapping("/delete/{productId}") 
        ResponseEntity<?> deleteReview( @PathVariable("productId") Long productId,
        @AuthenticationPrincipal UserDetails userDetails
        )  {
            
           
            if (null != userDetails) {
                String username = userDetails.getUsername();

                try {

                    List<ReviewDto> response = reviewService.deleteReview(username, productId);

                    return new ResponseEntity<>(response, HttpStatus.OK);

                } catch (java.util.NoSuchElementException e) {
                    // 지울 내 리뷰가 없다 — 예전에는 200 이라 화면이 "삭제되었습니다" 를 띄웠다.
                    // [JA] 消す自分のレビューがない — 以前は 200 で画面が「削除されました」を出していた。
                    return ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body(new MessageResponse(ClientMessage.of(e, StatusMessages.REVIEW_DELETE_FAILED)));
                } catch(RuntimeException e) {
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new MessageResponse(StatusMessages.REVIEW_DELETE_FAILED));
                }

            }
            else {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new MessageResponse(StatusMessages.LOGIN_REQUIRED));
            }
            
        }

    
}
