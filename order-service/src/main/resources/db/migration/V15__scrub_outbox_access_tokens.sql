-- 결제 아웃박스 행에 남은 사용자 액세스 토큰을 지운다 (docs/54 §1.7).
--
-- PaymentRequest 는 예전에 authorizationToken(사용자의 액세스 토큰 원문)을 실었고, 그 JSON 이 이 테이블의 payload 에
-- 그대로 저장됐다. 완료된 행도 월 파티션이 DETACH 될 때까지 남는다 — DB 를 읽을 수 있으면 그 사용자로 행세할 수 있는
-- 값이 평문으로 쌓여 있었다(15분 수명이라도, 덤프·백업에는 그대로 남는다). 이제 새 행에는 싣지 않는다. 남은 것을 지운다.
--
-- STARTED 행(아직 발행 전)도 지운다 — 새 릴레이는 토큰을 읽지 않고, 옛 pay 는 헤더의 표지만 본다
-- (LegacyAuthorizationHeader). 페이로드에서 키 하나를 빼는 것이라 다른 필드는 그대로다.
--
-- 멱등하다(키가 없는 행은 건드리지 않는다). 되돌릴 수 없지만 되돌릴 이유도 없다 — 옛 이미지도 이 키 없이 동작한다
-- (페이로드의 토큰은 CDC 경로의 "존재 검사" 에만 쓰였고, 그 경로는 헤더 표지로 통과한다).

UPDATE orders.payment_outbox_event
   SET payload = payload - 'authorizationToken'
 WHERE payload ? 'authorizationToken';
