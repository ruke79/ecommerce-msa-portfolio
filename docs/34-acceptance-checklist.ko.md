# 34. 인수 체크리스트 — 배포한 것을 손으로 확인한다

[日本語](34-acceptance-checklist.md) | **한국어**

> 🕰️ **상태(2026-09-27) — 일부 낡음.** 장바구니 API 는 `PUT/PATCH/DELETE /api/cart/items` 이고, `/api/pay/confirm` 은 없습니다.

이 저장소는 단위·통합 테스트가 268개고, 그 대부분은 **실제로 겪은 장애를 회귀 테스트로
굳힌 것**이다(docs/14 · 16). 그런데 아직 아무도 확인하지 않은 것이 하나 있다.

> **사용자가 처음부터 끝까지 실제로 쓸 수 있는가.**

테스트가 보는 것은 "이 코드가 이 입력에 이렇게 반응한다"이지, "회원가입하고 물건을 사고
배송을 조회할 수 있다"가 아니다. 그 둘은 다르고, 후자는 배포된 시스템을 사람이 눌러 봐야만
알 수 있다.

이 문서는 그 순회 경로다. 자동화 스크립트가 아니라 **체크리스트**인 이유는, 처음 도는
회차에서는 무엇이 깨질지 모르기 때문이다. 여기서 나온 실패가 다음 회귀 테스트의 목록이 된다.

---

## 0. 어떻게 쓰나

1. **순서대로 돈다.** 뒤 항목은 앞 항목의 결과에 의존한다(회원가입 없이 주문할 수 없다)
2. 실패하면 **고치지 말고 기록부터 한다.** 한 번에 하나씩 고치면 원인이 섞인다
3. 각 항목의 **"실패를 어디서 보나"** 를 미리 열어 둔다 — 화면만 보면 절반은 놓친다

### 미리 열어 둘 창 넷

```bash
# ① 전체 파드 상태
kubectl -n ecommerce get pods -w

# ② 게이트웨이 + 대상 서비스 로그 (탭을 나눠서)
kubectl -n ecommerce logs -f -l app=gateway-server
kubectl -n ecommerce logs -f -l app=user-service

# ③ DB (외부화했다면 관리형 콘솔의 SQL 편집기)
psql "$APP_URL"      # product 역할

# ④ Kafka 소비 지연 — 비동기 경로가 막혔는지 여기서 가장 먼저 보인다
kubectl -n kafka exec deploy/kafka -- \
  kafka-consumer-groups --bootstrap-server localhost:9092 --all-groups --describe
```

### 기본값 확인 — 이것부터 보고 시작한다

배포 기본값은 **아무것도 실제로 내보내지 않는다.** 모르고 "메일이 안 온다"로 30분을 쓰기 쉽다.

| 설정 | 기본값 | 의미 |
|---|---|---|
| `notification.mail.provider` | `logging` | 메일이 **발송되지 않고 로그에만 찍힌다** |
| `notification.sms.provider` | `logging` | SMS 도 마찬가지 |
| `SEARCH_ENGINE` | `postgres` | 검색이 LIKE 경로. ES/nori 는 `ops/03` 으로 전환 |
| `DELIVERY_KOREAPOST_SERVICE_KEY` | 비어 있을 수 있음 | 실제 배송 조회 대신 `SIMULATED` 택배사 |

로그 전용 모드가 **개발 기본값으로 맞다**(docs/29 — 잘못 보낸 메일은 되돌릴 수 없다).
실제 발송까지 확인하려면 §6 에서 명시적으로 켠다.

---

## 1. 기동 — 사용자를 태우기 전에

| | 확인 | 실패를 어디서 보나 |
|---|---|---|
| ☐ | 파드 전부 `Running` · `READY 1/1` | `kubectl -n ecommerce get pods` |
| ☐ | `CrashLoopBackOff` 없음 | 있으면 `kubectl logs --previous` 로 **직전** 로그를 본다. 기동 실패는 현재 로그에 안 남는다 |
| ☐ | Eureka 에 서비스 전부 등록 | `kubectl -n ecommerce port-forward svc/eureka-server 8761:8761` → 브라우저. 파드는 Running 인데 등록 0건이 실제로 있었다(사설 CA 미신뢰, docs/18) |
| ☐ | Flyway 마이그레이션 적용 | `select * from flyway_schema_history order by installed_rank desc limit 5;` — 각 DB 마다 |
| ☐ | `ops/06-smoke-test.sh` 통과 | `GATEWAY=https://shop.example.com ./ops/06-smoke-test.sh` |
| ☐ | 게이트웨이 fallback 이 503 | 스모크가 검사한다. 200 이면 서킷브레이커 배선이 죽어 있다 |

