package com.project.userservice.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.project.userservice.dto.ReviewLikeDto;
import com.project.userservice.model.ReviewLike;
import com.project.userservice.model.User;
import com.project.userservice.repository.jpa.ReviewLikeRepository;
import com.project.userservice.repository.jpa.ReviewRepository;
import com.project.userservice.repository.jpa.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 리뷰 좋아요 토글과 목록 집계.
 *
 * <p><b>클래스에 {@code @Transactional} 을 붙이지 않은 것은 의도적이다.</b> 토글은 "삭제 시도 →
 * 실패하면 삽입"인데, 삽입이 유니크 제약에 걸리면(같은 사람이 동시에 두 번 눌렀다는 뜻)
 * Hibernate 는 그 예외로 <b>영속성 컨텍스트 전체를 무효화</b>한다. 하나의 큰 트랜잭션 안에서
 * 이를 잡으면 뒤따르는 개수 조회까지 같이 죽고 롤백되어, 사용자는 실제로는 성공한 동작을
 * 오류로 보게 된다. 트랜잭션 경계를 리포지토리 호출 단위로 두면 제약 위반은 그 삽입 하나만
 * 되돌리고, 우리는 그것을 "이미 눌려 있다"로 해석하면 된다.
 *
 * <p>[JA] レビューいいねのトグルと一覧の集計。
 * <p><b>クラスに @Transactional を付けていないのは意図的である。</b> トグルは「削除を試す → 失敗したら挿入」だが、挿入が一意制約に引っかかると(同じ人が同時に二回押したということ)
 * Hibernate はその例外で<b>永続化コンテキスト全体を無効化</b>する。一つの大きなトランザクション内でこれを捕まえると後続の件数検索まで一緒に死んでロールバックされ、
 * ユーザーは実際には成功した操作をエラーとして見る。トランザクション境界をリポジトリ呼び出し単位にすれば制約違反はその挿入一つだけを戻し、こちらはそれを「既に押されている」と解釈すればよい。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewLikeService {

    private final ReviewLikeRepository likeRepository;
    private final ReviewRepository reviewRepository;
    private final UserRepository userRepository;

    /**
     * 좋아요를 누르거나 취소한다. 같은 요청을 두 번 보내면 원래대로 돌아온다.
     *
     * <p>[JA] いいねを押すか取り消す。同じリクエストを二回送ると元に戻る。
     *
     * @return 토글 <b>후</b>의 상태. 개수는 서버가 다시 센 값이다.
     *     [JA] トグル<b>後</b>の状態。件数はサーバが数え直した値。
     * @throws IllegalArgumentException 사용자나 리뷰가 없을 때
     *     [JA] ユーザーやレビューがないとき
     */
    public ReviewLikeDto toggle(String username, Long reviewId) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        // 없는 리뷰에 좋아요가 쌓이지 않도록 먼저 막는다. 외래키도 막아 주지만, 그 경우
        // 사용자에게는 원인을 알 수 없는 500 이 나간다.
        // [JA] 存在しないレビューにいいねが積もらないよう先に止める。外部キーも止めてくれるが、その場合ユーザーには原因不明の 500 が出る。
        if (!reviewRepository.existsById(reviewId)) {
            throw new IllegalArgumentException("리뷰를 찾을 수 없습니다.");
        }

        Long userId = user.getUserId();
        boolean liked;

        if (likeRepository.deleteByReviewIdAndUserId(reviewId, userId) > 0) {
            liked = false;
        } else {
            try {
                likeRepository.saveAndFlush(ReviewLike.builder()
                        .reviewId(reviewId)
                        .userId(userId)
                        .build());
                liked = true;
            } catch (DataIntegrityViolationException e) {
                // 같은 사용자의 요청이 동시에 들어왔다. 제약이 한 행만 남겼고, 원하던
                // 최종 상태("눌림")는 이미 이루어졌다.
                // [JA] 同じユーザーのリクエストが同時に来た。制約が一行だけ残し、望んだ最終状態(「押された」)は既に達成されている。
                log.debug("좋아요 중복 요청 — 이미 눌려 있다: reviewId={}", reviewId);
                liked = true;
            }
        }

        return new ReviewLikeDto(reviewId, likeRepository.countByReviewId(reviewId), liked);
    }

    /**
     * 리뷰 목록에 붙일 좋아요 정보를 쿼리 <b>두 번</b>으로 모은다(개수 1회 + 내 좋아요 1회).
     *
     * <p>[JA] レビュー一覧に付けるいいね情報をクエリ<b>二回</b>で集める(件数 1 回 + 自分のいいね 1 回)。
     *
     * @param viewerId 비로그인 조회면 null — 그때는 "내가 눌렀는지"를 물을 대상이 없다.
     *     [JA] 未ログインの閲覧なら null — そのときは「自分が押したか」を問う対象がない。
     */
    @Transactional(readOnly = true)
    public LikeSnapshot snapshot(Collection<Long> reviewIds, Long viewerId) {
        if (reviewIds == null) {
            return LikeSnapshot.EMPTY;
        }

        // 아직 저장 전이라 식별자가 없는 리뷰는 좋아요가 있을 수 없다. IN 절에 null 을
        // 넣어 봐야 아무것도 맞지 않으므로 애초에 빼고 묻는다.
        // [JA] まだ保存前で識別子がないレビューにはいいねがあり得ない。IN 句に null を入れても何も一致しないので最初から除いて問う。
        List<Long> ids = reviewIds.stream().filter(Objects::nonNull).toList();
        if (ids.isEmpty()) {
            return LikeSnapshot.EMPTY;
        }

        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : likeRepository.countByReviewIds(ids)) {
            counts.put((Long) row[0], ((Number) row[1]).longValue());
        }

        Set<Long> liked = viewerId == null
                ? Set.of()
                : new HashSet<>(likeRepository.findLikedReviewIds(viewerId, ids));

        return new LikeSnapshot(counts, liked);
    }

    /**
     * 한 화면에 필요한 좋아요 정보. 좋아요가 없는 리뷰는 집계 결과에 아예 없으므로
     * {@link #count} 가 0 을 채워 준다 — 호출부가 null 을 다루지 않게 하려는 것이다.
     *
     * <p>[JA] 一画面に必要ないいね情報。いいねのないレビューは集計結果にそもそもないので count が 0 を埋める — 呼び出し側が null を扱わずに済むように。
     */
    public record LikeSnapshot(Map<Long, Long> counts, Set<Long> liked) {

        public static final LikeSnapshot EMPTY = new LikeSnapshot(Map.of(), Set.of());

        /**
         * @param reviewId null 이면 0. 방금 만들어 아직 식별자가 없는 리뷰가 이 자리에
         *                 들어올 수 있는데, {@code Map.of()} 로 만든 {@link #EMPTY} 는 null
         *                 키 조회에서 {@code NullPointerException} 을 던진다(HashMap 은
         *                 던지지 않아, 목록이 비었을 때만 터지는 형태가 된다).
         *     [JA] null なら 0。作ったばかりでまだ識別子のないレビューがここに来うるが、Map.of() で作った EMPTY は null キーの検索で NullPointerException を投げる(HashMap は投げないので、一覧が空のときだけ爆発する形になる)。
         */
        public long count(Long reviewId) {
            return reviewId == null ? 0L : counts.getOrDefault(reviewId, 0L);
        }

        /** @param reviewId null 이면 false. 이유는 {@link #count} 와 같다. */
        // [JA] null なら false。理由は count と同じ。
        public boolean likedBy(Long reviewId) {
            return reviewId != null && liked.contains(reviewId);
        }
    }
}
