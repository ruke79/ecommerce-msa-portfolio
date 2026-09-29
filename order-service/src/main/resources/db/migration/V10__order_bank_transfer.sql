-- 무통장입금 — 결제 API 가 아니라 주문 상태 기계다 (docs/49 §11).
--
--   NOT_PROCESSED ──(입금 안내 받기)──▶ UNPAID ──(관리자 입금 확인)──▶ PAID
--                                        │
--                                        └──(기한 경과 · 손님이 접음)──▶ CANCELED
--
-- 주문에 둘을 남긴다:
--   deposit_deadline  입금 기한. 지나면 만료 잡이 CANCELED 로 접는다(쿠폰 복원)
--   depositor_name    입금자명 — 은행 내역과 주문을 맞추는 실마리
--
-- 계좌 정보는 주문에 두지 않는다. 상점 계좌는 하나이고 설정(BANK_TRANSFER_*)이 갖는다.
-- 추가만 한다. 둘 다 nullable 이라 옛 이미지도 그대로 돈다.

ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS deposit_deadline timestamp;
ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS depositor_name   varchar(50);
