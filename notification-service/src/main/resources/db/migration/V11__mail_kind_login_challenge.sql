-- 위험 기반 로그인의 확인 코드 메일(LOGIN_CHALLENGE, user-service docs/68 §19)을 mail_message.kind 에 허용한다.
-- [JA] リスクベースログインの確認コードメール(LOGIN_CHALLENGE)を mail_message.kind に許可する。
--
-- V10 이 다시 만든 check 는 그 시점의 MailKind 열한 값이다. 새 값을 넣지 않으면 확인 메일 행이 INSERT 에서 거절되어
-- 메일이 나가지 않는다 — 공격 신호가 있을 때 정상 사용자도 로그인하지 못한다. 이름이 무엇이든 kind 의 check 를 모두 지우고 다시 만든다(V8 · V10 과 같은 방식).
-- 되돌리기 안전: 허용 값을 늘리기만 한다.
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
    CHECK (kind IN ('REGISTRATION_CONFIRM', 'PASSWORD_RESET', 'LOGIN_CHALLENGE', 'ORDER_PLACED',
                    'PAYMENT_COMPLETED', 'PAYMENT_FAILED', 'PAYMENT_REFUNDED', 'SHIPPING_UPDATED',
                    'ORDER_COMPLETED', 'COUPON_ISSUED', 'SECURITY_ALERT', 'RESTOCK_AVAILABLE'));
