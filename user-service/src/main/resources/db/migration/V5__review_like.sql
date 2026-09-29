-- 리뷰 좋아요.
--
-- 프론트엔드에는 엄지 아이콘이 이미 있었지만 눌러도 아무 일이 없었다. `review.likes` 라는
-- 필드를 읽고 있었는데 서버는 그런 필드를 준 적이 없고(항상 undefined), 화면에는 하드코딩된
-- "6 likes" 가 찍혔다(ReviewCard.jsx). 즉 UI 만 있고 저장소도 API 도 없었다.
--
-- **왜 카운터 컬럼이 아니라 별도 테이블인가.** users.review 에 like_count 하나를 두면
-- 화면은 만들 수 있지만 "내가 눌렀는지"를 답할 수 없다. 그러면 버튼은 항상 빈 상태로
-- 그려지고, 새로고침할 때마다 다시 누를 수 있어 한 사람이 무한히 올릴 수 있다. 좋아요는
-- 숫자가 아니라 **(리뷰, 사용자) 한 쌍**이므로 그대로 행으로 저장한다.
--
-- **중복은 DB 가 막는다.** uk_review_like_review_user 유니크 제약이 유일한 방어선이다.
-- 애플리케이션에서 "있으면 지우고 없으면 넣는" 검사를 하더라도 두 요청이 동시에 들어오면
-- 그 사이가 벌어진다(더블클릭·모바일 중복 탭). 제약이 있으면 최악의 경우에도 한 행만
-- 남는다 — 애플리케이션은 실패를 "이미 눌려 있다"로 해석하면 된다.
--
-- **ON DELETE CASCADE 인 이유.** 리뷰 삭제(ReviewService.deleteReview)와 계정 정리
-- (UnverifiedAccountCleanup)가 이미 존재한다. 캐스케이드가 없으면 좋아요가 하나라도
-- 붙은 리뷰는 외래키에 막혀 지워지지 않는다 — 기존 기능이 조용히 깨진다. 좋아요는
-- 대상이 사라지면 의미가 없는 파생 데이터라 함께 지우는 것이 맞다.

CREATE TABLE IF NOT EXISTS users.review_like (
    like_id    bigint NOT NULL,
    review_id  bigint NOT NULL,
    user_id    bigint NOT NULL,
    created_at timestamp(6),
    PRIMARY KEY (like_id),
    CONSTRAINT uk_review_like_review_user UNIQUE (review_id, user_id),
    CONSTRAINT fk_review_like_review FOREIGN KEY (review_id)
        REFERENCES users.review (review_id) ON DELETE CASCADE,
    CONSTRAINT fk_review_like_user FOREIGN KEY (user_id)
        REFERENCES users.users (user_id) ON DELETE CASCADE
);

-- 목록 화면은 "이 상품 리뷰들의 좋아요 수"를 한 번에 세고(review_id IN (...)),
-- 마이페이지류는 "내가 누른 것"을 훑는다. 두 방향 모두 인덱스를 준다.
-- (review_id 단독 인덱스는 유니크 제약의 선행 컬럼으로 이미 커버되지만, 제약 이름에
--  의존하지 않도록 명시해 둔다.)
CREATE INDEX IF NOT EXISTS idx_review_like_review ON users.review_like (review_id);
CREATE INDEX IF NOT EXISTS idx_review_like_user ON users.review_like (user_id);
