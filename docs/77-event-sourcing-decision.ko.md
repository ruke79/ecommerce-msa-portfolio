# 77. 이벤트 소싱 — 적용하지 않은 이유와, 적용한다면의 설계

[日本語](77-event-sourcing-decision.md) | **한국어**

> **상태(2026-10-07)** — 결정 기록. 코드 변경 없음. [docs/76](76-order-status-read-model.ko.md) 이 "진실은 order 의 DB, 이벤트는 알림" 으로 정한 근거와, 그 결정을 뒤집을 때 무엇을 어떤 순서로 할지를 참고서를 근거로 적는다.
> 숫자는 2026-10-07 의 `order-service` 소스에서 센 것이다(§3-2).

---

## 0. 한눈에

- **지금은 하지 않는다.** 이벤트 소싱이 약속하는 것 중 우리가 원했던 둘 — *이력* 과 *신뢰할 수 있는 이벤트 발행* — 은 트랜잭셔널 아웃박스 + 상태 변경 이벤트(docs/76)로 이미 얻었다. 남은 약속(과거 시점 재구성, O/R 불일치 해소)은 지금 수요가 없고, 대가(쓰기 모델 전면 교체 · 모든 조회의 프로젝션화 · 불변 로그의 개인정보 삭제 · 영구 스키마 진화)는 단일 노드 · 브로커 하나 · 한 사람의 운영 체급에 비해 크다.
- **용어를 먼저 가른다.** Fowler 는 "이벤트 기반" 이라는 말이 네 가지 다른 패턴을 뭉뚱그린다고 했다 — 이벤트 통지(Event Notification), 상태 전달 이벤트(Event-Carried State Transfer), 이벤트 소싱, CQRS[^fowler-ed]. 이 저장소는 **앞의 둘과 CQRS(부분)** 를 쓴다. 이벤트 소싱은 쓰지 않는다.
- **Kleppmann 의 구분으로는** "변경 데이터 캡처(CDC) 쪽" 이다 — 가변 DB 가 진실이고 로그는 파생이다. 이벤트 소싱은 그 반대(로그가 진실, DB 는 파생)다[^ddia-cdc-es]. 어느 쪽이 진실인가가 이 결정의 전부다.
- **한다면 Order 집합체 하나부터**, PostgreSQL 이벤트 표 + 지금의 릴레이, 기존 `orders` 표는 프로젝션으로 강등, 네 릴리스(§4). 결정을 다시 볼 신호는 §5.

---

## 1. 우리는 지금 어디에 있나 — 책의 용어로

| 이 저장소 | 패턴 | 근거 |
|---|---|---|
| `payment_outbox_event` + `PaymentOutboxScheduler`(FOR UPDATE SKIP LOCKED 로 선점, 동기 발행) | **트랜잭셔널 아웃박스 + 폴링 발행기** | Richardson 3장 "Transactional messaging" — 아웃박스 표, 폴링 발행기, 트랜잭션 로그 테일링(CDC) 중 우리는 폴링[^rich-outbox] |
| `OrderStatusChanged`(docs/76) — 상태 전이 + 출처 | **이벤트 통지**(상태 일부를 싣는다) | Fowler 의 네 패턴 중 첫째 · 둘째 사이[^fowler-ed]. Newman 의 "무엇을 싣나 — id 만 vs 전부" 에서 중간[^newman-what] |
| saga-projection — `order` ∪ `payment` ∪ `order-status` 를 접어 두 저장소 | **파생 데이터 · 같은 로그에서 여러 뷰** | Kleppmann 11장 "Deriving several views from the same event log" — 여기서 CQRS 를 언급한다[^ddia-views] |
| `order-saga-state` · `order-timeline`(읽기) vs `orders` 표(쓰기), ES 검색 vs PostgreSQL | **CQRS — 부분** | Richardson 7장 — 조회를 API 조합이 아니라 뷰로[^rich-cqrs] |
| `OrderSaga`(결제 → 재고 → 배송 → 확정, 보상) | **사가 — 오케스트레이션** | Newman 6장 "Workflow"[^newman-saga], Richardson 4장 |
| `Order` 엔티티 — 37 필드, 시각 컬럼 9개, 상태는 덮어쓰기 | **상태 저장(전통적 영속화)** | Richardson 6장 "The trouble with traditional persistence" 가 말하는 바로 그 모양 — 이력이 없고 감사는 덧붙임[^rich-trouble] |

