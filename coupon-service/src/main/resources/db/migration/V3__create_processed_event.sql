-- 컨슈머 멱등성 원장 (docs/12 §2, catalog 의 product.processed_event 와 같은 패턴).
--
-- coupon-service 의 payment 컨슈머는 쿠폰을 USED 로 바꾼다. Kafka 는 at-least-once 이므로
-- 같은 결제 응답이 다시 올 수 있는데, 그때 updateCouponStatus 는 NOT_USED 인 쿠폰을 찾지 못해
-- null 을 반환하고 예외가 던져진다 → **정상적인 재전달이 DLT 로 쌓인다**(가짜 장애).
-- 처리 이력을 재고 변경과 같은 트랜잭션에 남겨, 재전달은 조용히 건너뛰게 한다.
CREATE SCHEMA IF NOT EXISTS coupon;

CREATE TABLE IF NOT EXISTS coupon.processed_event (
    event_id     varchar(64)  NOT NULL,
    processed_at timestamp(6) NOT NULL,
    PRIMARY KEY (event_id)
);

-- 보존: 재전달 윈도우보다 훨씬 길게 두고 주기적으로 정리해도 된다(예: 30일).
--   DELETE FROM coupon.processed_event WHERE processed_at < now() - interval '30 days';
