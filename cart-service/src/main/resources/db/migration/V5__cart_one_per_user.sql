-- 장바구니는 사용자당 하나 — user_id 유니크(docs/62 §18).
--
-- 🐛 예전에는 일반 인덱스뿐이었다. 처음 담기를 두 탭(또는 두 기기)에서 동시에 하면 둘 다 "장바구니 없음" 을 읽고 둘 다 만들었고,
-- 그 뒤로 그 사용자의 findByUserId 는 IncorrectResultSizeDataAccessException — 장바구니 화면 · 담기 · 체크아웃이 전부 실패했다.
-- [JA] カートはユーザーごとに一つ。以前は通常のインデックスだけで、初回の追加が同時に起きると二つ作られ、以後そのユーザーの
--      カート操作がすべて失敗していた。
--
-- 순서: ① 중복을 합친다 → ② 유니크 인덱스. 제약부터 걸면 이미 있는 중복 때문에 이 마이그레이션이 실패한다.
--
-- 합치는 규칙 — 가장 최근에 바뀐 장바구니를 남기고, 다른 장바구니의 줄은 남길 장바구니에 같은 줄(상품 · 색상 · 사이즈)이 없을 때만
-- 옮긴다(같은 줄이 여럿이면 가장 최근 것 하나). 나머지 줄과 장바구니는 지운다. 남긴 장바구니의 합계는 다시 계산한다.
-- 중복이 없는 DB(대부분)에서는 아무것도 바꾸지 않는다.
--
-- 되돌리기: 이미지만 되돌려도 된다 — 옛 코드는 유니크 인덱스가 있어도 그대로 동작한다(동시에 처음 담기면 한쪽이 실패할 뿐).
-- 지운 중복 행은 돌아오지 않지만, 그 행들은 이미 장바구니를 읽을 수 없게 만들던 것이다.

CREATE TEMP TABLE cart_dup ON COMMIT DROP AS
WITH ranked AS (
    SELECT cart_id, user_id,
           row_number() OVER (PARTITION BY user_id ORDER BY updated_at DESC NULLS LAST, cart_id DESC) AS rn
      FROM cart.cart
     WHERE user_id IS NOT NULL
)
SELECT d.cart_id, k.cart_id AS keep_id
  FROM ranked d
  JOIN ranked k ON k.user_id = d.user_id AND k.rn = 1
 WHERE d.rn > 1;

UPDATE cart.cart_product cp
   SET cart_id = m.keep_id
  FROM (
        SELECT DISTINCT ON (d.keep_id, l.product_id, l.color_id, l.size)
               l.cartproduct_id, d.keep_id
          FROM cart.cart_product l
          JOIN cart_dup d ON d.cart_id = l.cart_id
         WHERE NOT EXISTS (
                SELECT 1 FROM cart.cart_product k
                 WHERE k.cart_id = d.keep_id
                   AND k.product_id IS NOT DISTINCT FROM l.product_id
                   AND k.color_id IS NOT DISTINCT FROM l.color_id
                   AND k.size IS NOT DISTINCT FROM l.size)
         ORDER BY d.keep_id, l.product_id, l.color_id, l.size, l.updated_at DESC NULLS LAST, l.cartproduct_id DESC
       ) m
 WHERE cp.cartproduct_id = m.cartproduct_id;

DELETE FROM cart.cart_product WHERE cart_id IN (SELECT cart_id FROM cart_dup);
DELETE FROM cart.cart WHERE cart_id IN (SELECT cart_id FROM cart_dup);

-- 합계는 고른 줄로(CartService.computeCartTotal 과 같은 식). 쿠폰 적용액은 바뀐 장바구니에 맞지 않으니 비운다.
UPDATE cart.cart c
   SET cart_total = (SELECT coalesce(sum(p.price * p.qty + coalesce(p.shipping, 0)), 0)
                       FROM cart.cart_product p
                      WHERE p.cart_id = c.cart_id AND p.selected AND p.product_id IS NOT NULL),
       total_after_discount = 0
 WHERE c.cart_id IN (SELECT DISTINCT keep_id FROM cart_dup);

CREATE UNIQUE INDEX IF NOT EXISTS uq_cart_user ON cart.cart (user_id);
-- 유니크 인덱스가 같은 열을 덮는다.
DROP INDEX IF EXISTS cart.idx_cart_user;
