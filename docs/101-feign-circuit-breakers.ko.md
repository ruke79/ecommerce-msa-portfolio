# 101. 서비스 간 Feign 호출 — 서킷 브레이커 정책

> 2026-11-21 · 4단계 ④ 1차 — cart→catalog · order→catalog · order→coupon 에 적용(다음 배포에 함께 — cart · order 이미지).
> 일본어판: [101-feign-circuit-breakers.md](101-feign-circuit-breakers.md)

## 0. 왜

Feign 호출은 모두 같은 상한(연결 3초 · 읽기 5초, `application-common.yml`)이고 재시도 · 서킷 브레이커가 없었다. 상대가 죽거나 느리면 부르는 쪽은 요청마다 최대 5초씩 스레드 · DB 커넥션을 쥔 채 기다리고, 그 사이 들어온 요청까지 밀린다 — 한 서비스의 장애가 부르는 서비스로 번진다.

## 1. 방식 — 같은 스레드, 원래 예외 그대로

Spring Cloud 의 Feign 서킷 브레이커(`spring.cloud.openfeign.circuitbreaker.enabled`)는 쓰지 않는다(그래서 예전에 보류했다 — `application-common.yml` 메모):

| 문제 | 왜 |
|---|---|
| `Authorization` 헤더가 끊긴다 | TimeLimiter 가 다른 스레드에서 부르는데, `FeignConfig` 는 `RequestContextHolder`(스레드 로컬)에서 헤더를 옮긴다 |
| 기본 시간 상한 1초 | Feign 읽기 상한 5초보다 짧다 — 느린 정상 호출이 실패가 된다 |
| 예외를 감싼다 | `NoFallbackAvailableException` — `catch (FeignException.NotFound)`(쿠폰 결제 잡기) · 디코더의 `CustomException` 분기가 깨진다 |

대신 `common` 의 `UpstreamCircuitBreakers.wrap` 이 클라이언트 인터페이스를 **같은 스레드에서** 감싸고 **원래 예외를 그대로** 던진다. 각 서비스의 `config/UpstreamCircuitBreakerConfig` 가 감싼 것을 `@Primary` 로 내고, Feign 원본은 `primary = false` · `qualifiers = "raw…"` 로만 남긴다(둘 다 primary 면 기동 실패). 부르는 쪽 코드는 그대로다 — 열린 동안은 `CallNotPermittedException`(RuntimeException)이 기존의 `catch (RuntimeException)` 길을 **5초 기다리지 않고** 바로 탄다.

- 실패로 세는 것: 연결 실패 · 시간 초과(`RetryableException`), 5xx · 429(`FeignException` · `UpstreamCallException`, 원인 사슬). 4xx 는 상대가 건강하게 거절한 것이라 세지 않는다.
- 설정: 최근 20번 중 10번 이상 · 실패율 50% 이상이면 열림 → 30초 뒤 반열림 3번 시험.
- 지표 `upstream_circuit_state{name}`(0 닫힘 · 1 열림 · 2 반열림), 상태 전이는 WARN 한 줄.

## 2. 1차 적용 — 부르는 쪽의 처리(그대로)

| 브레이커 | 호출 | 부르는 곳 | 실패 · 열림일 때 |
|---|---|---|---|
| `cart-catalog` | `getProductsInfo` · `getCartProductsInfo` · `getColorsInfo` | 담기 · 수량 · 조회 · 체크아웃 · 저장 | 담기 · 수량 · 조회 · 체크아웃 503, 체크아웃의 가격 맞추기 · SKU 채우기는 저장한 값으로 진행, **저장(`savecart`)은 500**(§4) |
| `order-catalog` | `reserveStock` · `releaseStock` · `getColorsInfo` | 주문 생성 · 결제창 갱신 · 입금 취소 · 만료 | 예약은 예약 없이 진행(fail-open — 열리면 바로), 풀기는 경고만(catalog 의 만료가 푼다), 색상은 빈 채로. 거절(400 · 422)은 실패로 세지 않고 400 |
| `order-coupon` | `quote` · `claimForPayment` | 주문 생성 · 결제 시작 | 막는다(400 "쿠폰을 확인하지 못해…"), 옛 coupon 의 404 는 실패로 세지 않고 그대로 통과 |

