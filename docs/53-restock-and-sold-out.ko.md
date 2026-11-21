# 53. 품절과 재입고 — 운영자에게는 품절 경보, 손님에게는 재입고 알림

[日本語](53-restock-and-sold-out.md) | **한국어**

## 1. 누구에게 무엇을 알리나

| 사건 | 받는 사람 | 경로 | 이유 |
|---|---|---|---|
| **품절**(재고 있음 → 0) | 운영자 | 로그 `품절 발생` → Loki 경보 `ProductSoldOut`(warning) → Alertmanager → Discord(B-96) | 재고를 채울 사람은 운영자다. 손님은 상품 화면의 "품절" 로 충분하다 |
| **재입고**(0 → 있음) | 그 옵션에 **신청한** 손님 | `product-stock`(RESTOCKED) → notification-service → `RESTOCK_AVAILABLE` 알림(앱·메일) | 손님이 부탁한 소식이다. 신청하지 않은 손님에게는 소음이다 |

재고 복원 자체(환불·반품 입고)는 손님에게 알리지 않는다 — 가게 내부 사정이다. 손님에게 중요한 것은 "돈이
돌아왔다" 이고, 그것은 환불 알림(docs/49 §10, 부분 환불 포함)이 한다.

## 2. catalog-service — 0 을 건너는 순간만

재고가 바뀌는 곳은 한 군데다: `ProductService.updateProductSize_Qty`(주문 차감·환불/반품 복원). 여기에
관리자 재고 조정(`setStock`, §4)이 더해졌다. 둘 다 바꾸기 전·후 수량을 `StockEventProducer.onQuantityChanged`
에 넘긴다.

- **0 을 건널 때만** 뭔가 한다(`transitionOf`): 있음 → 0 은 `SOLD_OUT`, 0 → 있음 은 `RESTOCKED`. 5 → 3 같은
  보통 변화는 조용하다 — 주문마다 이벤트가 나가면 소음이다.
- **커밋 뒤에** 찍고 보낸다. 차감이 롤백됐는데 "품절" 경보나 "재입고" 알림이 나가면 거짓말이다. 필요한 값
  (상품 id·이름·slug, 색상 id·이름, 사이즈)은 커밋 전에 읽어 둔다.
- 품절은 `log.warn("품절 발생: productId=… colorId=… size=… name=…")`. 이 문구는 경보 규칙과 **계약**이다 —
  `StockEventProducer.SOLD_OUT_MARKER` 와 규칙 파일의 `SOLD_OUT_MARKER` 를 B-61 이 대조한다.
- 이벤트는 `product-stock` 토픽(키 = 상품 id — 같은 상품의 품절·재입고가 뒤집혀 도착하지 않게):

```json
{"type":"RESTOCKED","productId":10,"slug":"tee","name":"티셔츠","colorId":3,"color":"black",
 "size":"M","quantity":12,"occurredAt":"…","eventId":"restocked:10:3:M:<ms>"}
```

## 3. notification-service — 신청과 발송

**신청** — `notification.restock_subscription`(V10). `(user_id, product_id, color_id, size)` UNIQUE. 색상 없는
상품은 `color_id = 0` — null 을 두면 UNIQUE 가 같은 신청 둘을 막지 못한다. 한 손님이 걸어 둘 수 있는 신청은
100개(`MAX_ACTIVE_PER_USER`).

| API (`/api/notifications/restock-subscriptions`) | |
|---|---|
| `GET ?productId=` | 이 상품에 걸어 둔 옵션 `[{colorId, size}]` — 상품 화면의 버튼 상태 |
| `POST {productId, colorId, size, productName, productSlug}` | 신청. 새로 걸면 201, 이미 있으면 200, 한도면 429 |
| `DELETE ?productId=&colorId=&size=` | 거두기. 없거나 이미 알린 신청이면 404 |

신원은 게이트웨이가 토큰에서 뽑은 `User-Id` 뿐이다 — 본문에 사용자 값을 받지 않는다.

**발송** — `RestockEventListener` 가 `product-stock` 을 듣는다. `RESTOCKED` 면 그 옵션의 아직 알리지 않은 신청을
500개씩(`FANOUT_PAGE`, 페이지마다 트랜잭션이 따로) 읽어 알림을 만들고 `notified_at` 을 찍는다. `SOLD_OUT` 은
하는 일이 없다(§1).

