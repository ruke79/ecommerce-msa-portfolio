-- 역할 두 개를 심는다. **이게 없으면 회원가입이 아무도 못 한다.**
--
-- ── 왜 지금까지 없었나 ─────────────────────────────────────────────────
--
-- 역할을 넣던 유일한 코드는 SecurityConfig 의 CommandLineRunner 였는데 통째로 주석
-- 처리돼 있었다(테스트 계정 user1/1234 를 함께 만들던 블록이라 같이 꺼진 것으로 보인다).
-- 마이그레이션은 users.roles 테이블을 만들기만 하고 행은 넣지 않는다.
--
-- 그래서 **깨끗한 DB 에서는 회원가입이 100% 실패한다**:
--
--   UserServiceImpl.registerNewUserAccount
--     → roleRepository.findByRoleName(AppRole.ROLE_USER)
--     → .orElseThrow(new RuntimeException("Error: Role is not found."))
--     → HTTP 400 "Error: Role is not found."
--
-- 첫 사용자가 생길 수 없으니 관리자도 만들 수 없다. 실제 배포에서 여기서 막혔다.
-- 주석 처리된 부트스트랩에 의존하는 대신 스키마의 일부로 못박는다 — 마이그레이션은
-- 배포마다 반드시 실행되고, 코드처럼 "잠깐 꺼두는" 일이 생기지 않는다.
--
-- ── role_id 를 직접 주는 이유 ──────────────────────────────────────────
--
-- Role 엔티티는 @Tsid 다 — id 를 DB 가 아니라 **애플리케이션이** 만든다. 그래서
-- role_id 에는 identity 도 default 도 없고(V2 참조), SQL 로 넣으려면 값을 줘야 한다.
-- TSID 는 시각 기반이라 10^18 대역이므로 1·2 와는 절대 충돌하지 않는다.
--
-- ⚠️ 이미 역할이 있는 DB(수동으로 넣었거나 옛 CommandLineRunner 가 돌았던 DB)에서도
--    안전해야 한다. role_name 기준으로 없을 때만 넣는다 — role_id 가 무엇이든 상관없다.

INSERT INTO users.roles (role_id, role_name)
SELECT 1, 'ROLE_USER'
WHERE NOT EXISTS (SELECT 1 FROM users.roles WHERE role_name = 'ROLE_USER');

INSERT INTO users.roles (role_id, role_name)
SELECT 2, 'ROLE_ADMIN'
WHERE NOT EXISTS (SELECT 1 FROM users.roles WHERE role_name = 'ROLE_ADMIN');
