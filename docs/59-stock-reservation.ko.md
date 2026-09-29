# 59. 결제 전 재고 예약

[日本語](59-stock-reservation.md) | **한국어**

> docs/53 §7 의 다음 단계. §7 은 초과 판매가 **일어난 뒤** 몇 초 안에 돈을 돌려준다. 이 문서는 초과 판매가 **일어나지 않게**
> 주문을 만들 때 재고를 잡아 두는 쪽이다. §7 의 보상은 그대로 두고 안전망으로 쓴다.

## 1. 무엇이 문제였나

재고는 **결제 뒤에** 차감된다(사가의 `product-update`). 그 사이 아무것도 재고를 붙들지 않았다:

- 마지막 1개를 두 사람이 주문하면 둘 다 결제 화면까지 간다. 둘 다 결제하고, 늦은 쪽은 몇 초 뒤 자동 환불을 받는다.
  손님에게는 "결제됐다 → 환불됐다" 두 번의 알림이다. 결제 수수료는 환불해도 돌아오지 않는 PG 가 많다.
- 🔴 **무통장입금은 자동 환불이 안 된다**(docs/53 §7). 입금 기한 48시간 동안 재고를 잡지 않으니, 입금하는 사이 팔린 물건에
  손님이 돈을 보내고, 운영자가 손으로 돌려줘야 했다.
- 결제 화면에서 "지금 결제하면 받을 수 있다" 는 약속을 할 수 없었다.

## 2. 어떻게 바꿨나 — 한눈에

```
주문 생성 ──▶ order 저장(id 가 예약의 키) ──▶ catalog POST /api/internal/stock/reservations  (30분)
                                                  ├─ HELD   → orders.stock_hold_until 적고 결제 화면으로
                                                  ├─ SHORT  → 방금 만든 주문을 지우고 400 "재고가 부족해 주문하지 못했습니다: …"
                                                  └─ 닿지 못함 → 막지 않는다(fail-open) — §7 의 보상이 뒤를 받친다
무통장 입금 시작 ──▶ 같은 예약을 입금 기한 + 1시간(49시간)으로 연장. 모자라면 입금 안내를 내지 않는다
결제 완료 ──▶ 사가의 product-update(eventId order-<id>) ──▶ catalog 가 차감하며 그 주문의 예약을 같은 트랜잭션에서 COMMITTED
취소·만료·사가 롤백 ──▶ 커밋 뒤 POST /api/internal/stock/reservations/{orderId}/release
기한 초과 ──▶ catalog 의 만료 잡(60초)이 HELD → RELEASED
```

## 3. catalog — 예약은 재고의 주인이 가진다

`product.stock_reservation`(V14): `order_id`·`product_id`·`color_id`·`size`·`qty`·`status`(HELD|COMMITTED|RELEASED)·`expires_at`.
`UNIQUE(order_id, product_id, color_id, size)` — 같은 주문이 다시 잡으면 새 행이 아니라 그 행을 고친다.

