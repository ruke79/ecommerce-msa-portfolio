-- 인증번호의 용도를 나눈다 — 번호 등록(PHONE)과 로그인 2단계 인증(LOGIN_2FA, user-service docs/24 §10).
-- [JA] 認証番号の用途を分ける — 番号登録(PHONE)とログイン二段階認証(LOGIN_2FA)。
--
-- 왜: 번호 등록 검증은 "이 번호로 발급된 가장 최근 코드" 를 본다. 같은 번호로 로그인 코드가 나가면 그것이 최근 코드가 되어
-- 등록 화면의 코드가 무효가 되거나, 거꾸로 등록 코드가 로그인에 쓰일 수 있다. 용도마다 따로 찾는다.
-- 되돌리기 안전: 열을 더하기만 한다(기존 행 · 옛 이미지의 INSERT 는 기본값 PHONE). PostgreSQL 11+ 은 상수 기본값의 열 추가가
-- 표를 다시 쓰지 않는다. 표는 하루 지난 코드를 매일 지우므로(04:20) 작다 — 색인도 그냥 만든다.
ALTER TABLE notification.phone_verification
    ADD COLUMN IF NOT EXISTS purpose varchar(16) NOT NULL DEFAULT 'PHONE';

ALTER TABLE notification.phone_verification
    ADD CONSTRAINT phone_verification_purpose_check CHECK (purpose IN ('PHONE', 'LOGIN_2FA'));

CREATE INDEX IF NOT EXISTS idx_phone_verification_two_factor
    ON notification.phone_verification (user_id, purpose, created_at);
