-- docs/09 P1: payment_outbox_event 를 created_at 기준 월 단위 RANGE 파티션으로 전환한다.
--
-- 이 스크립트는 두 상황 모두에서 동작한다.
--  (a) Hibernate ddl-auto가 만들어 둔 기존 테이블이 있는 DB
--      → 기존 데이터를 새 파티션 테이블로 복사하고, 원본은 *_legacy 로 보존한 뒤 교체한다.
--  (b) 빈 DB (신규 환경)
--      → 파티션 테이블을 곧바로 생성한다. 이후 Hibernate(update)는 컬럼이 일치하므로 건드리지 않는다.
--
-- 주의:
--  * PostgreSQL 은 파티션 테이블의 PK 가 파티션 키를 포함할 것을 요구하므로 PK 는 (id, created_at) 복합키다.
--    JPA 엔티티는 여전히 @Id String id 만 사용한다 — Hibernate 의 validate 대상은 컬럼뿐이고,
--    id 는 UUID 라 단독 조회(findById)의 유일성이 실질적으로 유지된다(docs/09 §4.2 옵션 A 의 경량 변형).
--  * Debezium(pgoutput) 사용 시 publication 에 publish_via_partition_root 설정이 필요하다(하단 참조).

CREATE SCHEMA IF NOT EXISTS orders;

-- 1) 파티션 부모 테이블
CREATE TABLE IF NOT EXISTS orders.payment_outbox_event_p (
    id             varchar(255) NOT NULL,
    aggregate_id   varchar(255) NOT NULL,
    aggregate_type varchar(255) NOT NULL,
    event_type     varchar(255) NOT NULL,
    payload        jsonb        NOT NULL,
    outbox_status  varchar(255),
    created_at     timestamp(6) NOT NULL,
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

-- 2) 월 단위 파티션 (약 6개월 선생성). 장기적으로는 pg_partman 으로 자동화할 것 (docs/09 §4.6).
CREATE TABLE IF NOT EXISTS orders.payment_outbox_event_2026_07 PARTITION OF orders.payment_outbox_event_p FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');
CREATE TABLE IF NOT EXISTS orders.payment_outbox_event_2026_08 PARTITION OF orders.payment_outbox_event_p FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');
CREATE TABLE IF NOT EXISTS orders.payment_outbox_event_2026_09 PARTITION OF orders.payment_outbox_event_p FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');
CREATE TABLE IF NOT EXISTS orders.payment_outbox_event_2026_10 PARTITION OF orders.payment_outbox_event_p FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');
CREATE TABLE IF NOT EXISTS orders.payment_outbox_event_2026_11 PARTITION OF orders.payment_outbox_event_p FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');
CREATE TABLE IF NOT EXISTS orders.payment_outbox_event_2026_12 PARTITION OF orders.payment_outbox_event_p FOR VALUES FROM ('2026-12-01') TO ('2027-01-01');
CREATE TABLE IF NOT EXISTS orders.payment_outbox_event_2027_01 PARTITION OF orders.payment_outbox_event_p FOR VALUES FROM ('2027-01-01') TO ('2027-02-01');

-- 3) 경계 밖(과거 데이터 이관분/파티션 선생성 누락 시) INSERT 실패를 막는 안전망 (docs/09 F-11)
CREATE TABLE IF NOT EXISTS orders.payment_outbox_event_default PARTITION OF orders.payment_outbox_event_p DEFAULT;

-- 4) 기존 테이블이 있으면 데이터 이관 후 *_legacy 로 보존
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_tables
               WHERE schemaname = 'orders' AND tablename = 'payment_outbox_event') THEN
        INSERT INTO orders.payment_outbox_event_p
              (id, aggregate_id, aggregate_type, event_type, payload, outbox_status, created_at)
        SELECT id, aggregate_id, aggregate_type, event_type,
               payload::jsonb, outbox_status, created_at
        FROM   orders.payment_outbox_event;

        ALTER TABLE orders.payment_outbox_event RENAME TO payment_outbox_event_legacy;
    END IF;
END $$;

-- 5) 원자적 이름 교체 → 애플리케이션은 아무 변경 없이 파티션 테이블을 사용
ALTER TABLE orders.payment_outbox_event_p RENAME TO payment_outbox_event;

-- 6) 인덱스
--    엔티티(@Index name="idx_payment_outbox_event")와 동일한 이름 → Hibernate update 가 재생성 시도하지 않음
CREATE INDEX IF NOT EXISTS idx_payment_outbox_event
    ON orders.payment_outbox_event (aggregate_id, aggregate_type, event_type);
--    스케줄러 핫 쿼리 findByOutboxStatus(STARTED) 용 부분 인덱스.
--    STARTED 행만 색인하므로 COMPLETED 가 쌓여도 인덱스는 항상 작다 (docs/09 PG-5).
CREATE INDEX IF NOT EXISTS idx_payment_outbox_started
    ON orders.payment_outbox_event (created_at)
    WHERE outbox_status = 'STARTED';

-- 7) Debezium(pgoutput) publication 이 이미 존재하면 파티션 루트 기준으로 이벤트를 내보내게 한다.
--    (publish_via_partition_root=false 상태에서는 자식 파티션 단위 이벤트가 발행되어
--     커넥터의 table.include.list 매칭이 깨진다 — docs/09 PG-6)
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_publication WHERE pubname = 'dbz_publication') THEN
        EXECUTE 'ALTER PUBLICATION dbz_publication SET (publish_via_partition_root = true)';
    END IF;
END $$;

-- 운영 절차 메모:
--  * 릴레이/CDC 정상 동작 검증 후 legacy 정리:  DROP TABLE orders.payment_outbox_event_legacy;
--  * 보존(월 단위 폐기):
--      ALTER TABLE orders.payment_outbox_event DETACH PARTITION orders.payment_outbox_event_2026_07 CONCURRENTLY;
--      DROP TABLE orders.payment_outbox_event_2026_07;