| 결정 | 이유 |
|---|---|
| **남은 수 = `quantity` − 다른 주문의 유효한 HELD 합** | `quantity` 컬럼을 예약 때 깎으면 결제 전 이탈마다 되돌려야 하고, 되돌리기가 빠지면 재고가 사라진다. 예약은 따로 세고, 실제 차감은 지금처럼 결제 뒤 한 번이다. 만료된 HELD 는 잡이 치우기 전에도 합에서 빠진다(`expires_at > now`) |
| **전부 아니면 전무** | 3줄 중 1줄만 잡힌 주문은 결제할 수 없는 주문이다. 한 줄이라도 모자라면 아무것도 잡지 않고 모자란 줄(요청·남은 수)을 돌려준다 |
| SKU 행을 `PESSIMISTIC_WRITE` 로, **정렬된 순서로** 잠근다 | 결제 뒤 차감과 같은 잠금이라 예약과 차감이 줄을 선다. 두 주문이 같은 두 SKU 를 반대 순서로 잠그면 교착이 난다 — `(productId, colorId, size)` 순으로 잠근다 |
| 확정은 **차감과 같은 트랜잭션** | `product-update` 가 차감하면서 그 주문(`order-<id>` 에서 읽는다 — docs/53 §7 과 같은 키)의 예약을 COMMITTED 로. 차감할 때 남은 수는 "그 주문을 뺀" 다른 주문의 예약만 뺀다 — 자기 예약을 빼면 자기가 못 산다 |
| 유효 시간은 1분 ~ 72시간으로 자른다 | 부르는 쪽 실수로 영원한 예약이 생기지 않게 |
| 내부 API 는 `/api/internal/**` — 내부 호출 또는 관리자만 | `AdminWriteInterceptor` 가 **읽기까지** 막는다(`InternalCallGuard.allowed`). 게이트웨이가 신원 헤더를 붙인 요청(=바깥)은 거절된다 |
| 재입고(`restock`) 이벤트는 예약과 무관 | 늘리는 쪽은 누구의 예약도 건드리지 않는다 |
| 예약 합·만료 갱신은 **네이티브 SQL 에 `status = 'HELD'` 리터럴** | 🐛 처음에는 JPQL 에 상태를 바인딩 값으로 넘겼다. PostgreSQL 의 일반 플랜(같은 문장을 여러 번 실행하면 쓰는 것)은 바인딩 값으로 부분 인덱스(`WHERE status = 'HELD'`)의 조건을 증명하지 못해 **표 전체를 훑었다** — PG16 `plan_cache_mode = force_generic_plan` 에서 확인(행 20만, Parallel Seq Scan). 이 조회는 SKU 잠금을 쥔 채 돌고, 표는 확정·해제된 행이 쌓이기만 한다. 리터럴로 바꾼 뒤 세 조회(옵션별 합·상품별 합·만료 갱신) 모두 부분 인덱스를 탄다 |

## 4. order — 언제 잡고 언제 푸나

| 시점 | 동작 | 모자라면 | 닿지 못하면 |
|---|---|---|---|
| 주문 생성 | 같은 고객의 앞선 결제 전 주문이 잡은 것을 먼저 풀고, 저장 직후 30분(`order.stock-hold.checkout-minutes`) 예약 | 방금 만든 주문을 **지우고** 400. 문구에 상품·옵션·남은 수("티셔츠(M) 남은 수량 1개" / "품절") | 주문은 만들어진다. `order_stock_hold_total{result="unavailable"}` + 경고 로그 |
| 무통장 입금 시작 | 입금 기한 + 1시간으로 연장 | 입금 안내를 **내지 않는다**(400 "재고가 부족해 입금을 시작하지 못했습니다: …") | 안내는 낸다 |
| 포트원 가상계좌 발급 | 연장 | ⚠️ 경고만 — 계좌는 이미 PG 가 발급했다 | 경고 |
| PayPal 취소 · 무통장 취소 · 입금 기한 만료 · 사가 롤백 | **커밋 뒤** 해제 | — | 해제 실패는 삼킨다 — 만료 잡이 뒤를 받친다 |
| 결제 완료 | 아무것도 안 한다 — catalog 가 차감하며 확정 | — | — |

