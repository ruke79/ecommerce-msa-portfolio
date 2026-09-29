-- notification 스키마 생성. 테이블은 V2 베이스라인이 만든다(docs/20).
--
-- 스키마를 마이그레이션이 먼저 만드는 이유: Hibernate 의 ddl-auto 는 validate 이므로
-- 스키마를 만들지 않는다. 이게 없으면 베이스라인의 CREATE TABLE 이
-- 'schema "notification" does not exist' 로 실패한다.
CREATE SCHEMA IF NOT EXISTS notification;
