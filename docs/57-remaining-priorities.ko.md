# 57. 남은 우선순위 — docs/54 §10.1 의 열린 항목을 차례로

[日本語](57-remaining-priorities.md) | **한국어**

> 🕰️ **상태(2026-09-27) — 일부 낡음.** §11 의 "서비스 간 mTLS 는 여전히 없다" 는 같은 문서 §16 · §18 에서 해결했습니다.

> 처음 우선순위 목록(초과 판매·통화 표시·사가 지연)을 끝낸 뒤, docs/54 §10.1 에 "열린 항목" 으로 남아 있던
> 것 중 **코드로 닫을 수 있는 것**을 심각도·작업량 순으로 처리한 기록이다. 절 하나가 커밋 하나다.

## 1. 예외 문구가 응답 본문으로 새던 것

### 증상

- 공통 예외 처리기(`CustomRestExceptionHandler.handleAll`)의 500 이 `ex.getLocalizedMessage()` 를 본문에 실었다.
- 컨트롤러 **57곳**(cart·catalog·order·user)이 `catch (RuntimeException e)` 로 받아 `new MessageResponse(e.getMessage())`
  를 돌려줬다. chat 의 예외 처리기 둘도 같았다.
- 400 처리기들도 `getLocalizedMessage()` 를 실었다 — 검증 실패면 `Validation failed for argument [0] in public
  ResponseEntity com.project....Controller.method(...)`, 즉 **컨트롤러 메서드 시그니처**다. 제약 위반은 루트 빈의
  전체 클래스 이름을 붙였다.

### 왜 문제인가

`catch (RuntimeException e)` 는 서비스가 사람에게 쓴 문구("사용 기간이 지난 쿠폰입니다.")와 **라이브러리 예외를
함께** 받는다. JDBC·JPA 예외의 문구에는 SQL, 테이블·컬럼·제약 이름, 입력값이 있고(`Key (user_id, coupon_id)=(42, 7)
already exists`), Feign·Redis 예외에는 내부 호스트가 있다. JVM 의 NPE 문구(`Cannot invoke "…Order.getTotal()" because
"order" is null`)는 코드 구조다 — docs/47 에서 실제로 화면에 떴다.

반대로 문구를 일괄 일반화하면 **화면이 쓰는 문구가 사라진다**(비밀번호 재설정 링크 만료, 쿠폰 거절 사유 등).
그래서 "숨긴다" 가 아니라 "고른다" 가 필요했다.

### 한 것 — `common/exception/ClientMessage`

허용 목록이다. 문구를 내보내는 것은 둘뿐이다.

1. `com.project.` 아래의 예외(도메인 예외).
2. 우리 코드가 문구를 담아 던지는 표준 예외 넷 — `RuntimeException`·`IllegalArgumentException`·
   `IllegalStateException`·`NoSuchElementException` — **정확히 그 클래스일 때만**. 하위 클래스는 아니다: 라이브러리
   예외 대부분이 이 넷의 하위 클래스다(`NumberFormatException`, `DataAccessException` 계열의 조상 등).

그리고 감싸기만 한 예외(`new RuntimeException(e)` — 메시지가 원인의 `toString()`)는 허용 목록이어도 원인의 문구라
숨긴다. 나머지는 일반 문구다. 원래 문구는 로그에 남는다.

거부 목록(JDBC·JPA·…를 막는다)으로 하지 않은 이유: 목록에 없는 라이브러리가 하나 들어오면 **새는 쪽으로** 틀린다.
허용 목록은 틀려도 일반 문구가 나갈 뿐이다.

| 자리 | 전 | 후 |
|---|---|---|
| 컨트롤러 57곳 | `e.getMessage()` | `ClientMessage.of(e)` |
| 공통 500 | `ex.getLocalizedMessage()` | `ClientMessage.of(ex, "서버 오류가 발생했습니다…")` + 스택은 로그 |
| `CustomException` | 500 으로 떨어짐 | `ErrorCode` 의 상태·문구(🐛 `USER_NOT_FOUND` 가 404 가 아니라 500 이었다) |
| 400 처리기 | 메서드 시그니처·클래스 이름 | 고정 문구, 무엇이 틀렸는지는 `errors`(필드 이름·검증 문구) |
| chat 500 | `e.getMessage()` | 고정 문구 + 로그 |
| user 401 | 스프링 시큐리티 문구 | "인증이 필요합니다." |

### 가드

`ExceptionMessageGuardrailTest`(common) — 컨트롤러·`@ControllerAdvice` 파일에서 `getMessage()` 가 로그 호출 밖에
있으면 실패한다. 문장 단위로 보고(여러 줄 `log.warn(...)` 도 로그로 인정), 문자열 리터럴 안의 `{}` 와 줄 끝 주석의
`'` 는 경계로 읽지 않는다. 한 자리를 되돌려 실패하는 것을 확인했다.

⚠️ Gradle 은 이 테스트가 다른 모듈의 소스를 읽는다는 것을 모른다 — common 이 바뀌지 않았으면 **UP-TO-DATE 로
건너뛴다.** 확인할 때는 `:common:cleanTest :common:test`.

### 남은 것

- 컨트롤러 밖(서비스)에서 응답을 만드는 곳은 가드 범위가 아니다. 찾아본 범위에서는 없었다.
- ~~우리 코드의 `IllegalStateException` 중에는 사람에게 쓴 것이 아닌 문구도 있다~~ → §15 에서 허용 목록에서 뺐다.

## 2. 리프레시 토큰의 수명이 사실상 무한이던 것

### 증상

| 자리 | 코드 | 결과 |
|---|---|---|
| Redis | `set(key, token)` 뒤 `expire(key, System.currentTimeMillis() + jwtRefreshExpirationMs, MILLISECONDS)` | 기간 자리에 **epoch 시각**(약 1.8×10¹² ms)이 들어가 TTL 이 약 **56년** |
| 쿠키 | `createCookie(REFRESH, token, jwtRefreshExpirationMs)` → `setMaxAge(int)` | `Max-Age` 는 초인데 밀리초(1,209,600,000)를 넣어 14일짜리 쿠키가 약 **38년** |

2FA 대기 쿠키와 기기 쿠키는 초를 제대로 넘기고 있었다 — 같은 메서드를 부르는 쪽마다 단위가 달랐다.

### 왜 문제인가

리프레시 토큰 JWT 자체에는 14일 만료가 있어 **오래된 토큰으로 로그인이 되지는 않는다.** 그러나

- 로그아웃하지 않은 세션의 Redis 키가 지워지지 않고 계속 쌓인다(메모리).
- 브라우저는 만료된 토큰을 38년 동안 들고 다니며 매번 보낸다. 서버의 수명 정책(14일)과 클라이언트의 수명이 어긋나면,
  나중에 누가 JWT 만료 검사를 느슨하게 바꾸는 순간 그 쿠키들이 곧바로 살아난다.

### 한 것

- `JwtUtils.createCookie(key, value, Duration maxAge)` — 단위를 **타입으로** 못 박는다. 호출 7곳을 모두 `Duration` 으로
  (리프레시는 `refreshTokenLifetime()`, 2FA 대기는 `Duration.ofMillis(TTL_MS)`, 기기는 `ofSeconds(...)`, 지우기는 `ZERO`).
- `RefreshTokenRepository.save` — `set(key, token, Duration)` 한 명령. set 과 expire 사이에 죽어 TTL 없는 키가 남는 틈도 없다.
- 테스트 `RefreshLifetimeTest`(3) — 쿠키 Max-Age 가 1,209,600초, 지우는 쿠키 0, Redis 에 TTL 14일로 한 번에 쓰고 `expire` 를
  부르지 않는다. 기존 테스트의 쿠키 목(mock)도 `Duration` 을 받게 고쳤다.

로그아웃 블랙리스트(`registerBlacklist`)는 이미 **남은 시간**(만료 시각 − 지금)을 넘기고 있어 고칠 것이 없었다.

