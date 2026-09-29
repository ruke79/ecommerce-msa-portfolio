-- 부분 환불 · 부분 반품 (docs/49 §10).
--
-- 품목마다 네 수량을 센다:
--   refunded_qty   돈이 돌아간 수
--   returned_qty   물건이 돌아온 수 — 발송 전에 환불된 수량도 여기 든다(창고를 떠나지 않았다)
--   restocked_qty  재고로 되돌린 수 (catalog 에 복원 이벤트를 보낸 수)
--   discarded_qty  파손 등으로 재고에 넣지 않기로 한 수
-- 아직 재고로 갈 수 있는 수 = returned − restocked − discarded.
--
-- 주문에는 누적 환불액 둘(상점 통화 · 청구 통화)과, 환불 한 건 한 건의 기록(order_refund)이
-- 생긴다. Order.refund_id 는 이제 "마지막 환불 참조" 다.
--
-- 추가만 한다. 기본값이 있어 옛 이미지의 INSERT 도 통과한다.
--
-- 백필: 이 컬럼이 생기기 전의 환불은 전부 전액이었다. 그 주문은 "전 수량 환불" 이고,
-- stock_restored_at 이 있으면 전 수량이 재고로 돌아갔고, return_received_at 만 있으면
-- (재고 복원 없이 입고) 전 수량이 폐기다. 그래야 새 코드의 "남은 수량" 이 0 이 되어
-- 옛 주문에 환불·입고를 또 하지 못한다.

ALTER TABLE orders.order_product ADD COLUMN IF NOT EXISTS refunded_qty  integer NOT NULL DEFAULT 0;
ALTER TABLE orders.order_product ADD COLUMN IF NOT EXISTS returned_qty  integer NOT NULL DEFAULT 0;
ALTER TABLE orders.order_product ADD COLUMN IF NOT EXISTS restocked_qty integer NOT NULL DEFAULT 0;
ALTER TABLE orders.order_product ADD COLUMN IF NOT EXISTS discarded_qty integer NOT NULL DEFAULT 0;

ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS refunded_amount         numeric(19,2) NOT NULL DEFAULT 0;
ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS refunded_charged_amount numeric(19,2) NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS orders.order_refund (
    order_refund_id bigint        NOT NULL,
    order_id        bigint        NOT NULL,
    refund_ref      varchar(255),
    amount          numeric(19,2) NOT NULL,
    charged_amount  numeric(19,2) NOT NULL,
    currency        varchar(3),
    reason          varchar(200),
    items           text,
    version         bigint,
    created_at      timestamp(6),
    created_by      varchar(255),
    updated_at      timestamp(6),
    updated_by      varchar(255),
    CONSTRAINT pk_order_refund PRIMARY KEY (order_refund_id),
    CONSTRAINT fk_order_refund_order FOREIGN KEY (order_id) REFERENCES orders.orders (order_id)
);
CREATE INDEX IF NOT EXISTS idx_order_refund_order ON orders.order_refund (order_id);

UPDATE orders.order_product p SET refunded_qty = p.qty
  FROM orders.orders o
 WHERE p.order_id = o.order_id AND o.order_status = 'REFUNDED' AND p.refunded_qty = 0;

UPDATE orders.order_product p SET returned_qty = p.qty, restocked_qty = p.qty
  FROM orders.orders o
 WHERE p.order_id = o.order_id AND o.stock_restored_at IS NOT NULL AND p.restocked_qty = 0;

UPDATE orders.order_product p SET returned_qty = p.qty, discarded_qty = p.qty
  FROM orders.orders o
 WHERE p.order_id = o.order_id AND o.return_received_at IS NOT NULL
   AND o.stock_restored_at IS NULL AND p.returned_qty = 0;

UPDATE orders.orders
   SET refunded_amount = total,
       refunded_charged_amount = COALESCE(charged_amount, total)
 WHERE order_status = 'REFUNDED' AND refunded_amount = 0;
