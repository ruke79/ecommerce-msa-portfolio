-- 구매 확정 알림 ORDER_COMPLETED 를 두 CHECK 에 더한다 (docs/25 §12, 가드 B-55).
--
-- V7·V8 에서 환불(PAYMENT_REFUNDED)을 더할 때 겪은 대로, enum 에 값을 더하면 개인 알림
-- (notification.type)과 메일 큐(mail_message.kind) **둘 다** 넓혀야 한다. 한쪽을 잊으면 그
-- 종류의 첫 알림이 check 위반으로 죽는다. 이번에는 한 파일에서 둘을 같이 한다.
--
-- V6·V7·V8 과 같은 방식: 컬럼 번호(conkey)로 그 컬럼의 CHECK 를 찾아 지우고 같은 이름으로
-- 다시 건다. 되돌리기는 안전하다 — 허용 범위를 넓히기만 한다.

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
                    'SHIPPING_UPDATED', 'ORDER_COMPLETED', 'CHAT_MESSAGE', 'FRIEND_ONLINE',
                    'COUPON_ISSUED', 'SECURITY_ALERT', 'NOTICE_PUBLISHED', 'ANNOUNCEMENT',
                    'EVENT_STARTED'));

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
         WHERE con.conrelid = 'notification.mail_message'::regclass
           AND con.contype = 'c'
           AND att.attname = 'kind'
    LOOP
        EXECUTE format('ALTER TABLE notification.mail_message DROP CONSTRAINT %I', c.conname);
    END LOOP;
END $$;

ALTER TABLE notification.mail_message
    ADD CONSTRAINT mail_message_kind_check
    CHECK (kind IN ('REGISTRATION_CONFIRM', 'PASSWORD_RESET', 'ORDER_PLACED',
                    'PAYMENT_COMPLETED', 'PAYMENT_FAILED', 'PAYMENT_REFUNDED', 'SHIPPING_UPDATED',
                    'ORDER_COMPLETED', 'COUPON_ISSUED', 'SECURITY_ALERT'));
