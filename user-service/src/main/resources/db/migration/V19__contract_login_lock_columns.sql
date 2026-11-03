-- contract — 로그인 실패 잠금의 옛 열 둘을 지운다(V4). 횟수 · 잠금은 Redis(LoginAttemptService)이고 2026-10-16 엔티티에서
-- 필드를 뺐다(UserLockFieldsTest). 그 이미지(4415e498565c 묶음 릴리스)부터 아무도 읽거나 쓰지 않는다.
-- failed_login_attempts 는 NOT NULL DEFAULT 0 이라 필드가 없어도 INSERT 가 됐다 — 지우면 그 기본값도 함께 사라진다.
-- [JA] ログイン失敗ロックの旧列二つを削除する(V4)。回数 · ロックは Redis で、エンティティからは 2026-10-16 に外した。
--
-- ROLLBACK-UNSAFE: 열을 지운다 — 4415e498565c 전 이미지(b7c5053a45e8 등)는 users.lock_expires_at · failed_login_attempts 를
--   매핑한다(Hibernate validate 에서 기동 실패). 대비책: 그 뒤의 이미지(4415e498565c ~ d68f0307e979)는 두 열을 쓰지 않아 이미지만
--   되돌려도 뜬다 — ops/28 이 자동 복귀를 끄므로 `FORCE_UNSAFE_ROLLBACK=1 ops/28-deploy.sh <env> --rollback`. 그보다 옛 이미지로
--   가야 하면 먼저 V4 의 두 문장(`ADD COLUMN IF NOT EXISTS failed_login_attempts int NOT NULL DEFAULT 0` ·
--   `ADD COLUMN IF NOT EXISTS lock_expires_at timestamp(6)`)을 다시 실행한다 — 값은 0 · 비어 있음(잠금은 원래 Redis 에 있다).
-- 락: DROP COLUMN 은 카탈로그만 바꾸지만 잠깐 ACCESS EXCLUSIVE 를 잡는다 — 로그인 · 토큰 갱신이 그동안 기다린다.
--   배포 직전 pg_stat_activity 확인, 백업(03:00 UTC) 창은 피한다(docs/85 §8-1).

ALTER TABLE users.users DROP COLUMN IF EXISTS lock_expires_at;
ALTER TABLE users.users DROP COLUMN IF EXISTS failed_login_attempts;
