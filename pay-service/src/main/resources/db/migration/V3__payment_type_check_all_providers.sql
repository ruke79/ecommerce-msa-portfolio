-- 결제수단 CHECK 제약이 세 값(PAYPAL·CREDIT_CARD·CASH)에 얼어 있었다 (docs/49 §8, 가드 B-55).
--
-- 베이스라인(V2)은 hbm2ddl 산출물이라 그 시점의 enum 으로 check 를 박았다. 그 뒤 PaymentType
-- 에 STRIPE·PORTONE 을 더했는데 제약은 그대로였다. Hibernate validate 는 CHECK 를 보지 않으므로
-- 기동은 멀쩡했고, Stripe 결제가 처음 저장되는 순간
--
--     ERROR: new row for relation "payment" violates check constraint "payment_payment_type_check"
--
-- 로 죽었다. 결제 응답이 order-service 로 돌아가지 않아 주문이 영원히 NOT_PROCESSED 였다 —
-- 손님은 돈을 냈고 화면은 "결제 확인됨" 인데 주문은 확정되지 않는 상태.
--
-- 되돌리기는 안전하다 — 허용 범위를 넓히기만 한다. 옛 이미지는 옛 값만 쓴다.
--
-- 제약 이름은 PostgreSQL 이 인라인 check 에 붙이는 <table>_<column>_check 다. 이름이 다를
-- 가능성에 대비해 그 컬럼의 CHECK 를 전부 찾아 지운 뒤, 같은 이름으로 다시 건다.

DO $$
DECLARE
    c record;
BEGIN
    FOR c IN
        SELECT conname
          FROM pg_constraint
         WHERE conrelid = 'pay.payment'::regclass
           AND contype = 'c'
           AND pg_get_constraintdef(oid) ILIKE '%payment_type%'
    LOOP
        EXECUTE format('ALTER TABLE pay.payment DROP CONSTRAINT %I', c.conname);
    END LOOP;
END $$;

ALTER TABLE pay.payment
    ADD CONSTRAINT payment_payment_type_check
    CHECK (payment_type IN ('PAYPAL', 'STRIPE', 'PORTONE', 'CREDIT_CARD', 'CASH'));
