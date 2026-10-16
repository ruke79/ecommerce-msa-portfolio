# 38. 전체 코드 리뷰 — 백엔드 12모듈 · 프론트엔드 · CDC

[日本語](38-code-review.md) | **한국어**

> 🕰️ **상태(2026-09-27) — 이전 기록.** 점검 당시의 기록이라 상태 칸이 없습니다. 상당수가 이후 고쳐졌습니다. 최신 열린 항목은 [docs/54 §10](54-improvements-vs-practice.ko.md), 사례별 한계는 [docs/62](62-engineering-casebook.ko.md).

> 범위: 백엔드 전체와 CDC 구현의 코드 리뷰. 프론트엔드도 낙관적 업데이트와 롤백이 빠져 있어
> 고칠 곳이 많을 것으로 보고 함께 점검했다.
>
> 이 문서는 **점검 결과다.** §8 에 고치는 순서를 적었다.
>
> 예외는 §1.12 하나다 — `save()` 가 merge 되어 불필요한 select 가 호출되는 부분을 따로
> 확인하다 결함까지 드러나 그 자리에서 고쳤다. §1.13 도 같은 흐름에서
> 적용했다. 그 두 절만 **적용 완료**다.

## 0. 범위와 방법, 그리고 이 문서를 어떻게 읽어야 하나

- **범위**: 자바 13모듈(약 38,000줄), 프론트엔드(`frontend/src`, 약 16,000줄), 루트의 CDC 커넥터
  설정 5개와 아웃박스 경로.
- **방법**: 모듈 단위로 나눠 코드를 읽고 호출 경로를 따라갔다. 기준은 정확성 결함 → 보안 →
  효율 → 정리 순서이고, "더 깔끔할 수 있다" 류의 인상은 배제했다. 이미 문서화된 것(docs/27
  보안 리뷰, docs/37 트랜잭션 점검, docs/26·28 프론트엔드)은 다시 적지 않았다.
- **검증 표기**: 아래 🔴 항목은 **전부 내가 직접 파일을 열어 확인했다.** 🟠 이하는 리뷰어의
  보고를 그대로 옮기되, 내가 확인한 것은 "확인"으로 표시했다. 리뷰어가 틀린 것도 하나
  있었고(§6), 그것은 라이브러리 바이트코드로 반증해 뺐다.
- **읽는 법**: §1 만 읽어도 된다. 거기 있는 것들은 지금 운영에서 실제로 잘못 동작하고 있거나,
  누구나 재현할 수 있는 결함이다. §2 이하는 모듈별 상세다.

---

## 1. 지금 당장 — 운영에서 실제로 깨져 있는 것

심각도순. 같은 원인이 여러 모듈에서 발견된 것은 하나로 합쳤다.

### 1.1 🔴 주문 생성이 실패한다 — cart-service 계약 변경을 order-service 가 못 따라왔다

| 무엇 | 어디 |
| --- | --- |
| cart-service 의 `/checkout` 은 docs/27 수정으로 `?userId=` 대신 **`User-Email` 헤더**를 요구한다 | `cart-service/.../CartController.java:123-129` |
| order-service 의 Feign 클라이언트는 **아직 `?userId=`** 로 부른다. `FeignConfig` 는 `Authorization` 만 복사한다 | `order-service/.../client/CartServiceClient.java:18-19`, `config/FeignConfig.java:28` |

`createOrder` → cart 401 → `FeignErrorDecoder` 예외 → 트랜잭션 롤백 → **모든 주문 생성이
500.** 사가 E2E(`SagaEndToEndTest`)는 cart-service 를 띄우지 않아 이 경로를 지나지 않는다.
docs/34 의 "장바구니 → 주문 → 결제" 인수 항목은 아직 사람이 돌지 않았다.

### 1.2 🔴 결제 금액 검증이 클라이언트가 정한 값과 대조한다

docs/35 가 "브라우저의 '결제했다'를 믿지 않는다"로 고친 검증기는 PayPal 이 알려준 금액을
**`order.total` 과** 비교한다(`OrderDataService.java:270`). 그런데 `order.total` 은 주문 생성 때
**요청 본문의 `total` 을 그대로 저장한 값**이다(`:166`). 서버는 상품 목록만 cart-service 에서
다시 읽고 총액은 재계산하지 않는다.

```
장바구니 59.98 → POST /api/order/create {"total": 1, ...}
→ orders.total = 1 → PayPal 에서 1 USD 결제 → verified 1 == expected 1 → 통과
```

docs/35 의 ①②③④ 가 전부 통과한다. **유니크 제약도 진짜 결제이므로 무의미하다.**
같은 자리의 두 번째 결함: `total` 이 `int` 다(`common/.../OrderRequest.java:19`,
`order-service/.../Order.java:76`). Jackson 기본값(`ACCEPT_FLOAT_AS_INT`)이 소수점을 버리므로
59.98 → 59. 정상 주문마다 최대 0.99 가 장부에서 사라진다.

### 1.3 🔴 관리자 상품·카테고리 등록 화면이 빈 값을 보낸다 — 7월 수정의 회귀

docs/28 §2 가 "입력 글자 유실"을 고치려고 `AdminInput` 의 전개 순서를 `{...props}` →
`{...field}` 로 바꿨다(`AdminInput.jsx:22-23`, 커밋 `6f64410`). Formik 의 `onChange` 가 이기게
된 것 자체는 맞다. 문제는 세 호출부가 **여전히 자기 `useState` 를 제출**한다는 것이다:

- `CreateProduct.jsx:254` `onSubmit={() => {...}}` — Formik 의 `values` 를 받지 않고
  `product.name`(`:112`)을 쓴다. `AdminInput` 에 넘긴 `onChange={handleChange}`(`:340`)는
  덮여서 **한 번도 실행되지 않는다** → `product.name` 은 영원히 초기값.
- `CreateCategory.jsx:38,84`, `CreateSubCategory.jsx:10,27` 도 같은 구조.

결과: 이름·설명·브랜드가 빈 상품이 생성되거나(slug 는 `product.sku` 로 대체된다),
카테고리는 `slugify(undefined)` 에서 죽어 항상 "Failed to add a category". 내가 이번 작업에서
쓴 `CreateProduct.test.jsx` 는 **state 를 미리 채우고 제출만 눌러서** 이걸 못 잡는다 —
테스트가 입력을 타이핑했어야 했다.

### 1.4 ✅ 가입 확인 메일이 한 번도 나간 적이 없다 — `AFTER_COMMIT` 리스너가 트랜잭션 밖에서 호출된다

`RegistrationListener.onRegistrationComplete` 는 `@TransactionalEventListener(phase = AFTER_COMMIT)`
다(`:72`). 이벤트를 발행하는 곳은 컨트롤러다(`AuthController.java:196`, `:247`). 컨트롤러에는
`@Transactional` 이 없고, 직전의 `userService.registerNewUserAccount()` 는 반환하면서 이미
커밋됐다. 그러면 발행 시점에 활성 트랜잭션이 없고, `fallbackExecution` 기본값(false)이라
Spring 은 리스너를 **건너뛴다** — DEBUG 로그 한 줄만 남기고.

즉 `createVerificationTokenForUser` 도, `outgoing-mail` 발행도 실행되지 않는다. 지금은
`APP_REQUIRE_EMAIL_VERIFICATION` 이 꺼져 있어 가입 자체는 되지만, **그 스위치를 켜는 순간
모든 신규 계정이 영구 잠긴다.** 재발송(`/resend-verification`)도 같은 이유로 "보냈습니다"만
돌려준다.

> docs/37 §5 는 이 리스너를 "이 저장소의 가장 모범적인 지점"으로 꼽았다. 코드 모양은 모범이
> 맞다 — **실행되지 않을 뿐이다.** 그 문서에 정정을 달았다(§7).

**운영에서 실측 후 수정.** 2026-09-12, 실제 클러스터에서 가입해 보고 확인했다. 가입은
성공하는데 메일이 오지 않았고, 증거가 셋 다 같은 곳을 가리켰다:

| 확인 | 결과 |
| --- | --- |
| `kafka-run-class kafka.tools.GetOffsetShell --topic outgoing-mail` | `outgoing-mail:0:0` — 발행 0건 |
| `SELECT … FROM notification.mail_message` | 0 rows |
| user-service 로그 | 발행 성공도 실패도 없음 |

세 번째가 결정적이다. 리스너는 발행 성공이면 info, 실패면 error 를 남기는데 **둘 다 없다.**
실행 자체가 안 된 것이다. notification-service 는 정상이었다 — 마이그레이션 3개가 다 적용됐고
`메일 발송기: smtp (실제 발송)` 로 떴다. 즉 소비 측을 아무리 봐도 원인이 나오지 않는다.

**고친 방식:** 리스너에 `fallbackExecution = true`. 애초 제안(§8-4)은 "발행을
`registerNewUserAccount` 안으로 옮긴다"였는데, 다시 보니 그쪽이 맞지 않는다.
재발송 경로에는 쓸 트랜잭션이 애초에 없어서 애노테이션을 만족시키려고 트랜잭션을
만들어야 하고, 가입 경로는 **이미 커밋된 뒤에** 발행하므로 "커밋 이후에만 보낸다"는 보장이
이미 성립한다(서비스가 던지면 발행 줄에 닿지 않는다). 옮겨서 얻는 것이 없다. 이 플래그는
덧붙이는 설정이라, 나중에 누가 발행을 트랜잭션 안으로 옮겨도 `AFTER_COMMIT` 이 그대로 적용된다.

`RegistrationListenerDeliveryTest` 는 애노테이션을 문자열로 검사하지 않고 **스프링 이벤트
인프라를 실제로 태운다.** 트랜잭션 매니저를 두지 않아 발행 시점 조건이 운영과 같다. 플래그를
빼고 돌려 두 테스트가 모두 실패하는 것을 확인했다. 이 회귀는 로그를 남기지 않으므로,
세는 테스트가 없으면 다시 조용히 돌아온다.

**그 뒤에 나온 것 — 메일 인증은 애초에 게이트가 아니었다.** "메일이 안 나갔으면 그 가입은
무효가 돼야 하지 않나"는 질문을 따라가다 발견했다. 메일을 고쳐도 인증이 아무것도 막지 못한다.
두 겹으로 무력화돼 있었다:

```java
// UserDetailsImpl — enabled 를 담지도 않고 하드코딩
@Override public boolean isEnabled() { return true; }

// CustomAuthenticationProvider — AuthenticationProvider 직접 구현.
// DaoAuthenticationProvider 의 사전 검사(isEnabled)가 아예 돌지 않는다.
if (passwordEncoder.matches(password, userDetails.getPassword())) { … 인증 성공 … }
```

즉 `APP_REQUIRE_EMAIL_VERIFICATION=true` 로 켜서 계정을 `enabled=false` 로 만들어도 **그대로
로그인된다.** 남의 이메일 주소로 가입해도 쓸 수 있다는 뜻이다.

고친 방식: `UserDetailsImpl` 이 엔티티의 값을 담아 반환하고, 프로바이더가 **비밀번호 검사 뒤에**
`DisabledException` 을 던진다. 앞에 두지 않은 이유는 비밀번호를 모르는 사람에게 "이 주소는
가입돼 있고 미인증"을 알려 주지 않기 위해서다 — 가입 여부 확인 도구가 되면 안 된다.
`AuthLoginFilter` 는 이 예외의 메시지를 그대로 내보낸다. 일반 문구를 주면 사용자가 비밀번호를
계속 다시 치다가 잠기는데, 정작 필요한 것은 메일 확인이다.

`DisabledAccountLoginTest` 6개. 두 겹을 각각 되돌려 3개·2개가 실패하는 것을 확인했다 —
어느 한쪽만 있어도 게이트가 사라지므로 양쪽을 따로 센다.

나머지 셋(`isAccountNonLocked`·`isAccountNonExpired`·`isCredentialsNonExpired`)은 **의도적으로**
참을 유지한다. 잠금은 `lockExpiresAt` 과 `LoginAttemptService` 가 판정하고, 만료는 날짜 컬럼만
있고 그것을 검사하는 코드가 없다. 불리언만 강제하면 "만료됐는데 통과"가 되어 오히려 지켜지는
것처럼 보인다. 판정 로직을 먼저 만든 뒤에 붙일 것 — 코드에 그 이유를 적어 두었다.

**정리 스케줄러 — `UnverifiedAccountCleanup`.** 만료 토큰을 지우는 리포지토리 메서드가
`VerificationToken`·`PasswordResetToken` 양쪽에 있었는데 **아무도 호출하지 않았다.** 게이트를 켜면
링크를 누르지 않은 계정이 비활성으로 쌓이고, 그 계정은 아무 기능도 하지 않으면서 **그 이메일
주소를 영구히 점유한다** — 같은 주소로 다시 가입하면 "이미 존재한다"만 돌아온다.

판단 근거가 깔끔하다. 인증에 성공하면 `validateAndGetUser` 가 계정을 활성화하고 **토큰을 지운다.**
그래서 "비활성 계정 + 남아 있는 토큰"이면 한 번도 인증하지 않은 것이다. 재발송은 같은 토큰 행의
만료를 갱신하므로 최근에 재발송을 요청한 사람은 유예 계산에서 자동으로 빠진다.

안전장치 넷을 겹쳤다. 되돌릴 수 없는 작업이라서다.

| 장치 | 내용 |
| --- | --- |
| 기본 꺼짐 | `delete-accounts` 기본 false. 꺼진 동안에는 "지웠다면 몇 건"만 로그로 남겨 켜기 전에 영향 범위를 보게 한다 |
| 유예 기간 | 토큰 만료 후 기본 7일 더 기다린다. 만료 직후 지우면 재발송으로 복구하려던 사용자를 잘라 낸다 |
| 행 단위 트랜잭션 | 하나가 실패해도 나머지는 진행된다. 조회와 삭제 사이에 인증을 마쳤으면 재확인에서 걸러진다 |
| 외래키에 위임 | 주문·리뷰·배송지·위시리스트가 있으면 DB 가 막는다. 그 목록을 코드로 다시 열거하지 않아, 나중에 참조가 늘어도 이 코드는 그대로 안전하다 |

만료 토큰 정리에는 함정이 하나 있다. **확인 토큰을 전부 지우면 안 된다** — 비활성 계정의 토큰은
위 판정의 유일한 근거라서, 유예 기간이 끝나기 전에 지우면 그 계정이 영원히 후보에 오르지
않는다. 그래서 `deleteExpiredOfEnabledUsers` 로 **이미 활성화된 계정의 것만** 지운다. 비밀번호
재설정 토큰은 판정에 쓰이지 않으므로 만료된 것을 모두 지운다.

`UnverifiedAccountCleanupTest` 9개. 안전장치 셋을 각각 되돌려 모두 실패하는 것을 확인했다.
JPQL 자체는 실제 DB 가 있어야 검증된다 — 이 테스트가 고정하는 것은 스케줄러의 판단과 순서다.

**켜는 순서:** 메일이 실제로 오는 것을 확인 → `require-email-verification=true` → 며칠 뒤 로그의
"정리 대상 N건"이 예상과 맞는지 확인 → `delete-accounts=true`. 게이트가 꺼져 있으면 모든 계정이
활성으로 만들어지므로 삭제 대상 자체가 생기지 않는다.

### 1.5 🔴 할인가 계산식이 틀렸다 — 백엔드 두 곳, 프론트는 이미 고쳐짐

```java
priceBefore.subtract(priceBefore.divide(discount, 2, HALF_UP))   // 정가 − 정가/할인율
```