> ⚠️ `ops/06` 은 헬스·레이트리미터·Eureka·DLT·카탈로그 조회까지만 본다. **주문·결제는 보지
> 않는다.** 그게 아래가 필요한 이유다.

---

## 2. 인증 — 가장 많이 깨지는 곳

화면: `/register` → 메일 링크 → `/signin` → `/profile`

| | 확인 | 실패를 어디서 보나 |
|---|---|---|
| ☐ | 회원가입 (`POST /api/auth/public/register`) | 응답 200 |
| ☐ | **확인 메일이 나간다** | `provider=logging` 이면 notification-service 로그에 링크가 찍힌다. `mail_message` 테이블에 행이 생기고 상태가 `SENT` 로 간다 |
| ☐ | 🔒 **토큰이 로그에 없다** | `kubectl logs -l app=user-service \| grep -i token` — 원문 토큰이 보이면 **즉시 중단하고 고친다**. DB 에는 SHA-256 해시만 있어야 한다 |
| ☐ | 확인 링크가 **접속 가능한 주소** | `APP_PUBLIC_URL` 이 클러스터 내부 DNS 면 브라우저에서 안 열린다. 예전에 특정 호스트가 하드코딩돼 있었다 |
| ☐ | 링크 클릭 → 계정 활성화 (`GET /registrationConfirm`) | `users.enabled = true` |
| ☐ | 링크 **재사용 시 거부** | 두 번째 클릭이 실패해야 한다 |
| ☐ | 로그인 (`POST /api/auth/public/signup`) | JWT 발급 |
| ☐ | 재발송 (`POST /api/auth/public/resend-verification`) | 레이트리밋에 걸리는지도 함께 |
| ☐ | 비밀번호 재설정 2단계 | `forgot-password` → 메일 → `reset-password` |
| ☐ | 2FA 켜기 → 로그아웃 → 로그인 | `enable-2fa` → `verify-2fa` → 로그인 응답이 `two_factor_required`(토큰 없음) → `public/verify-2fa`(대기 쿠키) → 전체 리로드 뒤 로그인 상태(REFRESH 쿠키로 복구) |
| ☐ | 토큰 갱신 (`GET /token/refresh`) | 만료 후에도 세션 유지 |
| ☐ | CSRF 토큰 (`GET /api/csrf-token`) | 프론트가 이 값을 헤더에 실어야 쓰기 요청이 통과한다 |

### 소셜 로그인 — PKCE · 파드 간 이어받기 (docs/27 부록 2)

**두 파드가 뜬 상태에서 확인해야 의미가 있다.** 인가 요청을 세션에 두던 시절의 결함은
파드가 하나면 절대 재현되지 않는다.

```bash
kubectl -n ecommerce get pods -l app=user-service      # 2개인지 먼저 본다
```

| | 확인 | 실패를 어디서 보나 |
|---|---|---|
| ☐ | 버튼이 설정된 프로바이더만 뜬다 | `GET /api/auth/public/oauth2/providers` 응답과 화면이 일치 |
| ☐ | **인가 URL 에 `code_challenge` 가 있다** | 브라우저 개발자도구 → Network → `/oauth2/authorization/google` 의 302 `Location`. `code_challenge=...&code_challenge_method=S256` 이 없으면 PKCE 가 안 걸린 것 |
| ☐ | Redis 에 인가 요청이 잠깐 생겼다 사라진다 | `kubectl exec -it <redis> -- redis-cli --scan --pattern 'oauth2:authreq:*'` — 로그인 도중엔 보이고, 콜백 후엔 없어야 한다 |
| ☐ | **로그인을 5회 반복해도 전부 성공** | 파드가 2개면 예전 구현은 대략 절반이 `authorization_request_not_found` 로 실패했다. 한 번 성공했다고 넘어가지 말 것 — 이 결함은 **재시도하면 되는** 형태라 한 번만 해보면 놓친다 |
| ☐ | 뒤로가기로 콜백 URL 재방문 시 실패 | `GETDEL` 로 한 번만 쓰이므로 두 번째는 거부돼야 한다 |
| ☐ | 소셜 계정에 2FA 가 켜져 있으면 확인 단계로 간다 | `/signin?oauth2fa=1` 로 리다이렉트 |
| ☐ | 🔒 `code_verifier` 가 브라우저에 없다 | 개발자도구 → Application → Cookies/Storage 에 `code_verifier` 가 보이면 안 된다(서버 Redis 에만 있어야 한다) |