| 결정 | 이유 |
|---|---|
| 주문을 **먼저 저장하고** 잡는다 | 예약의 키가 주문 id 다. 먼저 잡으려면 임시 키가 필요하고, 그 키를 주문으로 옮기는 단계가 또 실패할 수 있다 |
| 모자라면 주문을 **지운다**(상태를 CANCELED 로 두지 않는다) | 결제도 외부 발행도 없던 주문이다. 남기면 주문 목록에 "취소됨" 이 쌓이고 손님은 취소한 적이 없다. 장바구니는 그대로라 수량을 고쳐 다시 주문할 수 있다 |
| 지우기는 네 단계 모두 **"결제 전·결제 참조 없음"** 을 조건으로 건다 | 주문과 주소가 서로를 가리켜(`orders.order_address_id` ↔ `order_address.order_id`) 순서대로 지운다. 조건 덕에 잘못 불러도 결제된 주문은 지워지지 않는다 — PG16 에서 결제 전/결제됨/결제 참조 있음 세 주문으로 확인 |
| catalog 에 닿지 못하면 **막지 않는다**(fail-open) | 예약은 초과 판매를 **줄이는** 장치이고 막는 장치는 §7 의 보상이 이미 있다. catalog 가 잠깐 느린 것으로 주문 전체를 멈추는 것이 더 비싸다 |
| 해제는 **커밋 뒤**(`afterCommit`) | 취소 트랜잭션이 롤백됐는데 예약만 풀리면 살아 있는 주문의 재고를 남이 가져간다 |
| 예약 시각을 주문에 적는다(`orders.stock_hold_until`, V22) | 결제 화면이 "언제까지 결제하면 되는지" 를 말할 근거. catalog 에 다시 묻지 않는다 |
| 🐛 새 주문을 만들면 **같은 고객의 앞선 결제 전 주문의 예약을 푼다** | 장바구니는 결제 뒤에야 비워진다. 주문하고 결제 화면에서 돌아가 다시 주문하면, 앞 주문의 예약이 새 주문에게는 "남의 예약" 이라 마지막 1개를 산 손님이 **자기 예약에 막혀** "재고가 부족해 주문하지 못했습니다" 를 받았다. 앞 주문은 그대로 두고(결제할 수는 있다) 보관 시각만 지금으로 당긴다 — 결제 화면이 "재고가 보장되지 않습니다" 라고 말한다. 입금 대기(UNPAID)는 입금 기한까지의 약속이라 건드리지 않는다. 고객별 조회라 `orders.customer_id` 인덱스를 더했다(V23 — 기존 `idx_order` 는 `tracking_id` 가 앞이라 못 쓴다) |

## 5. 손님에게 보이는 남은 수량 — 상품 상세·장바구니 담기·목록 카드

상품 상세(`GET /api/product/{slug}`), 장바구니 담기 조회(`GET /api/cart/{id}`·`/api/cart/info`), 목록 카드(검색 `GET /api/search`,
홈 `GET /api/content/home`, 기획전 `GET /api/content/events/{slug}`)의 남은 수량은 **지금 실물 − 다른 주문의 살아 있는 예약**이다.
옵션마다 `onHold`(실물 중 잡혀 있는 수)도 싣는다.

| 결정 | 이유 |
|---|---|
| **응답 직전에** 뺀다(`StockAvailability`) — 캐시(`products`·`search_cache`)는 그대로 | 예약은 분 단위로 생겼다 사라진다. 캐시된 값에 넣으면 예약이 풀려도 캐시가 바뀔 때까지 "0개" 가 남는다. 응답마다 상품당 한 번, 부분 인덱스를 타는 조회(§3)로 읽는다. 캐시된 객체는 건드리지 않고 복사본을 고친다 |
| 컨트롤러에서 — **트랜잭션 밖** | 트랜잭션 안에서 조회가 실패하면 예외를 삼켜도 그 트랜잭션이 rollback-only 가 돼 응답 전체가 실패한다 |
| 조회가 실패하면 실물 그대로 | 남은 수량 표시 때문에 상품 화면이 깨지면 안 된다. 주문 단계의 예약이 어차피 정확히 거른다 |
| `onHold` 는 0 이면 **싣지 않는다**(`@JsonInclude(NON_DEFAULT)`) | `products` 캐시의 직렬화기는 모르는 필드에서 실패한다. 캐시된 DTO 에는 늘 0 이라 JSON 에 없다 — 이 필드를 모르는 옛 파드가 롤링 중에 캐시를 읽어도 깨지지 않는다. DTO 에 `ignoreUnknown` 도 붙였다(다음 번을 위해) |
| 잡힌 수는 **실물까지만** | 관리자가 재고를 줄여 실물보다 많이 잡혀 있을 수 있다. 남은 수량이 음수가 되지 않는다 |
| 🔒 관리자 목록·편집(`/api/product/products`, `/product/edit/…`)에는 **빼지 않는다** | 뺀 값을 편집 화면이 받아 저장하면 실물 재고가 줄어든다 |
| 🐛 수량도 캐시가 아니라 **지금 값**(`size_id` 로 맞춘다) | 홈 카드가 오는 카테고리 목록 캐시(`products`, 카테고리 키)는 **만료가 없고 재고가 바뀌어도 비우지 않는다** — 그 안의 실물 수량부터 언제 적 것인지 모른다. 예약만 빼면 "옛날 수량 − 지금 예약" 이 된다. 한 묶음(상세 하나, 목록 한 페이지, 홈 전체)에 대해 사이즈별 지금 수량 한 번 + 예약 합 한 번을 읽는다 — 상품마다 읽지 않는다. 두 읽기는 따로 실패한다(하나를 못 읽어도 다른 하나는 반영) |
| 외래 키 인덱스(V15 — `product_sku.product_id`, `product_size.skuproduct_id`) | PostgreSQL 은 외래 키에 인덱스를 만들지 않는데 기준 스키마(V5)에 빠져 있었다. 지금 수량 조회뿐 아니라 상세의 지연 로딩, 차감·예약의 SKU 잠금 조회(`findForStockUpdate`)도 이 경로라 모두 표 전체를 훑고 있었다. PG16 에서 두 인덱스를 타는 것을 확인 |

