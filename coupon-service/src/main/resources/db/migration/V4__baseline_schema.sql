-- coupon-service 스키마 베이스라인 — Flyway 가 스키마를 소유한다 (docs/20).
--
-- ⚠️ 손으로 쓰지 말 것. Hibernate 가 엔티티에서 생성한 DDL 을 기계 변환한 것이다:
--     ./gradlew -PexportSchema -PwithContainers :coupon-service:test --tests '*ApplicationTests*'
--     ./ops/tools/hbm2flyway.py build/schema/coupon-service.sql -o <이 파일>
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
-- 스키마 1 · 시퀀스 0 · 테이블 2 · 인덱스 1 · 제약 0

CREATE SCHEMA IF NOT EXISTS coupon;

CREATE TABLE IF NOT EXISTS coupon.coupon (
    discount integer not null,
    coupon_id bigint not null,
    created_at timestamp(6),
    end_date timestamp(6),
    start_date timestamp(6),
    updated_at timestamp(6),
    user_id bigint,
    version bigint,
    created_by varchar(255),
    name varchar(255),
    status varchar(255) check (status in ('NOT_USED','USED','EXPIRED')),
    updated_by varchar(255),
    primary key (coupon_id),
    constraint uk_coupon_name_user_id unique (name, user_id)
);

CREATE TABLE IF NOT EXISTS coupon.processed_event (
    processed_at timestamp(6) not null,
    event_id varchar(64) not null,
    primary key (event_id)
);

CREATE INDEX IF NOT EXISTS idx_coupon on coupon.coupon (name, user_id);

-- ── 기존 DB 가 엔티티보다 뒤처진 경우를 메운다 ──────────────────────────
-- 빈 DB 에서는 위 create table 이 이미 만들었으므로 전부 무동작이다.

ALTER TABLE coupon.coupon ADD COLUMN IF NOT EXISTS discount integer;
ALTER TABLE coupon.coupon ADD COLUMN IF NOT EXISTS coupon_id bigint;
ALTER TABLE coupon.coupon ADD COLUMN IF NOT EXISTS created_at timestamp(6);
ALTER TABLE coupon.coupon ADD COLUMN IF NOT EXISTS end_date timestamp(6);
ALTER TABLE coupon.coupon ADD COLUMN IF NOT EXISTS start_date timestamp(6);
ALTER TABLE coupon.coupon ADD COLUMN IF NOT EXISTS updated_at timestamp(6);
ALTER TABLE coupon.coupon ADD COLUMN IF NOT EXISTS user_id bigint;
ALTER TABLE coupon.coupon ADD COLUMN IF NOT EXISTS version bigint;
ALTER TABLE coupon.coupon ADD COLUMN IF NOT EXISTS created_by varchar(255);
ALTER TABLE coupon.coupon ADD COLUMN IF NOT EXISTS name varchar(255);
ALTER TABLE coupon.coupon ADD COLUMN IF NOT EXISTS status varchar(255) check (status in ('NOT_USED','USED','EXPIRED'));
ALTER TABLE coupon.coupon ADD COLUMN IF NOT EXISTS updated_by varchar(255);
ALTER TABLE coupon.processed_event ADD COLUMN IF NOT EXISTS processed_at timestamp(6);
ALTER TABLE coupon.processed_event ADD COLUMN IF NOT EXISTS event_id varchar(64);