- 알림: `RESTOCK_AVAILABLE` "재입고 알림" — "티셔츠 (black / M) 이(가) 다시 입고되었습니다. 품절되기 전에 확인해
  보세요." 링크는 `product/<slug>`. 앱·메일, 문자는 아니다(급하지 않고 놓쳐도 손해가 없다).
- 키는 `restock:<신청 id>` — 재전달돼도 하나다. **한 번 알린 신청은 끝난다.** 다음 재입고도 받고 싶으면 다시
  신청한다 — 알린 행을 지우고 새로 만들어 새 id(= 새 키)가 된다.
- V10 이 `notification.type`·`mail_message.kind` 두 CHECK 에 `RESTOCK_AVAILABLE` 을 더했다(B-55).

## 4. 관리자 재고 — 예전에는 채울 방법이 없었다

재고는 주문으로 줄고 환불·반품 입고로만 돌아왔다. 품절 경보를 받아도 운영자가 할 수 있는 일이 없었다.

- `GET /api/content/admin/stock?soldOutOnly=true&after=<sizeId>` — 품절(또는 전체) 옵션, 사이즈 id 커서, 한 번에 200개
- `PUT /api/content/admin/stock/{sizeId}` `{"quantity": 12}` — 그 값으로 **정한다**(더하기가 아니다). 0~1,000,000.
  0 에서 채우면 §2 의 재입고가 나간다.
- `/api/content/admin` 아래라 GET 도 관리자 전용이다(AdminWriteInterceptor).
- 화면: `/admin/stock`(사이드바 Orders → Stock). 저장 뒤 서버에서 다시 읽는다.

## 5. 프론트 — 상품 화면의 "재입고 알림 받기"

선택한 사이즈가 품절이면(`?size=` 가 있고 그 사이즈 재고가 0) `RestockButton` 이 보인다. 누르면 신청, 다시
누르면 거둔다(`aria-pressed`). 로그인하지 않았으면 로그인으로 보낸다. 알림 아이콘은 `RESTOCK_AVAILABLE`.

## 6. 가드

| | 무엇을 |
|---|---|
| `StockEventProducerTest` | 0 을 건널 때만, 커밋 뒤에, 옵션 정보와 함께 |
| `ProductServiceUnitTest` | 재고 변경이 전·후 수량과 옵션으로 넘어간다, 관리자 조정(그 값으로·음수 거절·없는 사이즈) |
| `RestockSubscriptionServiceTest` | 새 신청(색상 0)·중복·알린 뒤 재신청(새 id)·한도, 발송(신청 id 키·링크·알렸다고 찍음), 거두기 |
| `RestockEventListenerTest` | 재입고만 알린다, 페이지가 덜 찰 때까지, 품절·깨진 이벤트는 무시 |
| `RestockButton.test` · `Stock.test` | 신청/거두기가 보내는 값, 로그인, 관리자 저장·검증·다시 읽기 |
| B-41 · B-60b · B-61 | `product-stock` 이 DLT 목록에, 리스너가 추적을 이어받는다, 품절 문구 = 경보 문구 |

V10 은 로컬 PostgreSQL 16 에 V1~V10 을 차례로 적용해 확인했다(색상 기본값 0, UNIQUE, 부분 인덱스, 두 CHECK).

## 7. 초과 판매 — 결제 뒤 재고가 모자랄 때

### 🔴 무엇이 문제였나

재고는 **결제 뒤에** 차감한다(order 의 사가가 `product-update` 를 보낸다). 마지막 1개를 두 사람이 동시에 결제하면 뒤의
차감이 모자란다. 그때 일어난 일:

- `updateProductSize_Qty` 가 조용히 `null` 을 돌려주고, 리스너는 `Product could not be updated` 한 줄을 남기고 끝났다.
- 주문은 결제 완료로 진행됐다. 환불도 경보도 없었다 — 손님은 돈을 냈는데 물건은 없고, 운영자는 배송 단계에서야 알았다.
- 🐛 판매량은 수량을 확인하기 **전에** 올라가서, 차감이 실패해도 판매량만 늘었다.
- 동시성 자체는 문제가 아니었다 — SKU 행을 `PESSIMISTIC_WRITE` 로 잠가 같은 옵션의 차감은 줄을 선다. 문제는 **모자랄 때의
  처리**였다.

### 어떻게 바꿨나