> 토큰 교환에서 `invalid_grant` 가 나면 PKCE 변경을 먼저 의심한다 — `code_challenge` 는
> 보냈는데 콜백 파드가 `code_verifier` 를 못 찾은 경우다(즉 Redis 접속 문제).

---

### 🔒 게이트웨이 신원 헤더 위조 (docs/27)

**이건 반드시 한다.** 통과하면 인증 체계 전체가 무의미해진다.

```bash
# 클라이언트가 직접 User-Id 를 보낸다. 게이트웨이가 **덮어써야** 한다.
curl -i https://shop.example.com/api/user/profile/address \
  -H "User-Id: 1" -H "User-Email: victim@example.com" -H "User-Role: ROLE_ADMIN"
```

- ☐ **401/403 이어야 한다.** 남의 주소 목록이 나오면 치명적이다
- ☐ 게이트웨이 로그에 클라이언트가 보낸 헤더가 제거된 흔적

---

## 3. 카탈로그 · 검색

화면: `/` → `/browse` → `/product/:slug`

| | 확인 | 실패를 어디서 보나 |
|---|---|---|
| ☐ | 첫 화면 상품 노출 (`GET /api/product/frontpage`) | 비어 있으면 시드 데이터 미적재 |
| ☐ | 목록 페이지네이션 (`GET /api/product/products/page`) | |
| ☐ | 상세 (`GET /api/product/{slug}`) | |
| ☐ | 검색 (`GET /api/search?...`) | 기본은 PostgreSQL LIKE |
| ☐ | **두 번째 검색이 500 이 아니다** | 검색 결과 캐시(`search_cache`)의 `Page` 역직렬화가 깨졌던 적이 있다(docs/16 B-27). **캐시 히트에서만 터지므로 두 번 눌러야 보인다** |
| ☐ | 캐시가 실제로 동작 | `redis-cli KEYS 'search_cache*'` 또는 두 번째 호출의 응답 시간 |
| ☐ | 관리자 상품 등록 (`POST /api/product`) | **화면에서 확인한다.** 이미지 3장 + 스타일 이미지 + 배송비를 채워야 제출된다 |
| ☐ | 상품 **수정** (`/admin/allproducts` → 수정) | 이름·브랜드·설명·배송비만. slug(주소)는 안 바뀐다 — 기존 링크 보호 |
| ☐ | **품절 토글** | 목록·상세에 배지로 남고, **장바구니 담기가 실패**해야 한다 |
| ☐ | **삭제** | 목록·검색·상세에서 즉시 사라진다. ⚠️ 화면·API 로는 복구 불가(V8 의 SQL 만) |
| ☐ | **색상 변형 연속 등록** | 등록 성공 배너 → "색상 변형 이어서 등록" → 이미지·색상만 채워 재등록. 같은 상품에 SKU 가 붙는다 |
| ☐ | **임시저장** | 폼을 채우다 새로고침 → 텍스트 필드가 복구된다(이미지는 다시 선택) |
| ☐ | **이미지 업로드** | ⚠️ Cloudflare 무료 플랜은 본문 100MB 제한이고, **애플리케이션 로그에 아무것도 남지 않는다**(엣지에서 잘린다). 큰 파일로 한 번 시험한다 |

