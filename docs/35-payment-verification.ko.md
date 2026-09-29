# 35. 결제를 서버가 확인하지 않고 있었다

[日本語](35-payment-verification.md) | **한국어**

> 🕰️ **상태(2026-09-27) — 일부 낡음.** `paypal_order_id` 대신 `(payment_provider, provider_payment_id)` 유니크(V18)가 재사용을 막고, 청구 통화는 주문에 저장합니다. 결제수단 확장은 [docs/49](49-payment-providers.ko.md).

## 어떻게 발견했나

PayPal 계정이 잠겨 시크릿을 폐기하지 못한다는 이야기에서 시작했다. "그럼 그 시크릿이
정확히 어디에 쓰이는가"를 확인하러 코드를 뒤졌는데, **아무 데도 쓰이지 않았다.**

그건 좋은 소식이 아니었다.

---

## 1. 무엇이 문제였나

```java
// order-service/controller/OrderController.java
@PostMapping("/payment/paypal")
public ResponseEntity<?> persistPaypalPayment(@RequestBody PaypalPaymentRequest request, ...) {
    orderService.persisitPaypalPayment(request, token);   // 그대로 저장
```

```java
// order-service/service/OrderDataService.java  (고치기 전)
Order order = getOrderByTrackingId(request.getTrackingId());
if (order != null) {
    PaymentRequest payload = PaymentRequest.builder()
            .amounts(request.getAmounts())                              // ← 클라이언트가 정한 금액
            .paypalOrderId(request.getPaypalOrderId())                  // ← 검증 없음
            .paymentStatus(PaymentStatus.valueOf(request.getPaymentStatus()))  // ← 클라이언트가 정한 상태
            .build();
    paymentOutboxHelper.savePaymentOutbox(payload, OutboxStatus.STARTED);
}
```

서버는 이 중 **무엇도 확인하지 않았다.** 금액도, 결제 상태도, PayPal 주문 ID 의 존재 여부도.
즉 결제하지 않고 이 한 줄로 주문을 완료시킬 수 있었다:

```bash
curl -X POST https://shop.example.com/api/order/payment/paypal \
  -H 'Authorization: Bearer <자기 토큰>' -H 'Content-Type: application/json' \
  -d '{"trackingId":"<자기 주문>","paypalOrderId":"아무거나","amounts":0.01,
       "paymentStatus":"COMPLETED","orderStatus":"NOT_PROCESSED"}'
```

로그인만 되어 있으면 된다. 자기 주문에 대고 쏘는 것이므로 IDOR 도 아니고, 소유권 검사를
아무리 잘해도 막히지 않는다.

### 왜 아무도 못 봤나

**브라우저에서 결제가 정말로 일어나기 때문이다.** PayPal JS SDK 가 결제창을 띄우고,
사용자가 승인하고, `actions.order.capture()` 가 성공하고, 그 결과를 서버로 보낸다.
화면상으로는 완벽하게 동작한다. docs/26 §1 이 이 경로의 버그(capture 2회 호출)를 잡았을 때도
**흐름 자체는 의심하지 않았다.**

놓친 것은 이것이다:

> 브라우저에서 결제가 일어났다는 사실은 **서버 입장에서 증거가 아니다.**

브라우저는 신뢰 경계 밖이다. SDK 가 돌려준 `details.id` 는 서버가 보기엔 그냥 문자열이고,
그 문자열이 어떻게 만들어졌는지 서버는 알 수 없다. 정직한 사용자에게는 진짜 PayPal 주문
ID 가 오고, 그렇지 않은 사용자에게는 무엇이든 온다.

### 시크릿이 없던 이유

이 저장소에 PayPal 시크릿이 없었던 것은 보안이 좋아서가 아니라 **서버가 PayPal 에 아무것도
묻지 않았기** 때문이다. 물어보려면 시크릿이 필요한데, 묻지 않으니 필요가 없었다.

