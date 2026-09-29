# 47. 주문 생성 400 — 실패가 보이지 않는다

[日本語](47-order-create-failure-visibility.md) | **한국어**

> 🕰️ **상태(2026-09-27) — 이전 기록.** 고객 백필과 `User-Email` 헤더는 이후 없앴습니다([docs/48](48-user-replication-debt.ko.md)).

`POST /api/order/create` 가 400 을 돌려줬다. order-service 로그를 그 시각으로 열어 보면
**그 시각의 줄이 한 줄도 없다.** 카프카 리밸런스와 eureka 하트비트뿐이다.

로그가 없는 것이 이 문서의 주제다. **원인을 못 찾은 것이 아니라, 찾을 수 없게 돼
있었다.**

---

## 1. 왜 아무것도 안 남았나

```java
@PostMapping("/create")
ResponseEntity<?> createOrder(@RequestBody OrderRequest request, @RequestHeader("Authorization") String token) {
    try {
        Order newOrder = orderService.createOrder(request, token);
        String orderID = Long.toString(newOrder.getOrderId());
        ...
    } catch (RuntimeException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new MessageResponse(e.getMessage()));
    }
}
```

**모든 `RuntimeException` 을 잡아 로그 없이 400 으로 바꾼다.** cart-service 호출이
실패해도, 파싱이 깨져도, 널이 나와도 전부 같은 400 이고 로그는 없다.

## 2. 게다가 서비스가 `null` 을 돌려줬다

`createOrder` 에는 `return null` 이 세 갈래 있었다.

| 갈래 | 뜻 | 그때 남던 로그 |
|---|---|---|
| 장바구니가 비었다 | 사용자가 고칠 수 있음 | `장바구니가 비어 있어…` (warn) |
| order DB 에 고객이 없다 | **운영 문제** | `고객을 찾지 못해…` (warn) |
| 배송지가 없다 | 사용자가 고칠 수 있음 | (없음 — NPE 로 죽었다) |

컨트롤러는 그 `null` 에 대고 `newOrder.getOrderId()` 를 부른다. **NPE** 가 나고, 그것이
위 `catch` 에 걸려 **본문이 JVM 의 NPE 문구인 400** 이 된다:

```
Cannot invoke "com.project.order_service.model.Order.getOrderId()" because "newOrder" is null
```

프론트는 `error.response.data.message` 를 그대로 화면에 보여 준다 — **사용자가 이 문장을
읽는다.** 원인 셋이 하나의 알 수 없는 400 으로 뭉개졌고, 내부 구현까지 샜다.

> `warn` 은 있었지만 그것만으로는 부족했다. 이번처럼 **NPE 가 그 앞에서 나면**(배송지
> 없음) 아무것도 안 남고, 남았더라도 "이 400 이 그 warn 인가" 를 이어 붙일 근거가 없다.

## 3. 세 번째 문제 — 전부 400 이었다

cart-service 가 죽어도, JSON 이 깨져도 "네 요청이 잘못됐다" 가 된다. 사용자는 고칠 것이
없는데 고치라는 말을 듣고, 운영자는 4xx 지표만 보고 "클라이언트 문제" 로 분류한다.

## 4. 고친 방식

**실패를 두 종류로 가른다.**

| | 상태 | 본문 | 로그 |
|---|---|---|---|
| `DomainException` (사용자가 고칠 수 있음) | 400 | 그 문구(이미 사용자를 향해 쓰여 있다) | `warn` |
| 그 밖의 `RuntimeException` | **500** | 일반 문구 | **`error` + 스택트레이스** |

예외 메시지를 그대로 내보내지 않는 이유는 그것이 대개 내부 사정이기 때문이다.

그리고 `null` 반환을 없앴다. 각 갈래가 **이유를 달고** 던진다.

