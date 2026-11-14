# DLQ 전면 확산 · 컨슈머 멱등성(G8) · 경보 규칙 · 대시보드 코드화

[日本語](12-dlq-rollout-idempotency-alerts.md) | **한국어**

> 🕰️ **상태(2026-11-14) — 2026-07-27 의 기록.** 본문은 그때의 글로 두고 절마다 🕰️ 로 지금을 적었습니다. 경보는 지금 `observability/alert-rules.yml` 에 44개 · 그룹 13개이고 Discord 로 전달됩니다(B-96, [docs/22](22-alerting-and-backup-automation.ko.md)). order 는 `user-created` 구독을 끊었습니다([docs/48](48-user-replication-debt.ko.md)).

| 2026-07 의 이 문서 | 지금(2026-11-14) | 어디 |
|---|---|---|
| DLT 설정은 catalog · order + 5개 서비스 | `KafkaDltConfig` 9개 서비스(cart · catalog · chat · coupon · delivery · notification · order · pay · user — user 에는 `@KafkaListener` 가 없다) | 각 모듈 `KafkaDltConfig` |
| 재시도는 서비스마다 | 공통 `DltRetryPolicy` — 일시 오류는 1 · 2 · 4 · 8 · 16 · 30초 … 10번(약 3분), 계수기 `kafka_dlt_records_total` · 경보 `KafkaDeadLetterPublished`(기동 때 0 — B-101) | common · B-88 · B-101 |
| DLT 토픽 사전 생성 8개(+ chat) | `ops/02-create-dlt-topics.sh` 가 토픽 23개의 DLT 를 14일 보존으로 만든다 | ops/02 의 `TOPICS` |
| 처리 원장은 catalog 하나 | catalog · coupon · cart · order(배송 상태, V30) + 30일 정리 `ProcessedEventPurger` | §2 |
| 경보 5종 | 44개 · 그룹 13개, Discord 전달 | `observability/alert-rules.yml` · docs/22 |
| 컨슈머 랙 · DLT 적재 · outbox 적체는 로드맵 | `KafkaConsumerLagHigh`(2026-11-14, docs/91 §3) · `KafkaDeadLetterPublished` · `OrderOutboxRowFailed` · `PayOutboxRowFailed` · 아웃박스 파티션 경보(`outbox-health` 그룹) | §3 의 🕰️ |
| 대시보드 하나 | `ecommerce-overview` · `tracing-red` 둘 | `observability/grafana/provisioning/dashboards/` |

> 작성일: 2026-07-27 · docs/11 §4에서 "남은 것"으로 명시했던 항목들의 적용 기록

## 1. Kafka DLQ 전면 확산 — 전 서비스 완료

docs/11의 레퍼런스 구현(catalog/order)을 나머지 5개 서비스로 확산했다. 이로써 **이 시스템의 모든 Kafka 컨슈머**가 재시도+DLT 격리, 역직렬화 방어, 추적 관측을 갖췄다.

| 서비스 | DLT 설정 | 배선된 팩토리 | 인덱스 루프 전환 리스너 |
|---|---|---|---|
| cart | `config/KafkaDltConfig` | 4 (record 1 포함) | CartRollback/CartEmpty/CouponUse |
| coupon | `config/KafkaDltConfig` | 3 | PaymentResponse/CouponRollback/UserCreated |
| pay | `message/config/KafkaDltConfig` | 1 | PaymentRequestKafkaListener (기존 NO-OP 스킵 유지) |
| user | `message/config/KafkaDltConfig` | 1 (record) | — (record 리스너) |
| chat | `common/config/kafka/KafkaDltConfig` | 3 (record 2 포함) | UserCreatedConsumer (MessageConsumer 는 record 모드라 무변경) |

record 모드 리스너는 `BatchListenerFailedException` 없이도 `DefaultErrorHandler`가 레코드 단위로 재시도/격리한다.

**운영 반영 시**: DLT 토픽 사전 생성 목록 — `cart-rollback.DLT`, `cart-empty.DLT`, `coupon-use.DLT`, `payment.DLT`, `coupon-rollback.DLT`, `user-created.DLT`, `order.DLT`, `product-update.DLT` (+ chat 토픽).

> 🕰️ 지금: DLT 설정은 9개 서비스(위 다섯 + catalog · order · delivery · notification), Kafka 를 받는 것은 그중 8개(user 는 리스너 없음). 재시도는 공통 `DltRetryPolicy`(일시 오류 약 3분 · 그 밖은 바로 DLT). 사전 생성은 손 목록이 아니라 `ops/02-create-dlt-topics.sh` — 토픽 23개의 `.DLT` 를 14일 보존으로(가입 확인 링크가 흐르는 `outgoing-mail` 은 본 토픽 · DLT 모두 짧게, docs/29 §5.3). 재처리는 `ops/56-dlt-replay.sh`.

## 2. 컨슈머 멱등성 (G8) — 재고 이중 차감 방지

**돈이 걸린 경로(product-update → catalog 재고 차감)** 에 end-to-end 로 적용했다.

