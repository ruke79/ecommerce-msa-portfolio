-- 고객 등급과 구매 이력 (docs/44 §3.2).
--
-- ROLLBACK-SAFE: 컬럼 추가뿐이다. 기본값이 있어 기존 행도 즉시 유효하고, 이전 버전의
-- 애플리케이션은 이 컬럼들을 읽지 않으므로 그대로 돌아간다.
--
-- ── 왜 등급을 user-service 에 저장하는가 ────────────────────────────────
--
-- 숫자는 order-service 가 갖고 있다. 하지만 "얼마부터 골드인가" 는 **정책**이고, 정책은
-- 사용자 데이터를 가진 쪽이 판정해야 세그먼트 규칙이 한곳에 모인다(AudienceSegment 주석).
-- 그래서 order-service 는 합계만 내보내고, 등급으로 바꾸는 일과 그 결과의 보관은 여기다.
--
-- ── 🔴 왜 매번 계산하지 않고 저장하는가 ────────────────────────────────
--
-- 세그먼트 조회는 쿠폰 발급 경로에 있다. 거기서 order-service 로 동기 호출을 하면
-- **주문 서비스가 죽을 때 쿠폰 발급도 같이 죽는다.** 그리고 등급은 원래 실시간이어야
-- 할 이유가 없는 값이다 — 실제 등급제는 전부 일/월 단위로 갱신한다. 하루에 한 번
-- 배치가 채우고, 읽는 쪽은 저장된 값만 본다.
--
-- 부작용도 정직하게 적어 둔다: 오늘 큰 주문을 한 사람은 **내일** 골드가 된다. 그게
-- 등급제의 정상 동작이고, 쿠폰 발급 화면이 주문 서비스 장애에 끌려가지 않는 대가로는 싸다.

ALTER TABLE users.users
    -- 현재 등급. CustomerTier enum 이름을 그대로 담는다(BASIC/SILVER/GOLD/VIP).
    -- NOT NULL + 기본값 BASIC: 등급이 비어 있는 상태를 아예 만들지 않는다. null 이면
    -- 읽는 쪽마다 "null 은 무슨 등급인가" 를 각자 정하게 되고, 한 곳은 BASIC 으로
    -- 한 곳은 "판정 불가" 로 읽는 순간 혜택이 새거나 막힌다.
    ADD COLUMN IF NOT EXISTS tier varchar(16) NOT NULL DEFAULT 'BASIC',

    -- 이 행의 등급을 마지막으로 계산한 시각.
    --
    -- 🔴 단순한 감사 필드가 아니다. **강등**이 이 컬럼으로 이뤄진다. 배치는 최근 구매가
    -- 있는 고객만 받으므로, 3개월 창에서 빠진 고객은 응답에 아예 없다. 그 사람들을
    -- 찾으려면 "이번 회차에 손대지 않은 행" 을 골라야 하고, 그 기준이 이 시각이다.
    -- (대안은 매 회차 전원을 BASIC 으로 밀고 다시 올리는 것인데, 그러면 배치 도중
    --  트랜잭션이 끊겼을 때 전 고객이 일반 등급으로 남는다.)
    ADD COLUMN IF NOT EXISTS tier_computed_at timestamp(6),

    -- 가입 이후 결제된 주문 수. 0 = PURCHASED 세그먼트 아님.
    -- 기간 창이 걸리지 않은 값이다 — 오래 쉰 단골이 "첫 구매 쿠폰" 을 받으면 안 된다.
    ADD COLUMN IF NOT EXISTS lifetime_order_count bigint NOT NULL DEFAULT 0,

    -- 최근 산정 기간의 실결제액 합계. 등급을 매긴 근거를 남긴다 —
    -- "왜 실버가 아니냐" 는 문의에 답하려면 당시 금액이 있어야 한다.
    ADD COLUMN IF NOT EXISTS recent_paid_amount bigint NOT NULL DEFAULT 0;

-- 강등 대상을 찾는 쿼리(tier <> 'BASIC' AND tier_computed_at < :회차시작)를 위한 인덱스.
-- BASIC 이 대다수라 부분 인덱스로 둔다 — 등급이 있는 소수만 담으면 되고, 그쪽이
-- 강등 스캔이 실제로 훑는 범위다.
CREATE INDEX IF NOT EXISTS idx_users_tier_stale
    ON users.users (tier_computed_at)
    WHERE tier <> 'BASIC';
