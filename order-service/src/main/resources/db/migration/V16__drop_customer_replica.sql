-- 고객 사본 테이블을 지운다 (docs/48 §6-①, 전환 정리).
--
-- order-service 는 요청자 신원을 검증된 토큰의 uid(User-Id)로 읽고, 고객 사본(Customer 엔티티·user-created 구독·
-- 주문 시점 백필)을 이미 지웠다. 테이블은 그 배포를 되돌릴 때 옛 이미지가 읽도록 한 릴리스 남겨 두었다
-- (expand-contract). 이제 지운다. 다른 테이블이 참조하지 않는다(orders.orders.customer_id 는 외래 키가 아니다).
-- 이메일·이름·닉네임의 복제본이 사라진다 — 원본은 user-service 에 있다.
--
-- ROLLBACK-UNSAFE: orders.customer 를 지운다. 고객 사본을 쓰던 이미지(docs/48 §6-① 이전)는 Customer 엔티티가
--   있어 Hibernate validate 에서 기동하지 못한다. 그 이미지로 되돌려야 한다면 V2 의 CREATE TABLE orders.customer 를
--   다시 적용하고, 행은 옛 코드의 주문 시점 백필이 user-service 에서 다시 채운다.

DROP TABLE IF EXISTS orders.customer;
