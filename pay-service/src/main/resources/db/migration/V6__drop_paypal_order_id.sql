-- 옛 PayPal 전용 컬럼을 지운다 — pay 의 제공자 중립 전환 2단계 (docs/49 §3).
--
-- 1단계(V5)에서 엔티티가 새 컬럼(payment_provider·provider_payment_id)만 매핑하게 됐다. 그런데 그 배포의 롤링 중 옛 파드가
-- 쓴 행은 paypal_order_id 에만 값이 있다 — 먼저 한 번 더 옮기고 지운다. 컬럼이 사라지면 idx_payment 도 함께 사라지므로
-- 옛 컬럼 없이 다시 만든다.
--
-- 🔴 1단계를 건너뛰면 안 된다 — 이 마이그레이션이 스스로 확인한다(order V18 과 같은 방식). V5 가 이번 실행에서 막 적용됐다면
--   지우지 않고 실패한다 — 롤링 중 옛 파드가 이 컬럼을 매핑하고 있어서다. 새 DB 는 통과시킨다.
--
-- ROLLBACK-UNSAFE: pay.payment.paypal_order_id 를 지운다. 이 컬럼을 매핑하는 이미지(V5 이전)는 Hibernate validate 에서
--   기동하지 못한다. 되돌려야 한다면 컬럼을 다시 만들고 PayPal 행을 provider_payment_id 에서 채운다.

DO $$
DECLARE
    v5    timestamp;
    first timestamp;
BEGIN
    IF to_regclass('flyway_schema_history') IS NOT NULL THEN
        SELECT installed_on INTO v5 FROM flyway_schema_history WHERE version = '5' AND success;
        SELECT min(installed_on) INTO first FROM flyway_schema_history WHERE success;
        IF v5 IS NOT NULL
           AND v5 > first + interval '10 minutes'
           AND v5 > localtimestamp - interval '30 minutes' THEN
            RAISE EXCEPTION 'V5 가 방금(%) 적용됐다 — 제공자 중립 전환 1단계를 먼저 배포하고 다 굴러간 뒤(30분) 다시 배포할 것 (docs/49 §3)', v5;
        END IF;
    END IF;
END $$;

-- 1단계의 롤링 중 옛 파드가 쓴 행.
UPDATE pay.payment
   SET payment_provider    = COALESCE(payment_provider, 'PAYPAL'),
       provider_payment_id = paypal_order_id
 WHERE paypal_order_id IS NOT NULL
   AND provider_payment_id IS NULL;

ALTER TABLE pay.payment DROP COLUMN IF EXISTS paypal_order_id;
CREATE INDEX IF NOT EXISTS idx_payment ON pay.payment (order_id, tracking_id, customer_id);