프론트(`components/product/Infos.jsx`):

- 고른 사이즈가 0개인데 `onHold > 0` 이면 품절이 아니다 — "다른 고객이 결제 중인 재고입니다. 결제되지 않으면 곧 다시 구매할 수
  있어요." 를 보여 주고 **재입고 알림은 권하지 않는다**(재입고 알림은 실물이 0 을 건널 때만 가서, 예약이 풀려 돌아오는 것으로는
  오지 않는다).
- 담기 직전 조회에서 0개가 되었으면(화면을 연 뒤 누가 잡았다) 같은 안내. 🐛 예전 검사 순서로는 0개도 "재고보다 많은 수량" 으로
  나왔다 — 0개를 먼저 본다.
- **장바구니 화면**은 담을 때 받은 수량을 들고 있었다(며칠 전 값일 수 있다 — "sold out" 표시도 그 값으로 했다). 이제 화면에
  들어올 때와 다른 탭에서 돌아올 때 `GET /api/stock?items=상품id:색상id:사이즈`(예약과 같은 키 — 장바구니 항목은 사이즈 순번이
  아니라 이름·색상 id 를 가진다)로 **지금** 재고를 받아 항목을 갱신한다(`syncStock` — 사용자 편집이 아니므로 장바구니 상태·담은
  수량은 그대로). 항목마다 `판매 종료`·`품절`·`결제 중`·`남은 수량 N개 — 수량을 줄여 주세요` 를 알리고, `+` 는 남은 수량에서
  멈춘다. 고른 항목 중 지금 재고로 살 수 없는 것이 있으면 주문서로 넘어가지 않는다(주문 단계의 예약이 어차피 거절한다).
  응답에 없는 옵션은 판매 종료(`UNAVAILABLE`)로 본다 — 그래서 재고를 **읽지 못하면 503**(빈 결과와 구분)이고, 화면은 가진 값을
  그대로 둔다.
- **목록 카드**(`components/home/ProductCard.jsx`, 판정은 `util/stock.js`)는 고른 색상의 사이즈를 합쳐 배지 하나를 단다:
  `품절`(남은 것도 잡힌 것도 없음, 또는 관리자가 품절로 내림 — 사진을 흐리게), `결제 진행 중`(남은 건 없지만 누가 결제 중),
  `N개 남음`(3개 이하). 넉넉하면 배지가 없고, 응답에 수량이 없으면 아무 말도 하지 않는다 — 모르는 것을 품절이라 하지 않는다.

## 5-1. 프론트 — 결제 화면의 안내

`components/order/StockHoldNotice.jsx` 가 주문 결제 화면(`Payment.jsx`)에 그린다.

- 기한 전: "주문하신 상품의 재고를 **9/25 오후 3:30** 까지 잡아 두었습니다. 이 시간 안에 결제해 주세요."
- 기한 뒤: "재고 보관 시간이 지났습니다. 지금 결제할 수는 있지만 재고가 보장되지 않습니다 — 결제 뒤 재고가 모자라면 모자란
  수량만큼 환불됩니다." 화면을 켜 둔 채 기한이 지나면 그 순간 문구가 바뀐다.
