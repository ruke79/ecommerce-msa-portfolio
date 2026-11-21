# 57. 남은 우선순위 — docs/54 §10.1 의 열린 항목을 차례로

[日本語](57-remaining-priorities.md) | **한국어**

> 🕰️ **상태(2026-11-14) — 절마다 그때의 기록이고, 그 뒤 운영 적용은 아래와 같습니다.** §11 의 "서비스 간 mTLS 는 여전히 없다" 는 §16 · §18 에서 해결했다. §16 의 판정 넷 — `INTERNAL_AUTH_MODE` · `_RULES_MODE` · `_IDENTITY_MODE` · `EUREKA_WRITE_AUTH_MODE` — 은 저장소 기본값(`k8s/10-configmap.yaml`)과 운영 모두 **enforce**(신원 헤더 2026-10-26 `d68f0307e979`, Eureka 쓰기 2026-10-30). 스토어 비밀번호 교체(ops/59)는 2026-10-25 운영 적용 · 10-26 사본 정리. §18 의 Certificate 는 10 → **12**(gateway-server · config-server 신원). 남은 일은 [docs/85 §8](85-release-deploy-runbook.ko.md).

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
- 나가는 연결 허용 목록(egress)은 외부 API 목록을 확정한 뒤에. → 🕰️ 2026-11-19: IP 목록 없이 할 수 있는 1단계를 했다(§23).

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
| 부르는 쪽 — 백엔드 서비스(k8s/40~49) | JVM 키스토어(`-Djavax.net.ssl.keyStore=…keystore.p12`)를 준다. 서비스 간 호출은 전부 Feign(JDK 기본 TLS)이라 서버가 요청하면 이 인증서를 **클라이언트 인증서로** 낸다. 게이트웨이(31)는 JVM 키스토어가 아니라 자기 신원(`tls-id-gateway-server`)을 HttpClient 클라이언트 인증서로 낸다(2026-10-14, 아래 "신원 헤더") — 그래도 헤더 없이 넘기는 요청은 내부 호출이 아니다 |
| 받는 쪽 — 같은 백엔드 | `SERVER_SSL_CLIENT_AUTH=want` — 클라이언트 인증서를 **요청**한다(없어도 연결은 된다: kubelet 프로브·게이트웨이·Prometheus). eureka·config·게이트웨이는 그대로 둔다 — 틀리면 등록·설정이 멈춰 전체가 선다(eureka 는 2026-10-14 에 `want` + 쓰기 판정 — 아래 "Eureka 쓰기") |
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
| `/api/coupon/internal/payment-claim` | order-service | 결제 시작 때 쿠폰 잡기(V7) |
| `/api/internal/stock/reservations` | order-service | 재고 예약·해제 |
| `/api/product/rating` | user-service | 리뷰 평점·매일 대사 |
| `/api/auth/user/emails` · `/name` | order-service | 관리자 주문 목록 |
| `/api/auth/user/segments` | coupon-service | 기획전 쿠폰 |
| `/api/auth/user/id` | coupon·order·chat | 쿠폰 발급(chat·order 는 선언만) |
| `/api/auth/user/email/` · `/profile/` | none | 관리자만(게이트웨이) |
| `/api/notifications/internal/two-factor` | user-service | 로그인 2단계 인증의 SMS 발송 · 확인(docs/24 §10 — 관리자도 게이트웨이로는 못 부른다) |

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
# k8s/10-configmap.yaml 의 INTERNAL_AUTH_RULES_MODE 를 "enforce" 로(✅ 2026-10-01 저장소 기본값) →
./ops/28-deploy.sh local --config-only         #    kubectl patch 로만 바꾸면 다음 배포가 매니페스트 값으로 되돌린다
kubectl -n ecommerce rollout restart deploy/<백엔드 열 개>   # 환경 변수는 재시작해야 읽는다
./ops/37-check-mtls.sh                         # ④ 대조는 user-service 의 신원으로 200, 인증서 없이 403
```

되돌리기: `INTERNAL_AUTH_RULES_MODE=observe` 로 바꾸고 `--config-only` + 재시작(판정은 "내부 CA 인증서면 된다" 로 돌아간다). B-67 이 저장소 기본값이 enforce 인지도 본다.

### 신원 헤더 — 누가 붙였나 (2026-10-14)

**무엇이 문제였나.** 판정 필터는 신원 헤더(`User-Id` · `User-Role` · `User-Email`)가 **있기만 하면** 게이트웨이가 붙인 것으로 보고 아무것도
보지 않았다. 서비스는 인증서 없는 연결도 받고(`want`), NetworkPolicy `allow-same-namespace` 는 같은 네임스페이스를 다 허용한다 —
ecommerce 의 파드 하나(frontend · cloudflared · 백업 Job …)를 장악하면 `User-Role: ROLE_ADMIN` 한 줄로 내부 전용 API(배송 주소 등)를
읽고, 남의 `User-Id` 로 그 사용자가 됐다. 인증서를 가진 서비스도 헤더 한 줄로 허용 주체 · 경로 규칙을 건너뛰었다.

**한 것.** 게이트웨이도 자기 인증서(CN=gateway-server — ops/38 · k8s/96 의 `tls-id-gateway-server`)를 **클라이언트 인증서로** 낸다
(Spring Cloud Gateway `httpclient.ssl.key-store` — Netty 클라이언트는 JVM 키스토어를 읽지 않는다). 받는 쪽이 헤더를 믿는 것은 보낸 쪽에 따라 다르다:

| 보낸 쪽(인증서) | 믿는 헤더 |
|---|---|
| 게이트웨이(`INTERNAL_AUTH_GATEWAY_SUBJECTS`, 기본 gateway-server) | 셋 다 — 게이트웨이는 클라이언트가 보낸 같은 헤더를 지우고 토큰에서 다시 붙인다 |
| 내부 서비스(허용 주체) | `User-Id` 만 — 사용자를 대신한 호출(order · coupon → cart `getCart`). 역할 · 이메일은 버린다 — 서비스가 관리자를 자처하지 못한다 |
| 내부 인증서 없음 · 다른 CA | 없음 — 셋 다 버리고 `ROLE_ANONYMOUS` |

헤더 없이 게이트웨이 인증서로 온 요청(공개 라우트)은 **내부 호출이 아니다** — 허용 주체 목록과 상관없이 외부로 본다.

**켜는 순서** (`INTERNAL_AUTH_IDENTITY_MODE`, 기본 observe):

1. 게이트웨이 인증서 — `./ops/38-issue-service-identities.sh --only gateway-server`(cert-manager 를 쓰면 `./ops/39-cert-manager.sh` 가
   k8s/96 의 `id-gateway-server` 를 함께 발급). `ops/28` 은 매니페스트가 마운트하는 `tls-id-*` 가 없으면 배포 전에 멈춘다.
2. 배포 — 게이트웨이가 인증서를 내기 시작하고, 백엔드는 observe 로 어긋난 것을 `[mTLS identity]` 로 남긴다(값은 남기지 않는다).
3. `./ops/37-check-mtls.sh`(①-3 이 0) → 로그인 · 주문 · 관리자 화면이 한 번씩 돈 뒤 `SINCE=24h` 로 한 번 더.
4. `k8s/10-configmap.yaml` 의 `INTERNAL_AUTH_IDENTITY_MODE: "enforce"` → `./ops/28-deploy.sh local --config-only` → 백엔드 재시작 →
   `./ops/37` 의 ②-2(인증서 없이 관리자 헤더 → 403).

되돌리기: observe 로 같은 절차. `InternalCallerFilterTest`(신원 헤더 5개) · `CheckMtlsScriptTest` 가 지킨다.

> 🕰️ 2026-11-14: 4단계까지 끝났다 — 운영 2026-10-26(`d68f0307e979`, `ops/37` ①-3 0 · ②-2 403), 저장소 기본값도 `enforce`. 경로 규칙에는 그 뒤 gRPC 재고 예약(`/ecommerce.stock.v1.StockReservation/=order-service`)이 더해졌다.

**enforce 의 전제와 마지막 방어선 — 가드 두 개(`InternalEndpointGuardTest`).**

- **B-81 — Feign 은 `User-Role` · `User-Email` 을 싣지 않는다.** enforce 는 내부 서비스가 보낸 그 둘을 버린다. 지금은 싣는 Feign 이
  없어(대신하는 호출은 `User-Id` 뿐) 4단계에서 동작이 바뀌지 않는다 — 그 전제를 코드로 고정했다. 역할이 필요한 내부 호출이 생기면
  싣지 말고 받는 쪽이 `User-Id` 로 조회한다.
- **B-80 — 내부 전용 엔드포인트는 모두 컨트롤러에서 `InternalCallGuard.allowed` 로 판정한다.** 경로 규칙(`INTERNAL_AUTH_PATH_RULES`)은
  신원 헤더가 있는 요청(게이트웨이 경유)을 건너뛴다 — 그때 실제로 막는 것은 그 한 줄(catalog 는 `AdminWriteInterceptor`)이다. 가드는
  규칙의 모든 경로에 그 판정이 있는지, `/internal/` 을 가진 엔드포인트가 규칙에 빠짐없이 있는지, 규칙이 가리키는 엔드포인트가 실제로
  있는지(경로를 바꾸면 규칙도) 본다.

### 스토어 비밀번호는 JVM 옵션 밖으로 (2026-10-14, B-83)

**무엇이 문제였나.** 매니페스트 13개가 `JAVA_TOOL_OPTIONS` 에 `-Djavax.net.ssl.trustStorePassword=$(LOCAL_KEY_STORE_PASSWORD)`(서비스는
`keyStorePassword` 도)를 넣었다. JVM 은 기동할 때마다 그 변수를 **통째로** `Picked up JAVA_TOOL_OPTIONS: …` 로 stderr 에 찍는다 — logback 을
거치지 않아 마스킹 없이 파드 로그와 Loki(alloy 가 `/var/log/pods/ecommerce_*` 를 보낸다)에 평문으로 남았다. 같은 값이 와일드카드 개인 키 ·
서비스 신원 키스토어 · 게이트웨이 신원의 비밀번호다.

**한 것.** `JAVA_TOOL_OPTIONS` 에는 경로 · 형식만 둔다. 비밀번호는 `JAVAX_NET_SSL_TRUST_STORE_PASSWORD` · `JAVAX_NET_SSL_KEY_STORE_PASSWORD`
환경 변수로 받고, 앱이 `SpringApplication.run` 의 맨 처음(설정 서버 · Eureka 의 첫 TLS 보다 앞)에 `javax.net.ssl.*Password` 로 옮긴다 —
common 의 `JvmSslPasswords`(실행 리스너), common 을 쓰지 않는 게이트웨이 · config-server 는 `main`. JDK 는 기본 SSLContext 를 처음 쓸 때
이 속성을 읽는다(실측: 변수로 준 비밀번호로 트러스트스토어가 열리고, 없으면 인증서 0개, `Picked up` 줄에는 비밀번호가 없다).
ops/12 · ops/13 도 같은 방식. B-83(`JvmSslPasswordsTest`)이 매니페스트 · 오버레이 · ops 의 JVM 옵션에 `Password=` 가 없는지, 스토어마다
비밀번호 변수가 있는지 본다.

**운영에서 할 일.** 배포만으로 새 로그에는 더 남지 않는다. 이미 남은 것은 지워지지 않는다 — Loki 보관 기간 동안 조회된다. 비밀번호가 새었다고
보고 `LOCAL_KEY_STORE_PASSWORD` 를 바꾸는 것이 맞다. 그 전에는 Grafana · Loki 접근을 운영자로만 둔다(관측 포트는 원래 공개하지 않는다).

> 🕰️ 2026-11-14: 둘 다 끝났다 — 새 매니페스트는 묶음 릴리스(2026-10-24 `4415e498565c`)로, 비밀번호 교체는 아래 ops/59 로 2026-10-25 운영 적용(스토어 27 · 재시작 0), 하루 뒤 cleanup.

**교체 — `ops/59-rotate-store-password.sh`(2026-10-25).** ops/07 로 다시 발급하지 않는다 — CA 가 바뀌면 서비스 신원(ops/38) · PostgreSQL
인증서 · 브라우저 신뢰까지 모두 다시 해야 한다. 키와 인증서는 그대로 두고 스토어의 비밀번호만 바꾼다(`keytool -storepasswd` — PKCS12 는 키
비밀번호도 함께 바뀌고 트러스트스토어의 신뢰 항목은 그대로; 실측: 새 비밀번호로 개인 키가 꺼내지고 옛 비밀번호로는 열리지 않는다).

| 대상 | 무엇을 |
|---|---|
| 클러스터 Secret | 네임스페이스의 `.p12` · `.jks` 전부(`tls-keystore` · `tls-id-<서비스>` …) — 하나라도 지금 비밀번호로 열리지 않으면 아무것도 바꾸지 않는다 |
| `ecommerce-secrets` | `LOCAL_KEY_STORE_PASSWORD` · `LOCAL_KEY_PASSWORD` · `KAFKA_SSL_*_PASSWORD` 중 **옛 값과 같은 것만**(다른 값은 그대로) |
| 이 기계 | `certs/` 의 스토어 · `certs/certs.env` · `k8s/20-secret.yaml` — 나중에 다시 적용해도 옛 값으로 돌아가지 않게 |
| 재시작 | 그 비밀번호를 읽는 Deployment 만, **하나씩**(노드 하나 — eureka · config 먼저, gateway 마지막) |
| 되돌리기 | 바꾸기 전 Secret 의 사본 `<이름>-prerotate`(같은 네임스페이스, 표지 `ecommerce.io/store-password-rotation`) · 파일의 `-prerotate` |

```bash
./ops/59-rotate-store-password.sh            # check — 스토어마다 열리는지 · 바꿀 키 · 재시작 대상(값은 출력하지 않는다)
./ops/59-rotate-store-password.sh apply      # 교체 + 재시작('yes' 를 묻는다)
./ops/59-rotate-store-password.sh rollback   # 문제가 있으면 — 사본으로 되돌리고 재시작
./ops/59-rotate-store-password.sh cleanup    # 하루쯤 이상이 없으면 — 사본에 옛 비밀번호가 있다
```

🔒 비밀번호는 인자 · 로그 · 화면에 나오지 않는다(keytool 은 0600 파일로 `-storepass:file`, 출력은 길이만). 호스트에 keytool 이 없으면 서비스와
같은 JRE 이미지(B-98)를 `docker run --network none` 으로 쓴다. cert-manager(ops/39)가 맡은 신원(운영은 `tls-id-*` 10개 — 2026-10-25 check)은 다음 갱신부터 새 비밀번호로 만든다 — 그래서 `ecommerce-secrets` 를 **먼저** 바꾸고(그 사이 cert-manager 가 다시 만들어도 새 값), Secret 의 라벨 · 주석 · 소유자는 그대로 둔다(빠지면 cert-manager 가 인증서를 새로 발급할 수 있다 — 사본에는 접어서 두고 되돌릴 때 펼친다).
가짜 kubectl 위에서 실제 PKCS12 · JKS 로 시험한다(`StorePasswordRotationScriptTest` — 되돌리면 실패하는 것 여섯 가지 확인).

### Eureka 쓰기 — 등록부는 그 서비스 자신의 인증서로만 (2026-10-14, B-86)

**무엇이 문제였나.** Eureka 는 클라이언트 인증서를 보지 않았다(서버 `client-auth` 없음). ecommerce 네임스페이스의 파드 하나만 뚫려도
`PUT /eureka/apps/ORDER-SERVICE/<id>/status?value=OUT_OF_SERVICE` 한 줄로 order-service 인스턴스가 목록에서 빠지고(게이트웨이 · Feign 의
`lb://` 가 대상을 잃어 503), `POST /eureka/apps/ORDER-SERVICE` 로 가짜 인스턴스를 끼우면 서비스 간 호출이 그쪽으로 갔다. 위의 mTLS 판정은
백엔드끼리의 호출만 지켰다.

