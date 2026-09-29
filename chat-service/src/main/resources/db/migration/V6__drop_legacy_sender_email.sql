-- 옛 발신자 이메일 매핑 테이블을 지운다 (docs/48 §6-⑥, 전환 정리).
--
-- V5 가 member.email 을 지우기 전에 "이메일 → member_id" 를 이 테이블로 옮겼고, 지난 릴리스의 기동 백필
-- (LegacySenderBackfill)이 몽고의 옛 문서(v1) 발신자를 senderId 로 바꾼 뒤 몽고에 이메일이 하나도 남지 않으면 행을
-- 전부 지웠다. 이번 릴리스는 그 백필 코드를 지운다 — 그래서 테이블도 지운다.
--
-- 🔴 **행이 남아 있으면 지우지 않고 실패한다.** 행이 있다 = 백필이 끝나지 않았다(전환 릴리스를 건너뛰었거나 백필이
--   몽고에 닿지 못했다). 그 상태에서 지우면 옛 문서의 발신자를 영영 되찾을 수 없고, 몽고에 옛 이메일이 남는다.
--   PostgreSQL 은 DDL 도 트랜잭션이라 실패하면 아무것도 바뀌지 않는다(Flyway 이력에도 남지 않는다) — 새 파드가
--   준비되지 못해 롤링이 멈추고 옛 파드가 계속 돈다. 그때는 전환 릴리스를 먼저 배포해 백필 로그
--   ("옛 발신자 백필: … 지운 매핑 N건")를 확인한 뒤 다시 배포한다.
--
-- ROLLBACK-UNSAFE: chat.legacy_sender_email 을 지운다. 이전 이미지는 기동 백필(실패해도 기동은 계속한다)과
--   user-deleted 처리에서 이 테이블을 읽는다 — 되돌리면 탈퇴 처리가 실패해 DLT 로 간다. 되돌려야 한다면 빈 테이블을
--   다시 만든다: CREATE TABLE chat.legacy_sender_email (email varchar(255) PRIMARY KEY, member_id bigint NOT NULL).
--   (백필이 끝났다면 원래 비어 있었으므로 잃는 데이터는 없다.)

-- 두 조건을 AND 로 묶지 않는다 — PL/pgSQL 은 조건식 전체를 한 번에 계획하므로 테이블이 없으면(새 DB) 단락 평가 전에
-- "relation does not exist" 로 실패한다. PG16 에서 확인했다.
DO $$
BEGIN
    IF to_regclass('chat.legacy_sender_email') IS NOT NULL THEN
        IF EXISTS (SELECT 1 FROM chat.legacy_sender_email) THEN
            RAISE EXCEPTION 'chat.legacy_sender_email 에 행이 남아 있다 — 옛 발신자 백필이 끝나지 않았다. '
                '전환 릴리스를 먼저 배포해 백필을 끝낸 뒤 다시 배포할 것 (docs/48 §6-⑥)';
        END IF;
    END IF;
END $$;

DROP TABLE IF EXISTS chat.legacy_sender_email;