```
catalog  product-update 소비 ─┬─ 줄마다 SKU 잠금 → 남은 수 확인 → 있는 만큼만 배정(판매량도 그만큼)
                              └─ 모자란 수 → product.stock_shortage (같은 트랜잭션, V13) ─ 발행기(2초) → stock-shortage
order    stock-shortage 소비 ─┬─ 줄마다 short_qty 적기(V21, 멱등 키 shortage_event_id)
                              └─ 그 수만큼 부분 환불(관리자 권한의 기존 환불 경로) → 손님에게 기존 환불 알림
```

| 결정 | 이유 |
|---|---|
| **있는 만큼 배정**하고 나머지만 환불 | 3개 주문에 1개 남았으면 1개는 보낸다. 부분 환불이 수량 단위라 가능하다(docs/49 §10) |
| 부족 기록을 **아웃박스**로(커밋 뒤 한 번 보내기가 아니라) | 돈이 걸린 알림이다. 커밋 뒤 발행이 실패하면 손님이 환불을 못 받는다. 표에 적으면 다음 주기에 다시 보낸다. 10분 넘게 못 보내면 "수동 확인 필요" |
| 주문 id 를 차감 이벤트의 멱등 키(`order-<id>`)에서 읽는다 | 차감 메시지(Avro)에 필드를 더하면 스키마 진화 두 단계가 필요하다(docs/55 §4). 키에 이미 있다 |
| 부족분을 `short_qty` 로 **따로 센다** | 발송 전 환불은 수량을 "돌아왔다" 로 세고 재고 복원을 보낸다. 모자란 수는 **차감된 적이 없다** — 그대로 두면 catalog 에 없는 재고가 생긴다. `short_qty` 는 돌아올 수·복원할 수·보낼 수(배송 정보)에서 빠지고, 환불은 부족분을 먼저 소진한다 |
| 기존 환불 경로를 그대로 쓴다 | 금액(할인 배분)·통화(청구 통화)·제공자 멱등 키·pay 알림·쿠폰 복원(전액일 때)·손님 알림 규칙을 다시 만들지 않는다 |
| 🔒 **무통장입금은 자동 환불하지 않는다** | 게이트웨이가 언제나 "받아들였다" 고 답한다 — 자동으로 부르면 돈은 안 갔는데 환불로 적힌다. 부족은 적고 "수동 환불 필요" 로 넘긴다 |
| 환불이 실패하면 **던진다** | Kafka 가 다시 전달하고, 그때는 적는 단계를 건너뛰고 환불만 다시 한다(돌려줄 수 = 적힌 부족분 − 이미 환불된 수). 재시도가 다 실패하면 DLT 로 가고, 실패마다 남긴 "수동 환불 필요" 로 운영자가 안다 |

전부 모자랐으면 주문 전체가 환불되고(REFUNDED), 결제 취소가 pay → `payment` CANCELED 로 이어져 배송도 취소된다.

### 알리는 것

| 신호 | 언제 | 어디 |
|---|---|---|
| `StockShortageOccurred` (warning) | 초과 판매가 났다 — 자동 환불되지만 재고 수치가 실물과 어긋났다는 신호다 | Prometheus `catalog_stock_shortage_total` |
| `StockShortageRefundNotAutomatic` (critical) | 무통장·모르는 주문 | Prometheus `order_stock_shortage_total{outcome}` |
| `ManualActionRequired` | 자동 환불 실패, 발행 10분 지연, 품목 불일치 | Loki — 로그 "수동 확인/환불 필요"(B-61) |

### 한계 — 정직하게

- **이 절은 사후 보상이다.** 초과 판매가 일어난 뒤 몇 초 안에 돈을 돌려준다. 결제 전 재고 예약(주문 생성 때 잡고 결제 실패·이탈
  때 풀기, 기한 만료 잡)은 [docs/59](59-stock-reservation.ko.md) 에서 더했다 — 이 절은 그 뒤에도 예약이 만료된 뒤의 결제·catalog 에
  닿지 못한 주문(fail-open)·가상계좌의 안전망으로 남는다.
- ~~**부분 부족인데 배송이 먼저 만들어졌을 수 있다.**~~ → 2026-11-21 고침(5단계 15, 아래 '품절 취소 — 대형 EC 방식'). 예전에는 배송 수량이
  주문 수량 그대로 남아 관리자가 품목의 `shortQty` 를 보고 송장을 맞춰야 했고, 전부 모자란 줄도 배송에 1개로 실렸다(`Math.max(1, …)`).
