-- order-service 스키마 베이스라인 — Flyway 가 스키마를 소유한다 (docs/20).
--
-- ⚠️ 손으로 쓰지 말 것. Hibernate 가 엔티티에서 생성한 DDL 을 기계 변환한 것이다:
--     ./gradlew -PexportSchema -PwithContainers :order-service:test --tests '*ApplicationTests*'
--     ./ops/tools/hbm2flyway.py build/schema/order-service.sql -o <이 파일>
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
-- 스키마 1 · 시퀀스 0 · 테이블 5 · 인덱스 5 · 제약 3

CREATE SCHEMA IF NOT EXISTS orders;

CREATE TABLE IF NOT EXISTS orders.customer (
    created_at timestamp(6),
    customer_id bigint not null,
    updated_at timestamp(6),
    version bigint,
    created_by varchar(255),
    email varchar(255),
    nickname varchar(255),
    updated_by varchar(255),
    username varchar(255),
    primary key (customer_id),
    constraint uk_customer_email unique (email),
    constraint uk_customer_username unique (username),
    constraint uk_customer_nickname unique (nickname)
);

CREATE TABLE IF NOT EXISTS orders.order_address (
    created_at timestamp(6),
    order_address_id bigint not null,
    order_id bigint not null unique,
    updated_at timestamp(6),
    version bigint,
    address1 varchar(255) not null,
    address2 varchar(255),
    city varchar(255) not null,
    country varchar(255),
    created_by varchar(255),
    firstname varchar(255),
    lastname varchar(255),
    phone_number varchar(255),
    state varchar(255) not null,
    updated_by varchar(255),
    zip_code varchar(255) not null,
    primary key (order_address_id)
);

CREATE TABLE IF NOT EXISTS orders.order_product (
    price numeric(38,2),
    qty integer not null,
    color_id bigint,
    created_at timestamp(6),
    order_id bigint not null,
    order_product_id bigint not null,
    product_id bigint,
    updated_at timestamp(6),
    version bigint,
    created_by varchar(255),
    image varchar(255),
    name varchar(255),
    size varchar(255),
    updated_by varchar(255),
    constraint uk_order_product_orderProductId primary key (order_product_id)
);

CREATE TABLE IF NOT EXISTS orders.orders (
    shipping_price integer not null,
    tax_price integer not null,
    total integer not null,
    total_before_discount integer not null,
    created_at timestamp(6),
    customer_id bigint,
    delivered_created_at timestamp(6),
    order_address_id bigint,
    order_id bigint not null,
    paid_created_at timestamp(6),
    updated_at timestamp(6),
    version bigint,
    coupon_applied varchar(255),
    created_by varchar(255),
    order_status varchar(255) check (order_status in ('NOT_PROCESSED','PAID','UNPAID','PROCESSING','DISPATCHED','DELIVERED','CANCELED','COMPLETED')),
    payment_method varchar(255),
    tracking_id varchar(255),
    updated_by varchar(255),
    primary key (order_id),
    constraint uk_order_trackingId unique (tracking_id)
);

CREATE TABLE IF NOT EXISTS orders.payment_outbox_event (
    created_at timestamp(6) not null,
    aggregate_id varchar(255) not null,
    aggregate_type varchar(255) not null,
    event_type varchar(255) not null,
    id varchar(255) not null,
    outbox_status varchar(255) check (outbox_status in ('STARTED','COMPLETED','FAILED')),
    payload jsonb not null,
    primary key (id)
);

CREATE INDEX IF NOT EXISTS idx_customer_id on orders.customer (customer_id);

CREATE INDEX IF NOT EXISTS idx_order_address on orders.order_address (order_id);

CREATE INDEX IF NOT EXISTS idx_order_product on orders.order_product (name, size, product_id, price);

CREATE INDEX IF NOT EXISTS idx_order on orders.orders (tracking_id, customer_id, payment_method);

CREATE INDEX IF NOT EXISTS idx_payment_outbox_event on orders.payment_outbox_event (aggregate_id, aggregate_type, event_type);

DO $$
BEGIN
    IF to_regclass('orders.order_address') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fkru9uj5vrdjaarx7l5oa19fvt2') THEN
        ALTER TABLE orders.order_address ADD CONSTRAINT FKru9uj5vrdjaarx7l5oa19fvt2 foreign key (order_id) references orders.orders;
    END IF;
END $$;

DO $$
BEGIN
    IF to_regclass('orders.order_product') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fkl5mnj9n0di7k1v90yxnthkc73') THEN
        ALTER TABLE orders.order_product ADD CONSTRAINT FKl5mnj9n0di7k1v90yxnthkc73 foreign key (order_id) references orders.orders;
    END IF;
END $$;

DO $$
BEGIN
    IF to_regclass('orders.orders') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fkt0ecml6rxswu7mop3s4nc73ie') THEN
        ALTER TABLE orders.orders ADD CONSTRAINT FKt0ecml6rxswu7mop3s4nc73ie foreign key (order_address_id) references orders.order_address;
    END IF;
END $$;

-- ── 기존 DB 가 엔티티보다 뒤처진 경우를 메운다 ──────────────────────────
-- 빈 DB 에서는 위 create table 이 이미 만들었으므로 전부 무동작이다.