> ⚠️ **상품·카테고리 등록은 이제 관리자 전용이다.** 예전에는 이 쓰기 API 들이
> **무인증**이었다 — 게이트웨이의 catalog 라우트에 JwtAuthorization 이 없었고, 서비스의
> 유일한 `@PreAuthorize` 는 주석 처리돼 있었다. 사이트가 공개된 동안 누구나 토큰 없이
> 상품을 등록·변조할 수 있었다. 지금은 두 겹이다: 게이트웨이가 쓰기 메서드(POST·PUT·
> DELETE·PATCH)에 JWT 를 요구하고, catalog-service 의 AdminWriteInterceptor 가
> `ROLE_ADMIN` 을 요구한다(별점 등록만 로그인으로 충분). 읽기는 전과 같이 공개다.
>
> **관리자 계정 만들기** — 회원가입은 전부 `ROLE_USER` 로 만들어지므로, 첫 관리자는
> DB 에서 한 번 승격해야 한다 (`users.users.role_id` → `users.roles`):
>
> ```sql
> INSERT INTO users.roles (role_name)
>   SELECT 'ROLE_ADMIN' WHERE NOT EXISTS
>     (SELECT 1 FROM users.roles WHERE role_name = 'ROLE_ADMIN');
> UPDATE users.users
>   SET role_id = (SELECT role_id FROM users.roles WHERE role_name = 'ROLE_ADMIN')
>   WHERE email = '<관리자로 만들 계정 이메일>';
> ```
>
> 역할은 **JWT 클레임에 들어가므로** 승격 후 재로그인해야 반영된다. 이미 로그인된
> 세션의 토큰에는 옛 역할이 들어 있다.
>
> **상품 등록 화면은 고쳤다.** 예전에는 `setLoading(true)` 뒤의 업로드·전송 블록이 통째로
> 주석 처리되어 있어 `setLoading(false)` 에 닿지 못하고 버튼이 'Loading...' 에서 영원히
> 멈췄다. Cloudinary 직접 업로드를 걷어낸 뒤 S3 경로로 다시 잇지 않은 상태였다.
> 지금은 `/catalog-service/api/product` 로 multipart 를 보내고, 실패하면 다이얼로그로
> 알리며, 어떤 경로로도 버튼이 풀린다.
>
> 눌러 볼 때 알아 둘 것:
>
> - **이미지 3장 이상 + 스타일 이미지 1장**이 없으면 제출되지 않는다(클라이언트 검증).
>   서버가 두 값을 무조건 사용하므로, 비면 500 이 난다 — 그래서 앞에서 막는다
> - **배송비를 비워 두면 제출되지 않는다.** 서버가 `new BigDecimal(shippingFee)` 를
>   그대로 부르기 때문이다
> - 업로드 중에는 버튼에 `Uploading… NN%` 가 표시된다
> - Cloudinary 계정은 **필요 없다.** 이미지 저장은 전적으로 S3 다(`imageUtil.js`)
>
> 회귀 테스트: `frontend/src/components/admin/CreateProduct.test.jsx`
>
> **SKU 는 비워도 된다.** 서버가 `<카테고리 접두사 3자>-<순번 6자리>`(예: `FAS-100042`)로
> 만든다 — 번호는 DB 시퀀스(V7)라 레플리카가 몇 개든 충돌하지 않는다. 직접 넣으면
> 중복일 때 400 으로 알려준다. `product_sku.sku` 에는 이제 UNIQUE 제약이 있다.
>
> **이미 시드를 넣은 DB 라면**: 시드의 옛 SKU(`브랜드-카테고리-서브카테고리`)는 V7 이
> 중복만 정리하고 형식은 그대로 둔다(관리자가 손으로 넣은 값을 함부로 바꾸면 안 되므로
> 마이그레이션은 최소만 한다). 전부 샘플 데이터라 새 형식으로 통일하고 싶다면 —
> 선택 사항이다:
>
> ```sql
> UPDATE product.product_sku ps
> SET sku = (CASE WHEN length(regexp_replace(c.category_name,'[^A-Za-z0-9]','','g')) >= 3
>                 THEN upper(substr(regexp_replace(c.category_name,'[^A-Za-z0-9]','','g'),1,3))
>                 ELSE 'PRD' END) || '-' || lpad(ps.skuproduct_id::text, 6, '0')
> FROM product.product p
> JOIN product.category c ON c.category_id = p.category_id
> WHERE ps.product_id = p.product_id;
> ```

### ES 로 전환한 경우만

| | 확인 |
|---|---|
| ☐ | `ops/03-enable-es-search.sh` 가 `korean_analyzer` 검증 통과 |
| ☐ | **한글 형태소 검색** — "청바지"로 검색해 "청 바지"류가 나오는가. nori 없이는 조용히 나빠질 뿐 오류가 안 난다 |
| ☐ | 색인 동기화 (`POST /api/products/sync`) |

