-- 채팅 회원의 이메일을 없앤다 (docs/48 §6-⑥, "남은 것").
--
-- chat 의 신원은 uid(member_id) 다. member.email 이 남은 이유는 하나 — 옛 대화 기록(몽고 v1)이 발신자를 이메일로
-- 적었고, 그것을 id 로 바꾸려면 "이메일 → member_id" 가 필요하다. 그런데 이 마이그레이션은 기동 때 백필
-- (LegacySenderBackfill)보다 **먼저** 돈다. 컬럼만 지우면 백필이 매핑을 잃는다.
--
-- 그래서 매핑을 전용 테이블로 옮긴 뒤 컬럼을 지운다. 백필은 이 테이블로 바꾸고, 몽고에 이메일이 하나도 남지 않으면
-- 이 테이블의 행을 **전부 지운다**(개인정보를 필요한 동안만 둔다). 테이블 자체는 다음 릴리스에서 지운다.
-- 한 번의 배포로 끝나도록 — 백필이 끝났는지 사람이 확인하고 두 번 나눠 배포할 필요가 없게 — 이렇게 했다.
--
-- ROLLBACK-UNSAFE: member.email 컬럼을 지운다. 이 버전 이전의 이미지는 ChatMember 에 email 이 있어 Hibernate
--   validate 에서 기동하지 못한다(롤링 중 잠깐 겹치는 옛 파드도 회원 조회가 실패한다 — chat 은 1 레플리카라 겹침은
--   새 파드가 준비될 때까지다). 되돌리려면 `ALTER TABLE chat.member ADD COLUMN email varchar(255)` 가 필요하고,
--   값은 백필 전이라면 chat.legacy_sender_email 에서 되살릴 수 있다(백필 뒤에는 비어 있다 — 옛 이미지는 이메일
--   없이는 채팅 회원을 찾지 못한다).

CREATE TABLE IF NOT EXISTS chat.legacy_sender_email (
    email     varchar(255) PRIMARY KEY,
    member_id bigint       NOT NULL
);

INSERT INTO chat.legacy_sender_email (email, member_id)
SELECT email, member_id FROM chat.member WHERE email IS NOT NULL
ON CONFLICT (email) DO NOTHING;

ALTER TABLE chat.member DROP CONSTRAINT IF EXISTS uk_member_email;
DROP INDEX IF EXISTS chat.idx_member_email;
ALTER TABLE chat.member DROP COLUMN IF EXISTS email;