Kleppmann 의 말로 요약하면: CDC 는 "애플리케이션은 DB 를 가변으로 쓰고, 변경 로그는 낮은 수준에서 뽑아낸다", 이벤트 소싱은 "애플리케이션 로직이 불변 이벤트 위에 명시적으로 세워지고, 이벤트는 상태 변경의 기계적 기록이 아니라 **사용자 행동의 의도**를 담는다"[^ddia-cdc-es]. 우리 `OrderStatusChanged` 는 `source`(payment-saga · delivery · customer …)로 의도를 조금 담지만, 본질은 상태 전이의 통지다. 그래서 docs/76 은 처음부터 "진실이 아니다" 라고 적었다.

---

## 2. 이벤트 소싱이 약속하는 것과 요구하는 것

### 2-1. 약속 — Richardson 의 네 가지[^rich-benefits]

1. **이벤트를 신뢰성 있게 발행한다** — 이벤트 저장소가 곧 메시지 로그라 아웃박스가 따로 필요 없다.
2. **집합체의 이력이 남는다** — 감사 · 디버깅 · "그때 왜 이랬나".
3. **O/R 불일치를 대부분 피한다** — 이벤트는 단순한 직렬화 대상이다.
4. **타임머신** — 과거 시점의 상태를 재구성하고, 나중에 생긴 요구를 과거 이벤트에 소급 적용한다.

Kleppmann 은 불변 이벤트의 이점을 회계의 장부에 비유한다 — 잘못된 거래는 지우지 않고 정정 거래를 더한다. 버그로 잘못 쓴 상태도 이벤트를 다시 접어 복구할 수 있고, 같은 로그에서 서로 다른 읽기 뷰를 여러 개 만들 수 있다[^ddia-immut].

### 2-2. 요구 — 같은 책들이 적은 대가

| 요구 | 내용 | 근거 |
|---|---|---|
| **낯선 프로그래밍 모델** | 집합체를 "명령 → 이벤트, 이벤트 → 상태 적용" 으로 다시 쓴다. 명령 검증은 이벤트가 되기 **전에** 동기로 끝나야 한다 — 소비자는 이벤트를 거부할 수 없다 | Richardson "Drawbacks"[^rich-drawbacks], Kleppmann "Commands and events"[^ddia-commands] |
| **조회가 어렵다 → CQRS 가 필수** | 이벤트 저장소는 기본 키 조회뿐이다. 목록 · 집계 · 검색은 전부 별도 뷰(프로젝션) | Richardson 6장 "Querying the event store is challenging" · 7장[^rich-drawbacks][^rich-cqrs] |
| **전체 이력이 필요하다 — 컴팩션 불가** | CDC 는 최신 상태만 남겨도 되지만(로그 컴팩션), 이벤트 소싱은 나중 이벤트가 앞 이벤트를 덮지 않으므로 전체 이력을 보존해야 한다. 그래서 **스냅샷**이 따라온다 | Kleppmann "Deriving current state from the event log"[^ddia-derive], Richardson "Using snapshots", Vernon 부록 A[^vernon-aes] |
| **이벤트 스키마 진화가 영구 업무다** | 저장된 모든 과거 이벤트를 영원히 읽어야 한다. 필드 추가는 기본값으로, 구조 변경은 업캐스터, 더 크면 스트림을 복사 · 변환(copy-transform)해 새 스트림으로 — 과거를 고치지 않는다 | Richardson "Evolving domain events"[^rich-evolve], Young *Versioning*[^young] |
| **삭제가 어렵다** | 불변 로그에서 개인정보를 지우려면 "절제(excision)" 같은 예외 경로가 필요하고, 실무에서는 **개인별 키로 암호화하고 키를 폐기**한다(암호 파쇄 · 가명화) | Kleppmann "Limitations of immutability"[^ddia-immut-limits], Richardson "Deleting data is tricky"[^rich-drawbacks] |
| **읽기 뷰의 지연** | 이벤트를 쓴 직후 조회가 옛 뷰를 볼 수 있다. 같은 트랜잭션에서 뷰를 갱신하려면 이벤트 로그와 뷰가 **같은 저장소**여야 한다 | Kleppmann "Concurrency control"[^ddia-concurrency] |
| **멱등 소비** | 메시지 기반이라 중복 전달을 소비자가 걸러야 한다(메시지 id 추적) | Richardson "Idempotent message processing"[^rich-idem] |

---

## 3. 적용하지 않은 이유 — 이 저장소의 사실로

### 3-1. 원했던 것은 이미 얻었다

이벤트 소싱으로 얻고 싶던 것은 둘이었다: "주문이 언제 왜 바뀌었나" 와 "상태 변경이 빠짐없이 이벤트로 나간다". 둘 다 docs/76 이 해결했다 — `OrderStatusChanger` 가 상태와 아웃박스 행을 **같은 트랜잭션**에 쓰고(Richardson 의 아웃박스 패턴[^rich-outbox]), saga-projection 이 그것을 타임라인으로 접는다. Richardson 이 꼽은 네 이점 중 ①과 ②는 이것으로 충족된다. ③(O/R 불일치)은 우리 문제가 아니었고, ④(과거 시점 재구성)는 아직 아무도 요구하지 않았다.

