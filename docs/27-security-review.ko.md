# 27. 보안 리뷰 — 프론트엔드 · 백엔드

[日本語](27-security-review.md) | **한국어**

> 🕰️ **상태(2026-09-27) — 일부 낡음.** §14 "서비스 간 인증 없음" 은 mTLS 로 해결했습니다([docs/57 §16](57-remaining-priorities.ko.md)). 맨 앞 요약표는 발견 당시의 기록으로 둡니다. 최신 열린 항목은 [docs/54 §10](54-improvements-vs-practice.ko.md).

인증·인가, 시크릿 관리, 노출된 관리 엔드포인트, 주입, 속도 제한을 프론트엔드와 전 서비스에
대해 훑었다. **찾은 것 중 절반은 "권한 검사가 아예 없는" 종류**였다 — 잘못 구현된 게 아니라
구현되지 않은 것이다.

가장 중요한 결론부터: **이 서비스는 로그인만 하면 다른 사람의 주문·장바구니·쿠폰을 읽을 수
있었다.** 취약점 하나가 아니라 같은 실수가 네 서비스에 반복돼 있었고, 원인은 공통이다 —
**신원을 클라이언트가 보낸 값에서 읽었다.**

---

## 요약

| # | 위치 | 문제 | 심각도 | 상태 |
|---|---|---|---|---|
| 1 | order-service | 주문번호만 알면 **아무 주문이나** 조회 (소유권 검사 전무) | 🔴 Critical | 고침 |
| 2 | order-service | `?email=` 로 남의 주문 목록 조회 | 🔴 Critical | 고침 |
| 3 | cart-service | `?userId=` 로 남의 장바구니 조회 | 🔴 Critical | 고침 |
| 4 | coupon-service | `?email=` 로 남의 쿠폰 조회 | 🔴 High | 고침 |
| 5 | coupon-service | 쿠폰 삭제에 **권한 검사 없음** | 🔴 High | 고침 |
| 6 | delivery-service | 송장 등록에 **권한 검사 없음** (내가 만든 코드) | 🔴 High | 고침 |
| 7 | config-server | actuator `include: "*"` → `/env` 로 **시크릿 전체 노출** | 🔴 Critical | 고침 |
| 8 | gateway | actuator `gateway` 노출 → **라우트 조작 가능** | 🟠 High | 고침 |
| 9 | user-service | 이메일로 사용자 조회가 외부에 열림 (계정 열거) | 🟠 High | 고침 |
| 10 | gateway | 로그인 경로 속도 제한이 20 req/s (분당 1,200회 대입) | 🟠 High | 고침 |
| 11 | gateway | `User-Id` 헤더에 이메일을 담아 이름과 내용이 불일치 | 🟠 High | 고침 |
| 12 | frontend | JWT `role` 클레임을 `roles` 로 잘못 읽음 → 역할이 항상 빈 배열 | 🟠 High | 고침 |
| 12b | gateway | 신원 헤더를 외부 입력에서 지우지 않음 → 헤더 위조·속도제한 우회 | 🔴 Critical | 고침 |
| 13 | user-service | 로그인 실패 횟수를 세지 않음 (계정 잠금 미구현) | 🟠 | 고침 ([docs/54 §10.2](54-improvements-vs-practice.ko.md)) |
| 14 | 전반 | 서비스 간 호출에 인증 없음 (네트워크 격리에만 의존) | 🟠 | 고침 ([docs/57 §16](57-remaining-priorities.ko.md)) |

---

## 1~4. 🔴 신원을 클라이언트에게 물어봤다 (IDOR)

**같은 실수가 네 서비스에 있다.** 하나씩 다른 버그가 아니라 하나의 패턴이다.

### 무엇이 문제였나

```java
// order-service
@GetMapping("/{orderId}")
ResponseEntity<?> getOrder(@PathVariable String orderId, @RequestParam("email") String email)

@GetMapping("/orders")
ResponseEntity<?> getOrders(@RequestParam("filter") String filter, @RequestParam("email") String email)

// cart-service
@GetMapping("/loadcart")
ResponseEntity<?> loadCart(@RequestParam("userId") String userId, ...)

// coupon-service
@GetMapping("/coupons")
ResponseEntity<?> getNotUsedCoupons(@RequestParam("email") String userId, ...)
```

**"너 누구야?" 를 요청자에게 물어보고 그 대답을 믿었다.** 게이트웨이가 JWT 를 검증하므로
*로그인한 사람*인 것은 맞지만, 그 사람이 *누구인지*는 쿼리 파라미터에서 읽었다. 그래서
로그인한 아무나 이렇게 할 수 있었다:

```
GET /order-service/api/order/orders?email=victim@example.com
GET /cart-service/api/cart/loadcart?userId=victim@example.com
GET /coupon-service/api/coupon/coupons?email=victim@example.com
```

이메일 하나만 알면 그 사람의 주문 이력, 장바구니, 보유 쿠폰이 전부 나온다.

### 그런데 1번은 이메일조차 필요 없었다

`getOrder` 는 더 나쁘다. `email` 을 파라미터로 <b>받아 놓고 쓰지 않았다</b>:

```java
// OrderDataService — email 인자가 어디에도 쓰이지 않는다
public OrderDto getOrder(Long orderId, String email, String filter) {
    Order data = filter.isEmpty()
            ? orderRepository.findById(orderId).orElse(null)      // ← 주문번호만 본다
            : orderRepository.findByOrderIdAndOrderStatus(orderId, ...).orElse(null);

    if (data != null) {
        return OrderService.convertToOrderDto(data, ...);          // ← 그대로 반환
    }
    return null;
}
```