게다가 **클라이언트 쪽 인증서가 없었다.** 백엔드는 JVM 키스토어(`-Djavax.net.ssl.keyStore`)를 갖지만 Eureka 클라이언트의 전송(RestTemplate ·
HttpClient 5)은 자기 SSLContext 를 **키 없이** 만든다(신뢰만 JVM 기본) — 실제 TLS 로 확인했다: 같은 JVM 키스토어로 JDK 기본 연결(Feign 의 길)은
인증서를 냈고 Eureka 클라이언트는 내지 않았다. 게이트웨이의 `tls:` 블록은 `eureka.instance` 아래에 있어 아무도 읽지 않았다.

**한 것.**

| 자리 | 한 것 |
|---|---|
| 클라이언트 — 공통 설정 | `application-common.yml` 의 `eureka.client.tls` — 신원 keystore(`/app/identity/keystore.p12`, CN=서비스 이름) · 공유 truststore. `EUREKA_CLIENT_TLS_ENABLED` 로 켠다(기본 꺼짐 — 로컬 개발 · ops/13). import 된 파일이라 서비스 자신의 yml 보다 우선한다 |
| 클라이언트 — 매니페스트 | 신원을 마운트한 Deployment(백엔드 10 · 게이트웨이 · config-server)가 `EUREKA_CLIENT_TLS_ENABLED=true`. config-server 에도 신원을 줬다(k8s/96 · ops/38 · ops/39) — 내부 API 는 부르지 않지만 Eureka 에 자기를 등록한다. ⚠️ config-server 는 common 을 쓰지 않아 공통 설정의 `eureka.client.tls` 를 받지 못한다 — 같은 블록을 자기 `application-local.yml` 에 둔다(2026-10-15 전에는 없어 키 없는 SSL 로 등록했다 — observe 에서 30초마다 `[eureka write]`, 가드 B-86 이 이제 대조한다) |
| 받는 쪽 — eureka-server | `SERVER_SSL_CLIENT_AUTH=want`(프로브 · 대시보드 · 등록부 읽기는 인증서 없이 그대로) + `RegistryWriteGuard`: 쓰기(GET · HEAD · OPTIONS 밖)는 내부 CA 가 서명한 인증서의 CN 이 경로의 앱 이름과 같아야 한다(대소문자 무시, 경로는 디코딩 · `;…` 제거 뒤 — Jersey 가 보는 이름). `/eureka/apps/` 밖의 쓰기(피어 복제 · ASG)는 이 단독 Eureka 에 올 일이 없어 거절. 인증서 판정은 `InternalCallerFilter.verifiedCommonName` 하나를 쓴다. common 의 `InternalCallerFilter`(내부 API 판정)는 eureka-server 에서 끈다 — 켜 두면 허용 주체 밖(config-server)의 하트비트마다 `[mTLS rule]` 경고가 남는다 |
| 모드 | ConfigMap `EUREKA_WRITE_AUTH_MODE`: off · observe(`[eureka write]` 로 남기고 통과) · enforce(403). enforce 인데 CA 를 못 읽으면 기동을 멈춘다 — 그대로 뜨면 모든 하트비트가 403 이 되어 90초 뒤 등록부가 빈다 |

**켜는 순서.**

```bash
./ops/38-issue-service-identities.sh --only config-server     # cert-manager 면 ops/39 (k8s/96 에 id-config-server)
./ops/28-deploy.sh <환경> <버전>                              # 클라이언트 TLS · want · EUREKA_WRITE_AUTH_MODE=observe
# 하루 뒤 — 어긋난 쓰기가 없어야 한다(처음 보는 (호출자, 메서드, 앱) 마다 한 줄)
kubectl -n ecommerce logs deploy/eureka-server --since=24h | grep -c '\[eureka write\]'      # 0
# k8s/10 의 EUREKA_WRITE_AUTH_MODE 를 enforce 로 바꾸고
./ops/28-deploy.sh <환경> --config-only --restart eureka-server
```

**운영 첫 판정(2026-10-26) — 서버가 자기 자신에게 복제하고 있었다.** 하루에 한 줄: `caller=(인증서 없음) POST app=/eureka/peerreplication/batch/`.
피어 복제는 Eureka 서버만 보낸다 — 단독 서버인데 `eureka.server.my-url` 이 없어 `PeerEurekaNodes` 가 defaultZone 의 `https://localhost:8761/eureka/`
를 파드 호스트 이름과 비교했고(다르다) 자기를 피어로 보아 등록 · 하트비트를 자기에게 복제했다(같은 줄은 파드마다 한 번만 남기므로 하루 한 줄). enforce
였다면 그 복제가 모두 403. 고침: `my-url` 을 defaultZone 과 같게(`EurekaServerPropertiesTest` — 다르면 실패). 이 이미지가 뜬 뒤 다시 하루 0 을 보고 enforce.

되돌리기: `EUREKA_WRITE_AUTH_MODE` 를 observe 로 같은 절차. 서버가 재시작해도 클라이언트는 30초 하트비트로 다시 등록한다.

> 🕰️ 2026-11-14: `my-url` 고침은 2026-10-26 운영에 들어갔고, 2026-10-30 다시 하루 0건을 보고 enforce 로 올렸다(로그 `mode=ENFORCE (CA 읽음)` · 10분 `[eureka write]` 0 · 재시작 0).
>
> 🕰️ 2026-11-21 정정(docs/99 §4): **그 고침은 k8s 에서 효과가 없었다.** eureka-server 파드도 ConfigMap 의 `EUREKA_CLIENT_SERVICEURL_DEFAULTZONE`(자기 Service 주소)을 받아 피어 목록이 자기가 됐고, `my-url` 은 yml 의 localhost 그대로라 걸러지지 않았다 — 자기 복제가 enforce 뒤 403 으로 5분마다 ~100줄(운영 ERROR 의 9할). `[eureka write]` 는 같은 줄을 파드마다 한 번만 남겨 '하루 0건' 이 이를 가렸다. 또 config-server 는 yml 에 `defaultZone` 키가 없어 env 가 먹지 않고 평문 localhost 로 붙어 **한 번도 Eureka 에 닿지 않았다** — 그 서비스의 '0건' 은 인증서가 맞아서가 아니었다. 고침: k8s/30 `EUREKA_SERVER_MYURL`(= ConfigMap defaultZone + `/`, 시험 `PeerSelfReplicationTest` — 매니페스트 + ConfigMap + yml 을 묶는다) · config-server yml 의 `service-url.defaultZone`(시험 `EurekaServiceUrlBindingTest`). 다음 배포 뒤 두 ERROR 줄 0 · config-server 가 등록부에 보인다.

테스트: `RegistryWriteGuardTlsTest`(실제 TLS 로 띄운 Eureka 에 **실제 Eureka 클라이언트**가 공통 설정 그대로 등록 — 신원은 통과 · TLS 를 끈
클라이언트는 JVM 키스토어가 있어도 인증서를 내지 않아 거절 · 다른 서비스 인증서로 남의 앱 등록 거절 · 인증서 없이 OUT_OF_SERVICE 403 ·
읽기 200 · 인코딩한 앱 이름도 같은 판정), `RegistryWriteGuardTest`(모드 · 메서드 · 경로), B-86 `EurekaClientIdentityGuardTest`(신원 마운트 ↔
`EUREKA_CLIENT_TLS_ENABLED` · 발급 목록 셋 · Reloader · want · 모드).

### 남은 것 — 정직하게

- 서버 인증서는 여전히 공유다 — 게이트웨이도 그 키를 갖는다. 다만 그 키(CN=localhost)는 이제 **허용 주체가 아니라** 내부 호출로
  쓸 수 없다. 서버 인증서까지 서비스별로 나누는 것은 이득이 작다(서버 인증은 "같은 CA 인가" 만 본다).
- 서버 쪽 `need`(인증서 없으면 연결 거부)가 아니라 `want` 다 — kubelet 의 HTTPS 프로브가 인증서를 내지 못한다. 판정은 앱이 한다(Eureka 도 같다 — 쓰기만 판정한다).
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

> 🕰️ 2026-11-14: k8s/96 의 Certificate 는 이제 **12개** — §16 의 게이트웨이(`id-gateway-server`, 2026-10-14)와 config-server(`id-config-server`, Eureka 쓰기)가 더해졌다.

**가드**: B-68 — 백엔드가 마운트하는 신원 Secret 마다 k8s/96 에 같은 이름을 발급하는 Certificate 가 있고, 그 Deployment 에 Reloader
주석이 있다(주석을 지우면 실패하는 것을 확인).

**남은 것**: 공유 서버 인증서(`tls-keystore`)는 여전히 손으로 — 825일이라 급하지 않지만, 같은 방식(Certificate + Reloader)으로
옮길 수 있다. 그때는 Kafka·Redis·Postgres 클라이언트가 같은 파일을 쓰므로 인프라 쪽 재시작까지 함께 계획해야 한다.

> 🕰️ 2026-11-20: 서버 인증서는 §27 로 옮겼다 — 새 Certificate 를 만들지 않고 이 신원을 서버 인증서로도 쓴다(eureka-server 신원 추가). 공유 파일의 Kafka 클라이언트 키 · CA 는 그대로.