### 운영 — 이미 쌓인 키

배포 뒤 새로 발급되는 토큰은 14일로 들어간다. 이미 있는 키는 그대로 약 56년이다. 로그아웃하면 지워지지만, 그렇지 않은
세션은 남는다. 필요하면 TTL 이 14일보다 긴 키만 14일로 줄인다(값은 출력하지 않는다):

```bash
# k8s/90-dev-infra.yaml 의 Redis(네임스페이스 redis). 비밀번호는 파드 안의 REDIS_PASSWORD(시크릿 redis-auth) — 밖으로 꺼내지 않는다.
kubectl -n redis exec -it deploy/redis -- sh -c '
  redis-cli -a "$REDIS_PASSWORD" --no-auth-warning --scan | while read k; do
    t=$(redis-cli -a "$REDIS_PASSWORD" --no-auth-warning ttl "$k")
    [ "$t" -gt 1209600 ] && redis-cli -a "$REDIS_PASSWORD" --no-auth-warning expire "$k" 1209600 >/dev/null && echo "$k"
  done | wc -l'
```

14일보다 긴 TTL 을 정상으로 갖는 키는 이 저장소에 없다(세션·블랙리스트·잠금은 모두 그보다 짧다). 줄인 키의 **개수**만 찍는다.

⚠️ 쿠키는 브라우저에 있다 — 다음 로그인·토큰 갱신 때 새 `Max-Age` 로 덮인다. 서버에서 따로 할 일은 없다.

## 3. 상품 별점 — 아무나 덮어쓰던 것, 그리고 리뷰가 저장되지 않던 것

### 증상

`PUT /api/product/rating?id=&rating=` 을 catalog 의 쓰기 인터셉터(`AdminWriteInterceptor`)가 "로그인만" 요구했다.

| 호출하는 쪽 | 신원 헤더 | 결과 |
|---|---|---|
| 브라우저(로그인한 아무나) | 있음 | ✅ **통과** — 아무 상품의 별점을 임의 값(범위 검사 없음 — 100, NaN 도)으로 덮어쓸 수 있었다 |
| user-service `ReviewService.publishRating`(리뷰 평균) | **없음**(Feign 은 헤더를 싣지 않는다) | ❌ **401** — 별점 예외보다 신원 검사가 먼저였다 |

두 번째가 더 컸다. `publishRating` 은 리뷰 저장과 **같은 트랜잭션**에서 예외를 던졌고, 컨트롤러가 그것을 "리뷰 등록 실패" 로
바꿨다. 코드상으로는 이 인터셉터가 들어온 뒤(`3d130d1`) **리뷰 작성이 늘 실패**한다(운영에서 재현하지는 않았다 — 운영 로그의
`REVIEW_REGISTRATION_FAILED` 로 확인할 수 있다).

정당한 호출자는 하나뿐이다 — 별점은 사람이 매기는 값이 아니라 리뷰의 평균이다. 프론트는 이 경로를 부르지 않는다.

### 한 것

- **내부 전용** — 별점 경로는 `InternalCallGuard.allowed`(신원 헤더 없음 = 내부, 또는 관리자)만 통과. 일반 사용자는 403.
  신원 검사보다 **먼저** 본다(내부 호출에는 헤더가 없다). 게이트웨이의 쓰기 라우트(`catalog-service-write`)는 항상 JWT 필터를
  거치므로 밖에서 온 PUT 에는 신원 헤더가 반드시 있다 — "헤더 없음 = 내부" 는 다른 내부 API 와 같은 전제다(docs/27 §9b).
- **범위 검사** — 0 이상 5 이하, 유한값만. 벗어나면 400.
- **리뷰를 평점에 묶지 않는다** — `publishRating` 이 실패하면 경고만 남기고 리뷰는 저장한다. 평점은 다시 계산할 수 있는 파생 값이고,
  다음 리뷰가 다시 보낸다.

테스트: 인터셉터(일반 사용자 403·내부 통과·관리자 통과), 컨트롤러(403·내부 200·범위 밖 400 셋), 리뷰(평점 반영 실패에도 저장).

### 남은 것

- ~~평점 반영이 실패한 채로 새 리뷰가 없으면 평점이 어긋난 채 남는다~~ → §13 매일 대사.
- ~~"구매한 사람만 리뷰" 검증은 없다~~ → §13.

## 4. 게이트웨이 라우트 id 중복 — 로그인 레이트리미트가 덮일 수 있던 것

`gateway-server/.../application-local.yml` 에 라우트 id `user-service` 가 **두 번** 있었다.

| 라우트 | 경로 | 레이트리미트 |
|---|---|---|
| 첫째 | `/api/auth/public/**`(로그인·가입), 토큰 갱신, OAuth2 콜백 — 인증 **전** | **1/s, 버스트 10**(IP 기준 — 무차별 대입 방어) |
| 둘째 | `/api/user/**`, `/api/auth/**`, SSE — 인증 **후** | 전역 기본값 20/s, 버스트 40 |

