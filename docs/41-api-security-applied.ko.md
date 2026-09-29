# 41. API 보안 점검 — www-security 데모를 이 시스템에 대조하다

[日本語](41-api-security-applied.md) | **한국어**

> 🕰️ **상태(2026-09-27) — 일부 낡음.** 쿠폰 · 장바구니 신원은 `User-Id` 헤더, 주문 금액은 서버가 정하고(86010e1), 서비스 간 mTLS 는 적용됐습니다.

`ruke79/www-security`(『API Security in Action』 챕터별 데모 + 취약점 랩 17종)의
체크리스트를 ecommerce-msa 에 하나씩 대조했다. 데모의 결론은 두 줄이다:

> 인증(누구인가)만으로는 부족하고, **인가(무엇을 할 수 있나)** 가 진짜 방어선이다.
> **클라이언트가 고를 수 있는 값**은 신원도, 키도, 금액도 될 수 없다.

이 문서는 "데모의 항목 → 이 시스템의 상태 → 한 일"이다. 고친 것은 둘, 조정한 것은 하나,
이미 맞게 돼 있던 것은 그대로 적어 다시 의심하지 않게 했다.

---

## 고친 것

### 시나리오 1 — Rate Limit 우회 (클라이언트가 버킷 키를 고른다)

**데모**: `RateLimitingFilter` 가 `X-Forwarded-For` 를 그대로 키로 써서, 헤더를 요청마다
바꾸면 제한이 없는 것과 같다.

**이 시스템** (`gateway-server/.../resolver/CustomUserKeyResolver.java`) — 같은 함정이
**두 군데** 있었다:

| 읽던 것 | 왜 조작 가능한가 |
|---|---|
| `X-User-Id` 폴백 | 게이트웨이가 넣는 이름은 `User-Id` 다. `X-User-Id` 는 **아무도 넣지 않으므로** 그 값은 언제나 클라이언트가 보낸 것이다. 비로그인 요청에 값을 바꿔 가며 붙이면 요청마다 새 버킷 |
| `X-Forwarded-For` 첫 항목 | 프록시는 이 헤더에 **덧붙이지** 지우지 않는다. 첫 항목은 클라이언트가 처음 쓴 값 |

더 나쁜 것은 기존 테스트 `CustomUserKeyResolverTest` 가 **정확히 이 동작을 고정**하고
있었다는 점이다("X-User-Id 가 있으면 user: 키를 쓴다", "XFF 첫 IP 로 폴백한다"). 우회
경로를 회귀 테스트가 보호하고 있었다.

**한 일**:
- 키 우선순위를 `User-Id`(게이트웨이가 검증 뒤 넣음) → `CF-Connecting-IP`(Cloudflare
  엣지가 실제 접속 IP 로 **덮어씀** — 클라이언트가 같은 이름을 보내도 교체된다) → 소켓
  원격 주소로 바꿨다. `X-User-Id`·`X-Forwarded-For` 는 읽지 않는다.
- `IdentityHeaderScrubFilter` 가 `X-User-Id/Email/Role` 변형도 지운다. 신원처럼 보이는
  이름은 들어오는 족족 지워 두면, 나중에 누가 어느 이름으로 읽든 클라이언트 값은 도달하지
  않는다.
- 테스트를 뒤집었다: 스푸핑된 `X-User-Id` 와 XFF 는 **무시되어야** 통과한다.

### 시나리오 10 — IDOR / BOLA (남의 쿠폰을 적용한다)

**데모**: `/api/lab/idor/orders/{id}` 가 소유권을 보지 않아 `?asUser=alice` 로 bob 의
주문을 연다. OWASP API 보안 1위.

**이 시스템** (`coupon-service/.../CouponService.applyCoupon`) — 요청 **본문의**
`userEmail` 로 사용자를 찾고 있었다:

```java
userServiceClient.findUserByEmail(request.getUserEmail(), token)   // 본문 값
Coupon coupon = getCouponNotUsed(request.getCouponName(), response.getUserId());
couponUseProducer.publish(CouponUseRequest.builder().userId(response.getUserId()) ...);
```

로그인만 돼 있으면 본문에 남의 이메일을 넣어:
1. 그 사람이 그 이름의 **미사용 쿠폰을 가졌는지** 알아내고(존재 여부 누출),
2. 그 할인율로 계산된 `totalAfterDiscount` 를 받고,
3. **그 사람 id 로 쿠폰 사용 이벤트**를 발행시킬 수 있었다.

같은 컨트롤러의 `/coupons` 는 이미 `User-Email` 헤더를 쓰고 있었다(docs/27 에서 고쳤다).
이 엔드포인트 하나만 빠져 있었다 — "한 곳만 막으면 새 엔드포인트에서 빠진다"는 데모
부록 A.3 의 지적 그대로다.

**한 일**:
- 컨트롤러가 `@RequestHeader("User-Email")` 로 신원을 받고, 없으면 401.
- 서비스는 본문의 `userEmail` 을 **읽지 않는다.** 필드는 옛 클라이언트 호환을 위해 남기되
  `@Deprecated` 로 표시했다. 본문과 헤더가 다르면 경고 로그(값은 남기지 않는다).
- 프론트엔드(`util/api.js`, `Summary.jsx`)는 이메일을 더 이상 보내지 않는다.
- `CouponApplyOwnershipTest`: 본문에 남의 이메일을 넣어도 조회는 헤더의 이메일로만 나가야
  한다. 컨트롤러 시그니처도 고정한다 — `(request, token)` 두 개짜리로 돌아가면 실패.