---

## 4. 장바구니 → 주문 → 결제 (사가 본체)

화면: `/product/:slug` → `/cart` → `/checkout` → `/order/:id`

| | 확인 | 실패를 어디서 보나 |
|---|---|---|
| ☐ | 장바구니 담기 (`POST /api/cart/savecart`) | Redisson 분산 락 경로 — **관리형 Redis(Upstash)를 쓴다면 여기가 첫 관문이다**(docs/32 §6) |
| ☐ | 수량 변경 · 삭제 | `PUT /api/cart/updatecart`, `DELETE /api/cart/deleteItem/{id}` |
| ☐ | 주소 등록·선택 (`POST /api/user/profile/save_address`) | |
| ☐ | 쿠폰 적용 (`POST /api/coupon/applycoupon`) | |
| ☐ | 주문 생성 (`POST /api/order/create`) | |
| ☐ | **재고가 정확히 1회 차감** | `select stock from product_sku where ...` 를 주문 전후로 비교. 사가 재전달 시 이중 차감이 실제로 있었다 |
| ☐ | 결제 (`POST /api/pay/confirm`) | |
| ☐ | 주문 상태가 최종까지 진행 | `/order/:id` 화면 + `select status from orders` |
| ☐ | 장바구니가 비워짐 | `cart-empty` 이벤트 경로 |

### 🔒 결제 위조 (docs/35)

**반드시 한다.** 이 경로는 예전에 완전히 열려 있었다.

```bash
# 자기 주문에 대고, 결제하지 않고 결제 완료를 주장한다.
curl -i -X POST https://shop.example.com/api/order/payment/paypal \
  -H "Authorization: Bearer <자기 토큰>" -H 'Content-Type: application/json' \
  -d '{"trackingId":"<자기 주문>","paypalOrderId":"위조-1","amounts":0.01,
       "paymentStatus":"COMPLETED","orderStatus":"NOT_PROCESSED"}'
```

- ☐ **400 이어야 한다.** 200 이면 배포를 멈춘다
- ☐ order-service 로그에 `PaypalVerificationException`
- ☐ `select * from orders.payment_outbox_event where ...` — **아웃박스에 아무것도 없어야 한다.**
  거부 응답을 받고도 아웃박스에 들어갔다면 결제는 이미 하류로 전파된 것이다
- ☐ 정상 결제 1건을 마친 뒤, 그 `paypalOrderId` 를 **다른 주문**에 붙여 본다 → 거부
- ☐ 기동 로그에 `⚠️ PayPal 결제 검증이 꺼져 있습니다` 가 **없다**. 있으면 검증이 꺼진 것이다

### 실패 경로 — 성공보다 중요하다

| | 확인 | 방법 |
|---|---|---|
| ☐ | **재고 부족 주문이 롤백된다** | 재고를 1로 만들고 2개 주문. 재고가 음수가 되면 안 되고, 쿠폰·장바구니도 원복돼야 한다(`cart-rollback`, `coupon-rollback`) |
| ☐ | 결제 실패 시 주문이 매달려 있지 않다 | PayPal 취소로 이탈 |
| ☐ | **같은 요청을 두 번 보내도 주문이 하나** | 멱등성 키. 브라우저 새로고침으로 재현 |

### 비동기 경로 — 화면만 봐서는 알 수 없다

```sql
-- 아웃박스가 밀리지 않는가 (PENDING 이 쌓이면 폴러가 죽은 것)
select status, count(*) from users.outbox_event_p group by status;
select status, count(*) from orders.payment_outbox_event group by status;
```

- ☐ 아웃박스 `PENDING` 이 계속 쌓이지 않는다
- ☐ 컨슈머 그룹 lag 이 0 으로 수렴 (§0 의 ④번 창)
- ☐ **DLT 가 비어 있다** — `kafka-console-consumer --topic order.DLT --from-beginning`. 여기에 뭔가 있으면 그게 곧 버그 목록이다

---

## 5. 배송

화면: `/profile/orders` → 주문 상세

