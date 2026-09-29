-- 재시도 간격 — "몇 번 시도하는가" 옆에 "언제 다시 시도하는가" 를 둔다.
--
-- ROLLBACK-SAFE: 컬럼 둘과 인덱스 둘을 더한다. 기존 행은 NULL 이 되고, 선점 질의가
-- NULL 을 "지금 보내도 된다" 로 읽으므로 배포 직후 동작이 달라지지 않는다.
--
-- ── 왜 필요한가 ──────────────────────────────────────────────────────────
--
-- 상한만 있고 간격이 없었다. 디스패처는 5초마다 돌고 선점 질의는 상한에 닿지 않은 행을
-- 무조건 다시 집었으므로, 메일의 5회 재시도가 **20초 안에 전부 소진**됐다. 운영 로그:
--
--   04:12:10 [MAIL:retryable] … 04:12:15 … 04:12:20 … 04:12:25 … 04:12:30 포기
--
-- MailDispatcher 의 주석은 상한을 5로 둔 이유를 "그레이리스팅을 쓰는 서버는 처음 온
-- 발신자를 일부러 한 번 거절하고 재시도를 기다린다" 라고 적어 두었는데, 그 기다림은 보통
-- **수 분**이다. 5초 간격으로 다섯 번 때리는 것은 한 번 때리는 것과 결과가 같다 —
-- 주석이 막겠다고 한 실패가 그대로 일어났다.
--
-- SMS 도 같은 모양이었다(3회 × 5초 = 15초).

ALTER TABLE notification.mail_message
    ADD COLUMN IF NOT EXISTS next_attempt_at timestamp(6);

ALTER TABLE notification.notification
    ADD COLUMN IF NOT EXISTS sms_next_attempt_at timestamp(6);

-- 선점 질의는 "보낼 상태" + "시각이 됐다" 로 좁힌다. 부분 인덱스를 쓰는 이유: 종료된 행
-- (SENT/FAILED)이 시간이 갈수록 테이블의 대부분을 차지하는데, 폴러는 그것들을 한 번도
-- 보지 않는다. 전체 인덱스를 만들면 폴러가 쓰지 않는 항목에 쓰기 비용만 계속 낸다.
--
-- ⚠️ 기존 idx_mail_message_status (status, created_at) 는 남겨 둔다 — 운영에서 "어떤
--    메일이 실패했나" 를 상태로 훑는 조회가 그것을 쓴다.
CREATE INDEX IF NOT EXISTS idx_mail_message_due
    ON notification.mail_message (next_attempt_at, created_at)
    WHERE status IN ('PENDING', 'RETRYABLE');

CREATE INDEX IF NOT EXISTS idx_notification_sms_due
    ON notification.notification (sms_next_attempt_at, created_at)
    WHERE sms_status IN ('PENDING', 'RETRYABLE');
