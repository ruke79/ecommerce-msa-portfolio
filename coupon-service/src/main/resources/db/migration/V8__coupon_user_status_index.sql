-- 사용자의 쓰지 않은 쿠폰 조회용 인덱스 (user_id, status).
--
-- /coupons(체크아웃 화면의 쿠폰 고르기)가 매번 findByUserIdAndCouponStatus 로 읽는데, 인덱스는 (name, user_id) 뿐이라 user_id 로
-- 시작하지 못해 표를 훑었다. 가입 쿠폰만으로도 행은 사용자 수만큼 늘어난다.
-- idx_coupon(name, user_id)은 유니크 제약 uk_coupon_name_user_id 의 인덱스와 같은 열이라 지운다(쓰기마다 두 번 갱신했다).
--
-- ROLLBACK-SAFE: 인덱스만 바뀐다. 옛 코드도 같은 조회를 하며, (name, user_id) 조회는 유니크 인덱스가 받는다.
-- [JA] ユーザーの未使用クーポン照会用インデックス。idx_coupon はユニーク制約のインデックスと同じ列なので削除する。

CREATE INDEX IF NOT EXISTS idx_coupon_user_status ON coupon.coupon (user_id, status);
DROP INDEX IF EXISTS coupon.idx_coupon;
