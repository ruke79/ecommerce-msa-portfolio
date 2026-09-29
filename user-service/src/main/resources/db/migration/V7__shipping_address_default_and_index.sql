-- 배송지: 기본 배송지를 사용자당 하나로 강제하고, 아무도 쓰지 않는 개인정보 인덱스를 지운다.
--
-- ROLLBACK-SAFE: 인덱스만 만들고 지운다. 행 데이터는 건드리지 않는다.
--   (아래 UPDATE 하나는 예외 — 같은 사용자에게 기본 배송지가 둘 이상인 기존 데이터를
--    하나만 남기고 끈다. 이건 유니크 인덱스를 걸기 위해 반드시 선행돼야 하고,
--    되돌리더라도 "기본 배송지가 하나"는 여전히 올바른 상태다.)

-- ── ① 사용자당 기본 배송지는 하나 ──────────────────────────────────────────
--
-- 애플리케이션 코드가 findByActive(true) 로 **전 테이블**에서 기본 배송지를 찾고 있었다.
-- user_id 조건이 없어서, 사용자가 둘 이상 기본 배송지를 갖는 순간 Optional 쿼리가 두 행을
-- 만나 IncorrectResultSizeDataAccessException 이 났고, 그 전이라도 전체에 active 가 하나뿐일
-- 때 그것이 남의 것이면 남의 기본 배송지를 꺼 버렸다.
--
-- 코드는 고쳤다(AddressService). 그래도 DB 가 보장해야 하는 이유: "사용자당 하나" 는
-- 애플리케이션이 지키는 관례가 아니라 데이터의 성질이다. 동시 요청 둘이 각자 "현재 기본
-- 배송지가 없다" 를 읽고 둘 다 켜는 경쟁 상태는 코드만으로는 막지 못한다.

-- 유니크 인덱스를 걸기 전에 기존 위반을 정리한다. 사용자마다 가장 최근에 만들어진 것
-- 하나만 남기고 끈다 — 임의로 고르는 것보다 "마지막에 고른 것" 이 사용자의 의도에 가깝다.
UPDATE users.shipping_address s
   SET active = false
 WHERE s.active
   AND s.shipping_address_id <> (
        SELECT t.shipping_address_id
          FROM users.shipping_address t
         WHERE t.user_id = s.user_id
           AND t.active
         ORDER BY t.created_at DESC NULLS LAST, t.shipping_address_id DESC
         LIMIT 1
   );

-- 부분 유니크 인덱스. active = false 인 행은 제약을 받지 않으므로 배송지는 얼마든지
-- 가질 수 있고, 기본으로 켜진 것만 하나로 제한된다.
CREATE UNIQUE INDEX IF NOT EXISTS uq_shipping_address_active_per_user
    ON users.shipping_address (user_id)
 WHERE active;

-- ── ② user_id 인덱스 ────────────────────────────────────────────────────────
--
-- 이 테이블에 실제로 나가는 쿼리는 전부 user_id 로 거른다
-- (findByUser_UserId, findByUser_UserIdWithUser, findByUser_UserIdAndActiveTrue,
--  findByShippingAddressIdAndUser_UserId, deleteByUserId).
--
-- PostgreSQL 은 외래키에 인덱스를 자동으로 만들지 않는다. 즉 지금까지 이 조회들은
-- 전부 순차 스캔이었다.
CREATE INDEX IF NOT EXISTS idx_shipping_address_user
    ON users.shipping_address (user_id);

-- ── ③ 이름 + 주소 전체에 걸린 인덱스를 지운다 ───────────────────────────────
--
-- @Index(columnList = "firstname, lastname, address1, city, state, zipCode")
-- 6개 컬럼짜리 복합 인덱스인데, **이 인덱스를 타는 쿼리가 하나도 없다.** 위에 적은 대로
-- 조회는 전부 user_id 또는 pk 로 한다.
--
-- 그래서 이 인덱스가 하는 일은 두 가지뿐이다:
--   · 배송지를 쓸 때마다 6개 컬럼짜리 인덱스를 갱신한다
--   · 실명 + 전체 주소를 정렬된 형태로 한 벌 더 복제해 둔다. DB 덤프가 유출되면
--     "이 사람 주소 찾기" 가 인덱스 스캔 한 번이 된다
--
-- 개인정보를 이유 없이 한 벌 더 들고 있을 이유는 없다.
DROP INDEX IF EXISTS users.idx_shipping_address;