## 19. 브라우저 보안 헤더 · 외부 CSS — 프론트엔드 점검(2026-10-22)

`manuscripts/보안-면접`(problem-solving 저장소)의 브라우저 쪽 문항(Q5 · Q9 · Q10 · Q25 ~ Q28 · Q33 · Q36)을 점검표로 frontend 를 봤다. 토큰(메모리 + `HttpOnly · Secure · SameSite=Lax` 리프레시 쿠키) · XSS 싱크(0건) · 열린 리다이렉트(역슬래시까지 거름) · 번들의 비밀(공개 id 뿐) · 소스맵(404)은 이미 기준을 지켰다. 빈 곳 둘을 메웠다.

**보안 헤더가 하나도 없었다** — 결제 · 계정 · 관리자 화면을 남의 사이트가 투명한 iframe 에 넣는 클릭재킹(Q33)이 가능했다. 이제 frontend nginx 의 모든 응답에:

| 헤더 | 값 | 강제? |
|---|---|---|
| `X-Frame-Options` | `DENY` | 강제 |
| `Content-Security-Policy` | `default-src 'self'` · 스크립트는 자기 출처 + PortOne v1 · v2 · PayPal(`'unsafe-inline'` 없음) · `object-src 'none'` · `base-uri 'self'` · `frame-ancestors 'none'` | 강제(2026-10-25 — 그 전 사흘은 `frame-ancestors` 만 강제, 나머지는 `-Report-Only`) |
| `X-Content-Type-Options` · `Referrer-Policy` · `Permissions-Policy` · `Strict-Transport-Security` | `nosniff` · `strict-origin-when-cross-origin` · 카메라 · 마이크 · 위치 · USB 끔 · 1년(하위 도메인 제외) | 강제 |

- CSP 를 보고만 하는 이유: 결제 SDK 와 MUI · emotion 의 인라인 스타일이 있어 처음부터 강제하면 결제 화면이 깨질 수 있다(Q26 — 나중에 넣는 CSP 는 report-only 로 위반부터). **배포 뒤 카드 · PayPal · 무통장까지 한 바퀴 돌려 브라우저 콘솔에 CSP 위반이 없으면** `Content-Security-Policy` 로 바꾼다. **2026-10-25 에 바꿨다** — 운영에서 카드 · PayPal · 무통장을 돌려 콘솔 위반 0. 포트원은 숨긴 채(`HIDDEN_METHODS`)라 다시 열 때 결제창까지 한 번 돌려 콘솔을 본다. 새 외부 스크립트 · 결제창 · API 출처는 정책에 먼저 더한다(빠지면 그 기능이 조용히 막히고 콘솔에만 보인다).
- ⚠️ nginx 는 location 안에 `add_header` 가 하나라도 있으면 server 의 것을 물려받지 않는다 — `/index.html` · `/static/` · `/healthz` 에도 같은 줄을 둔다(`nginxHeaders.test.js`). 실제 nginx 1.27 에 dist 를 올려 `/` · SPA 경로 · `/static/*.js` · 404 · `.map` 모두 일곱 개를 확인했다(강제로 바꾼 뒤로는 여섯 — CSP 가 하나가 됐다).

**Font Awesome CSS 를 cdnjs 에서 SRI 없이 받았다** — 아이콘은 채팅의 셋뿐이었다. 같은 Font Awesome 5 아이콘을 `react-icons/fa`(SVG)로 바꿔 외부 출처를 없앴다(`indexHtml.test.js` — 외부 `link` · `script` 0, `fas fa-` 0).

남은 것: ~~재설정 링크의 `?token=` 을 읽은 뒤 주소창에서 지우기, 택배 조회 주소의 송장 번호 인코딩~~(2026-11-19 끝 — §22), react-router v7 · jest 30 메이저 판 올림(남은 npm 권고 7건 — 개발 도구 · 이미 막은 것).

## 20. JRE 보안 수정 — CVE-2026-114938("Java4Shell") · 10월 CPU(2026-11-18)

**무엇**: 2026-11-17 공개된 java.util.Base64 디코더의 메모리 손상(보도 기준 CVSS 8.1 — 조작한 Base64 입력으로 JVM 이 죽고, 원격 코드 실행 가능성이 거론된다; 2026-11-17 기준 실제 악용 확인은 없다는 보도). 영향 JDK 17 · 21 · 25 · 27, 고친 판 17.0.21.1 · 21.0.13.1 · 25.0.5.1 · 27.0.1.1. 같은 날 Oracle 의 JSSE 경보 CVE-2026-91403(보도 기준 9.1)도 17.0.21 을 영향 판으로 든다.
⚠️ 근거는 보도 · 검색 요약이다 — Oracle · CISA 원문은 이 환경에서 열리지 않았다(403 · DNS). 배포 전에 원문으로 판과 우회책을 한 번 더 확인한다.

**우리 쪽**: 런타임 이미지는 `eclipse-temurin:17.0.20.1`(B-98 고정)이었다 — 10월 CPU(17.0.21, 보안 수정 55)보다도 앞이다. 외부 입력을 java.util.Base64 로 디코딩하는 곳이 있다:

| 경로 | 입력 | 인증 |
|---|---|---|
| `PortOneV2WebhookVerifier` | 웹훅 서명 헤더 | 전 |
| `PasskeyService`(`Base64.getUrlDecoder`) | 패스키 등록 · 로그인 응답 | 전(로그인) |
| `GraceTokenCipher` | 2FA 대기 토큰 | 전(2단계) |
| Spring Security 기본 인증(config-server) | `Authorization` 헤더 | 클러스터 안 |

게이트웨이의 JWT(jjwt 0.12)와 Jackson 의 byte[] 는 자기 Base64 구현을 쓴다고 알고 있다 — 확인은 하지 않았다.

**한 것**(이미지를 다시 빌드해 배포해야 들어간다):
1. JRE → `eclipse-temurin:17.0.21_9-jre-jammy@sha256:aa638138…`(10월 CPU, amd64 · arm64 목록 — Docker Hub API 기준). 11월 수정판(17.0.21.1)의 Temurin 이미지는 2026-11-18 에 아직 없다.
2. 엔트리포인트에 `-XX:-UseBASE64Intrinsics` — 보도된 우회책(내장 함수를 끄면 Base64 가 순수 자바로 돈다 — 요청마다 수백 바이트라 차이는 없다고 본다, 측정은 안 했다). JDK 17.0.20.1 에서 옵션이 받아들여지고 Base64 가 정상인 것만 확인했다.
3. 가드 **B-113**: JRE 가 수정판 아래면 엔트리포인트에 그 옵션(JAVA_OPTS 앞), 매니페스트 · 오버레이 · 스크립트 어디에도 `+UseBASE64Intrinsics` 없음. 수정판으로 올리면 옵션을 요구하지 않는다.

**남은 것**: ① 배포 — 이미지는 CI 가 만든다 → 릴리스 후보 초록 → Actions → deploy(docs/85). 배포 뒤 `kubectl -n ecommerce exec deploy/catalog-service -- sh -c 'java -version 2>&1 | grep -v JAVA_TOOL_OPTIONS'` 가 17.0.21 · `ps` 의 명령줄에 `-XX:-UseBASE64Intrinsics`. ② Temurin 17.0.21.1 이 나오면 JRE 줄을 그 판으로 · 옵션 삭제(B-113 이 허락한다) · 다시 배포 — CVE-2026-91403(JSSE)은 그것으로만 닫힌다. ③ jdk21-build 브랜치는 21 판(수정판 21.0.13.1)으로 같은 일. ④ CI 러너 · 개발 PC 의 JDK 는 외부 입력을 받지 않아 급하지 않다.

---

## 21. 프론트 nginx 보안 수정 — "NGINX2Root"(CVE-2026-123007 외 넷, 2026-11-19)

**무엇**: 2026-11-18 공개된 nginx 취약점 묶음. 중심은 CVE-2026-123007 — HTTP/2 처리의 메모리 손상으로 인증 없는 원격 코드 실행(F5 기준 CVSS 9.2, 공개 PoC 있음; 1.9.5 부터 들어 있던 코드). 같은 묶음에 HTTP/2 use-after-free(CVE-2026-123633) · HTTP/3 · resolver · mail 모듈 취약점. 영향 1.9.5 ~ 1.30.6 · 1.31.0 ~ 1.31.8, 고친 판 **1.30.7(stable) · 1.31.9(mainline)**. HTTP/2 를 끄면 HTTP/2 두 건만 막힌다.
⚠️ 근거는 보도 · 보안 업체 글이다(Qualys · Upwind · The Hacker News 등 — 판 범위가 글마다 조금 다르다). F5 공지 원문은 이 환경에서 확인하지 않았다.

**우리 쪽**: 프론트 이미지가 `nginx:1.27-alpine` 이었다 — 영향 범위 안이고, 1.27 갈래는 지원이 끝나 수정판이 나오지 않는다. 다만 `frontend/nginx.conf` 는 `listen 8080`(HTTP/1.1)뿐이고 `http2` · `quic` · `resolver` · `mail` 이 없다. 손님의 HTTP/2 는 Cloudflare 가 받고 안쪽은 cloudflared → traefik → frontend 가 HTTP/1.1 이라, 핵심 HTTP/2 경로는 바깥에서 닿지 않았다고 본다. 운영 노드에 다른 nginx 컨테이너는 없다(2026-11-19 `docker ps` 0건). 저장소에서 nginx 를 쓰는 곳은 두 Dockerfile 과 `ops/18`(SSE 시험)뿐.

**한 것**(frontend 이미지를 다시 빌드해 배포해야 들어간다):
1. `nginx:1.30.7-alpine@sha256:bef15473…`(다중 아키텍처 목록 — Docker Hub API 기준) — `frontend/Dockerfile` · `ops/Dockerfile.frontend-runtime` · `ops/18-test-nginx-sse.sh`.
2. 확인: 그 이미지로 실제 `nginx.conf`(템플릿 치환 포함) `nginx -t` 통과 · 보안 헤더 일곱 줄 그대로 · SPA 경로 200, `ops/18`(SSE 버퍼링 · envsubst 필터 · gzip 대조군) 8/8 통과.
3. 가드 **B-114**: 세 곳이 같은 `nginx:<판>-alpine@sha256:…`(태그만 금지), 판은 1.30.7 · 1.31.9 이상(1.30 아래 갈래는 거절), `nginx.conf` 는 HTTP/1.1 만(`http2` · `quic` · `http3` · `resolver` · `mail` 없음 — 켤 까닭 없이 그 코드 경로를 열지 않는다). 변이 확인: 1.27 태그 · 세 곳 1.30.6 · 세 곳 1.31.8 · 한 곳만 다른 판 · `http2 on` → 실패.

**남은 것**: 배포 — 다음 배포(docs/85 §9)에 함께, 급하면 frontend 만(Actions → deploy). 배포 뒤 `curl -sI https://www.ysyproject.com/` 는 Cloudflare 가 `Server` 를 바꾸므로 판은 파드에서 본다: `kubectl -n ecommerce exec deploy/frontend -- nginx -v` 가 1.30.7. 선택: `server_tokens off`(안쪽 응답의 `Server: nginx/1.30.7` 판 표시 — 바깥은 Cloudflare 가 가린다).

## 22. 보안 남은 일 정리 — 토큰 즉시 폐기 · 복구 코드 · Kafka 신뢰 패키지 외(2026-11-19)

"모든 문서를 통틀은 남은 일" 의 보안 칸 가운데 코드로 끝낼 수 있는 일곱을 했다. 하지 않은 것과 그 까닭은 아래 **남은 것**.

