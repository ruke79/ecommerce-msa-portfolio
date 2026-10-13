# 76. 주문 상태 이벤트와 진행 타임라인 읽기 모델 — CQRS 를 주문 진행 조회로 넓힌다

[日本語](76-order-status-read-model.md) | **한국어**

> **상태(2026-10-07)** — 구현 완료, 운영 배포 대기. 순서는 [docs/72](72-model-refactor-deploy-runbook.ko.md) → [docs/74](74-feature-flags.ko.md) → 이것.
> 계기: "이벤트 소싱 · CQRS 가 적용돼 있나" 는 질문. 답은 "CQRS 는 두 곳(결제 사가 프로젝션 · ES 검색)에 부분적으로, 이벤트 소싱은 없다" 였고, 그 다음 한 걸음이 이 문서다.

---

## 0. 한눈에

| | 예전 | 지금 |
|---|---|---|
| 주문 상태 변경 | 열 군데가 저마다 `order.setOrderStatus(...)` — DB 에만 남는다 | `OrderStatusChanger` 한 곳 — 상태를 쓰고 **같은 트랜잭션**에 아웃박스 행(`order-status`)을 남긴다 |
| 이벤트 | 결제 요청(`order`) · 결제 응답(`payment`) 뿐 | + `order-status`(OrderStatusChanged, Avro) — 생성 · 결제 · 배송 · 확정 · 취소 · 환불 모두 |
| 읽기 모델 | 결제 사가 상태(`order-saga-state`) | + 주문 진행 타임라인(`order-timeline`) — 언제 · 무엇이 바꿨나, 지금 상태 |
| 조회 | 내부에서만(`GET /api/saga/{orderId}`, 게이트웨이 라우트 없음) | 게이트웨이 `/saga-projection/api/saga/{orderId}` — JwtAuthorization, **남의 주문은 404** |
| 화면 | 주문 진행 띠(상태 하나) | + 진행 기록(타임라인) — 읽기 모델에서, 없으면 조용히 숨김 |

**이벤트 소싱은 여전히 아니다**([docs/77](77-event-sourcing-decision.ko.md) 에 이유와, 한다면의 설계). 진실의 원천은 order 의 DB 다. 이벤트는 "바뀌었다" 는 알림이고, 타임라인은 그것을 접은 **읽기 전용** 모델이다. 재생해서 상태를 만들지 않고, 틀리면 입력 토픽에서 다시 접으면 된다(애플리케이션 리셋).

---

## 1. 왜 이 모양인가

### 1-1. 왜 주문 목록 전체가 아니라 "진행 타임라인" 인가

처음 후보는 주문 목록 조회를 통째로 읽기 모델로 옮기는 것이었다. 보고 나니 그럴 이유가 약했다 — `/orders/page` 는 이미 커서 페이징이고 N+1 도 고쳐져 있다(OrderDataService). 상품 · 색상 · 주소까지 읽기 모델에 복제하면 비용은 크고 얻는 것은 적다.

반면 **"이 주문이 언제 어떻게 여기까지 왔나"** 는 지금 어디에도 없었다. 상태는 덮어쓰이고, `paidCreatedAt` · `deliveredCreatedAt` · `completedAt` 세 시각만 남는다. 취소 · 환불 · 배송 취소로 되돌아간 이력은 로그뿐이다. 이것이 CQRS 로 얻을 수 있는 가장 싼 가치였다.

### 1-2. 왜 아웃박스인가 — 같은 트랜잭션

상태 변경과 이벤트가 따로 커밋되면 둘 중 하나만 남는 순간이 생긴다. 이 저장소에는 이미 결제 요청용 트랜잭셔널 아웃박스(`payment_outbox_event`, 릴레이 `PaymentOutboxScheduler`)가 있다 — 같은 표에 `event_type=order-status` 행으로 넣는다. 릴레이의 at-least-once · 추적 문맥 복원(docs/51 §4e) · 커밋 직후 릴레이(docs/56)가 그대로 적용된다.

### 1-3. 왜 한 곳(OrderStatusChanger)으로 모았나 — 가드 B-78

누군가 `setOrderStatus` 를 직접 부르면 DB 는 바뀌는데 이벤트가 없다. 읽기 모델이 **조용히** 틀리고 테스트는 모두 초록이다. 그래서 호출 자리를 하나로 묶고 B-78 이 `.setOrderStatus(` · `.markPaid(` 를 그 클래스 밖에서 찾으면 빌드를 빨갛게 만든다.

같은 상태로 다시 "바꾸는" 것은 이벤트가 아니다 — 결제 응답 재전달 등 멱등 경로에서 타임라인이 늘지 않는다.

서비스들은 변경기를 **세터**로 받는다(`OrderSaga.setStockHold` 와 같은 이유 — 생성자를 쓰는 테스트가 13곳). 세터 전의 기본값은 `withoutEvents()`(상태만 바꿈)라 기존 단위 테스트는 그대로다. Spring 에서는 `@Autowired` 세터가 필수라 빈이 없으면 기동이 실패한다 — 운영에서 조용히 빠지지 않는다.

