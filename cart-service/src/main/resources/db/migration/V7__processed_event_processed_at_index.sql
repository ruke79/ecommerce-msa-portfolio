-- 멱등 원장 정리용 인덱스 — cart.processed_event (processed_at).
--
-- V6 의 원장은 주문마다 cart-empty 한 줄을 남기는데 정리하는 곳이 없었다. ProcessedEventCleanup 이 매일 보존 기간(기본 30일,
-- 22일 아래로는 안 내려간다)이 지난 행을 조금씩 지운다 — 그 조회가 표를 훑지 않게 한다.
--
-- ROLLBACK-SAFE: 인덱스만 더한다. 옛 코드는 이 인덱스를 쓰지 않고 행도 그대로다.
-- [JA] 冪等台帳の掃除用インデックス。V6 の台帳は注文ごとに cart-empty 一行を残すが、掃除する所がなかった。

CREATE INDEX IF NOT EXISTS idx_processed_event_processed_at ON cart.processed_event (processed_at);