Kleppmann 식으로 말하면 우리는 "로그를 진실로 삼는" 대신 "DB 를 진실로 두고 파생 로그를 만드는" 쪽을 골랐고, 그는 두 접근이 **같은 생각의 다른 수준**이라고 적었다[^ddia-cdc-es]. 파생 쪽은 기존 애플리케이션을 그대로 두면서 시작할 수 있다는 것이 이 저장소에서는 결정적이었다.

### 3-2. 쓰기 모델을 통째로 뒤집어야 한다 — 숫자

`order-service/src/main` 에서 센 것:

| 항목 | 수 | 뜻 |
|---|---|---|
| `Order` 의 필드 | **37**(그중 `LocalDateTime` 9 — paid · delivered · completed · depositDeadline · stockHoldUntil · refunded · returnReceived · shortageResolved · stockRestored) | 상태 표가 이력 노릇을 하려고 시각 컬럼을 늘려 온 모양. 이것은 이벤트 소싱의 **신호**이기도 하다(§5) |
| `order.set*(...)` 호출 | **28회 · 22종 · 5 파일** | 전부 "명령 → 이벤트 → apply" 로 다시 써야 한다 |
| `Order` 를 다루는 클래스 | **23** | 사가 · 결제 확인 · 환불 · 반품 · 무통장 · 가상계좌 · 배송 · 확정 · 재고 … |
| `OrderRepository` 조회 메서드 | **21**(`@Query` 8) | 전부 프로젝션 위로 옮겨야 한다 — 이벤트 저장소는 기본 키 조회뿐[^rich-drawbacks] |
| 주문을 읽는 GET | **8**(내부 3 — 구매 통계 · 배송지 · 구매 여부) | 같은 이유 |
| `@Version` | **0** | 지금은 낙관적 잠금이 없다. 이벤트 소싱은 (집합체, 버전) 유니크로 이것을 강제한다[^rich-optimistic] — 가져가고 싶은 장점이지만, 별도로도 할 수 있다 |

반면 `pay-service` 의 `Payment` 는 필드 11개에 `payment.set*` 호출이 **0** — 이미 사실상 불변이다. 이벤트 소싱이 필요한 쪽은 Order 뿐이고, Order 가 가장 비싸다.

### 3-3. 운영 체급 — 단일 노드, 브로커 하나, 한 사람

- 운영은 OCI Ampere **단일 노드**(docs/32), Kafka 는 **클러스터 안 브로커 하나**(복제 없음, 관리형 없음 — `k8s-overlays/external` 주석). 이벤트 소싱에서는 이벤트 저장소가 **유일한 진실**이라 그 저장소의 내구성 · 백업 · 복원이 곧 주문 데이터의 전부가 된다.
- 그 저장소를 Kafka 로 두는 선택은 이 체급에서 맞지 않는다 — 컴팩션을 쓸 수 없고(전체 이력)[^ddia-derive], 집합체 하나를 읽으려면 파티션을 훑어야 하며, 브로커가 하나다. 결국 PostgreSQL 에 이벤트 표를 두고(PITR 백업이 이미 있다, docs/15) Kafka 는 발행 채널이 된다 — Richardson 의 Eventuate Local 도 RDB `events` 표 + CDC 다[^rich-store]. 그러면 **지금 구조(표 + 아웃박스 + 릴레이)와 저장 계층은 거의 같고, 바뀌는 것은 "어느 표가 진실인가" 뿐**이다.
- 한 사람이 운영한다. Richardson 이 첫 단점으로 "다르고 낯선 프로그래밍 모델 · 학습 곡선"[^rich-drawbacks] 을 꼽은 것은 팀이 하나의 생각을 공유해야 한다는 뜻이다.

### 3-4. 개인정보 — 불변 로그에서 지우기

주문 이벤트에는 배송지(`OrderAddress` — 이름 · 주소 두 줄 · 시 · 주 · 우편번호 · 나라 · 전화, 9 필드)가 실린다. 지금은 표라서 UPDATE 로 지울 수 있다(현재 탈퇴 처리기 `WithdrawalProcessor` 는 주문을 다루지 않는다 — 그것대로 열린 일이다). 불변 로그라면 Kleppmann 이 말한 "절제(excision)"[^ddia-immut-limits] 나 Richardson 의 "개인별 키 암호화 → 키 폐기"[^rich-drawbacks] 가 **먼저** 설계돼 있어야 한다. 이 저장소의 규칙(이메일 · 주소를 로그에 남기지 않는다)을 이벤트에도 지키려면 §4-3 처럼 배송지를 이벤트 밖에 둬야 한다.

