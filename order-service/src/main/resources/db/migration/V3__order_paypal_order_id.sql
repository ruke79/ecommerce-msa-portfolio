-- 결제 재사용(replay) 차단 — docs/35
--
-- 서버가 PayPal 에 조회해 "이 주문은 COMPLETED, 59.98 USD" 를 확인하더라도, 그 결과만으로는
-- 부족하다. 공격자가 59.98 USD 를 **한 번** 정상 결제한 뒤 그 paypalOrderId 를 같은 금액의
-- 다른 주문 열 개에 재사용할 수 있기 때문이다. 금액도 상태도 전부 진짜라 검증을 통과한다.
--
-- 그래서 "한 PayPal 주문은 한 주문에만 쓸 수 있다"를 **DB 유니크 제약으로** 강제한다.
-- 애플리케이션에서 select 후 insert 로 확인하면 동시 요청 둘이 같은 값을 통과시킬 수 있다.
-- 제약은 그 경쟁을 원천적으로 없앤다.
--
-- 부분 인덱스인 이유: 아직 결제되지 않은 주문은 이 값이 NULL 이고, NULL 은 유니크 제약에서
-- 서로 충돌하지 않지만, 부분 인덱스로 두면 인덱스가 결제된 주문만큼만 커진다.

ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS paypal_order_id varchar(255);

CREATE UNIQUE INDEX IF NOT EXISTS uk_order_paypal_order_id
    ON orders.orders (paypal_order_id)
    WHERE paypal_order_id IS NOT NULL;