(git 이력에 있던 `REACT_APP_PAYPAL_CLIENT_SECRET` 은 값이 `REACT_APP_PAYPAL_CLIENT_ID` 와
**완전히 같았다** — 둘 다 `A` 로 시작하는 80자다. PayPal 클라이언트 ID 는 `A`, 시크릿은 `E`
로 시작한다. 시크릿 변수에 클라이언트 ID 를 붙여넣은 것이고, 클라이언트 ID 는 공개 값이다.
docs/30 이 이걸 "PayPal 시크릿 🔴" 으로 분류한 것은 **변수 이름만 보고 판단한 오류**다.)

---

## 2. 무엇을 고쳤나

서버가 PayPal 에 직접 묻는다. 네 가지를 확인한다.

| | 확인 | 막는 공격 |
|---|---|---|
| ① | PayPal Orders API 로 조회 → `status == COMPLETED` | 지어낸 주문 ID · 승인만 되고 캡처 안 된 결제 |
| ② | 금액을 **DB 의 주문 총액**과 대조 | 0.01 결제 후 59.98 주문에 붙이기 |
| ③ | 통화 대조 | 59.98 USD 를 59.98 KRW 로 바꿔치기 |
| ④ | 그 PayPal 주문이 이미 쓰였는지 **DB 유니크 제약**으로 | 한 번 결제하고 열 주문에 재사용 |

②가 핵심이다. 요청 본문의 `amounts` 는 **더 이상 쓰지 않는다.** 하류로 넘기는 페이로드도
서버가 확인한 값으로 다시 만든다 — `paymentStatus` 도 `orderStatus` 도 클라이언트가 정하지
않는다.

### ④를 DB 제약으로 한 이유

애플리케이션에서 "이미 쓰였나" 조회한 뒤 저장하면, 동시에 들어온 요청 둘이 **함께 통과한다.**
조회 시점에는 둘 다 비어 있기 때문이다. 부분 유니크 인덱스는 그 경쟁 자체를 없앤다.

```sql
-- V3__order_paypal_order_id.sql
ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS paypal_order_id varchar(255);

CREATE UNIQUE INDEX IF NOT EXISTS uk_order_paypal_order_id
    ON orders.orders (paypal_order_id)
    WHERE paypal_order_id IS NOT NULL;   -- 미결제 주문(NULL)은 인덱스에 들어가지 않는다
```

저장할 때 `saveAndFlush` 로 **즉시** 제약을 확인한다. 커밋까지 미루면 아웃박스에 결제가
이미 들어간 뒤에 터진다.

### ①~③이 없어도 ④만으로는 부족하고, 그 반대도 마찬가지다

- ④만 있으면: 지어낸 ID 도 "처음 쓰는 값"이라 통과한다
- ①~③만 있으면: 진짜 결제 하나를 같은 금액의 여러 주문에 재사용할 수 있다

넷이 함께 있어야 의미가 있다.

---

## 3. 기본값을 어느 쪽으로 두었나

```yaml
paypal:
  verification:
    mode: ${PAYPAL_VERIFICATION_MODE:http}   # 기본값이 '켜짐'
```

자격증명이 없으면 **order-service 가 기동하지 않는다.**

```
IllegalStateException: PayPal 결제 검증에 자격증명이 필요합니다.
  PAYPAL_CLIENT_ID / PAYPAL_CLIENT_SECRET 를 채우거나,
  검증 없이 돌리려면 PAYPAL_VERIFICATION_MODE=disabled 를 명시하세요.
```

이것이 의도다. 결제 검증은 **"설정을 깜빡하면 조용히 꺼지는"** 종류가 되면 안 된다.
검증 없이 돌리려면 `disabled` 를 명시적으로 적어야 하고, 그러면 기동할 때마다 경고가 찍힌다.

### 메일·SMS 와 방향이 반대인 이유

docs/29 에서 메일·SMS 는 `logging` 이 기본값이다. 여기서는 반대다. 모순이 아니라
**안전한 방향이 서로 다르기** 때문이다:

