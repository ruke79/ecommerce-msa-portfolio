-- 결제 참조를 제공자 중립으로 넓힌다 — order-service V4 와 같은 일을 pay 에서 (docs/49 §3).
--
-- pay.payment 는 PayPal 참조만 paypal_order_id 에 적었다. Stripe·포트원·무통장 결제는 "어느 결제인가" 가 이 서비스에
-- 남지 않았다(결제 요청의 Avro 스키마에 제공자 필드가 없어 전해지지도 않았다 — v3 에서 더했다).
--
-- 넓히기(expand) + 값 이관:
--   1) 새 컬럼 둘을 더하고
--   2) payment_type 에서 제공자를 정한다 — order 의 paymentTypeOf 와 1:1 이다(CASH = BANK_TRANSFER, CREDIT_CARD 는 대응 없음)
--   3) paypal_order_id 를 provider_payment_id 로 옮긴다
--
-- 옛 컬럼은 **여기서 지우지 않는다.** 새 엔티티는 매핑하지 않지만 롤링 중 옛 파드는 매핑하고, 그 파드가 쓰는 행은
-- paypal_order_id 에만 값이 있다. 다음 릴리스에서 한 번 더 옮긴 뒤 지운다(SQL 은 docs/49 §3).
--
-- 되돌리기 안전: 컬럼을 더하고 값을 복사할 뿐이다. 옛 이미지는 새 컬럼을 모르고, paypal_order_id 로 로직을 판단하지 않는다
-- (저장만 했다). CHECK 제약은 걸지 않는다 — 제공자를 더할 때 제약이 얼어붙은 적이 있다(V3, 가드 B-55).

ALTER TABLE pay.payment ADD COLUMN IF NOT EXISTS payment_provider varchar(20);
ALTER TABLE pay.payment ADD COLUMN IF NOT EXISTS provider_payment_id varchar(255);

UPDATE pay.payment
   SET payment_provider = CASE payment_type
                              WHEN 'PAYPAL'  THEN 'PAYPAL'
                              WHEN 'STRIPE'  THEN 'STRIPE'
                              WHEN 'PORTONE' THEN 'PORTONE'
                              WHEN 'CASH'    THEN 'BANK_TRANSFER'
                          END
 WHERE payment_provider IS NULL
   AND payment_type IN ('PAYPAL', 'STRIPE', 'PORTONE', 'CASH');

UPDATE pay.payment
   SET payment_provider    = COALESCE(payment_provider, 'PAYPAL'),
       provider_payment_id = paypal_order_id
 WHERE paypal_order_id IS NOT NULL
   AND provider_payment_id IS NULL;

-- 제공자 참조로 결제를 찾는다(문의·대사). 유니크는 걸지 않는다 — 옛 데이터를 검증하지 않은 채 걸면 마이그레이션이
-- 배포를 막을 수 있고, 같은 결제가 두 주문에 붙는 것은 order 의 유니크(V4)가 이미 막는다.
CREATE INDEX IF NOT EXISTS idx_payment_provider_ref
    ON pay.payment (payment_provider, provider_payment_id)
    WHERE provider_payment_id IS NOT NULL;
