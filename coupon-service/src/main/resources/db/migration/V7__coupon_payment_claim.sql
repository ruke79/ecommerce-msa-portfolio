-- 결제 중인 주문(payment_order_id · payment_until) — 쿠폰 하나로 두 주문을 동시에 결제하지 못하게.
--
-- 예전에는 쿠폰이 결제 확정 때에야 USED 가 됐다. 같은 손님이 같은 쿠폰으로 주문 둘을 만들면 견적은 둘 다 통과하고(견적은
-- 부수효과가 없다) 둘 다 결제할 수 있었다 — 두 번째 결제 확정에서 경보(CouponDoubleUse)만 울리고 할인은 두 번 나갔다.
-- 이제 결제를 시작할 때(order 의 결제창 열기 · 입금 안내) 그 주문이 쿠폰을 잡는다: payment_until 까지 다른 주문은 결제를 시작하지
-- 못하고, 견적(주문 생성 · 장바구니 적용)도 거절한다. 결제 확정 · 보상(coupon-rollback)이 푼다. 시각이 지나면 저절로 풀린다.
--
-- ROLLBACK-SAFE: 널 허용 열 추가뿐이다. 옛 코드는 이 열을 읽지 않는다(되돌리면 예전처럼 결제 확정 때만 막는다).
-- [JA] 決済中の注文 — クーポン一つで二つの注文を同時に決済できないように。決済開始時にその注文がクーポンを押さえ、
--      payment_until まで他の注文は決済を始められない。決済確定 · 補償が外す。時刻を過ぎれば自然に外れる。

ALTER TABLE coupon.coupon ADD COLUMN IF NOT EXISTS payment_order_id bigint;
ALTER TABLE coupon.coupon ADD COLUMN IF NOT EXISTS payment_until timestamp(6);