| | 실수로 하면 위험한 것 | 그래서 기본값 |
|---|---|---|
| 메일·SMS | **보내는 것** (되돌릴 수 없다) | 안 보냄 (`logging`) |
| 결제 검증 | **통과시키는 것** (돈이 나간다) | 검증함 (`http`) |

둘 다 "실수했을 때 덜 나쁜 쪽"이라는 같은 규칙에서 나온다.

### 확인 불가는 거부한다 (fail closed)

PayPal 에 닿지 못했을 때(네트워크·토큰 발급 실패) "일단 통과"시키고 싶은 유혹이 있다.
그러면 **PayPal 을 잠깐 막는 것만으로 검증 전체가 무력화된다.** 확인되지 않으면 거부한다.

읽기 타임아웃을 5초로 짧게 잡은 것도 같은 판단이다. 거부돼도 돈은 PayPal 에 그대로 있고
사용자는 다시 시도할 수 있다 — 사용자가 빈 화면을 30초 보는 것보다 낫다.

---

## 4. 확인한 것

**단위 (10개, `PaypalPaymentVerificationTest`)** — 각 공격이 실제로 막히는지, 그리고
**막힐 때 아웃박스에 아무것도 들어가지 않는지**를 본다. 예외를 던지더라도 그 전에 아웃박스에
넣었다면 결제는 이미 하류로 전파된 뒤다.

| | |
|---|---|
| ✅ | 지어낸 주문 ID → 거부, 아웃박스 발행 없음 |
| ✅ | `APPROVED`(캡처 전) → 거부 |
| ✅ | 실제 1원 결제를 5998 주문에 → 거부 |
| ✅ | 통화 불일치(KRW) → 거부 |
| ✅ | 다른 주문에 쓴 결제 재사용 → 거부 |
| ✅ | 존재하지 않는 주문 → 거부 (예전에는 **조용히 200**이었다) |
| ✅ | 페이로드 금액이 요청 본문이 아니라 PayPal 확인값 |
| ✅ | 같은 주문 · 같은 결제 재전송(새로고침) → 조용히 무시, 이중 발행 없음 |

**통합** — `order-service:test -PwithContainers` 19개 통과. Flyway V3 적용과 Hibernate
`validate` 가 새 컬럼을 받아들이는 것까지 실제 PostgreSQL 로 확인했다.

**전체** — `test -PwithContainers` **268 tests, 실패 0** (e2e 사가 전 구간 포함).

### 확인하지 못한 것

- **PayPal 에 실제로 붙여 보지 않았다.** 계정이 잠겨 있다. `HttpPaypalOrderVerifier` 의
  OAuth2 토큰 발급과 Orders API 조회는 **문서 기준으로 작성했고 실호출은 미검증**이다.
  응답 JSON 의 경로(`purchase_units[0].amount.value`)가 어긋날 가능성이 남아 있다
- 부분 캡처(partial capture)는 다루지 않는다. 이 저장소는 전액 캡처만 쓴다
- 환불·분쟁(refund/dispute) 웹훅은 여전히 없다. 결제 **이후** 상태 변화는 반영되지 않는다

계정이 풀리면 sandbox 로 §4 의 첫 항목부터 실제로 확인할 것. 그때까지는
`PAYPAL_VERIFICATION_MODE=disabled` 로 두고 배포해도 되지만, **그건 이 문서가 설명한
결함이 그대로 살아 있다는 뜻**이다.

---

## 5. 남는 위험

| | |
|---|---|
| 환불·분쟁 | PayPal 에서 환불해도 주문 상태는 그대로다. 웹훅이 필요하다 |
| 부분 캡처 | 전액 캡처만 가정한다 |
| 통화 하드코딩 | `paypal.currency` 하나뿐이다. 다통화 상점이 되면 주문마다 통화를 저장해야 한다 |
| pay-service | 여전히 order-service 가 넘긴 값을 믿는다. 검증은 경계(order-service)에서 한 번만 한다 — 내부 서비스 간에는 그게 맞지만, 그 경계가 뚫리면 하류는 막지 못한다 |

## 6. 샌드박스로 테스트하기 (한국에서 결제가 안 될 때)

