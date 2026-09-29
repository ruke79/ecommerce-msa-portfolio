-- 고객지원팀 상담원 역할 ROLE_SUPPORT (docs/58).
--
-- 역할은 users.users.role_id 하나다(사용자당 역할 하나). 상담원은 따로 만든 업무 계정에 이 역할을 준다 — 관리자가
-- PUT /api/admin/update-role?roleName=ROLE_SUPPORT 로. 부서·직원 정보(사번·연락처 등)는 쇼핑몰이 갖지 않는다.
--
-- 1) role_name 의 CHECK 를 넓힌다. V2 의 제약은 이름 없이 만들어져(PostgreSQL 이 roles_role_name_check 로 짓는다)
--    환경마다 이름이 같다는 보장이 없다 — role_name 을 검사하는 CHECK 를 이름과 무관하게 찾아 지운다.
-- 2) 역할 행을 심는다. id 는 V3 과 같은 이유로 직접 준다(Role 은 @Tsid — DB 에 기본값이 없다).
--
-- ROLLBACK-UNSAFE: CHECK 를 지우고 다시 거는 것은 데이터를 바꾸지 않는다. 다만 이 버전이 도는 동안 누군가 상담원
--   역할을 받았다면, 옛 이미지(AppRole 에 ROLE_SUPPORT 가 없다)는 그 사용자를 읽을 때 enum 변환에 실패한다 — 그
--   계정만 로그인이 안 된다. 되돌릴 때는 먼저 그 사용자들의 역할을 ROLE_USER 로 돌린다.

DO $$
DECLARE
    c record;
BEGIN
    FOR c IN
        SELECT conname
        FROM pg_constraint
        WHERE conrelid = 'users.roles'::regclass
          AND contype = 'c'
          AND pg_get_constraintdef(oid) LIKE '%role_name%'
    LOOP
        EXECUTE format('ALTER TABLE users.roles DROP CONSTRAINT %I', c.conname);
    END LOOP;
END $$;

ALTER TABLE users.roles
    ADD CONSTRAINT ck_roles_role_name CHECK (role_name IN ('ROLE_USER', 'ROLE_ADMIN', 'ROLE_SUPPORT'));

INSERT INTO users.roles (role_id, role_name)
SELECT 3, 'ROLE_SUPPORT'
WHERE NOT EXISTS (SELECT 1 FROM users.roles WHERE role_name = 'ROLE_SUPPORT');