| # | 무엇이 문제였나 | 한 것 | 시험(변이 확인) |
|---|---|---|---|
| 1 | **훔친 액세스 토큰이 로그인 해제 뒤에도 최대 15분** — 게이트웨이는 서명 · 만료만 봤다("모든 기기에서 로그아웃" · 비밀번호 변경 · 재설정 · 관리자 잠금 · 탈퇴 · 리프레시 재사용 감지 뒤에도, docs/68 §7 은 "충분하다" 로 두었다) | 게이트웨이가 user-service 가 이미 쓰던 `auth:cutoff:<uid>`(긴 연결용, docs/68 §9)를 읽어 그보다 이른 `iat` 는 401(`JwtAuthorizationGatewayFilterFactory#sessionStillValid`). 인증 요청마다 Redis GET 하나 · 상한 300ms(`GATEWAY_SESSION_CUTOFF_TIMEOUT`), 느리거나 실패하면 통과(만료가 15분 상한), 끄기 `GATEWAY_SESSION_CUTOFF_ENABLED=false`. 화면 문구 "최대 15분 안에" → "곧바로" | gateway `JwtSessionCutoffTest` 4(이른 토큰 401 · 뒤 토큰 통과 · 기록 없음 · Redis 실패/무응답 통과 — 확인을 빼면 실패) |
| 2 | **2단계 인증 복구 수단이 없다** — 인증 앱을 지우거나 번호를 바꾸면(SMS 는 켤 때의 번호에 묶인다) 비밀번호를 알아도 못 들어온다. 비밀번호 재설정은 2단계 인증을 풀지 않는다 | 복구 코드 10개(`RecoveryCodes`, user V21 `two_factor_recovery_code`): 31글자 × 10자리 · 원문은 만들 때 응답에만(`no-store`), DB 에는 HMAC(파생 키 — `auth.recovery-code.pepper`, 없으면 JWT 비밀; 바꾸면 모든 코드 무효) · 한 번만(쓰지 않은 행만 UPDATE) · 시도는 TOTP · SMS 와 같은 칸 · 만들기 · 쓰기는 주인에게 알림(`recovery-codes-created` · `recovery-code-used`) · 마지막 수단을 끄면 지움 · 탈퇴 때 지움. 만들기는 최근 로그인(10분) 필요. 로그인 2단계에 "복구 코드 사용", 보안 화면에 만들기 · 남은 수 | user `RecoveryCodesTest` 5 · `RecoveryCodeLoginTest` 5 · `RecoveryCodeMigrationTest`(컨테이너) · notification `DeliveryStatusNotificationTest` +1 · 프론트 `RecoveryCodes.test.jsx` 3 · `SignInPage.test.jsx` +2 |
| 3 | **Kafka JSON 역직렬화가 `addTrustedPackages("*")`**(10곳) — 컨슈머가 헤더(`__TypeId__`)를 따르므로 토픽에 쓸 수 있는 누구든 헤더 하나로 클래스패스의 아무 클래스나 만들게 할 수 있었다(Jackson 가젯의 입구) | 메시지 DTO 패키지(`com.project.common.message.dto.*`)만 — `KafkaTrustedPackages.trustMessageDtos`. 가드 **B-115**(`"*"` 금지 · 헬퍼 10곳 이상) | common `KafkaTrustedPackagesTest` 2 · 가드 1 |
| 4 | **소셜 로그인은 새 기기를 보지 않았다** — 소셜 계정을 가져간 사람은 "새로운 기기에서 로그인" 알림 없이 들어왔다(docs/44 §4) | `OAuth2SuccessHandler` 가 비밀번호 로그인과 같은 자리(본인 확인 뒤 · 2FA 앞)에서 기기를 기록하고 처음 보는 기기면 알린다. 실패해도 로그인은 계속 | user `OAuth2SuccessHandlerTest` +2 |
| 5 | **로그인 때는 유출 비밀번호를 보지 않았다** — 가입 · 변경 · 재설정에서만(docs/68 §18). 그 뒤 샌 비밀번호를 쓰는 사람은 알 길이 없었다 | `LoginPasswordAdvisor`: 비밀번호가 **맞은 뒤에만** HIBP(k-익명성) · 흔한 목록을 보고, 걸리면 응답 `passwordAdvice: "breached"` → 화면이 변경을 권한다(**막지 않는다**). "괜찮음" 은 7일 기억(`auth:pw-ok:<uid>`, 해시도 두지 않는다 — 바꾼 비밀번호는 이미 변경 경로에서 통과했다), 확인 못 함은 1시간. 끄기 `auth.breached-password.login-check.enabled=false` | user `LoginPasswordAdvisorTest` 4 · `LoginPasswordAdviceFilterTest` 3(틀린 비밀번호로는 묻지 않는다) · `SignInPage.test.jsx` +1 |
| 6 | **재설정 링크의 토큰이 주소창 · 방문 기록에 남았다**(docs/57 §19) | 처음 읽은 값을 붙잡고 `history.replaceState` 로 `?token=` 만 지운다 | `ResetPasswordPage.test.jsx` +1 |
| 7 | **택배 조회 링크에 송장 번호를 글자 그대로 넣었다** — `&` · `#` · `/` 가 링크의 쿼리 · 경로를 바꿨다 | RFC 3986 비예약 문자 밖은 퍼센트 인코딩(`Carrier.trackingUrlFor`) | delivery `CarrierTrackingUrlTest` 3 |

**배포 순서**: notification(새 알림 두 종류 — 옛 notification 은 "새 기기" 문구로 떨어진다)을 user 보다 먼저, user(V21 — 새 표 하나, 되돌리기 안전) · gateway · frontend · 공통 이미지(cart · catalog · coupon · order · pay — 신뢰 패키지). 게이트웨이와 user-service 는 같은 Redis(`REDIS_HOST`, db 0)를 봐야 한다 — 다르면 해제 시각을 못 읽어 예전처럼 15분. 배포 뒤: "모든 기기에서 로그아웃" 뒤 다른 브라우저의 다음 요청이 401 인지, 로그 `로그인 비밀번호가 유출` 수 · 지표 `auth_breached_password_login_advised_total`.

**남은 것(코드만으로는 끝나지 않는다)**:
- NetworkPolicy egress 허용 목록 — 결제사 · SMS · 메일 · 택배사 · HIBP · DNS 목록을 정한 뒤(잘못 막으면 결제가 멈춘다). → 🕰️ 1단계는 §23(IP 목록 없이).
- ~~공유 서버 인증서 `tls-keystore`(825일)를 서비스별로 · cert-manager + Reloader(§18)~~ → 🕰️ 2026-11-20 §27(서버 인증서 = 서비스별 신원), 비밀 관리 도구(SOPS · Sealed Secrets · Vault)와 정기 회전, 개인 키 HSM/KMS — 운영 장비 · 비용 판단.
- ~~관리 포트 분리(`management.server.port`) — 프로브 · metrics-agent · 매니페스트 13개를 함께 바꾸는 배포 작업.~~ → 🕰️ 2026-11-20 §25.
- react-router v7 · jest 30 메이저 판 올림 — 화면 전체 회귀가 필요한 별도 작업.
- 같은 전화번호를 두 계정이 쓰는 것 — 막을지(가족 공용 번호)부터 정할 일.

### 22-1. 해제 확인이 보이게(2026-11-21)

**문제**: 운영에서 비밀번호를 재설정했는데 다른 브라우저(시크릿 창)가 그대로 진행됐다(user-service 로그 `끊은 로그인=0`, `auth:cutoff` 는 있었다). 그런데 게이트웨이가 그 브라우저에 401 을 줬는지 알 수 없었다 — 거절도, Redis 를 못 읽어 통과시킨 것도 debug 로그뿐이었다.

**한 것**: 계수기 `gateway_session_cutoff_total{result}` — `revoked`(해제 뒤 토큰을 401) · `error` · `timeout` · `no_redis`(확인하지 못해 통과). 기동 때 넷 모두 0(B-101). 로그: 거절은 한 줄씩 INFO `[auth] 로그인 해제 뒤의 액세스 토큰 — 401 uid= 경로=`(uid 와 경로만), 통과는 분마다 한 줄 WARN(그 사이 건수와 함께), 확인이 꺼져 있거나 Redis 템플릿이 없으면 기동 때 WARN. 시험 gateway `JwtSessionCutoffTest` +3(변이 넷: 각 계수 · 기동 등록을 빼면 실패).

**판정(배포 뒤 같은 일이 생기면)**: `kubectl -n ecommerce logs deploy/gateway-server --since=1h | grep '\[auth\] 로그인 해제'` — 401 줄이 있으면 게이트웨이는 막았다(그 뒤는 화면 · 갱신 쪽), WARN 이면 Redis, 아무것도 없으면 그 브라우저는 로그인이 필요한 요청을 보내지 않았다(공개 화면만). 지표는 `sum by (result) (increase(gateway_session_cutoff_total[1h]))`. 경보는 아직 두지 않았다 — 평소 값을 본 뒤 정한다.

**운영 확인(2026-11-21 07:46 UTC)**: 크롬 시크릿 창 로그인 → 파이어폭스에서 재설정 → `끊은 로그인=1`, 0.3초 뒤 갱신이 `비밀번호 변경 이전에 발급된 리프레시 토큰` 으로 4번 거절되고 크롬이 로그아웃됐다. 게이트웨이 `revoked` 는 0 — 알림 연결(SSE)이 먼저 닫혀 화면이 옛 액세스 토큰을 쓰기 전에 갱신으로 끊겼다(게이트웨이는 그 길이 없을 때의 두 번째 겹). 앞의 두 번의 `끊은 로그인=0` 은 같은 조건에서 재현되지 않았다 — 그 순간 색인에 그 로그인이 없었던 것으로 본다.

**되풀이 갱신 고침(2026-11-21)**: 위 확인에서 끊긴 브라우저가 갱신을 0.26초 간격으로 4번 보내 같은 거절을 4번 받았다. 서버 — 비밀번호 변경으로 거절할 때만 리프레시 쿠키를 남겼다 → 다른 거절처럼 쿠키를 지우고 그 묶음도 끊는다(다음 시도는 쿠키가 없어 204, `RefreshTokenRotationControllerTest` +1, 변이 둘). 화면 — 갱신을 부르는 셋(알림 연결의 Reauth · 채팅 연결의 4401 · 401 재시도)이 서로의 진행을 몰랐다 → 갱신 요청은 한 번에 하나(겹치면 같은 결과를 함께 받는다), 갱신이 실패해 로그인이 끝나면 새 로그인까지 연결의 갱신 부탁을 보내지 않는다(`TokenRefresher.test.jsx` +2, 변이 둘). 배포 뒤 재설정 → `비밀번호 변경 이전에 발급된` 줄이 한 번.

## 23. 나가는 연결(egress) 1단계 — IP 목록 없이(2026-11-19)

**문제**: 들어오는 연결은 §11 로 막았지만 나가는 연결은 모두 열려 있었다. 서비스 하나에 SSRF · 원격 코드 실행이 생기면 그 파드에서 클라우드 메타데이터(`169.254.169.254` — 자격증명) · 노드의 다른 포트(kubelet · SSH · 관측 스택) · 같은 VCN 의 장비에 닿고, 바깥으로 데이터를 내보낼 수 있다. 다만 NetworkPolicy 는 도메인이 아니라 IP 로만 고르고, 결제사 · Atlas · Gmail · S3 의 IP 는 바뀐다 — IP 허용 목록은 언젠가 결제를 멈춘다(§22).

**한 것** — `k8s/97-network-policy-egress.yaml`(정책 둘, kustomization 밖):

| 그룹 | 서비스 | 나갈 수 있는 곳 |
|---|---|---|
| 클러스터 안만(`egress-cluster-only`) | eureka · gateway · cart · coupon · pay · saga-projection · frontend | 같은 네임스페이스 · 인프라 네임스페이스(postgres · redis · kafka · mongodb · elasticsearch) · kube-dns(53) · 노드의 OTLP 수집기(4318) |
| 바깥 필요(`egress-public-no-private`) | user(OAuth · HIBP · 본인인증) · catalog(S3) · order(Stripe · PayPal · 포트원 · 환율) · notification(SMTP · SMS) · delivery(택배사) · chat(Atlas) · config-server(설정 Git) | 위 + 인터넷 — 단 `10/8` · `172.16/12` · `192.168/16` · `169.254/16`(메타데이터) · `100.64/10` · `127/8` 은 닫는다 |

정책 밖(예전처럼 다 열림): metrics-agent(노드 9090 · API 서버) · cloudflared · 백업 CronJob — 각자 노드 · 바깥이 필요하고 손님 요청을 받지 않는다(2단계에서).

**적용은 `ops/35-network-policy.sh egress-apply` 로만**:
1. 사전 점검 — '클러스터 안만' 그룹이 쓰는 PostgreSQL · Redis · Kafka 주소(ConfigMap `ecommerce-config`)가 모두 `*.svc.cluster.local` 인지. external 오버레이처럼 Neon · Upstash · Confluent 로 뺀 환경이면 **적용하지 않는다**(그 그룹이 DB 에 못 닿는다).
2. 매니페스트의 `__NODE_IP__` 줄을 노드 InternalIP 마다 펼친다(파드의 `HOST_IP` = 추적을 보내는 곳). 원본을 그대로 `kubectl apply` 하면 CIDR 이 틀려 API 서버가 거절한다 — 일부러.
3. 적용 **전**에 두 그룹 이름의 두드리기 파드(`netpol-probe-internal` · `-public`, 같은 정책을 받는다)로 흐름을 기록하고, 적용 **뒤** 다시 두드린다 — 허용: DNS · 게이트웨이 · user · PostgreSQL · Redis · Kafka · 노드:4318(public 은 + `api.stripe.com:443` · `1.1.1.1:443`), 차단: 메타데이터 · 노드:10250(internal 은 + 바깥 둘). 허용이 하나라도 막히면 **스스로 지운다**, 막혀야 할 것이 하나라도 열려 있으면 경고 종료(2, 지우지 않는다), 정책 전에도 닫혀 있던 것은 판정에서 뺀다.

