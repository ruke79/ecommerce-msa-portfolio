-- 고객센터 채팅 — 고객 ↔ 고객지원팀 상담 스레드 (docs/58).
--
-- 친구 채팅(chat_room)과 따로 둔다. 친구 채팅방은 누구나 만들고 누구나 들어가는 공개 방이다 — 상담은 정반대로
-- **고객 본인과 상담원만** 볼 수 있어야 한다. 같은 표에 type 컬럼 하나를 더하면, 공개 방을 전제로 짠 목록·기록
-- 조회가 상담을 흘리지 않는지를 매번 따져야 한다.
--
-- 메시지 본문은 친구 채팅과 같은 곳(MongoDB chat 컬렉션)에 roomId = 'support-<thread_id>' 로 쌓는다. 여기에는
-- 상담의 상태(대기·상담 중·종료), 담당 상담원, 목록에 필요한 마지막 메시지 요약만 둔다.
--
--   WAITING — 고객이 문의를 열었고 아직 아무도 맡지 않았다(agent_id 없음)
--   ACTIVE  — 상담원이 맡았다(agent_id 있음)
--   CLOSED  — 끝났다. 다시 문의하면 새 스레드가 열린다
--
-- 고객당 열린(WAITING·ACTIVE) 스레드는 하나다 — 부분 유니크 인덱스. 같은 고객이 두 탭에서 동시에 "문의하기" 를
-- 눌러도 스레드가 둘 생기지 않는다(애플리케이션은 충돌하면 다시 읽는다).

CREATE TABLE IF NOT EXISTS chat.support_thread (
    thread_id            bigserial    PRIMARY KEY,
    customer_id          bigint       NOT NULL REFERENCES chat.member (member_id),
    agent_id             bigint       REFERENCES chat.member (member_id),
    status               varchar(16)  NOT NULL DEFAULT 'WAITING',
    -- 마지막 말을 고객이 했는가 — 상담 데스크의 "답변 필요" 표시.
    awaiting_reply       boolean      NOT NULL DEFAULT false,
    last_message_preview varchar(200),
    last_message_at      timestamptz,
    created_at           timestamptz  NOT NULL DEFAULT now(),
    assigned_at          timestamptz,
    closed_at            timestamptz,
    CONSTRAINT ck_support_thread_status CHECK (status IN ('WAITING', 'ACTIVE', 'CLOSED')),
    -- 상담 중이면 담당자가 있다. 대기 중이면 없다. 종료는 둘 다 가능하다(대기 중에 고객이 닫을 수 있다).
    CONSTRAINT ck_support_thread_agent CHECK (
        (status = 'ACTIVE' AND agent_id IS NOT NULL) OR (status = 'WAITING' AND agent_id IS NULL) OR status = 'CLOSED')
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_support_thread_open_customer
    ON chat.support_thread (customer_id) WHERE status <> 'CLOSED';

-- 데스크 목록: 상태별, 최근 대화 순.
CREATE INDEX IF NOT EXISTS idx_support_thread_status_recent
    ON chat.support_thread (status, last_message_at DESC NULLS LAST);

-- 데스크 "내 담당".
CREATE INDEX IF NOT EXISTS idx_support_thread_agent
    ON chat.support_thread (agent_id, status) WHERE agent_id IS NOT NULL;