`RedisRateLimiter` 는 설정을 **라우트 id 로** 저장한다. id 가 같으면 둘째 라우트의 기본값이 첫째의 강한 제한을 덮을 수 있다 —
로그인 대입 방어가 설정 한 줄로 사라지는데, 평소에는 아무 증상이 없다(docs/38 §2.6 #4 에서 "실측 필요" 로 남겨 둔 것).

- id 를 `user-service-public` / `user-service-auth` 로 나눴다. 라우트 id 를 참조하는 곳(대시보드·설정)은 없었다.
- 가드 **B-62**(`ConfigurationGuardrailTest`) — 게이트웨이 설정 파일 안에서 라우트 id 가 겹치면 실패한다. 중복을 되살려 실패를
  확인했다.

⚠️ 게이트웨이 메트릭(`spring_cloud_gateway_requests_seconds{routeId=…}`)의 라우트 이름이 바뀐다. 이 저장소의 대시보드·경보는
라우트 id 를 쓰지 않는다.

## 5. 관리자 주문 화면에 재고 부족 수량

재고 초과 판매 보상(docs/53 §7)은 부족분을 품목의 `short_qty` 로 적고 자동 환불한다. 서버 응답(`AdminOrderItemDto.shortQty`)에는
있었는데 **화면이 보여 주지 않았다.** 그래서 docs/53 §7 의 남은 한계 — "배송이 부족 기록보다 먼저 만들어졌다면 배송 수량이 원래
수량 그대로다, 관리자가 송장을 맞춰야 한다" — 를 관리자가 알아챌 방법이 없었다.

- 품목 요약에 `재고 부족 N`, 주문 줄에 빨간 경고 "재고 부족 N개 — 자동 환불 대상, 발송 수량에서 빼 주세요".
- 🐛 반품 입고 패널의 상한이 `qty − returnedQty` 였다 — 나간 적 없는 부족분까지 "돌아올 수 있는 수" 로 보였다. 서버의
  `OrderProduct.remainingToReturn`(`qty − shortQty − returnedQty`)과 같은 식으로 맞췄다(서버는 원래 초과 입고를 거절한다 —
  화면이 거절될 선택지를 내밀던 것).
- 테스트 3(요약·경고, 부족 없으면 경고 없음, 입고 상한).

## 6. CI 비밀 스캔

유출 재발 방지 장치가 푸시·PR 단계에 없었다(docs/30 §5) — `ops/20` 은 사람이 가끔 돌렸다. 공개 저장소의 유출은 **커밋되는 순간**
끝난 일이다(스크레이퍼가 몇 분 안에 읽는다). 가장 싼 검사를 가장 앞에 둔다.

### `ops/20 --range A..B` — 새 모드

| 모드 | 무엇을 보나 | 놓치는 것 |
|---|---|---|
| `--head` | 지금 트리 | PR 안에서 **넣었다가 다음 커밋에서 지운** 비밀 — 머지되면 이력에 그대로 남는다 |
| (기본) 이력 전체 | 모든 blob | 없음. 대신 무겁다(푸시마다 돌리기엔) |
| `--range A..B` ⭐ | 그 범위의 커밋이 만든 blob | 범위 밖 — 그건 이미 들어온 이력이다(이력 전체 모드의 몫) |

양성 대조: 임시 저장소에서 가짜 AWS 키 모양을 커밋하고 다음 커밋에서 지웠다 — `--head` 는 0건, `--range` 는 **1건·exit 2**,
출력에는 종류와 경로만 있고 값은 없다.

### CI(`build.yml` 의 `secrets` 잡)

- 모든 푸시·PR 에서(주간 복원 훈련 스케줄은 제외) `--head` 와 `--range` 를 돈다. 범위는 PR 이면 `origin/<대상>..HEAD`, 푸시면
  `<before>..HEAD`, 새 브랜치의 첫 푸시(before = 0000…)면 `origin/<기본 브랜치>..HEAD`.
- 찾으면 실패하고 **릴리스 후보 승격(`release-candidate`)이 막힌다** — `needs` 에 넣었다.
- 로그가 공개여도 새로 새지 않는다 — 스크립트는 값을 출력하지 않는다.

### 남은 것

- **GitHub Push Protection**(푸시 자체를 거절)은 저장소 설정이다 — 코드로 켤 수 없다. Settings → Code security → Secret
  scanning → Push protection. CI 는 푸시 **뒤에** 알리므로, 막는 것은 이쪽이다.
- ~~`frontend/dist.zip`(옛 맵·옛 호스트 포함)이 아직 추적된다~~ → §12 에서 추적을 끊었다. 이력의 사본은 남는다(docs/38 §3 #1).

## 7. 서비스의 actuator·API 명세가 게이트웨이로 새던 것

### 증상

게이트웨이 라우트는 `/catalog-service/**` 처럼 서비스 접두사 아래를 통째로 넘기고 `RewritePath` 로 접두사만 뗀다. 그리고
모든 서비스가 공통 설정(`application-common.yml`)으로 `prometheus, health, info, metrics` 를 노출하고, springdoc 이 들어 있다.

| 요청 | 닿는 곳 | 로그인 |
|---|---|---|
| `GET /catalog-service/actuator/prometheus` | catalog 의 지표 전부 | **필요 없다**(catalog 읽기 라우트는 공개) |
| `GET /catalog-service/v3/api-docs` | catalog 의 API 명세 — 관리자 API 포함 | 필요 없다 |
| `GET /order-service/actuator/metrics/...` 등 | 다른 서비스 | 로그인만 하면 |

docs/54 §10.1 에 "추정" 으로 남겨 둔 것이다. 라우트·노출 설정을 읽으면 그대로 닿는다(실제 요청으로는 확인하지 않았다).
지표에는 내부 경로·호출량·오류율·JVM 상태가, 명세에는 관리자 API 의 전체 모양이 있다 — 공격 준비에 쓰이는 정보다.

### 한 것 — 게이트웨이 한 곳에서

`InternalEndpointBlockFilter`(GlobalFilter, 가장 먼저): 경로가 `/<…-service>/{actuator|v3|swagger-ui|swagger-ui.html|api-docs|
swagger-resources|webjars}/…` 이면 **404**(있다는 사실도 알리지 않는다).

- 서비스마다 막지 않은 이유: 라우트·서비스가 늘 때 빠진다. 입구가 하나다.
- 우회 방지 — 경로를 한 번 URL 디코딩하고, 겹친 슬래시를 접고, 소문자로, 매트릭스 변수(`;x=…`)를 떼고 본다
  (`%61ctuator`, `//actuator`, `/Actuator`, `actuator;x=1` 전부 차단 — 테스트로 고정).
- 영향 없는 것: Prometheus 는 서비스를 **직접** 긁는다(observability/prometheus.yml). 게이트웨이 자신의 `/actuator/**` 는 첫 조각이
  서비스 이름이 아니라 대상이 아니다(그 경로는 터널이 밖으로 넘기지 않는다 — docs/27 §8).

테스트 `InternalEndpointBlockFilterTest`(4) — 차단 다섯, 우회 다섯, 통과(업무 API·이름이 비슷한 업무 경로·게이트웨이 자신), 404 로
끝나고 뒤로 넘기지 않음.

### 남은 것

- ~~서비스 안에서도 springdoc 끄기~~ → §12(클러스터 ConfigMap). 관리 포트 분리(`management.server.port`)는 남았다 — 프로브·
  Prometheus 대상이 함께 바뀌어야 한다.

## 8. 리프레시 토큰이 액세스 토큰으로 통하던 것 · user-service 가 만료 토큰을 받던 것

### 증상

| 자리 | 코드 | 결과 |
|---|---|---|
| user-service `JwtUtils` | 액세스·리프레시를 **같은 키·같은 클레임**(sub·uid·role·sessionId)으로 서명 | 두 토큰을 구분할 수 없다 |
| 게이트웨이 `JwtAuthorizationGatewayFilterFactory` | 서명·만료·uid 만 본다 | 14일짜리 **리프레시 토큰을 `Authorization: Bearer` 로 보내면 통과** — 로그아웃한 뒤에도(로그아웃은 Redis 의 리프레시 기록만 지운다) |
| chat-service `TokenHandler.verifyToken` | 서명·만료만 | STOMP CONNECT·API 에 리프레시 토큰으로 붙는다 |
| user-service `JwtAuthFilter` | 만료 검사가 주석 처리, `getIdFromJwtToken` 이 `ExpiredJwtException` 을 잡아 subject 를 돌려줌 | **만료된 액세스 토큰을 유효로** 받았다 |

리프레시 쿠키는 경로가 `/` 라 모든 요청에 실려 다닌다(HttpOnly 라 스크립트는 못 읽지만). 한 번 새면 14일짜리 API 열쇠가 되고,
로그아웃으로 닫히지 않았다.

### 한 것

- 토큰에 종류 클레임 `tokenType` = `access` | `refresh`(user-service `JwtUtils.TOKEN_TYPE_CLAIM`). 액세스를 만드는 네 경로 모두.
- **게이트웨이·chat·user-service 는 `access` 만** 신원으로 받는다. 클레임이 없는 옛 토큰도 거절 — 화면은 401 이면 리프레시로 새
  토큰을 받는다(TokenRefresher).
- 리프레시·로그아웃 경로는 **`access` 를 거절**하고, `refresh` 또는 클레임 없는 옛 리프레시를 받는다(옛 리프레시는 최대 14일 뒤
  자연히 사라진다 — 그때 이 호환을 걷어내면 된다).
- user-service 인증 필터: 유효한 액세스 토큰일 때만 인증을 세우고, 아니면 인증 없이 넘긴다 — 보호 경로는 `JwtAuthEntryPoint`
  가 401, 공개 경로(가입·비밀번호 찾기)는 오래된 쿠키가 실려 와도 막히지 않는다. `getIdFromJwtToken` 은 만료면 던진다.
- 차단 목록 조회 로그(`blackLisTToken : …`)를 뺐다.

테스트: `TokenTypeTest`(user, 4), `JwtAccessTokenTypeTest`(gateway, 3), `TokenHandlerTypeTest`(chat, 2).

### ⚠️ 배포 순서 — user-service 가 먼저

게이트웨이·chat 을 먼저 올리면, 아직 옛 user-service 가 **종류 없는 토큰**을 발급하므로 갱신해도 계속 401 → 모두 로그아웃된다.

```bash
./ops/33-deploy-some.sh user-service gateway-server chat-service   # user-service → 백엔드 → 게이트웨이 순서로 하나씩 올린다
```

`ops/33` 은 이미 user-service 를 게이트웨이보다 먼저 올리고 둘을 짝으로 묶는다. 전체 배포(`ops/28`)도 같은 버전으로 수렴하므로
몇 초의 섞인 구간 뒤에는 정상이다.

배포 직후 로그인해 있던 사용자는 요청 한 번이 401 → 자동 갱신으로 넘어간다(화면에는 보이지 않는다).

## 9. 게이트웨이 자동 라우트(discovery locator)를 껐다

`spring.cloud.gateway.discovery.locator.enabled: true` 였다. Eureka 에 등록된 서비스마다 `/ORDER-SERVICE/**` 같은 라우트가 저절로
생기는데, **거기에는 JWT 필터가 없다.** 전역 필터(`IdentityHeaderScrubFilter`)가 클라이언트의 신원 헤더를 지우므로, 그 경로로 들어온
요청은 하위 서비스에 **신원 헤더 없이** 도착한다 — `InternalCallGuard.allowed` 가 "내부 호출" 로 믿는 바로 그 모양이다
(관리자 주문 목록, 내부 구매 통계, 배송 정보 조회 등).

밖에서 닿지 않았던 이유는 하나였다 — Cloudflare 터널이 **소문자** `…-service` 접두사만 게이트웨이로 넘긴다(대문자 서비스 id 의 자동
라우트와 맞지 않는다). 방어가 터널 설정의 정규식 한 줄에 달려 있었다.

쓰는 곳은 없었다 — 화면은 명시 라우트(`/order-service/**` 등)만, 서비스끼리는 Eureka 로 직접(`lb://`) 부른다. 껐고, 가드 **B-64**
(`ConfigurationGuardrailTest`)가 게이트웨이 설정에서 `locator: enabled: true` 를 막는다(켜서 실패하는 것을 확인했다).

## 10. 인증 SMS — 번호를 바꿔 가며 보내면 막히지 않던 것

`VerificationService.issue` 의 제한은 **번호 단위**뿐이었다(재전송 60초, 번호당 하루 10회). 주석에는 "번호당·IP당" 이라고 적혀
있었지만 IP 제한은 없었다. 번호를 바꿔 가며 요청하면 계정 하나로 번호마다 10회씩 — 사실상 무제한 실발송이고 요금은 우리가 낸다
(docs/38 §1.6).

| 겹 | 기준 | 한도 | 걸리면 |
|---|---|---|---|
| 번호 | 재전송 간격 · 하루 | 60초 · 10회 | 429 |
| **계정**(새로) | `User-Id` — 게이트웨이가 알림 경로 전부에 로그인을 요구하므로 늘 있다 | 하루 10회, 번호와 무관 | 429 |
| **전체**(새로) | 서비스 하루 발급 수 | `notification.verification.daily-global-limit`(기본 1000, 0 이하면 끔) | 429 + **ERROR 로그**(경보 대상) |

IP 로 제한하지 않은 이유: 이 서비스가 보는 IP 는 터널·게이트웨이를 지난 값이고, `X-Forwarded-For` 는 클라이언트가 위조할 수 있다
(결정에 쓰지 않는다는 원칙). 계정은 게이트웨이가 검증한 토큰에서 온다.

테스트 `VerificationLimitTest`(4) — 번호를 바꿔도 계정당 10회, 다른 계정은 따로, 전체 상한에서 멈춤, 번호당 한도 유지.

## 11. 네임스페이스 사이를 막는 NetworkPolicy

NetworkPolicy 가 하나도 없었다. 클러스터 안 **어느 파드든** user-service·order-service 의 내부 API 와 Postgres·Redis·Kafka·Mongo·ES
에 곧바로 붙을 수 있었고, 내부 API 의 "신원 헤더 없음 = 내부 호출"(`InternalCallGuard.allowed`)은 그 전제 위에 있다(docs/27 §14).

`k8s/95-network-policy.yaml` — **들어오는(ingress) 연결만**:

| 네임스페이스 | 허용 |
|---|---|
| ecommerce | ecommerce 안끼리(서비스끼리·cloudflared·백업 CronJob) + kube-system(traefik) → frontend·gateway-server |
| postgres·redis·kafka·mongodb·elasticsearch | 자기 네임스페이스 + ecommerce |

그 밖(다른 네임스페이스)에서 들어오는 연결은 막힌다. 노드 자신(kubelet 프로브·`kubectl exec/port-forward`·노드의 관측 스택)은 k3s 의
정책 컨트롤러가 정책과 무관하게 허용한다.

**나가는 연결은 막지 않았다** — 결제사·SMS·메일·택배사 API 와 DNS 를 쓰고, 그 목록이 틀리면 결제가 멈춘다. 들어오는 쪽만으로 "다른
네임스페이스에서 내부 API·DB 로" 가는 길은 닫힌다.

### 적용은 `ops/35-network-policy.sh apply` 로만

kustomization 에 넣지 않았다. NetworkPolicy 는 틀리면 **조용히** 끊고(파드는 Running 인데 결제가 멈춘다), 클러스터가 집행하지
않으면 적용해도 **조용히** 아무것도 막지 않는다. 그래서 두드려 본다:

```bash
./ops/35-network-policy.sh check    # 지금 상태 — 바꾸지 않는다
./ops/35-network-policy.sh apply    # 적용 → 확인 → 허용 흐름이 막히면 스스로 되돌린다(exit 1)
./ops/35-network-policy.sh remove   # 되돌리기
```

🐛 첫 실제 적용(2026-09-26)에서 MongoDB 한 곳이 "막혔다" 로 나와 스스로 되돌렸다. postgres·redis·kafka·es 는 같은 정책으로
통과했으므로 정책 탓이 아니라 **원래 닿지 않는 대상**(외부 Atlas 를 쓰거나 파드가 없다)이었을 가능성이 크다 — 그런데 스크립트는 적용
**후**에만 두드려 둘을 가르지 못했다. 이제 apply 는 적용 **전**에 한 번 두드려, "전에는 열렸는데 후에 막힌 것" 만 정책 탓으로 본다.

잠깐 뜨는 busybox 파드에서 `nc -z`(TCP 연결만, 자격증명 없음)로 — ecommerce 에서 서비스·DB 넷이 **열려 있는지**, default 에서
내부 API·DB 가 **막혔는지**, 그리고 ecommerce 파드가 준비 상태를 유지하는지. 막혀야 할 흐름이 열려 있으면 "집행되지 않는다" 로
exit 2(정책은 남긴다). 판정 로직은 가짜 kubectl 로 확인했고, **실제 클러스터에서는 아직 돌려 보지 않았다.**

### 남은 것

- 서비스끼리의 인증(mTLS·서비스 토큰)은 여전히 없다 — ecommerce 안의 파드 하나가 뚫리면 내부 API 는 열려 있다. 다음 단계는
  내부 API 에 서비스 토큰(또는 서비스 메시)이다.
- 나가는 연결 허용 목록(egress)은 외부 API 목록을 확정한 뒤에.

## 12. 심층 방어 정리 — springdoc 끄기 · dist.zip · Redis 비밀번호 분리

| 무엇 | 전 | 후 |
|---|---|---|
| API 명세(springdoc) | 게이트웨이만 막았다(§7). 서비스 안에서는 켜져 있어 클러스터 안 어디서든 `/v3/api-docs` 로 관리자 API 의 전체 모양을 읽을 수 있었다 | `k8s/10-configmap.yaml` 의 `SPRINGDOC_API_DOCS_ENABLED`·`SPRINGDOC_SWAGGER_UI_ENABLED` = false — 클러스터에서만. 로컬 개발은 ConfigMap 을 쓰지 않아 그대로 켜져 있다 |
| `frontend/dist.zip` | 옛 소스맵·옛 호스트가 든 빌드 산출물(3MB)이 추적되고 있었다 | 추적을 끊고 지웠다(`/dist` 는 이미 gitignore). 이력의 사본은 남는다 |
| 클러스터 Redis 비밀번호 | DB 와 같은 값 — 서비스는 `${LOCAL_DB_PASSWORD}`, redis-auth 도 같은 값. 하나가 새면 둘 다 새고, DB 비밀번호를 바꿀 때마다 Redis 를 함께 맞춰야 했다 | `ops/36-rotate-redis-password.sh` 로 분리 — 아래 |

### `ops/36` — 끊김 없이

1. Redis default 사용자에 **새 비밀번호를 더한다**(`ACL SETUSER default >…`). 옛 값도 계속 통한다.
2. `ecommerce-secrets` 에 `SPRING_DATA_REDIS_PASSWORD`, `redis-auth` 도 새 값. 환경 변수가 서비스 설정 파일의 `${LOCAL_DB_PASSWORD}` 를
   덮는다(스프링 우선순위) — 서비스 설정은 바꾸지 않는다.
3. ecommerce Deployment 를 하나씩 재시작(롤아웃이 끝나야 다음). 하나라도 실패하면 **거기서 멈춘다** — Redis 는 두 값을 다 받고
   있어 멈추지 않는다.
4. Redis 재시작 — 새 프로세스는 새 값만 받는다(수 초 끊김). `--keep-old` 면 건너뛴다.

값은 셸 변수로만 만들고 stdin 으로만 넘긴다(`kubectl patch --patch-file /dev/stdin`, `redis-cli` 표준 입력) — 인자·로그·파일에 남지
않는다(가짜 kubectl 로 호출 인자를 기록해 확인했다). `ops/27` 의 Redis 점검은 이제 `SPRING_DATA_REDIS_PASSWORD` 가 있으면 그 값으로
시험하고, `redis-cli -a` 대신 `REDISCLI_AUTH` 를 쓴다.

⚠️ **실행은 운영자 몫**이다. 실제 클러스터에서는 아직 돌리지 않았다.

## 13. 리뷰 — 받은 상품만 · 평점 매일 대사

### 받은 상품만 리뷰를 쓴다

검증이 없었다(docs/38 §1.7) — 로그인한 아무나 사지 않은 상품에 별점을 남겨 평균을 움직일 수 있었다.

| 자리 | 한 것 |
|---|---|
| order-service `GET /api/order/internal/purchased?customerId=&productId=` | 그 고객의 **배송 완료(DELIVERED)·구매 확정(COMPLETED)** 주문에 그 상품이 있고 한 개 이상 배정됐는가(`qty > shortQty`). 🔒 `InternalCallGuard.allowed` — 밖에서는 관리자만(남의 구매 이력을 상품 단위로 캐내지 못하게) |
| user-service `ReviewService.addReview` | **새 리뷰**일 때만 묻는다(고치기는 이미 자격을 확인했다). 이미지를 올리기 전에 — 거절될 것을 올리지 않는다 |
| 응답 | 받지 않은 상품 **403** "받으신 상품만 리뷰를 작성할 수 있습니다." · 확인 실패 **503** "구매 내역을 확인하지 못했습니다…" (둘 다 일본어 카탈로그) |

- **모르면 받지 않는다**(fail-closed). 결제의 재고 예약(docs/59)은 fail-open 이었다 — 그쪽은 막으면 매출이 멈추고 보상 경로가 있다.
  리뷰는 막아도 잃는 것이 없고, 잘못 받은 별점은 되돌릴 경로가 없다.
- 결제만 하고 아직 오지 않은 상품은 쓸 수 없다 — 받아 보고 쓰는 것이 리뷰다. 반품한 상품은 여전히 쓸 수 있다(받았고, 반품 사유도 리뷰다).
- 시연 환경에서 배송까지 기다리기 어려우면 `review.require-purchase=false`.

### 평점 매일 대사

평점은 리뷰가 바뀔 때 보낸다(§3). 그 호출이 실패하면 리뷰는 남기고 경고만 남기므로, 그 뒤로 리뷰가 없는 상품은 평점이 어긋난 채
남았다. `ReviewRatingReconciler` — 매일 18:40 UTC(`review.rating-reconcile.cron`) 리뷰가 있는 모든 상품의 평균을 한 번의
집계 쿼리로 구해 다시 보낸다. 멱등이라 인스턴스가 여럿이어도 망가지지 않고, 한 상품의 실패가 나머지를 막지 않는다.

테스트: `ReviewServiceTest`(+3 — 받지 않은 상품 거절·저장·평점 반영 없음, 확인 실패 503, 고치기는 다시 묻지 않음),
`ReviewRatingReconcilerTest`.

## 14. 사용자 복제 대사 — 최근 것을 다시 보낸다

docs/48 §6-③ 의 남은 항목. 복제본은 이제 둘 — chat 의 회원 행(닉네임)과 coupon 의 가입 쿠폰. 이벤트는 아웃박스로 반드시 나가지만,
**소비자에서 실패해 DLT 로 가면 거기서 멈춘다.** 가입 쿠폰은 "못 받았다" 는 사실을 나중에 알 방법이 없어 주기적으로 훑는 것 말고는
답이 없었다.

`UserReplicationReconciler`(user-service, 매일 19:25 UTC):

| 최근 N일(기본 7 — Kafka 보존과 같다) 안에 | 다시 보내는 것 | 받는 쪽이 멱등인 이유 |
|---|---|---|
| 가입 | `user-created` + `user-updated` | coupon: (쿠폰 이름, 사용자)가 있으면 만들지 않는다. chat: 행이 없을 때만 만든다 |
| 프로필 변경(그보다 먼저 가입) | `user-updated` | chat: `updatedAt` 이 더 새로울 때만 적용 |
| 탈퇴 | `user-deleted` | chat: 익명화는 여러 번 해도 같다 |

- 받는 쪽에 "무엇이 빠졌나" 를 묻는 API 가 필요 없다 — 멱등이면 다시 보내는 것이 곧 메꾸기다.
- 오래된 가입자에게는 `user-created` 를 보내지 않는다 — 쿠폰이 만료·정리된 뒤 다시 나가면 안 된다.
- 이 배치가 생기기 전의 누락은 `user.replication.reconcile.window-days` 를 한 번 크게 주고 재시작한 뒤 되돌린다.
- id 커서로 200명씩, 페이지마다 한 트랜잭션(아웃박스 기록은 호출자의 트랜잭션을 요구한다 — MANDATORY).

테스트 `UserReplicationReconcilerTest`(2) — 종류별로 보내는 이벤트, 오래된 가입자에게 가입 이벤트 없음, 커서 페이징.

## 15. `IllegalStateException` 을 응답 문구 허용 목록에서 뺐다

`ClientMessage`(§1)는 `IllegalStateException` 의 문구를 그대로 내보냈다. 그런데 우리 코드는 그 예외를 **두 가지로** 쓴다:

| 쓰임 | 예 | 곳 |
|---|---|---|
| 사람에게 보일 상태 충돌 | "진행 중인 기획전은 삭제할 수 없습니다. 먼저 종료해 주세요." · "채팅 회원 정보가 없습니다…" | 기획전 3, 채팅 2 |
| **운영자용** | "SKU 생성이 10회 연속 충돌했습니다 — product_sku_seq 시퀀스 상태를 확인하세요" · "users.roles 에 ROLE_USER 가 없습니다. 마이그레이션 V3…" · "SHA-256 을 쓸 수 없습니다" · "Failed to acquire lock for …" | 약 60곳(대부분 기동 설정 검증) |

둘째가 요청 경로에서 나면 시퀀스·마이그레이션·설정 키 이름이 손님 화면에 떴다. 허용 목록은 둘을 가르지 못한다.

- 새 도메인 예외 `StateConflictException`(common) — "요청은 맞지만 지금 상태에서는 할 수 없다", 409. 기획전 셋을 옮기고
  관리 컨트롤러의 catch 도 바꿨다.
- 채팅 둘은 `DomainException` 으로 — 그리고 chat 의 예외 처리기에 `DomainException` → **400 + 그 문구**를 더했다. 예전에는 같은
  문구가 `IllegalStateException` 이라 500 "서버 오류가 발생했습니다" 로 떨어져, **사람을 위해 쓴 문구가 오히려 보이지 않았다.**
- `ClientMessage` 의 허용 목록에서 `IllegalStateException` 을 뺐다 — 운영자용 문구는 일반 문구로, 원래 문구는 로그로.
  `RuntimeException`·`IllegalArgumentException`·`NoSuchElementException` 은 그대로다(입력 검증 문구가 이것들이다).

테스트: `ClientMessageTest`(ISE 는 일반 문구, `StateConflictException` 은 그대로), `EventLifecycleTest`·`ChatServiceConnectTest`
(예외 종류), `DomainExceptionResponseTest`(chat — 도메인 예외 400, ISE 500 일반 문구). 백엔드 아홉 모듈 전부 통과.

## 16. 서비스 간 mTLS — "내부 호출" 을 인증서로 판정한다

### 무엇이 문제였나

내부 전용 API(구매 집계·배송 정보·재고 예약·별점·구매 여부·사용자 조회 등)는 `InternalCallGuard` 로 **"게이트웨이가 붙인 신원
헤더가 없으면 내부 호출"** 이라고 판정했다. 그런데 신원 헤더가 없는 요청은 내부 호출만이 아니다:

| 헤더 없는 요청 | 지금 막혀 있는 이유 |
|---|---|
| 게이트웨이의 **공개 라우트**(로그인 없는 카탈로그 읽기, Stripe·포트원 웹훅) | 내부 API 가 우연히 그 라우트의 메서드·경로 밖에 있다 — 카탈로그 내부 API 는 POST 라 JWT 라우트를 탄다 |
| 클러스터 안의 **아무 파드** | 없음 — §11 의 NetworkPolicy 가 다른 네임스페이스는 막지만, ecommerce 안의 파드는 그대로 닿는다 |

라우트 하나가 바뀌거나 ecommerce 안의 파드 하나가 뚫리면 조용히 열린다.

### 한 것

모든 서비스는 이미 HTTPS 로 뜨고, 같은 내부 CA 가 서명한 인증서(`ops/07`, serverAuth·clientAuth)를 갖고 있다. 그것으로:

| 자리 | 한 것 |
|---|---|
| 부르는 쪽 — 백엔드 서비스(k8s/40~49) | JVM 키스토어(`-Djavax.net.ssl.keyStore=…keystore.p12`)를 준다. 서비스 간 호출은 전부 Feign(JDK 기본 TLS)이라 서버가 요청하면 이 인증서를 **클라이언트 인증서로** 낸다. 게이트웨이(31)에는 주지 않는다 — 게이트웨이가 넘기는 요청은 내부 호출이 아니다 |
| 받는 쪽 — 같은 백엔드 | `SERVER_SSL_CLIENT_AUTH=want` — 클라이언트 인증서를 **요청**한다(없어도 연결은 된다: kubelet 프로브·게이트웨이·Prometheus). eureka·config·게이트웨이는 그대로 둔다 — 틀리면 등록·설정이 멈춰 전체가 선다 |
| 판정 — `InternalCallerFilter`(common, 모든 서블릿 서비스에 자동 구성) | 신원 헤더가 **없는** 요청만 본다. TLS 로 받은 클라이언트 인증서가 **내부 CA 로 서명**됐으면(여기서 다시 검증 — JVM 신뢰 저장소가 공개 CA 를 믿어도 공개 CA 인증서는 내부가 아니다) 내부 호출. 아니면 `User-Role: ROLE_ANONYMOUS` 를 붙여 넘긴다 → `InternalCallGuard` 가 "외부·관리자 아님" 으로 읽어 **내부 전용 API 가 403**. 호출 자리 수십 곳을 고치지 않고 판정의 입력을 바로잡는다 |

모드 `INTERNAL_AUTH_MODE`(ConfigMap):

| 모드 | 하는 일 |
|---|---|
| `off`(코드 기본값 — 로컬 개발) | 아무것도 안 한다 |
| `observe`(단계적 적용의 첫 단계) | 판정만. 내부 경로에 내부 인증서 없이 온 요청을 WARN `[mTLS observe]` — "서비스끼리의 호출이 모두 인증서를 내는가" 를 확인하는 단계 |
| `enforce` | 위처럼 표시한다(403). CA 를 읽지 못하면 **기동을 멈춘다** — 조용히 모든 내부 호출이 403 이 되지 않게 |

`internal-auth.allowed-subjects`(CN 목록)를 주면 그 CN 만 내부로 본다 — 서비스별 인증서로 넘어갈 때 쓴다(아래).

### 켜는 순서

```bash
./ops/28-deploy.sh <환경> <버전>                 # 새 이미지 + 매니페스트(JAVA_TOOL_OPTIONS·SERVER_SSL_CLIENT_AUTH·INTERNAL_AUTH_MODE=observe)
# 주문·결제·배송 흐름을 한 번 돌린다(내부 호출이 일어나야 경고가 나온다)
./ops/37-check-mtls.sh                            # ① observe 경고 0건 ② 인증서 없이 200 · 내부 인증서로 200
kubectl -n ecommerce patch configmap ecommerce-config --type merge -p '{"data":{"INTERNAL_AUTH_MODE":"enforce"}}'
kubectl -n ecommerce rollout restart deploy/<백엔드 서비스들>
./ops/37-check-mtls.sh                            # 인증서 없이 403 · 내부 인증서로 200
```

되돌리기: `INTERNAL_AUTH_MODE` 를 `observe` 로 되돌리고 재시작. TLS 층까지 되돌리려면 매니페스트의 `SERVER_SSL_CLIENT_AUTH` 를 `none`.

`ops/37-check-mtls.sh` — 잠깐 뜨는 curl 파드에 `tls-keystore` Secret 을 마운트해 order-service 내부 API 를 **인증서 없이 / 내부
인증서로** 부르고 상태 코드만 본다(양쪽이 기대대로여야 판정이 살아 있다). observe 경고는 대조 호출보다 **먼저** 센다 — 대조의
"인증서 없이" 호출 자체가 흔적을 남기기 때문이다(그 호출에는 `X-Mtls-Probe` 표식을 달아 로그만 가른다. 인가와는 무관하다).

테스트: `InternalCallerFilterTest`(7 — JDK keytool 로 내부 CA·서비스 인증서·다른 CA 인증서를 만들어: 인증서 없음 → 403 판정, 내부
인증서 → 내부·CN 기록, 다른 CA → 외부, 허용 CN 목록, 신원 헤더는 그대로, off·observe 는 막지 않음, enforce 인데 CA 없으면 기동 실패).
백엔드 열 개 모듈 전부 통과. `ops/37` 은 가짜 kubectl 로 네 경우(enforce 정상·enforce 미적용·observe 정상·observe 경고)를 확인했다.

**✅ 운영 적용 (2026-09-26)** — observe 에서 경고 0건·대조 200/200 을 본 뒤 enforce 로 올렸다. `ops/37`: 기동 모드 ENFORCE,
**인증서 없이 403 · 내부 인증서로 200**. 올리기 전 주문·결제(Stripe)·장바구니 비우기가 끝까지 도는 것을 확인했다.
실제로 돌리며 `ops/37` 이 세 번 틀렸고 고쳤다:

| 증상 | 원인 | 고침 |
|---|---|---|
| 인증서 없이·있이 둘 다 `000` | HTTP 코드만 찍어 이유를 알 수 없었다 | curl 종료 코드·오류 문구, Secret 의 빠진 키, Service 엔드포인트 비었는지를 먼저 본다 |
| 준비된 order-service 에도 연결 거부(curl 7) | NetworkPolicy(k3s kube-router)가 **방금 뜬 파드** IP 를 허용 목록에 넣기까지 몇 초 — 그새 거부 | 연결 거부면 2초씩 최대 8번 다시(ops/35 도 두드리기 전 5초) |
| enforce 인데 인증서 없이 `200` | 재시작 **직후** 돌려 옛 observe 파드가 응답 | 롤아웃이 끝날 때까지 기다리고, 파드의 기동 모드(로그 `서비스 간 mTLS 판정: mode=…`)를 보인다 |

### 서비스별 신원 — "어느 서비스인가" 까지 (2026-09-26)

예전에는 모든 서비스가 인증서 **하나**(CN=localhost, `tls-keystore`)를 나눠 썼다. 판정은 "내부 서비스인가" 까지였고, 게이트웨이도
같은 키를 서버 인증서로 갖고 있어 게이트웨이가 뚫리면 그 키로 내부 호출을 흉내 낼 수 있었다.

**바꾼 것 — 클라이언트 신원만.** 서비스가 다른 서비스를 부를 때 내는 인증서(JVM 기본 keystore → Feign)를 서비스마다 따로 둔다
(`tls-id-<서비스>`, CN=서비스 이름, `/app/identity` 에 마운트). 서버 인증서·Redis·Kafka 는 공유 인증서 그대로다(같은 CA).
Secret 모양은 cert-manager 의 Certificate(`keystores.pkcs12`)가 만드는 것과 같다(`tls.crt`·`tls.key`·`ca.crt`·`keystore.p12`)
— 발급자를 바꿔도 매니페스트는 그대로다(§18).

**판정 — 허용 주체 + 경로 규칙** (`InternalCallerFilter`, ConfigMap):

| 키 | 값 |
|---|---|
| `INTERNAL_AUTH_ALLOWED_SUBJECTS` | 백엔드 열 개의 이름. 공유 인증서(localhost)는 없다 — 게이트웨이의 키는 내부 호출이 아니다 |
| `INTERNAL_AUTH_PATH_RULES` | 아래 표. 가장 긴 앞부분이 이긴다. `none` = 서비스는 못 부른다(관리자만) |
| `INTERNAL_AUTH_RULES_MODE` | 단계적 적용(관찰 → 강제): `observe`(어긋나면 `[mTLS rule]` 로 남기기만) → `enforce`(막는다) |

| 내부 경로(앞부분) | 부를 수 있는 서비스 | 호출 자리 |
|---|---|---|
| `/api/order/internal/purchase-stats` · `/purchased` | user-service | 고객 등급 · 리뷰 구매 확인 |
| `/api/order/internal/orders` | delivery-service | 결제 완료 리스너 · 배송 백필 |
| `/api/coupon/internal/quote` | order-service | 주문 금액 |
| `/api/internal/stock/reservations` | order-service | 재고 예약·해제 |
| `/api/product/rating` | user-service | 리뷰 평점·매일 대사 |
| `/api/auth/user/emails` · `/name` | order-service | 관리자 주문 목록 |
| `/api/auth/user/segments` | coupon-service | 기획전 쿠폰 |
| `/api/auth/user/id` | coupon·order·chat | 쿠폰 발급(chat·order 는 선언만) |
| `/api/auth/user/email/` · `/profile/` | none | 관리자만(게이트웨이) |

게이트웨이·saga-projection 은 내부 API 를 부르지 않는다(Feign 없음). 처음 보는 (호출 서비스, 경로) 짝은 `[mTLS caller]` 로 한 번
남는다 — `./ops/37-check-mtls.sh --callers` 가 모아 보여 준다.

**가드**: `InternalCallerFilterTest`(+5 — 경로 규칙이 호출자를 좁힌다, 가장 긴 앞부분, 규칙 없는 경로, rules observe 는 막지 않음,
규칙 문자열이 틀리면 기동 실패) · **B-67**(Feign 이 부르는 내부 경로를 규칙이 그 호출 모듈에 허용하는가 — 규칙에서 호출자를 빼면
실패하는 것을 확인). Feign 호출을 더하거나 옮기면 B-67 이 규칙을 고치라고 한다.

**올리는 순서** — 끊김 없이:

```bash
./ops/38-issue-service-identities.sh          # ① 서비스별 신원 Secret(ops/07 의 CA 로, 값은 출력하지 않는다)
./ops/38-issue-service-identities.sh check
./ops/28-deploy.sh local <버전>                # ② 매니페스트 — /app/identity 마운트 + 규칙(관찰 단계부터)
                                               #    (ops/28 은 신원 Secret 이 없으면 적용 전에 멈춘다)
# ③ 주요 흐름(주문·결제·배송·리뷰·쿠폰)을 한 번 돌린 뒤
./ops/37-check-mtls.sh --callers               #    누가 무엇을 불렀나 — 모두 자기 이름인가
./ops/37-check-mtls.sh                         #    ①-2 규칙 위반 0건이면 ↓
kubectl -n ecommerce patch configmap ecommerce-config --type merge -p '{"data":{"INTERNAL_AUTH_RULES_MODE":"enforce"}}'
kubectl -n ecommerce rollout restart deploy/<백엔드 열 개>
./ops/37-check-mtls.sh                         # ④ 대조는 user-service 의 신원으로 200, 인증서 없이 403
```

되돌리기: `INTERNAL_AUTH_RULES_MODE=observe` + 재시작(판정은 "내부 CA 인증서면 된다" 로 돌아간다).

### 남은 것 — 정직하게

- 서버 인증서는 여전히 공유다 — 게이트웨이도 그 키를 갖는다. 다만 그 키(CN=localhost)는 이제 **허용 주체가 아니라** 내부 호출로
  쓸 수 없다. 서버 인증서까지 서비스별로 나누는 것은 이득이 작다(서버 인증은 "같은 CA 인가" 만 본다).
- 서버 쪽 `need`(인증서 없으면 연결 거부)가 아니라 `want` 다 — kubelet 의 HTTPS 프로브가 인증서를 내지 못한다. 판정은 앱이 한다.
- 서비스별 신원(클라이언트 인증서)은 cert-manager 가 갱신한다(§18). **공유 서버 인증서**(`tls-keystore`, 825일)는 여전히 `ops/07`
  + 재시작이다 — `MODE=verify ./ops/07-issue-certificates.sh` 가 남은 날을 알린다.

## 17. 🔴 2026-09-26 배포 실패 — 85커밋을 한 번에, 그리고 자동 복귀가 장애를 키운 것

`0afb735 → 675ca96`(85커밋)을 `ops/28` 로 한 번에 올렸다.

| 서비스 | 앞으로 갈 때 | 원인 |
|---|---|---|
| user-service | 🔴 기동 실패 `UserLifecycleProducer: No default constructor found` | 생성자가 둘(운영용·테스트용)인데 어느 쪽에도 `@Autowired` 가 없었다. 단위 테스트는 생성자를 직접 불러 초록, 컨텍스트 기동 테스트는 Docker 가 있어야 돌아 CI 에서 건너뛰었다 |
| chat-service | 🔴 V6 가 스스로 거절 "legacy_sender_email 에 행이 남아 있다" | **두 릴리스로 나눠 올리도록 만든 전환**(docs/48 §6-⑥)을 건너뛰었다 — 1단계(V5 + 기동 백필, 커밋 `1227834a0a5a`)가 한 번도 돌지 않아 매핑 표가 비지 않았다. V6 의 안전장치가 제 일을 했다 |
| order·pay 외 | ✅ 새 버전으로 떴다 | order V16·V18, pay V6 등 **되돌릴 수 없는 마이그레이션이 성공**했다 |

그리고 `ops/28` 이 **자동 복귀**했다. 이미지는 되돌렸지만 스키마는 되돌리지 않으므로, 옛 order·pay·chat 이미지는 "missing table
orders.customer", "missing column paypal_order_id", "missing column email" 로 기동하지 못했다 — 복귀조차 실패했다. 롤링이 멈춰
order·pay 는 **새 버전 파드가**, chat 은 **배포 전의 옛 파드가**(V5 로 email 컬럼이 없어진 스키마 위에서) 계속 돌고 있다.

### 고친 것

- `UserLifecycleProducer` — 운영용 생성자에 `@Autowired`. 가드 **B-65**: 생성자가 둘 이상인 빈은 하나에 `@Autowired`(또는 인자
  없는 생성자). 고치기 전 코드로 되돌려 실패하는 것을 확인했다.
- `ops/28` — 배포 범위(`현재 → 새 버전`)에 `ROLLBACK-UNSAFE` 마이그레이션이 있으면 **미리 알리고, 실패해도 자동 복귀하지 않는다**
  (앞으로 고친다). 수동 `--rollback` 도 그 범위를 지나면 거절한다(`FORCE_UNSAFE_ROLLBACK=1` 은 스키마를 먼저 되돌린 뒤에만).

### 복구 — 앞으로 간다

```bash
git pull                                   # @Autowired·ops/28 가드
# ① chat 전환 릴리스(1227834a0a5a)를 한 번 — 기동 백필이 몽고의 옛 발신자를 id 로 바꾸고 매핑 표를 비운다
git worktree add ../ecommerce-msa-transition 1227834a0a5a
cp common/src/main/resources/kafka-secrets.properties ../ecommerce-msa-transition/common/src/main/resources/ 2>/dev/null
( cd ../ecommerce-msa-transition && ./ops/19-build-images.sh --registry ghcr.io/ruke79 --push --only chat-service )
kubectl -n ecommerce set image deploy/chat-service chat-service=ghcr.io/ruke79/ecommerce-msa/chat-service:1227834a0a5a
kubectl -n ecommerce rollout status deploy/chat-service --timeout=900s
kubectl -n ecommerce logs deploy/chat-service | grep "옛 발신자 백필"     # "… 지운 매핑 N건" 이 나오면 끝
# ② 전부를 새 버전으로 — 이번에는 실패해도 자동 복귀하지 않는다
VER=$(git rev-parse --short=12 HEAD)
REACT_APP_...=... ./ops/19-build-images.sh --registry ghcr.io/ruke79 --push
./ops/28-deploy.sh local $VER
git worktree remove ../ecommerce-msa-transition
```

①의 로그가 "백필 실패" 면 몽고(Atlas)에 닿지 못한 것이다. 옛 대화의 발신자를 포기해도 되면(시연 데이터) 표를 비우고 ②로 간다 —
그 문서들은 "발신자 모름" 으로 보이고, 이메일이 몽고에 남는다:
`DELETE FROM chat.legacy_sender_email;`

### 두 번째 시도 — chat 의 빈 순환 참조

전환 릴리스로 백필을 끝낸 뒤(`id 로 바꾼 문서 4건 … 지운 매핑 1건`) 다시 배포하자 chat 이 "APPLICATION FAILED TO START" 로
멈췄다. 상담 채팅(docs/58)이 들어오며 `StompHandler` 가 `SupportService` 를 받게 됐는데:

```
brokerMessagingTemplate → clientInboundChannel → StompHandler(인터셉터) → SupportService → SupportDeskNotifier → brokerMessagingTemplate
```

스프링 부트는 순환을 거부한다. 상담 기능이 이번에 처음 운영에 올라가 처음 드러났다. `StompHandler.supportService` 에 `@Lazy`
(처음 쓸 때 받는다 — STOMP 프레임을 처리할 때는 컨텍스트가 다 떠 있다). `WebSocketWiringTest` 가 실제 WebSocket 설정·상담 서비스·
알림기를 띄워 조립을 확인한다(고치기 전에 정확히 이 순환으로 실패하는 것을 확인했다).

### 교훈

- **컨텍스트 조립은 단위 테스트가 보지 않는다.** 이번 두 기동 실패(생성자 둘·순환 참조)는 모두 "스프링이 빈을 엮는 순간" 의
  문제였고, 그것을 보는 기동 테스트는 Docker 가 있어야 돌아 CI 에서 건너뛰었다. 조립이 까다로운 곳(WebSocket 브로커)은 목으로 둔
  가벼운 컨텍스트 테스트로, 흔한 실수(생성자 여럿)는 가드로 막는다.
- **여러 릴리스로 나눈 전환은 건너뛸 수 없다.** 한 번에 오래 쌓아 두었다가 올리면, 중간 릴리스가 해 주던 일(백필)이 빠진다.
  지금은 V6 의 안전장치가 막았지만, 그런 장치가 없는 전환이었다면 데이터를 잃었다.
- **되돌릴 수 없는 마이그레이션이 지나간 뒤의 자동 복귀는 복구가 아니다.** 스키마는 앞으로만 간다.
- 단일 노드라 롤아웃마다 새 파드가 Pending 으로 기다렸다(서비스당 약 600초). `maxSurge: 0 / maxUnavailable: 1` 을 쓰면 빨라지지만
  서비스마다 재시작 동안 끊긴다 — 트레이드오프라 그대로 두었다.

## 18. 서비스별 신원 인증서 — 발급·갱신을 cert-manager 로

**문제**: ops/38 로 만든 신원은 1년짜리이고, 갱신은 사람이 다시 돌리고 재시작해야 했다. 잊으면 만료되는 날 서비스 간 호출이 전부
끊긴다 — mTLS 가 enforce 라 조용히 넘어가지 않는다.

**구성**

| 무엇 | 어디 | 하는 일 |
|---|---|---|
| cert-manager(v1.16.2, 고정) | `cert-manager` 네임스페이스 | Certificate 를 보고 Secret 을 발급·갱신 |
| ClusterIssuer `internal-ca` | CA 키는 `cert-manager/internal-ca` 에만 | ops/07 의 CA 로 서명 — 앱 네임스페이스에는 CA 키가 없다 |
| Certificate `id-<서비스>` ×10 | `k8s/96-service-identities.yaml`(kustomization 밖 — CRD 가 먼저) | 90일짜리를 만료 30일 전에 새로(키도 새로). Secret 은 ops/38 과 같은 이름·키(`tls-id-<서비스>` — `tls.crt`·`tls.key`·`ca.crt`·`keystore.p12`) |
| Reloader(v1.1.0, 고정) | `default` | Secret 이 바뀌면 그 Deployment 만 롤링 재시작 — 자바는 keystore 를 기동할 때만 읽는다(`secret.reloader.stakater.com/reload` 주석, k8s/40~49) |

`keystore.p12` 는 `Modern2023`(AES-256/PBKDF2) — 자바 17 이 읽는다. 비밀번호는 서비스들이 이미 쓰는 `LOCAL_KEY_STORE_PASSWORD`.

**올리는 순서** (§16 의 서비스별 신원이 먼저 올라가 있어야 한다):

```bash
./ops/39-cert-manager.sh          # install → ca(지문으로 같은 CA 인지 확인) → apply(Certificate 10 개 Ready) → check
# cert-manager 가 ops/38 의 Secret 을 새로 발급해 덮는다 → Reloader 가 서비스를 한 번씩 롤링 재시작한다
./ops/37-check-mtls.sh            # 다 뜬 뒤 — 대조 200·403, 규칙 위반 0
./ops/39-cert-manager.sh check    # 만료·다음 갱신 시각, Reloader 가 떠 있는가
```

갱신을 연습하려면 `./ops/39-cert-manager.sh renew order-service` — 그 서비스만 새로 발급되고 재시작된다. cert-manager 가 맡은
뒤에는 ops/38 이 그 서비스를 건드리지 않는다(둘이 번갈아 덮으면 계속 재시작된다).

**가드**: B-68 — 백엔드가 마운트하는 신원 Secret 마다 k8s/96 에 같은 이름을 발급하는 Certificate 가 있고, 그 Deployment 에 Reloader
주석이 있다(주석을 지우면 실패하는 것을 확인).

**남은 것**: 공유 서버 인증서(`tls-keystore`)는 여전히 손으로 — 825일이라 급하지 않지만, 같은 방식(Certificate + Reloader)으로
옮길 수 있다. 그때는 Kafka·Redis·Postgres 클라이언트가 같은 파일을 쓰므로 인프라 쪽 재시작까지 함께 계획해야 한다.

