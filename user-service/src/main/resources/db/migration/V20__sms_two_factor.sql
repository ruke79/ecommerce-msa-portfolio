-- 로그인 2단계 인증의 두 번째 수단 — SMS(docs/24 §10). 켜 둠 여부와 켤 때의 번호 결속값을 user-service 가 갖는다.
-- [JA] ログイン二段階認証の二つ目の手段 — SMS。有効かどうかと、有効化時の番号の結束値を user-service が持つ。
--
-- 왜 여기인가: 로그인(비밀번호 → 대기 토큰 → 코드)이 이 서비스에 있다. 켜 둠 여부를 notification 에 두면 로그인마다 그쪽에 물어야 하고,
-- 그쪽이 닿지 않을 때 "모두 막기"(가용성) 와 "모두 통과"(SMS 2단계 인증 우회) 사이에서 골라야 한다. 번호 자체는 복제하지 않는다 —
-- sms_two_factor_binding 은 notification 이 준 HMAC(번호)이고, 등록 번호가 바뀌면 그쪽이 발송을 거절한다(세션만으로 번호를 바꿔
-- 코드를 가로채지 못하게).
-- 되돌리기 안전: 열을 더하기만 한다(옛 이미지는 읽지 않고, 새 행의 기본값은 꺼짐). 상수 기본값의 열 추가는 표를 다시 쓰지 않는다.
ALTER TABLE users.users ADD COLUMN IF NOT EXISTS sms_two_factor_enabled boolean NOT NULL DEFAULT false;
ALTER TABLE users.users ADD COLUMN IF NOT EXISTS sms_two_factor_binding varchar(64);
