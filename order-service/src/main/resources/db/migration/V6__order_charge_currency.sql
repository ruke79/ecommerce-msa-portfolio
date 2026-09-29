-- 결제수단마다 다른 통화 — 주문에 "그때 청구한 통화·금액·환율" 을 남긴다.
--
-- total 은 그대로 상점 통화(USD)다. 매출 집계·pay-service·구매 이력이 전부 그 값을 보므로
-- 바꾸지 않는다. 여기 추가하는 셋은 **제공자에게 실제로 요청한 청구**다:
--   currency        청구 통화 (KRW · JPY · USD …)
--   charged_amount  청구 금액 — 그 통화의 자릿수로 반올림된 값
--   fx_rate         주문 생성 시점의 환율(상점 통화 1 단위 = 청구 통화 얼마)
--
-- 세 컬럼 다 nullable 이고 백필하지 않는다. 옛 주문은 null 이고, 코드는 null 을
-- "상점 통화로 total 만큼" 으로 읽는다 — 그게 이 컬럼이 생기기 전의 의미 그대로다.
-- 추가만 하므로 되돌려도 옛 코드가 깨지지 않는다.

ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS currency       varchar(3);
ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS charged_amount numeric(19,2);
ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS fx_rate        numeric(19,6);
