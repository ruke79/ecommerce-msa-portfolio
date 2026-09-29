-- 전체 사용자에게 보내는 알림 — 공지사항 게시, 관리자 안내.
--
-- ROLLBACK-SAFE: 새 테이블 둘만 만든다. 기존 데이터를 건드리지 않는다.
--
-- ── 왜 개인 알림 행을 만들지 않는가 ──────────────────────────────────────
--
-- 공지 하나에 사용자 수만큼 notification 행을 만들면 사용자가 10만이면 공지 한 건에
-- 10만 insert 다. 테이블은 사용자 수 × 공지 수로 부풀고, 공지 내용을 고치면 10만 행을
-- 전부 고쳐야 한다.
--
-- 여기는 공지당 **행 하나**다. 개인별로 필요한 것은 "읽었는가" 뿐이고 그것만 아래
-- broadcast_read 에 남긴다 — **읽은 사람만** 행이 생기므로 안 읽은 상태는 공짜다.

CREATE TABLE IF NOT EXISTS notification.broadcast_notification (
    broadcast_id    bigint not null,
    type            varchar(32) not null,
    title           varchar(255) not null,
    content         varchar(1000),
    url             varchar(512),
    -- 같은 사건이 재전달돼도 공지가 하나만 생기게 하는 키. 카프카는 at-least-once 다.
    source_event_id varchar(255),
    starts_at       timestamp(6) not null,
    -- null 이면 만료 없음.
    ends_at         timestamp(6),
    created_at      timestamp(6) not null,
    primary key (broadcast_id),
    constraint uq_broadcast_source_event unique (source_event_id)
);

-- 목록은 "지금 유효한 것"을 시간 역순으로 읽는다.
CREATE INDEX IF NOT EXISTS idx_broadcast_starts
    ON notification.broadcast_notification (starts_at);

CREATE INDEX IF NOT EXISTS idx_broadcast_source_event
    ON notification.broadcast_notification (source_event_id);

CREATE TABLE IF NOT EXISTS notification.broadcast_read (
    broadcast_id bigint not null,
    recipient_id bigint not null,
    read_at      timestamp(6),
    -- 공지 자체는 지울 수 없다(모두가 공유하는 행이다). "이 사람에게는 보이지 않는다"로
    -- 표현한다. 읽음과 따로 두는 이유: 읽었지만 목록에 남겨 두는 것과 치워 버린 것은
    -- 사용자에게 다른 동작이다.
    dismissed    boolean not null default false,
    -- 복합 키라 같은 사람이 같은 공지에 두 행을 가질 수 없다 — 읽음 처리가 여러 번
    -- 들어와도 결과가 같다.
    primary key (broadcast_id, recipient_id)
);

-- 안읽음 수를 셀 때 "이 사람이 읽은 것" 을 빼야 한다.
CREATE INDEX IF NOT EXISTS idx_broadcast_read_recipient
    ON notification.broadcast_read (recipient_id);

-- FK 를 걸지 않는 이유는 coupon_target(V5) 과 같다: broadcast_id 는 애플리케이션이
-- TSID 로 만들고, 공지 삭제 경로에서 서비스가 읽음 행도 함께 지운다. ON DELETE CASCADE 를
-- 걸면 삭제가 조용해져 고아 행이 생겨도 드러나지 않는다. 고아 행은 조회 시 조인되는
-- 공지가 없으므로 아무 영향이 없다.