- 예약 시각이 없으면(fail-open 으로 만들어진 주문, 기능이 꺼짐) 그리지 않는다 — 잡아 두었다고 말할 근거가 없다.
- 결제 전(`NOT_PROCESSED`)과 입금 대기(`UNPAID`)에서만 보인다.
- 주문 생성이 모자라서 거절되면 기존 주문 요약 화면(`Summary.jsx`)이 서버 문구를 그대로 보여 준다.

## 6. 한계 — 정직하게

- ~~목록·상세를 연 뒤에 생긴 예약은 화면에 반영되지 않는다~~ → §9: 상세는 돌아올 때·1분마다 다시 읽는다. 목록은 react-query 의
  창 포커스 재조회(staleTime 60초가 지난 뒤)로 따라온다.
- 목록 응답마다 DB 를 두 번 읽는다(인덱스 조회, 한 페이지 단위). 캐시는 상품 정보·가격을 위한 것으로 남고, 재고만 캐시를 건너뛴다.
- 상품 **상태**(`SOLD_OUT` 등)는 여전히 캐시의 값이다 — 상태는 관리자가 바꿀 때 캐시를 갱신하는 경로가 따로 있다.
- 앞선 주문의 예약을 새 주문에 넘기는 것은 **같은 고객** 기준이다. 새 주문이 다른 이유로 모자라 거절돼도 앞 주문의 예약은 이미
  풀렸다 — 앞 주문은 결제할 수 있지만 보장이 없다(결제 화면이 그렇게 말한다).
- **예약이 만료된 뒤의 결제는 여전히 된다.** 그때는 §7 의 부족 검출·자동 부분 환불로 떨어진다(무통장은 수동). 결제 화면이
  그렇게 말해 준다.
- **포트원 가상계좌는 발급 뒤에는 막을 수 없다.** 계좌는 PG 결제창 안에서 발급되고 우리는 그 뒤에 안다. §9 에서 결제창을 여는
  **순간** 예약을 새로 잡아 모자라면 창을 열지 않게 했다 — 남는 틈은 창을 연 뒤 발급까지의 몇 분(그 사이 예약은 잡혀 있다)과
  catalog 에 닿지 못한 경우(fail-open)뿐이다. 그래도 모자라면 입금 뒤 §7 경로로 "수동 환불 필요".
- **fail-open 동안 만들어진 주문은 예약이 없다.** `order_stock_hold_total{result="unavailable"}` 가 늘면 그 시간 동안의 초과 판매는
  §7 의 보상에 기대고 있다는 뜻이다.
- 장바구니 단계에서는 잡지 않는다. 장바구니는 오래 머물러 재고를 묶어 두는 비용이 크다 — 실무도 대개 결제 단계에서 잡는다.

## 7. 운영

| 설정 | 기본값 | 뜻 |
|---|---|---|
| `order.stock-hold.enabled` | `true` | `false` 면 예약하지 않는다 — 예전 동작(§7 의 보상만) |
| `order.stock-hold.checkout-minutes` | `30` | 결제 화면에서 잡아 두는 시간 |
| `catalog.stock-reservation.expiry-check-ms` | `60000` | 만료 잡 주기 |

- 지표: `order_stock_hold_total{result=held|short|unavailable|disabled}`. `short` 는 주문 단계에서 막은 초과 판매,
  `unavailable` 은 fail-open 으로 넘어간 주문.
- 지금 잡혀 있는 재고:
  ```sql
  select product_id, color_id, size, sum(qty)
    from product.stock_reservation
   where status = 'HELD' and expires_at > now()
   group by 1, 2, 3 order by 4 desc;
  ```
- 배포 순서: **catalog 먼저**(V14·V15 와 내부 API), 그다음 order(V22·V23). order 가 먼저 뜨면 예약 호출이 404 라 fail-open 으로 지나간다 —
  멈추지는 않지만 그 사이 주문은 예약이 없다.