⚠️ 이 결함은 docs/40 의 10번("금액을 클라이언트가 정한다")과 **연쇄**한다. 남의 쿠폰으로
계산된 금액을 받은 뒤 그 `total` 을 주문 생성에 그대로 보내면, 서버가 금액을 재계산하지
않는 한 할인이 그대로 먹는다. 10번은 아직 열려 있다(order-service 확인 필요).

---

## 조정한 것

### 챕터 9 — 토큰 폐기 (Introspection / Revocation)

**데모의 교훈**: 자체 인코딩(JWT) 토큰은 로컬 검증만으로는 폐기가 반영되지 않는다 →
**수명을 짧게 + refresh 로 보완**.

**이 시스템**: 게이트웨이는 서명만 검증한다(introspection 없음). `RedisSessionManager` 는
이름과 달리 인메모리 `HashMap` 이고, 그것을 읽는 `JwtUtils.isRequestAuthorized` 는
**호출되는 곳이 없다.** 즉 로그아웃해도, 계정이 탈취돼도, 발급된 액세스 토큰은 만료까지
유효하다. 그 만료가 **2시간**이었다.

**한 일**: 액세스 토큰을 15분으로 줄였다(`user-service/application-local.yml`). 사용자
체감은 없다 — `TokenRefresher` 가 만료 10초 전에 리프레시 쿠키(14일)로 조용히 갱신하고,
리프레시 토큰은 서버가 관리하므로 로그아웃 시 진짜로 무효화된다. 도난당한 액세스 토큰의
유효 창이 2시간에서 15분으로 준다.

남은 것: 진짜 폐기가 필요하면 `RedisSessionManager` 를 실제 Redis 로 옮기고 게이트웨이가
그것을 본다. 지금은 15분이 그 값어치를 대부분 한다.

---

## 이미 맞게 돼 있던 것 (확인했음)

| 데모 항목 | 이 시스템 | 근거 |
|---|---|---|
| #15 JWT `alg:none` / 약한 키 | 방어됨 | jjwt 0.12.6 `Jwts.parser().verifyWith(key())` — 서명 없는 JWS 와 `none` 을 거부한다. 키는 Secret 의 `JWT_SECRET_KEY` |
| #10 IDOR — 주문 조회 | 방어됨 | `OrderDataService.getOrder` 가 `customerId` 를 대조해 남의 것이면 **null → 404** (존재 여부도 안 샌다). `OrderOwnershipTest` 있음 |
| #10 IDOR — 배송 조회/변경/취소 | 방어됨 | `DeliveryController` 가 `User-Id` 로 `findByOrder(userId, orderId)` — 쿼리에 소유자 조건(데모 부록 A.3 의 권장 패턴 1) |
| #10 IDOR — 장바구니 | 방어됨 | `CartController` 가 `User-Email` 헤더만 쓴다(docs/27) |
| #10 IDOR — 주소 선택/삭제 | 방어됨 | `@AuthenticationPrincipal` 의 username 으로 서비스 호출 |
| #11 Mass Assignment | 해당 없음 | 엔티티를 `@RequestBody` 로 직접 받는 컨트롤러가 없다. 전부 DTO |
| #9 XSS | 방어됨 | React 가 이스케이프한다. `dangerouslySetInnerHTML` 사용처 없음. 공지 본문은 일반 텍스트로 그린다 |
| 신원 헤더 위조 (User-Role: ROLE_ADMIN) | 방어됨 | `IdentityHeaderScrubFilter`(전역, HIGHEST_PRECEDENCE) + B-53 (docs/39 §7, af776f9) |
| #12 SSRF, #13 Path Traversal, #14 Command Injection, #16 SSTI, #17 NoSQL | 해당 없음 | URL 을 받아 가져오는 기능·셸 호출·템플릿 평가·Mongo 쿼리 조립이 없다. chat-service 의 Mongo 는 Spring Data 메서드만 쓴다 |
| ch4 mTLS | 부분 | 서비스 간 TLS 는 내부 CA 로 되어 있으나 **클라이언트 인증(mTLS)** 은 아니다. 게이트웨이가 신원을 헤더로 넘기는 구조라, 하위 서비스는 "게이트웨이에서 왔다"를 네트워크에 의존한다. 서비스 메시(Istio 등) 없이 손으로 붙이기엔 크다 — 미룬다 |
| ch8 DPoP | 해당 없음 | 핀테크급 요구가 아니다. 15분 수명 + httpOnly 리프레시로 충분 |

---

## 데모의 3부 "CI 보안 회귀 테스트"를 이 시스템에서 하는 법

데모는 "안전 엔드포인트는 공격 페이로드를 차단해야 한다"를 자동 테스트로 만들라고 한다.
이 저장소에서 그 역할을 하는 것들:

| 공격 | 테스트 |
|---|---|
| 신원 헤더 위조 | `ConfigurationGuardrailTest` B-53 |
| 레이트리미터 키 스푸핑 | `CustomUserKeyResolverTest` (이 문서) |
| 남의 쿠폰 적용 | `CouponApplyOwnershipTest` (이 문서) |
| 남의 주문 조회 | `OrderOwnershipTest` |
| 액세스 토큰을 로그에 찍기 | `ConfigurationGuardrailTest` B-52 |

새 엔드포인트가 사용자 소유 자원을 다루면 **같은 모양의 테스트를 하나 추가**한다 —
"본문/쿼리에 남의 식별자를 넣어도 검증된 신원으로만 조회한다". 컨트롤러 한 곳에서 막는
것으로는 부족하다는 것이 이번에 다시 드러났다(`/coupons` 는 막았는데 `/applycoupon` 은
빠졌다).