| 갈래 | 사용자에게 | 로그 |
|---|---|---|
| 장바구니 비었음 | "장바구니가 비어 있습니다. 상품을 담은 뒤…" | `주문 생성 실패: 장바구니가 비어 있다` |
| 고객 없음 | "주문자 정보를 찾지 못했습니다…" | `주문 생성 실패: order DB 에 고객이 없다 — user-created 이벤트가 처리됐는지 확인할 것` |
| 배송지 없음 | "배송지를 선택해 주세요." | `주문 생성 실패: 배송지가 없다` |

배송지 검사를 **먼저** 하는 이유: 그것이 없으면 그 아래 아홉 줄이 전부 NPE 다.

## 5. "고객 없음" 은 왜 일어나는가 — 미리 적어 둔다

order-service 의 `orders.customer` 는 **`user-created` 이벤트로 채워진다**
(`CustomerMessageListener`). 그 이벤트는 **회원가입 시점이 아니라 메일 인증 시점**에
발행된다(`UserServiceImpl.registerConfirmed`).

그래서 이런 계정이 생길 수 있다:

- 인증 메일이 나가지 않던 기간에 가입한 계정(docs/29·38 참조) — 나중에 인증을 마쳐도,
  그때 order-service 가 떠 있지 않았거나 컨슈머가 밀려 있었으면 행이 없다.
- 관리자가 DB 에 직접 만든 계정 — 이벤트를 아무도 발행하지 않는다.

증상은 **로그인은 되는데 주문만 안 되는 것**이다. 확인:

```bash
# order DB 에 그 고객이 있나
kubectl -n ecommerce exec -it deploy/postgres -- \
  psql -U postgres -d orderdb -c \
  "select customer_id, email from orders.customer where email = '<이메일>';"

# 컨슈머가 밀려 있나
kubectl -n ecommerce logs deploy/order-service | grep -i "Customer is created\|user-created"
```

### ✅ 실제로 그랬다

확인 결과 `orders.customer` 에 해당 계정의 행이 **없었다**. 그리고 order-service 기동
로그가 그 이유를 말해 준다:

```
Setting offset for partition user-created-0 to the committed offset FetchPosition{offset=0 ...}
리밸런스 — 그룹 'user-1' 이 파티션 [user-created-0] 을 받았다. 받을 때 lag={user-created-0=0}
```

**오프셋 0 · lag 0 = 그 파티션에 메시지가 하나도 없다.** 컨슈머가 놓친 것이 아니라
**발행된 적이 없다.**

### 왜 발행이 안 됐는지도 모른다 — 그게 두 번째 문제

```java
public void publish(UserCreatedRequest req) {
    kafkaTemplate.send("user-created", String.valueOf(req.getUserId()), req);
}
```

`send` 는 **비동기**다. future 를 돌려주고 즉시 끝난다. 직렬화 실패든, 브로커 거절이든,
타임아웃이든 — **그 future 를 아무도 보지 않으므로 로그 한 줄 남지 않는다.**

이 문서의 주제가 여기서 반복된다. **실패가 보이지 않는 곳이 두 군데 겹치면, 증상과
원인 사이의 거리가 그만큼 멀어진다.** 사용자가 본 것은 "주문 버튼을 누르면 400", 원인은
"몇 주 전 메일 인증 때 카프카 발행이 조용히 실패했다" 였다.

## 5-1. 고친 방식 — 알리는 쪽과 견디는 쪽

| | |
|---|---|
| **user-service** | `send(...).whenComplete(...)` 로 결과를 본다. 실패는 `error` + userId. **예외는 던지지 않는다** — 메일 인증은 이미 끝났고, 여기서 실패했다고 사용자에게 "인증 실패"를 보여 줄 수는 없다 |
| **order-service** | 주문 시점에 행이 없으면 **user-service 에 물어 스스로 만든다**(`OrderService.resolveCustomerId` → `backfillCustomer`) |

이벤트를 고치는 것과 **별개로** 백필을 둔 이유: 이벤트는 빠른 길일 뿐이고, **놓친 것을
영원히 못 고치는 구조는 그 자체가 결함**이다. 이미 그런 상태가 된 계정들은 이벤트를
고쳐도 살아나지 않는다.

