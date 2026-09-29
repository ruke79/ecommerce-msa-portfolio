-- 기획전 확장: 카테고리 단위 대상 · 대상 고객군 · 딸린 쿠폰 (docs/44 §3.1 "아직 없는 것")
--
-- ROLLBACK-SAFE: 테이블 하나를 새로 만들고, 기존 테이블에 NULL 허용 컬럼만 더한다.
-- 기존 기획전은 카테고리 없음 · 전체 대상 · 쿠폰 없음으로 그대로 동작한다.
--
-- ── ① 카테고리 단위 대상 ────────────────────────────────────────────────
--
-- 지금까지 대상은 상품 단위였다(event_product). "여성 의류 전체 20%" 를 하려면 그 안의
-- 상품을 하나씩 담아야 했고, 한 기획전 500개 상한에 금방 닿는다. 더 나쁜 것은 **기획전이
-- 시작된 뒤 등록된 상품이 빠진다**는 점이다 — 관리자는 카테고리를 걸었다고 생각하는데
-- 신상품만 정가로 남는다.
--
-- 카테고리는 조회 시점에 상품으로 펼친다(EventPricing). 그래서 나중에 들어온 상품도
-- 자동으로 포함된다.
--
-- 상품 단위 지정을 없애지 않는 이유: 같은 카테고리 안에서 일부만 할인하거나 상품마다
-- 할인율이 다른 경우가 있다. 둘 다 걸리면 **높은 할인율**이 이긴다(EventPricing).

CREATE TABLE IF NOT EXISTS content.event_category (
    event_id      bigint NOT NULL,
    -- product.category 의 id. FK 를 걸지 않는 이유는 event_product 와 같다 —
    -- content 스키마를 떼어낼 수 있게 남겨 둔 경계를 DB 제약으로 굳히지 않는다(V11 주석).
    category_id   bigint NOT NULL,
    -- 퍼센트(0~100). product_sku.discount 와 같은 단위.
    discount_rate int    NOT NULL,
    -- 한 기획전이 같은 카테고리를 두 번 담을 수 없다.
    PRIMARY KEY (event_id, category_id)
);

-- ── ② 대상 고객군 ───────────────────────────────────────────────────────
--
-- 🔴 이 값은 **쿠폰을 누가 받을 수 있는가**만 가른다. 진열가는 가르지 않는다.
--
-- 상품 조회에는 신원이 없고(게이트웨이의 공개 라우트에 JwtAuthorization 이 없다),
-- 목록은 Redis 에 캐시되며, 무엇보다 신원을 아는 화면과 모르는 화면이 섞이면 "목록에서
-- 30% 를 보고 들어왔는데 상세에서는 정가" 가 구조적으로 되살아난다.
--
-- 그래서 세그먼트 할인은 쿠폰으로 준다 — 쿠폰은 결제 시점에 적용되고 그때는 신원이
-- 확실하다. 자세한 이유는 common 의 AudienceSegment 주석에 적어 두었다.

ALTER TABLE content.event
    ADD COLUMN IF NOT EXISTS audience varchar(24) NOT NULL DEFAULT 'ALL';

-- ── ③ 딸린 쿠폰 ─────────────────────────────────────────────────────────
--
-- 기획전이 쿠폰을 **뿌리지 않는다.** 사용자가 받아 간다(claim).
--
-- 시작할 때 전 사용자에게 발급하면 사용자 수만큼 coupon 행이 생긴다 — 공지 알림에서
-- 개인별 행을 만들지 않기로 한 것과 같은 문제다. 게다가 대부분은 쓰지 않는다.
-- 받아 간 사람만 행이 생기게 두면 쓰기량이 실제 관심에 비례한다.
--
-- 중복 수령은 coupon 테이블의 uk_coupon_name_user_id 가 이미 막는다 — 같은 이름의 쿠폰을
-- 한 사람이 두 번 가질 수 없다. 따로 방어를 만들지 않는다.

ALTER TABLE content.event
    -- 비어 있으면 이 기획전에는 쿠폰이 없다.
    ADD COLUMN IF NOT EXISTS coupon_name varchar(120);

ALTER TABLE content.event
    ADD COLUMN IF NOT EXISTS coupon_discount int;

ALTER TABLE content.event
    -- 받은 날로부터 며칠간 쓸 수 있는가. 기획전 종료와 별개다 — 마지막 날 받은 쿠폰이
    -- 그 자리에서 만료되면 받을 이유가 없다.
    ADD COLUMN IF NOT EXISTS coupon_valid_days int;