### 1-4. 왜 별도 저장소인가 — 스키마 진화를 피한다

사가 상태(`OrderSagaState`)에 필드를 더하면 그 스키마가 바뀐다. 상태 토픽 · 변경 로그 · RocksDB 에 쌓인 **옛 지문**의 레코드를 전부 읽을 수 있어야 하고(단일 객체 인코딩은 지문으로 작성 스키마를 찾는다), 그 절차는 docs/55 §4 의 두 릴리스다. 새 레코드(`OrderTimeline`) · 새 저장소 · 새 토픽이면 그 문제가 없다. 조회 API 가 둘을 합친다.

### 1-5. 접기 규칙 (OrderTimelineReducer)

- **같은 eventId 는 한 번만.** eventId = 아웃박스 행 id. 릴레이가 재발행하면 `eventCount` 만 는다 — 그 차이가 재발행의 기록이다.
- **순서는 도착이 아니라 발생(`occurredAt`).** 릴레이가 레플리카 둘에서 돌면 나중 변경이 먼저 도착할 수 있다. 도착 순서를 믿으면 "배달 완료 뒤에 결제 완료" 가 된다. 지금 상태 = 가장 늦게 *일어난* 변경.
- **상한 50.** 버그로 핑퐁하면 레코드가 끝없이 자란다. 넘으면 오래된 것부터 버린다.

### 1-6. 소유권 — 남의 주문은 404

게이트웨이가 `User-Id` 를 넣고(JwtAuthorization), saga-projection 이 레코드의 `customerId` 와 대조한다. 다르면 **진짜 없음과 같은 404** — 있는지조차 알리지 않는다. customerId 를 모르는 레코드도 남의 것으로 본다. `User-Id` 가 없으면 401(게이트웨이를 거치지 않은 호출).

---

## 2. 구성

```
order-service                                   saga-projection
  OrderStatusChanger.change / markPaid / created      stream(order-status) ─ map(orderId, delta)
    ├ order.setOrderStatus                              ─ groupByKey ─ aggregate(OrderTimelineReducer)
    └ outbox(event_type=order-status) ─┐                 ├ store  order-timeline-store  ← interactive query
                                       │                 └ to(order-timeline, compact)
  PaymentOutboxScheduler ──────────────┘
    └ OrderStatusKafkaPublisher → topic order-status   SagaQueryService.compose(saga, timeline, User-Id)
      (Avro 단일 객체, AvroSpecificSerde, 키 = orderId)     → GET /api/saga/{orderId}  (게이트웨이 /saga-projection/**)
                                                        frontend OrderTimeline (주문 상세)
```

| 곳 | 파일 |
|---|---|
| 스키마 | `common/src/main/avro/OrderStatusChanged.avsc` · `OrderTimeline.avsc` |
| 쓰기 | `order-service/.../service/OrderStatusChanger.java`, `PaymentOutboxHelper.saveOrderStatusOutbox`, `OrderStatusKafkaPublisher`, `PaymentOutboxScheduler`(분기), `KafkaProducerConfig`(템플릿) |
| 호출부 | OrderSaga(결제 · 취소) · OrderDeliveryStatusService(배송 · 배송 취소) · OrderPurchaseConfirmationService(확정) · OrderDataService(생성 · 취소 · 환불 · 무통장 · 가상계좌 · 기한 만료) |
| 읽기 | `saga-projection/.../topology/SagaTopology`(두 번째 분기) · `OrderTimelineReducer` · `SagaQueryService.compose` · `OrderSagaView` · `SagaQueryController` |
| 운영 | `ops/02-create-dlt-topics.sh` — `STATE_TOPICS="order-saga-state order-timeline"` 컴팩션(B-38) |
| 게이트웨이 | 라우트 `saga-projection`(GET, JwtAuthorization) |
| 화면 | `frontend/src/components/order/OrderTimeline.jsx`, `api.getOrderTimeline`, i18n `order.timeline.*` |

출처(`source`) 값: `created` · `payment-saga` · `delivery` · `delivery-cancelled` · `customer` · `admin` · `bank-transfer` · `virtual-account` · `deposit-expired` · `auto-confirm`. 화면은 모르는 값을 코드 그대로 보여 준다.

---

## 3. 운영

### 3-1. 배포 전

`ops/02-create-dlt-topics.sh` 를 한 번 돌려 `order-timeline` 을 컴팩션으로 만든다. 안 만들면 프로젝션이 쓰는 순간 브로커가 delete 정책으로 자동 생성하고, 보존 기간이 지난 뒤 과거 주문의 타임라인이 조용히 사라진다(기존 `order-saga-state` 와 같은 함정, docs/38 §1.17). 입력 `order-status` 는 프로듀서가 자동 생성해도 된다(일반 토픽).

### 3-2. 롤링 중 — 옛 릴레이는 새 행을 모른다

