-- pay-service 스키마 베이스라인 — Flyway 가 스키마를 소유한다 (docs/20).
--
-- ⚠️ 손으로 쓰지 말 것. Hibernate 가 엔티티에서 생성한 DDL 을 기계 변환한 것이다:
--     ./gradlew -PexportSchema -PwithContainers :pay-service:test --tests '*ApplicationTests*'
--     ./ops/tools/hbm2flyway.py build/schema/pay-service.sql -o <이 파일>
--
-- 왜 베이스라인이 필요한가: 예전에는 `ddl-auto: update` 가 스키마를 만들었다. 그래서
--   · 스키마 변경이 이력에 남지 않고 (무엇이 언제 왜 바뀌었는지 아무도 모른다)
--   · 운영 DB 에 예고 없는 DDL 이 나가며
--   · 제약을 **지우지는 않아** 잘못된 제약이 영원히 남는다 (V4 가 그 뒤처리였다)
-- 이제 Flyway 가 소유하고 Hibernate 는 `validate` 로 검증만 한다.
--
-- 모든 문장이 멱등이다. 빈 DB 에서는 전부 만들고, `ddl-auto` 가 이미 만들어 둔 DB 에서는
-- 전부 건너뛴다. 그래서 기존 환경도 이 마이그레이션을 그대로 통과한다.
--
-- 파일 끝의 `add column if not exists` 묶음은 기존 DB 가 엔티티보다 **뒤처진** 경우를 메운다.
-- "그건 ddl-auto 랑 뭐가 다르냐"에 대한 답: ddl-auto 는 매 기동마다 그때의 코드에서 DDL 을
-- 새로 유추해 **검토 없이** 실행한다. 이건 커밋된 파일에 적힌 고정된 문장 목록이고, 리뷰를
-- 거치며, Flyway 가 기록하므로 **딱 한 번만** 실행된다. 뒤에 스키마를 또 바꾸려면 새 마이그
-- 레이션을 써야 한다. 컬럼은 반드시 널 허용으로 추가한다 — 기존 행이 있는 테이블에 기본값
-- 없는 NOT NULL 은 붙지 않기 때문이고, validate 는 널 여부가 아니라 존재와 타입만 본다.
--
-- 스키마 1 · 시퀀스 0 · 테이블 2 · 인덱스 2 · 제약 0

CREATE SCHEMA IF NOT EXISTS pay;

CREATE TABLE IF NOT EXISTS pay.order_outbox_event (
    created_at timestamp(6) not null,
    aggregate_id varchar(255) not null,
    aggregate_type varchar(255) not null,
    event_type varchar(255) not null,
    id varchar(255) not null,
    outbox_status varchar(255) check (outbox_status in ('STARTED','COMPLETED','FAILED')),
    payload jsonb not null,
    primary key (id)
);

CREATE TABLE IF NOT EXISTS pay.payment (
    amounts numeric(38,2),
    cancel_createdat timestamp(6),
    created_at timestamp(6),
    customer_id bigint,
    order_id bigint,
    payment_created_at timestamp(6),
    payment_id bigint not null,
    updated_at timestamp(6),
    version bigint,
    created_by varchar(255),
    payment_status varchar(255) check (payment_status in ('COMPLETED','CANCELED','FAILED')),
    payment_type varchar(255) check (payment_type in ('PAYPAL','CREDIT_CARD','CASH')),
    paypal_order_id varchar(255),
    tracking_id varchar(255),
    updated_by varchar(255),
    primary key (payment_id),
    constraint UniqueOrderIdAndTrackingId unique (order_id, tracking_id)
);

CREATE INDEX IF NOT EXISTS idx_order_outbox_event on pay.order_outbox_event (aggregate_id, aggregate_type, event_type);

CREATE INDEX IF NOT EXISTS idx_payment on pay.payment (order_id, tracking_id, customer_id, paypal_order_id);

-- ── 기존 DB 가 엔티티보다 뒤처진 경우를 메운다 ──────────────────────────
-- 빈 DB 에서는 위 create table 이 이미 만들었으므로 전부 무동작이다.

ALTER TABLE pay.order_outbox_event ADD COLUMN IF NOT EXISTS created_at timestamp(6);
ALTER TABLE pay.order_outbox_event ADD COLUMN IF NOT EXISTS aggregate_id varchar(255);
ALTER TABLE pay.order_outbox_event ADD COLUMN IF NOT EXISTS aggregate_type varchar(255);
ALTER TABLE pay.order_outbox_event ADD COLUMN IF NOT EXISTS event_type varchar(255);
ALTER TABLE pay.order_outbox_event ADD COLUMN IF NOT EXISTS id varchar(255);
ALTER TABLE pay.order_outbox_event ADD COLUMN IF NOT EXISTS outbox_status varchar(255) check (outbox_status in ('STARTED','COMPLETED','FAILED'));
ALTER TABLE pay.order_outbox_event ADD COLUMN IF NOT EXISTS payload jsonb;
ALTER TABLE pay.payment ADD COLUMN IF NOT EXISTS amounts numeric(38,2);
ALTER TABLE pay.payment ADD COLUMN IF NOT EXISTS cancel_createdat timestamp(6);
ALTER TABLE pay.payment ADD COLUMN IF NOT EXISTS created_at timestamp(6);
ALTER TABLE pay.payment ADD COLUMN IF NOT EXISTS customer_id bigint;
ALTER TABLE pay.payment ADD COLUMN IF NOT EXISTS order_id bigint;
ALTER TABLE pay.payment ADD COLUMN IF NOT EXISTS payment_created_at timestamp(6);
ALTER TABLE pay.payment ADD COLUMN IF NOT EXISTS payment_id bigint;
ALTER TABLE pay.payment ADD COLUMN IF NOT EXISTS updated_at timestamp(6);
ALTER TABLE pay.payment ADD COLUMN IF NOT EXISTS version bigint;
ALTER TABLE pay.payment ADD COLUMN IF NOT EXISTS created_by varchar(255);
ALTER TABLE pay.payment ADD COLUMN IF NOT EXISTS payment_status varchar(255) check (payment_status in ('COMPLETED','CANCELED','FAILED'));
ALTER TABLE pay.payment ADD COLUMN IF NOT EXISTS payment_type varchar(255) check (payment_type in ('PAYPAL','CREDIT_CARD','CASH'));
ALTER TABLE pay.payment ADD COLUMN IF NOT EXISTS paypal_order_id varchar(255);
ALTER TABLE pay.payment ADD COLUMN IF NOT EXISTS tracking_id varchar(255);
ALTER TABLE pay.payment ADD COLUMN IF NOT EXISTS updated_by varchar(255);
