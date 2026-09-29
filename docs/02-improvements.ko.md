# 개선 내역 (What Changed)

[日本語](02-improvements.md) | **한국어**

> 🕰️ **상태(2026-09-27) — 이전 기록.** 로드맵으로 넘긴 항목 중 DLQ · 컨슈머 멱등성 · 분산 추적 · TLS 검증은 이후 적용했습니다([11](11-observability-and-kafka-reliability.ko.md) · [12](12-dlq-rollout-idempotency-alerts.ko.md) · [18](18-certificates.ko.md)). Feign 서킷 브레이커만 아직 없습니다.

> [`docs/01-branch-issues.md`](01-branch-issues.ko.md)에서 도출한 문제를 근거로 한 실제 변경 목록이다.

이번 개선의 원칙: 당시 검증 환경은 **컴파일/통합 테스트가 불가능**했으므로,
1. **동작을 바꾸지 않고도 확실히 안전한 변경**(설정·게이트웨이 배선·운영)을 실제로 적용하고,
2. **런타임 회귀 위험이 검증 불가한 변경**(Kafka 배치 컨슈머 DLQ, Feign 레벨 서킷브레이커)은 **정확한 구현 템플릿과 함께 로드맵으로 문서화**한다.

이렇게 하면 "동작하는 코드"만 브랜치에 들어가고, 위험한 변경은 근거·방법과 함께 리뷰 대상이 된다.

---

## 1. API Gateway — 서킷브레이커 배선 + 레이트리미터 활성화 🔴→✅

문제: 서킷브레이커·레이트리미터의 부품(FallbackController, Resilience4jConfig, CustomUserKeyResolver, resilience4j 설정 블록)이 **모두 존재하는데 라우트에 연결만 안 됨**.

| 파일 | 변경 |
|---|---|
| `gateway-server/build.gradle.kts` | `spring-boot-starter-data-redis-reactive` 주석 해제(레이트리미터 백엔드), `micrometer-registry-prometheus` 활성화 |
| `gateway-server/.../resolver/CustomUserKeyResolver.java` | **NPE 버그 수정**: `X-User-Id` 없을 때 `null` 반환 → `user:<id>` → `ip:<X-Forwarded-For>` → 소켓 IP → `anonymous` 순으로 **항상 비어있지 않은 키** 반환 |
| `gateway-server/.../application-local.yml` | ① `RequestRateLimiter`를 `default-filters`로 추가(Redis 토큰 버킷, replenish 20/burst 40) ② 각 **REST 라우트**(catalog/cart/coupon/order/pay/chat-api)에 `CircuitBreaker` 필터 + `fallbackUri: forward:/fallback` 연결 ③ `spring.data.redis` 접속 정보 추가 |

주의점(문서화됨):
- **WS 라우트(`chat-service-ws`)에는 서킷브레이커를 붙이지 않았다.** WebSocket은 장수명 커넥션이라 요청 지속시간=연결 지속시간이 되어 slow-call 로 오탐되기 때문.
- 레이트리미터는 **Redis 가용성에 결합**된다. Redis 다운 시 게이트웨이가 트래픽을 처리하지 못하므로 운영에서 Redis 이중화가 전제(→ [`docs/03`](03-architecture-and-reliability.ko.md) 장애 시나리오 F-6).

효과: 특정 서비스가 죽어도 게이트웨이가 **빠르게 폴백 응답**을 주어 사용자 경험이 유지되고, 폭주 트래픽은 사용자/IP 단위로 **셰이핑**된다. (System Design Notes의 *Circuit Breaker*, *Rate Limiter* 반영)

---

## 2. Feign 호출 타임아웃 표준화 🔴→✅ (부분)

문제: Feign 호출에 서비스별로 타임아웃이 제각각/부재 → 느린 다운스트림이 워커 스레드를 점유하는 **cascading failure**.

| 파일 | 변경 |
|---|---|
| `common/src/main/resources/application-common.yml` | 모든 서비스 공통 `spring.cloud.openfeign.client.config.default`: `connectTimeout 3000ms`, `readTimeout 5000ms`, `loggerLevel basic`. ⚠️ 처음에는 `feign.client.config` 접두사로 넣었는데 OpenFeign 4.x 가 읽지 않는 키라 **실제로는 적용되지 않고 있었다**(docs/37 §2.1). 접두사를 고치고 바인딩·동작 테스트로 고정했다 |

이 파일은 **전 서비스가 import하는 단일 공통 설정**이라 한 곳 수정으로 전 서비스에 적용된다.