## 9. 열어 둔 화면 · 결제창 직전 (후속)

| 무엇 | 전 | 후 |
|---|---|---|
| 상품 상세의 남은 수량 | 연 순간의 값 — 그 뒤 생긴 예약은 새로고침 전까지 안 보였다 | `useRevalidateWhileVisible` — 탭으로 돌아올 때와 보이는 동안 1분마다 loader 를 다시 돌린다(숨은 탭에서는 안 돈다) |
| 결제창을 열 때 | 주문 때 잡은 30분이 지났으면 예약 없이 결제창이 열렸다. 가상계좌는 **없는 물건에 계좌가 발급**될 수 있었다 | `POST /api/order/payment/stock-hold/{trackingId}` — 포트원(V1·V2)·Stripe 결제창을 열기 직전에 예약을 새로 30분 잡는다. 모자라면 400 "재고가 부족해 결제를 시작하지 못했습니다: …" 로 **창을 열지 않는다**. 잡으면 주문의 `stockHoldUntil` 을 새 값으로 — 화면의 안내도 바뀐다 |

- 결제 전(NOT_PROCESSED) 주문만, 🔒 주문자만(아니면 404). catalog 에 닿지 못하거나 네트워크 오류면 막지 않는다(fail-open — 주문
  생성과 같다).
- PayPal 은 버튼이 SDK 안에서 주문을 만들어 끼울 자리가 다르다 — 이번에는 넣지 않았다. 카드라 부족분 자동 환불이 받는다.
- 가상계좌 발급 웹훅의 연장(§4)은 그대로 — 발급되면 입금 기한까지 늘린다.

테스트: `CheckoutHoldRenewTest`(5 — 갱신·부족 거절 문구·남의 주문·결제 전 아님·fail-open), `Payment.test`(+3 — Stripe 부족이면
세션 없음, 네트워크 오류면 진행, 포트원 부족이면 스크립트도 안 불러옴), `useRevalidateWhileVisible.test`(4).

## 8. 가드(테스트)

| 테스트 | 고정하는 것 |
|---|---|
| `StockReservationServiceTest`(catalog, 12) | 전부 아니면 전무, 남의 살아 있는 예약만 뺀다(자기 주문 제외), 정해진 순서로 잠근다, 같은 주문의 재예약은 행을 고친다, 확정·해제는 HELD 만·여러 번 불러도 같다, 유효 시간 자르기 |
| `ProductUpdateMessageListenerTest`·`ProductServiceUnitTest.othersHoldsAreNotAvailable` | 차감 때 다른 주문의 예약은 남은 수에서 빠지고, 자기 예약은 같은 트랜잭션에서 확정 |
| `AdminWriteInterceptorTest` | `/api/internal/**` 는 읽기까지 내부·관리자만 |
| `OrderStockHoldTest`(6) | 결과 넷(HELD·SHORT·UNAVAILABLE·DISABLED), catalog 오류에서 fail-open, 해제 오류를 삼킨다 |
| `OrderCreateStockHoldTest`(4) | 모자라면 주문을 지우고 문구에 상품·옵션·남은 수, 잡으면 예약 시각을 적는다 |
| `BankTransferStockHoldTest`(4) | 입금 시작 때 연장·모자라면 거절, 취소·만료 때 해제 |
| `OrderSagaStockReleaseTest`(2) | 사가 롤백 때 커밋 뒤 해제 |
| `ProductControllerTest`(+3) | 상세 남은 수량 = 실물 − 예약·`onHold`, 캐시된 DTO 는 그대로, 실물보다 많이 잡혀도 0, 조회 실패면 실물 그대로·`onHold` 를 싣지 않음, 장바구니 배치는 한 번에 읽는다 |
| `StockAvailabilityTest`(5)·`StockOnCardsControllerTest`(2)·`ProductControllerTest.productDetailUsesLiveQuantity` | 캐시의 수량 대신 지금 수량 → 그 위에서 예약을 뺀다, 한 페이지·홈 전체를 한 번씩만 읽는다, 두 읽기가 따로 실패, 검색의 페이지 정보는 그대로·캐시된 결과는 그대로 |
| `ProductControllerTest.cartStock*`(2)·`CartSlice.test.js`(syncStock 3)·`CartPage.test.jsx`(5)·`util/stock.test.js`(장바구니 5) | 장바구니 재고 — 예약을 뺀 수량·상태, 없는 옵션은 빠짐, 형식 오류 400·읽기 실패 503, 들어오면 한 번에 묻고 갱신(실패면 그대로), 담은 수량·장바구니 상태는 그대로, 항목별 안내, 살 수 없는 항목이 있으면 넘어가지 않음 |
| `util/stock.test.js`(7)·`ProductCard.test.jsx`(+4) | 카드 배지 — 결제 중은 품절이 아니다, 품절·N개 남음(사이즈 합), 넉넉하면 없음, 수량을 모르면 말하지 않음 |
| `OrderCreateStockHoldTest.releasesEarlierHoldsBeforeHolding` | 새 주문을 잡기 **전에** 같은 고객의 앞선 결제 전 주문의 예약을 푼다 |
| `Infos.test.jsx`(5)·`pages/Product.test.jsx`(2) | 결제 중이면 안내·재입고 알림 권하지 않음, 진짜 품절이면 재입고 알림, 담기 순간 0개면 "결제 중"/"품절", loader 가 `onHold` 를 넘김(없으면 0) |
| `StockHoldNotice.test.jsx`(8) | 기한 전·후 문구, 기한이 지나는 순간 바뀜, 시간대 없는 서버 시각을 UTC 로 읽음, 예약 없음·결제 뒤에는 그리지 않음 |