**확인**: 렌더한 매니페스트는 k3s v1.30.6 API 서버의 server dry-run 을 통과(원본은 `__NODE_IP__/32` 로 거절) · 가짜 kubectl 로 판단 여섯(`EgressPolicyScriptTest` — 적용 · 되돌리기 · 외부 DB 거절 · 집행 안 됨 · 일부만 집행 · 전부터 닫힌 것; 되돌리기 · 사전 점검 · IP 펼치기 · 일부 집행을 빼면 각각 실패) · 가드 **B-117**(`EgressPolicyGuardTest` 4 — ecommerce Deployment 는 정확히 한 그룹, '클러스터 안만' 그룹의 코드 · 설정 · frontend nginx 에 바깥 주소 없음, 예외 대역 · 0.0.0.0/0 없음, 노드 자리표시 · kustomization 밖; 그룹에서 빼기 · catalog 를 안쪽에 넣기 · 169.254 빼기 · IP 손으로 적기는 각각 실패). ⚠️ **실제 집행(kube-router)은 보지 못했다** — 이 환경의 docker 안 k3s 는 파드를 띄우지 못했다(중첩 runc). 실습 클러스터에서 먼저 `egress-apply` 를 돌린다.

🕰️ **2026-11-19 첫 실습 시도**: registry-vm(이미지 빌드 VM)에서 돌려 "ConfigMap 에서 백엔드 주소를 읽지 못했다" 로 멈췄다 — 그 셸의 kubectl 이 실습 클러스터를 가리키지 않은 것으로 보인다(스크립트가 오류를 삼켜 까닭을 말하지 않았다). 이제 사전 점검이 **클러스터에 닿지 못함(지금 컨텍스트와 kubectl 오류 첫 줄) · ConfigMap 없음 · 키가 비어 있음**을 나눠 말한다(`EgressPolicyScriptTest` +1). 실습 클러스터의 kubeconfig 가 있는 곳(서버 노드 · kubectl 을 쓰는 PC)에서 돌린다.

**순서**: 실습 클러스터 `egress-apply` → 결제(카드 · PayPal) · 메일 · 소셜 로그인 · 이미지 업로드 · 채팅 · 택배 조회를 한 바퀴 → 운영 노드에서 `egress-apply`(되돌리기는 `egress-remove`). 운영은 하이브리드 원격 노드(docs/71)가 있으면 노드 IP 가 둘 — 스크립트가 둘 다 펼친다.

**2단계** → 🕰️ 2026-11-20 §26(metrics-agent · cloudflared · 백업 CronJob 의 정책, public 그룹의 포트 좁히기). 남은 것: 바깥 주소별 허용(도메인 단위가 필요해지면 Cilium 등 — 노드가 늘 때 함께 판단).

## 24. 운영 Secret 을 암호문으로 저장소에 — SOPS + age(2026-11-19)

**문제**: 운영 값은 평문 `k8s/20-secret.yaml`(gitignore) 하나뿐이고, 그 파일이 운영 노드 · 작업 PC 에 흩어져 있었다. 어느 것이 최신인지, 언제 무엇을 바꿨는지 남지 않았다(어긋남은 ops/27 이 사후에 찾았다). 디스크를 잃으면 값도 잃는다.

**정한 것**: SOPS + age. Vault 는 서버 하나를 더 운영해야 하고, Sealed Secrets 는 클러스터 키를 따로 백업해야 한다 — 운영자 한 명 · 노드 한 대에는 파일 하나가 맞다. 값(`stringData` · `data`)만 암호화하므로 `kind` · 이름 · **키 이름은 평문**이라 커밋 diff 에서 "무엇이 바뀌었는지" 는 보이고 값은 안 보인다.

**한 것**:
- `ops/62-secrets-sops.sh` — `keygen [<파일>]`(age 키, 공개 키만 출력 — 파일을 주면 그 이름으로: 보관용 · 다른 기계용 키를 이 기계의 키와 따로) · `init <age1…>`(받는 사람) · `encrypt`(왕복 확인 뒤에만 암호문을 바꾼다) · `check`(값이 모두 암호문 · 이 기계의 키로 풀리나 · 예시와 키 이름 · 평문과 같은가 — 값 대신 sha256 앞 12자로 비교) · `apply`(평문 파일 없이 파이프로 `kubectl apply`) · `decrypt`(0600, 다른 평문은 덮어쓰지 않는다 — `FORCE=1`) · `updatekeys`. 어떤 경로도 값을 출력하지 않는다.
- `.sops.yaml` — 그 파일만 · 값만 암호화. 받는 사람은 처음 자리표시(`age1REPLACE_ME`)로 커밋했고 — 자리표시인 동안 `encrypt` 는 거절한다 — 2026-11-19 에 실제 공개 키 셋으로 바꿨다.
- 암호문 `k8s/20-secret.enc.yaml` 은 `.gitignore`(`k8s/20-secret.yaml*`)에 걸리지 않는다 — 커밋한다. 포트폴리오(공개)에는 옮기지 않는다(`.sops.yaml` 도).
- 받는 사람은 셋을 권한다 — 운영 노드 · 작업 PC · 오프라인 보관(종이 · 비밀번호 관리자). 개인 키를 모두 잃으면 암호문은 풀 수 없다.

**확인**: 실제 sops 3.9.4(공식 릴리스 · 체크섬 확인) · age 로 `SopsSecretsScriptTest` 6 — 값은 모두 암호문(평문 값 없음, 키 이름은 보임) · 두 받는 사람 모두 풀림 · 자리표시 · 잘못된 키 거절 · 암호문에 평문 한 줄이 섞이면 그 키 이름만 대고 실패(값은 말하지 않는다) · 다른 평문을 덮어쓰지 않음 · `updatekeys` 로 뺀 키는 못 푼다 · `apply` 는 평문 파일을 만들지 않는다. sops · age 가 PATH 에 없으면 건너뛴다(CI) — 그때도 가드 **B-118**(`SopsSecretGuardTest` 4: `.sops.yaml` 의 대상 · `encrypted_regex` · 받는 사람 모양, 커밋된 암호문의 값이 모두 `ENC[…]`(빈 값 제외), `.gitignore` 가 평문은 막고 암호문은 막지 않음, 포트폴리오 제외)이 돈다. 변이: 자리표시 검사 · 키 이름 읽기 · 덮어쓰기 막기 · 파이프 apply(평문 파일로 바꾸면 멈춘다)를 빼면 각각 실패, `.gitignore` 가 암호문까지 막거나 `encrypted_regex` 를 넓히면 가드가 실패.
🐛 만들면서 잡은 것: sops 는 YAML 을 네 칸 들여쓰기로 다시 쓴다 — 처음의 키 이름 읽기(두 칸만)는 암호문의 "평문 섞임" 검사에서 **아무 줄도 보지 않고 통과**했다(실제 sops 로 돌려 드러남). 빈 값(`""`)은 sops 가 암호화하지 않는다(숨길 것이 없다 — 누출로 세지 않는다). `diff` 가 다르면 1 을 돌려 `pipefail` 아래서 스크립트가 **말없이** 끝났다.

**처음 한 번**(운영 노드에서 — 평문이 있는 곳):
1. sops · age 설치(age 는 `apt install age`, sops 는 릴리스 바이너리 + 체크섬).
2. 운영 노드 · 작업 PC 는 각자 `./ops/62-secrets-sops.sh keygen`, 보관용은 이름을 주어 `keygen ~/age-offline-backup.txt`(옮긴 뒤 그 기계에서는 지운다) → 공개 키 셋을 모아 `init age1… age1… age1…`. **끝(2026-11-19)** — 받는 사람 셋(작업 PC · 오프라인 보관 · 운영 노드, 세 키 모두 작업 PC 에서 만들어 옮겼다)을 `.sops.yaml` 에 커밋했다.
   ⚠️ `init` 은 **저장소 안에서** 돌린다 — 스크립트가 자기 위치(`$0/..`)로 저장소 루트를 잡는다. `/tmp` 에 사본을 꺼내 `sudo` 로 돌리면 루트가 `/` 가 되어 `/.sops.yaml` 에 root 소유로 쓴다(저장소에는 아무것도 생기지 않는다). 운영 체크아웃을 더럽히지 않으려면 자리만 지정한다: `SOPS_CONFIG=$HOME/ecommerce-msa/.sops.yaml /tmp/62.sh init …`.
   ⚠️ 같은 공개 키를 두 번 넘겨도 `sops` 는 거절하지 않는다 — 리뷰에서 "받는 사람이 셋" 으로 보여 누가 더 있는지 세게 만든다. `init` 이 중복을 걸러내고 몇 개를 뺐는지 말한다(실제로 운영 노드의 첫 `.sops.yaml` 에 같은 키가 둘 있었다).
3. `encrypt` → `check` → `git add k8s/20-secret.enc.yaml .sops.yaml` → 커밋 · push(값은 암호문).
4. 그 뒤로는 평문 대신 암호문이 원본 — 바꿀 때는 `decrypt` → 고치기 → `encrypt` → 커밋, 적용은 `apply`. ops/27 · 46 · 59 처럼 평문을 읽는 스크립트는 `decrypt` 로 평문을 만든 뒤 돌린다.

**첫 암호화(2026-11-19, 운영 노드)**: `encrypt` → 키 **32개** 암호화 · `check` 네 줄 초록(값이 모두 암호문 · 이 기계 키로 풀림 · 평문과 같음). `check` 가 낸 두 줄은 **운영의 실제 상태**이고 결함이 아니다:
- 예시에는 있고 암호문에 없는 셋 — `TOTP_ENCRYPTION_KEY`(없으면 `TotpSecretCipher` 가 JWT 비밀에서 파생한 키를 쓰고 경고를 찍는다, docs/70 — **JWT 비밀을 교체하기 전에** 전용 키를 넣고 한 번 배포해야 한다) · `JWT_SIGNING_KEYS` · `JWT_ACTIVE_KID`(없으면 legacy = `JWT_SECRET_KEY` 한 키, ops/46 을 아직 돌리지 않았다). 클러스터 Secret 도 32개라 파일과 어긋난 것은 없다.
- 암호문에만 있는 넷 — `OAUTH2_{GOOGLE,GITHUB}_CLIENT_{ID,SECRET}`. 예시에서 선택 제공자는 주석이라 비교에 잡히지 않는다.
⚠️ 운영 노드에서 `ops/62` 를 돌릴 때는 **스크립트를 저장소의 `ops/` 안에 두고** 돌린다(`$0/..` 로 루트를 잡으므로 `/tmp` 사본은 평문 · 예시 · 암호문 자리를 모두 `/` 에서 찾는다 — `SOPS_PLAIN` · `SOPS_ENC` 는 바꿀 수 있지만 예시 경로는 못 바꾼다). 끝나면 꺼낸 파일을 지워 체크아웃을 배포 버전 그대로 둔다(docs/85 §9-2 ⓠ).
🐛 그때 디스크에 평문이 셋 있었다 — nano 가 남긴 `20-secret.yaml.save`(2026-08-01) · `.save.1`(2026-10-24, 스토어 비밀번호 교체 직전)로 지문이 서로 달랐다. `.gitignore` 의 `k8s/20-secret.yaml*` 이 커밋은 막지만, "어느 것이 최신인지 모른다" 는 이 절이 없애려던 문제 그 자체다 — `shred -u` 로 지웠다(암호문의 커밋 이력이 그 역할을 한다).

**남은 것** → 🕰️ 2026-11-20 아래 §24-1(어긋남 경고 · 교체 점검표 · 보관 키 복구 훈련). ~~아직 남은 것: 전용 `TOTP_ENCRYPTION_KEY` 넣기~~ — 끝(2026-11-20, §24-2 끝).

### 24-1. 어긋남 경고 · 교체 점검표 · 보관 키 복구 훈련(2026-11-20)

