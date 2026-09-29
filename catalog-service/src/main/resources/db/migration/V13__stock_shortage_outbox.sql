-- 재고 부족 기록 — 결제된 주문의 일부를 보낼 수 없을 때 order 에 알리는 아웃박스 (docs/53 §초과 판매).
--
-- 재고는 결제 뒤에 차감한다. 마지막 1개를 두 사람이 동시에 결제하면 뒤의 차감이 모자란다. 예전에는 로그 한 줄로
-- 끝났고 주문은 결제 완료로 진행됐다 — 손님은 돈을 냈는데 물건은 없고, 아무도 몰랐다. 이제 모자란 수를 이 표에
-- **재고 차감과 같은 트랜잭션**으로 적고, 발행기가 stock-shortage 토픽으로 내보내면 order 가 그만큼 자동 환불한다.
--
-- 왜 커밋 뒤 바로 보내지 않고 표에 적나: 돈이 걸린 알림이다. 커밋 뒤 발행이 실패하면(브로커 중단, 파드 종료)
-- 알림이 사라지고, 손님은 환불받지 못한다. 표에 적으면 발행이 실패해도 다음 주기에 다시 보낸다. order 는
-- event_id 로 중복을 버린다(여러 파드가 같은 행을 보내도 된다).
--
-- 되돌리기 안전: 새 표만 만든다. 옛 이미지는 이 표를 모른다.

CREATE TABLE IF NOT EXISTS product.stock_shortage (
    event_id     varchar(80)  PRIMARY KEY,          -- shortage-order-<주문 id>. 차감 이벤트(order-<id>)당 하나
    order_id     bigint       NOT NULL,
    payload      text         NOT NULL,             -- order 에 보내는 JSON 그대로
    created_at   timestamp(6) NOT NULL,
    published_at timestamp(6)                       -- null = 아직 못 보냄
);

-- 발행기가 찾는 것은 "아직 못 보낸 것" 뿐이다 — 보낸 행은 인덱스에 들어가지 않는다.
CREATE INDEX IF NOT EXISTS idx_stock_shortage_unpublished
    ON product.stock_shortage (created_at)
    WHERE published_at IS NULL;
