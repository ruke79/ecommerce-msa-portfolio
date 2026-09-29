-- docs/09 P1: order_outbox_event 를 created_at 기준 월 단위 RANGE 파티션으로 전환한다.
-- 동작 방식/주의사항은 order-service 의 V1__partition_payment_outbox_event.sql 주석 참조.

CREATE SCHEMA IF NOT EXISTS pay;

CREATE TABLE IF NOT EXISTS pay.order_outbox_event_p (
    id             varchar(255) NOT NULL,
    aggregate_id   varchar(255) NOT NULL,
    aggregate_type varchar(255) NOT NULL,
    event_type     varchar(255) NOT NULL,
    payload        jsonb        NOT NULL,
    outbox_status  varchar(255),
    created_at     timestamp(6) NOT NULL,
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

CREATE TABLE IF NOT EXISTS pay.order_outbox_event_2026_07 PARTITION OF pay.order_outbox_event_p FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');
CREATE TABLE IF NOT EXISTS pay.order_outbox_event_2026_08 PARTITION OF pay.order_outbox_event_p FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');
CREATE TABLE IF NOT EXISTS pay.order_outbox_event_2026_09 PARTITION OF pay.order_outbox_event_p FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');
CREATE TABLE IF NOT EXISTS pay.order_outbox_event_2026_10 PARTITION OF pay.order_outbox_event_p FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');
CREATE TABLE IF NOT EXISTS pay.order_outbox_event_2026_11 PARTITION OF pay.order_outbox_event_p FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');
CREATE TABLE IF NOT EXISTS pay.order_outbox_event_2026_12 PARTITION OF pay.order_outbox_event_p FOR VALUES FROM ('2026-12-01') TO ('2027-01-01');
CREATE TABLE IF NOT EXISTS pay.order_outbox_event_2027_01 PARTITION OF pay.order_outbox_event_p FOR VALUES FROM ('2027-01-01') TO ('2027-02-01');

CREATE TABLE IF NOT EXISTS pay.order_outbox_event_default PARTITION OF pay.order_outbox_event_p DEFAULT;

DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_tables
               WHERE schemaname = 'pay' AND tablename = 'order_outbox_event') THEN
        INSERT INTO pay.order_outbox_event_p
              (id, aggregate_id, aggregate_type, event_type, payload, outbox_status, created_at)
        SELECT id, aggregate_id, aggregate_type, event_type,
               payload::jsonb, outbox_status, created_at
        FROM   pay.order_outbox_event;

        ALTER TABLE pay.order_outbox_event RENAME TO order_outbox_event_legacy;
    END IF;
END $$;

ALTER TABLE pay.order_outbox_event_p RENAME TO order_outbox_event;

CREATE INDEX IF NOT EXISTS idx_order_outbox_event
    ON pay.order_outbox_event (aggregate_id, aggregate_type, event_type);
CREATE INDEX IF NOT EXISTS idx_order_outbox_started
    ON pay.order_outbox_event (created_at)
    WHERE outbox_status = 'STARTED';

-- 이 테이블은 order-outbox-connector 가 CDC 로 tailing 한다 (docs/09 PG-6).
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_publication WHERE pubname = 'dbz_publication') THEN
        EXECUTE 'ALTER PUBLICATION dbz_publication SET (publish_via_partition_root = true)';
    END IF;
END $$;
