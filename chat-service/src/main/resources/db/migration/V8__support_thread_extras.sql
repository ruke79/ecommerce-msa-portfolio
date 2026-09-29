-- 고객센터 채팅 개편 — 문의 유형 · 만족도 평가 · 상담원 내부 메모 (docs/58 §11).
--
-- 모두 열을 **더하기만** 한다. 옛 이미지(V7 까지의 엔티티)도 이 표로 그대로 돈다 — 모르는 열은 읽지 않고,
-- topic 은 기본값이 있어 옛 코드의 INSERT 도 통과한다. 되돌리기(ops/28 --rollback)가 막히지 않는다.
--
--   topic          — 고객이 문의를 열 때 고른 유형. 데스크가 유형별로 거른다.
--   rating …       — 종료된 상담에 고객이 한 번 남기는 별점(1~5)과 한 줄 의견.
--   internal_note  — 상담원끼리만 보는 메모. 🔒 고객 API 응답에는 절대 싣지 않는다(SupportService.toDtos).

ALTER TABLE chat.support_thread ADD COLUMN IF NOT EXISTS topic           varchar(16)   NOT NULL DEFAULT 'OTHER';
ALTER TABLE chat.support_thread ADD COLUMN IF NOT EXISTS rating          smallint;
ALTER TABLE chat.support_thread ADD COLUMN IF NOT EXISTS rating_comment  varchar(500);
ALTER TABLE chat.support_thread ADD COLUMN IF NOT EXISTS rated_at        timestamptz;
ALTER TABLE chat.support_thread ADD COLUMN IF NOT EXISTS internal_note   varchar(2000);
ALTER TABLE chat.support_thread ADD COLUMN IF NOT EXISTS note_updated_at timestamptz;

ALTER TABLE chat.support_thread ADD CONSTRAINT ck_support_thread_topic
    CHECK (topic IN ('ORDER', 'DELIVERY', 'PAYMENT', 'PRODUCT', 'ACCOUNT', 'OTHER'));

-- 평가는 끝난 상담에만, 1~5 점.
ALTER TABLE chat.support_thread ADD CONSTRAINT ck_support_thread_rating
    CHECK (rating IS NULL OR (rating BETWEEN 1 AND 5 AND status = 'CLOSED'));

-- 대기 순번 — "나보다 먼저 연 대기 중 문의" 를 센다.
CREATE INDEX IF NOT EXISTS idx_support_thread_waiting_queue
    ON chat.support_thread (created_at) WHERE status = 'WAITING';
