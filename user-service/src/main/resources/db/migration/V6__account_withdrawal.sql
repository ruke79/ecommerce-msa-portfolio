-- 회원 탈퇴 — 신청 후 유예 기간, 그 뒤 최종 처리.
--
-- 탈퇴를 누르는 즉시 지우지 않는 이유는 되돌릴 수 없기 때문이다. 홧김에 눌렀거나, 계정을
-- 빼앗긴 사람이 눌렸거나(공격자가 로그인해 탈퇴를 신청하면 피해자의 계정이 사라진다),
-- 결제 분쟁이 진행 중일 수 있다. 유예 기간은 그 모든 경우에 되돌릴 시간을 준다.
--
-- **세 컬럼의 역할이 각각 다르다.**
--   · withdrawal_requested_at — 신청 시각. 유예 기간 계산의 기준이다. 취소하면 NULL 로
--     돌아간다. 다시 신청하면 새로 찍힌다.
--   · withdrawn_at — 최종 처리 시각. 값이 있으면 이미 익명화가 끝난 행이다. 이 컬럼이
--     "처리 완료" 표식이라, 배치가 같은 계정을 두 번 처리하지 않는다.
--   · withdrawal_reason — 왜 떠나는지. 통계용이며 사용자가 직접 쓴 문장이 들어올 수
--     있으므로 로그에 남기지 않는다.
--
-- **상태를 boolean 이 아니라 시각으로 두는 이유.** "탈퇴 신청함" 플래그 하나로는 언제
-- 지워지는지 답할 수 없다. 화면이 "3일 남았습니다"를 보여 주려면 신청 시각이 필요하고,
-- 유예 기간을 나중에 7일에서 30일로 바꿔도 시각만 있으면 그대로 재계산된다.

ALTER TABLE users.users
    ADD COLUMN IF NOT EXISTS withdrawal_requested_at timestamp(6);

ALTER TABLE users.users
    ADD COLUMN IF NOT EXISTS withdrawn_at timestamp(6);

ALTER TABLE users.users
    ADD COLUMN IF NOT EXISTS withdrawal_reason varchar(255);

-- 배치가 찾는 것은 "신청했고 아직 처리 안 된" 소수의 행뿐이다. 부분 인덱스로 두면
-- 인덱스가 전체 회원 수가 아니라 처리 대기 중인 건수만큼만 커진다.
CREATE INDEX IF NOT EXISTS idx_users_withdrawal_pending
    ON users.users (withdrawal_requested_at)
    WHERE withdrawal_requested_at IS NOT NULL AND withdrawn_at IS NULL;
