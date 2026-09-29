-- cart-service 스키마 베이스라인 — Flyway 가 스키마를 소유한다 (docs/20).
--
-- ⚠️ 손으로 쓰지 말 것. Hibernate 가 엔티티에서 생성한 DDL 을 기계 변환한 것이다:
--     ./gradlew -PexportSchema -PwithContainers :cart-service:test --tests '*ApplicationTests*'
--     ./ops/tools/hbm2flyway.py build/schema/cart-service.sql -o <이 파일>
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
-- 스키마 1 · 시퀀스 0 · 테이블 3 · 인덱스 3 · 제약 1

CREATE SCHEMA IF NOT EXISTS cart;

CREATE TABLE IF NOT EXISTS cart.cart (
    cart_total numeric(38,2),
    total_after_discount numeric(38,2),
    cart_id bigint not null,
    created_at timestamp(6),
    updated_at timestamp(6),
    user_id bigint,
    version bigint,
    created_by varchar(255),
    updated_by varchar(255),
    primary key (cart_id)
);

CREATE TABLE IF NOT EXISTS cart.cart_product (
    price numeric(38,2),
    qty integer not null,
    shipping numeric(38,2),
    style integer not null,
    cart_id bigint,
    cartproduct_id bigint not null,
    color_id bigint,
    created_at timestamp(6),
    product_id bigint,
    updated_at timestamp(6),
    version bigint,
    _uid varchar(255),
    created_by varchar(255),
    image varchar(255),
    name varchar(255),
    size varchar(255),
    updated_by varchar(255),
    primary key (cartproduct_id)
);

CREATE TABLE IF NOT EXISTS cart.outbox_event (
    created_at timestamp(6) not null,
    aggregate_id varchar(255) not null,
    aggregate_type varchar(255) not null,
    event_type varchar(255) not null,
    id varchar(255) not null,
    outbox_status varchar(255) check (outbox_status in ('STARTED','COMPLETED','FAILED')),
    payload jsonb not null,
    primary key (id)
);

CREATE INDEX IF NOT EXISTS idx_cart_user on cart.cart (user_id);

CREATE INDEX IF NOT EXISTS idx_cart_product on cart.cart_product (_uid, name, size, style);

CREATE INDEX IF NOT EXISTS idx_outbox_event on cart.outbox_event (aggregate_id, aggregate_type, event_type);

DO $$
BEGIN
    IF to_regclass('cart.cart_product') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fklv5x4iresnv4xspvomrwd8ej9') THEN
        ALTER TABLE cart.cart_product ADD CONSTRAINT FKlv5x4iresnv4xspvomrwd8ej9 foreign key (cart_id) references cart.cart;
    END IF;
END $$;

-- ── 기존 DB 가 엔티티보다 뒤처진 경우를 메운다 ──────────────────────────
-- 빈 DB 에서는 위 create table 이 이미 만들었으므로 전부 무동작이다.

ALTER TABLE cart.cart ADD COLUMN IF NOT EXISTS cart_total numeric(38,2);
ALTER TABLE cart.cart ADD COLUMN IF NOT EXISTS total_after_discount numeric(38,2);
ALTER TABLE cart.cart ADD COLUMN IF NOT EXISTS cart_id bigint;
ALTER TABLE cart.cart ADD COLUMN IF NOT EXISTS created_at timestamp(6);
ALTER TABLE cart.cart ADD COLUMN IF NOT EXISTS updated_at timestamp(6);
ALTER TABLE cart.cart ADD COLUMN IF NOT EXISTS user_id bigint;
ALTER TABLE cart.cart ADD COLUMN IF NOT EXISTS version bigint;
ALTER TABLE cart.cart ADD COLUMN IF NOT EXISTS created_by varchar(255);
ALTER TABLE cart.cart ADD COLUMN IF NOT EXISTS updated_by varchar(255);
ALTER TABLE cart.cart_product ADD COLUMN IF NOT EXISTS price numeric(38,2);
ALTER TABLE cart.cart_product ADD COLUMN IF NOT EXISTS qty integer;
ALTER TABLE cart.cart_product ADD COLUMN IF NOT EXISTS shipping numeric(38,2);
ALTER TABLE cart.cart_product ADD COLUMN IF NOT EXISTS style integer;
ALTER TABLE cart.cart_product ADD COLUMN IF NOT EXISTS cart_id bigint;
ALTER TABLE cart.cart_product ADD COLUMN IF NOT EXISTS cartproduct_id bigint;
ALTER TABLE cart.cart_product ADD COLUMN IF NOT EXISTS color_id bigint;
ALTER TABLE cart.cart_product ADD COLUMN IF NOT EXISTS created_at timestamp(6);
ALTER TABLE cart.cart_product ADD COLUMN IF NOT EXISTS product_id bigint;
ALTER TABLE cart.cart_product ADD COLUMN IF NOT EXISTS updated_at timestamp(6);
ALTER TABLE cart.cart_product ADD COLUMN IF NOT EXISTS version bigint;
ALTER TABLE cart.cart_product ADD COLUMN IF NOT EXISTS _uid varchar(255);
ALTER TABLE cart.cart_product ADD COLUMN IF NOT EXISTS created_by varchar(255);
ALTER TABLE cart.cart_product ADD COLUMN IF NOT EXISTS image varchar(255);
ALTER TABLE cart.cart_product ADD COLUMN IF NOT EXISTS name varchar(255);
ALTER TABLE cart.cart_product ADD COLUMN IF NOT EXISTS size varchar(255);
ALTER TABLE cart.cart_product ADD COLUMN IF NOT EXISTS updated_by varchar(255);
ALTER TABLE cart.outbox_event ADD COLUMN IF NOT EXISTS created_at timestamp(6);
ALTER TABLE cart.outbox_event ADD COLUMN IF NOT EXISTS aggregate_id varchar(255);
ALTER TABLE cart.outbox_event ADD COLUMN IF NOT EXISTS aggregate_type varchar(255);
ALTER TABLE cart.outbox_event ADD COLUMN IF NOT EXISTS event_type varchar(255);
ALTER TABLE cart.outbox_event ADD COLUMN IF NOT EXISTS id varchar(255);
ALTER TABLE cart.outbox_event ADD COLUMN IF NOT EXISTS outbox_status varchar(255) check (outbox_status in ('STARTED','COMPLETED','FAILED'));
ALTER TABLE cart.outbox_event ADD COLUMN IF NOT EXISTS payload jsonb;
