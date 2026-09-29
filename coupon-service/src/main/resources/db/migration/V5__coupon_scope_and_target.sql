-- 쿠폰에 "어디에 적용되는가"를 준다.
--
-- 예전 쿠폰은 userId · name · 기간 · 할인율이 전부였고, 적용하면 장바구니 **총액 전체**에
-- 할인율이 곱해졌다. "패션 카테고리 10%" 같은 기획을 표현할 방법이 없었다.
--
-- 기존 행은 전부 ALL 이 된다 — 예전과 똑같이 동작한다.
--
-- ROLLBACK-SAFE: 컬럼 추가와 테이블 생성뿐이다. 되돌려도 기존 데이터가 사라지지 않는다.
--   (scope 가 없던 시절의 코드로 되돌아가면 이 컬럼을 그냥 읽지 않는다.)

ALTER TABLE coupon.coupon ADD COLUMN IF NOT EXISTS scope varchar(16);

-- 기존 쿠폰은 제약 없음. NULL 로 두면 코드가 매번 null 을 ALL 로 해석해야 하므로 채워 둔다.
UPDATE coupon.coupon SET scope = 'ALL' WHERE scope IS NULL;

-- NOT NULL 은 붙이지 않는다. 이 베이스라인 규칙(V4 주석)대로 컬럼은 널 허용으로 추가하고,
-- Hibernate validate 는 널 여부가 아니라 존재와 타입만 본다. 코드가 null 을 ALL 로 읽는다.
ALTER TABLE coupon.coupon
    ADD CONSTRAINT coupon_scope_check CHECK (scope IS NULL OR scope IN ('ALL', 'CATEGORY', 'PRODUCT'));

CREATE TABLE IF NOT EXISTS coupon.coupon_target (
    coupon_target_id bigint not null,
    coupon_id        bigint not null,
    target_type      varchar(16) not null,
    target_value     varchar(255) not null,
    primary key (coupon_target_id),
    constraint uk_coupon_target unique (coupon_id, target_type, target_value),
    constraint coupon_target_type_check check (target_type in ('CATEGORY', 'PRODUCT'))
);

CREATE INDEX IF NOT EXISTS idx_coupon_target_coupon ON coupon.coupon_target (coupon_id);

-- FK 를 걸지 않는 이유: coupon 은 TSID 를 애플리케이션이 생성하고, 쿠폰 삭제 경로가
-- couponRepository.delete() 하나뿐이라 서비스 계층에서 대상도 함께 지운다. FK + ON DELETE
-- CASCADE 를 걸면 삭제가 조용해져서, 대상만 남는 고아 행이 생겼을 때 드러나지 않는다.
-- 대신 고아 행은 조회 시 쿠폰이 없으므로 아무 영향이 없다.
