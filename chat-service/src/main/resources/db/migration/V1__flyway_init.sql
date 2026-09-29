-- Flyway 도입 기준점 (docs/09 P0).
-- 이 서비스는 아직 파티셔닝 대상 테이블이 없어(또는 후순위여서) 구조 변경 없이
-- 마이그레이션 이력만 시작한다. 이후 모든 DDL 변경은 V2__*.sql 부터 이 디렉터리로만 추가한다.
-- (기존 DB는 spring.flyway.baseline-on-migrate 에 의해 버전 0으로 기준선이 잡힌 뒤 V1이 적용된다)
SELECT 1;
