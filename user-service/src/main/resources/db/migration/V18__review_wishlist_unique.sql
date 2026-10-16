-- 리뷰 · 위시리스트의 중복을 DB 가 막는다 — 한 사람이 한 상품에 리뷰 하나, 같은 상품 · 스타일의 찜 하나.
-- [JA] レビュー · ウィッシュリストの重複を DB が防ぐ — 一人一商品にレビュー一つ、同じ商品 · スタイルのお気に入り一つ。
--
-- ROLLBACK-SAFE: 인덱스 둘과 중복 행 정리. 옛 이미지도 그대로 돈다(중복을 만들려는 삽입만 제약에 걸린다 — 그것이 목적이다).
--   지운 중복 행은 되살리지 않는다 — 같은 사람의 같은 상품 리뷰 · 찜의 사본이라 잃는 정보가 없다(남는 것은 가장 최근 리뷰 · 가장 먼저 찜한 행).
--
-- 🐛 앱은 "확인한 뒤 삽입" 만 했다(ReviewService.findMyReview · WishItemService) — 더블클릭 두 요청이 모두 "없음" 을 읽어
--    같은 사용자의 리뷰가 둘(둘 다 평균에 들어감) · 같은 찜이 두 행 생겼다.

-- ── 리뷰: (user_id, product_id) 마다 가장 최근(review_id 가 큰 — TSID 는 시간 순) 하나만 ────────────────
-- 지우는 리뷰의 좋아요는 FK(ON DELETE CASCADE)로 함께 지워지고, 그 리뷰만 쓰던 review_style 도 지운다(1:1).
WITH ranked AS (
    SELECT review_id,
           row_number() OVER (PARTITION BY user_id, product_id ORDER BY review_id DESC) AS rn
    FROM users.review
    WHERE product_id IS NOT NULL
), gone AS (
    DELETE FROM users.review r
    USING ranked k
    WHERE r.review_id = k.review_id AND k.rn > 1
    RETURNING r.rstyle_id
)
DELETE FROM users.review_style s USING gone g WHERE s.rstyle_id = g.rstyle_id;

CREATE UNIQUE INDEX IF NOT EXISTS uq_review_user_product ON users.review (user_id, product_id);

-- ── 위시리스트: (user_id, product_id, style) 마다 가장 먼저 찜한 하나만 — style 이 NULL 인 옛 행도 같은 것으로 본다 ─────────
DELETE FROM users.wishlist w
USING users.wishlist k
WHERE w.user_id = k.user_id
  AND w.product_id IS NOT DISTINCT FROM k.product_id
  AND COALESCE(w.style, '') = COALESCE(k.style, '')
  AND w.wish_item_id > k.wish_item_id;

CREATE UNIQUE INDEX IF NOT EXISTS uq_wishlist_user_product_style
    ON users.wishlist (user_id, product_id, COALESCE(style, ''));
