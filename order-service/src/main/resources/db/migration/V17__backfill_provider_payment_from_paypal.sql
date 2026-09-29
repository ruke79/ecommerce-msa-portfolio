-- 옛 PayPal 전용 컬럼(paypal_order_id)의 값을 제공자 중립 컬럼으로 옮긴다 — 옛 컬럼 정리의 1단계 (docs/49 §3).
--
-- V4 가 한 번 옮겼지만, 그 배포가 굴러가는 동안 V4 이전 이미지가 반영한 결제는 paypal_order_id 에만 값이 남았을 수
-- 있다. 그래서 지금까지 코드가 두 컬럼을 함께 봤다(OrderDataService.recordPayment 의 멱등 검사, 결제된 주문 취소 거절).
-- 이 릴리스부터 코드는 새 컬럼만 본다 — 그 전에 남은 값을 옮겨 둔다. 멱등하다(이미 옮긴 행은 건드리지 않는다).
--
-- 컬럼은 **여기서 지우지 않는다.** 롤링 중에는 옛 파드(엔티티가 paypal_order_id 를 매핑한다)가 잠시 함께 돌고, 컬럼이
-- 없으면 그 파드의 주문 조회가 전부 실패한다. 이 릴리스가 안정된 뒤 다음 릴리스에서 컬럼·인덱스(uk_order_paypal_order_id)를
-- 지운다 — SQL 은 docs/49 §3 에 있다.
--
-- 되돌리기 안전: 값을 복사할 뿐이고, 옛 이미지는 두 컬럼을 다 보므로 이 릴리스가 새 컬럼에만 쓴 결제도 알아본다.
-- 유니크 충돌 없음: paypal_order_id 는 V3 의 부분 유니크라 값마다 한 행이고, 새 컬럼에 같은 값을 가진 다른 PAYPAL 행은
-- 두 컬럼을 늘 같이 채운 코드만 만들었다(같은 행).

UPDATE orders.orders
   SET payment_provider    = COALESCE(payment_provider, 'PAYPAL'),
       provider_payment_id = paypal_order_id
 WHERE paypal_order_id IS NOT NULL
   AND provider_payment_id IS NULL;
