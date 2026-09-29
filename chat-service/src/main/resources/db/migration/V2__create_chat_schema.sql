-- chat-service 의 엔티티는 @Table(schema = "chat") 를 쓰지만, Hibernate 의 ddl-auto 는
-- 테이블은 만들어도 **스키마는 만들지 않는다**. 그래서 빈 DB 에 기동하면
--   Error executing DDL "create table chat.member (...)" [ERROR: schema "chat" does not exist]
-- 가 WARN 으로만 남고 기동은 계속돼, chat.member 가 없는 채로 서비스가 "떠 있는" 상태가 된다
-- (docs/14 B-13 — 기동 로그를 끝까지 읽어야만 보이는 조용한 실패).
-- Flyway 는 Hibernate 보다 먼저 돌므로 여기서 스키마를 선행 생성한다.
CREATE SCHEMA IF NOT EXISTS chat;