컨트롤러에도 검사가 없다. 따라서:

```
GET /order-service/api/order/1?email=아무거나
GET /order-service/api/order/2?email=아무거나
GET /order-service/api/order/3?email=아무거나
...
```

**주문 번호를 1부터 세면 전 고객의 주문이 순서대로 나온다.** 노출되는 내용은 주문 상품,
결제 금액, 결제 수단, 그리고 **배송 주소와 수령인 전화번호**다. 순차 증가하는 정수 id 라
열거 비용도 거의 없다.

### 어떻게 고쳤나

**신원의 출처를 하나로 못박았다: 게이트웨이가 검증한 토큰.**

게이트웨이가 JWT 를 검증한 뒤 신원을 헤더로 넣어 준다. 클라이언트가 같은 이름의 헤더를
보내도 게이트웨이가 **지운 다음** 자기 값을 넣으므로 사칭할 수 없다.

```java
// 게이트웨이 — 클라이언트가 보낸 값을 먼저 지운다
ServerHttpRequest.Builder mutated = request.mutate()
        .headers(h -> {
            h.remove("User-Id");
            h.remove("User-Email");
            h.remove("User-Role");     // 이걸 안 지우면 헤더 하나로 관리자가 된다
        });
```

```java
// order-service — 파라미터 대신 검증된 헤더
@GetMapping("/orders")
public ResponseEntity<?> getOrders(@RequestParam(required = false) String filter,
        @RequestHeader(value = "User-Email", required = false) String userEmail) {
    if (userEmail == null || userEmail.isBlank()) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)...;
    }
    return new ResponseEntity<>(orderService.getOrders(userEmail, filter), HttpStatus.OK);
}
```

`getOrder` 는 데이터 계층에서 소유권을 대조한다 — `OrderDto` 에는 소유자 필드가 없어 엔티티를
봐야 하기 때문이다:

```java
Long requesterId = getCustomerIdByEmail(email);
if (requesterId == null || !requesterId.equals(data.getCustomerId())) {
    log.warn("다른 고객의 주문 조회 시도를 차단: orderId={}", orderId);
    return null;
}
```

**403 이 아니라 404 로 답한다.** 403 은 "그 주문은 존재한다"를 알려주는 셈이라, 번호를 훑어
어떤 주문이 실재하는지 확인하는 것 자체는 막지 못한다.

`requesterId == null` 도 거부한다. 토큰은 유효하지만 주문 도메인에 고객 레코드가 없는
경우인데, null 을 "일치"로 처리하면 그 경로로 전부 열린다.

### 프론트엔드도 같이 바꿨다

```js
// 수정 전
export const getOrders = async (filter, userEmail) =>
  getRequest("/order-service/api/order/orders", { params: { filter, email: userEmail } });

// 수정 후 — 신원을 아예 보내지 않는다
export const getOrders = async (filter) =>
  getRequest("/order-service/api/order/orders", { params: { filter } });
```

서버가 이미 무시하므로 기능상 보낼 필요가 없다. 그래도 지운 이유는, 클라이언트가 계속
보내면 **"이 값이 신원을 정한다"는 오해를 남기고** 나중에 누군가 서버에서 다시 신뢰하도록
되돌릴 여지를 주기 때문이다.

### 회귀 테스트

이 종류의 결함은 **기능 테스트로는 절대 잡히지 않는다** — 자기 주문을 조회하는 정상 경로는
고치기 전에도 잘 동작했다. 그래서 "남의 것을 요청하면 거절한다"를 명시적으로 고정했다
(`OrderOwnershipTest`).

테스트가 진짜로 잡는지 확인하려고 **수정을 일시적으로 되돌려 보고** 실패를 확인했다:

```
OrderOwnershipTest > 남의 주문을 주문번호로 조회하면 아무것도 돌려주지 않는다 FAILED
OrderOwnershipTest > 존재하지 않는 사용자로 요청해도 주문이 새지 않는다 FAILED
3 tests completed, 2 failed
```

수정을 되돌린 뒤 통과해 버리는 테스트는 아무것도 지키지 못한다.

---

## 5~6. 🔴 권한 검사가 아예 없는 쓰기 엔드포인트

### coupon-service — 쿠폰 삭제

```java
@DeleteMapping("/delete")
ResponseEntity<?> deleteCoupon(@RequestParam("id") String id) {
    couponService.delete(id);      // 권한 검사도, 소유권 검사도 없다
```

로그인한 아무나 쿠폰 id 만 넣으면 지울 수 있었다. id 를 훑으면 발행된 프로모션 쿠폰을 통째로
지우는 것도 가능하다. 관리자 역할을 요구하도록 고쳤다.

### coupon-service — 쿠폰 발급·수정 (2026-09 발견, docs/54)

삭제만 고치고 **같은 컨트롤러의 발급(`POST /api/coupon/add`)·수정(`PUT /update`)은 열린 채였다.**
게이트웨이는 로그인만 확인하므로, 로그인한 아무나 **자기 id 로 할인율을 마음대로 정한 쿠폰**을 만들 수 있었다.
주문 총액은 서버가 그 쿠폰으로 계산하므로 결제 금액 대조(docs/35)도 통과한다 — 금액 검증이 막아 주지 못하는
종류다. 화면은 이 경로를 부르지 않는다(일반 사용자의 발급은 서버가 조건을 판정하는 `/events/{slug}/claim` 뿐).
둘 다 `InternalCallGuard.isAdmin(User-Role)` 로 막았다(수정은 지금 서비스가 아무것도 하지 않지만, 채워지는 순간
할인율을 바꾸는 경로다). 가드: `CouponAdminGuardTest`.