> 🧩 **다만 이 백필은 복구 밸브지 설계가 아니다.** 같은 이벤트를 chat·coupon 도 받고,
> 놓쳤을 때 서로 다르게 망가진다(쿠폰은 못 받았다는 사실조차 알 수 없다). order 는
> 애초에 이 복제가 필요 없다 — 필요한 id 가 이미 토큰에 있다. 정리는
> **[docs/48](48-user-replication-debt.ko.md)** 에 있고, 구조 개선은 아직 하지 않았다.

> ⚠️ 백필은 **원격 호출**이라 반드시 트랜잭션 밖이다(docs/37 #5). 그래서
> `OrderDataService.createOrder` 는 customerId 를 **인자로 받는다** — 스스로 찾으면
> 언젠가 그 자리에서 user-service 를 부르고 싶어진다. 가드는
> `OrderTransactionBoundaryTest.thePersistStepTakesTheCustomerId` 이며, `OrderDataService`
> 에 `UserServiceClient` 필드가 **없다는 것**까지 고정한다.

경합(그 사이에 이벤트가 도착)은 `DataIntegrityViolationException` 을 잡아 다시 읽는다 —
실패가 아니라 이기고 지는 문제이고, 원하는 결과(행이 있다)는 이미 이뤄졌다.

### 계정을 지우고 다시 만들면 되지 않나

**권하지 않는다.** 발행이 한 번도 성공한 적이 없다면(토픽이 비어 있다) 새 계정도 같은
자리에 떨어진다 — 주문·장바구니·쿠폰만 잃고 문제는 그대로다. 발행 로그가 생긴 뒤
(위 수정) 재가입이 실제로 무엇을 만드는지 볼 수 있게 되고, 그전에는 백필이 더 확실하다.

## 5-2. 이 400 이 왜 하필 `{"message": null}` 이었나

두 곳에서 메시지가 사라지고 있었다.

```java
@AllArgsConstructor
public class CustomException extends RuntimeException {
    private final ErrorCode errorCode;
}
```

Lombok 의 `@AllArgsConstructor` 는 필드만 대입하고 **`super(message)` 를 부르지 않는다.**
`ErrorCode.USER_NOT_FOUND` 에 "해당 회원 정보를 찾을 수 없습니다." 가 들어 있어도 예외의
`getMessage()` 는 **언제나 `null`** 이었다. 그 값을 그대로 응답 본문에 넣으니
`{"message": null}` 이 된다. → `detail` 을 `super` 로 올렸다.

```java
case 400: break;
...
return null;     // FeignErrorDecoder
```

Feign 은 이 반환값을 `throw errorDecoder.decode(...)` 로 그대로 던진다. 즉 **`throw null`**
이고, 어느 서비스가 몇 번으로 답했는지는 그 순간 사라진다. → 모르는 상태는
`ErrorDecoder.Default` 에 맡겨 `FeignException`(상태·요청·응답 본문 포함)을 돌려준다.
`default` 갈래의 `new Exception(...)` 도 없앴다 — **검사 예외**라 Feign 이 다시 감싸
원인이 한 겹 더 묻혔다.

## 5-3. ✅ 실제 원인 — 장바구니 조회가 성립한 적이 없었다

가시화가 들어가자 첫 재시도에서 바로 나왔다.

```
WARN  FeignErrorDecoder - 서비스 간 호출 실패: CartServiceClient#getCart(String,String) -> 401
ERROR OrderController - 주문 생성 실패(예상치 못한 오류)
feign.FeignException$Unauthorized: [401] during [GET] to
  [https://cart-service…/api/cart/checkout?userId=…&Authorization=Bearer%20eyJ…]
  : [{"message":"로그인이 필요합니다."}]
```

cart-service 의 `/api/cart/checkout` 은 보안 강화 때 **쿼리 파라미터를 버리고 게이트웨이가
검증한 헤더를 읽도록** 바뀌었다:

```java
ResponseEntity<?> getCart(@RequestHeader(value = "User-Email", required = false) String userEmail,
                          @RequestHeader("Authorization") String token) {
    if (userEmail == null || userEmail.isBlank()) return 401;
```

order-service 의 Feign 클라이언트는 그대로였다:

```java
ResponseEntity<?> getCart(@RequestParam("userId") String email,
                          @RequestParam("Authorization") String token);
```

`User-Email` 헤더가 없으니 **언제나 401**. 즉 이 배포 이후 **주문은 한 번도 되지 않았다.**

### 왜 이렇게 오래 안 보였나

- **프론트 경로는 멀쩡했다.** 결제 화면이 장바구니를 읽는 호출은 게이트웨이를 지나며
  헤더가 붙는다. 깨진 것은 **서비스 간 직접 호출뿐**이라, cart-service 로그에는 정상
  호출만 보이고 실패한 호출은 컨트롤러 본문에 들어가기도 전에 끝나 흔적이 없었다.
- **컴파일러가 잡을 수 없다.** 양쪽이 다른 모듈이고 계약이 애노테이션에만 있다.
- 그리고 §1~§5-2 의 가림막 셋이 그 위에 겹쳐 있었다.

가드는 `FeignContractTest` — Feign 클라이언트의 애노테이션을 리플렉션으로 본다. 옛
시그니처로 되돌려 실패하는 것을 확인했다.

## 5-4. 🔒 액세스 토큰이 URL 에 실리고 있었다

위 예외 메시지를 다시 보면 **토큰 전문이 쿼리 문자열에 들어 있다.**

```
?userId=…&Authorization=Bearer%20eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI…
```

`UserServiceClient` 의 네 메서드와 `CartServiceClient.getCart` 가 전부
`@RequestParam("Authorization")` 이었다. **URL 은 어디에나 남는다** — 액세스 로그, 프록시,
예외 메시지, Referer. 실제로 운영 로그에 유효한 액세스 토큰이 평문으로 찍혔다.

게다가 **필요도 없었다.** `FeignConfig` 의 인터셉터가 들어온 요청의 `Authorization`
**헤더**를 그대로 이어 붙인다. 받는 쪽인 `/api/auth/user/id` 는 그 파라미터를 읽지도
않는다. 그래서 파라미터를 지웠다 — 헤더로 **다시 선언하지는 않는다.** 인터셉터와 겹쳐
같은 헤더가 두 번 간다.

> ⚠️ `User-Role` 은 인터셉터가 전달하지 않는다. `/api/auth/user/id` 는 그 헤더가 **있으면**
> 외부 호출로 보고 관리자만 통과시키므로, 서비스 간 호출에 실으면 403 이 된다. 이것도
> `FeignContractTest` 가 고정한다.

## 5-5. 🔒 주문자를 요청 본문에서 읽고 있었다

`createOrder` 는 `request.getEmail()` — **요청 본문** — 을 장바구니 조회·쿠폰 견적·주문자
조회에 모두 썼다. 남의 이메일을 넣어 보내면 **그 사람의 장바구니로 주문이 만들어지고
배송지는 내 것**이 된다.

이제 게이트웨이가 검증한 `User-Email` 헤더에서만 읽는다. 이 저장소의 다른 엔드포인트들이
쿼리 파라미터 `email` 을 버린 것과 같은 이유다(`getOrder`·`getOrders` 주석).

## 6. 다음에 이 400 을 만나면

이제 order-service 로그에 반드시 한 줄이 남는다.

```bash
kubectl -n ecommerce logs deploy/order-service --since=10m | grep "주문 생성"
```

- `주문 생성 거절: …` → 400. 그 문구가 사용자가 본 것과 같다.
- `주문 생성 실패(예상치 못한 오류)` + 스택트레이스 → 500. 우리 잘못이다.
- **아무것도 없다** → 요청이 order-service 에 닿지 않았다. 게이트웨이(라우트·JWT 필터·
  서킷브레이커 폴백)를 본다.

마지막 줄이 중요하다. 고치기 전에는 세 경우가 구분되지 않았다.

## 검증

```
:order-service:test  통과
:user-service:test   통과
```
