-- 결제수단을 여러 개로 — PayPal 전용 컬럼을 제공자 중립으로 넓힌다.
--
-- 왜 rename 하지 않는가 (expand-contract)
-- ----------------------------------------
-- paypal_order_id 를 provider_payment_id 로 **이름만 바꾸면** 되돌리기가 깨진다. 이 배포를
-- 롤백하면 옛 이미지의 엔티티가 paypal_order_id 를 찾다가 Hibernate validate 에서 기동
-- 실패한다(B-54). 게다가 이 컬럼에는 유니크 인덱스가 걸려 있어 더 위험하다.
--
-- 그래서 넓히기(expand)만 한다:
--   1) 새 컬럼 둘을 추가하고
--   2) 기존 행을 백필하고
--   3) **옛 컬럼은 그대로 둔다** — 다음 릴리스에서 아무도 읽지 않는 것을 확인한 뒤 지운다.
-- ops/32-db-migration.sh 의 expand-contract 설명과 같은 방식이다.
--
-- 이 배포 동안 두 컬럼이 같이 채워진다(코드가 둘 다 쓴다). 롤백해도 옛 코드가 읽는
-- paypal_order_id 가 살아 있으므로 PayPal 결제는 그대로 동작한다.

ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS payment_provider varchar(20);
ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS provider_payment_id varchar(255);

-- 기존 결제는 전부 PayPal 이다. 이 컬럼이 생기기 전에는 그것뿐이었다.
UPDATE orders.orders
   SET payment_provider    = 'PAYPAL',
       provider_payment_id = paypal_order_id
 WHERE paypal_order_id IS NOT NULL
   AND provider_payment_id IS NULL;

-- 재사용(replay) 차단 — V3 와 같은 이유, 같은 모양.
--
-- 🔴 제공자를 **키에 포함한다.** provider_payment_id 만으로 유니크를 걸면 서로 다른
--    제공자가 우연히 같은 형식의 id 를 쓸 때 정상 결제가 거부된다. 반대로 제공자를
--    빼먹으면 "PayPal 의 X" 와 "Stripe 의 X" 가 같은 결제로 취급된다.
--
-- 부분 인덱스인 이유는 V3 와 같다 — 아직 결제되지 않은 주문은 NULL 이고, 인덱스가
-- 결제된 주문만큼만 커진다.
CREATE UNIQUE INDEX IF NOT EXISTS uk_order_provider_payment
    ON orders.orders (payment_provider, provider_payment_id)
    WHERE provider_payment_id IS NOT NULL;