- `./ops/62-secrets-sops.sh drift` — 암호문 ↔ 클러스터의 `ecommerce/ecommerce-secrets`. 값 대신 정규화한 지문(stringData 는 값 그대로, `data` · 클러스터는 base64 를 푼 바이트의 sha256 앞 12자)으로 **키 이름만** 말한다: 한쪽에만 있는 키 · 값이 다른 키. 같으면 0, 다르면 1.
  **`ops/28` 이 배포 전에 부른다**(`check_secret_drift`) — 다르면 알리고 배포는 계속한다. sops · 이 기계의 키 · 암호문이 없으면 건너뛴다(`SKIP_SECRET_DRIFT=1` 로도).
- `./ops/62-secrets-sops.sh drill <보관 키 파일>` — **이 기계의 키를 쓰지 않고** 그 키 하나로 풀리는가 · `.sops.yaml` 의 받는 사람에 있는가 · 평문 · 클러스터와 같은가. 분기마다 오프라인 키를 꺼내 한 번 돌리고 날짜를 아래 표에 적는다(키는 다시 오프라인으로).
- `./ops/62-secrets-sops.sh rotation` — 키마다 **값이 마지막으로 바뀐 날**, 오래된 순 · `ROTATION_DAYS`(기본 90) 넘으면 ⏰. 🐛 처음에는 암호문의 그 키 줄이 바뀐 커밋으로 봤는데, `encrypt` 는 값을 **모두 새로 암호화**(새 IV)해 줄이 매번 바뀐다 — 그 날짜는 "마지막 encrypt" 일 뿐이다. 이력의 판마다 이 기계의 키로 풀어 지문이 마지막으로 바뀐 판을 찾는다(풀리지 않는 옛 판에서 멈춘다, 값 · 평문 파일은 남기지 않는다).

**🐛 첫 `drift` 가 찾아낸 두 가지(2026-11-20 운영, `ops/28` 배포 전 경고)** — 암호문 32 · 클러스터 34:

- `SPRING_DATA_REDIS_PASSWORD` — **클러스터가 맞다.** 이 키는 예시 템플릿에서 따라온 **주석 줄**(`# SPRING_DATA_REDIS_PASSWORD: "REPLACE_ME…"`)로만 있었고, 값은 `ops/36-rotate-redis-password.sh` 가 클러스터에 직접 쓴다 — 첫 `encrypt` 가 평문 파일을 그대로 암호화했으니 **주석은 키가 아니라서** 빠졌다. ⚠️ 이때 `./ops/62 apply` 를 돌리면 안 된다 — `sops --decrypt | kubectl apply -f -` 라서, last-applied 주석에 따라 그 키를 **지울 수 있다**(Redis 를 쓰는 전부 — 유입 제한 · 장바구니 캐시 · chat 팬아웃 · 필터 메뉴 선점 — 이 인증 실패로 넘어간다). 클러스터 값을 암호문에 **넣는** 방향으로 고친다.
- `REACT_APP_PAYPAL_CLIENT_ID` — **지울 것.** 빌드 시점 값이라 매니페스트에서 읽는 곳이 없고(B-51 — REACT_APP_* 는 k8s 에 두지 않는다), PayPal CLIENT_ID 는 공개 값이다. `kubectl -n ecommerce patch secret ecommerce-secrets --type=json -p '[{"op":"remove","path":"/data/REACT_APP_PAYPAL_CLIENT_ID"}]'`(2026-11-20 적용).

평문에 키를 **더할 때**는 파일 끝에 붙이지 않는다 — `stringData` 블록 뒤에 긴 주석과 `sops:` 메타데이터가 있어 EOF 추가는 YAML 을 깨뜨린다(2026-11-20: 그래서 `encrypt` 가 `Error unmarshalling file: yaml: line 8` 로 거부했다 — **암호문은 그대로였고 피해는 없었다**). `stringData` 의 마지막 키 줄(4칸 들여쓰기) 뒤에 같은 들여쓰기로 끼우고, `encrypt` 전에 `yaml.safe_load` 로 읽히는지 · 값이 그대로인지 본다. 값은 `kubectl get secret … -o jsonpath` → 파일로 받아 쓰고 화면에 띄우지 않는다(§5).

~~**남은 것**: `SPRING_DATA_REDIS_PASSWORD` 를 암호문에 넣어 `drift` 를 0 으로.~~ — 끝(2026-11-20, §24-2 끝 — `from-cluster` 로 다시 만든 암호문에 들어갔다).

**분기 교체 점검표**(`rotation` 의 ⏰ 부터):

| 키 | 바꾸는 법 | 다시 읽는 곳 |
|---|---|---|
| `LOCAL_DB_PASSWORD` | DB 에서 `ALTER ROLE product PASSWORD …` → `decrypt` → 고치기 → `encrypt` → `apply` | DB 를 쓰는 백엔드 모두 재시작 — 바꾸는 동안 옛 연결은 그대로, 새 연결만 새 값 |
| `LOCAL_KEY_STORE_PASSWORD` · `LOCAL_KEY_PASSWORD` | `ops/59-rotate-store-password.sh` check → apply → 하루 뒤 cleanup(스토어 · Secret · 평문을 함께) | ops/59 가 하나씩 재시작 |
| `JWT_SIGNING_KEYS` · `JWT_ACTIVE_KID` | `ops/46-rotate-jwt-key.sh` add → activate → 토큰 수명 뒤 retire(docs/68 §17) | user · gateway · chat |
| `JWT_SECRET_KEY` · `TOTP_ENCRYPTION_KEY` · `SMS_CODE_PEPPER` | **정기 교체하지 않는다** — 2FA 대기 토큰 · 복구 코드(`auth.recovery-code.pepper` 가 없으면) · TOTP 비밀 · 인증번호의 뿌리라 바꾸면 그것들이 무효. 샜을 때만, 사용자 재등록 안내와 함께 | user · notification |
| `KAFKA_SSL_*` | 브로커 스토어와 함께 | Kafka 를 쓰는 서비스 |
| 바깥 API 키(`AMAZON_*` · `PORTONE_*` · `STRIPE_*` · `PAYPAL_*` · `MAIL_PASSWORD` · `DELIVERY_*` · `SPRING_DATA_MONGODB_PASSWORD` · ES) | 제공자 콘솔에서 새 키 → `encrypt` → `apply` → 그 서비스 재시작 → 확인 뒤 옛 키 폐기 | 그 키를 읽는 서비스 |

Redis 비밀번호(`SPRING_DATA_REDIS_PASSWORD`)는 이 Secret 에 **있다**(🕰️ 2026-11-20 정정 — 처음에는 없다고 적었다): `ops/36-rotate-redis-password.sh` 가 이 키와 `redis/redis-auth` 를 함께 바꾼다 — 바꾼 뒤 `from-cluster` → `encrypt` 로 암호문에도. 교체 · 훈련 기록:

| 날짜 | 한 것 | 결과 |
|---|---|---|
| 2026-11-20 | 낡은 암호문 적용 사고 복구(§24-2) → `from-cluster` 로 암호문 다시 만듦 + `TOTP_ENCRYPTION_KEY` 새로 | `drift` 키 35 · 35 같음, 2FA 비밀 0행(다시 잠글 것 없음), 커밋 `539a2a2c` |
| 2026-11-21 | 첫 `rotation`(작업 PC 의 키, 암호문 판 4개) | 키 34개 모두 ✅ — 2026-11-19 · 20 (0 ~ 1일), ⏰ 0. ⚠️ 날짜는 암호문 이력이 시작된 2026-11-19 부터만 센다 — 그 전에 언제 바뀌었는지는 모른다(교체했다는 뜻이 아니다). 첫 ⏰ 는 2027-02-17 무렵 |
| 2026-11-21 | 첫 `drill` — 보관 키는 오프라인 보관 PC에 있어 **그 PC 에서**(키를 운영 노드로 옮기지 않는다 · 이 PC 의 키를 쓰지 않음 `SOPS_AGE_KEY_FILE=/nonexistent` · 실습 Secret 과 비교하지 않게 `KUBECONFIG=/dev/null`) + 운영 노드에서 `drift` | 받는 사람에 있다 · 그 키 하나로 풀린다(키 34개) ✅. 운영 노드: 체크아웃의 암호문 = 원격 끝, `drift` 34 · 34 같다 — 보관 키로 푼 값 = 운영 Secret. 다음 훈련 2027-02 무렵(분기) |

**확인**: `SopsSecretsScriptTest` +3(실제 sops · age — `drift` 는 다른 키 이름만 · 값은 출력하지 않음 · 종료 1, `drill` 은 보관 키 하나로 통과하고 받는 사람이 아닌 키는 실패, `rotation` 은 다시 암호화만 한 판을 교체로 세지 않는다) · 가드 `SopsSecretGuardTest` +1(ops/28 이 `drift` 를 한 번 부른다). 변이: 지문 비교를 빼면 · `drill` 이 이 기계의 키로 풀면 · `rotation` 이 판 0 의 날짜만 보면 각각 실패.

### 24-2. 낡은 암호문을 적용한 사고 · apply 가 먼저 비교한다(2026-11-20)

**무슨 일**: 운영 노드에서 TOTP 전용 키(docs/70)를 넣으려다 `encrypt` 가 "평문이 없다" 로 멈췄고, 그 전의 암호문(11-19 첫 `encrypt` — 그때의 평문이 이미 낡아 있었다)을 `sops -d … | kubectl apply -f -` 로 적용했다. 결과: **키 22개가 옛 값으로 덮였고**(10-25 에 바꾼 스토어 비밀번호 · JWT · DB · 메일 · 결제 …) 암호문에 없던 **다섯 개는 지워졌다**(`SPRING_DATA_REDIS_PASSWORD` · OAuth GitHub · Google 넷 — `kubectl apply` 는 지난 적용에 있던 키가 새 파일에 없으면 지운다). 빈 값 셋(`JWT_SIGNING_KEYS` · `JWT_ACTIVE_KID` · `TOTP_ENCRYPTION_KEY`)은 새로 생겼다. 재시작한 user-service 새 파드만 `keystore password was incorrect` 로 CrashLoop — Reloader 는 `tls-id-*` 만 보므로 다른 서비스는 재시작되지 않았고, 옛 파드가 버텨 손님 영향은 없었다.

**되살린 방법**(값을 출력하지 않고): 살아 있는 옛 파드의 환경 변수가 적용 전 Secret 그대로다. ① 옛 파드 env 이름 ↔ Secret · ConfigMap 이름(`env -0 | cut -z -d= -f1` — 여러 줄 값의 조각이 이름 목록에 새지 않게) ② Secret 키마다 옛 파드 값과 sha256 비교 → 다른 키 · 지워진 키를 옛 파드의 값으로 `kubectl patch --type merge`(base64 를 파이프로) ③ 다시 비교해 0 · 새로 생긴 빈 키는 `null` 로 삭제 ④ `kubectl.kubernetes.io/last-applied-configuration` 주석 삭제(낡은 값이 그 안에 있고, 다음 apply 가 키를 지우는 근거다).

**고친 것**(`ops/62`):
- `apply` 는 먼저 클러스터와 지문으로 비교한다 — **클러스터에만 있는 키가 있으면 거절**(지워질 키 이름만), 값이 바뀌거나 새로 생기는 키는 **`EXPECT_CHANGED="K1 K2"` 로 이름을 대야** 적용한다(바꾸려던 키만 바뀌는가). 같으면 아무것도 하지 않는다. 클러스터에 닿지 못하면 비교 없이 적용하지 않는다(NotFound 일 때만 새로 만든다).
- `from-cluster` — 클러스터 Secret 에서 평문(0600)을 만든다. 암호문이 운영보다 낡았을 때 운영 값에서 다시 시작한다: `from-cluster` → 바꿀 키만 고치기 → `encrypt` → `drift`(바꾼 키만 다르다) → `EXPECT_CHANGED=… apply` → 커밋.
- `encrypt` 가 실패해도 임시 파일(`20-secret.enc.yaml.XXXXXX`)을 남기지 않는다 — RETURN 트랩은 `exit` 에서 돌지 않았다(운영 노드에 하나 남아 있었다). 이제 EXIT 트랩.

**확인**: `SopsSecretsScriptTest` +4(실제 sops · age — 클러스터에만 있는 키 거절 · 이름 없는 변경 거절 · `EXPECT_CHANGED` 면 적용 · 같으면 아무것도 안 함, `from-cluster` → `encrypt` → `drift` 같음 · 있는 평문은 덮지 않음 · 따옴표 · 콜론 · 한글 값, `encrypt` 실패에 임시 파일 없음). 변이: 거절 검사 · 이름 검사 · 정리 등록을 각각 빼면 실패.

