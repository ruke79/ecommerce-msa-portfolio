-- chat-service 와 같은 문제(docs/14 B-13). coupon-service 엔티티는 @Table(schema = "coupon")
-- 을 쓰는데 Hibernate 는 스키마를 만들지 않으므로, 빈 DB 에서는 테이블 생성이 WARN 만 남기고
-- 실패한 채 서비스가 기동된다. Flyway 가 먼저 스키마를 만들어 준다.
CREATE SCHEMA IF NOT EXISTS coupon;
