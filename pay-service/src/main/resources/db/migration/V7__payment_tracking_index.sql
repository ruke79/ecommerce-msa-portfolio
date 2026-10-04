-- 결제를 추적 id 로 찾는 인덱스(docs/62 §18).
--
-- 🐛 결제 요청 · 응답마다 findByTrackingId 로 결제 행을 찾는데, tracking_id 로 시작하는 인덱스가 없었다 — 둘 다 order_id 로
-- 시작한다(유니크 (order_id, tracking_id), idx_payment (order_id, tracking_id, customer_id)). 그래서 매번 표 전체를 읽었다.
-- idx_payment 는 유니크 제약의 인덱스와 앞부분이 같아 하는 일이 없었다 — 지운다(인덱스라 되돌리기와 무관하다).
-- [JA] tracking_id で始まるインデックスがなく、決済のたびに全件走査していた。重複していた idx_payment は削除する。
CREATE INDEX IF NOT EXISTS idx_payment_tracking ON pay.payment (tracking_id);
DROP INDEX IF EXISTS pay.idx_payment;