> 교훈: 한 엔드포인트의 권한 누락을 고칠 때 **같은 컨트롤러의 형제를 전부** 본다(§9b 와 같은 모양).

### delivery-service — 송장 등록 (내가 만든 코드다)

이건 **내가 이번 작업에서 작성한 코드**이고, 주석으로 문제를 인지해 놓고도 고치지 않은 채
남겨 뒀다:

```java
/**
 * ⚠️ 이 엔드포인트는 고객이 아니라 판매자/관리자용이다. 지금은 게이트웨이가
 * 인증만 확인하고 역할까지 구분하지 않으므로, 운영 전에 관리자 역할 검사를 넣어야 한다.
 */
@PostMapping("/order/{orderId}/tracking")
```

"나중에 넣어야 한다"고 적어 두는 것은 조치가 아니다. 그 사이 이 엔드포인트는 **로그인한
아무나** 남의 주문에 임의의 택배사·송장번호를 붙일 수 있는 상태였고, 결과는 가볍지 않다:

- 주문이 발송된 것으로 바뀌고(폴링 시작 → `DISPATCHED`)
- **주소 변경과 취소가 막힌다**(발송 전에만 가능하므로)
- 고객에게 오지 않을 물건의 배송 알림이 나간다

이제 게이트웨이가 넘겨준 `User-Role` 을 확인하고, **헤더가 없으면 거부**한다. 없을 때
통과시키면 게이트웨이를 우회한 요청이 그대로 들어온다.

> 참고: 같은 delivery-service 의 조회·주소변경·취소는 처음부터 `findByOrder(customerId, orderId)`
> 로 소유권을 확인하고 있었다. 송장 등록만 빠져 있었다.

---

## 7~8. 🔴 관리 엔드포인트가 열려 있다

### config-server — `include: "*"`

```yaml
management:
  endpoints:
    web:
      exposure:
        include: "*"        # 🔴
```

config-server 는 **모든 서비스의 설정을 쥐고 있는** 서비스다. 노출 범위가 가장 좁아야 하는데
정반대로 전부 열려 있었고, 인증도 없었다. 노출되는 것:

- `/actuator/env` — **DB 비밀번호, JWT 시크릿을 포함한 전체 환경변수**
- `/actuator/configprops` — 바인딩된 설정값 전체
- `/actuator/heapdump` — 힙 덤프(메모리 안의 평문 자격증명까지)

`health, info, prometheus` 만 남기고 `show-details: never` 를 걸었다.

> 이건 이번 작업 내내 신경 써 온 시크릿 관리(ops/01 비밀번호 회전, k8s Secret 분리,
> `.gitignore` 처리)를 **한 번에 무의미하게 만드는** 구멍이었다. 파일에서 시크릿을 빼내도
> HTTP 로 그대로 읽을 수 있으면 의미가 없다.

### gateway — `gateway`, `beans`

```yaml
include: gateway, refresh, health, beans, http, busrefresh, info, metrics, prometheus
```

`gateway` 액추에이터는 라우트 **조회뿐 아니라 POST 로 추가·삭제**가 된다. 인증 없이 열려
있으면 트래픽을 임의의 목적지로 돌릴 수 있다는 뜻이다. `beans` 는 내부 구조를 그대로
알려준다. 둘 다 제거했다.

`refresh`/`busrefresh` 는 설정 리로드를 트리거하는 쓰기 엔드포인트라 원래는 인증 뒤에
있어야 하지만, 운영 절차가 이것에 의존할 수 있어 이번에는 남기고 별도 과제로 뺐다.

---

## 9. 🟠 이메일로 사용자를 조회하는 내부 API 가 외부에 열림

```java
@GetMapping("/user/id")
public ResponseEntity<?> findUserByEmail(@RequestParam("email") String email)
```

서비스 간 Feign 호출용인데 게이트웨이 라우트(`/user-service/api/auth/**`)에 포함돼 **로그인한
아무나** 호출할 수 있었다. 이메일을 넣으면 userId·이름·닉네임·프로필 이미지가 나오므로,
이메일 목록만 있으면 가입 여부 확인(계정 열거)과 개인정보 수집이 그대로 가능했다.

**판정 방법:** 게이트웨이를 거친 요청에는 게이트웨이가 `User-Role` 을 붙인다(클라이언트가
보낸 동명 헤더는 지운다). 내부 Feign 호출은 게이트웨이를 거치지 않으므로 이 헤더가 없다.
따라서 *헤더가 있다 = 외부에서 온 요청*이고, 이 경우 관리자만 허용한다.

전체 이메일을 찍던 `log.info` 도 지웠다 — 개인정보다.

> ⚠️ **이 판정은 user-service 가 외부에서 직접 닿지 않는다는 전제 위에 있다.** k8s 에서는
> ClusterIP 라 기본적으로 성립하고(이 프로젝트의 모든 Feign 트래픽이 이미 같은 전제를 쓴다),
> 서비스 포트를 노출하는 순간 무너진다. 근본 해결은 §14 다.

### 9b. 🔴 같은 컨트롤러의 형제 엔드포인트 셋은 열린 채였다 (2026-09 발견)

§9 는 `/user/id` 하나만 막았다. 바로 옆의 **id·이름으로 찾는 조회 셋에는 가드가 없었다**:

| 엔드포인트 | 돌려주던 것 | 호출자 |
|---|---|---|
| `GET /api/auth/user/email/{userId}` | 이메일 | cart-service(내부) |
| `GET /api/auth/user/profile/{userId}` | 이름·이메일·이미지·역할·잠금 상태·가입일… | 없음 |
| `GET /api/auth/user/name?userName=` | id·이메일·닉네임·이미지 | order-service Feign(선언만) |

