-- 예약 작업의 실행 임대(lease) — 레플리카가 여럿이어도 한 회차는 한 파드만 돈다(CustomerTierScheduler).
-- [JA] 予約ジョブの実行リース — レプリカが複数でも一回分は一つの Pod だけが走る。
--
-- ROLLBACK-SAFE: 새 테이블 하나. 기존 데이터를 건드리지 않는다. 옛 이미지는 이 테이블을 모른다(쓰지 않을 뿐이다).
--
-- 🐛 등급 배치는 user-service 의 모든 파드(replicas 2, HPA min 2)에서 같은 시각에 돌았다. 각 파드가 자기 시작 시각으로
--    tier_computed_at 을 찍고 그보다 오래된 행을 강등하므로, 늦게 시작한 회차가 먼저 끝나면 앞 회차가 덮어쓴 VIP 를
--    BASIC 으로 내렸다(하루 동안 등급 쿠폰 대상에서도 빠졌다).
--
-- 잡는 쪽: INSERT … ON CONFLICT DO UPDATE … WHERE locked_until <= now() — 빈 자리거나 만료된 임대만 가져간다. 시각은 DB 의
-- now() 하나로 판정한다(파드끼리 시계가 달라도 된다). 파드가 도중에 죽으면 임대는 locked_until 에 저절로 풀린다.
CREATE TABLE IF NOT EXISTS users.scheduler_lock (
    name          varchar(64)  NOT NULL,
    locked_until  timestamptz  NOT NULL,
    -- 누가 잡았나(파드 이름 + 난수) — 풀 때 남의 임대를 풀지 않게.
    locked_by     varchar(128) NOT NULL,
    PRIMARY KEY (name)
);