PayPal 은 한국 개인계정의 수취를 제한한다. 그래서 **라이브 자격증명으로는 국내에서 결제
테스트가 막힌다** — 결제 화면까지는 가는데 거기서 끝난다.

**샌드박스는 국가 제한이 없다.** 가짜 구매자 계정으로 승인→캡처를 그대로 태울 수 있고
돈은 움직이지 않는다. 결제 이후 과정(결제 아웃박스 → `payment` 토픽 → pay-service →
주문 상태 → 배송)을 실제 경로로 확인하려면 이쪽이 맞다.

### 🔴 맞춰야 하는 값이 셋이고, 하나만 빠져도 조용히 어긋난다

| | 어디 | 언제 박히나 |
|---|---|---|
| `PAYPAL_ENV` | configmap | 컨테이너 시작 |
| `PAYPAL_CLIENT_ID` / `SECRET` | Secret | 컨테이너 시작 |
| **`REACT_APP_PAYPAL_CLIENT_ID`** | 프론트 **빌드 인자** | **이미지 빌드 시점** (B-51) |

세 번째가 함정이다. 서버 설정만 바꾸고 프론트를 다시 빌드하지 않으면 **브라우저는 여전히
예전 환경의 PayPal** 을 띄운다. 기동은 성공하고, 증상은 결제 화면에서야 나온다.

`paypal.env` 스위치가 서버 쪽 두 값이 어긋나는 것은 막지만(기동 실패), **브라우저에 박힌
값은 서버가 알 수 없다.** 그래서 양쪽이 각각 client id 앞 6자리를 찍는다 — 그것으로 대조한다.

```bash
# 서버가 무엇으로 떴나
kubectl -n ecommerce logs deploy/order-service | grep "PayPal 결제 검증 활성화"
#   → env=sandbox endpoint=https://api-m.sandbox.paypal.com clientId=AXxxxx…(80자)

# 프론트는 무엇으로 빌드됐나 — ops/19 가 빌드할 때 같은 형식으로 찍는다
#   → PayPal client id: AXxxxx… (80자)
```

**두 줄의 앞 6자리가 다르면 그게 원인이다.**

### 순서

1. developer.paypal.com → Testing Tools → Sandbox Accounts 에서 **business**(판매자)와
   **personal**(구매자) 계정을 만든다. 국가는 US, 통화 USD — 프론트가
   `currency: "USD"` 로 고정돼 있다(App.jsx `initialOptions`).
2. Apps & Credentials → **Sandbox** 탭 → 앱 생성 → client id / secret.
3. Secret 의 `PAYPAL_CLIENT_ID` / `PAYPAL_CLIENT_SECRET` 을 그 값으로.
   `PAYPAL_ENV` 는 `sandbox` 그대로(기본값).
4. **프론트 재빌드** — 이 단계를 빼먹으면 아무것도 바뀌지 않는다:
   ```bash
   REACT_APP_PAYPAL_CLIENT_ID=<같은 sandbox client id> ops/19-build-images.sh ...
   ```
5. 결제 화면에서 **sandbox personal 계정**으로 로그인해 결제.

### ⚠️ `PAYPAL_VERIFICATION_MODE=disabled` 로는 결제 창을 건너뛸 수 없다

그것은 **서버 검증만** 끈다. 프론트의 `onApprove` 는 여전히 `actions.order.capture()` 가
성공해야 발화하므로, PayPal 창 자체는 그대로 필요하다. 결제 창 없이 뒷단만 확인하려면
별도의 개발 전용 경로가 있어야 하고, 지금 저장소에는 **없다**(의도적으로 만들지 않았다 —
결제를 우회하는 코드는 한번 들어오면 운영까지 따라간다).

### 라이브로 전환할 때

`PAYPAL_ENV=live` 로 바꾸고, Secret 과 프론트 빌드 인자를 **모두 live 값으로** 교체한다.
order-service 기동 로그에 경고가 한 줄 찍힌다 — 그 줄이 보이면 실제 돈이 오간다는 뜻이다.
