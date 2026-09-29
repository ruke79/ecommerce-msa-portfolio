# 개선 착수 전 문제 분석

[日本語](01-branch-issues.md) | **한국어**

> 🕰️ **상태(2026-09-27) — 이전 기록.** 개선에 착수하기 전의 진단입니다. 여기 적힌 문제는 대부분 [02](02-improvements.ko.md) · [11](11-observability-and-kafka-reliability.ko.md) · [12](12-dlq-rollout-idempotency-alerts.ko.md) · [18](18-certificates.ko.md) 에서 고쳤습니다. 지금 상태는 [docs/54](54-improvements-vs-practice.ko.md).

> 작성일: 2026-07-24
> 기준 브랜치: `amaon_clone_msa_postgresql_version` (default)
> 참고: [liquidslr/system-design-notes](https://github.com/liquidslr/system-design-notes) (Alex Xu, *System Design Interview* Vol.1-2) — Rate Limiting / Circuit Breaker, Message Queue, Caching, Payment System, Chat System, Monitoring & Alerting 챕터

이 문서는 **개선 작업 이전의 상태**를 기록한다. 여기서 도출한 문제점을 근거로 `docs/02-...` 이후의 개선을 진행했다.

---

## 0. 요약

현재 브랜치는 default 브랜치 대비 **커밋 1개**만 추가되어 있다.

```
bd76010 Harden chat-service WebSocket/STOMP for production
```

이 커밋은 **chat-service의 WebSocket/STOMP 보안·정합성 하드닝**으로 품질이 높다(아래 §1). 그러나 시스템 전체 관점에서는 **chat 한 서비스에만 국한**되어 있고, 나머지 9개 모듈이 공통으로 안고 있는 **회복탄력성(resilience)·메시징 신뢰성·관측성·운영** 문제는 손대지 않았다. "획기적 개선"을 위해서는 브랜치의 범위를 시스템 전반으로 넓혀야 한다.

| 영역 | 심각도 | 핵심 문제 |
|---|---|---|
| 서비스 간 호출 회복탄력성 | 🔴 High | Feign에 서킷브레이커/폴백 없음 → 장애 전파(cascading failure) |
| API Gateway 방어 | 🔴 High | 서킷브레이커 필터 주석 처리, 레이트리미터 미완성 |
| Kafka 메시징 신뢰성 | 🔴 High | 컨슈머 에러 핸들러/DLQ/재시도 없음, 멱등성 부재 |
| 관측성(Observability) | 🟠 Medium | 분산 추적·상관관계 ID 없음, 메트릭 노출 불완전 |
| 배포/운영 | 🟠 Medium | healthcheck/restart/리소스 제한 전무, TLS 무력화 |
| 데이터/확장성 | 🟠 Medium | 상태 저장소 단일 인스턴스, 설정 불일치 |
| 테스트/문서 | 🟡 Low | 테스트 17개, `docs/` 부재 |

---

## 1. 브랜치가 이미 잘한 점 (유지 대상)

커밋 `bd76010`은 실제 취약점을 정확히 고쳤다:

- **STOMP 신원 위조 차단**: CONNECT 시 JWT를 1회 검증하고 검증된 subject를 STOMP 세션에 저장, 이후 SUBSCRIBE/SEND는 클라이언트가 보낸 `email` 헤더를 신뢰하지 않음 (`StompHandler.java`).
- **인증 우회 수정**: `TokenHandler.getUid()`(서명 미검증 디코드)로 사용자를 식별하던 경로를 `resolveVerifiedSubject()`로 교체 → 위조 JWT로 임의 사용자 사칭 불가.
- **CORS 축소**: `setAllowedOriginPatterns("*")` → 프론트 오리진으로 제한.
- **토큰 로그 누출 제거**, **ParticipantChatRoom 중복 insert 멱등화**.
- **Gateway 라우트 분리**: `/chat-service/api/**`에 JWT 필터 적용, WS 핸드셰이크 경로만 예외.
- 프론트: 잘못된 구독 해제 버그, STOMP 클라이언트 재생성 문제, 디버그 로그의 토큰 노출 수정.

→ 이 변경들은 **회귀시키지 않고 그대로 유지**한다. 문제는 "잘못됨"이 아니라 "범위가 좁음"이다.

---

## 2. 서비스 간 호출 회복탄력성 부재 🔴

**근거 파일**
- `order-service/.../client/CartServiceClient.java`, `CatalogServiceClient.java`, `UserServiceClient.java`
- `cart-service/.../client/CatalogServiceClient.java`, `UserServiceClient.java`
- `coupon-service/.../client/UserServiceClient.java`
- 7개 서비스 모두 `spring-cloud-starter-openfeign` **+** `spring-cloud-starter-circuitbreaker-resilience4j` 를 classpath에 보유

**문제**
1. **서킷브레이커 미적용**: 모든 `@FeignClient` 인터페이스에 `fallback`/`fallbackFactory`가 없고, `spring.cloud.openfeign.circuitbreaker.enabled`가 켜져 있지 않다. resilience4j가 classpath에 있는데도 Feign 호출을 전혀 감싸지 않는다.
2. **폴백 없음**: 의존 서비스가 죽으면 예외가 그대로 상위로 전파되어 사용자에게 5xx.
3. **타임아웃 불균일**: `order-service`는 Feign `connect/readTimeout: 5000`을 두었지만, 다른 호출자(cart, coupon, user)에는 명시적 타임아웃 표준이 없다. 타임아웃이 없으면 느린 응답이 톰캣 워커 스레드를 점유 → **스레드 풀 고갈 → 장애 전파**.

**영향 시나리오**: `catalog-service`가 느려지면 → `order-service`/`cart-service`의 Feign 호출이 대기 → 워커 스레드 고갈 → 정상이던 주문/장바구니 기능까지 동반 장애. (System Design Notes의 *Circuit Breaker* 패턴이 정확히 이 상황을 다룬다.)

---

## 3. API Gateway 방어 로직 미완성 🔴

**근거**: `gateway-server/src/main/resources/application-local.yml`, `gateway-server/.../fallback/*`, `resolver/CustomUserKeyResolver.java`, `build.gradle.kts`

1. **서킷브레이커 필터 비활성**: `default-filters`의 `CircuitBreaker` 항목이 **주석 처리**되어 있고, 어떤 라우트에도 CircuitBreaker 필터가 붙어 있지 않다. 정작 `FallbackController`, `Resilience4jConfig`, `resilience4j:` 설정 블록은 **완성되어 있는데 연결만 안 됨**.
2. **레이트리미터 미완성 (스캐폴딩만 존재)**:
   - `build.gradle.kts`에서 `spring-boot-starter-data-redis-reactive`가 **주석 처리**되어 있어 `RequestRateLimiter`가 동작 불가.
   - `CustomUserKeyResolver`가 `X-User-Id` 헤더를 그대로 반환하는데, 헤더가 없으면 **`null` 반환 → 리액티브 파이프라인 NPE**.
   - 어떤 라우트에도 `RequestRateLimiter` 필터가 없다.
   - → 게이트웨이에 **유입 트래픽 제어 수단이 전혀 없음**. (System Design Notes의 *Rate Limiter* 챕터 미반영)
3. **TLS 신뢰 무력화**: `spring.cloud.gateway.httpclient.ssl.useInsecureTrustManager: true` → 게이트웨이가 업스트림 인증서를 검증하지 않는다. README가 내세우는 mTLS의 신뢰 체계를 스스로 깨뜨린다.

---

## 4. Kafka 메시징 신뢰성 부재 🔴

**근거**: 6개 서비스의 `KafkaConsumerConfig.java` (order/pay/cart/catalog/coupon/chat/user), `order-service/.../listener/PaymentResponseMessageListener.java`

1. **컨슈머 에러 핸들러/DLQ 없음**: `order-service/.../config/KafkaConsumerConfig.java`에 `DefaultErrorHandler`, `DeadLetterPublishingRecoverer`, 재시도 `BackOff` 설정이 전혀 없다. 역직렬화 실패나 처리 예외가 발생하면 **오프셋이 커밋되지 않고 같은 레코드를 무한 재시도** → **poison message가 파티션 전체를 블로킹**.
2. **멱등성(idempotency) 부재**: `pay-service`에만 `IdempotencyKeyRepository`(Redis)가 있고, order/cart/catalog/coupon 컨슈머는 중복 소비 방어가 없다. Kafka는 at-least-once 이므로 리밸런싱/재처리 시 **재고 이중 차감, 쿠폰 이중 처리** 위험.
3. **Outbox relay 견고성 부족**: `PaymentOutboxScheduler`는 `@Scheduled` 폴링으로 `STARTED` 이벤트를 발행하지만,
   - 발행 실패 시 재시도/백오프/최대 시도 횟수 정책이 코드로 드러나지 않고,
   - 스케줄러가 `@Transactional` 한 트랜잭션에서 배치 전체를 처리해 **한 건 실패가 배치 전체에 영향**,
   - `COMPLETED` 이벤트 정리(archiving/삭제) 정책이 없어 outbox 테이블이 무한 증가.

---

## 5. 관측성(Observability) 미흡 🟠

- **분산 추적 없음**: Micrometer Tracing/Zipkin/OTel 의존성이 없고, 서비스 경계를 넘는 **상관관계 ID(correlation/trace id)** 전파가 없다. Kafka·Feign을 넘나드는 사가 흐름에서 장애를 추적하기 매우 어렵다.
- **메트릭 노출 불완전**: 게이트웨이는 `prometheus` 엔드포인트를 노출하지만, 개별 서비스의 actuator 노출은 표준화되어 있지 않다(서비스별 `management.endpoints` 설정 상이/부재).
- README에 monitoring/tracing/alerting 언급이 전혀 없다 → 운영 가시성 설계 부재.

---

## 6. 배포/운영 신뢰성 🟠

**근거**: `docker-compose-core.yml`, `docker-compose-services-1.yml`, `docker-compose-services-2.yml`

1. **healthcheck 전무**: 어떤 컨테이너에도 `healthcheck`가 없다 → 컨테이너가 "떠 있지만 요청은 못 받는" 좀비 상태를 오케스트레이터가 감지 못함.
2. **restart 정책 없음**: `restart: unless-stopped` 등이 없어 크래시 후 자동 복구 불가.
3. **리소스 제한 없음**: `mem_limit`/`cpus`/`deploy.resources`가 없어 한 서비스의 메모리 누수가 **호스트 전체를 잠식**(noisy neighbor).
4. **빌드 컨텍스트 오타**: `docker-compose-core.yml`의 eureka 서비스 `build.context: ./discovery-service` — 실제 디렉터리는 `./eureka-server`. 빌드 실패 유발.
5. **의존성 순서 취약**: `depends_on`만으로는 "컨테이너 시작"만 보장하고 "서비스 준비 완료"는 보장하지 못한다(healthcheck condition 미사용).
6. **네트워크 분리**: `msa-core-network` / `services-1-network` / `services-2-network`로 나뉘어 있어 서비스 상호 통신 경로가 compose만으로는 성립하지 않는다(외부 네트워크 전제가 문서화되지 않음).
7. **시크릿/설정 결함**: compose env의 `SERVER_SSL_KEY_STORE_PASSWORD=` 등 **빈 값**, `SPRING_CONFIG_IMPORT`/`EUREKA...DEFAULTZONE`의 호스트가 비어 있음. `cart-service`만 eureka/config를 `http://`로, 나머지는 `https://`로 지정하는 **프로토콜 불일치**.

---

## 7. 데이터·확장성 🟠

- 상태 저장소(PostgreSQL/MongoDB/Redis/Elasticsearch/Kafka)가 **단일 인스턴스** 전제 → 복제/failover/파티셔닝 부재. (System Design Notes의 *Replication/Partitioning* 미반영)
- 캐시(catalog Redis)의 **무효화·TTL·스탬피드(cache stampede) 방어 정책**이 문서화되어 있지 않다.

---

## 8. 테스트·문서 🟡

- 전체 저장소 테스트 파일 **17개** — 사가/아웃박스/서킷브레이커 등 핵심 신뢰성 경로에 대한 통합 테스트가 빈약.
- `docs/` 디렉터리 부재. 아키텍처/장애 대응 문서가 없음(본 개선에서 생성).

---

## 9. 개선 우선순위 (다음 단계에서 반영)

1. **(P0) 서비스 간 호출 회복탄력성**: Feign 서킷브레이커+타임아웃 표준화 → `docs/02`, `03`.
2. **(P0) Gateway 방어**: 서킷브레이커 라우트 연결 + Redis 레이트리미터 활성화 + KeyResolver 견고화.
3. **(P1) Kafka 신뢰성**: DLQ + 재시도 백오프 + 컨슈머 멱등성 가이드.
4. **(P1) 운영 하드닝**: compose healthcheck/restart/리소스 제한, 빌드 컨텍스트 오타 수정.
5. **(P2) 관측성**: actuator 표준 노출 + 분산 추적 도입 로드맵.

각 항목의 실제 변경 내역은 [`docs/02-improvements.md`](02-improvements.ko.md), 시스템 전체 설계·장애 대응은 [`docs/03-architecture-and-reliability.md`](03-architecture-and-reliability.ko.md) 참조.