`/api/auth/user/**` 는 USER 역할만 요구하므로 **로그인한 아무나** id 를 바꿔 가며 남의 이메일과 프로필을
받아 갈 수 있었다 — OWASP API Top 10 의 1번, **BOLA/IDOR** 이다. id 가 TSID(시간 기반)라 무작위 대입보다
훨씬 좁은 범위만 훑으면 된다.

§9 의 판정(`User-Role` 이 있으면 외부)에도 구멍이 있었다: **role 클레임이 없는 토큰은 `User-Role` 없이
도착해 "내부 호출" 로 통과한다.** 다른 서비스는 이미 `InternalCallGuard.allowed(role, email, id)`(세 신원
헤더 중 하나라도 있으면 외부)로 옮겨 갔는데 user-service 만 옛 판정이었다.

**고친 것** — 다섯 엔드포인트 모두 `InternalCallGuard.allowed`. 막을 때 id·이름 조회는 **없는 사용자와 같은
404** 다(403 이면 "그 id 는 있다" 를 알려 준다). `/user/id`·`/user/segments` 는 기존 응답(403) 유지.
Feign 은 `Authorization` 만 전달하므로(각 서비스 `FeignConfig`) 내부 호출은 영향이 없다.
**가드**: `UserLookupGuardTest`(5건 — 남의 id 404·조회 자체를 안 함, 프로필·이름도, 내부 호출 통과, 관리자
통과, 역할 헤더 없이 User-Id 만 있어도 외부).

> **실무 기준과의 차이 — 아직 남은 것.** 실무에서는 내부 전용 API 를 **다른 포트/리스너**에 두거나 게이트웨이
> 라우트에서 아예 빼고(`/internal/**` 은 외부 라우팅 안 함), 서비스 간에는 mTLS·서비스 토큰으로 호출자를
> 증명한다. 여기의 헤더 판정은 "게이트웨이가 신원 헤더를 반드시 붙이고, 서비스 포트가 밖에 안 열린다" 는
> 두 전제 위에 있다(§14). 판정을 경로 규칙 한 곳으로 모으지 않은 것도 이번처럼 **형제 엔드포인트가 빠지는**
> 원인이다 — 다음 단계는 게이트웨이에서 `/api/auth/user/{email,profile,name,id,segments}` 를 외부 라우팅에서
> 제외하는 것이다.

---

## 10. 🟠 로그인 대입 공격이 사실상 무제한

게이트웨이의 속도 제한은 전역 기본 필터 하나뿐이었다:

```yaml
default-filters:
  - name: RequestRateLimiter
    args:
      redis-rate-limiter.replenishRate: 20      # 초당 20회
      redis-rate-limiter.burstCapacity: 40
```

일반 API 기준으로는 타당하지만 **로그인에는 지나치게 관대하다** — IP 하나로 분당 1,200회
비밀번호를 시도할 수 있다는 뜻이다. 그리고 계정 잠금이 구현돼 있지 않아(§13) 실질적으로
무제한 대입이었다.

인증 전 경로(`/api/auth/public/**` 등)에 훨씬 강한 제한을 별도로 걸었다:

```yaml
- name: RequestRateLimiter
  args:
    redis-rate-limiter.replenishRate: 1        # 초당 1회, 분당 60회
    redis-rate-limiter.burstCapacity: 10       # 순간 10회까지
```

이 경로는 인증 전이라 버킷 키가 클라이언트 IP 로 잡힌다. 정상 사용자는 로그인·회원가입을
초당 여러 번 하지 않으므로 충분하다.

### 곁가지로 발견: 속도 제한 우회가 가능했다

`CustomUserKeyResolver` 는 버킷 키를 `User-Id` 헤더 → 클라이언트 IP 순으로 정한다. 그런데
게이트웨이가 그 헤더를 **외부 입력에서 지우지 않았기 때문에**, 클라이언트가 매 요청마다 다른
`User-Id` 를 붙여 보내면 **매번 새 버킷**이 잡혀 속도 제한이 사실상 무력화됐다. §11 에서
추가한 전역 `RemoveRequestHeader` 가 이 경로를 닫는다.

> 🔍 **정직하게 적어 둘 것:** 속도 제한 필터는 `default-filters` 에 있고 `JwtAuthorization`
> 은 라우트 필터라, **속도 제한이 JWT 필터보다 먼저** 실행된다. 즉 제한 시점에는 아직
> `User-Id` 가 세팅되지 않았으므로 키는 **실질적으로 항상 클라이언트 IP** 다. "인증 사용자
> 단위 제한"이라는 리졸버의 주석과 실제 동작이 다르다. 지금은 안전한 쪽(IP 기준)으로 동작하고
> 우회도 막혔으므로 급하지 않지만, 사용자 단위 제한을 진짜로 원한다면 속도 제한 필터를
> 라우트 필터로 옮겨 JWT 필터 뒤에 두어야 한다. **이번에는 바꾸지 않았다** — 필터 순서
> 변경은 전 라우트의 트래픽 제어에 영향을 주므로 부하 테스트와 함께 해야 한다.

---

## 11. 🟠 `User-Id` 헤더에 이메일이 들어 있었다

게이트웨이가 하위 서비스로 넘기는 신원 헤더가 이랬다:

```java
String userId = extractUserIdFromJwt(jwt);     // 실제로는 getSubject() = 이메일
request.mutate().header("User-Id", userId)
```

**이름은 `User-Id` 인데 값은 이메일 문자열이다.** 그래서 이 헤더를 숫자 id 로 받는 서비스는
전부 깨졌다:

```java
// delivery-service, notification-service — 둘 다 내가 이번 작업에서 작성한 코드다
@RequestHeader(value = "User-Id", required = false) Long userId
```

Spring 은 `"user@example.com"` 을 `Long` 으로 변환하지 못해 **400 Bad Request** 를 돌려준다.
즉 배송 조회·주소 변경·취소·알림 목록이 **실제 게이트웨이를 거치면 전부 실패**한다. 단위
테스트는 숫자를 직접 넣어 통과했기 때문에 드러나지 않았다 — 컴포넌트 각각은 맞고 그 사이의
계약이 틀린, 통합 테스트가 없으면 잡히지 않는 종류다.

식별자로 이메일을 쓰는 것 자체도 문제다. **이메일은 바뀔 수 있는 값**이라 사용자가 이메일을
변경하면 과거 데이터와의 연결이 끊긴다. 소유권 판정의 기준은 불변 id 여야 한다.

### 고친 방법

JWT 에 불변 숫자 id 를 담는 `uid` 클레임을 추가하고, 게이트웨이가 셋으로 나눠 넘긴다.

```java
// JwtUtils
.subject(email)
.claim(UID_CLAIM, user.getUserId())     // 새로 추가
.claim("role", role)
```

| 헤더 | 내용 | 쓰는 곳 |
|---|---|---|
| `User-Id` | 불변 숫자 사용자 id | delivery-service, notification-service |
| `User-Email` | 이메일 | order/cart/coupon-service (기존 도메인이 이메일로 키를 잡는다) |
| `User-Role` | `ROLE_USER` / `ROLE_ADMIN` | 관리자 전용 엔드포인트 |

`uid` 가 없는 토큰(이 변경 이전 발급분, OAuth 경로)은 게이트웨이가 **거부**한다. 신원을 확인할
수 없는 요청을 통과시키는 것보다 재로그인을 요구하는 편이 안전하다.

> ⚠️ **배포 시 주의:** 이 변경은 기존에 발급된 액세스 토큰을 모두 무효화한다. 배포 직후
> 사용자는 한 번 다시 로그인해야 한다. 게이트웨이를 user-service 보다 **먼저** 배포하면
> 그 사이 발급되는 토큰에 `uid` 가 없어 전원이 로그인 불가가 되므로, **user-service 를 먼저**
> 배포해야 한다.

OAuth 로그인 경로(`OAuth2SuccessHandler`)도 함께 고쳤다. 그대로 뒀다면 소셜 로그인 사용자는
`uid` 없는 토큰을 받아 로그인 직후 모든 API 에서 401 을 맞았을 것이다.

---

## 12. 🟠 프론트엔드가 역할 클레임을 잘못 읽고 있었다

세 군데(`SignInPage`, `TokenRefresher` 의 초기 복구와 401 재발급)에 같은 코드가 복사돼 있었고,
셋 다 똑같이 틀려 있었다:

```js
roles: decodedToken.roles ? decodedToken.roles.split(",") : []
```

백엔드가 넣는 클레임은 `roles`(복수)가 아니라 **`role`(단수)** 이다. 따라서
`decodedToken.roles` 는 언제나 `undefined` 였고 **`user.roles` 는 항상 빈 배열**이었다.

지금까지는 역할을 실제로 검사하는 코드가 없어서 아무도 눈치채지 못했다. 그런데 docs/26 에서
**관리자 전용 라우트 가드를 추가하는 순간** 이 빈 배열 때문에 진짜 관리자까지 관리자 화면에서
쫓겨난다. 가드를 넣으면서 반드시 같이 고쳐야 하는 부분이었고, 이번 리뷰에서 잡았다.

`util/jwtUser.js` 헬퍼로 합쳤다. 단수/복수, 배열/쉼표 문자열, `ADMIN`/`ROLE_ADMIN` 을 모두
받아들인다 — 백엔드가 표기를 바꿔도 화면이 조용히 권한을 잃지 않도록.

---

## 12b. 🔴 2단계 인증을 서버가 강제하지 않았다 (2026-09 발견, docs/54)

두 경로 모두 우회할 수 있었다.

| 경로 | 예전 | 우회 |
|---|---|---|
| 비밀번호 로그인 | 2FA 계정에도 **액세스 토큰(응답 헤더)과 REFRESH 쿠키를 먼저** 내줬다. 코드를 기다린 것은 프론트뿐 | 비밀번호만 알면 응답 헤더의 토큰을 그대로 쓴다 |
| 소셜 로그인 | 세션 대신 5분짜리 보류 쿠키 — 그런데 값이 **액세스 토큰과 같은 키로 서명한 보통 JWT(uid 포함)** | 그 값을 `Authorization: Bearer` 로 보내면 게이트웨이가 통과시킨다 |
| 코드 확인 | 횟수 제한 없음 | 6자리 = 100만 가지, 5분 안에 훑을 수 있다(게이트웨이 속도 제한은 IP 당이라 분산하면 소용없다) |

고친 것:

- **`PendingTwoFactor`** — 대기 토큰을 **액세스 토큰 키에서 파생한 별도 키**(HMAC(secret, "2fa_pending"))로
  서명한다. 게이트웨이·chat·user-service 필터 등 액세스 토큰을 검증하는 모든 곳이 **고치지 않고도** 서명 불일치로
  거부한다. `uid` 도 없다. 세션을 등록하지 않는다.
- **비밀번호 로그인**(`AuthLoginFilter`) — 2FA 계정이면 토큰·REFRESH 없이 대기 쿠키만 심고
  `code=two_factor_required` 로 답한다. 기기 기록(새 기기 알림)은 한다 — 비밀번호가 새 기기에서 맞았다는 것
  자체가 알릴 일이다. **다중 로그인 차단(이전 세션 끊기)은 하지 않는다** — 비밀번호만으로 남의 세션을 끊게 된다.