- 발행기는 파드마다 돈다 — 같은 행을 둘이 보낼 수 있고, order 가 멱등 키로 버린다.


### 품절 취소 — 대형 EC 방식(2026-11-21, 5단계 15)

아마존 · 쿠팡 · 라쿠텐처럼 **있는 것만 보내고, 없는 것은 그 품목만 취소 · 환불하고, 손님에게 '품절 취소' 로 따로 알린다.**

| 규칙 | 구현 |
|---|---|
| 부족을 적는 **같은 트랜잭션**에서 delivery 에 다시 맞추라고 알린다 — 무통장 · 환불 실패에도(보내지 않을 물건이다) | order `Recorder.record` 가 아웃박스 행 `delivery-resync`(키 주문 id) → 릴레이 → 토픽 `delivery-resync` |
| delivery 는 보낼 수를 **지금** order 에 묻는다(배정된 수) — 재전달 · 순서가 바뀌어도 마지막 값으로 맞는다 | `DeliveryResyncListener` → 기존 내부 경로 `/api/order/internal/orders/{id}/shipping` → `DeliveryService.syncShippable` |
| 발송 전 상자(송장 없음 · 대기)만 줄인다 — 분할 배송이면 **나중 상자부터** · 0 이 된 품목은 빼고 · 빈 상자는 취소 · 늘리지 않는다 · 멱등 | `Delivery.syncShippableQuantities` |
| 송장이 붙은 상자는 고치지 않는다 — 환불은 이미 나갔다, 실물만 사람이 본다 | 로그 "재고 부족분이 이미 송장이 붙은 상자에 있다 — 수동 확인 필요"(Loki `ManualActionRequired`, B-61) |
| 배송을 만들 때 보낼 수 0 인 줄은 싣지 않는다 · 전부 0 이면 만들지 않는다(결제 취소가 따른다) | `PaymentCompletedListener.commandFor` |
| 손님에게는 '일부 상품 품절 취소' — 무엇이 왜 빠졌는지 · 나머지는 그대로 보낸다 | `order-refunded` 에 `cause: stock-shortage` → notification `SOLD_OUT_CANCEL_TITLE` · `soldOutCancel`(전액이면 결제 응답의 '환불 완료' 그대로) |

배포: order · delivery · notification 이미지, 순서 상관없음(옛 delivery 면 토픽에 쌓였다가 새 파드가 earliest 로 읽는다 · 옛 notification 이면 예전 문구). DLT 는 `ops/02` 에 `delivery-resync` 를 더했다 — 배포 전에 한 번 돌린다.
시험: `DeliveryShortageSyncTest`(3) · `DeliveryResyncListenerTest`(2) · `PaymentCompletedListenerTest` +1 · `OrderStockShortageServiceTest` +1 · `DeliveryResyncKafkaPublisherTest`(2) · `OrderRefundedKafkaPublisherTest` +1 · `OrderRefundedNotificationTest` +1.

### 가드

| | 무엇을 |
|---|---|
| `ProductServiceUnitTest` | 🔴 모자라면 있는 만큼만 배정·판매량도 그만큼, 0 이면 아무것도 안 바꿈, 상품 없음 = 전부 부족, 복원은 부족 없음, 남은 수는 잠금 뒤에 읽고 음수는 0 |
| `ProductUpdateMessageListenerTest` | 🔴 모자라면 같은 트랜잭션에서 부족 기록, 안 모자라면 기록 없음 |
| `StockShortageOutboxTest` | 이벤트 키에서 주문 id·JSON, 주문 모르면 안 적음, 보내고 나서 표시, 실패하면 표시 안 하고 멈춤 |
| `OrderStockShortageServiceTest` | 🔴 적고 그만큼 자동 환불, 해결된 재전달 무시, 🔴 환불 실패 → 재전달 때 적지 않고 환불만, 🔒 무통장은 사람에게, 돌려줄 것 없음, 모르는 주문, 줄 수 초과 없음, 배송 수량 = 배정된 수 |
| `OrderRefundTest` | 🔴 부족분 환불은 돌아온 수로 세지 않는다(없는 재고를 복원하지 않게), 발송 뒤 부족분만 환불하면 반품 대기 아님 |
| B-41 · B-61 | `stock-shortage` 가 DLT 목록에, "수동 … 필요" 문구 = 경보 문구 |

catalog V13 과 order V21 은 PostgreSQL 16 에 두 번씩 적용해 확인했다(멱등, 부분 인덱스, 중복 키 거절).