`catalog-service/.../ProductService.java:757`, `cart-service/.../CartService.java:445`(그리고
`:462-473`). `discount` 는 퍼센트다(프론트 `Infos.jsx` 가 `-{discount}%` 로 표시하고
`util/pricing.js:21` 은 `p * (1 - d/100)` 로 계산한다).

정가 10,000 / 50% 할인 → 화면 5,000, 장바구니 **9,800**. 할인율이 클수록 실제 할인은
작아진다(70% → 1.4%). 10% 일 때만 우연히 맞다. cart-service 가 catalog 의 이 값을 다시
읽어 총액을 만들므로 **장바구니·주문 금액이 광고가와 다르다.**

### 1.6 🔴 남의 데이터에 손이 닿는 경로 — docs/27 이후에도 남은 것

| 경로 | 무엇이 없나 | 위치 |
| --- | --- | --- |
| `DELETE /api/user/profile/delete/{addressId}` | 주소 소유권 검사 없음 — 남의 배송지 삭제 | `AddressService.java:154-173` |
| `GET /api/user/profile/select/{addressId}` | `findByActive(true)` 가 **전역** 쿼리 + `Optional` — 사용자 2명 이상이면 `IncorrectResultSize` 로 상시 실패, 1명이면 남의 활성 주소를 끈다 | `AddressService.java:92-100`, `ShippingAddressRepository.java:34` |
| `GET /api/auth/user/profile/{userId}`, `/user/email/{userId}`, `/user/name?userName=` | 본인 확인 없음 — 이메일·실명·2FA 여부·기본 결제수단 열거 | `AuthController.java:299-344` |
| ~~`POST /api/cart/savecart`~~ ✅ | ~~신원을 **요청 본문의 email** 에서 읽는다 — 남의 장바구니를 지우고 덮어쓴다~~ → 신원은 `User-Id`(토큰 uid), 본문 DTO 에 email 필드 없음(2026-09, docs/54 §1.1) | `CartController.saveCart` · `CartIdentityContractTest` |
| `POST /api/coupon/applycoupon` | 신원을 본문의 `userEmail` 에서, 금액을 본문의 `totalPrice` 에서 — 남의 쿠폰 열거 + 남의 카트 총액 오염 | `CouponController.java:61-66`, `CouponService.java:46-70` |
| `POST /api/order/payment/paypal` | 주문 소유권 검사 없음, `customerId` 를 본문의 `userEmail` 로 해석 | `OrderDataService.java:247-258`, `:303` |
| `PUT /api/product/rating` | 로그인만 하면 **어느 상품이든 평점을 덮어쓴다**(0~5 범위·구매 여부·중복 검사 없음). `AdminWriteInterceptor` 의 명시적 예외 경로다 | `ProductController.java:274-279`, `ProductRepository.java:52-54` |

docs/27 은 "남의 것에 손이 닿는가"를 물었지만 조회·삭제 경로 일부와 **쓰기 경로의 신원
출처**를 놓쳤다. 패턴은 하나다 — 게이트웨이가 검증해 준 `User-Id`/`User-Email` 헤더가
있는데 본문/파라미터의 값을 쓴다.

### 1.7 🔴 2FA 인증번호가 기본 발송기의 INFO 로그에 그대로 찍힌다

`LoggingSmsSender.java:33` — `body={}` 에 `"[쇼핑몰] 인증번호 483920 …"` 전문. 바로 옆의
`LoggingMailSender.java:35-41` 은 본문을 빼고 길이만 찍는다(링크가 들어 있어서). 규칙은
알고 있었고 SMS 쪽만 빠졌다. 기본값이 `logging` 이라(`SmsConfig.java:27`) `SMS_PROVIDER` 를
명시하지 않은 환경에서는 운영에서도 이 경로다. 로그 열람 = 남의 2FA 통과.

같은 서비스의 이웃 결함: 발급 제한이 **번호당**뿐이다(`VerificationService.java:104,110` 의
키가 `sms:cooldown:{e164}`, `sms:daily:{e164}`). 로그인한 계정 하나가 번호를 바꿔 가며
보내면 게이트웨이 20 req/s 까지 실발송된다. 요금은 우리가 낸다. pepper 기본값이 빈
문자열인 것(`:69`)도 같은 파일이다 — 비면 `SHA-256(code)` 라 100만 개 표로 즉시 역산된다.

> **2026-09 고침.** 발송기가 본문을 남기지 않는다. 실제 로그를 캡처하는 `LoggingSmsSenderTest` 가 고정한다 — docs/51 §1.

### 1.8 🔴 `delivery-status` 토픽을 아무도 읽지 않는다

`DeliveryStatusPublisher` 가 발행하는 토픽의 컨슈머가 저장소 어디에도 없다. 클래스 주석은
notification·order 두 소비자를 명시하지만 둘 다 그 토픽을 구독하지 않는다. 결과:
`SHIPPING_UPDATED` 알림은 한 번도 생성되지 않고, docs/25 §0 이 이 서비스를 만든 이유로
든 "주문이 PAID 에서 멈춘다"가 그대로 남아 있다.

### 1.9 🔴 상품 등록이 사이즈를 SKU 에 연결하지 않는다

```java
request.getSizes().forEach(size -> ProductSizeMapper.toEntity(size).setSku(skuProject)); // 버려지는 인스턴스
skuProject.setSizes(request.getSizes().stream().map(ProductSizeMapper::toEntity).collect(...)); // sku == null
```

`ProductService.java:662-665`(`loadSku` `:585-602` 도 동일). `sizes` 는 `mappedBy` 역방향이라
FK 는 `ProductSize.sku` 에서 나오는데 그게 null 이다. `product_size.skuproduct_id` 는
nullable(V5) → **고아 행이 조용히 INSERT** 된다. `details`/`questions` 는 `product_id not null`
이라 제약 위반 500. 단위 테스트가 전부 Mockito 라 못 잡는다. 1.3 이 고쳐져 실제 등록이
되기 시작하면 바로 드러난다.

### 1.10 🔴 결제 후 화면이 "미결제"로 남아 이중 청구를 유도한다

`pages/order.jsx:22,49,54` — 결제 성공 시 갱신하는 `order` state 는 **아무 데서도 렌더되지
않는다.** 화면은 react-query 의 `orderData` 로 그리는데 `invalidateQueries` 도 `refetch` 도
없다. 사용자는 `Payment Status ✗` 와 PayPal 버튼을 그대로 보고 다시 결제한다. 서버는 같은
`paypalOrderId` 만 중복 무시하므로(`OrderDataService.java:285-288`) **새 결제는 받아서 아웃박스에
두 번째로 발행한다.** 주문 목록의 Paid 배지는 조건이 반대다(`orders.jsx:80` —
`=== "UNPAID"` 일 때 초록 체크).

### 1.11 🔴 컨트롤러의 상태 플래그 분기 — 셋이 잘못 동작한다

컨트롤러 안에서 불리언·문자열 상태로 분기하는 지점을 전수로 훑었다(5곳). 그중 셋이 결함이다.

**① 카테고리 생성이 영구히 막혀 있다** — `CategoryController.java:63`

```java
boolean exist = categoryService.findCategory(request.getName());
if (!exist) { ...생성... } else { return 400 CATEGORY_IS_EXISTED; }
```

`findCategory` 는 `null != categoryRepository.findByCategoryName(name)` 인데 그 반환형이
**`Optional<Category>`** 다(`CategoryRepository.java:24`). `Optional` 은 절대 null 이 아니므로
**항상 true** → `!exist` 는 항상 false → 무엇을 넣어도 "이미 존재합니다".

바로 아래 서브카테고리(`:96`)는 **멀쩡하다** — `findBySubcategoryNameAndCategory_CategoryId` 의
반환형이 `Subcategory`(`SubcategoryRepository.java:31`)라 `null !=` 비교가 성립한다. 같은
파일에 나란히 있는 두 메서드가 하나는 맞고 하나는 틀려서 눈에 띄지 않았다.

**② `/api/auth/public/verify-2fa-login` 은 동작한 적이 없다** — `AuthController.java:495-507`

```java
String username = jwtUtils.getIdFromJwtToken(jwtToken);
User user = userService.findByUsername(username);
boolean isValid = userService.validate2FACode(user.getUserId(), code);   // ← NPE
```

`JwtUtils` 의 토큰 생성 네 곳이 전부 `.subject(email)` 인데(`:113,132,160,188`),
`findByUsername` 은 리포지토리 주석이 밝히듯 **실명으로 검색**한다(`UserRepository.java:29`
"실명으로 검색"). 이메일로 실명을 찾으니 null 이고 다음 줄에서 NPE → **500**. 즉 일반
2FA 로그인의 완결 경로가 처음부터 막혀 있다.