**마무리(2026-11-20)**: 운영 노드에서 새 `ops/62` 로 `from-cluster`(키 34) → `TOTP_ENCRYPTION_KEY` 한 줄(`openssl rand -base64 32` — 처음 넣는 키라 새로 만들었다, 그 전 커밋 `4be5da5c` 의 값은 적용된 적 없다) → `encrypt` → `drift`(암호문에만 TOTP) → `EXPECT_CHANGED=TOTP_ENCRYPTION_KEY apply` → user-service 재시작. 확인: Secret 의 TOTP 길이 44 · 파드 로그에 'TOTP_ENCRYPTION_KEY 가 없다' 없음 · `users.users.two_factor_secret` 0행(TOTP 를 켠 사용자가 아직 없다 — 앞으로는 `enc:k1:`) · `drift` 35 · 35 같음 · 암호문 커밋 `539a2a2c`. 사고 중 만든 사본(잘못 적용된 Secret yaml · 낡은 암호문 둘)은 지웠다.

## 25. 관리 포트 분리 — actuator 는 9180, 지표 에이전트만(2026-11-20, 가드 B-119)

**문제**: actuator(health 상세 · metrics · prometheus)가 앱 포트에 있었고, 들어오는 정책(§11, k8s/95)은 같은 네임스페이스에 **모든 포트**를 열었다. B-112 로 노출 목록을 넷으로 줄였지만, 같은 네임스페이스의 파드 하나만 뚫려도 모든 서비스의 지표 · health 상세를 인증 없이 읽었다. egress 1단계(§23)도 같은 네임스페이스는 연다 — 그 길은 받는 쪽에서 닫아야 한다.

**한 것**:
- 자바 Deployment 열셋(k8s/30 ~ 49)에 `MANAGEMENT_SERVER_PORT=9180` · 컨테이너 포트 `management`(9180) · actuator HTTP 프로브는 `port: management`. **설정 파일(yml)에는 넣지 않았다** — 개발 PC 는 서비스를 한 기계에서 함께 띄우므로 관리 포트가 겹친다. 그래서 바뀌는 것은 k8s 뿐이다.
- 관리 포트는 **앱 포트의 TLS 를 물려받는다**(`server.ssl` — 프로브는 그대로 HTTPS) · Spring Security 의 규칙도 물려받는다(config-server 의 비밀번호, user-service 의 `EndpointRequest`). Service 는 관리 포트를 내지 않는다 — Ingress · 터널 · 다른 파드가 Service 이름으로 닿을 길이 없다.
- k8s/95: `allow-same-namespace` 는 **앱 포트 목록만**(매니페스트의 containerPort 와 같다 — 포트를 더하면 여기도, 가드가 맞춘다) · 새 `allow-management-from-metrics-agent`(9180 은 `app=metrics-agent` · 두드리기 파드만) · traefik → 앱 포트(8000 · 8080)만. kubelet 프로브 · `kubectl port-forward` 는 노드에서 오므로 정책과 무관하다(kube-router).
- 지표 에이전트(external · demo)는 포트 이름 `management` 만 긁는다(예전에는 포트마다 긁고 gRPC 9095 만 뺐다).
- 배포 스모크: `ops/28` 이 게이트웨이 health 를 `port-forward deploy/gateway-server 19180:9180` 로 본다(`GATEWAY_HEALTH` → ops/06). 관리 포트가 없는 옛 버전(자동 복귀 뒤)이면 예전처럼 앱 포트를 본다. ⚠️ 이것을 고치지 않았다면 스모크의 "게이트웨이 health" 가 404 로 실패해 **모든 배포가 자동 복귀**했을 것이다.
- `ops/35 check · apply` 가 관리 포트를 두드린다(파드 IP:9180 — 지표 에이전트 라벨은 닿고, 같은 네임스페이스의 다른 파드는 막힌다 · 에이전트가 막히면 되돌린다).

**배포 순서**(이미지 변경 없음 — 매니페스트만, 다음 배포에 함께):
1. `ops/28` 로 배포 — 파드가 관리 포트로 다시 뜬다(프로브가 그 포트).
2. **곧바로** 지표 에이전트를 새 규칙으로: `kubectl apply -f k8s-overlays/external/metrics-agent.yaml && kubectl -n ecommerce rollout restart deploy/metrics-agent`. 그 사이 서비스 지표가 끊긴다(옛 규칙은 앱 포트를 긁어 404 — `ServiceDown`). `ops/28` 이 옛 규칙이면 알린다(`warn_metrics_agent`).
3. 들어오는 정책을 쓰는 클러스터면 `./ops/35-network-policy.sh apply`(관리 포트 줄이 새로 나온다).
4. 확인: `curl -sk https://localhost:9180/actuator/health` 를 파드 안에서 · `up{job="spring-actuator"}` 가 11개 · 앱 포트의 `/actuator/health` 는 404.

**확인**: gateway · config-server `ManagementPortTest`(시험 인증서를 그 자리에서 만들어 **HTTPS 로** — 앱 포트 /actuator 404, 관리 포트는 TLS 로 health · prometheus, config-server 는 관리 포트에서도 비밀번호 없이 401 · 맞으면 404(노출 밖) — 보안 규칙이 걸린다는 증거) · user-service `ManagementPortTest`(컨테이너 — `EndpointRequest` 규칙이 관리 포트에도, 업무 API 는 401) · 가드 **B-119** `ManagementPortGuardTest` 5 · `NetworkPolicyScriptTest` 3. 변이: 프로브 하나를 앱 포트로 · 같은 네임스페이스 허용에서 포트 목록을 빼면 · 에이전트의 keep 규칙을 drop 으로 · 스모크를 앱 포트로 · 시험에서 관리 포트 설정을 빼면 · ops/35 가 에이전트 막힘을 실패로 보지 않으면 각각 실패.

> 🕰️ 2026-11-20 운영 배포(`d8922fc53e0c`) 뒤 **서비스 지표가 21분 끊겼다** — ConfigMap 은 이미 새 규칙이라 `ops/28` 이 경고하지 않았는데, 에이전트 **파드**는 옛 규칙을 쥐고 있었다(기동할 때 initContainer 가 ConfigMap 을 설정 파일로 만들고 그 뒤로는 다시 읽지 않는다). 이제 `ops/28` 은 배포 끝에 **파드가 뜬 시각 < ConfigMap 의 마지막 변경(managedFields 의 가장 늦은 시각)** 이면 에이전트를 재시작한다(`METRICS_AGENT_RESTART=0` 이면 알리기만 · 배포는 막지 않는다 · ConfigMap 이 옛 규칙이면 예전처럼 다시 적용하라고 알린다). 시험 `MetricsAgentStaleScriptTest` 5(변이: 시각 비교를 빼면 2 실패).

## 26. 나가는 연결(egress) 2단계 — 포트 좁히기 · 정책 밖이던 파드(2026-11-20)

1단계(§23)의 남은 것을 했다. 적용은 그대로 `./ops/35-network-policy.sh egress-apply` 로만(k8s/97 전체 — 1 · 2단계를 한 번에).

| 정책 | 고르는 파드 | 여는 것 |
|---|---|---|
| `egress-public-no-private` | 바깥이 필요한 일곱 | 1단계 그대로 + 인터넷은 **TCP 443 만**(예전에는 모든 포트) |
| `egress-smtp` | notification | 메일 587 · 465(사설 · 메타데이터 밖) |
| `egress-mongo-atlas` | chat · mongo-backup | Atlas 27017 · DNS · 클러스터 안 mongodb |
| `egress-metrics-agent` | metrics-agent | 같은 네임스페이스의 **9180 만**(관리 포트, §25) · 노드 IP:6443(API 서버 — kubernetes Service 는 DNAT 뒤 노드의 6443) · remote write TCP 9090(운영은 노드 IP, 데모는 관측 VM 의 사설 IP — 그래서 대역 대신 포트) · DNS |
| `egress-cloudflared` | cloudflared | Cloudflare TCP · UDP 7844 · TCP 443(사설 밖) · gateway(8000) · frontend(8080) · kube-system 의 traefik · DNS |
| `egress-backup-postgres` | postgres-backup · restore-drill | postgres 네임스페이스 5432 · DNS — IP 대역은 하나도 열지 않는다 |

- 백업 CronJob 파드에는 고를 라벨이 없었다 — `app: postgres-backup · mongo-backup · restore-drill` 를 더했다(k8s/85 — 적용은 늘 `ACTION=cronjobs ./ops/29`).
- 사전 점검이 2단계의 전제를 본다(값은 출력하지 않는다): 메일 포트(Secret `MAIL_PORT`)가 465 · 587 · 설정 저장소(`CONFIG_GIT_URI`)가 https(ssh 면 443 밖) · 백업 `PGHOST` 가 클러스터 안. 하나라도 아니면 적용하지 않는다.
- 두드리기 그룹이 일곱(internal · public · mail · mongo · metrics · cloudflared · backup) — public 은 `1.1.1.1:80` 이 막혀야 하고, 지표 에이전트는 앱 포트 · DB 로, cloudflared 는 DB · 앱 서비스로, 백업은 Redis · gateway · 인터넷으로 못 가야 한다. 되돌리기(`egress-remove` · 자동)는 일곱 정책을 모두 지운다.

**아직 못 본 것**: kube-router 실제 집행(1단계와 같다 — 이 환경의 docker 안 k3s 는 파드를 못 띄운다). 특히 ① 지표 에이전트의 API 서버 접속(정책이 DNAT 뒤 노드:6443 을 본다는 가정) ② cloudflared 의 QUIC(UDP 7844) ③ traefik 파드 라벨 `app.kubernetes.io/name: traefik`(k3s 기본 차트). 실습 클러스터에서 `egress-apply` → 지표 11개 up · 사이트 · 결제 · 메일 · 채팅 · 백업 한 번(`kubectl create job --from=cronjob/postgres-backup …`) → 운영.

**확인**: `EgressPolicyScriptTest` 9(+2 — public 의 443 밖이 열리면 성공이 아니다, 메일 포트 · ssh 저장소 · 바깥 PGHOST 면 적용하지 않고 값을 말하지 않는다) · 가드 B-117 `EgressPolicyGuardTest` 6(+2 — 인터넷 규칙은 모두 포트가 있다 · public 443 · 메일 · Atlas · 터널 · 에이전트 포트와 고르는 파드, 백업은 IP 대역 없음, 정책의 이름이 CronJob · 오버레이의 실제 라벨과 같다, ops/35 가 지우는 정책 = k8s/97). 변이: public 의 포트를 빼면 · CronJob 라벨을 바꾸면 · 지울 목록에서 하나를 빼면 · public 의 80 확인을 빼면 · 메일 포트 점검을 열면 각각 실패.