| | 확인 | 비고 |
|---|---|---|
| ☐ | 주문 후 배송 레코드 생성 (`GET /api/deliveries/order/{orderId}`) | |
| ☐ | 택배사 목록 (`GET /api/deliveries/carriers`) | 키가 없으면 `SIMULATED` 만 나온다 — 그게 정상이다 |
| ☐ | 배송 조회 갱신 (`POST /api/deliveries/order/{orderId}/tracking`) | |
| ☐ | 배송지 변경 (`PATCH .../address`) | 발송 후에는 거부돼야 한다 |
| ☐ | 배송 취소 (`POST .../cancel`) | |

---

## 6. 알림 · 메일 · SMS

| | 확인 | 실패를 어디서 보나 |
|---|---|---|
| ☐ | 알림 목록 (`GET /api/notifications`) | |
| ☐ | 안 읽음 수 (`GET /api/notifications/unread-count`) | |
| ☐ | 읽음 처리 (`PATCH /api/notifications/{id}/read`, `read-all`) | |
| ☐ | 수신 설정 (`GET`/`PATCH /api/notifications/preferences`) | |
| ☐ | **거부해도 가입확인·비밀번호재설정 메일은 나간다** | `MailKind.isTransactional()` — 이걸 옵트아웃으로 막으면 계정을 못 만든다 |
| ☐ | SSE 실시간 알림 (`GET /sse`) | 다른 탭에서 이벤트를 일으켜 본다 |
| ☐ | 휴대폰 인증 (`POST /api/notifications/phone/verification` → `/confirm`) | |
| ☐ | 🔒 **인증번호가 로그에 없다** | `kubectl logs -l app=notification-service \| grep -E '[0-9]{6}'` — 보이면 즉시 중단. DB 에도 페퍼 해시만 있어야 한다 |
| ☐ | 🔒 **전화번호가 마스킹된다** | 로그에 `010-****-1234` 형태 |

### 실제 발송까지 확인하려면

```bash
kubectl -n ecommerce set env deploy/notification-service NOTIFICATION_MAIL_PROVIDER=smtp
```

- ☐ 실제 수신함에 도착
- ☐ 🔒 **발송 후 `mail_message.params` 가 비워진다** — 확인 링크·재설정 링크는 자격증명이다(docs/29). `select params from mail_message where status='SENT'` 가 전부 null 이어야 한다
- ☐ SMTP 일시 실패 시 재시도 후 5회에서 멈춘다 (그레이리스팅)

---

## 7. 채팅

화면: `/chat/chatrooms`

| | 확인 | 비고 |
|---|---|---|
| ☐ | 방 생성 (`POST /api/chat/room`) · 목록 (`GET /api/chat/rooms`) | |
| ☐ | 두 브라우저에서 실시간 송수신 | WebSocket/STOMP |
| ☐ | 메시지가 MongoDB 에 남는다 | `db.chat.find().sort({_id:-1}).limit(5)` |
| ☐ | 새로고침 후 이력 유지 (`GET /api/chat/room/{roomId}`) | |

> ⚠️ chat-service 는 **레플리카 1 고정**이다. WebSocket 세션이 파드에 붙어 있어 늘리려면
> 세션 공유가 먼저다(docs/21). 파드가 재시작되면 연결이 끊기는 것이 **정상**이다.

---

## 8. 🔒 권한 — 남의 것에 손이 닿는가 (docs/27)

계정을 **둘** 만들어서 A 의 토큰으로 B 의 자원을 요청한다.

| | 시도 | 기대 |
|---|---|---|
| ☐ | `GET /api/order/{B의 orderId}` | 403/404 |
| ☐ | `GET /api/user/profile/select/{B의 addressId}` | 403/404 |
| ☐ | `DELETE /api/user/profile/delete/{B의 addressId}` | 403/404 |
| ☐ | `GET /api/deliveries/order/{B의 orderId}` | 403/404 |
| ☐ | `DELETE /api/notifications/{B의 id}` | 403/404 |
| ☐ | `PUT /api/admin/update-role` (일반 계정) | 403 |
| ☐ | `GET /api/admin/getusers` (일반 계정) | 403 |
| ☐ | `POST /api/review/add/{productId}` (미로그인) | 401 |

**하나라도 200 이면 배포를 멈춘다.**

### 액추에이터 노출

```bash
curl -s https://shop.example.com/actuator/env | head
```

- ☐ 외부에서 `/actuator/env`·`/heapdump` 가 **열려 있지 않다**. 시크릿이 그대로 나온다

---

