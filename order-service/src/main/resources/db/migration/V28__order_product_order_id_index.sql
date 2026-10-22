-- 주문 줄 → 주문 외래 키(order_product.order_id)의 인덱스 — 주문 쪽에서 하나뿐이던 인덱스 없는 외래 키.
--
-- 2026-10-20 주문 이력 생성기 시험(ops/tools/generate-demo-orders.py, docs/83 §2.4 — 주문 20만 · 줄 40만):
--   ① 주문 상세 · 내 주문 목록이 줄을 order_id 로 읽을 때마다 표 전체를 훑었다(Parallel Seq Scan 27ms — 줄 수에 비례).
--   ② 주문을 지울 때 Postgres 가 지운 주문마다 "아직 가리키는 줄이 있나" 를 물어 같은 표 전체를 훑었다 — 주문 2만 개 삭제 9분 20초
--      (catalog V22 와 같은 결함). 관리자의 결제 전 주문 폐기(discardUnplaced)도 같은 검사를 한다.
-- 운영의 주문 수에서는 아직 작지만 주문이 쌓이는 만큼 상세가 느려진다(ForeignKeyIndexMigrationTest — 인덱스 없는 외래 키가 남으면 실패).
--
-- ROLLBACK-SAFE: 인덱스만 더한다. 옛 코드도 그대로 돈다. 쓰기는 인덱스를 만드는 동안만 기다린다(운영 주문 수에서는 1초 안).
-- [JA] 注文明細 → 注文の外部キーのインデックス。注文詳細が明細を order_id で読むたびに全件走査し、注文の削除は行ごとの外部キー検査で
--      明細表全体を走査した(2 万件の削除に 9 分 20 秒)。

CREATE INDEX IF NOT EXISTS idx_order_product_order_id ON orders.order_product (order_id);
