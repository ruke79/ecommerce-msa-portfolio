-- 처리한 이벤트 원장 — 배송 상태(delivery-status)를 배송 건 · 상태마다 한 번만 반영한다(event_id = delivery-status:<deliveryId>:<status>).
--
-- 🐛 예전에는 원장이 없었다. 배송 상태 반영은 "앞으로만 간다" 로 재전달을 견뎠지만 하나의 예외 — 배송 취소(CANCELLED → 주문을 PAID 로
-- 되돌림) — 는 그 규칙 밖이었다. 관리자가 배송을 다시 만들어 주문이 다시 PROCESSING 이 된 뒤 옛 배송의 취소가 다시 오면(재전달 · 새 그룹 ·
-- 새 Kafka 클러스터 — 오프셋 기본값 earliest, B-110 · docs/89 §2-1) 주문이 PAID 로 돌아갔다.
-- [JA] 処理済みイベントの台帳 — 配送状態を配送 · 状態ごとに一度だけ反映する。以前は配送キャンセル(PAID に戻す)の再配信で、配送を作り直した注文が戻った。
--
-- 정리: ProcessedEventCleanup(매일 04:55, 30일 — 22일 아래로는 안 내려간다, docs/12 §2).
-- ROLLBACK-SAFE: 테이블 · 인덱스 추가뿐이다. 옛 코드는 이 테이블을 읽지 않는다.

CREATE TABLE IF NOT EXISTS orders.processed_event (
    event_id     varchar(100) PRIMARY KEY,
    processed_at timestamp    NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_processed_event_processed_at ON orders.processed_event (processed_at);