## 9. 레이트리밋 · 캐시 · 관측성

| | 확인 | 방법 |
|---|---|---|
| ☐ | 게이트웨이 레이트리밋 | `for i in $(seq 1 70); do curl -s -o /dev/null -w "%{http_code} " URL; done` → 429 가 나와야 한다. 안 나오면 Redis 미연결이다 |
| ☐ | 인증번호 발급 속도 제한 | 요금이 걸린 경로다 |
| ☐ | Prometheus 타깃 전부 UP | `ops/05-check-observability.sh` |
| ☐ | Grafana 대시보드에 서비스별로 구분되어 나온다 | 전부 `common` 으로 뭉치면 `spring.application.name` 문제(docs/14 B-22) |
| ☐ | Zipkin 에서 한 요청이 여러 서비스에 걸쳐 보인다 | 주문 하나를 넣고 추적 |
| ☐ | 경보가 실제로 도달 | `ops/17-configure-alert-receivers.sh` |

---

## 10. 외부화한 인프라 (docs/32 를 적용한 경우)

| | 확인 | 왜 |
|---|---|---|
| ☐ | **Redisson 락이 동작** (장바구니 담기) | Upstash 무료 플랜의 Lua·pub/sub 지원이 미검증이다. `ERR unknown command` 나 타임아웃이면 Redis 를 클러스터 안으로 되돌린다 |
| ☐ | 산발적 `prepared statement "S_1" already exists` 가 없다 | 있으면 풀러 엔드포인트를 쓰는 것이다. JDBC URL 에 `&prepareThreshold=0` 이 필요하다(docs/32 §9, 미해결 항목) |
| ☐ | 응답 시간이 로컬 대비 크게 나빠지지 않았다 | DB 가 다른 리전이면 쿼리 20개짜리 요청이 600ms 가 된다 |
| ☐ | 커넥션 한도 여유 | `select count(*) from pg_stat_activity;` vs `show max_connections` |
| ☐ | provider 콘솔에서 접근 IP 가 제한되어 있다 | 무료 티어라 불가능하면 비밀번호가 유일한 방어선이다 |

---

## 11. 확인할 수 **없는** 것 — 미리 알고 있을 것

OCI 한 대 구성에서는 HPA `minReplicas=1` 로 내려야 자원이 맞는다(docs/32 §2). 그 상태에서는
**이 저장소가 레플리카 2에서 겪은 버그들이 재현되지 않는다:**

- 아웃박스 폴러가 레플리카마다 같은 이벤트를 발행
- 메일·SMS 디스패처가 잠금 없이 같은 행을 두 번 집음

동시성은 `ops/12-staging-rehearsal.sh` 나 prod 오버레이(레플리카 2)로 **따로** 확인해야 한다.
"운영에서 잘 돌던데요"는 이 항목들에 대해서는 증거가 아니다.

---

## 12. 결과 기록

발견한 것은 이 형식으로 남긴다. **그대로 다음 회귀 테스트의 목록이 된다.**

```
[번호] 화면/엔드포인트
  재현:      무엇을 눌렀는가 (남이 그대로 따라 할 수 있게)
  기대:
  실제:
  단서:      로그 · DB 쿼리 결과 · DLT · lag 중 무엇을 보고 알았나
  분류:      🔴 배포 중단 / 🟠 고쳐야 함 / 🟡 알고 넘어감
```

🔴 는 §2 헤더 위조, §8 권한, 로그에 남은 비밀 셋뿐이다. 나머지는 우선순위를 매겨
순서대로 처리한다.

---

## 13. 이 문서의 한계

- **여기 적힌 엔드포인트는 소스에서 뽑았지만, 실제로 이 순회를 돈 적은 없다.** 화면 흐름과
  API 의 연결이 어긋난 곳이 있을 수 있다 — 그것도 발견 사항으로 기록할 것
- 항목은 **알려진 위험**(docs/14 · 16 · 21 · 27 · 29 · 32 에서 실제로 터진 것들)을 중심으로
  뽑았다. 한 번도 문제가 없었던 영역은 상대적으로 얇다
- 자동화하지 않았다. 1회차에서 무엇이 깨지는지 모르는 상태에서 스크립트를 짜면 대부분
  버리게 된다. **2회차부터** 반복되는 항목을 `ops/06` 에 흡수시키는 것이 맞다
