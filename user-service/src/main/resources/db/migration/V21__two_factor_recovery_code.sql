-- 2단계 인증 복구 코드(docs/57 §22) — 인증 앱 · 휴대폰을 잃은 사람이 들어올 길.
-- [JA] 二段階認証のリカバリーコード — 認証アプリ · 携帯を失った人が入る道。
--
-- 🐛 예전에는 복구 수단이 없었다 — 인증 앱을 지우거나 번호를 바꾸면(SMS 수단은 켤 때의 번호에 묶인다) 비밀번호를 알아도 들어올 수
-- 없었고, 비밀번호 재설정은 2단계 인증을 풀지 않는다(풀면 메일함만 턴 사람이 2단계 인증을 건너뛴다).
--
-- 코드 원문은 두지 않는다 — HMAC-SHA256(JWT 비밀에서 파생한 키, 코드)만. DB 만 샌 사람은 코드를 되돌릴 수 없다.
-- 한 번 쓰면 used_at 이 찬다(행은 남긴다 — 몇 개 남았는지 · 언제 썼는지). 새로 만들면 그 사용자의 행을 모두 지우고 다시 쓴다.
-- 되돌리기 안전: 새 표 하나(옛 이미지는 읽지 않는다).
CREATE TABLE IF NOT EXISTS users.two_factor_recovery_code (
    code_id    bigserial PRIMARY KEY,
    user_id    bigint      NOT NULL,
    code_hash  varchar(64) NOT NULL,
    created_at timestamp   NOT NULL,
    used_at    timestamp,
    CONSTRAINT uk_two_factor_recovery_code UNIQUE (user_id, code_hash)
);