### 3-5. 스키마 진화 — 지금도 두 릴리스, 이벤트 소싱이면 영구

메시지 스키마에 필드를 더하거나 빼는 데 이 저장소는 이미 두세 릴리스를 쓴다(docs/55 §4 · §5 — 결제 요청 v1 → v4, `AvroSchemaHistory` 가 옛 지문을 기억한다). 그것은 **토픽 보존 기간 동안의** 호환성이다. 이벤트 소싱은 **저장소에 영원히 남는 모든 과거 이벤트**를 읽어야 하므로, 업캐스터(옛 이벤트 → 새 모양)와 copy-transform(스트림을 복사하며 변환해 새 스트림으로)이 상시 업무가 된다[^rich-evolve][^young]. Young 의 첫 질문 — "왜 이벤트를 고칠 수 없는가" — 의 답(이미 읽은 소비자가 있고, 로그는 정정으로만 바꾼다)은 이 저장소의 Flyway 규칙("적용된 마이그레이션 파일은 고치지 않는다")과 같은 정신이지만, 대상이 모든 이벤트 타입으로 늘어난다.

### 3-6. 그래서

Fowler 는 이벤트 소싱이 "강력하지만 ... 익숙하지 않고, 도구와 경험이 적다" 며 **필요한 곳에 한정해서** 쓰라고 했고[^fowler-es], Vernon 도 이벤트 소싱을 집합체 단위의 선택(A+ES)으로 다룬다[^vernon-aes]. Richardson 은 "복잡한 비즈니스 로직 · 감사 요구가 있는 집합체" 를 대상으로 꼽는다. 지금 이 저장소의 Order 는 그 문턱에 **가까워지고 있지만**(§3-2 의 시각 컬럼 9개) 아직 넘지 않았고, 넘기 전에 할 수 있는 더 싼 일(docs/76)을 먼저 했다.

---

## 4. 적용한다면 — Order 집합체부터, 네 릴리스

### 4-1. 범위

**Order 만.** 이유: 상태 전이가 가장 많고(§3-2), 분쟁(환불 · 반품 · 입금 기한)이 생기는 곳이다. `Payment` 는 이미 불변에 가깝고, 카탈로그 · 회원 · 장바구니는 CRUD 에 검색 · 개인정보가 얽혀 있어 대상이 아니다. Vernon 의 A+ES 처럼 **집합체 단위**로 들인다[^vernon-aes] — 서비스 전체를 바꾸지 않는다.

### 4-2. 이벤트 저장소 — PostgreSQL, Kafka 는 발행 채널

```sql
CREATE TABLE orders.order_event (
    aggregate_id   bigint      NOT NULL,          -- orderId
    version        integer     NOT NULL,          -- 집합체 안의 순번, 1부터
    event_id       uuid        NOT NULL,
    event_type     varchar(64) NOT NULL,          -- OrderPlaced · PaymentConfirmed · …
    payload        bytea       NOT NULL,          -- Avro 단일 객체 인코딩(지문 포함) — 지금 토픽과 같은 도구
    occurred_at    timestamptz NOT NULL,
    trace_context  varchar(512),                  -- 지금 아웃박스와 같은 추적 전파(docs/51 §4e)
    outbox_status  varchar(16) NOT NULL DEFAULT 'STARTED',
    PRIMARY KEY (aggregate_id, version)           -- 🔴 낙관적 잠금: 같은 버전을 두 번 쓰면 실패
);
```

- `(aggregate_id, version)` 기본 키가 **낙관적 잠금**이다 — Richardson 의 "Handling concurrent updates using optimistic locking"[^rich-optimistic]. 지금 Order 에 없는 `@Version` 을 이것으로 얻는다.
- 이 표가 **곧 아웃박스**다. 지금의 `PaymentOutboxScheduler` 가 `outbox_status=STARTED` 행을 선점해 Kafka 로 내고 COMPLETED 를 찍는다 — Richardson "이벤트 저장소는 메시지 로그 역할도 한다"[^rich-benefits], Eventuate Local 의 `events` 표를 CDC 로 테일링하는 것과 같은 구조[^rich-store]. 폴링 대신 Debezium 으로 바꾸는 것은 선택이다(docs/76 §4 의 하이브리드 역직렬화기가 필요해진다).
- **Kafka 를 저장소로 쓰지 않는 이유**: 전체 이력이라 컴팩션 불가[^ddia-derive], 집합체 단위 읽기 불가, 브로커 하나(§3-3). PITR 백업(docs/15)이 이미 PostgreSQL 에 있다.
- 스냅샷 표 `order_snapshot(aggregate_id, version, state bytea)` 는 **처음엔 만들지 않는다.** 주문 하나의 이벤트는 10 ~ 15개다. 집합체당 50 을 넘기 시작하면 Richardson · Vernon 의 스냅샷[^rich-snapshot][^vernon-aes] 을 넣는다 — 재구성 비용은 그때 실측한다.