- **코드 확인**(`POST /api/auth/public/verify-2fa`, 옛 이름 `/public/oauth2/verify-2fa` 도 받는다) — 틀리면 토큰당
  5번까지(Redis), 넘으면 그 토큰을 태운다. 통과하면 곧바로 태운다(1회용). 그다음 REFRESH 쿠키.
- **프론트**(`SignInPage`) — 토큰을 들고 있다가 `login()` 하던 경로를 없앴다. 비밀번호·소셜 모두 코드만 보내고,
  통과하면 전체 리로드로 REFRESH 쿠키에서 세션을 복구한다(가려던 곳 `redirectTo` 는 유지).

가드: `TwoFactorLoginTest`(7) — 대기 토큰이 액세스 키로 검증되지 **않는다**, 액세스 토큰은 대기 토큰으로 안 받는다,
5회 제한, 1회용, 2FA 계정의 로그인 응답에 토큰·REFRESH 가 없다. `OAuth2SuccessHandlerTest` 는 대기 쿠키 값이
액세스 토큰 생성기에서 나오지 않음을 고정한다. 프론트 `SignInPage.test`.

> 실무 기준: 2FA 는 "두 번째 요소를 통과하기 전에는 **어떤 자격증명도 발급하지 않는다**" 가 전부다. 화면이 기다려
> 주는 것은 보안이 아니라 UX 다. 대기 상태의 토큰은 용도를 분리(별도 키·audience)해 다른 검증기가 절대 받지 않게
> 하고, 두 번째 요소는 시도 횟수를 제한한다(NIST SP 800-63B §5.2.2).

## 확인했고 문제가 없던 것

리뷰했지만 이상이 없어 손대지 않은 것들도 적어 둔다. "안 봤다"와 "봤는데 괜찮다"는 다르다.

- **SQL 인젝션** — 전 서비스가 Spring Data JPA 의 파라미터 바인딩을 쓴다. 문자열을 이어 붙여
  쿼리를 만드는 곳은 없었다.
- **XSS** — 프론트엔드에 `dangerouslySetInnerHTML` 사용처가 하나도 없다. React 의 기본
  이스케이프에 의존하고 있고, 그것으로 충분하다.
- **액세스 토큰 저장 위치** — localStorage 가 아니라 메모리(AuthContext)에만 둔다. 리프레시
  토큰은 HttpOnly 쿠키다. XSS 로 토큰을 훔치기 어려운 올바른 구조이고, `util/tokenUtil.js` 에
  "여기에 토큰 저장 메서드를 두지 말 것"이라는 의도가 명시돼 있다.
- **CORS** — 게이트웨이에서 `allowedOrigins` 를 명시 목록으로 두고 있다. 와일드카드 +
  `allow-credentials: true` 조합(브라우저가 거부하고, 설정에 따라 위험한 조합)은 없었다.
- **JWT 시크릿** — 코드에 하드코딩돼 있지 않고 `${JWT_SECRET_KEY}` 환경변수로 주입된다.
- **비밀번호 저장** — 해시를 쓴다. 평문 저장은 없었다.
- **STOMP 디버그 로그** — 운영에서 인증 헤더가 포함된 프레임을 콘솔에 남기지 않도록 이미
  분기돼 있다.

---

## 남은 과제 (이번에 고치지 않은 것)

### 13. ✅ 계정 잠금이 구현돼 있지 않다 — **해결됨**(`03422d0`: 5회 실패 → 15분 잠금, 자동 해제, `LoginAttemptService`)

> 아래는 발견 당시의 기록이다.

`User` 엔티티에 `accountNonLocked` 필드가 있고 `UserDetailsImpl.isAccountNonLocked()` 도 그
값을 읽는다. 그런데 **로그인 실패 횟수를 세거나 잠금을 거는 코드가 어디에도 없다.** 필드만
있고 그것을 `false` 로 만드는 주체가 없으므로 항상 `true` 다.

속도 제한(§10)으로 대입 속도는 크게 줄였지만, 분산된 IP 에서 오는 공격은 여전히 막지 못한다.
실패 횟수 카운터(Redis)와 임계치 초과 시 일시 잠금이 필요하다. 백엔드 로직 추가가 필요해
이번 범위에서는 하지 않았다.

### 14. ✅ 서비스 간 호출에 인증이 없다 — **해결됨**(서비스 간 mTLS: 내부 CA 클라이언트 인증서로 내부 호출을 판정, `InternalCallerFilter` — [docs/57 §16](57-remaining-priorities.ko.md))

> 아래는 발견 당시의 기록이다.

모든 Feign 호출이 "내부 네트워크에서만 닿는다"는 전제에 의존한다. §9 의 수정도 그 전제 위에
서 있다. 클러스터 안에 침투당하면(다른 파드 하나만 장악해도) 모든 내부 API 가 무방비다.

제대로 하려면 서비스 메시(mTLS) 또는 서비스 간 토큰이 필요하다. 인프라 결정이 걸려 있어
(docs/23 의 인프라 선택과 함께) 별도 과제로 남긴다.

### 15. 여전히 유효한 이전 과제

- ~~**DB 비밀번호 회전(`ops/01`)**~~ ✅ **종결(2026-09-25)** — 유출된 값(커넥터 JSON 의 `product` 암호)은
  운영 PostgreSQL·Redis 모두 거절하고 어느 Secret 에도 없음을 **로그인해 보는 방식**으로 확인했다(대조군·양성
  대조 포함). 운영은 배포 때 새로 만든 암호를 쓰고 있었다. 이력 스캐너(`ops/20`)가 JSON 형식을 놓쳐 "0건" 이라
  보고하던 것도 고쳤다(docs/30 §6).
