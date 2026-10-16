-- 결제 시도(payment_attempt · payment_attempt_until) — 주문 하나에 살아 있는 결제창을 하나로.
--
-- 예전에는 결제창(PayPal 주문 · 포트원 paymentId · Stripe 세션)을 서버가 기록하지 않아, 두 탭에서 같은 주문의 결제창을 열고
-- 둘 다 결제할 수 있었다 — 두 번째 결제 기록에서 경보(OrderDuplicatePayment)만 울리고 돈은 두 번 나갔다(수동 환불).
-- 이제 결제창을 열기 직전(결제 시작 호출)에 화면이 창마다의 시도 키를 보내고, 그 키가 payment_attempt_until 까지 이 주문의
-- 결제창이다. 다른 키의 시도는 그동안 거절된다. 같은 키(같은 탭 · 새로고침)는 다시 열 수 있다.
--
-- ROLLBACK-SAFE: 널 허용 열 추가뿐이다. 옛 코드는 이 열을 읽지 않는다.
-- [JA] 決済試行 — 注文一つに生きている決済画面を一つに。決済開始時に画面が窓ごとの試行キーを送り、期限までその窓だけが決済できる。

ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS payment_attempt varchar(64);
ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS payment_attempt_until timestamp(6);