## 10. 🔴 모든 주문이 "품절" 이었다 — 주문 품목의 상품 id 가 장바구니 **줄** id 였다 (2026-09-26)

**증상**: 상품 화면은 재고가 있다고 하는데 주문하면 `在庫が不足しているため注文できませんでした：Italian Olive Oil() 품절`.

**원인**: order-service 가 주문 품목을 만들 때 `.productId(Long.parseLong(p.getId()))` 였다. `CartProductDto.id` 는 **장바구니 줄 id**
(`cart_product_id`, TSID)다 — 그 DTO 의 주석에도 "상품 id 가 아니다" 라고 적혀 있고, 상품 id 는 `productId` 다. 줄 id 는 어느 상품도
가리키지 않으므로 catalog 는 SKU 를 찾지 못해 남은 수를 0 으로 답했다. 처음(2025-10)부터 그랬고, 이 값으로 하는 일이 **전부**
헛돌았다:

| 쓰는 곳 | 어떻게 틀렸나 |
|---|---|
| 재고 예약(이번 배포부터) | SKU 없음 → 0 → **모든 주문이 "품절"** 로 막힘 |
| 결제 뒤 차감(docs/53 §7) | 상품 없음 → 전량 부족 → **결제한 주문이 전부 자동 환불** |
| 결제 뒤 장바구니 비우기 | 상품 id 가 달라 줄을 못 찾음 → 주문한 상품이 장바구니에 남음 |
| 리뷰 "구매한 사람만"(docs/57) | 받은 상품 조회가 늘 비어 있음 → 리뷰 403 |

**고침**: `getProductId()` 를 쓴다(`OrderDataService.requireProductId` — 비어 있으면 주문을 만들지 않는다, 줄 id 로 대신하지 않는다).
`productId` 필드가 생기기 전에 Redis 에 들어간 장바구니 사본에는 값이 없으므로, `OrderService.fillMissingProductIds` 가 트랜잭션
밖에서 cart 에 줄 id 로 물어 채운다.

**가드**: `OrderProductIdTest`(3) · B-66(order·coupon·pay 에서 `CartProductDto` 의 `getId()` 를 `.productId(` 에 넣으면 실패 —
되돌려 실패하는 것을 확인).

**이미 만들어진 주문**: `order_product.product_id` 에 줄 id 가 들어 있다. 부족분 자동 환불(docs/53 §7)이 생긴 뒤 결제된 주문은
위 표대로 이미 전량 환불됐다(관리자 주문 화면의 부족 수량). 그 전에 결제된 주문은 재고가 차감되지 않은 채 남아 있다. 어느 쪽이든
그 품목들은 리뷰 대상이 되지 않는다.

