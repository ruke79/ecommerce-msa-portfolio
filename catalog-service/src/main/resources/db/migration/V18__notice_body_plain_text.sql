-- 공지 본문을 대형 객체(Large Object)에서 text 열의 글로 옮긴다(docs/62 §18).
--
-- 🐛 Notice.body 가 @Lob 이었다. Hibernate 6 은 PostgreSQL 에서 @Lob String 을 대형 객체로 다룬다 — 글은 pg_largeobject 에
-- 들어가고 body 열(text)에는 그 OID 번호만 남았다(실측: "16698"). 그래서
--   · SQL · 백업 확인 · 다른 도구로 공지를 읽으면 숫자만 보였고,
--   · 공지를 고칠 때마다 새 대형 객체가 생기고 옛것은 지워지지 않았다(삭제도 같다) — 아무도 모르게 쌓였다.
-- 코드에서 @Lob 을 뗐다(열은 원래 text). 여기서는 이미 있는 행을 옮긴다.
-- [JA] Notice.body の @Lob により本文がラージオブジェクトに入り、列には OID の番号だけが残っていた。行を text に移し、
--      ラージオブジェクトを解放する。
--
-- ROLLBACK-UNSAFE: 옛 이미지(@Lob)는 body 를 OID 로 읽는다 — 옮긴 뒤의 글을 읽으면 공지 조회가 실패한다. 되돌려야 하면
--   공지 본문을 다시 대형 객체로 넣는 역마이그레이션이 필요하다(공지는 운영자만 쓰므로 그 사이 공지 화면만 막힌다).
--
-- 대형 객체는 이 저장소에서 공지 본문만 만든다(@Lob 은 이것 하나였다). 그래서 옮긴 것과, 수정 · 삭제 때 버려진 것(어느 행도
-- 가리키지 않는 것)을 함께 해제한다.

CREATE TEMP TABLE notice_lo ON COMMIT DROP AS
SELECT notice_id, lo
  FROM (SELECT notice_id,
               CASE WHEN body ~ '^[0-9]{1,10}$' AND body::bigint BETWEEN 1 AND 4294967295
                    THEN body::bigint::oid END AS lo
          FROM content.notice) n
 WHERE lo IS NOT NULL
   AND EXISTS (SELECT 1 FROM pg_largeobject_metadata m WHERE m.oid = n.lo);

UPDATE content.notice n
   SET body = convert_from(lo_get(l.lo), 'UTF8')
  FROM notice_lo l
 WHERE n.notice_id = l.notice_id;

SELECT lo_unlink(m.oid)
  FROM pg_largeobject_metadata m
 WHERE m.lomowner = (SELECT oid FROM pg_roles WHERE rolname = current_user);
