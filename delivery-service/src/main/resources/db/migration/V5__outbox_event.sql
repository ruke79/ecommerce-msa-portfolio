-- docs/93 ③ 2단계: 배송 상태 이벤트를 아웃박스로 — 상태 변경과 같은 트랜잭션에 이벤트를 적는다.
--
-- 🐛 예전에는 커밋 뒤에 바로 Kafka 로 보내고 실패하면 세기만 했다(DeliveryStatusPublisher — 경보 DeliveryStatusPublishFailed).
--    브로커가 잠깐 없거나 파드가 그 사이에 죽으면 그 이벤트는 사라졌다 — 주문 상태(DISPATCHED · DELIVERED)와 알림이 멈추고,
--    상태는 이미 DB 에 있으므로 다음 폴링에서도 다시 감지되지 않았다. 이제 릴레이(DeliveryOutboxRelay)가 보낼 때까지 남는다.
--
-- 다른 아웃박스(cart · order · pay · user)처럼 created_at 월 RANGE 파티션 — 공통 파티션 잡이 앞으로 석 달을 만들고 보존이 지난 달을
-- 지운다(outbox.partitions.tables, B-70), 발행 지연 지표(outbox_pending_*)도 같은 목록에서 나온다. created_at 은 UTC(B-21).
-- 더하기만 — 옛 이미지는 이 테이블을 모른다(되돌려도 그대로 뜬다, 남은 행은 다시 올린 뒤 릴레이가 보낸다).
-- 비밀은 싣지 않는다 — 배송 상태 · 주문 id · 고객 id 뿐.
-- 처음부터 이 이름으로 만든다 — 옮길 옛 테이블이 없어 다른 서비스 V1 의 `_p` → RENAME 이 필요 없다(RENAME 은 B-54 가 되돌리기 위험으로 본다).
--
-- [JA] 配送ステータスのイベントをアウトボックスへ — 状態変更と同じトランザクションにイベントを書く。以前はコミット後に直接 Kafka へ送り、
--      失敗は数えるだけで、ブローカーが一時的にいない · その間に Pod が落ちるとイベントは消えた。他のアウトボックスと同じく月次 RANGE
--      パーティション。追加のみ。秘密は載せない。最初からこの名前で作る(移す旧テーブルがないので RENAME 不要 — B-54)。

CREATE TABLE IF NOT EXISTS delivery.outbox_event (
    id            varchar(64)  NOT NULL,
    topic         varchar(255) NOT NULL,
    msg_key       varchar(255) NOT NULL,
    payload       text         NOT NULL,
    outbox_status varchar(32)  NOT NULL,
    attempts      integer      NOT NULL DEFAULT 0,
    created_at    timestamp(6) NOT NULL,
    sent_at       timestamp(6),
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

CREATE TABLE IF NOT EXISTS delivery.outbox_event_2026_11 PARTITION OF delivery.outbox_event FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');
CREATE TABLE IF NOT EXISTS delivery.outbox_event_2026_12 PARTITION OF delivery.outbox_event FOR VALUES FROM ('2026-12-01') TO ('2027-01-01');
CREATE TABLE IF NOT EXISTS delivery.outbox_event_2027_01 PARTITION OF delivery.outbox_event FOR VALUES FROM ('2027-01-01') TO ('2027-02-01');
CREATE TABLE IF NOT EXISTS delivery.outbox_event_2027_02 PARTITION OF delivery.outbox_event FOR VALUES FROM ('2027-02-01') TO ('2027-03-01');

CREATE TABLE IF NOT EXISTS delivery.outbox_event_default PARTITION OF delivery.outbox_event DEFAULT;

-- 릴레이가 읽는 것 — 아직 보내지 못한 행을 오래된 순으로(발행 지연 측정도 같은 조건).
-- [JA] リレーが読むもの — まだ送れていない行を古い順に(発行遅延の計測も同じ条件)。
CREATE INDEX IF NOT EXISTS idx_delivery_outbox_started
    ON delivery.outbox_event (created_at) WHERE outbox_status = 'STARTED';
