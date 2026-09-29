-- 알림 종류에 PAYMENT_REFUNDED(환불 완료)를 더한다 (가드 B-55 가 요구한다).
--
-- V6 와 같은 방식 — 그 컬럼의 CHECK 를 컬럼 번호(conkey)로 찾아 지우고 같은 이름으로 다시 건다.
-- 되돌리기는 안전하다: 허용 범위를 넓히기만 한다.

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
    CHECK (type IN ('ORDER_PLACED', 'PAYMENT_COMPLETED', 'PAYMENT_FAILED', 'PAYMENT_REFUNDED',
                    'SHIPPING_UPDATED', 'CHAT_MESSAGE', 'FRIEND_ONLINE', 'COUPON_ISSUED',
                    'SECURITY_ALERT', 'NOTICE_PUBLISHED', 'ANNOUNCEMENT', 'EVENT_STARTED'));