새 order 파드가 `order-status` 행을 쓰는데 **옛** 파드의 릴레이가 그 행을 선점하면 "Unknown outbox event type" 으로 **FAILED** 를 찍는다(`PaymentOutboxScheduler` 의 안전장치 — 모르는 타입을 STARTED 로 두면 무한 반복이라). 그 이벤트는 토픽에 나가지 않는다. 창은 롤링 시간(1 ~ 2분)이고, 잃는 것은 읽기 모델의 항목이지 주문 상태가 아니다.

배포 뒤 확인하고, 있으면 되살린다 — 릴레이는 at-least-once 고 리듀서가 eventId 로 거르므로 다시 보내도 안전하다:

```sql
-- order DB
SELECT count(*) FROM orders.payment_outbox_event WHERE event_type = 'order-status' AND outbox_status = 'FAILED';
UPDATE orders.payment_outbox_event SET outbox_status = 'STARTED'
 WHERE event_type = 'order-status' AND outbox_status = 'FAILED';
```

원칙대로라면 docs/49 §3 처럼 "읽는 쪽(릴레이 분기) 먼저, 쓰는 쪽 나중" 두 릴리스다. 이번에는 잃는 것이 읽기 모델뿐이고 복구가 UPDATE 한 줄이라 한 릴리스로 간다.

### 3-3. 배포 후 확인

- order 로그: `주문 상태 변경: orderId=… NOT_PROCESSED → PAID (payment-saga)`, 릴레이 `OrderStatusChanged sent`.
- saga-projection 로그: 토폴로지 설명에 `order-status-changes` · `fold-timeline` · `to-order-timeline`.
- `kubectl -n ecommerce exec deploy/saga-projection -- curl -sk -H 'User-Id: <customerId>' https://localhost:8190/api/saga/<orderId>` → `orderStatus` · `timeline[]`. 다른 `User-Id` 면 404.
- 화면: 주문 상세에 "진행 기록".
- 지표: `saga_projection_deserialization_failures_total{topic="order-status"}` 가 0 — 올라가면 §4.

### 3-4. 되돌리기

이미지만 되돌리면 된다(스키마 변경 없음, 자동 복귀 가능). 되돌린 뒤 남는 `order-status` 행은 옛 릴레이가 FAILED 로 찍는다 — 다시 올릴 때 §3-2 의 UPDATE 로 되살린다.

---

## 4. 한계와 다음

- **Debezium 경로는 없다.** `order-outbox-connector-*.json` 은 쓰이지 않는 옛 파일이다. 누군가 order 의 아웃박스에 CDC 를 붙이면 `order-status` 토픽에 **JSON 문자열**이 실리고, 프로젝션의 `AvroSpecificSerde` 는 그것을 못 읽어 건너뛴다(계수된다). 그때는 하이브리드 역직렬화기(`AvroDtoDeserializer` 계열)로 바꿔야 한다.
- **주문 목록(여러 주문) 조회는 아직 쓰기 모델이다.** 저장소가 주문 id 키라 고객별 조회가 없다. 필요해지면 고객 id 키의 두 번째 저장소(최근 N건)를 더한다.
- **옛 주문에는 타임라인이 없다** — 이벤트 도입 전의 변경은 기록되지 않았다. 화면은 그때 아무것도 그리지 않는다. 사가 상태만 있는 응답도 정상이다.
- **상태 토픽의 보존.** 컴팩션이라 주문마다 마지막 타임라인 하나가 남는다. "지운 주문" 은 아직 묘비(tombstone)를 내지 않는다.
- 다음 후보: 알림(notification-service)이 `order-status` 를 직접 구독해 "배송 시작" 알림을 보내는 것 — 지금은 order 가 알림을 따로 발행한다.

---

## 5. 테스트

| 테스트 | 무엇을 막나 |
|---|---|
| `OrderStatusChangerTest`(order, 8) | 이벤트 모양 · 같은 상태는 이벤트 아님 · markPaid 시각 보존 · 생성 · 저장 전 주문 거부 · 아웃박스 행(event_type · 키 · 커밋 후 릴레이) → Avro(eventId = 행 id) · 스키마에 개인정보 없음 |
| `OrderStatusRelayTest`(order, 1) | 릴레이의 `order-status` 분기 — 추적 문맥 아래에서 발행기로 |
| `OrderTimelineReducerTest`(saga-projection, 5) | 순서 · eventId 중복 · 늦게 온 먼저 일어난 변경 · 같은 시각 · 상한 |
| `SagaTopologyTest` +2 | order-status → 타임라인 저장소 · 출력 토픽(Avro), 사가 저장소는 그대로 |
| `SagaQueryServiceTest` +4 | 두 저장소 합치기 · **남의 주문은 404** · customerId 모름도 404 · 내부 진단은 소유권 생략 |
| `OrderTimeline.test.jsx`(frontend, 6) | 기록 그리기 · 404/503 은 숨김 · 빈 기록 숨김 · stale 표시 · 상태 바뀌면 다시 읽기 · 모르는 출처 |
| **B-78** | `.setOrderStatus(` · `.markPaid(` 는 OrderStatusChanger 밖에서 금지 |
| **B-38**(갱신) | ops/02 의 `STATE_TOPICS` 에 `state` · `timeline` 둘 다 |

변이 확인은 §6 커밋 메시지에.
