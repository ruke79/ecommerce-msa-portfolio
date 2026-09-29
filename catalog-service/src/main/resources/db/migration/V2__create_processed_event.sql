-- docs/11 §4 (G8): 컨슈머 멱등성 처리 이력 테이블.
-- product-update 이벤트의 eventId("order-<orderId>")를 재고 변경과 같은 트랜잭션으로 기록해,
-- Kafka 재전달/사가 재실행 시 재고 이중 차감을 방지한다.
CREATE SCHEMA IF NOT EXISTS product;

CREATE TABLE IF NOT EXISTS product.processed_event (
    event_id     varchar(64)  NOT NULL,
    processed_at timestamp(6) NOT NULL,
    PRIMARY KEY (event_id)
);

-- 보존: 오래된 이력은 주기적으로 정리해도 된다(재전달 윈도우보다 훨씬 길게, 예: 30일).
--   DELETE FROM product.processed_event WHERE processed_at < now() - interval '30 days';
