-- docs/09 P1: users.outbox_event 를 created_at 기준 월 단위 RANGE 파티션으로 전환한다.
-- 동작 방식/주의사항은 order-service 의 V1__partition_payment_outbox_event.sql 주석 참조.
-- 참고: user-service 의 OutboxEvent 엔티티에는 outbox_status 컬럼이 없다(다른 서비스와 구조가 다름).

CREATE SCHEMA IF NOT EXISTS users;

CREATE TABLE IF NOT EXISTS users.outbox_event_p (
    id             varchar(255) NOT NULL,
    aggregate_id   varchar(255) NOT NULL,
    aggregate_type varchar(255) NOT NULL,
    event_type     varchar(255) NOT NULL,
    payload        jsonb        NOT NULL,
    created_at     timestamp(6) NOT NULL,
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

CREATE TABLE IF NOT EXISTS users.outbox_event_2026_07 PARTITION OF users.outbox_event_p FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');
CREATE TABLE IF NOT EXISTS users.outbox_event_2026_08 PARTITION OF users.outbox_event_p FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');
CREATE TABLE IF NOT EXISTS users.outbox_event_2026_09 PARTITION OF users.outbox_event_p FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');
CREATE TABLE IF NOT EXISTS users.outbox_event_2026_10 PARTITION OF users.outbox_event_p FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');
CREATE TABLE IF NOT EXISTS users.outbox_event_2026_11 PARTITION OF users.outbox_event_p FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');
CREATE TABLE IF NOT EXISTS users.outbox_event_2026_12 PARTITION OF users.outbox_event_p FOR VALUES FROM ('2026-12-01') TO ('2027-01-01');
CREATE TABLE IF NOT EXISTS users.outbox_event_2027_01 PARTITION OF users.outbox_event_p FOR VALUES FROM ('2027-01-01') TO ('2027-02-01');

CREATE TABLE IF NOT EXISTS users.outbox_event_default PARTITION OF users.outbox_event_p DEFAULT;

DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_tables
               WHERE schemaname = 'users' AND tablename = 'outbox_event') THEN
        INSERT INTO users.outbox_event_p
              (id, aggregate_id, aggregate_type, event_type, payload, created_at)
        SELECT id, aggregate_id, aggregate_type, event_type,
               payload::jsonb, created_at
        FROM   users.outbox_event;

        ALTER TABLE users.outbox_event RENAME TO outbox_event_legacy;
    END IF;
END $$;

ALTER TABLE users.outbox_event_p RENAME TO outbox_event;

CREATE INDEX IF NOT EXISTS idx_outbox_event
    ON users.outbox_event (aggregate_id, aggregate_type, event_type);
CREATE INDEX IF NOT EXISTS idx_outbox_created_at
    ON users.outbox_event (created_at);
