-- 로그인 실패 횟수 기반 계정 잠금.
--
-- 지금까지 비밀번호 대입을 막는 것은 게이트웨이의 IP 레이트리밋뿐이었다. 그것만으로는
-- 부족한 이유:
--   · IP 를 바꿔 가며 한 계정을 노리는 공격(크리덴셜 스터핑)에는 IP 버킷이 무력하다
--   · users.account_non_locked 컬럼은 있었지만 **아무도 세지 않았고**, 커스텀
--     AuthenticationProvider 는 그 플래그를 검사조차 하지 않았다 — 장식이었다
--
-- 규칙(코드의 기본값): 연속 5회 실패 → 15분 잠금. 성공하면 카운터 리셋. 만료는 자동
-- 해제라 운영자 개입이 필요 없다 — 영구 잠금은 "고객이 전화할 때까지 서비스 불능"과
-- 같은 말이라 일부러 피했다.
--
-- 잠금 상태는 lock_expires_at 하나로 판정한다(값이 있고 미래면 잠김). account_non_locked
-- 를 재사용하지 않는 이유: 그 컬럼은 만료 시각을 담을 수 없어 "언제 풀리나"를 답할 수
-- 없고, 수동 잠금(운영자)과 자동 잠금(대입 방어)을 섞으면 해제 로직이 서로를 덮어쓴다.

ALTER TABLE users.users
    ADD COLUMN IF NOT EXISTS failed_login_attempts int NOT NULL DEFAULT 0;

ALTER TABLE users.users
    ADD COLUMN IF NOT EXISTS lock_expires_at timestamp(6);