### 4-3. 이벤트 — 의도를 담고, 개인정보는 밖에

Kleppmann: 이벤트 소싱의 이벤트는 "상태 갱신의 기계적 기록이 아니라 **사용자 행동의 의도**"[^ddia-cdc-es]. 지금의 `OrderStatusChanged(PAID → PROCESSING, source=delivery)` 를 그대로 이벤트 소싱 이벤트로 쓰면 "왜" 가 빠진다. 이름을 의도로 바꾼다:

| 지금의 전이 | 이벤트 소싱 이벤트 (예) | 담는 것 |
|---|---|---|
| 생성 | `OrderPlaced` | 상품 · 수량 · 금액 · 쿠폰 · 결제수단, **배송지 id**(본문 아님) |
| → UNPAID | `DepositRequested` / `VirtualAccountIssued` | 기한 · 입금자명 / 은행 · 계좌(가상계좌는 개인정보 아님) |
| → PAID | `PaymentConfirmed` | 제공자 · 결제 참조 · 청구 통화 · 금액 · 시각 |
| → PROCESSING / DISPATCHED / DELIVERED | `ShipmentStarted` · `ShipmentDispatched` · `Delivered` | 배송 id · 송장(배송 서비스가 보낸 값) |
| → COMPLETED | `PurchaseConfirmed` | 고객 / 자동, 시각 |
| → CANCELED | `OrderCanceled` | 사유(고객 · 관리자 · 입금 기한) |
| → REFUNDED(부분 포함) | `RefundIssued` | 금액 · 항목 · 환불 참조 — 부분 환불은 상태가 안 바뀌어도 이벤트다 |
| 반품 · 재고 | `ReturnReceived` · `StockShortageResolved` · `StockRestored` | 지금 시각 컬럼 셋이 하는 일 |

- **배송지는 이벤트에 싣지 않는다.** `OrderPlaced` 는 `order_address` 행의 id 만 가리키고, 그 표는 가변으로 남겨 UPDATE 로 지울 수 있게 한다. 이벤트 안에 꼭 들어가야 할 개인정보가 생기면 주문별 데이터 키로 암호화하고 삭제 시 키를 폐기한다(암호 파쇄 — Richardson · Kleppmann[^rich-drawbacks][^ddia-immut-limits]).
- 인코딩은 **Avro 단일 객체**(지문 포함) — 지금 토픽과 같은 도구라 `AvroSchemaHistory` · 하이브리드 역직렬화기 · B-xx 가드가 그대로 쓰인다. 호환 규칙은 Kleppmann 4장의 작성자 · 독자 스키마[^ddia-avro]: 필드 추가는 기본값과 함께(옛 이벤트도 읽힌다), 제거는 기본값이 있던 필드만.

### 4-4. 집합체 — 명령 → 이벤트, 이벤트 → 상태

```java
// 스케치. 상태는 이벤트를 접은 결과이고, 명령은 검증 뒤 이벤트를 돌려준다.
final class Order {
    private OrderStatus status; private BigDecimal refunded; /* … */ private int version;

    static Order rehydrate(List<OrderEvent> history) { Order o = new Order(); history.forEach(o::apply); return o; }

    List<OrderEvent> confirmPayment(PaymentConfirmed cmd) {             // 명령
        if (!AWAITING_PAYMENT.contains(status)) return List.of();      // 재전달 — 이벤트 없음(멱등)
        return List.of(new PaymentConfirmed(id, version + 1, cmd.provider(), cmd.ref(), cmd.at()));
    }
    private void apply(PaymentConfirmed e) { status = PAID; paidAt = e.at(); version = e.version(); }
    /* apply(...) 는 검증하지 않는다 — 이미 일어난 일이다 */
}
```

- 검증은 명령 단계에서 **동기로** 끝난다. 이벤트가 되면 소비자는 거부할 수 없다[^ddia-commands]. 지금 `OrderDeliveryStatusService` 가 "앞으로만 간다" 를 검사하는 자리가 바로 명령 검증이다.
- 저장: `load = (스냅샷) + 이후 이벤트`, `save = INSERT … (aggregate_id, version)` — 충돌이면 다시 읽고 재시도.
- 테스트: **Given(이벤트들) · When(명령) · Then(이벤트들)** — Young 이 CQRS 문서에서 제안한 형식[^young-cqrs]. 리듀서가 순수 함수라 TopologyTestDriver 없이 검증하는 지금 saga-projection 의 방식과 같다.