**Feign 레벨 서킷브레이커는 이번에 켜지 않았다 — 이유:**
`order-service`/`cart-service`의 `FeignConfig.requestInterceptor()`는 `RequestContextHolder`(ThreadLocal)에서 `Authorization` 헤더를 읽어 전파한다. Spring Cloud의 Feign 서킷브레이커는 기본적으로 **별도 스레드에서 호출을 실행**하므로 이 ThreadLocal이 유실 → 다운스트림 호출이 **인증 헤더 없이** 나가 인증 실패를 유발할 수 있다. 검증 불가한 환경에서 이 회귀를 감수할 수 없어, **컨텍스트 전파와 함께 도입**하도록 로드맵에 코드 템플릿으로 남겼다([`docs/03` §로드맵 R-1](03-architecture-and-reliability.ko.md)).

효과: 타임아웃만으로도 **스레드 고갈형 장애 전파의 최악 시나리오는 차단**된다.

---

## 3. 관측성(Observability) 표준화 🟠→✅ (부분)

| 파일 | 변경 |
|---|---|
| `common/src/main/resources/application-common.yml` | actuator 노출에 `info, metrics` 추가, **health 프로브(liveness/readiness) 활성화**, `show-details: when_authorized` |
| `gateway-server/build.gradle.kts` | Prometheus 레지스트리 활성화 |

효과: 컨테이너 오케스트레이터가 `/actuator/health/readiness`로 **트래픽 라우팅 시점**을, `/liveness`로 **재시작 판단**을 구분할 수 있게 됨. Prometheus 스크레이프 대상 표준화.

---

## 4. 배포/운영 하드닝 🟠→✅

| 파일 | 변경 |
|---|---|
| `docker-compose-core.yml` | eureka `build.context` **오타 수정**(`./discovery-service` → `./eureka-server`), 잘못된 `Dockerfile:` 키 → `dockerfile:`, healthcheck/`restart: unless-stopped`/`mem_limit`/`cpus` 추가, gateway `depends_on: eureka(condition: service_healthy)`, gateway에 `REDIS_HOST/PORT` 주입 |
| `docker-compose-services-1.yml` | user/cart/catalog/chat 4개에 healthcheck/restart/리소스 상한 추가, `Dockerfile:`→`dockerfile:`, **cart-service eureka/config `http`→`https` 통일** |
| `docker-compose-services-2.yml` | coupon/pay/order 3개에 동일 하드닝 |

healthcheck는 베이스 이미지(`eclipse-temurin:17-jre-jammy`)에 curl/wget이 없어 **bash `/dev/tcp` 포트 점검**으로 구현.

효과: 좀비 컨테이너 감지, 크래시 자동 복구, noisy-neighbor 방지, 기동 순서 정합.

---

## 5. 로드맵으로 이관한 항목(안전상 미적용, 템플릿 제공)

| 항목 | 이유 | 위치 |
|---|---|---|
| Kafka 컨슈머 DLQ + 재시도 백오프 | 대상 컨슈머가 **batch listener** → `DeadLetterPublishingRecoverer`에 SSL 프로듀서(KafkaTemplate) 필요, `BatchListenerFailedException` 처리 등 미검증 위험 | [`docs/03` R-2](03-architecture-and-reliability.ko.md) |
| Kafka 컨슈머 멱등성(order/cart/catalog/coupon) | pay-service의 `IdempotencyKeyRepository` 패턴을 확산해야 하나 스키마/트랜잭션 검증 필요 | [`docs/03` R-3](03-architecture-and-reliability.ko.md) |
| Feign 서킷브레이커 + 폴백 | ThreadLocal 인증 전파 충돌(§2) | [`docs/03` R-1](03-architecture-and-reliability.ko.md) |
| 분산 추적(OTel/Zipkin) | 의존성/컬렉터 인프라 필요 | [`docs/03` R-4](03-architecture-and-reliability.ko.md) |
| `useInsecureTrustManager: true` 제거 | 트러스트스토어 정합성 실환경 검증 필요 | [`docs/03` R-5](03-architecture-and-reliability.ko.md) |

---

## 검증

- 변경한 5개 YAML 전부 `yaml.safe_load_all` 파싱 통과.
- 게이트웨이 부품(FallbackController/Resilience4jConfig/KeyResolver)은 기존 구현을 **재사용**하도록 배선만 추가 → 신규 컴파일 위험 최소화.
- 브랜치 기존 커밋(chat WS 하드닝)은 **회귀 없이 유지**.