- **방화벽 점검(`ops/11`)** 🔴 — 노출됐던 호스트가 git 히스토리에 남아 있고 되돌릴 수 없다.
- **actuator 인증** — 이번에 노출 범위를 줄였지만, 남긴 엔드포인트(`refresh`, `busrefresh`,
  `prometheus`)도 인증 뒤에 두는 것이 맞다.

---

## 배포 순서 (중요)

§11 의 변경 때문에 순서가 있다.

1. **user-service 먼저** — `uid` 클레임을 넣는 토큰을 발급하기 시작한다.
2. **그다음 gateway** — `uid` 없는 토큰을 거부하기 시작한다.
3. 나머지 서비스(order/cart/coupon/delivery).

**순서를 뒤집으면** 게이트웨이가 먼저 `uid` 를 요구하는데 user-service 는 아직 넣지 않으므로
**모든 사용자가 로그인 불가**가 된다.

배포 후 기존 액세스 토큰은 전부 무효다 — 사용자는 한 번 다시 로그인해야 한다. 짧은 로그아웃을
감수하는 대신, 신원을 확인할 수 없는 토큰이 계속 통과하는 상황을 없앤다.

---

## 검증 범위

- 백엔드 전체 테스트 통과 (`OrderOwnershipTest` 3건 신규 포함)
- `OrderOwnershipTest` 는 **수정을 되돌리면 실패**하는 것을 확인했다(2 failed)
- 프론트엔드 빌드 통과

⚠️ **실제 요청으로 확인하지는 않았다.** 게이트웨이를 띄우고 다른 사용자의 토큰으로 §1~4 를
실제로 시도해 보는 검증이 남아 있다. 특히 §11 의 헤더 계약 변경은 서비스를 모두 띄워
end-to-end 로 한 번 확인해야 한다 — 이번 버그 자체가 "단위 테스트는 통과하는데 실제 게이트웨이를
거치면 깨지는" 종류였기 때문이다.

---

## 부록. 이 리뷰가 놓친 것 — 결제 검증 (docs/35)

이 문서는 IDOR·권한·시크릿 노출을 훑었지만 **결제 경로의 신뢰 경계는 보지 않았다.**
나중에 별건(PayPal 시크릿 폐기)을 확인하다 발견했다:

> `POST /api/order/payment/paypal` 이 브라우저가 보낸 금액·결제 상태·PayPal 주문 ID 를
> **아무 검증 없이** 그대로 아웃박스에 넣고 있었다. 로그인만 되어 있으면 결제 없이
> 주문을 완료시킬 수 있었다.

소유권 검사로는 막히지 않는다 — **자기 주문에 대고 쏘는 공격**이기 때문이다. 이 리뷰가
"남의 것에 손이 닿는가"만 물었고 "자기 것에 대해 거짓말할 수 있는가"는 묻지 않은 결과다.

수정과 회귀 테스트는 [docs/35](35-payment-verification.ko.md).


---

## 부록 2. 소셜 로그인의 PKCE 와 인가 요청 저장소

이 리뷰(와 소셜 로그인 구현) 이후에 나온 질문 하나에서 시작했다 — **"OAuth 로그인은
PKCE 도 적용돼 있나?"**. 답은 **아니오**였고, 확인하는 과정에서 그보다 더 급한 결함이
하나 더 나왔다.

### 왜 PKCE 가 빠져 있었나 — "요즘 기본이겠지"의 함정

`oauth2Login` 을 쓰면 최신 스프링이 알아서 해 줄 것 같지만 그렇지 않다.
`DefaultOAuth2AuthorizationRequestResolver` 는 PKCE 를 **공개 클라이언트에만** 붙인다.
Spring Security 6.3.1 바이트코드로 확인한 분기:

```
54: getstatic     ClientAuthenticationMethod.NONE
58: invokevirtual ClientRegistration.getClientAuthenticationMethod
61: invokevirtual ClientAuthenticationMethod.equals
64: ifeq  76                     ← NONE 이 아니면 PKCE 를 건너뛴다
67: getstatic     DEFAULT_PKCE_APPLIER
```

`SocialClientRegistration` 은 셋 다 클라이언트 시크릿을 쓰는 **기밀 클라이언트**다
(`CommonOAuth2Provider` 가 구글·깃허브는 `CLIENT_SECRET_BASIC`, 페이스북은
`CLIENT_SECRET_POST` 로 지정한다). 그래서 저 분기에 한 번도 걸리지 않았다.

기밀 클라이언트라 PKCE 없이도 시크릿과 `state` 로 막히는 부분이 크다. 그래서 이건
🔴 가 아니라 🟡 다. 다만 OAuth 2.1 과 현행 Security BCP 는 기밀 클라이언트에도 PKCE 를
권고한다 — **인가 코드 주입**(빼돌린 코드를 공격자가 자기 세션에 밀어 넣는 공격)이 한 겹
더 막히고, 리다이렉트 URI 설정 실수나 프로바이더 쪽 오픈 리다이렉트로 코드가 새는 경우의
피해가 줄어든다. 세 프로바이더 모두 지원하므로 켜지 않을 이유가 없다.

### 확인하다 나온 더 급한 것 — 인가 요청이 HTTP 세션에 있었다

소셜 로그인은 요청 두 개로 이루어진다.

1. `/oauth2/authorization/google` — 서버가 `state`(PKCE 를 켜면 `code_verifier` 도)를
   만들어 **저장**하고 프로바이더로 보낸다