### 4-5. 읽기 모델 — `orders` 표를 프로젝션으로 강등

- 지금의 `orders.orders` 표와 21개 조회 · 8개 GET 은 **그대로 둔다.** 다만 그 표를 쓰는 주체가 바뀐다 — 명령 처리기가 이벤트를 `order_event` 에 쓰고, **같은 트랜잭션**에서 `orders` 행을 갱신한다(동기 프로젝션). Kleppmann 이 말한 "읽기 뷰를 이벤트 추가와 같은 트랜잭션으로 갱신하려면 같은 저장소여야 한다" 의 조건이 PostgreSQL 안에서 성립한다[^ddia-concurrency]. 그래서 **읽기 지연이 없고**, 화면 · 관리자 · 내부 API 가 하나도 바뀌지 않는다.
- 서비스 밖 프로젝션(saga-projection 의 타임라인, 알림)은 지금처럼 Kafka 로 — 비동기, eventId 로 멱등[^rich-idem](이미 `OrderTimelineReducer` 가 한다).
- Richardson 7장의 CQRS 는 보통 "다른 DB · 다른 서비스의 뷰" 를 뜻하지만, 같은 DB 안의 두 표로 시작해도 "명령과 조회가 다른 모델을 본다" 는 본질은 같다[^rich-cqrs]. 멀티 노드가 되면 그때 분리한다.

### 4-6. 네 릴리스 — docs/55 의 expand → contract 정신

| 릴리스 | 하는 일 | 진실 | 되돌리기 |
|---|---|---|---|
| **R0** (지금, docs/76) | 상태 변경을 아웃박스 이벤트로, 타임라인 읽기 모델 | `orders` 표 | 이미지만 |
| **R1 저널** | `order_event` 표 생성. `OrderStatusChanger` 가 상태 변경마다 **같은 트랜잭션**에 이벤트도 쓴다(의도 이름으로). 기존 주문은 `OrderSnapshotImported`(현재 행 전체) 한 건으로 시드. **검증 잡**: 이벤트를 재생한 상태 == `orders` 행 — 어긋나면 경보(이것이 "되돌리면 실패하는 테스트" 의 운영판) | `orders` 표 | 이미지만(표는 덤) |
| **R2 전환** | 집합체를 이벤트에서 로드, `orders` 는 동기 프로젝션. 직접 UPDATE 를 가드(B-7x — B-78 의 확장)로 막는다. 재생 잡이 1주일 0건이면 간다 | `order_event` | R1 이미지로 — 두 표가 R1 에서도 함께 쓰였으므로 어긋남 없음 |
| **R3 정리** | `Order` 의 setter · 시각 컬럼 정리(열 삭제는 ROLLBACK-UNSAFE, docs/55), 폴링 → CDC 선택 | `order_event` | 불가(contract) — 안정 뒤에만 |

각 단계는 지금 저장소가 이미 쓰는 도구(Flyway 가드 · 릴리스 표지 · Testcontainers 실제 PostgreSQL 마이그레이션 테스트)로 검증한다. R1 의 "재생 == 행" 검증이 핵심이다 — Kleppmann 이 말한 "같은 로그에서 파생한 뷰" 가 진짜 같은지 **데이터로** 확인한 뒤에만 진실을 옮긴다[^ddia-views].

### 4-7. 치러야 할 값 — 대략

§3-2 의 숫자가 곧 작업량이다: 23 클래스 · 28 호출을 명령/이벤트로, 이벤트 타입 10여 개의 스키마와 업캐스터 자리, R1 의 시드 · 검증 잡, 가드, 문서. 운영 변화는 표 하나(백업 · PITR 범위 안)와 릴레이 처리량(주문당 이벤트 10 ~ 15개 — 지금의 결제 요청 1 ~ 2개 대비 열 배). 이 체급에서 몇 주 단위의 일이고, 얻는 것은 §2-1 의 ④(타임머신)와 ①의 "아웃박스 없이도 발행" 뿐이다 — 나머지는 이미 있다.

---

## 5. 결정을 다시 보는 조건

다음 중 하나가 생기면 §4 를 꺼낸다:

- **감사 · 규제** — 결제 · 환불 이력을 증빙으로 내야 할 때. 타임라인(docs/76)은 읽기 모델이라 증빙이 아니다(재생으로 다시 만들 수 있지만 원본은 `orders` 표의 현재 상태뿐).
- **과거 시점 재구성** 요구 — "환불 당시 주문이 어떤 상태였나" 를 분쟁에서 묻기 시작할 때(§2-1 ④).
- **시각 컬럼이 계속 는다** — 지금 9개. 상태 표가 이력 노릇을 하려는 압력이 더 커지면 그것이 Richardson 의 "전통적 영속화의 문제"[^rich-trouble] 가 임계에 왔다는 뜻이다.
- **같은 이벤트의 소비자가 셋 이상** — 알림 · 분석 · 정산이 각자 주문 이력을 원할 때. 지금은 saga-projection 하나다.
- **운영 체급** — 멀티 노드(docs/71)와 복제된 Kafka 가 생겨 이벤트 저장소 · 발행 채널의 내구성을 따로 말할 수 있을 때.

그 전까지는 docs/76 의 결정 — **진실은 order 의 DB, 이벤트는 알림, 타임라인은 파생** — 을 유지한다.

---

## 6. 참고서

| 책 · 글 | 어디 | 무엇을 가져왔나 |
|---|---|---|
| Martin Kleppmann, *Designing Data-Intensive Applications* (O'Reilly, 2017) | 11장 "Stream Processing" — Change Data Capture · Event Sourcing · Commands and events · State, Streams, and Immutability(Advantages / Deriving several views / Concurrency control / Limitations) | CDC 와 이벤트 소싱의 구분, 전체 이력 · 컴팩션 불가 · 스냅샷, 명령은 동기 검증, 읽기 뷰 지연과 같은 저장소 트랜잭션, 불변의 한계(삭제) |
| 같은 책 | 4장 "Encoding and Evolution" — Avro | 작성자 · 독자 스키마, 전방 · 후방 호환 규칙 |
| Chris Richardson, *Microservices Patterns* (Manning, 2018) | 3장 "Transactional messaging" | 트랜잭셔널 아웃박스 · 폴링 발행기 · 트랜잭션 로그 테일링 |
| 같은 책 | 6장 "Developing business logic with event sourcing" — The trouble with traditional persistence · optimistic locking · snapshots · idempotent message processing · evolving domain events · benefits · drawbacks · Implementing an event store | 이점 넷 · 단점(낯선 모델 · 진화 · 삭제 · 조회), 이벤트 표 + CDC 저장소 구조 |
| 같은 책 | 7장 "Implementing queries in a microservice architecture" — CQRS | 조회를 뷰로, 이벤트 소싱이 CQRS 를 요구하는 이유 |
| Vaughn Vernon, *Implementing Domain-Driven Design* (Addison-Wesley, 2013) | 4장 "Architecture" — Event Sourcing · CQRS, 부록 A "Aggregates and Event Sourcing: A+ES" | 집합체 단위의 선택, 이벤트 저장소 · 스냅샷 |
| Greg Young, *Versioning in an Event Sourced System* (Leanpub, 2017) | "Why can't I update an event?" · "Weak Schema" · "Copy and Replace" | 과거를 고치지 않는다, 업캐스팅, copy-transform |
| Greg Young, *CQRS Documents* (2010, 온라인) | Testing | Given · When · Then 이벤트 테스트 |
| Martin Fowler, "Event Sourcing" (2005) · "What do you mean by 'Event-Driven'?" (2017, martinfowler.com) | — | 네 패턴의 구분, "필요한 곳에 한정해서" |
| Sam Newman, *Building Microservices*, 2nd ed. (O'Reilly, 2021) | 4장 "Pattern: Event-Driven Communication — What's in an Event?", 6장 "Workflow" | 이벤트에 무엇을 싣나, 사가 |

[^fowler-ed]: Fowler, "What do you mean by 'Event-Driven'?" — Event Notification · Event-Carried State Transfer · Event-Sourcing · CQRS 네 가지를 구분하고, 한 단어로 뭉뚱그리는 데서 오해가 생긴다고 적는다.
[^ddia-cdc-es]: Kleppmann, 11장 "Event Sourcing" — CDC 는 DB 를 가변으로 쓰고 변경 로그를 낮은 수준에서 추출하는 반면, 이벤트 소싱은 불변 이벤트 위에 애플리케이션 로직을 세우고 이벤트는 상태 갱신의 기계적 기록이 아니라 사용자 행동의 의도를 표현한다고 구분한다.
[^rich-outbox]: Richardson, 3장 "Transactional messaging" — 아웃박스 표를 임시 메시지 큐로 쓰는 Transactional outbox, 그 표를 폴링해 발행하는 Polling publisher, DB 로그를 읽는 Transaction log tailing.
[^newman-what]: Newman, 4장 "What's in an Event?" — id 만 싣는 이벤트와 전체 상태를 싣는 이벤트의 장단.
[^ddia-views]: Kleppmann, 11장 "Deriving several views from the same event log" — 같은 이벤트 로그에서 서로 다른 읽기 지향 표현을 여럿 만들 수 있고, 이것이 CQRS 로 불린다고 언급한다.
[^rich-cqrs]: Richardson, 7장 — API 조합과 CQRS. 이벤트 소싱은 이벤트 저장소가 기본 키 조회만 지원하므로 조회를 더 어렵게 만들고, 그래서 CQRS 뷰가 필요하다.
[^newman-saga]: Newman, 6장 "Workflow" — 사가, 오케스트레이션과 코레오그래피, 보상 트랜잭션.
[^rich-trouble]: Richardson, 6장 "The trouble with traditional persistence" — 집합체의 이력이 없고, 감사 로깅은 덧붙이는 일이라 지루하고 틀리기 쉬우며, 이벤트 발행이 비즈니스 로직에 덧대어진다.
[^rich-benefits]: Richardson, 6장 "Benefits of event sourcing" — 도메인 이벤트의 신뢰성 있는 발행, 집합체 이력 보존, O/R 임피던스 불일치의 대부분 회피, 개발자에게 타임머신.
[^ddia-immut]: Kleppmann, 11장 "Advantages of immutable events" — 회계 장부의 비유, 잘못된 거래는 정정 거래로, 버그 복구와 디버깅의 용이함.
[^rich-drawbacks]: Richardson, 6장 "Drawbacks of event sourcing" — 다르고 낯선 프로그래밍 모델, 메시지 기반 애플리케이션의 복잡성(중복 처리), 이벤트 진화의 어려움, 데이터 삭제의 어려움(GDPR — 개인별 키 암호화와 키 폐기), 이벤트 저장소 조회의 어려움.
[^ddia-commands]: Kleppmann, 11장 "Commands and events" — 요청은 명령으로 도착해 검증을 거친 뒤에야 이벤트가 되며, 이벤트 스트림의 소비자는 이벤트를 거부할 수 없으므로 검증은 그 전에 동기로 끝나야 한다.
[^ddia-derive]: Kleppmann, 11장 "Deriving current state from the event log" — 이벤트 소싱에서는 나중 이벤트가 앞 이벤트를 덮지 않아 전체 이력이 필요하고, CDC 와 달리 로그 컴팩션을 같은 식으로 쓸 수 없으며, 그래서 스냅샷을 둔다.
[^vernon-aes]: Vernon, 부록 A "Aggregates and Event Sourcing: A+ES" — 집합체마다 이벤트 스트림, 이벤트 저장소, 스냅샷, 성능 고려.
[^rich-evolve]: Richardson, 6장 "Evolving domain events" — 스키마 변경의 종류와 호환성, 업캐스팅.
[^young]: Young, *Versioning in an Event Sourced System* — 이벤트는 고치지 않는다, 약한 스키마(기본값으로 읽기), 업캐스팅, Copy and Replace(스트림을 복사 · 변환해 새 스트림으로).
[^ddia-immut-limits]: Kleppmann, 11장 "Limitations of immutability" — 보존 · 높은 변경률 · 그리고 규제 등으로 데이터를 정말 지워야 할 때의 어려움(Datomic 의 excision, Fossil 의 shunning).
[^ddia-concurrency]: Kleppmann, 11장 "Concurrency control" — 이벤트를 쓴 직후 읽기 뷰가 아직 갱신되지 않았을 수 있으며, 뷰 갱신을 이벤트 추가와 동기로 묶으려면 둘이 같은 저장소에 있거나 분산 트랜잭션이 필요하다.
[^rich-idem]: Richardson, 6장 "Idempotent message processing" — 처리한 메시지 id 를 추적해 중복을 거른다.
[^rich-optimistic]: Richardson, 6장 "Handling concurrent updates using optimistic locking" — 집합체 버전으로 동시 갱신을 감지한다.
[^rich-store]: Richardson, 6장 "Implementing an event store" — Eventuate Local: RDB 의 events · entities · snapshots 표와 트랜잭션 로그 테일링으로 Kafka 에 발행.
[^rich-snapshot]: Richardson, 6장 "Using snapshots to improve performance".
[^ddia-avro]: Kleppmann, 4장 "Avro" — 작성자 스키마와 독자 스키마, 스키마 해석과 진화 규칙.
[^young-cqrs]: Young, *CQRS Documents* — 집합체 테스트를 "Given 이벤트들, When 명령, Then 이벤트들" 로 쓴다.
[^fowler-es]: Fowler, "Event Sourcing" — 익숙하지 않은 모델과 도구 · 경험의 부족을 들어 모든 곳에 쓰는 패턴이 아니라고 적는다.
