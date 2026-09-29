# DLQ 전면 확산 · 컨슈머 멱등성(G8) · 경보 규칙 · 대시보드 코드화

[日本語](12-dlq-rollout-idempotency-alerts.md) | **한국어**

> 🕰️ **상태(2026-09-27) — 이전 기록.** 경보는 지금 14종이고 수신 채널이 연결돼 있습니다([docs/22](22-alerting-and-backup-automation.ko.md)). order 는 `user-created` 구독을 끊었습니다([docs/48](48-user-replication-debt.ko.md)).

> 작성일: 2026-07-27 · docs/11 §4에서 "남은 것"으로 명시했던 항목들의 적용 기록

## 1. Kafka DLQ 전면 확산 — 전 서비스 완료

docs/11의 레퍼런스 구현(catalog/order)을 나머지 5개 서비스로 확산했다. 이로써 **이 시스템의 모든 Kafka 컨슈머**가 재시도+DLT 격리, 역직렬화 방어, 추적 관측을 갖췄다.

| 서비스 | DLT 설정 | 배선된 팩토리 | 인덱스 루프 전환 리스너 |
|---|---|---|---|
| cart | `config/KafkaDltConfig` | 4 (record 1 포함) | CartRollback/CartEmpty/CouponUse |
| coupon | `config/KafkaDltConfig` | 3 | PaymentResponse/CouponRollback/UserCreated |
| pay | `message/config/KafkaDltConfig` | 1 | PaymentRequestKafaListener (기존 NO-OP 스킵 유지) |
| user | `message/config/KafkaDltConfig` | 1 (record) | — (record 리스너) |
| chat | `common/config/kafka/KafkaDltConfig` | 3 (record 2 포함) | UserCreatedConsumer (MessageConsumer 는 record 모드라 무변경) |

record 모드 리스너는 `BatchListenerFailedException` 없이도 `DefaultErrorHandler`가 레코드 단위로 재시도/격리한다.

**운영 반영 시**: DLT 토픽 사전 생성 목록 — `cart-rollback.DLT`, `cart-empty.DLT`, `coupon-use.DLT`, `payment.DLT`, `coupon-rollback.DLT`, `user-created.DLT`, `order.DLT`, `product-update.DLT` (+ chat 토픽).

## 2. 컨슈머 멱등성 (G8) — 재고 이중 차감 방지

**돈이 걸린 경로(product-update → catalog 재고 차감)** 에 end-to-end 로 적용했다.

- **이벤트 계약**: `ProductUpdateRequest`(common)에 `eventId` 추가. JSON 하위호환 — 구버전 생산자(null)면 기존 동작.
- **생산자**(order `OrderSaga`): `eventId = "order-<orderId>"`. 랜덤 UUID가 아니라 **자연 멱등 키**를 택했다 — "재고 차감은 주문당 1회"가 업무 규칙이므로, Kafka 재전달뿐 아니라 **사가 자체가 재실행되어 새 메시지를 만들어도** 중복이 걸러진다.
- **소비자**(catalog): `product.processed_event` 테이블(Flyway `V2__create_processed_event.sql`)에 처리 이력을 **재고 변경과 같은 트랜잭션**으로 기록. 재전달 시 `existsById` 로 스킵. 동시 중복(희귀)은 PK 충돌 → 전체 롤백 → 재전달 시 스킵으로 수렴.
- **테스트**: `ProductUpdateMessageListenerTest` 3케이스(중복 스킵 / 최초 처리+기록 / eventId 없음 하위호환) 통과.

Redis(SETNX)가 아닌 **DB 테이블**을 택한 이유: 처리 이력 기록이 비즈니스 변경과 **원자적**이어야 하기 때문(Redis면 "기록됐는데 DB 롤백" 창이 생긴다).

**남은 확산 대상**(동일 패턴 적용 가능, 문서화만): user-created 3개 소비자(order/chat/coupon — 현재는 email 유니크 제약이 사실상 방어), payment 응답(order — 낙관적 락 NO-OP이 부분 방어), cart-empty/rollback.

## 3. 경보 규칙 + Alertmanager

- `observability/alert-rules.yml`: **ServiceDown**(2m) · **CircuitBreakerOpen** · **High5xxRate**(>5%, 5m) · **HighP99Latency**(>3s) · **JvmHeapNearLimit**(>90%).
- Alertmanager(9093) 추가, Prometheus에 rule_files/alerting 배선. 수신 채널은 플레이스홀더(웹훅/Slack 교체 지점 주석).
- p99 규칙을 위해 `application-common.yml`에 `percentiles-histogram.http.server.requests: true` 활성화(버킷 노출).
- 미포함(로드맵): 컨슈머 랙·DLT 적재량(kafka exporter 필요), outbox 적체(커스텀 메트릭 필요 — docs/03 부록).

## 4. Grafana 대시보드 코드화

`observability/grafana/provisioning/dashboards/` — provider + `ecommerce-overview.json` 프로비저닝: Service Up(스탯) · 요청률 · 5xx 비율 · p99 · JVM 힙 · 서킷브레이커 상태. 기동 즉시 "ecommerce-msa" 폴더에 나타난다.

## 검증

- DLQ 확산 + eventId + 멱등성 포함 **전 모듈 compileJava BUILD SUCCESSFUL**.
- catalog 신규 테스트 **12케이스 전부 통과** (기존 9 + 멱등성 3).
- observability YAML/JSON 파싱 검증.
- ⚠️ 실제 브로커/DB에 연결한 통합 검증은 아님 — DLT 토픽 생성 등 §1·§3의 운영 반영 절차 필요.