ALTER TABLE orders.customer ADD COLUMN IF NOT EXISTS created_at timestamp(6);
ALTER TABLE orders.customer ADD COLUMN IF NOT EXISTS customer_id bigint;
ALTER TABLE orders.customer ADD COLUMN IF NOT EXISTS updated_at timestamp(6);
ALTER TABLE orders.customer ADD COLUMN IF NOT EXISTS version bigint;
ALTER TABLE orders.customer ADD COLUMN IF NOT EXISTS created_by varchar(255);
ALTER TABLE orders.customer ADD COLUMN IF NOT EXISTS email varchar(255);
ALTER TABLE orders.customer ADD COLUMN IF NOT EXISTS nickname varchar(255);
ALTER TABLE orders.customer ADD COLUMN IF NOT EXISTS updated_by varchar(255);
ALTER TABLE orders.customer ADD COLUMN IF NOT EXISTS username varchar(255);
ALTER TABLE orders.order_address ADD COLUMN IF NOT EXISTS created_at timestamp(6);
ALTER TABLE orders.order_address ADD COLUMN IF NOT EXISTS order_address_id bigint;
ALTER TABLE orders.order_address ADD COLUMN IF NOT EXISTS order_id bigint;
ALTER TABLE orders.order_address ADD COLUMN IF NOT EXISTS updated_at timestamp(6);
ALTER TABLE orders.order_address ADD COLUMN IF NOT EXISTS version bigint;
ALTER TABLE orders.order_address ADD COLUMN IF NOT EXISTS address1 varchar(255);
ALTER TABLE orders.order_address ADD COLUMN IF NOT EXISTS address2 varchar(255);
ALTER TABLE orders.order_address ADD COLUMN IF NOT EXISTS city varchar(255);
ALTER TABLE orders.order_address ADD COLUMN IF NOT EXISTS country varchar(255);
ALTER TABLE orders.order_address ADD COLUMN IF NOT EXISTS created_by varchar(255);
ALTER TABLE orders.order_address ADD COLUMN IF NOT EXISTS firstname varchar(255);
ALTER TABLE orders.order_address ADD COLUMN IF NOT EXISTS lastname varchar(255);
ALTER TABLE orders.order_address ADD COLUMN IF NOT EXISTS phone_number varchar(255);
ALTER TABLE orders.order_address ADD COLUMN IF NOT EXISTS state varchar(255);
ALTER TABLE orders.order_address ADD COLUMN IF NOT EXISTS updated_by varchar(255);
ALTER TABLE orders.order_address ADD COLUMN IF NOT EXISTS zip_code varchar(255);
ALTER TABLE orders.order_product ADD COLUMN IF NOT EXISTS price numeric(38,2);
ALTER TABLE orders.order_product ADD COLUMN IF NOT EXISTS qty integer;
ALTER TABLE orders.order_product ADD COLUMN IF NOT EXISTS color_id bigint;
ALTER TABLE orders.order_product ADD COLUMN IF NOT EXISTS created_at timestamp(6);
ALTER TABLE orders.order_product ADD COLUMN IF NOT EXISTS order_id bigint;
ALTER TABLE orders.order_product ADD COLUMN IF NOT EXISTS order_product_id bigint;
ALTER TABLE orders.order_product ADD COLUMN IF NOT EXISTS product_id bigint;
ALTER TABLE orders.order_product ADD COLUMN IF NOT EXISTS updated_at timestamp(6);
ALTER TABLE orders.order_product ADD COLUMN IF NOT EXISTS version bigint;
ALTER TABLE orders.order_product ADD COLUMN IF NOT EXISTS created_by varchar(255);
ALTER TABLE orders.order_product ADD COLUMN IF NOT EXISTS image varchar(255);
ALTER TABLE orders.order_product ADD COLUMN IF NOT EXISTS name varchar(255);
ALTER TABLE orders.order_product ADD COLUMN IF NOT EXISTS size varchar(255);
ALTER TABLE orders.order_product ADD COLUMN IF NOT EXISTS updated_by varchar(255);
ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS shipping_price integer;
ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS tax_price integer;
ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS total integer;
ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS total_before_discount integer;
ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS created_at timestamp(6);
ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS customer_id bigint;
ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS delivered_created_at timestamp(6);
ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS order_address_id bigint;
ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS order_id bigint;
ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS paid_created_at timestamp(6);
ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS updated_at timestamp(6);
ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS version bigint;
ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS coupon_applied varchar(255);
ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS created_by varchar(255);
ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS order_status varchar(255) check (order_status in ('NOT_PROCESSED','PAID','UNPAID','PROCESSING','DISPATCHED','DELIVERED','CANCELED','COMPLETED'));
ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS payment_method varchar(255);
ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS tracking_id varchar(255);
ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS updated_by varchar(255);
ALTER TABLE orders.payment_outbox_event ADD COLUMN IF NOT EXISTS created_at timestamp(6);
ALTER TABLE orders.payment_outbox_event ADD COLUMN IF NOT EXISTS aggregate_id varchar(255);
ALTER TABLE orders.payment_outbox_event ADD COLUMN IF NOT EXISTS aggregate_type varchar(255);
ALTER TABLE orders.payment_outbox_event ADD COLUMN IF NOT EXISTS event_type varchar(255);
ALTER TABLE orders.payment_outbox_event ADD COLUMN IF NOT EXISTS id varchar(255);
ALTER TABLE orders.payment_outbox_event ADD COLUMN IF NOT EXISTS outbox_status varchar(255) check (outbox_status in ('STARTED','COMPLETED','FAILED'));
ALTER TABLE orders.payment_outbox_event ADD COLUMN IF NOT EXISTS payload jsonb;
