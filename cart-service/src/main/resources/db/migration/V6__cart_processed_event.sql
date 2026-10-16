-- 처리한 이벤트 원장 — cart-empty 를 주문당 한 번만 반영한다(event_id = cart-empty-<orderId>).
--
-- 🐛 예전에는 원장이 없었다. order 의 아웃박스 릴레이 · Kafka 는 at-least-once 라 같은 비우기가 다시 올 수 있는데, 그러면 주문한 줄을
-- 한 번 더 뺐다 — 그 사이 손님이 같은 상품을 다시 담았으면 주문하지 않은 수량까지 사라졌다.
-- [JA] 処理済みイベントの台帳 — cart-empty を注文ごとに一度だけ反映する。以前は再配信で注文していない数量まで抜けた。
--
-- ROLLBACK-SAFE: 테이블 추가뿐이다. 옛 코드는 이 테이블을 읽지 않는다.

CREATE TABLE IF NOT EXISTS cart.processed_event (
    event_id     varchar(100) PRIMARY KEY,
    processed_at timestamp    NOT NULL DEFAULT now()
);
