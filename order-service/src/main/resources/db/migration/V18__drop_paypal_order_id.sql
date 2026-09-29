-- 옛 PayPal 전용 컬럼을 지운다 — 옛 컬럼 정리의 2단계 (docs/49 §3).
--
-- 1단계(V17 과 같은 릴리스)에서 엔티티가 이 컬럼을 매핑하지 않게 됐고, V17 이 남은 값을 provider_payment_id 로 옮겼다.
-- 재사용 차단은 (payment_provider, provider_payment_id) 부분 유니크(V4)가 맡는다.
--
-- 🔴 1단계를 건너뛰면 안 된다 — 이 마이그레이션이 스스로 확인한다. 1단계 이전 이미지는 이 컬럼을 매핑하므로, 롤링 중
--   그 파드가 아직 돌 때 컬럼이 사라지면 그 파드의 주문 조회가 전부 실패한다. V17 이 **이번 실행에서 막 적용됐다**면
--   (= 1단계와 이 단계를 한 배포로 올렸다) 지우지 않고 실패한다. PostgreSQL 은 DDL 도 트랜잭션이라 이 파일은 아무것도 바꾸지
--   않고(V17 은 이미 커밋됐다 — 그것은 안전하다), 새 파드가 준비되지 못해 옛 파드가 계속 돈다. 1단계가 다 굴러간 뒤
--   (30분 뒤) 다시 배포하면 통과한다. 새 DB(모든 마이그레이션이 한 번에 도는 경우)는 옛 파드가 없으므로 통과시킨다.
--
-- ROLLBACK-UNSAFE: orders.orders.paypal_order_id 를 지운다. 이 컬럼을 매핑하는 이미지(V17 이전)는 Hibernate validate 에서
--   기동하지 못한다. 되돌려야 한다면 컬럼을 다시 만들고(ALTER TABLE orders.orders ADD COLUMN paypal_order_id varchar(255))
--   PayPal 행을 채운다(UPDATE ... SET paypal_order_id = provider_payment_id WHERE payment_provider = 'PAYPAL').

DO $$
DECLARE
    v17   timestamp;
    first timestamp;
BEGIN
    IF to_regclass('flyway_schema_history') IS NOT NULL THEN
        SELECT installed_on INTO v17 FROM flyway_schema_history WHERE version = '17' AND success;
        SELECT min(installed_on) INTO first FROM flyway_schema_history WHERE success;
        IF v17 IS NOT NULL
           AND v17 > first + interval '10 minutes'
           AND v17 > localtimestamp - interval '30 minutes' THEN
            RAISE EXCEPTION 'V17 이 방금(%) 적용됐다 — 옛 컬럼 정리 1단계를 먼저 배포하고 다 굴러간 뒤(30분) 다시 배포할 것 (docs/49 §3)', v17;
        END IF;
    END IF;
END $$;

DROP INDEX IF EXISTS orders.uk_order_paypal_order_id;
ALTER TABLE orders.orders DROP COLUMN IF EXISTS paypal_order_id;
