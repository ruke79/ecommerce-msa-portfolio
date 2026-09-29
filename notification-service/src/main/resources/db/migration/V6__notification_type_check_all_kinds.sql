-- 알림 종류 CHECK 제약에 나중에 더한 세 값이 없었다 (가드 B-55).
--
-- 베이스라인(V2)의 check (type in (...)) 는 그 시점의 NotificationType 여덟 값이다. 그 뒤
-- NOTICE_PUBLISHED · ANNOUNCEMENT · EVENT_STARTED 를 더했는데 제약은 그대로였다. 그 종류의
-- 알림을 개인 알림(notification.notification)으로 저장하는 순간 check 위반으로 죽는다 —
-- pay-service 의 payment_type 이 실제로 그렇게 터진 것을 보고 함께 찾았다.
-- (broadcast_notification 은 V4 에서 check 없이 만들어져 해당 없음.)
--
-- 되돌리기는 안전하다 — 허용 범위를 넓히기만 한다.
--
-- ⚠️ 기존 제약은 **컬럼 번호(conkey)** 로 찾는다. 첫 판은 pg_get_constraintdef() 의 문자열을
--    '%(type %' 로 찾았는데, PostgreSQL 은 CHECK 정의를 `((type)::text = ANY (ARRAY[...]))` 로
--    다시 써서 보여 주므로 아무것도 못 찾았고, 지우지 못한 채 같은 이름으로 다시 걸다
--    "already exists" 로 죽었다(롤백됨 — 이 파일은 그 뒤에 고친 것이다). 컬럼 번호는
--    표현 방식과 무관하다.

DO $$
DECLARE
    c record;
BEGIN
    FOR c IN
        SELECT con.conname
          FROM pg_constraint con
          JOIN pg_attribute att
            ON att.attrelid = con.conrelid
           AND att.attnum = ANY (con.conkey)
         WHERE con.conrelid = 'notification.notification'::regclass
           AND con.contype = 'c'
           AND att.attname = 'type'
    LOOP
        EXECUTE format('ALTER TABLE notification.notification DROP CONSTRAINT %I', c.conname);
    END LOOP;
END $$;

ALTER TABLE notification.notification
    ADD CONSTRAINT notification_type_check
    CHECK (type IN ('ORDER_PLACED', 'PAYMENT_COMPLETED', 'PAYMENT_FAILED', 'SHIPPING_UPDATED',
                    'CHAT_MESSAGE', 'FRIEND_ONLINE', 'COUPON_ISSUED', 'SECURITY_ALERT',
                    'NOTICE_PUBLISHED', 'ANNOUNCEMENT', 'EVENT_STARTED'));