덤으로 이 **공개** 엔드포인트에는 코드 시도 횟수 제한이 없고, `getIdFromJwtToken` 은
`ExpiredJwtException` 을 잡아 만료 토큰의 subject 도 돌려준다(§2.1 #5).

**③ 결제 엔드포인트에 주문 상태 가드가 없다** — `OrderDataService.java:247-300`

중복 차단이 **같은 `paypalOrderId`** 에 대한 유니크 제약뿐이다. 주문이 이미 `PAID` 인지
보지 않는다. 새 PayPal 주문으로 다시 결제하면 서버가 받아들여 아웃박스에 두 번째 결제를
발행한다. §1.10(결제 후 화면이 "미결제"로 남아 재결제를 유도)과 맞물리면 **유도하는 쪽과
막지 않는 쪽이 동시에 성립한다.** 둘 중 서버 가드가 없는 이쪽이 더 근본이다.

**반대로 올바른 둘** — 참고용:

- `NotificationController.java:145` — `preference.enableTwoFactor(...)` 가 `phoneVerified`
  가 아니면 false 를 돌려주고 컨트롤러는 **409** 를 낸다(`NotificationPreference.java:137-144`).
  판단은 도메인 객체가 하고 컨트롤러는 결과만 옮긴다.
- 품절 가드 — `ProductService.java:740` 이 cart-service 가 담기 직전에 부르는
  `getCartProductInfo` 에서 `SOLD_OUT` 이면 null 을 돌려준다. `DELETED` 는
  `@SQLRestriction` 이 조회 단계에서 거른다.

**공통 원인**: ①②는 컨트롤러가 **불리언/문자열로 납작해진 결과**를 받아 분기하는데, 그 값을
만드는 쪽의 계약(`Optional` 인지 엔티티인지, subject 가 이메일인지 실명인지)이 어긋났고
컴파일러는 아무 말도 하지 않는다. `findCategory` 가 `Optional` 을 그대로 돌려줬거나
`verify-2fa-login` 이 `findByEmail` 을 썼다면 둘 다 생기지 않았다.

### 1.12 🔴 `save()` 가 `persist` 가 아니라 `merge` 로 가는 엔티티 8개 — **적용 완료**

`SimpleJpaRepository.save()` 는 한 줄로 갈린다.

```java
if (entityInformation.isNew(entity)) em.persist(entity);
else                                 return em.merge(entity);
```

그리고 `isNew()` 의 기본 판정은 **"`@Id` 가 null 인가"** 다. 그래서 id 를 코드가 직접
배정하는 엔티티 — 생성자의 `UUID.randomUUID()`, 카프카가 준 `eventId`, user-service 가 준
`userId` — 는 save() 시점에 id 가 이미 차 있고, 그것만으로 "기존 행"으로 판정돼 `merge()` 로
간다. **merge 는 붙일 대상을 찾으려 SELECT 를 먼저 던진다.**

실제로 돌려서 확인했다(임시 PostgreSQL + 실제 `SimpleJpaRepository`):

| 매핑 | `isNew()` | 신규 1건 save() 가 내는 SQL |
| --- | --- | --- |
| 할당 ID, `@Version` 없음 | `false` → `merge()` | **`select … where id=?`** + `insert` |
| 할당 ID + 래퍼 `@Version` (order-service `Customer`) | `true` → `persist()` | `insert` |
| `@Tsid` (나머지 대다수) | `true` → `persist()` | `insert` |
| `Persistable` 구현 (수정안) | `true` → `persist()` | `insert` |

`@Tsid` 가 안전한 이유는 `TsidGenerator` 가 `IdentifierGenerator` 라 save() 직전까지 id 가
여전히 null 이기 때문이다(실행해서 확인 — save() 전 `id = null`, 후 `id = 8846…`).

**해당 엔티티 8개.** 모두 아웃박스·멱등성 원장·이벤트 소비 경로에 있다 — 즉 주문·결제
한 건마다 곱해지는 자리다.

| 엔티티 | 실제 save 경로 |
| --- | --- |
| `order-service/.../PaymentOutboxEvent` | `PaymentOutboxHelper.java:34` — 주문마다 |
| `pay-service/.../OrderOutboxEvent` | `OrderOutboxHelper.java:34` — 결제마다 |
| `catalog-service/.../ProcessedEvent` | `ProductUpdateMessageListener.java:45` |
| `coupon-service/.../ProcessedEvent` | `PaymentResponseMessageListener.java:48` |
| `chat-service/.../ChatMember` | `UserMessageListener.java:27` |
| `cart-service/.../OutboxEvent` | 없음 — 아웃박스 전체가 미사용(§아래) |
| `user-service/.../OutboxEvent` | 없음 — `UserServiceImpl.java:549-550` 이 주석 |
| `delivery-service/.../Carrier` | 없음 — 조회 전용, Flyway 가 채운다 |

#### 성능보다 나쁜 쪽 — 멱등성 가드가 무력화돼 있었다

`merge` 는 같은 id 가 **이미 커밋돼 있으면 PK 충돌 대신 조용히 UPDATE 를 낸다.** 이것도
실행해서 확인했다 — T1 이 커밋한 뒤 T2 가 같은 id 로 save() 하면 예외 없이 `update` 가
나가고 커밋된다.

그래서 "삽입이 실패해 줄 것"에 기대던 코드 셋이 전부 성립하지 않고 있었다.

- `ProductUpdateMessageListener.java:43-44` 주석: *"동시 중복(희귀)은 PK 충돌로 전체 롤백 →
  재전달 시 existsById 로 스킵된다."* — 충돌이 나지 않으므로 롤백도 없다. `existsById` 를
  동시에 통과한 두 소비자가 있으면 **재고가 두 번 차감되고 원장은 UPDATE 로 덮인다.**
- coupon-service 도 같은 구조 — 쿠폰 사용이 두 번 반영된다.
- `UserMessageListener.java:42` 의 `catch (DataIntegrityViolationException)` 은 **한 번도
  실행된 적이 없다.** 재전달된 user-created 이벤트가 기존 회원의 `nickname`·`imageUrl` 을
  조용히 덮어쓸 수 있었다.

세 곳 모두 `existsById` 단독 검사(먼저 읽고 나중에 쓰는 구조)라 동시성에서는 원래 못 막고,
PK 충돌이 마지막 방어선이었다. 그 방어선만 정확히 빠져 있었다.

#### 수정

여덟 엔티티 모두 `Persistable<ID>` 를 구현하게 했다.

```java
@Transient
private boolean persisted;              // 기본값 false = 새 것

@Override public boolean isNew() { return !persisted; }

@PostPersist @PostLoad
private void markPersisted() { this.persisted = true; }
```

플래그를 `isNew = true` 가 아니라 **`persisted = false` 방향**으로 뒤집은 이유가 있다.
`ChatMember` 는 `@SuperBuilder` 를, 나머지는 `@NoArgsConstructor` 를 쓰는데 Lombok 이
`@Builder.Default` 없는 필드 초기화를 어떻게 다루느냐에 따라 값이 달라진다. 기본값 `false`
가 곧 "새 것"이면 어느 쪽으로 떨어져도 안전하다. 실제로 컴파일된 `ChatMember` 로 확인했다 —
`ChatMember.builder()…build().isNew() == true`.

`@PostLoad` 가 핵심이다. 이게 없으면 불러온 엔티티도 `isNew()=true` 로 남아 아웃박스
릴레이의 상태 변경(`claim → setOutboxStatus → save`)이 UPDATE 대신 INSERT 를 시도해 **PK
충돌로 릴레이가 통째로 죽는다.** 이 수정이 만들 수 있는 최악의 회귀라 양쪽 경로를 모두
돌려서 확인했다(신규 → `insert` 만, 불러온 것 → `update`).

#### 되돌아오지 않게

`common/src/test/.../EntityPersistenceGuardrailTest.java` 를 새로 두었다. 저장소의 모든
`@Entity` 를 훑어(상속 사슬을 따라 올라가며) `@GeneratedValue` · `@Tsid` · 래퍼 `@Version` ·
`Persistable` 중 아무것도 없는 엔티티를 잡고, `Persistable` 을 구현했다면 `@PostLoad` 가
있는지도 함께 본다. 둘 다 일부러 되돌려 놓고 정확히 그 파일 이름을 짚으며 깨지는 것을
확인했다.

여기서 **기존 가드레일의 구멍이 하나 드러났다.** 이 테스트도 `ConfigurationGuardrailTest`
도 *다른 모듈의* 파일을 읽는데, 그 파일들은 `:common:test` 의 선언된 입력이 아니다. 그래서
common 자신이 안 바뀌면 Gradle 이 UP-TO-DATE 로 건너뛴다 — **가드레일이 지켜야 할 바로 그
변경에서 실행되지 않는다.** 실제로 다른 모듈 엔티티를 되돌려 놓고 돌렸을 때 통과해
버렸다. `common/build.gradle` 의 test 태스크에 `outputs.upToDateWhen { false }` 를 넣어
막았다.

#### 덤으로 드러난 것 — cart-service 아웃박스가 통째로 비어 있다

`cart-service/.../outbox/` 에 `OutboxEvent`·`OutboxEventRepository`·`OutboxStatus`·
`OutboxHelper`·`OutboxScheduler` 가 다 있는데 **`OutboxHelper` 는 필드 두 개뿐이고
`OutboxScheduler` 는 5줄짜리 빈 껍데기다.** 패키지 밖에서 참조하는 코드도 없다. 즉
cart-service 에는 아웃박스가 없다. user-service 쪽도 `UserServiceImpl.java:549-550` 에서
주석 처리돼 있어 마찬가지다. 이 문서의 §5(아웃박스/CDC)와 함께 봐야 한다.


### 1.13 🟡 저장소 정리 · SKU 잠금 범위 · 스레드 풀 — **적용 완료**

세 가지를 한 커밋에 넣었다. 셋 다 "긴 이름" 이나 "풀을 쓰자" 같은 인상이 아니라 실측이 근거다.

#### 미사용 파생 쿼리 19개 삭제

이름 16자 이상인 파생 쿼리 중 테스트까지 포함해 어디서도 부르지 않는 것이 20개였다. 그중
가장 긴 `findBySizesPriceBetweenAndSizesSizeAndColorColor`(48자, 인수 4개)는 4줄 아래의
native 쿼리가 같은 일을 하고 있었고, `ProductSizeRepository.findBySkuproductIdAndSize` 는
`nativeQuery=true` 인데 본문이 JPQL 엔티티명이고 `@Param("skuId")` 와 `:skuid` 가 어긋나
있어 **불리면 그때 깨질** 것이었다. `findByIdWithPessimisticLock` 은 이름과 달리 `@Lock` 이
주석 처리된 예제였다. 하나는 남겼다 — `NotificationPreferenceRepository.findByPhoneE164AndPhoneVerifiedTrue`
는 §2.5 #11 이 "선언만 있고 검사가 빠졌다"고 짚은 그 메서드라, 지우면 고칠 자리가 없어진다.

#### `findByProductProductIdAndSizesSizeAndColorColorId` → `findForStockUpdate` (JPQL + `PESSIMISTIC_WRITE`)

먼저 **정정**. 앞서 "컬렉션 join + `Optional` 이라 사이즈 행이 중복되면
`IncorrectResultSizeDataAccessException`" 이라고 했는데, 실제 카탈로그 엔티티로 돌려 보니
**틀렸다.** DB 는 2행을 돌려주지만 Hibernate 6 가 루트 엔티티를 중복 제거해
`getSingleResult()` 도 예외를 내지 않는다(스칼라 셀렉션은 2행 그대로 — 대조 확인).

바꿀 진짜 이유는 **잠금 범위**다. 원저자가 주석으로 남긴 `PESSIMISTIC_WRITE` 는 켜야 한다
(`sold` 는 읽고-더하고-쓰는 값). 그런데 join 형태에 FOR UPDATE 를 붙이면 PostgreSQL 은 FROM 의
**두 테이블 행을 모두** 잠근다. 실측: join+FOR UPDATE 를 쥔 T1 아래에서 T2 의 `product_size`
UPDATE → `lock timeout`; `exists` 형태 → 통과. 같은 사가의 다음 단계가 바로 그
`updateProductSize_Qty`(수량 차감)라, join 이었다면 자기 사가가 자기 잠금에 막혔을 것이다.
`distinct` 로 우회하는 길은 없다 — PostgreSQL 은 `DISTINCT … FOR UPDATE` 를 거부한다.

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("""
       select sk from ProductSku sk
        where sk.product.productId = :productId
          and sk.color.colorId = :colorId
          and exists (select 1 from ProductSize s where s.sku = sk and s.size = :size)
       """)
```

생성 SQL 확인: `from product.product_sku ps1_0 where … and exists(select 1 from product.product_size …) for no key update`
— `product.productId`/`color.colorId` 는 FK 컬럼으로 풀려 조인이 없다.

#### 스레드 풀 — 있어야 할 곳 둘, 있어도 답이 아닌 곳 둘

| | 어디 | 무엇 | 조치 |
| --- | --- | --- | --- |
| 🟠 | notification-service | `@Scheduled` 셋(SMS 5초·메일 5초·스크럽 1시간)이 **기본 스케줄러 스레드 하나**를 나눠 쓴다(`TaskSchedulingProperties.Pool.size` 기본 1 — 바이트코드 확인). SMS 배치가 게이트웨이 타임아웃에 걸린 동안 메일은 한 통도 안 나간다 | `spring.task.scheduling.pool.size: 3`. 읽히는지는 `SchedulerPoolBindingTest` 가 고정(키를 틀리면 깨지는 것 확인). 서로 다른 작업이 겹쳐도 안전 — 둘 다 `SKIP LOCKED` 로 자기 몫을 선점한다 |
| 🟠 | user-service `NotificationService:37` | `Executors.newSingleThreadExecutor()` 필드 — **스레드 하나가 접속한 모든 브라우저의 SSE 전송**을 담당. `emitter.send()` 는 블로킹 소켓 쓰기라 느린 클라이언트 하나가 전부를 줄 세운다. 큐 무제한, 종료 훅 없음, 이름 없음 | `AsyncSseConfig`(2–4 스레드, 큐 200, 가득 차면 **버리고 로그**) + `SseDispatcher` 의 `@Async("sseExecutor")`. `CallerRunsPolicy` 는 틀린 선택 — 호출자가 Redis 구독 스레드라 그게 막히면 파드의 알림 수신이 멈춘다 |
| 🟡 | cart-service `CartService` — `loadCart`·`updateCart`·`saveCart` | 카트 항목마다 `getProductInfo` Feign 을 **직렬**로 불렀고, `loadCart` 는 거기에 항목별 `getCartProductInfo` 까지(N 항목 = 2N 왕복) | **풀이 답이 아니다.** 병렬화하면 `FeignConfig` 의 `Authorization` 복사가 요청 스레드 컨텍스트에 묶여 있어 워커에서 빠진다. 대신 catalog 에 배치 둘 — `GET /api/products/info?ids=…` 와 `GET /api/cart/info?items=1:0:0&…`(키 `productId:style:sizeIndex`, 상한 100, 못 찾은 항목은 빠질 뿐 200). 세 경로가 각각 1회, `loadCart` 는 2회로. 두 번째가 **GET 인 이유**: 처음엔 POST+JSON 으로 만들었는데 `@WebMvcTest` 에서 401 — `AdminWriteInterceptor` 가 POST/PUT/DELETE 에 게이트웨이의 `User-Id` 를 요구하고 서비스 간 Feign 호출에는 그 헤더가 없다. 운영에서도 똑같이 막혔을 것이다. 읽기이니 GET 이 맞기도 하다. `CartServiceBatchLookupTest` 가 **호출 횟수**를 센다(항목 셋에 배치 1+1회, 단건 0회, 키 `[1:0:0, 1:0:1, 2:0:0]`). 단건으로 되돌리면 컴파일도 기능도 그대로라 세지 않으면 안 보인다 |
| 🟡 | delivery `TrackingPoller`, notification `SmsDispatcher` | 외부 HTTP 를 **잠금 트랜잭션 안에서 직렬**로 부른다(저장소 주석이 이미 인정: "외부 호출이 배치 크기만큼 직렬로 붙어 트랜잭션이 길어진다") | 풀로 병렬화하려면 claim(tx) → 호출(tx 밖, 풀) → 반영(tx) 으로 쪼개야 한다 — 영속성 컨텍스트는 스레드에 묶여 있다. 구조 변경이라 미적용. 지금은 배치 크기 상한으로 버틴다 |

#### 색상 조회도 같은 모양이었다 — order-service · cart-service

order-service 의 `CatalogServiceClient.getCartProductInfo` 는 **선언만 있고 호출이 없었다**(지웠다).
그 서비스가 실제로 항목마다 부르던 것은 `getColorInfo` 다 — `OrderService.convertToOrderDto` 가 주문
상품마다 하나씩, 주문 목록 한 페이지(주문 10건 × 상품 3개)면 Feign **30번**. cart-service 의
`convertCartToDto`(캐시 갱신 4곳)도 항목마다 같은 호출이었다.

catalog 에 `GET /api/colors/info?ids=…` 를 두고, order 는 **페이지 단위**로(`OrderService.fetchColors`
→ `convertToOrderDto(order, colors)`), cart 는 장바구니 단위로 한 번 받는다. 못 찾은 색상은 예전처럼
null 이다. `OrderColorBatchTest` 가 페이지당 1회·단건 0회를, `CartServiceBatchLookupTest.getCart_…` 가
장바구니당 1회를 센다. `OrderMapper` 는 `convertToOrderDto` 와 같은 일을 하는 중복이고 아무도 부르지 않는
빈이었다(§2.3) — 항목별 `getColorInfo` 를 남겨 두는 마지막 자리라 지웠다.

#### StreamingResponseBody — 붙일 자리가 없었다, 대신 둘

응답을 스트리밍할 만한 곳(파일 다운로드·내보내기·큰 목록 생성)을 컨트롤러 전체에서 찾았다.
**없다.** 이미지는 S3 URL 로 나가고, CSV 는 Spring Batch 가 *읽기만* 하고, SSE 는 이미
`SseEmitter` 다. 유일하게 큰 응답은 `SearchController` 의 `/{entity}/all` 8개(ES `findAll()`
무제한 덤프, §2.2 #9 · docs/06 CAT-6)인데, 이건 **스트리밍이 아니라 삭제가 답이다** — 프론트엔드·
다른 서비스·테스트 어디에도 호출자가 없다. 지웠다(저장소 필드 7개와 함께).

스트리밍이 실제로 빠져 있던 곳은 **요청 쪽**이었다. `ImageService`(catalog·user 둘 다)가
`IOUtils.toByteArray(file.getInputStream())` 로 업로드 파일을 통째로 힙에 올렸다 — S3 SDK 의
Content-Length 때문인데, 그 값은 `MultipartFile.getSize()` 가 이미 안다(톰캣이 받으면서 임시
파일에 써 둔다). `file.getInputStream()` 을 그대로 넘기게 바꿨다. 상품 등록은 색상별로 이미지를
여러 장 한 번에 올리므로, 그만큼의 `byte[]` 가 요청 하나에 동시에 살아 있던 것이 사라진다.
`encodeFileToBase64`(`ProductService:721`)는 Base64 특성상 전체가 필요해 그대로다.

Kafka 리스너 `concurrency` 는 전 모듈 기본값 1 이다. 파티션 수를 늘린 토픽이 있다면 그때
같이 올릴 값이지, 지금 결함은 아니다.


### 1.14 🟡 프론트엔드 메모리 누수 점검 — 둘 고침, 나머지는 깨끗

> 범위: 프론트엔드에서 메모리 누수가 생길 수 있는 지점 점검.

구독·타이머·리스너·옵저버·`createObjectURL`·전역 캐시·언마운트 뒤 setState 를 `frontend/src`
192개 파일에서 훑었다. 큰 것들은 앞선 작업에서 이미 닫혀 있었다 — SSE(`util/Notification.js`)는
새 연결 전 이전 연결을 닫고 언마운트에 타이머·연결을 정리하며, STOMP(`util/stompProvider.js`)는
`deactivate()` 와 구독별 `unsubscribe` 를 돌려주고, `useTokenRefresh` 의 axios 인터셉터 둘은
정리에서 `eject` 된다. `IntersectionObserver`·폴링 `setInterval`·`document` 리스너도 전부 짝이
맞는다. `AuthContext` 의 `login`/`logout` 이 `useCallback` 이라 SSE `connect` 가 렌더마다
재생성되는 일도 없다.

**고친 둘**

| | 어디 | 무엇 |
| --- | --- | --- |
| 🟡 | `ChatArea.jsx` `useLayoutEffect` | 정리 함수가 `messageRef.current.removeEventListener` 를 부르는데, 방 전환·언마운트 시점에는 `current` 가 null 이거나 다른 요소라 **엉뚱한 대상에 호출하고 catch 로 삼켰다** — 원래 요소에는 리스너가 남는다. 이 effect 는 `messages` 가 바뀔 때마다 다시 도니 남은 리스너가 메시지 수만큼 쌓이고 각각이 그 시점의 `messages` 배열을 클로저로 붙든다. 붙인 요소를 `el` 로 붙잡아 같은 요소에서 떼게 했고, React 가 감지하지 못하는 `messageRef.current` 를 deps 에서 뺐다 |
| 🟡 | `ChatFooter.jsx` `handleImg` | `URL.createObjectURL` 만 있고 `revokeObjectURL` 이 저장소 어디에도 없다 — 브라우저가 파일을 붙든다. 첨부 UI 가 주석 처리돼 잠든 경로지만, 되살릴 때 누수도 같이 살아나므로 `img.url` 이 바뀌거나 언마운트될 때 revoke 하는 effect 를 넣었다 |

**누수는 아니지만 짚어 둘 것**

- `pages/admin/Index.jsx:71,84,108`, `components/admin/ProductColors.jsx:11` — 비동기 요청 뒤
  setState 하는데 언마운트 가드가 없다. React 18 은 경고하지 않고, 응답이 오면 닫힌 컴포넌트의
  setState 는 no-op 이라 **누수가 아니다**(응답이 올 때까지 클로저가 살아 있을 뿐). 관리자 화면이라
  그대로 둔다.
- `ChatArea.jsx:45` `let prevScrollHeight` 가 모듈 스코프다 — 인스턴스가 하나라 지금은 문제없지만
  방을 둘 띄우면 공유된다.
- `pages/browse.jsx:243` 는 스크롤 이벤트마다 `setScrollY` 로 페이지 전체를 다시 그렸다 — 누수가
  아니라 성능. **고쳤다**: rAF 로 프레임당 한 번으로 묶고, 값은 픽셀이 아니라 "헤더를 넘었는가"
  판정이 바뀔 때만 저장한다(그 하나가 유일한 용도라서). 스크롤 중 대부분의 프레임에서 setState 가
  아예 일어나지 않는다. `ChatArea.jsx:177` 의 `.current` 누락 오타도 **고쳤다**.
- react-query 캐시는 기본 `gcTime` 5분이라 `room_messages/<id>+cursor` 항목이 페이지마다 생겨도
  유한하다. `ChatArea` 의 `messages` 상태는 방에 머무는 동안 계속 자란다 — 세션 단위라 두되, 긴
  방이면 상한을 둘 만하다.


### 1.15 🟠 Kafka 프로듀서의 키·토픽·파티션 — 키가 거의 없다, 파티션은 전부 하나

> 점검 질문: Kafka 프로듀서의 key·topic·partition 관계에 문제가 없는가?

**지금은 문제가 드러나지 않고, 파티션을 늘리는 순간 드러난다.** 셋의 관계가 이렇다.

| | 사실 | 근거 |
| --- | --- | --- |
| 파티션 | 주 토픽(`order` `payment` `product-update` `user-created` `cart-*` `coupon-*` `chat` `outgoing-mail` `delivery-status` …)은 **아무도 만들지 않는다.** 브로커 자동 생성이고 `KAFKA_NUM_PARTITIONS` 가 없으니 cp-kafka 기본값 **1**. 명시적으로 만드는 건 `.DLT` 9종뿐이고 그건 **3**이다 | `k8s/90-dev-infra.yaml:170-185`(파티션 설정 없음), `ops/02-create-dlt-topics.sh:35,47`, `ops/13:309-312` |
| 키 | 발행 12곳 중 키를 싣는 곳은 **셋**(`DeliveryStatusPublisher` orderId, `TokenRequestKafkaPublisher`, `OutgoingMailProducer` toEmail). 사가의 핵심 토픽(`order` `payment` `coupon-use` `coupon-rollback` `cart-empty` `cart-rollback` `product-update` `user-created`)과 **`chat` 은 전부 키 없음** | 위 `kafkaTemplate.send` 목록 |
| 소비자 | `concurrency` 전부 기본 1 | §1.13 |

파티션이 하나라 키 없음이 **아직** 순서를 깨지 않는다 — 한 파티션 안에서는 발행 순서가 곧 소비
순서다. 그런데 이건 우연한 안전이다. 누군가 처리량 때문에 `order` 를 3파티션으로 만들면(DLT 는
이미 3이라 그렇게 만들 유혹이 크다) 키 없는 레코드는 스티키 파티셔너가 배치 단위로 흩뿌리고,
같은 주문의 결제 응답과 롤백이 다른 파티션에 앉아 **소비자가 롤백을 먼저 볼 수 있다.** `chat` 은
더 직접적이다 — 같은 방의 메시지가 흩어지면 화면에 뒤섞여 도착한다. 파티션을 늘린 사람은 키를
빠뜨린 발행 코드를 본 적이 없다.

**적용**: 아홉 곳에 키를 실었다. 파티션이 하나인 지금은 동작이 바뀌지 않는다.

| 토픽 | 키 | 왜 이 키인가 |
| --- | --- | --- |
| `order`, `payment` | `orderId` | 사가 순서의 단위 |
| `coupon-use`, `coupon-rollback` | `userId` | 페이로드에 orderId 가 없다. 쿠폰은 사용자 단위로 사용/롤백된다 |
| `cart-empty`, `cart-rollback` | `userId` | 장바구니 = 사용자 |
| `product-update` | `eventId`(= `order-<id>`) | 한 메시지가 주문의 상품 여러 개를 담아 상품 단위 키가 없다. 원본과 재전달이 같은 파티션으로 |
| `user-created` | `userId` | 소비자 셋(order·coupon·chat) |
| `chat` | `roomId` | 방 안의 순서가 곧 대화 |

`KafkaProducerKeyGuardrailTest`(common)가 저장소의 모든 `kafkaTemplate.send` 를 훑어 세 인자
`(topic, key, value)` 이거나 `MessageBuilder` 에 `KafkaHeaders.KEY` 가 있는지 본다. 둘을 일부러
되돌려 놓고 정확히 그 파일을 짚으며 깨지는 것을 확인했다.

**안 한 것 — 파티션 늘리기.** 키가 실렸으니 이제 늘려도 순서는 안전하지만, 그건 `concurrency` 와
함께 결정할 운영 변경이고 이미 만들어진 토픽의 파티션은 `--alter` 라 배포 스크립트도 달라진다.
지금 처리량에서는 1이 문제가 아니다. 늘릴 때는 `ops/02` 처럼 주 토픽도 **명시적으로** 만들고(자동
생성에 기대지 말고) 소비자 `concurrency` 를 파티션 수에 맞추면 된다.

**CDC 릴레이를 스케줄러와 같은 규칙으로 맞췄다 — 적용 완료.** 커넥터 4개(`*-outbox-connector-*.json`):

| 규칙 | 전 | 후 |
| --- | --- | --- |
| 토픽 | `route.by.field=aggregate_type` → `Order.events` (아무도 안 듣는다, §5 A2) | `event_type` 으로 라우팅 + `RegexRouter` 로 스케줄러와 같은 이름: `payment-request→order`, `token-update→token-u`, `payment-response→payment` |
| 키 | `aggregate_id` 이되 기본 `JsonConverter` → `{"schema":…,"payload":"123"}` 로 감싸여 **앱 프로듀서의 `"123"` 과 바이트가 다르다** → 다른 파티션 | `key.converter=StringConverter` — 스케줄러의 `StringSerializer` 와 같은 바이트 |
| 테이블 | `public.*` (아무것도 캡처 안 함, §5 A1) | `orders.payment_outbox_event`, `pay.order_outbox_event` |

그 과정에서 **pay-service 의 `aggregate_id` 가 잘못돼 있던 것**이 드러났다. `OrderOutboxHelper.saveOrderOutbox` 가
`paymentId` 를 넣는데, `PaymentRequestMessageListener` 의 멱등성 검사는 `orderId` 로 `findByAggregateId` 를
한다 — **한 번도 맞은 적이 없다**(재전달을 걸러낸 적이 없다). `orderId` 로 바꿨다. 이제 스케줄러 키·CDC 키·
멱등성 검사 키가 전부 주문 id 로 같다. `CdcConnectorGuardrailTest` 가 네 파일의 라우팅 표·키 컬럼·컨버터·
스키마를 스케줄러 규칙과 대조한다(라우트 하나를 틀려 놓고 짚는 것을 확인).

**§5 A3 도 적용 — 소비자 쪽.** 컨슈머 설정 9개의 `new JsonDeserializer<>()`(타입 없음 = `__TypeId__` 헤더에만
의존) 13곳을 `new JsonDeserializer<>(X.class, true)` 로 — 헤더가 있으면 쓰고 없으면 X 로 읽는다. pay 의
`PaymentRequestKafkaListener` 는 `Authorization` 헤더가 없으면 배치를 거부했는데(CDC 레코드는 헤더를 못 싣는다),
그 헤더는 로그에만 쓰이고 실제 토큰은 페이로드(`authorizationToken`) 안에 있다 — "헤더 또는 페이로드 토큰"으로
완화했다. 덤: coupon 의 `UserCreatedConsumer` 가 `@Header("Authorization")` 를 **필수**로 받는데 발행자
`UserCreatedProducer` 는 헤더를 싣지 않는다 → 헤더 없는 배치가 DLT 로. `required=false` 로. 
`KafkaConsumerGuardrailTest` 가 타입 없는 역직렬화기를 막는다.

#### 리밸런스 — `RebalanceSettings` + `LoggingRebalanceListener` (common)

전 컨슈머가 기본 `RangeAssignor`(eager)였다 — 멤버 하나가 바뀔 때마다 **그룹 전원이 모든 파티션을 내려놓고**
다시 받는다. 파드 하나가 롤링 배포로 교체될 때 같은 그룹의 나머지 파드까지 멈춘다. order 의 설정에는
`CooperativeStickyAssignor` 가 **주석으로** 남아 있었다 — 알았지만 켜지 않은 흔적.

- `RebalanceSettings.assignors(env)`: 기본 `[cooperative-sticky, range]`(1단계 — 옛 멤버와 섞여도 안전),
  `kafka.rebalance.protocol=cooperative` 면 `[cooperative-sticky]`(2단계). 바로 cooperative 만 두면 이 커밋의
  첫 롤링에서 Range 만 아는 옛 파드와 섞여 그룹이 깨진다. 전원이 1단계를 지난 뒤 yml 한 줄로 넘어간다.
- `LoggingRebalanceListener`: 파티션을 내려놓기 전 **동기 커밋**(넘겨받는 멤버가 같은 배치를 다시 읽지 않게),
  받을 때 파티션별 lag 기록, lost 는 커밋 없이 경고. 컨테이너 팩토리 20개 전부에 등록.
- `LoggingRebalanceListenerTest`(MockConsumer 로 커밋/미커밋 확인)와 `KafkaConsumerGuardrailTest`(그룹마다
  어사이너, 팩토리마다 리스너).

#### 사용자 정의 파티셔너 — `AggregateKeyPartitioner` (common)

키를 실어도 한 가지가 남는다. 토픽마다 키 모양이 다르다 — `order` 는 `"123"`, `product-update` 는
`"order-123"`. 기본 파티셔너는 바이트를 해시하므로 같은 주문이 토픽마다 **다른 파티션**에 앉는다. 파티션 수가
같아도 토픽 사이 공동 파티셔닝이 안 되고, 나중에 스트림 조인이나 파티션 단위 재처리를 하려면 그때 키를 전부
바꿔야 한다.

`AggregateKeyPartitioner` 는 `<접두사>-` 를 벗겨 집합체 id 만 남기고 **기본 파티셔너와 똑같은 murmur2** 로
파티션을 고른다. 그래서 (1) `"123"`·`"order-123"`·`"payment-123"` 이 같은 파티션이고, (2) 접두사 없는 키는 기본
파티셔너와 **정확히 같은** 파티션이라 — Connect 워커의 기본 파티셔너를 쓰는 CDC 릴레이와도 공동 파티셔닝된다.
키가 없으면 `UNKNOWN_PARTITION` 을 돌려 내장 스티키 파티셔닝에 맡기고(기본 동작) 토픽당 한 번 경고를 남긴다.
프로듀서 설정 맵 14개(프로듀서 7 + DLT 7)에 `PARTITIONER_CLASS_CONFIG` 로 등록했고, `KafkaProducerKeyGuardrailTest`
가 빠진 맵을 잡는다. `AggregateKeyPartitionerTest` 가 공동 파티셔닝·기본 파티셔너 동일성·null 키·분포를 고정한다.

common 은 `kafka-clients` 를 `compileOnly` 로만 받는다 — eureka/config-server 런타임으로 새지 않는다는
기존 결정을 지킨다. 파티셔너를 쓰는 모듈은 spring-kafka 로 이미 갖고 있다.


### 1.16 ✅ Avro — 스키마 레지스트리 없이, JSON 과 섞여도 안전하게 (사가 핵심 두 토픽)

> 범위: 가장 중요한 사가 핵심 두 토픽에 Avro 를 적용한다.

**제약부터.** 당시 검증 환경의 네트워크 제한으로 `packages.confluent.io` 에 접근할 수 없었다(HTTP 000). Confluent 의
`KafkaAvroSerializer` 와 스키마 레지스트리 클라이언트를 받을 수 없고, 클러스터에도 레지스트리가 없다.
Maven Central 은 열려 있어 **Apache Avro 본체**(1.11.4)와 컴파일러는 받힌다. 그래서 레지스트리 방식이
아니라 Avro 명세의 **단일 객체 인코딩**(single-object encoding)을 쓴다 — 레코드 앞에 `C3 01` 마커와
작성 스키마의 64비트 지문이 붙고, 읽는 쪽은 `SchemaStore` 로 옛 스키마를 찾아 자기 스키마와 맞춰 읽는다.
진화가 되고, 서버가 없다.

**설계 — 리스너는 DTO 를 계속 받는다.** `AvroMapping<DTO, Record>` 가 DTO↔Avro 변환을 품고,
`AvroDtoSerializer`/`AvroDtoDeserializer` 가 그 변환을 안고 전선만 Avro 로 바꾼다. 리스너·서비스는
한 줄도 안 바뀌었다. Avro 생성 클래스를 도메인까지 끌고 들어가면 스키마를 바꿀 때마다 서비스가 흔들린다.

**하이브리드 — 소비자는 JSON 도 읽는다.** `AvroDtoDeserializer` 는 마커(`C3 01`)가 있으면 Avro, 아니면
(`{` 로 시작) 예전 그대로 spring 의 `JsonDeserializer` 다. 세 경로가 JSON 으로 남아 있기 때문이다:
롤링 배포 중 옛 프로듀서, Debezium CDC 릴레이(Connect 는 Avro 컨버터 없이는 문자열), e2e 테스트의 손으로 쓴
JSON. delivery·notification 은 값을 `List<String>` 으로 받아 `JsonNode` 로 파싱하므로 그쪽엔
`AvroJsonTextDeserializer` — 토픽에 매핑이 있고 Avro 면 JSON 문자열로 바꿔 주고, 아니면 바이트 그대로.
**소비자 코드는 손대지 않았다.**

| 어디 | 무엇 |
| --- | --- |
| `common/src/main/avro/PaymentRequest.avsc`, `PaymentResponse.avsc` | 스키마가 진실. 생성 클래스(`com.project.common.avro.*`)는 `build/generated/avro` 에, 커밋하지 않는다 |
| `common/build.gradle` `generateAvro` | Gradle Avro 플러그인은 플러그인 포털에서 404 라 `avro-compiler` 의 Ant `SchemaTask` 로 직접. `stringType=String` |
| `common/.../kafka/avro/` | `AvroWire`(마커), `AvroMapping`, 매핑 둘, 직렬화기 1 + 역직렬화기 2, `AvroMappings`(토픽 → 매핑) |
| order `paymentRequestProducerFactory`, pay `paymentResponseProducerFactory` | 값 = `AvroDtoSerializer` (인스턴스로 넘기므로 맵의 `JsonSerializer` 는 무시된다) |
| pay·order·coupon 의 DTO 소비자, delivery·notification 의 문자열 소비자 | 하이브리드 역직렬화기 |

스키마 결정 둘. `amounts` 는 `decimal` 논리 타입 대신 **문자열**(`BigDecimal.toPlainString`) — 자릿수를
스키마에 고정하지 않고도 손실이 없다(테스트가 `"59.98"` 그대로임을 본다). `createdAt` 은
`timestamp-millis`(UTC) 로 `Instant` ↔ `LocalDateTime`. 공유 enum(`PaymentStatus`)은 두 파일에 **같은
정의**를 둔다 — `SchemaTask` 는 파일마다 파서를 새로 만들어 이름 참조가 안 된다.

`AvroWireTest` 6개: 왕복 무손실(둘), 옛 `JsonSerializer` 바이트 수용, 헤더 없는 손 JSON 수용, **스키마 진화**
(필드가 적은 옛 작성 스키마의 레코드를 `SchemaStore` 로 읽고 새 필드는 default), 문자열 소비자 변환의
토픽별 개입. `AvroWiringGuardrailTest` 가 일곱 설정 파일이 Avro 로 묶여 있는지 본다 — 프로듀서를 JSON 으로
되돌려도 하이브리드 소비자가 조용히 받아 주므로, 세지 않으면 전선이 돌아간 것을 아무도 모른다.

**전 토픽으로 확장 — 적용 완료.** 소비자가 있는 토픽 전부(10개)가 Avro 다. 스키마 8개를 더했다:
`ProductUpdateRequest`(상품 배열 포함, DTO 의 오타 `autorizationToken` 을 그대로 — JSON 하위호환은 필드명이
곧 키다), `UserCreatedRequest`, `CartEmptyRequest`, `CartRollbackRequest`, `CouponUseRequest`,
`CouponRollbackRequest`, `OutgoingMailRequest`(`params` 는 `map<string>`), `ChatMessage`. 매핑 7개는 common 에
한 틀로 생성했고, chat 은 DTO(`MessageDto`)가 chat-service 것이라 매핑도 그쪽(`ChatMessageAvroMapping`)이다.

그래서 문자열 소비자용 변환을 **DTO 없이** 하도록 바꿨다 — `SpecificRecordJson` 이 스키마를 따라가며 유니온을
벗기고 enum 은 이름으로, `timestamp-millis` 는 ISO 문자열로 낸다(예전 Jackson 출력과 같은 모양이라
`event.get("customerId")` 같은 `JsonNode` 접근이 그대로 맞는다). `AvroDecoders`(토픽 → 디코더)가 스키마만으로
등록되므로 chat 처럼 DTO 가 common 밖에 있어도 notification 이 읽는다.

덤: chat 소비자 팩토리가 `ConsumerFactory<String, ChatMessage>` 로 선언돼 있었는데 리스너
(`MessageConsumer`)는 `MessageDto` 를 받는다 — `__TypeId__` 헤더가 `MessageDto` 를 가리켜서만 동작하던
것이다. Avro 는 헤더가 없으니 선언을 실제 타입으로 맞췄다.

**Avro 로 옮기지 않은 것 — 소비자가 없는 토픽.** `token-u`(order 가 문자열로 발행, 듣는 곳 없음 — 2026-10-14 발행 쪽까지 지웠다: 아웃박스 행을 쓰는 곳도 없었고 토큰 원문을 싣는 모양이었다, `MessageDtoNoTokenGuardTest`),
`delivery-status`(delivery 가 Map→JSON 으로 발행, 듣는 곳 없음), `user-presence`(notification 이 듣지만
발행하는 곳 없음), user-service 의 `AlarmEventDto` 소비자(발행자 주석 처리). 죽은 토픽에 스키마를 붙이면
살아 있는 것처럼 보인다. 살릴 때 다섯 자리를 채우면 된다. 프로듀서 배선 파일 6개 + 소비자 8개를
`AvroWiringGuardrailTest` 가 대조하고, 스키마 파일 수 = `AvroDecoders` 등록 수 = `AvroMappings` + 2(chat,
order-saga-state — §1.17) 를 고정한다. `AvroWireTest` 10개.

### 1.17 ✅ Kafka 트랜잭션 — 있다고 믿었지만 없던 보장, 그리고 Streams 를 둘 자리

> 범위: Kafka Streams 적용을 염두에 두고 트랜잭션을 쓰는 프로듀서·컨슈머를 평가해 권고안을 정하고, 그대로 적용했다.

**평가 — 트랜잭셔널 프로듀서 14개 중 진짜 트랜잭션이 필요한 것은 4개.** 이 저장소에는 `KafkaTransactionManager` 도
`executeInTransaction` 도 없다. 트랜잭셔널 프로듀서가 동작한 이유는 전부 JPA `@Transactional` 안에서 `send()` 가
불렸기 때문이다 — spring-kafka 가 그 순간 Kafka 트랜잭션을 열고 JPA 커밋의 `afterCommit` 에서 Kafka 를 커밋한다
(best-effort 1PC). 문제는 그 순서다.

- **아웃박스 릴레이(order `order`·`token-u`, pay `payment`)** — 릴레이는 발행을 **트랜잭션 안에서 기다린 뒤**
  COMPLETED 를 찍는다(`PaymentRequestKafkaPublisher`). 트랜잭셔널 프로듀서에서 `send().get()` 이 돌려주는 ack 는
  "트랜잭션 안에 썼다"이지 "보인다"가 아니다. 그 뒤 DB 가 커밋되고 **그다음에** Kafka 가 커밋된다 — 그 사이에
  죽으면 아웃박스는 COMPLETED 인데 레코드는 중단(abort)된다. 트랜잭션이 오히려 **유실 창**을 만들고 있었다.
  레코드 하나를 보내는 릴레이에 트랜잭션이 줄 것은 없다. → 트랜잭션 제거, `enable.idempotence=true` + `acks=all`.
  이제 ack 는 "복제 완료"고, ack 뒤 DB 커밋 전에 죽으면 다음 폴링이 재발행한다(at-least-once, 소비 측 원장이 막는다).
- **단건 발행(user `user-created`, coupon `coupon-use`)** — 같은 이유로 제거. 메일 프로듀서는 이미 그렇게 하고 있었다.
- **order 의 사가 보상 4개(coupon-rollback·cart-rollback·product-update·cart-empty)** — 결제 응답 리스너가 한
  트랜잭션 안에서 **두 토픽 이상**에 보낸다. 여기는 원자성이 의미가 있다. 유지하되 id 를 고친다.
- **`transactional.id=UUID.randomUUID()` 는 펜싱을 무력화한다.** 이 id 는 "같은 프로듀서의 다음 화신"을 식별하는
  값이다. 파드가 죽었다 살아나 같은 id 로 `initTransactions()` 하면 브로커가 epoch 를 올려 좀비의 미완 트랜잭션을
  중단시킨다. 기동마다 새 UUID 면 매번 처음 보는 프로듀서라 아무것도 펜싱되지 않고, 브로커에는 다시는 안 쓰일
  트랜잭션 상태가 `transactional.id.expiration.ms`(기본 7일)까지 쌓인다. 설정은 걸려 있는데 그 설정의 보장은
  없는 상태 — §5 의 첫 원인 그대로다. → `KafkaTransactionalIds.stable("order-saga-cart-rollback")` =
  `<접두사>-<HOSTNAME>`. 같은 파드의 재시작(좀비가 생기는 바로 그 경우)에서 같고, 팩토리마다 접두사가 달라 같은
  JVM 의 프로듀서 둘이 서로를 펜싱하지 않는다.
- **소비자 15개는 전부 DB 를 갱신한다.** DB 커밋과 Kafka 오프셋 커밋이 원자적일 수 없으므로 exactly-once 는
  성립하지 않는다. 정답은 이미 있는 것 — `processed_event` 멱등 원장이다. Streams 를 여기에 끼우면 얻는 것 없이
  두 트랜잭션 경계가 생긴다.

그래서 **Streams 는 기존 리스너가 아니라 새 소비자에게** 준다. 이 저장소에서 EOS 가 성립하는 유일한 모양은
Kafka→Kafka 다.

**`saga-projection` — 주문 결제 사가의 읽기 모델 (Kafka Streams, exactly_once_v2).** `order` 와 `payment` 를
주문 id 로 재키잉해 합치고 한 집계로 접는다. 주문마다 `REQUESTED → COMPLETED / FAILED / CANCELED`, 그리고
응답이 요청보다 먼저 보이면 `ORPHAN_RESPONSE`(두 토픽은 순서가 독립이라 정상 범위 — 오래 남으면 요청 릴레이가
죽은 것). `requestCount ≥ 2` 는 릴레이의 at-least-once 재발행이 실제로 일어났다는 계측이다. 상태는 RocksDB +
변경 로그이고 `order-saga-state` 토픽으로도 나간다. `GET /api/saga/{orderId}` 가 저장소를 연다.

| 어디 | 무엇 |
| --- | --- |
| `common/src/main/avro/OrderSagaState.avsc` | 스키마가 곧 읽기 모델. `authorizationToken` 은 **없다** — 조회 API 로 노출되므로 자격증명이 들어가면 안 된다 |
| `common/.../kafka/avro/AvroSpecificSerde` | Streams 상태·출력용 레코드 serde. 형식은 §1.16 과 같은 단일 객체 인코딩이라 다른 서비스의 하이브리드 역직렬화기가 출력을 그대로 읽는다 |
| `saga-projection/.../topology/SagaTopology` | 입력은 `AvroDtoDeserializer`(Avro·JSON 둘 다), 재키잉(`"order-5"`/`"5"` → `"5"`), `merge` → `aggregate` → KTable → `to` |
| `SagaEvents`, `SagaStateReducer` | 델타 변환과 접기 규칙 — 순수 함수라 브로커 없이 검증 |
| `KafkaStreamsConfig` | `exactly_once_v2`, `auto.offset.reset=earliest`(과거 주문도 접는다), `replication.factor=-1`(브로커 기본값 — 브로커 하나인 dev 에서 3 을 고정하면 내부 토픽 생성이 실패한다), `num.standby.replicas`, 스레드 사망 시 SHUTDOWN_CLIENT + 로그 |
| `SagaQueryService` | 대화형 조회. **다른 파드로 포워딩하지 않는다** — 파드 IP 로 HTTPS 를 걸면 SAN 검증에 실패한다(k8s/48 의 EUREKA_INSTANCE_HOSTNAME 과 같은 이유). 대신 대기 복제본(`enableStaleStores`)에서 로컬로 답하고 `stale` 을 표시한다. 사본이 없으면 503 + `Retry-After` |
| `KafkaStreamsHealthIndicator` | Boot 는 Streams 헬스를 자동 구성하지 않는다. readiness 그룹에 넣어 클라이언트가 내려가면 트래픽에서 빠진다 |
| `SagaDeserializationExceptionHandler` | 입력 레코드를 못 읽었을 때. 예전에는 기본값(LogAndFail)이라 깨진 레코드 하나에 스레드가 죽고, 재시작해도 같은 오프셋에서 또 죽었다. **모르는 작성 스키마**(`MissingSchemaException`, 이 파드보다 새 프로듀서)면 멈춘다 — 건너뛰면 EOS 로 오프셋이 커밋되어 롤링 중 새 파드도 다시 읽지 않고 프로젝션이 그 기록을 잃는다. **그 밖의 깨진 레코드**는 건너뛰고 좌표를 남긴다. 계수기 `saga_projection_deserialization_failures_total{topic,action}` + 경보 둘(docs/55 §8) |
| `k8s/49-saga-projection.yaml` | replicas 2 = 활성 1 + 대기 1. `SAGA_APPLICATION_SERVER=$(POD_IP):8190`(파드마다 달라야 한다), 상태는 emptyDir. `kustomization`·ops/13·ops/19 에 등록 |
| `ops/02-create-dlt-topics.sh` | `order-saga-state` 를 **컴팩션으로** 만든다(아래). `ops/06` 6번 항목이 배포 후 확인 |

**검증.** `SagaTopologyTest` 7개(TopologyTestDriver): 요청→응답이 출력 토픽에 REQUESTED·COMPLETED 두 변경으로 나가고
저장소가 최종 상태, 재키잉, **옛 `JsonSerializer` 바이트도 같은 상태로**, 고아 응답, 주문 id 없는 레코드 폐기, 출력이
Avro 이고 토큰 문자열이 없음. `SagaStateReducerTest` 7개(늦은 응답이 이긴다, `updatedAt` 은 뒤로 가지 않는다 등).
`KafkaTransactionGuardrailTest`: `TRANSACTIONAL_ID_CONFIG` 는 `KafkaTransactionalIds.stable("<접두사>")` 로만, 접두사는
저장소 전체에서 유일 — UUID 를 되돌려 넣어 실패하는 것을 확인했다.

**`order-saga-state` 컴팩션 — ops/02 로 처리.** Streams 는 싱크 토픽(`to()`)을 만들지 않는다. 내부 토픽(리파티션·
변경 로그)만 스스로 만들고 — 변경 로그는 이미 컴팩션이다 — 싱크는 브로커 자동 생성에 맡겨지는데 그 기본 정책은
`delete` 다. 증상이 없다는 게 문제다: 토픽은 생기고 레코드도 쌓이며, `retention.ms`(기본 7일)가 지나서야 과거
주문의 상태가 조용히 사라진다.

더 고약한 순서가 하나 있다. **앱이 먼저 뜨면 토픽은 이미 존재한다.** 그 뒤 `--create --if-not-exists` 는 "성공"
으로 끝나고 정책은 여전히 delete 다 — §5 의 "적용됐다고 믿지만 아닌" 패턴 그대로다. 그래서 ops/02 는 존재
여부만 보지 않고 **`cleanup.policy` 를 실제로 읽어 다르면 `kafka-configs --alter` 로 고친다**(`FIX_CONFIGS=0`
이면 경고만). 설정 셋 다 이유가 있다:

| 설정 | 왜 |
| --- | --- |
| `cleanup.policy=compact` | 주문마다 최신 상태 하나 |
| `segment.ms=86400000` | 컴팩션은 **활성 세그먼트를 건드리지 않는다.** 저트래픽 토픽에서 기본값(1GB / 7일)이면 한 세그먼트가 몇 주씩 열려 있다 — "컴팩션을 켰는데 왜 안 줄지"의 거의 유일한 원인 |
| `min.cleanable.dirty.ratio=0.1` | 기본 0.5 는 "중복이 절반은 돼야 청소". 주문당 2~3회 갱신이라 그 비율에 잘 닿지 않는다 |

파티션 수는 **입력 토픽(`order`)에서 읽어 맞춘다.** 지금은 단순 싱크라 달라도 동작하지만, 이 토픽을 KTable 로
다시 읽어 조인하려는 순간 공동 파티셔닝이 요구된다(Streams 는 파티션 수가 다르면 `TopologyException` 으로
거부한다). DLT 기본값 3을 그대로 썼으면 입력이 1인 지금 이미 어긋난다.

가짜 브로커(스텁 CLI)로 일곱 시나리오를 돌렸다: 신규 생성, delete→compact 교정, **재실행 시 무변경**, `FIX_CONFIGS=0`,
파티션 불일치 경고, `DRY_RUN`, `SKIP_STATE`. 재실행 시나리오에서 실제 버그가 하나 나왔다 — 정책을 읽는 정규식이
`.*cleanup\.policy=` 로 시작해 탐욕적 매칭이 `synonyms={... DEFAULT_CONFIG:log.cleanup.policy=delete}` 의 **마지막**
값을 잡았다. 이미 compact 인 토픽도 매번 "delete → compact 로 바꿨다"고 보고했다. 줄 처음에 고정해 고쳤다.
`ConfigurationGuardrailTest` B-38 이 앱의 `saga-projection.topics.state` 와 스크립트의 `STATE_TOPIC` 기본값이
갈라지는 것을 막고, `ops/06` 6번 항목은 존재가 아니라 **컴팩션인지**를 본다.

**하지 않은 것 / 확인하지 못한 것.**
- **브로커에 붙여 보지 않았다.** TopologyTestDriver 는 EOS 를 흉내 내지 않는다 — 여기서 검증한 것은 접기 규칙과
  배선이다. 트랜잭션·펜싱·대기 복제본 복구는 클러스터에서 처음 돈다. ops/02 의 상태 토픽 경로도 스텁 CLI 로만
  돌렸다: 명령 조립과 분기는 확인했지만 실제 브로커의 응답 형식은 아니다.
- **앱보다 먼저 돌리는 편이 낫다.** 이미 delete 정책으로 지워진 상태는 정책을 고쳐도 돌아오지 않는다.
- 입력이 파티션 하나라 태스크도 하나다. §1.15 대로 파티션을 늘리면 태스크가 늘고, 그때 레플리카 > standby+1 이면
  사본 없는 파드가 생긴다 — 둘을 같이 올릴 것. 상태 토픽의 파티션은 **줄일 수 없어** 재생성이 필요하다.
- 게이트웨이 라우트는 추가하지 않았다. 내부 망에서 `saga-projection:8190` 으로 연다.


---

## 2. 모듈별 상세 — 🟠 잘못된 동작 · 🟡 비효율/정리

§1 에 올린 것은 제외. "확인" 표시는 내가 파일을 열어 본 것.

### 2.1 user-service

| # | 심각도 | 위치 | 무엇 | 확인 |
| --- | --- | --- | --- | --- |
| 1 | 🟠 | `NotificationService.java:44,48-52,68-70` | 싱글턴 빈의 `private String sessionId` 를 가변 공유 필드로 쓴다. B 가 SSE 접속 직후 A 가 중복 로그인하면 A 의 "Logout" 알림이 **B 의 세션으로** 간다 | |
| 2 | 🟠 | `ReviewService.java:153-156, 249-251` | 남이 먼저 리뷰한 상품에 두 번째 리뷰 → `findFirst().get()` `NoSuchElement`. 첫 리뷰는 `0.0F/0 = NaN` 을 `updateRating` 으로 보낸다 | |
| 3 | 🟠 | `UserServiceImpl.java:212-224` + `UserController.java:166-183` | 현재 비밀번호가 틀리면 아무것도 안 하고 **"Password updated" 200** | |
| 4 | 🟠 | `AuthLoginFilter.java:83-89` | `catch (Exception)` 이 `AuthenticationException` 까지 삼켜 `null` 반환 → `unsuccessfulAuthentication` 이 절대 호출되지 않는다. docs/27 §13 의 잠금 안내 메시지가 사용자에게 닿을 길이 없고 401 대신 본문 없는 400 | 확인 |
| 5 | 🟠 | `JwtAuthFilter.java:87-96` + `JwtUtils.java:204-215` | 만료 검사가 주석 처리돼 있고 `getIdFromJwtToken` 이 `ExpiredJwtException` 을 잡아 subject 를 돌려준다 — **서비스 자체는 만료 토큰을 유효로 취급**(게이트웨이 경유 시만 막힘) | |
| 6 | 🟠 | `RefreshTokenRepository.java:50`, `JwtUtils.java:319` | 단위 오류 둘: `expire(key, now+ms, MILLISECONDS)` → TTL ≈ 55년(Redis 키가 영원히 쌓인다); `setMaxAge(ms)` → 쿠키 38년. `OAuth2SuccessHandler:87` 은 `/1000` 을 해서 의도가 드러난다 | 확인 |
| 7 | 🟠 | `WishItemService.java:49-50` | `x.getProductId() == id` — `Long` 참조 비교. TSID 는 캐시 범위 밖이라 항상 false → 위시리스트 중복 무한 | 확인 |
| 8 | 🟠 | `UserServiceImpl.java:240-253`, `PasswordResetToken` | 재설정 토큰 **평문 저장**(`VerificationToken` 은 해시로 고쳤는데 여기만 남음), 요청마다 새 행 + 기존 무효화 없음 → `findByUser` `Optional` 이 2건에서 예외 | |
| 9 | 🟠 | `AuthController.java:429-440` | 미가입 이메일에 500, 가입은 200 → `forgot-password` 로 가입 여부 열거. 바로 아래 `resend-verification` 은 응답을 통일해 놨다 | |
| 10 | 🟠 | `AuthController.java:496-508` | `verify-2fa-login`: subject(이메일)로 `findByUsername` → 항상 null → **NPE 500.** 정상 동작한 적이 없어 보인다. 시도 횟수 제한도 없음 | ✅ 엔드포인트 삭제, 로그인 2단계는 `/public/verify-2fa`(대기 쿠키·5회 제한) 하나 — docs/27 §12b |
| 11 | 🟡 | `SecurityConfig.java:81` | `CorsConfigurationSource` 를 생성자 주입하는데 그 타입의 빈이 없다(`CorsConfig` 전체 주석). 쓰이지도 않는다(`cors.disable()`). 기동이 되고 있으니 어딘가에서 오지만 지워야 한다 | |
| 12 | 🟡 | `AdminController.java:39-42` | `findAll()` 로 users 전체를 엔티티째 반환 | |
| 13 | 🟡 | `security/RedisSessionManager` | 이름과 달리 JVM 내 `HashMap` — 레플리카 2개에서 중복 로그인 감지가 파드마다 다르다 | |

죽은 코드(호출부 0): `UserService.getUser(String)`, `getUsersFromSessionRegistry`, `createOutboxEvent`,
`deleteUser(User)`, `JwtUtils.isRequestAuthorized/generateJwtCookie/getCleanJwt*Cookie`(되살리면
path 불일치로 쿠키가 안 지워진다), `CsrfController`, `message/OutboxScheduler` 인터페이스.

### 2.2 catalog-service

| # | 심각도 | 위치 | 무엇 | 확인 |
| --- | --- | --- | --- | --- |
| 1 | 🔴 | `CategoryService.java:36-39` | `null != categoryRepository.findByCategoryName(...)` — 반환형이 `Optional` 이라 **항상 true** → 카테고리 생성이 영구히 "이미 존재" 400 | 확인 |
| 2 | 🔴 | `ProductIndexService.java:49,61` + `ProductDocument.java:16` | 실시간 색인이 `@Document(indexName="products")` 로 쓰는데 검색은 `product-alias`, 배치는 `<ts>-products`. 인덱스가 다르다 → 등록·삭제가 **다음 수동 sync 까지 검색에 반영되지 않는다**(이 클래스가 메우려던 바로 그 간극) | 확인 |
| 3 | 🔴 | `ProductService.java:669-677` | 기존 상품에 색상 변형 추가 시 `product.setDetails/setQuestions` 로 **영속 부모의 `orphanRemoval` 컬렉션을 통째로 교체** → 기존 상세/Q&A 삭제 또는 Hibernate 예외 | 코드 경로 확인 |
| 4 | 🟠 | `ProductService.java:972-990` + `:1031-1038` | 재고 부족이면 `null` 을 돌려주는데 그 직전 `updateSoldValue` 가 이미 `sold` 를 올려 저장했다 → 판매량·재고 영구 불일치, 리스너는 멱등 레코드를 저장해 재처리 기회도 없앤다 | |
| 5 | 🟠 | `SearchService.java:174-176` | SKU 필터 결과가 **비면 조건 자체를 뺀다** → 해당 가격대 상품이 없으면 전체 목록 반환. ES 분기(`:190-192`)는 `productId.eq(-1L)` 로 맞게 한다 | |
| 6 | 🟠 | `ProductService.java:155-185` | `@Cacheable(key="'warmup'")` 에 `pageSize` 가 없고 TTL 도 없다(`permanentCacheConfig`) → 첫 호출이 4건이면 이후 20건 요청도 4건 | |
| 7 | 🟠 | `ProductService.java:919-1011` | 자기호출로 무력화된 `@CachePut`/`@CacheEvict` 묶음 + 진입점 evict 에 `products`·`product` 누락 → **품절 재고가 상세 페이지에 무기한 1로** | |
| 8 | 🟡 | `ElasticsearchBatchConfig.java:126-147` | 컬렉션 4개 `JOIN FETCH` + `JpaPagingItemReader` = 메모리 페이징(HHH90003004). 5만 건에서 O(n²) | |
| 9 | 🟡 | `SearchController.java:74-112, 137-147` | `/{entity}/all` 8개가 ES 를 무제한 덤프, `/search/category` 는 `pageSize` 검증 없이 엔티티 직렬화 | |
| 10 | 🟡 | `ProductSkuRepository.java:45-53` | 필터가 전부 null 이면 `where true` → 전 product_id 를 IN 절로 | |
| 11 | 🟡 | `ElasticsearchBatchConfig.java:335-438` | 롤오버가 `-products` 만 별칭에 붙이고 옛것 하나만 지운다. 나머지 7종 인덱스는 sync 마다 쌓인다 | |
| 12 | 🟡 | `ProductSubcategory.java:52,57` | 저장소에서 **유일하게 EAGER 인 연관 둘**(`@ManyToOne` 에 `fetch` 미지정 = 기본값 EAGER). `subcategory` 는 상품 목록 매핑마다 발화해 **서로 다른 서브카테고리 수만큼 SELECT** 를 낸다 — `Subcategory` 클래스에 `@BatchSize` 가 없어 배치되지 않는다. `product` 는 이미 영속성 컨텍스트에 있는 부모를 가리키는 역참조라 대개 쿼리가 나가지 않지만, `ProductSubcategory` 를 단독 조회하면 그때 발화한다. `Product` 의 LAZY 컬렉션 넷에는 `@BatchSize(20)` 가 꼼꼼히 붙어 있는데(`Product.java:78-104`) **그 다음 한 홉이 비어 있다** | 확인 |

### 2.3 order-service · pay-service

| # | 심각도 | 위치 | 무엇 | 확인 |
| --- | --- | --- | --- | --- |
| 1 | 🟠 | `OrderDataService.java:312` | 결제 아웃박스의 `paymentStatus` 가 **항상 `COMPLETED` 하드코딩** → `order` 토픽에 `CANCELED` 가 실린 적이 없고 `cancelPayment → OrderSaga.rollback → 쿠폰·카트 롤백` 전 구간이 **도달 불가**. PayPal 창 취소는 400 으로 끝나고 아무것도 기록되지 않는다 | 확인 |
| 2 | 🟠 | `PaymentResponseKafkaPublisher.java:58,62` | `TokenContext.getToken()` 은 HTTP 스레드의 ThreadLocal — 스케줄러 스레드에서는 null → 헤더가 문자열 **`"Bearer null"`** 로 나간다. order 측 null 검사를 통과해 `"null"` 토큰이 하류 카트 비우기까지 전파된다 | 확인 |
| 3 | 🟠 | `PaymentRequestMessageListener.java:31,45` ↔ `OrderOutboxHelper.java:72` | 멱등 조회는 `aggregateId = orderId` 로 하는데 저장은 `paymentId` 로 한다 → **한 번도 참이 안 된다.** 재전달마다 아웃박스 행 + `payment` 메시지가 하나씩 더 생긴다 | |
| 4 | 🟠 | `PaymentService.java:30` | `persistPayment` 에 `@Transactional` 이 없어 결제 행과 아웃박스 행이 **다른 트랜잭션** → 아웃박스 저장 실패 시 돈은 캡처됐는데 응답이 영영 안 나간다 | |
| 5 | 🟠 | `PaymentOutboxScheduler.java:57,82` + `KafkaProducerConfig.java:55` | 트랜잭셔널 프로듀서 + JPA 동기화 → **Kafka 커밋이 DB 커밋 뒤**에 일어난다. `.get()` 은 append 확인일 뿐. DB 가 COMPLETED 로 커밋된 직후 Kafka 커밋이 실패하면 메시지는 `read_committed` 컨슈머에게 영원히 안 보이고 행은 다시 안 집힌다. 스케줄러 주석이 보장한다고 적은 것과 순서가 반대다 | |
| 6 | 🟠 | `OrderCacheService.java:78` + `RedisCacheConfig.java:155` | `order` 캐시가 `disableCachingNullValues` 인데 `getOrder` 는 남의 주문/없는 주문에 null 을 돌려주는 것이 정상(docs/27) → `IllegalArgumentException` → 의도한 404 대신 **400 + 캐시 내부 메시지** | 확인 |
| 7 | 🟠 | `OrderController.java:109-118` + `OrderDataService.java:96` | `/orders` 의 `filter` 가 null 이면 `filter.isEmpty()` NPE, `?filter=all` 이면 `IllegalState` — 주문이 1건 이상인 사용자는 **500** | 확인 |
| 8 | 🟠 | `OrderSaga.java:75-94, 195-220` | 순서 뒤집힘(`CANCELED` 후 `COMPLETED`)을 경고 로그로만 처리하고 커밋 — 돈은 캡처됐는데 주문은 취소 상태 | |
| 9 | 🟡 | `PaymentResponseKafkaListener.java:26-27` | 배치 리스너인데 `@Header String` 으로 받는다 — 2건 이상이면 `"Bearer a,Bearer b"` 가 될 가능성. E2E 는 항상 1건씩만 보낸다 | 의심 |
| 10 | 🟡 | `KafkaProducerConfig.java:55…` | `TRANSACTIONAL_ID_CONFIG = UUID.randomUUID()` 기동마다 — 좀비 펜싱 무력화, 브로커에 id 상태 누적 | |
| 11 | 🟡 | `OrderSaga.java:155-169, 185` + `CartService.java:303` | 보상이 **수량을 1로 강제**한다(카트 롤백이 모든 상품을 1개로). 지금은 #1 때문에 도달 불가 | |
| 12 | 🟡 | `OrderController.java:43-53` | `createOrder` 가 null 을 돌려주는 정상 실패(빈 장바구니)에서 NPE → `{"message":null}` 400 | |

### 2.4 notification-service · delivery-service

| # | 심각도 | 위치 | 무엇 | 확인 |
| --- | --- | --- | --- | --- |
| 1 | 🔴 | `MailMessageRepository.java:39-47`, `SmsDispatcher.java:61-84` | **재시도 백오프가 없다.** 선점 조건이 `attempts < max` 뿐이고 `next_attempt_at` 컬럼이 없다. 5초 간격 5회 = **25초 만에 FAILED.** 이 서비스가 `max-attempts=5` 를 고른 근거인 그레이리스팅(수신 서버가 첫 발신을 4xx 로 거절하고 5분 이상 기다림)에서는 5회 전부 거절 → 그 도메인 사용자는 가입 확인 메일을 영영 못 받는다 | 확인 |
| 2 | 🟠 | `NotificationService.java:57` vs `SmsDispatcher.java:100-101` | `create(..., smsParams)` 의 마지막 인자가 본문에서 참조되지 않는다 → 주문번호가 빠지고 "결제가 완료되었습니다. 결제 완료" | |
| 3 | 🟠 | `KoreaPostCarrierAdapter.java:234-260` | 택배사 시각(KST)을 타임존 변환 없이 `LocalDateTime` 으로 받아 UTC 로 저장 → 타임라인이 9시간 밀린다 | 변환 부재 확인 / KST 전제는 의심 |
| 4 | 🟠 | `DeliveryService.java:120-132`, `Shipment.java:164-173` | 송장 재등록이 기존 송장을 무조건 덮어쓰고 상태를 `INFO_RECEIVED` 로 되돌린다; UNIQUE 위반은 500 | |
| 5 | 🟠 | `NotificationService.java:197-210` | `markAllRead` 는 최근 500건만, 배지는 전체 카운트 → 600건 이상이면 배지가 영원히 남는다 | |
| 6 | 🟠 | `VerificationService.java:198-202` | `purgeExpired()` 를 부르는 곳이 없다(`@Scheduled` 누락) → 전화번호 원장이 무한 증가 | |
| 7 | 🟡 | `CarrierConfig.java:77-82` + `V3__seed_carriers.sql` | 시뮬레이터가 `matchIfMissing=true` 로 기본 활성 — 운영에서 끄는 것을 잊으면 실물이 창고에 있는데 "배달완료" | |
| 8 | 🟡 | `SmsMessage.java:19,33-41` | `SMS_BYTE_LIMIT=90`(LMS 경계, 요금 3~4배) 이 선언만 되고 쓰이지 않는다 | |
| 9 | 🟡 | `SmsDispatcher.java:124-129`, `MailDispatcher.java:122-127` | 상한 도달 시 `recordAttempt` 를 두 번 불러 카운터가 +1 부풀려진다 | |
| 10 | 🟡 | `TrackingPoller.java:83-88` | "폴링 중단" 주석과 달리 `recordSyncFailure()` 만 부른다 — 어댑터 없는 택배사가 12시간마다 영원히 선점된다 | |
| 11 | 🟡 | `NotificationPreferenceRepository.java:13` | 번호 중복 등록 검사 메서드가 선언만 — 두 계정이 같은 번호로 2FA 가능 | |

### 2.5 cart-service · coupon-service · chat-service

| # | 심각도 | 위치 | 무엇 | 확인 |
| --- | --- | --- | --- | --- |
| 1 | 🔴 | `ChatMessageRepository.java:23,25` + `SequenceGeneratorService.java:27-31` | 메시지 `_id` 가 **문자열 증가 시퀀스**("1","2",…)인데 정렬·커서가 사전순 → 12건이면 `9,8,…,2,12,11,10,1` 로 뒤섞이고 커서 `"10"` 다음 페이지는 `"1"` 하나 | 확인 |
| 2 | 🟠 | `CouponUseProducer.java:29-40` + `CartService.java:250-259` | `CouponUseRequest` 에 토큰을 안 채운다 → cart 의 afterCommit Feign 이 401 → **캐시가 옛 값으로 남고**(TTL 없음) 레코드는 DLT | |
| 3 | 🟠 | `CartService.java:303` | `cartRollback` 이 모든 상품 수량을 1로 (§2.3 #11 과 짝) | |
| 4 | 🟠 | `CouponService.java:53,202,226-231` | `startDate/endDate` 를 어디에서도 비교하지 않는다 — 만료 쿠폰이 영원히 유효 | |
| 5 | 🟠 | `CouponUseMessageListener.java:30-35`, `CartRollbackMessageListener.java:27-32` | null 분기에서 `log.error` 후 return 없이 흘러내려 NPE → DLT | |
| 6 | 🟠 | `ChatService.java:126-136` + `MessageMapper.java:46` | `chat.member` 행이 없는 사용자(컨슈머가 `latest` 오프셋이라 기동 전 가입자)의 메시지는 `createdAt` null → 컨슈머 언박싱 NPE → 저장도 브로드캐스트도 안 됨 | |
| 7 | 🟠 | `ChatService.java:141-160, 204-217` | 방 생성이 JPA → Redis → 참여자 순인데 참여자 저장 NPE 로 롤백돼도 Redis 는 남는다 → 유령 방; 반대로 Redis 초기화 시 `room_id NULL` 행 무한 누적 | |
| 8 | 🟠 | `StompHandler.java:60-77` | SUBSCRIBE 인가가 `roomId` 헤더 존재만 본다 — `destination` 과 대조하지 않아 타인 방 구독 가능. 지금은 "공개 채팅방" 전제라 피해가 작지만 1:1 방을 만드는 순간 유출 경로 | 확인 |
| 9 | 🟠 | `CouponService.java:174-190` | 이메일 분기에서 `Long.parseLong(request.getUserId())` 로 이메일을 파싱 → 이메일로는 쿠폰 발급 불가 | |
| 10 | 🟡 | `CartService.java:73-99` | 카트 캐시를 TTL 없이 `set` — read-through 와 무효화의 경쟁으로 삭제된 카트가 되살아날 수 있다 | |
| 11 | 🟡 | `ChatService.java:145-158` | 방 생성 락 키가 `roomName` 인데 식별자는 UUID — 중복을 막지 못한다; leaseTime 5초 초과 시 `unlock` 이 예외 | 의심 |

작게: `FeignErrorDecoder`(cart) 가 본문을 두 번 읽고 400 에 `null` 을 반환해 NPE 로 둔갑;
`MessageMongoEventListener`(chat) 는 `@Component` 가 없는 죽은 코드; `deleteCartItem`(cart) 은
도달 불가인데 총액에서 수량을 곱하지 않는다.

### 2.6 common · gateway-server

#### 🔴 게이트웨이가 방금 넣은 신원 헤더를 스스로 지운다 — 이 리뷰의 최대 발견

`application-local.yml:128-135` 의 주석은 이렇게 적혀 있다:

> default-filters 는 모든 라우트에 적용되고 **라우트 필터보다 먼저 실행되므로**,
> 여기서 지운 뒤 JwtAuthorization 이 검증된 값을 다시 넣는 순서가 된다.

**그 전제가 틀렸다.** Spring Cloud Gateway 4.2.0 의 `RouteDefinitionRouteLocator` 는
`loadGatewayFilters` 에서 필터에 **자기 리스트 안의 인덱스+1** 을 order 로 부여하고
(바이트코드: `new OrderedGatewayFilter(f, i + 1)`), default 목록과 라우트 목록을 각각
그렇게 매긴 뒤 하나로 합쳐 `AnnotationAwareOrderComparator.sort` 한다. 즉 두 목록의
order 가 **1 부터 각자 다시 시작**한다.

default-filters: `Dedupe=1`, `RemoveRequestHeader=User-Id=2`, `User-Email=3`, `User-Role=4`,
`RequestRateLimiter=5`.

**cart-service** (`JwtAuthorization=1`, `RemoveCookie=2`, `RewritePath=3`, `CircuitBreaker=4`):

| 실행 순서 | 필터 |
| --- | --- |
| 1 | Dedupe, **JwtAuthorization** ← 여기서 User-Id/Email/Role 주입 |
| 2 | **RemoveRequestHeader=User-Id** ← 방금 넣은 것을 지운다 |
| 3 | **RemoveRequestHeader=User-Email** ← 지운다 |
| 4 | **RemoveRequestHeader=User-Role** ← 지운다 |

**cart-service·catalog-service-write 는 신원 헤더를 하나도 못 받는다.**
`user-service`(`RewritePath=1`, `JwtAuthorization=2`)는 `RemoveUserId`(2)가 JwtAuth 와
같은 order 라 안정 정렬로 먼저 실행되어 `User-Id` 만 살아남고 **`User-Email`·`User-Role`
은 지워진다.**

결과:

| 증상 | 근거 |
| --- | --- |
| `GET /cart-service/api/cart/loadcart` → `User-Email` null → **401.** 장바구니 전 기능 | `CartController.java:85` |
| `POST /catalog-service/api/product` (관리자) → `User-Id` null → **401.** 상품 등록 불가 | `AdminWriteInterceptor.java:55` |
| 쿠폰 삭제·배송 송장 등록 → 역할 헤더 없음 → **항상 403** | `CouponController.java:108`, `DeliveryController.java:161` |
| **보안 회귀**: user-service 가 `User-Role` 을 못 받아 "헤더 없음 = 내부 Feign 호출" 분기로 빠진다 → 일반 사용자 토큰으로 `GET /api/auth/user/id?email=victim@…` 이 **남의 userId·이름·프로필**을 돌려준다. docs/27 §9 방어가 통째로 무효 | `AuthController.java:367` |

§1.1(주문 생성 500)과 §1.3(관리자 화면)이 각각 다른 원인으로 같은 화면을 막고 있어
이 결함이 가려져 있었을 가능성이 크다. **고치는 방법은 한 줄이다** — `RemoveRequestHeader`
세 개를 default-filters 에서 빼고 각 라우트의 `JwtAuthorization` **앞**에 두거나,
`JwtAuthorization` 필터가 주입 직전에 스스로 지우게 한다(이미 그 코드가 있다).

#### 나머지

| # | 심각도 | 위치 | 무엇 | 확인 |
| --- | --- | --- | --- | --- |
| 2 | 🔴 | `application-local.yml:170-173` | `discovery.locator.enabled: true` — 유레카에 등록된 모든 서비스에 `Path=/{serviceId}/**` 자동 라우트가 생긴다. **`JwtAuthorization` 도 CircuitBreaker 도 없다.** `curl https://gateway:8000/CHAT-SERVICE/api/chat/rooms` 가 토큰 없이 통한다 | |
| 3 | 🔴 | `resolver/CustomUserKeyResolver.java:41-45` | 레이트리밋 버킷 키를 `X-Forwarded-For` 첫 값에서 만드는데 **신뢰 프록시 목록이 없다.** 헤더를 바꿔 가며 보내면 매 요청 새 버킷 → 로그인 대입 제한이 사실상 없다 | |
| 4 | 🟠 | `application-local.yml:177`, `:204` | 라우트 id 가 **둘 다 `user-service`** — 레이트리미터 설정이 라우트 id 로 저장돼 last-wins. public 로그인 라우트의 1/10 이 인증 라우트의 20/40 으로 덮인다 | |
| 5 | 🟠 | `JwtAuthorizationGatewayFilterFactory.java:115-121` | `ExpiredJwtException` 만 잡는다. `MalformedJwtException`·서명 불일치는 그대로 던져져 **401 이 아니라 500** | |
| 6 | 🟠 | `common/.../RedisConfig.java:25-31,33-53` | `ObjectMapper` 빈을 둘 등록 → Boot 의 `JacksonAutoConfiguration` 이 물러난다. 이 설정을 스캔하는 order/cart/chat 은 `WRITE_DATES_AS_TIMESTAMPS` ON, `FAIL_ON_UNKNOWN_PROPERTIES` ON → **날짜가 배열로 나가고**, 모르는 필드 하나에 역직렬화가 죽어 호환 배포가 불가능하다 | |
| 7 | 🟠 | `common/.../CustomRestExceptionHandler.java:147-154` | `Exception` 전부를 500 으로 잡고 `getLocalizedMessage()` 를 본문에 넣는다 → JDBC 예외의 테이블·컬럼·파라미터가 클라이언트로 | |
| 8 | 🟠 | `common/.../PaymentRequest.java` 외 4개 | Kafka DTO 에 **사용자 액세스 토큰 원문**이 담겨 아웃박스 테이블과 토픽에 평문 저장된다. 보존기간 동안 브로커·DB 읽기 권한이 곧 그 사용자 계정 | ✅ 다섯 DTO·스키마(v2)에서 제거, 소비자의 존재 검사 제거, 아웃박스 행 스크럽(V15) — docs/54 §1.7 |
| 9 | 🟠 | `application-local.yml:337-356` | `chat-service-ws` 에 `JwtAuthorization` 없음(핸드셰이크 무인증); `chat-service-api` 의 uri 가 **`lb:ws://`** 라 REST 요청이 웹소켓 라우팅으로 샌다 | ✅ 2026-10-16 두 라우트 `lb://`. 운영(Eureka)은 인스턴스가 https 를 알려 ws 가 무시되고 동작했다 — 스킴을 알리지 않는 디스커버리에서는 GET 400 · POST 405 · 평문(ChatRouteTlsTest 로 실측). 핸드셰이크는 STOMP CONNECT 의 토큰으로 인증한다(설계대로) |
| 10 | 🟡 | `JwtAuthorizationGatewayFilterFactory.java:117,132-134,173-176` | 요청당 JWT 5회 파싱 + `key()` 가 매번 `Jwts.SIG.HS256.key().build()` 로 **임의 키를 생성**(알고리즘 이름만 쓰려고) | |
| 11 | 🟡 | `eureka-server/application-local.yml:80-92` | `logging:` 이 `eureka:` 아래로 들여쓰기돼 `eureka.logging.*` 로 바인딩 → **유레카 로그 설정이 전부 무시**된다 | |
| 12 | 🟡 | `common/application-common.yml:46-51` | 유레카 zone 이 평문 `http://localhost:8761` 인데 서버는 TLS → 로컬 실행 시 등록 실패. k8s 는 ConfigMap 이 덮어써서 가려져 있다 | |

**문제 없던 것**: JWT 알고리즘 한정(`verifyWith(SecretKey)` — alg 혼동 불가), 만료 강제,
게이트웨이/발급자의 시크릿 파생 일치, `uid` 클레임 부재 시 **401 + 로그**(docs/27 §11 의
배포 순서 결합이 코드와 일치), CORS 명시 오리진 + `User-*` 미허용, actuator 미노출,
`KafkaSecuritySettings` 의 빈 값 규칙, 공통 Kafka DTO 의 생산자/소비자 타입 드리프트 없음,
`TokenContext` 의 `finally` 정리.


---

## 3. 프론트엔드 — "낙관적 업데이트와 롤백이 빠져 있다"에 대해

맞다. 다만 정확히 어디가 빠졌는지 나눠 보면 고칠 곳이 좁혀진다.

**장바구니는 서버 낙관적 갱신이 아니라 로컬 저장소다.** `cart/Product.jsx:53,72`,
`Infos.jsx:73,78` 은 Redux 에 `dispatch(updateCart)` 만 하고 서버를 부르지 않는다
(`redux-persist` whitelist `["cart"]`). 서버와 만나는 지점은 체크아웃의 `saveCart` 하나다.
그래서 여기에는 "롤백"이 필요 없다 — 대신 **체크아웃에서 서버가 다시 계산한 값과 화면이
다를 수 있다**는 문제가 있고, §1.5 의 할인식 때문에 지금 실제로 다르다.

**서버가 진실인 뮤테이션에는 낙관적 갱신도, 실패 시 복원도 없다.** 실측:

| 지표 | 값 |
| --- | --- |
| `useMutation` 호출 | 7 |
| 그중 `onMutate`(낙관적 갱신) | **0** |
| `onError` 핸들러 | 22 (대부분 에러 메시지 표시만, 상태 복원 없음) |

| 화면 | 지금 동작 | 빠진 것 |
| --- | --- | --- |
| 결제 후 주문 페이지 (`pages/order.jsx`) | 렌더되지 않는 로컬 state 만 바꾸고 **refetch 없음** → "미결제"로 남음 (§1.10) | 성공 시 `invalidateQueries(['order', id])`. 낙관적 갱신보다 이게 먼저다 |
| 위시리스트 | 서버 응답 후 갱신 | 낙관 토글 + 실패 시 이전 값 복원 |
| 배송지 선택/삭제 | 서버 응답 후 목록 재조회 | 낙관 갱신 + 복원(서버 §1.6 결함이 먼저) |
| 쿠폰 적용 (`Summary.jsx:153`) | `onChange` 가 덮여 **입력이 state 에 도달하지 않는다**(§1.3 과 같은 원인) | 입력 경로 자체 복구 |
| 알림 읽음 (`NotificationBell.jsx`) | **낙관 갱신 + 실패 시 `refreshCount()` 복원 — 잘 돼 있다** | — 이걸 본보기로 |
| 배송 추적 (`DeliveryTracker.jsx`) | 언마운트 가드·UTC 변환 가드 있음 | — |

**그 외 프론트엔드 결함** (내가 확인한 것은 "확인"):

| # | 심각도 | 위치 | 무엇 | 확인 |
| --- | --- | --- | --- | --- |
| 1 | 🔴 | `webpack.config.prod.js:14` + `Dockerfile.frontend-runtime` + `nginx.conf:121` | `devtool: "source-map"` 이고 `dist` 전체가 nginx 루트로 → `main.bundle.js.map` 이 서빙된다. `sourcesContent` 에 원본 1,084개 파일 전문(내부 엔드포인트·취약점 설명 주석 포함). 그리고 **`frontend/dist.zip`(3MB) 이 git 에 추적 중**이며 맵 파일과 `backend.ysyproject.com`, PayPal client-id 가 들어 있다 | 확인 |
| 2 | 🟠 | `pages/browse.jsx:77-89, 92-114` | `toggleParam(name, undefined)` 이 `delete` → 핸들러가 하나씩만 넘기므로 **나머지 12개 파라미터가 전부 지워진다.** 필터를 두 개 동시에 걸 수 없다. docs/26 §13 수정의 회귀 | |
| 3 | 🟠 | `browse.jsx:402` vs `:111` | UI 는 `rating`, 로더는 `rate` → 평점 필터가 아무 일도 안 한다 | |
| 4 | 🟠 | `browse.jsx:510-514` | `sort` 를 서버에 안 보내고 **현재 페이지 10건만** 클라이언트 정렬 | |
| 5 | 🟠 | `browse.jsx:518,587-592`, `Home.jsx:53-58` | 로더가 오류를 삼키고 null → 첫 줄 `productsDB.categories` 에서 ErrorBoundary; 홈은 빈 화면 | |
| 6 | 🟠 | `util/TokenRefresher.js:112-114` | **어떤 500 이든 홈으로 이동** — 결제 중 500 이면 화면이 언마운트되어 사용자는 주문이 됐는지 모른다 | |
| 7 | 🟡 | `pages/admin/Index.jsx:108-126` | `product.name` 이 effect 의존성 — 글자마다 API 2건(지금은 §1.3 때문에 가려짐) | |
| 8 | 🟡 | `ProductPage.jsx:24-60` | `reviews` 를 배열로 다루지만 서버는 `{reviews, num_reviews}` → 별점 분포가 전부 0% | |
| 9 | 🟡 | `util/loadFake.js` | 미참조 280줄. 번들에는 안 들어가지만 `@faker-js/faker` 를 dependencies 에 붙잡아 둔다 | |

**문제 없던 것**: 토큰은 메모리(액세스)+HttpOnly 쿠키(리프레시)뿐, `localStorage` 에 토큰 없음.
`dangerouslySetInnerHTML`/`innerHTML`/`eval` 0건. `/admin` 가드와 `role` 클레임 읽기(docs/27
§12) 유효. OAuth 콜백에 오픈 리다이렉트 없음. 401 동시 요청 큐 정상. 번들에 비밀값 없음
(#1 은 소스 노출이지 시크릿 유출은 아니다).

---

## 4. CDC — 커넥터를 켜면 무엇이 깨지고, 살아 있는 릴레이는 올바른가

**전제**: Kafka Connect 는 어디에도 배포돼 있지 않다(docs/23 §4). 실제 아웃박스 발행은
`PaymentOutboxScheduler`/`OrderOutboxScheduler` 가 한다. 루트의 커넥터 JSON 5개는 죽은 설정이다.

### 4.1 지금 커넥터를 켜면

| # | 심각도 | 무엇 | 위치 |
| --- | --- | --- | --- |
| A1 | ✅ 적용 | `table.include.list` 가 `public.*` 인데 테이블은 `pay`/`orders` 스키마 → **아무것도 캡처하지 않는다**(WARN 만) | `*-postgresql.json:12` vs `OrderOutboxEvent.java:28`, `PaymentOutboxEvent.java:27` |
| A2 | ✅ 적용 | 라우팅이 `${aggregate_type}.events` → `Order.events`/`Payment.events`/`UserToken.events`. 소비자는 `order`/`payment` 만 본다 | `:15-16` vs `PaymentOutboxHelper.java:67,98`, `OrderOutboxHelper.java:73` |
| A3 | ✅ 적용 | 값 형식: EventRouter 는 `payload` 컬럼만 보내고 `__TypeId__`·`Authorization` 헤더를 붙이지 않는다 → order/pay 의 `JsonDeserializer` 가 타입을 못 정해 **전량 DLT** | `KafkaConsumerConfig.java:85`(order), `:53`(pay) |
| A4 | 🔴 | `pgoutput` 은 `wal_level=logical` + REPLICATION 권한 필요. HA compose 는 `replica`, 관리형 프로비저닝(ops/25)은 일반 역할 | `docker-compose-postgres-ha.yml:36`, `ops/25:338` |
| A5 | 🟠 | 마이그레이션의 `publish_via_partition_root` 는 `IF EXISTS dbz_publication` 일 때만 — Flyway 가 커넥터보다 먼저 돌므로 **항상 no-op** → 켜면 자식 파티션 단위 이벤트로 include list 재불일치 | `V1__partition_*.sql:72-77` |
| A6 | 🟠 | `chat-connector.json`: MongoDB **싱크**가 `mongodb://mywebserv.site:27017` **무인증** 공개 호스트로, `chat` 컬렉션에 쓴다 — chat-service 가 이미 같은 컬렉션에 저장하므로 켜면 **형식이 다른 중복 문서.** docs/13 이 다른 4개에서 뺀 호스트명이 여기만 남았다 | `chat-connector.json:4-8` vs `MessageConsumer.java:41` |
| A7 | 🟡 | 세대 혼합(`database.server.name` 1.x + `topic.prefix` 2.x, MySQL 전용 `database.history.*`) — Debezium 2.x Postgres 는 `topic.prefix` 만 읽고 나머지는 WARN 무시. 치명 아님 | |
| A8 | 🟡 | MySQL 변형 2개는 호스트 `""`·`<localhost>:<9092>` 플레이스홀더 잔재 | |

이중 발행 여부: 지금 JSON 그대로면 토픽이 달라 "허공에 발행"이다. A1·A2 를 고쳐 같은 토픽으로
보내면 모든 이벤트가 두 번 가는데, 소비자 멱등 키(pay `UNIQUE(order_id, tracking_id)`, order
`NOT_PROCESSED` 상태 검사, coupon `payment-{id}`, delivery `findByOrderId`, notification
`payment:{orderId}:{status}` UNIQUE)가 흡수한다. 스케줄러의 `STARTED→COMPLETED` UPDATE 는
EventRouter 가 `warn` 으로 건너뛴다.

### 4.2 살아 있는 릴레이(스케줄러)

| # | 심각도 | 무엇 | 위치 | 확인 |
| --- | --- | --- | --- | --- |
| B1 | 🔴 | 재시도 불가 실패(payload JSON 파싱 실패 → `OrderDomainException`)가 **try/catch 없이** 스케줄러 루프 밖으로 튀어 트랜잭션이 롤백되고 행은 STARTED 로 남는다. `created_at asc` 라 **매번 배치 맨 앞** → 그 뒤 모든 이벤트가 영원히 발행되지 않는다(head-of-line 정지). 최대 시도 횟수·데드레터 없음 | `PaymentRequestKafkaPublisher.java:67,98-105`, `PaymentOutboxScheduler.java:74-96` | 확인 |
| B2 | 🟠 | Kafka 커밋이 DB 커밋 뒤 (§2.3 #5) | | |
| B3 | 🟠 | `"Bearer null"` (§2.3 #2) | | 확인 |
| B4 | 🟠 | 메시지 키 없음 + 3 파티션 → 같은 주문의 이벤트 순서 보장 없음 | `PaymentRequestKafkaPublisher.java:69-72`, `ops/02:35,47` | |
| B5 | 🟠 | 2027-01 까지만 파티션 선생성 + DEFAULT. 2027-02 부터 행이 DEFAULT 로 가는데, 그 뒤 새 파티션을 만들면 PostgreSQL 이 DEFAULT 안의 행과 충돌한다며 **거부** → 운영 개입 없이는 파티션 추가 불가 | `V1__partition_*.sql:30-39` | |
| B6 | 🟡 | `PaymentService.java:73-85` 가 스케줄러 밖에서 잠금 없이 재발행하는 두 번째 경로 | | |

**올바른 것**: SKIP LOCKED 선점 + 배치 상한, 발행+상태 확정 한 트랜잭션, 일시 실패를 FAILED
로 적지 않음, 부분 인덱스가 클레임 쿼리와 정확히 맞음, 동시성 테스트 존재.

**권고**: 커넥터 JSON 5개는 **삭제**하고 docs/23 §4 를 가리키는 한 줄만 남긴다. 고치려면
A1~A5 를 전부 손봐야 하는데 그 끝에 얻는 것은 이미 정리된 스케줄러와 같은 이벤트를 두 번
넣는 경로다. CDC 의 이점(폴링 지연·DB 부하)을 취하려면 스케줄러를 *끄는* 결정까지 같이
해야 하고, 그 결정은 docs/23 이 이미 반대로 내렸다. 같이 정리할 것: `README.md:262,336`,
`ops/01:53-54`, `ops/promote-replica.sh:35` 의 커넥터 절차. **B1 과 B5 는 JSON 과 무관하게
살아 있는 경로의 결함이라 별도로 고쳐야 한다.**

---

## 5. 왜 이렇게 됐나 — 반복되는 원인 셋

**① 계약을 한쪽만 바꿨다.** cart `/checkout` 헤더(§1.1), `AdminInput` 전개 순서(§1.3), cart
캐시 키 갱신에 필요한 토큰(§2.5 #2), 멱등 조회 키(§2.3 #3). 호출부와 피호출부가 다른
모듈·다른 커밋에 있고, 그 사이를 건너는 테스트가 없다. 사가 E2E 는 cart 를 띄우지 않고,
프론트 테스트는 state 를 미리 채운다.

**② 신원을 게이트웨이 헤더가 아니라 본문에서 읽는다.** §1.6 의 일곱 경로 전부. docs/27 이
조회 경로를 고쳤고 쓰기 경로가 남았다. 규칙은 하나다 — *`User-Id`/`User-Email` 헤더 외의
신원은 없다.*

**③ "실행되지 않는 안전장치".** `AFTER_COMMIT` 리스너(§1.4), `publish_via_partition_root`
(A5), `purgeExpired`(§2.4 #6), `SMS_BYTE_LIMIT`(§2.4 #8), `findByPhoneE164AndPhoneVerifiedTrue`
(§2.4 #11), user/cart 의 아웃박스 클래스 — 있어 보이지만 호출되지 않는다. 앞선 작업의 Feign
타임아웃(docs/37 §2.1)과 같은 부류다. 이 부류는 리뷰로 못 잡고 **"실제로 호출되는가"를 묻는
테스트**로만 잡힌다.

---

## 6. 리뷰가 틀렸던 것 — 반증하고 뺀 항목

catalog 리뷰어가 `ProductUpdateMessageListener.productUpdated`(package-private) 의
`@Transactional` 이 "프록시가 public 만 보므로 무효"라고 보고했다. **Spring 6.2.1 에서는
틀리다.** `AbstractTransactionManagementConfiguration` 이 `new AnnotationTransactionAttributeSource(false)`
로 만들고(바이트코드 `iconst_0` 확인), 6.0 부터 클래스 기반 프록시에서는 protected/package-private
도 트랜잭션이 걸린다. **private 은 여전히 안 된다**(CGLIB 이 오버라이드하지 못한다) — docs/37
§4 의 private 관련 서술은 그대로 유효하고, 거기 적힌 package-private 두 곳(`CouponService.java:242,250`)
은 자기호출이라 어차피 프록시를 안 탄다.

---

## 7. 이전 문서 정정

| 문서 | 무엇이 틀렸나 | 정정 |
| --- | --- | --- |
| docs/37 §5 | `RegistrationListener` 를 "가장 모범적인 지점"으로 인용 | 코드 모양은 맞지만 **호출되지 않는다**(§1.4). 각주를 달았다 |
| docs/35 | "DB 의 주문 총액과 대조한다"를 신뢰 기준으로 삼음 | 그 총액이 클라이언트가 정한 값이다(§1.2). 서버 재계산이 선행돼야 한다 |
| docs/28 §2 | `AdminInput` 전개 순서 수정으로 "세 화면이 함께 낫는다" | 세 화면의 제출부를 같이 바꾸지 않아 **회귀**(§1.3) |
| docs/25 | delivery-service 가 주문 상태를 `DISPATCHED/DELIVERED` 로 반영 | 그 토픽을 아무도 읽지 않는다(§1.8) |

---

## 8. 고치는 순서 — 제안

각 묶음은 하나의 PR 크기다. 앞의 것이 뒤의 것을 가리지 않도록 순서를 정했다.

1. **주문 경로 복구 + 금액 서버 재계산** — §1.1(Feign 클라이언트 헤더), §1.2(`createOrder` 가
   cart 에서 받은 목록으로 `total` 재계산, `int`→`BigDecimal`), §1.5(할인식 두 곳), §2.3 #1
   (취소 상태 전달). 이 묶음이 끝나야 docs/34 의 결제 인수를 처음으로 돌 수 있다.
2. **신원 출처 통일** — §1.6 일곱 경로를 게이트웨이 헤더로. 패턴이 같아 한 PR 이다.
3. **관리자 화면 회귀** — §1.3 (세 `onSubmit` 을 Formik `values` 로) + §1.9 (사이즈 역참조) +
   catalog #1(`Optional` 검사) + #2(색인 대상). 테스트는 **입력을 타이핑**하게 고친다.
4. **가입 메일** — §1.4: `publishEvent` 를 `registerNewUserAccount` 안으로 옮긴다(트랜잭션
   안에서 발행해야 `AFTER_COMMIT` 이 성립한다). 그리고 리스너가 실제로 호출되는 테스트.
5. **notification** — §1.7(로그·제한·pepper 검증), §2.4 #1(`next_attempt_at` + 지수 백오프).
6. **프론트엔드** — §1.10(refetch), 배지 반전, browse 필터 세 건, 소스맵 제거 + `dist.zip`
   git 에서 제거, `TokenRefresher` 의 500 리다이렉트 제거. 낙관적 갱신은 그 다음이고
   `NotificationBell` 패턴을 그대로 쓰면 된다.
7. **아웃박스 릴레이** — B1(파싱 실패 격리 + FAILED + 경보), §2.3 #3·#4·#5, B5(파티션 자동
   생성 또는 2027-01 전에 수동 추가 절차).
8. **정리** — 커넥터 JSON 5개 삭제, 죽은 코드(§2.1 목록, `MessageMongoEventListener`,
   `loadFake.js`), `delivery-status` 컨슈머를 만들거나 발행을 끈다(§1.8 은 둘 중 하나여야 한다).

---

## 9. 확인하지 못한 것

- **재현 실행**: 당시 검증 환경에는 Docker 도 클러스터도 없었다. §1 의 항목은 코드로 확인했지 실제
  요청을 보내 본 것이 아니다. 특히 §1.1 은 배포된 서비스에 주문 생성 한 번이면 확정된다.
- **§1.2 의 `int` 절삭**: Jackson 기본값과 설정 부재로 판단했다. 59.98 짜리 주문 하나 만들어
  `orders.total` 을 보면 끝난다.
- **§2.3 #9 의 배치 헤더 변환**: Spring Kafka 를 실행해 보지 않았다. `@Header List<String>`
  으로 받는 테스트 하나면 결론이 난다.
- **KST 전제(§2.4 #3)**: 우체국 API 실응답이 없다.
- **운영 프로파일**: `SMS_PROVIDER`·`SMS_CODE_PEPPER`·`DELIVERY_SIMULATED_ENABLED` 가 운영에
  실제로 주입되는지는 config-server 저장소를 봐야 한다. §1.7 의 심각도가 여기 달려 있다.
- 읽지 않은 것: catalog 배치 프로세서 4개, `document/` 나머지 7개, chat 컴포넌트 대부분,
  `Delivery.overallStatus()`.
