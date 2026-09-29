-- 릴리스 가드 — 스키마는 바꾸지 않는다 (docs/49 §3 C2).
--
-- 이 릴리스(C2)부터 order 는 결제 요청을 v4(paypalOrderId 제거)로 쓴다. 롤링 중 남아 있는 옛 pay·saga-projection 이 v4
-- 지문을 알려면 앞 릴리스(C1 — 디코더에 v4 를 먼저 넣었다)가 **이미 다 굴러가 있어야** 한다. C1 을 건너뛰고 이 릴리스를
-- 올리면 옛 소비자가 v4 레코드를 못 읽어 결제 요청이 DLT 로 가고 스트림이 멈춘다.
--
-- C1 은 DB 를 바꾸지 않아서 릴리스 표지 V19 를 남겼다. 그 적용 시각이 이번 실행에서 막 찍혔다면(= C1 과 C2 를 한 배포로
-- 올렸다) 실패한다. 새 order 파드가 준비되지 못하므로 v4 레코드도 나가지 않고 옛 파드가 계속 돈다. C1 이 다 굴러간 뒤(30분)
-- 다시 배포하면 통과한다. 새 DB(모든 마이그레이션이 한 번에 도는 경우)와 이력 테이블이 없는 경우는 통과시킨다 — V18 과 같다.
--
-- 이 파일도 아무것도 바꾸지 않지만 지우면 안 된다 — 이력에 남아 "C2 가 배포됐다" 는 표지가 된다.

DO $$
DECLARE
    v19   timestamp;
    first timestamp;
BEGIN
    IF to_regclass('flyway_schema_history') IS NOT NULL THEN
        SELECT installed_on INTO v19 FROM flyway_schema_history WHERE version = '19' AND success;
        SELECT min(installed_on) INTO first FROM flyway_schema_history WHERE success;
        IF v19 IS NOT NULL
           AND v19 > first + interval '10 minutes'
           AND v19 > localtimestamp - interval '30 minutes' THEN
            RAISE EXCEPTION 'V19 가 방금(%) 적용됐다 — 결제 요청 v4 의 C1(읽는 쪽 먼저)을 먼저 배포하고 다 굴러간 뒤(30분) 다시 배포할 것 (docs/49 §3)', v19;
        END IF;
    END IF;
END $$;
