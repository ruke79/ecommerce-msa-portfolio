-- ROLLBACK-UNSAFE: ALTER COLUMN ... SET NOT NULL 이 들어 있다(B-54).
--   실제 위험은 낮다 — 같은 줄에서 DEFAULT 'STARTED' 를 먼저 걸므로, 이 컬럼을 모르는
--   **옛 이미지의 INSERT 도 그대로 성공한다**(값을 생략하면 기본값이 들어간다). Hibernate
--   의 스키마 검증도 매핑된 컬럼의 존재만 보지 nullability 는 보지 않는다.
--   되돌렸을 때 실제로 일어나는 일: 옛 이미지에는 폴러가 없으므로 STARTED 행이 쌓인다.
--   그건 유실이 아니라 대기다 — 다시 올리면 폴러가 그 행들을 보낸다.
--   NOT NULL 을 굳이 거는 이유: NULL 인 행은 아래 부분 인덱스에도, 폴러의 조건에도 걸리지
--   않아 **영원히 보이지 않는 채로 남는다.** 이 작업이 없애려는 것이 바로 그런 상태다.
--
-- docs/48 ② — user-created 발행을 transactional outbox 로 옮긴다.
--
-- 왜 필요한가
-- ------------
-- 지금까지 발행은 이랬다:
--
--     @Transactional
--     public void publish(UserCreatedRequest req) {
--         kafkaTemplate.send("user-created", ..., req);   // 비동기, 결과를 보지 않음
--     }
--
-- DB 트랜잭션과 카프카 발행은 같은 원자 단위가 아니다(dual-write). 계정은 커밋되고
-- 이벤트는 안 갈 수 있고, send 가 비동기라 실패해도 로그 한 줄 남지 않았다. 실제로
-- 그 결과를 봤다 — 계정은 멀쩡한데 user-created 토픽이 통째로 비어 있었고(offset 0,
-- lag 0), 그 계정은 주문을 할 수 없었다(docs/47).
--
-- 이제 인증 확정과 **같은 트랜잭션**에 outbox 행을 쓰고, 폴러가 그 행을 읽어 발행한다.
-- 둘 중 하나만 일어나는 일이 없어진다.
--
-- 왜 status 컬럼이 필요한가
-- --------------------------
-- users.outbox_event 에는 다른 서비스와 달리 상태 컬럼이 없었다(V1 주석 참조). CDC
-- 릴레이만 상정했기 때문이다. 애플리케이션 폴러는 "아직 안 보낸 것"을 골라야 하므로
-- 상태가 필요하다. CDC 로 옮기더라도 이 컬럼이 방해가 되지는 않는다.
--
-- ⚠️ 이 테이블은 created_at 으로 RANGE 파티션돼 있다(V1). ALTER 는 파티션 부모에 하면
--    자식으로 전파되고, 아래 부분 인덱스도 파티션 인덱스로 만들어진다(PG 11+).

ALTER TABLE users.outbox_event ADD COLUMN IF NOT EXISTS outbox_status varchar(20);

-- 이미 있던 행은 COMPLETED 로 둔다. 기본값을 STARTED 로 먼저 걸면 과거 행이 전부
-- "안 보낸 것"이 되어 폴러가 한꺼번에 재발행한다. (지금은 행이 없을 것으로 보이지만,
-- 마이그레이션은 그 가정 위에 서면 안 된다.)
UPDATE users.outbox_event SET outbox_status = 'COMPLETED' WHERE outbox_status IS NULL;

ALTER TABLE users.outbox_event ALTER COLUMN outbox_status SET DEFAULT 'STARTED';
ALTER TABLE users.outbox_event ALTER COLUMN outbox_status SET NOT NULL;

-- 폴러가 보는 것은 '아직 안 보낸 것' 뿐이다. 부분 인덱스라 보낸 행이 쌓여도 커지지
-- 않는다 — 이 테이블은 계속 append 되므로 전체 인덱스를 두면 그만큼 비싸진다.
CREATE INDEX IF NOT EXISTS idx_outbox_pending
    ON users.outbox_event (created_at)
    WHERE outbox_status = 'STARTED';