시험: common `UpstreamCircuitBreakersTest`(4 — 분류 · 열림 뒤 부르지 않음 · 4xx 는 닫힌 채 원래 예외 · 같은 스레드), cart · order 의 컨텍스트 시험(컨테이너 — 주입값이 프록시 · 원본은 qualifier · 지표). 변이: 감싼 빈의 `@Primary` 를 빼면 cart 컨텍스트가 뜨지 않는다.

## 3. 전체 표(34개 메서드, 2026-11-21 조사)

| 호출 | 경로 | 실패 처리 | 1차 |
|---|---|---|---|
| cart→catalog 상품 · 장바구니 정보 · 색상(3) | 사용자 | 503 · 저장한 값 · savecart 500 | ✅ |
| cart→user 데모 계정 | 매일 작업 | 내일 다시 | — |
| coupon→cart 장바구니 | 사용자(견적) | 200 + '확인 못 함'(막음) | 다음 |
| coupon→catalog 상품 정보 · 기획전 쿠폰 | 사용자 | 빈 값(카테고리 제약이 안 맞음) · 409 | 다음 |
| coupon→user 사용자 · 고객군 | 관리자 · 사용자 | 400 · 빈 목록(막음) | 다음 |
| delivery→order 배송지 | Kafka · 관리자 | 재시도 약 3분 → DLT · 200 created:false | 다음(delivery 에 resilience4j 없음) |
| order→cart 장바구니 · 상품 id | 사용자 | **500**(§4) | 다음 |
| order→catalog 예약 · 풀기 · 색상 | 사용자 · 예약 작업 | fail-open · 경고 · 빈 값 | ✅ |
| order→coupon 견적 · 결제 잡기 | 사용자 | 400(막음) · 404 통과 | ✅ |
| order→user 이메일 묶음 | 관리자 | 이메일 없이 | 다음 |
| user→catalog 평점 · 상품 | 사용자 · 대사 작업 | 리뷰는 저장 · 400 | 다음 |
| user→notification 2단계 SMS 보내기 · 확인 · 데모 초기화 | 로그인 · 매일 작업 | 503 · 다음 날 | 다음 |
| user→order 구매 통계 · 구매 확인 | 등급 작업 · 리뷰 | 다음 회차 · 503(막음) | 다음 |

부르는 곳이 없는 메서드 9개(cart `getProductInfo` · `getCartProductInfo` · `getColorInfo`, chat `findUserByEmail`, order `getColorInfo` · `findUserByEmail` · `findUserByName` · `getUserInfoWithAddresses` · `selectShippingAddresses`)는 지울 후보다.

## 4. 조사에서 드러난 것 — 고침(2026-11-21, 다음 배포에 함께)

| # | 무엇 | 고침 | 시험 |
|---|---|---|---|
| 1 | cart `savecart` 의 catalog 실패가 **500** | 다른 장바구니 경로와 같은 `failure()` — 수량 범위는 400, 상류 · 브레이커 열림 · DB 는 503 | `CartSaveFailureStatusTest` |
| 2 | order 주문 생성의 상류(cart …) 실패가 **500** | `isUpstreamFailure` 거나 `CallNotPermittedException` 이면 503(문구 같음), 그 밖만 500 | `OrderCreateAddressValidationTest#upstreamFailureIs503` |
| 3 | user · chat 디코더가 400 · (find 아닌) 404 에 `null` → **NPE**, 그 밖은 검사 예외 | cart 와 같이 늘 `UpstreamCallException(메서드, 상태)` · find 의 404 는 그대로 사용자 없음 — 부르는 쪽의 `catch (RuntimeException)` 는 그대로 받는다 | user `FeignErrorDecoderTest` |
| 4 | 전역 처리기에 상류 실패 503 이 없다 | common `handleUpstream` — `UpstreamCallException` 은 503(상류 상태 · 메서드는 본문에 없음). `CallNotPermittedException` 은 common 이 resilience4j 를 런타임에 갖지 않아 호출 지점에서(1 · 2) | `CustomRestExceptionHandlerTest#upstreamCallIs503` |
| 5 | 부르는 곳 없는 메서드 9개 | 지웠다(chat 은 클라이언트째) — 테스트의 `never()` 확인도(그 길로 되돌아갈 수 없다) | 컴파일 |
| 6 | 브레이커 경보가 없다 | `UpstreamCircuitOpen`(열림 · 반열림이 5분) | promtool `reliability-rules.test.yml` |