- **이벤트 계약**: `ProductUpdateRequest`(common)에 `eventId` 추가. JSON 하위호환 — 구버전 생산자(null)면 기존 동작.
- **생산자**(order `OrderSaga`): `eventId = "order-<orderId>"`. 랜덤 UUID가 아니라 **자연 멱등 키**를 택했다 — "재고 차감은 주문당 1회"가 업무 규칙이므로, Kafka 재전달뿐 아니라 **사가 자체가 재실행되어 새 메시지를 만들어도** 중복이 걸러진다.
- **소비자**(catalog): `product.processed_event` 테이블(Flyway `V2__create_processed_event.sql`)에 처리 이력을 **재고 변경과 같은 트랜잭션**으로 기록. 재전달 시 `existsById` 로 스킵. 동시 중복(희귀)은 PK 충돌 → 전체 롤백 → 재전달 시 스킵으로 수렴.
- **테스트**: `ProductUpdateMessageListenerTest` 3케이스(중복 스킵 / 최초 처리+기록 / eventId 없음 하위호환) 통과.
- **보존**(2026-10-15): 원장이 막는 것은 같은 이벤트가 다시 오는 것이고, 다시 올 수 있는 기간은 원래 토픽 보존(7일)과 DLT 보존(14일, ops/02 — 원래 토픽으로 되돌리면 거기서 또 7일)이다. 네 원장 모두 common `ProcessedEventPurger` 로 매일 보존 기간(`<서비스>.processed-event.retention`, 기본 30일 · **22일 아래로는 내려가지 않는다**)이 지난 행을 5,000줄씩 문장마다 커밋하며 지운다(한 회차 상한 200번, 남으면 다음 날): coupon `coupon.processed_event` 04:40(V9), catalog `product.processed_event` 04:45(V19), cart `cart.processed_event` 04:50(V7), order `orders.processed_event` 04:55(V30 — 2026-11-07, 배송 상태 원장과 인덱스를 함께) — 각 마이그레이션은 `processed_at` 인덱스만 더한다. DLT 보존을 늘리면 하한(`MIN_RETENTION`)도 함께 늘린다.

Redis(SETNX)가 아닌 **DB 테이블**을 택한 이유: 처리 이력 기록이 비즈니스 변경과 **원자적**이어야 하기 때문(Redis면 "기록됐는데 DB 롤백" 창이 생긴다).

**남은 확산 대상**(동일 패턴 적용 가능, 문서화만): user-created 3개 소비자(order/chat/coupon — 현재는 email 유니크 제약이 사실상 방어), payment 응답(order — 낙관적 락 NO-OP이 부분 방어), cart-empty/rollback.

> 🕰️ 지금: user-created 소비자는 order 가 구독을 끊어(docs/48) chat(`user-3`) · coupon(`user-2`) 둘 — coupon 은 가입 쿠폰이 이미 있으면 재전달로 보고 건너뛴다(CP4). cart-empty 는 ✅ cart V6 처리 원장(`cart.processed_event`). 그 밖에 coupon 은 결제 응답에 `coupon.processed_event`, order 는 배송 상태에 `orders.processed_event`(V30)를 쓴다.

## 3. 경보 규칙 + Alertmanager

- `observability/alert-rules.yml`: **ServiceDown**(2m) · **CircuitBreakerOpen** · **High5xxRate**(>5%, 5m) · **HighP99Latency**(>3s) · **JvmHeapNearLimit**(>90%).
- Alertmanager(9093) 추가, Prometheus에 rule_files/alerting 배선. 수신 채널은 플레이스홀더(웹훅/Slack 교체 지점 주석).
- p99 규칙을 위해 `application-common.yml`에 `percentiles-histogram.http.server.requests: true` 활성화(버킷 노출).
- 미포함(로드맵): 컨슈머 랙·DLT 적재량(kafka exporter 필요), outbox 적체(커스텀 메트릭 필요 — docs/03 부록).

> 🕰️ 지금: 경보는 44개 · 그룹 13개(서비스 · 요청 · JVM · 재고/결제/DLT · 인증 · 사가 · 아웃박스 · 경보 전달 · 추적 · 백업 · SLO · 컨슈머 지연), 수신은 플레이스홀더가 아니라 Discord(`discord_configs` + `webhook_url_file`, B-96 · docs/22 §1). 로드맵 셋은 ✅: 컨슈머 랙 `KafkaConsumerLagHigh`(2026-11-14, docs/91 §3), DLT 적재 `KafkaDeadLetterPublished`(앱 계수기 — kafka exporter 없이), outbox `OrderOutboxRowFailed` · `PayOutboxRowFailed` + 아웃박스 파티션 경보(`outbox-health`).

## 4. Grafana 대시보드 코드화

`observability/grafana/provisioning/dashboards/` — provider + `ecommerce-overview.json` 프로비저닝: Service Up(스탯) · 요청률 · 5xx 비율 · p99 · JVM 힙 · 서킷브레이커 상태. 기동 즉시 "ecommerce-msa" 폴더에 나타난다.

> 🕰️ 지금: 대시보드는 `ecommerce-overview.json` · `tracing-red.json` 둘(docs/63).

## 검증

- DLQ 확산 + eventId + 멱등성 포함 **전 모듈 compileJava BUILD SUCCESSFUL**.
- catalog 신규 테스트 **12케이스 전부 통과** (기존 9 + 멱등성 3).
- observability YAML/JSON 파싱 검증.
- ⚠️ 실제 브로커/DB에 연결한 통합 검증은 아님 — DLT 토픽 생성 등 §1·§3의 운영 반영 절차 필요.