🕰️ **2026-11-20 첫 실습(server-node · agent 셋 · data, 1단계는 31시간 전부터 걸려 있었다)**: `egress-apply` 가 사전 점검에서 멈췄다 — 데모 Secret 의 메일은 `MAIL_HOST=localhost` · `MAIL_PORT=25`(보내지 않는 자리)인데 포트만 보고 거절했다. 파드 안 루프백은 NetworkPolicy 가 고르지 않으므로 이제 호스트가 localhost · 127.x · ::1 이면 '쓰지 않는다' 로 본다. 같은 `egress-check` 에서 metrics 그룹의 `첫 노드:6443` · `첫 노드:9090` 이 정책 전부터 닫혀 판정에서 빠졌다 — 여러 노드면 API 서버는 한 노드에만 있고 remote write 는 관측 VM 이다. 이제 metrics 그룹은 `kubernetes` 엔드포인트(실제 API 서버)와 `metrics-agent-config` 의 remote write url(`__HOST_IP__` 면 노드)을 두드린다. 실습 노드에는 OTLP 수집기(노드:4318)가 없어 그 줄은 늘 '정책 전에도 닫힘' 이다(실습 추적의 구성 문제 — 정책과 무관). 실습에는 백업 CronJob 이 없다 — 백업 정책은 두드리기 파드로만 보고 실제 Job 은 운영에서. 시험 `EgressPolicyScriptTest` +2(localhost 메일 · 바깥 호스트 25 거절, 지표 그룹의 실제 대상 — 변이: 두 수정을 각각 되돌리면 실패). 적용 결과(같은 날): rc=0 — 2단계 정책 다섯 생성 · public 의 80 막힘 · 메일 · Mongo · 지표 · cloudflared · 백업 그룹의 제 일 밖 모두 막힘, **지표 에이전트 → API 서버(`server-node:6443`, DNAT 뒤) · remote write(obs-vm) · 관리 포트 열림**, **cloudflared → traefik 열림**(라벨 확인) — 못 본 것 셋 가운데 둘이 풀렸다(QUIC 는 실습에 cloudflared 가 없어 운영에서). 함께 드러난 것(정책과 무관 — 정책을 다 빼도 같았다): ① 지표 에이전트 파드가 11-19 에 떠서 13:15 의 새 규칙을 모른 채 대상 42개(앱 포트까지)를 긁었다 — 재시작으로 21개 모두 up(지금 `ops/28` 은 이것을 잡는다) ② **채팅 SockJS 가 모두 403** — chat 만 `frontend.url` 이 `https://localhost:3000` 고정이라 `shop.lab` 출처를 거절했다, order · user 처럼 `APP_PUBLIC_URL` 을 따르게 고쳤다(chat 이미지, `WebSocketAllowedOriginTest` +2) ③ 실습의 추적 대상(`otel-collector` → obs-vm 사설 IP)은 노드 IP:4318 만 여는 정책 밖이라 막힌다 — 운영은 노드 위 수집기라 무관, 실습은 추적이 필요할 때 정책을 뺀다. ④ 출처를 고친 뒤에도 채팅이 끊겼다 — 상담 구독 · 끊김 처리가 `StackOverflowError`(스택은 `DefaultedRedisConnection.pExpire` 만): chat 의 Redisson 3.23.5 는 Spring Data Redis 3.1 용 모듈이라 Boot 3.5 의 조건 있는 `pExpire` 가 연결 자신을 다시 불렀다. `Error` 라 presence 의 catch 도 지나 STOMP 구독이 'Failed to send message to clientInboundChannel' 로 죽었다 — 3.50.0(`redisson-spring-data-35`)으로(시험 `RedissonExpireRecursionTest`, 옛 판에서 실패; `ChatScaleOutRedisTest` · `ChatStompIntegrationTest` 컨테이너 통과). cart · order 도 3.23.5 — `expire` 를 부르지 않아 지금은 드러나지 않았다 → 2026-11-21 같은 3.50.0 으로 맞췄다(cart 는 `RedisTemplate` · 캐시가 Redisson 연결 위, order 는 Lettuce 지만 starter 가 Redisson 빈을 만든다; 같은 이름의 시험 둘, 옛 판에서 실패 · 컨테이너 시험 통과, 다음 배포에 함께). ⑤ 그 뒤 WebSocket 만 403 — chat 직접은 101, 게이트웨이를 거치면 403, Origin 을 빼면 101: 게이트웨이 CORS 허용 출처가 `https://localhost:3000` 하나였다 → `${APP_PUBLIC_URL}` 을 더했다(gateway `CorsAllowedOriginTest`). 같은 날 결제 화면: Stripe 테스트 결제 → 배송 생성 → 발송까지 지나 order → `api.stripe.com:443` 확인. 위쪽 진행 막대의 네 번째 칸 이름은 `配送中` 이던 것을 주문 상태 글자(`発送済み` · `발송 완료`)로 — 아래 배송 추적의 '이동 중' 과 엇갈려 보였다(`OrderProgress.test.jsx`). 🕰️ 2026-11-21: 세 수정(`429a3f35` · `f1c205eb` · `c613ba2e`)을 담은 새 이미지를 실습에 배포 — **채팅 · 상담 동작, 진행 막대 `発送済み` 확인**(egress 정책을 건 채로). 운영은 다음 배포에 함께.

🕰️ **2026-11-21 운영 첫 적용 — 스스로 되돌림(rc=1)**: 사전 점검 · 백업 라벨(`ACTION=cronjobs ./ops/29` — 라벨은 CronJob 이 아니라 파드 템플릿에 붙는다) 통과, 두드리기도 메일 587 · Stripe 443 · 지표 에이전트 → 노드 6443 · 9090 · 관리 포트 · cloudflared → traefik · 백업 → postgres 모두 열림 · 막아야 할 것 모두 막힘 — **노드 수집기 `노드IP:4318` 만 막혔다**(셋 그룹 모두). 원인: 운영 노드의 관측 스택은 docker compose 라 공개 포트가 DNAT 로 **컨테이너 IP(docker 브리지)** 로 바뀐다 — 정책은 바뀐 목적지를 보아 `노드IP/32` 규칙에 맞지 않았다(같은 compose 의 Prometheus 9090 은 지표 정책이 포트로만 열어 통과, k3s API 6443 은 호스트가 직접 받아 DNAT 없음). 실습 노드에는 수집기가 없어 드러나지 않았다. 고침: 4318 규칙에 docker 브리지 대역 `172.16.0.0/12` 를 더했다(그 규칙의 포트는 4318 하나 — 가드 `EgressPolicyGuardTest` 가 본다, 되돌리면 실패). 운영 Atlas 27017 은 두드리기 대상이 아니라(운영 mongodb 0/0 — ℹ️) 적용 뒤 채팅으로 본다. → 같은 날 두 번째 적용 **rc=0**(DNAT 목적지 `172.18.0.8:4318` 확인, 세 그룹 모두 4318 열림). 적용 뒤 운영 1:1 상담의 입력 버튼이 회색 — **정책을 빼도 같았다**: 브라우저 `…/chat-service/chat/…/xhr_streaming 403`, chat 로그 0줄 = 운영 이미지(`d8922fc53e0c`)의 SockJS 출처 고정(`429a3f35` 에서 고침)으로 egress 전부터 상담이 막혀 있었다. 다음 배포(chat · gateway · frontend)로 고쳐진다.

## 27. 서버 인증서를 서비스마다 — 공유 `tls-keystore` 대신 신원으로(2026-11-20, 가드 B-121)

**문제**: 자바 서비스 열셋이 모두 **같은 서버 키**(ops/07 의 `keystore.p12`, Secret `tls-keystore`)로 HTTPS 를 열었다. 그 인증서의 SAN 에는 `*.ecommerce.svc.cluster.local` 와 모든 서비스 이름이 있다 — 파드 하나에서 그 키가 새면 **어느 서비스로도 행세**할 수 있었다(게이트웨이 → 백엔드 · Feign · Eureka 등록이 서버를 확인할 때 모두 통과한다). 유효 825일 · 손으로 바꾸고 모든 파드를 재시작해야 했다(§18 의 남은 것). 서비스별 신원(§16 · §18 — 90일, cert-manager 가 만료 30일 전 새로 발급, Reloader 가 재시작)에는 이미 `server auth` 와 그 서비스의 이름 · FQDN 이 있었다 — gRPC 서버(catalog 9095)는 원래 그것을 썼다.

**한 것**:
- 자바 Deployment 열셋(k8s/30 ~ 49)에 `SERVER_SSL_KEY_STORE=/app/identity/keystore.p12` · 형식 PKCS12 · 비밀번호 둘 다 `$(LOCAL_KEY_STORE_PASSWORD)`(cert-manager 의 PKCS12 는 키 비밀번호 = 스토어 비밀번호). 환경 변수가 yml 의 `server.ssl.key-store: ${LOCAL_KEY_STORE_PATH}` 보다 앞선다(Spring 의 속성 순서) — **`LOCAL_KEY_STORE_PATH` 는 그대로**: Postgres(`sslrootcert`) · Redis 설정이 같은 변수를 읽는다. 관리 포트(§25)는 서버 TLS 를 물려받으므로 함께 바뀐다.
- eureka-server 에도 신원 `id-eureka-server`(k8s/96 · ops/38 · ops/39) — 마운트 · Reloader 주석 · `EUREKA_CLIENT_TLS_ENABLED=true`(신원을 마운트한 파드의 규칙 B-86 — 자기 피어 복제도 그 인증서를 낸다). config-server 는 이미 `SERVER_SSL_*` 넷이 있어(B-91) 경로만 신원으로.
- 신원마다 이름 하나 더: `metrics.ecommerce.svc.cluster.local` — 지표 에이전트(k8s-overlays/external)가 파드 IP 로 긁으며 **모든 파드에서 이 이름으로** 서버를 확인한다(Prometheus 는 대상마다 `server_name` 을 바꿀 수 없다). 예전에는 공유 인증서의 와일드카드가 덮었다. 이 이름으로 접속하는 것은 에이전트뿐이고 에이전트는 "내부 CA 가 낸 인증서인가" 만 본다 — 지금과 같은 보증.
- `ops/38`(손 발급): eureka-server 추가 · SAN 에 그 이름 · **기한이 남아도 그 이름이 없는 옛 신원은 다시 만든다** · `check` 가 알린다. `ops/39`(cert-manager): 목록에 eureka-server — k8s/96 의 `dnsNames` 가 바뀌어 `apply` 하면 cert-manager 가 열셋을 다시 발급하고 Reloader 가 하나씩 재시작한다.
- `ops/28` 배포 전 점검: 마운트하는 신원 Secret 마다 지표 이름이 없으면 **멈춘다**(없이 배포하면 서비스 지표가 모두 끊긴다 — `ServiceDown`).
- 공유 `tls-keystore` 는 남는다 — 사설 CA(`ca.crt` · 트러스트스토어), Kafka 클라이언트 키스토어, Postgres · Redis 클라이언트 설정이 읽는다. 서버 키로는 이제 아무도 쓰지 않는다(운영 클러스터 밖 — 개발 PC 의 `LOCAL_KEY_STORE_PATH` 는 그대로 그 키).

**바뀌지 않는 것**: 서버를 확인하는 쪽이 쓰는 이름 — 게이트웨이(Eureka 의 `EUREKA_INSTANCE_HOSTNAME` = FQDN) · Feign(`FEIGN_*_URL` = FQDN) · Eureka 등록 · config 클라이언트(FQDN) · 데모 Ingress(`serverName` = 게이트웨이 FQDN) — 모두 신원의 `dnsNames` 에 있다. cloudflared 는 `noTLSVerify`, kubelet 프로브는 확인하지 않는다. CA 가 같으므로 트러스트스토어도 그대로다.

**배포 순서**(이미지 변경 없음 — 매니페스트만, 다음 배포에 함께):
1. 신원을 새 이름으로: cert-manager 면 `./ops/39-cert-manager.sh apply`(열셋 다시 발급 · Reloader 가 재시작 — 아직 옛 매니페스트라 동작은 같다), 손 발급이면 `./ops/38-issue-service-identities.sh`(eureka-server 를 새로 · 이름 없는 신원을 다시) → 서비스 재시작은 2 가 한다.
2. `ops/28` 로 배포 — 위 점검이 이름 · eureka 신원을 확인한 뒤 적용, 파드가 신원으로 HTTPS 를 다시 연다.
3. 확인: `./ops/37-check-mtls.sh`(내부 호출 200 · 403 그대로) · `up{job="spring-actuator"}` 11 · 아무 파드에서 `openssl s_client -connect order-service.ecommerce.svc.cluster.local:8084 -servername order-service.ecommerce.svc.cluster.local </dev/null 2>/dev/null | openssl x509 -noout -subject -enddate` 가 `CN=order-service` · 90일 안.
4. 되돌리기: 이전 버전으로 `ops/28 --rollback`(옛 매니페스트 = 공유 키) — 신원의 이름이 늘어난 것은 옛 매니페스트에도 해가 없다.

**확인**: 가드 **B-121** `ServerCertificateGuardTest` 3(자바 Deployment 마다 서버 키스토어 = 자기 신원 · `LOCAL_KEY_STORE_PATH` 그대로 · 그 Secret 마운트 · Reloader 주석, k8s/96 의 Certificate 마다 server auth · 이름 · FQDN · 지표 이름 · 와일드카드 없음, ops/38 · ops/39 · 자바 Deployment 의 목록이 같고 ops/38 의 SAN · 다시 만들기 · ops/28 의 점검). 변이: 서비스 하나의 서버 키스토어를 공유 키로 · 신원 하나에서 지표 이름을 빼면 · ops/38 이 이름 없는 신원을 기한만 보고 건너뛰면 각각 실패. ops/38 의 openssl 설정으로 만든 인증서가 serverAuth · 세 이름을 갖는 것을 따로 확인했다. **아직 못 본 것**: 실제 클러스터에서 새 키스토어로 기동 · 갱신 뒤 재시작(이 환경의 docker 안 k3s 는 파드를 못 띄운다) — 실습 클러스터에서 1 → 3 순서로.

**남은 것**: 공유 `tls-keystore` 의 Kafka 클라이언트 키(브로커가 확인하는 클라이언트 인증서)도 서비스별로 · 인프라(Kafka · Redis · Postgres) 서버 인증서를 cert-manager 로 — 인프라 재시작이 함께라 따로 계획한다. 개인 키 HSM/KMS 는 운영 장비 · 비용 판단.
