-- 멱등 원장 정리용 인덱스 — coupon.processed_event (processed_at).
--
-- V3 이 "주기적으로 정리해도 된다" 고만 적고 정리하는 곳이 없어, 쿠폰을 쓴 결제마다 한 줄씩 영구히 쌓였다.
-- ProcessedEventCleanup 이 매일 보존 기간(기본 30일, 22일 아래로는 안 내려간다)이 지난 행을 조금씩 지운다 — 그 조회가 표를 훑지 않게 한다.
--
-- ROLLBACK-SAFE: 인덱스만 더한다. 옛 코드는 이 인덱스를 쓰지 않고 행도 그대로다.
-- [JA] 冪等台帳の掃除用インデックス。V3 は「定期的に掃除してよい」と書くだけで掃除する所がなく、行が永久に溜まっていた。

CREATE INDEX IF NOT EXISTS idx_processed_event_processed_at ON coupon.processed_event (processed_at);
