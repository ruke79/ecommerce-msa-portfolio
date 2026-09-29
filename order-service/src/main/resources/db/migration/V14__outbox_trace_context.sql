-- 아웃박스를 건너도 추적이 이어지게 — 행을 쓴 순간의 추적 문맥을 남긴다 (docs/51 §4e).
--
-- 행은 요청(또는 리스너) 안에서 쓰이지만 보내는 것은 몇 초 뒤 스케줄러다. 추적 문맥은 스레드에 붙어
-- 있어 스케줄러에는 없었고, Kafka 헤더에 실리는 추적은 스케줄러가 새로 시작한 것이었다. Zipkin 에서
-- 요청은 DB 에 행을 쓰고 끝났고, 그 뒤의 사가는 따로 떨어진 추적이었다.
--
-- trace_context: 전파 헤더를 "이름=값" 줄로 이은 것(W3C 면 traceparent=00-<traceId>-<spanId>-01).
-- 릴레이가 이 값으로 스팬을 되살린 채 보낸다(common 의 OutboxTraceContext).
--
-- 추가만 한다. nullable 이라 옛 이미지의 INSERT 도 통과하고, 값이 없는 옛 행은 예전처럼 보낸다.
-- 파티션 테이블이어도 부모에 더하면 모든 파티션에 붙는다.

ALTER TABLE orders.payment_outbox_event ADD COLUMN IF NOT EXISTS trace_context varchar(512);
