-- 메일 종류(mail_message.kind)에 PAYMENT_REFUNDED(환불 완료)를 더한다 — 가드 B-55 가 찾았다.
--
-- V3 가 만든 check (kind in (...)) 는 그 시점의 MailKind 여덟 값이다. 환불 알림을 붙이며
-- enum 에 PAYMENT_REFUNDED 를 더했는데, 개인 알림(V7)만 넓히고 이 표를 잊었다 — 그 메일을
-- 처음 큐에 넣는 순간 check 위반으로 죽었을 것이다. 빌드의 가드가 배포 전에 잡았다.
--
-- V6·V7 과 같은 방식: 컬럼 번호(conkey)로 그 컬럼의 CHECK 를 찾아 지우고 같은 이름으로 다시 건다.
-- 되돌리기는 안전하다 — 허용 범위를 넓히기만 한다.

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
                    'COUPON_ISSUED', 'SECURITY_ALERT'));
