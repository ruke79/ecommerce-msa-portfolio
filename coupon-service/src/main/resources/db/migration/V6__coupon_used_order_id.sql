-- 쿠폰을 쓴 주문을 적는다(used_order_id).
--
-- 예전에는 "쓰였다(USED)" 만 있고 어느 주문이 썼는지가 없었다. 사가 보상(coupon-rollback)은 이름 · 사용자로 USED 쿠폰을
-- 찾아 무조건 되돌렸다 — 결제가 실패한 다른 주문의 보상이 이미 결제된 주문의 쿠폰을 되살려, 같은 쿠폰을 또 쓸 수 있었다.
-- 이제 결제 확정 때 주문 id 를 적고, 보상은 그 주문의 것일 때만 되돌린다.
--
-- 기존 USED 행은 NULL — 어느 주문이 썼는지 모르므로 예전처럼 보상을 받는다(CouponService.couponRollback).
--
-- ROLLBACK-SAFE: 널 허용 컬럼 추가뿐이다. 옛 코드는 이 컬럼을 읽지 않는다.
-- [JA] クーポンを使った注文を記録する。以前は補償が別注文の決済済みクーポンを戻していた。

ALTER TABLE coupon.coupon ADD COLUMN IF NOT EXISTS used_order_id bigint;