## 11. 사이즈 없는 상품 — 이름이 빈 사이즈 행이 여럿 (2026-09-26)

식품·생활용품은 사이즈 행의 이름이 비어 있다. 시드의 SKU 509개 중 403개가 그렇고, 그중 315개는 **한 SKU 에 이름 빈 행이 2~4개**다
(가격·수량이 서로 다른 행). 옵션은 (상품, 색상, 사이즈 **이름**)으로 주고받으므로 이 행들은 서로 구별되지 않는다.

| 어디서 | 무엇이 틀렸나 | 고침 |
|---|---|---|
| catalog 사이즈 조회(`findBySkuSkuproductIdAndSize`) | "하나" 를 기대 → 이름 빈 행이 둘 이상이면 예외 → 재고 예약·결제 뒤 차감 실패 | `findFirst…OrderBySizeIdAsc` — 같은 이름이면 **첫 행**. 장바구니(`CartService.sizeIndexOf`)도 같은 이름의 첫 행을 쓴다 |
| `ProductSku.sizes`·`Product.skus` | 순서가 없었다(List 는 DB 가 준 대로, Set 은 해시 순) — 그런데 화면·장바구니는 **인덱스**(`?style=1&size=0`)로 주고받는다 | `@OrderBy`(size_id·skuproduct_id) |
| `ProductSku` equals/hashCode | 선언이 없어 `BaseEntity` 의 `@Data`(감사 필드·version)를 물려받았다 — 재고가 바뀌면 hashCode 가 바뀌어 Set 순서가 로드마다 달라지고, 감사 필드가 같은 두 SKU 는 하나로 합쳐질 수 있었다 | id 로만 같음, hashCode 상수 |
| 상품 화면 | 빈 동그라미가 줄지어 나오고, 고르지 않으면 "사이즈를 선택하세요" 로 담기가 막혔다. 다른 동그라미를 고르면 화면 가격과 장바구니 가격(첫 행)이 달랐다 | `sizeless` 면 사이즈 고르기를 그리지 않고 첫 행(0)을 쓴다 |
| 리뷰 | 사이즈가 필수(화면·서버)라 **리뷰를 쓸 수 없었다**. 카드에는 "사이즈:" 만, 걸러 보기에는 빈 항목. 착용감("정사이즈")도 올리브오일에 물었다 | 고를 사이즈가 없으면 사이즈·착용감을 묻지 않는다(서버: 사이즈가 비면 착용감도 빈 값 허용, 사이즈를 골랐으면 착용감 필수), 카드·걸러 보기에서 뺀다 |

같은 이름의 둘째 행부터는 이제 어디서도 고를 수 없다 — 원래도 장바구니가 첫 행으로 바꿔 담았으니 새로 잃는 것은 없다.
그 행들이 서로 다른 상품(용량 등)이라면 관리자 화면에서 이름을 붙여야 한다(예: `250ml`·`1L`).

**가드**: `ProductSkuIdentityTest`(4) · `StockReservationServiceTest.sizelessOption` · `ReviewServiceTest.sizelessProductCanBeReviewed`·`fitRequiredWhenSized` · `ReviewCard.test.jsx`(2) ·
`util/sizes.test.js`(3) · `Infos.test.jsx`(+4) · `Review.test.jsx`(+1) · `pages/Product.test.jsx`(+2).

**배포 뒤 한 번**: 상품 캐시(`product`·`products` 는 만료가 없다)에 옛 순서의 SKU 목록이 남아 있으면, 화면(캐시)과 장바구니 담기(새로
읽음)의 스타일 인덱스가 어긋날 수 있다. catalog 캐시를 비운다(값은 다시 채워진다):

```bash
kubectl -n redis exec deploy/redis -- sh -c 'for c in product products details_cache sizes_cache colors_cache search_cache products_by_category; do
  REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --scan --pattern "$c::*" | xargs -r -n 200 env REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli del >/dev/null; done; echo done'
```
