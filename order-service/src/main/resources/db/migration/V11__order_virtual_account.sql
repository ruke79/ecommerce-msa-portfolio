-- 포트원 가상계좌 (docs/49 §11) — 무통장입금 상태 기계의 "입금 안내" 를 PG 가 만든 경우.
--
--   vbank_bank · vbank_account · vbank_holder   손님에게 보여 줄 계좌(설정의 상점 계좌 대신)
--   pending_payment_ref                          발급된 결제의 참조(imp_uid · paymentId).
--                                                입금 전 취소 때 PG 의 계좌도 닫는 데 쓴다
--
-- 입금 확인은 관리자가 아니라 포트원 결제 완료 웹훅이 한다 — 그 뒤는 카드와 같다.
-- 추가만 한다. 전부 nullable.

ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS vbank_bank          varchar(50);
ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS vbank_account       varchar(64);
ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS vbank_holder        varchar(50);
ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS pending_payment_ref varchar(255);
