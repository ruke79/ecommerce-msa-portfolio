-- 비밀번호 재설정 토큰을 가입 확인 토큰과 같은 수준으로 올린다.
--
-- ROLLBACK-UNSAFE: 기존 password_reset_token 행을 전부 지운다. 이유는 아래 ②.
--
-- ── 왜 ────────────────────────────────────────────────────────────────────
--
-- 두 토큰을 나란히 놓고 보면 보호 수준이 거꾸로였다:
--
--                        가입 확인 토큰        비밀번호 재설정 토큰
--   저장                 SHA-256 해시          평문
--   사용자당              1개 (user_id UNIQUE)  요청할 때마다 새 행
--   할 수 있는 일         계정 활성화           계정 탈취
--
-- **더 강력한 토큰이 더 약하게 보호되고 있었다.** DB 덤프 한 번이면 살아 있는 재설정
-- 링크가 전부 노출되고, 그 링크 하나로 비밀번호를 바꿔 계정을 가져갈 수 있다.
-- VerificationToken 의 클래스 주석은 해시를 쓰는 이유를 이미 적어 두었는데, 그 규칙이
-- 이쪽에는 적용되지 않았다.

-- ① 비밀번호가 마지막으로 바뀐 시각.
--
--    세션 무효화의 근거다. 리프레시 토큰은 Redis 에 sessionId 로만 저장돼 있어
--    "이 사용자의 모든 세션" 을 뒤집어 찾을 수 없고, RedisSessionManager 는 이름과 달리
--    **인메모리 HashMap** 이라 레플리카마다 따로 논다. 그래서 토큰을 찾아 지우는 대신,
--    발급 시각(iat)이 이 시각보다 이른 리프레시 토큰을 거절한다 — DB 한 곳만 보므로
--    레플리카 수와 무관하게 동작한다.
--
--    NULL 은 "한 번도 바꾼 적 없음" 이고 아무 토큰도 거절하지 않는다. 기본값을 now() 로
--    넣으면 배포 순간 **모든 사용자가 로그아웃된다.**
ALTER TABLE users.users
    ADD COLUMN IF NOT EXISTS password_changed_at timestamp(6);

-- ② 평문으로 저장돼 있던 재설정 토큰을 지운다.
--
--    앞으로 token 컬럼에는 SHA-256 해시가 들어간다. 기존 행은 평문이라 어차피 대조에
--    걸리지 않아 죽은 행이 되는데, 죽었다고 두면 **평문 자격증명이 DB 에 그대로 남는다.**
--    지우는 것이 목적이다.
--
--    잃는 것: 아직 안 쓴 재설정 링크가 무효가 된다. 실제로는 잃을 것이 없다 —
--    프론트에 /reset-password 화면이 아예 없어서 그 링크는 애초에 쓸 수 없었다.
--    사용자는 비밀번호 찾기를 다시 요청하면 된다.
DELETE FROM users.password_reset_token;

-- ③ 사용자당 재설정 토큰은 하나만 산다.
--
--    예전에는 "비밀번호 찾기" 를 누를 때마다 새 행이 쌓였고, 이전 링크는 아무도 무효화하지
--    않았다. 열 번 누르면 24시간짜리 유효 링크가 열 개 살아 있게 된다 — 메일함이 털렸을 때
--    공격자가 고를 수 있는 링크가 그만큼 늘어난다.
--
--    VerificationToken 이 user_id UNIQUE 로 같은 규칙을 이미 쓰고 있다.
ALTER TABLE users.password_reset_token
    DROP CONSTRAINT IF EXISTS uq_password_reset_token_user;
ALTER TABLE users.password_reset_token
    ADD CONSTRAINT uq_password_reset_token_user UNIQUE (user_id);