2. `/login/oauth2/code/google` — 프로바이더가 돌려보내면 서버가 그 값을 **꺼내** 검증한다

기본 저장소(`HttpSessionOAuth2AuthorizationRequestRepository`)는 그 사이 값을 HTTP 세션에
넣는다. 그런데 이 서비스는:

- **레플리카가 2개다** (`k8s/70-autoscaling.yaml` 의 minReplicas=2). 세션 복제도 없고
  세션 어피니티도 없다. ①을 받은 파드와 ②를 받은 파드가 다르면 세션에 아무것도 없다.
- **`SessionCreationPolicy.STATELESS` 를 선언해 놓았다.** JWT 로 가겠다고 해 놓고 소셜
  로그인 경로에서만 서블릿 세션이 생기고 있었다 — 선언과 실제가 어긋난 상태.

증상은 `authorization_request_not_found` 로 로그인 실패인데, **확률이 대략 절반이고
재시도하면 되는 경우가 있어** "가끔 안 되네"로만 보인다. 진단하기 가장 나쁜 형태다.

> 이 결함은 PKCE 와 무관하게 **이미 있었다.** 다만 PKCE 를 켜면 세션에 담기는 것이
> `state` 하나에서 `code_verifier` 까지로 늘어나 실패 표면이 넓어진다. 그래서 저장소를
> 먼저 정리하고 PKCE 를 켰다.

### 무엇을 했나

**① PKCE 를 명시적으로 켠다** — `SecurityConfig.pkceAuthorizationRequestResolver()`

```java
DefaultOAuth2AuthorizationRequestResolver resolver = new DefaultOAuth2AuthorizationRequestResolver(
        clientRegistrationRepository, DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
```

**② 인가 요청을 Redis 에 둔다** — `RedisOAuth2AuthorizationRequestRepository`

`state` 를 키로, TTL 10분(기본값), 꺼낼 때는 **`GETDEL`**. Redis 는 이미 리프레시 토큰에
쓰고 있어 새 인프라가 늘지 않는다.

**왜 쿠키가 아닌가.** 흔한 대안은 인가 요청을 통째로 쿠키에 담는 것이다. 동작은 하지만
PKCE 를 켠 지금은 **`code_verifier` 가 브라우저로 나간다.** PKCE 의 전제가 "verifier 를
아는 쪽만 코드를 교환할 수 있다"인데 그 값을 클라이언트에 맡기면 보호의 상당 부분이
사라진다(HttpOnly 라도 XSS·확장프로그램의 사정권이다). 시크릿을 지킬 수 있는 기밀
클라이언트이므로 verifier 도 서버에 둔다.

**왜 `GETDEL` 인가.** GET 후 DELETE 로 나누면 두 콜백이 동시에 도착할 때 둘 다 인가
요청을 얻는 창이 생긴다. 짧지만 실재하고, 그게 정확히 코드 재사용 공격이 노리는 창이다.

**③ 요청 캐시를 끈다** — STATELESS 를 선언했는데 세션을 만드는 곳이 하나 더 남아 있었다.
미인증 요청이 오면 `ExceptionTranslationFilter` 가 엔트리포인트를 부르기 **전에**
`HttpSessionRequestCache` 로 원 요청을 세션에 저장한다. 즉 **401 을 낼 때마다 세션이
생겼다.** 그런데 아무도 그걸 읽지 않는다 — 로그인 성공 경로는 JWT 를 발급하고
`OAuth2SuccessHandler` 는 저장된 요청이 아니라 고정된 프론트엔드 주소로 보낸다.
읽히지 않는 상태는 비용만 있다.

### 검증

| 무엇을 | 어떻게 | 결과 |
| --- | --- | --- |
| 커스터마이저를 걸면 PKCE 가 붙는다 | 리졸버를 실제로 실행해 `code_challenge`/`code_verifier` 확인 | ✅ |
| **걸지 않으면 안 붙는다**(개편 전 상태) | 같은 리졸버, 커스터마이저 없이 | ✅ 없음을 확인 |
| `code_verifier` 가 직렬화 왕복에서 살아남는다 | 진짜 저장/복원 코드 경로(매퍼 포함) | ✅ |
| 그 테스트가 실제로 잡는가 | **매퍼를 일부러 망가뜨려 재실행** | ✅ 왕복 테스트 2건만 정확히 실패 |
| 파드가 달라도 이어받는다 | 저장소 인스턴스 2개 + 실제 Redis 7 | 🕐 `containers` 태그 — CI 에서 |
| `GETDEL` 재사용 차단 · TTL · 만료 | 실제 Redis 7 | 🕐 `containers` 태그 — CI 에서 |

Docker 가 없는 환경에서 작업해 `containers` 태그 테스트는 **이 자리에서 돌리지 못했다.**
파이프라인의 통합 스테이지가 돌린다(docs/36). 가장 위험한 부분(직렬화)은 컨테이너 없이
검증되도록 따로 떼어 두었고, 그건 통과했다.

### 남은 것

- **실제 프로바이더와의 종단 확인.** 구글·깃허브·페이스북에 실제로 붙어 로그인이
  끝까지 되는지는 배포 후에만 알 수 있다. `code_challenge` 를 보내기 시작했으므로
  토큰 교환에서 `invalid_grant` 가 나면 이 변경을 먼저 의심할 것.
- **Redis 가 죽으면 소셜 로그인이 안 된다.** 의도한 동작이다(fail closed) — 저장에
  실패하면 로그인을 시작하지 않고 죽는다. 조용히 세션으로 폴백하면 지금 고친 레플리카
  문제가 보이지 않게 되돌아온다.
