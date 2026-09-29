# 실제 기동 테스트 — 발견된 기동 불가 버그와 검증 결과

[日本語](14-boot-test-findings.md) | **한국어**

> 🕰️ **상태(2026-09-27) — 이전 기록.** 기동 테스트 당시의 기록입니다. 가드는 이후 B-69 까지 늘었습니다. 테스트 전략은 [docs/17](17-testing.ko.md).

> 작성일: 2026-07-27
> 배경: 그때까지의 작업은 컴파일·단위 테스트·SQL 실행까지만 검증했을 뿐 **애플리케이션을 한 번도 띄워본 적이 없었다.**
> 그것이 남은 가장 큰 미검증 리스크였으므로, 실제 PostgreSQL/Redis 를 띄우고 서비스를 기동했다.

## 결론 먼저

**서비스는 기동조차 불가능한 상태였다.** 컴파일과 단위 테스트로는 절대 잡히지 않는 기동 차단 버그가 연달아 드러났고, 모두 수정한 뒤에야 처음으로 애플리케이션이 떴다.

```
2026-07-27 21:34:28  INFO  Started OrderServiceApplication in 19.051 seconds
```

**최종적으로 애플리케이션 서비스 8종이 전부 기동을 확인했다** (아래 §"기동 확인 현황").
그리고 기동 테스트를 마친 뒤 실행한 `./gradlew test` 는 **22건 실패로 빨간불이었다** — 이 역시 함께 고쳐 처음으로 전체 초록불이 됐다(B-15~B-18).

## 발견된 기동 불가 버그

### B-1. profile-specific 파일 안의 `spring.profiles.active` — 6개 서비스 🔴

```
InvalidConfigDataPropertyException: Property 'spring.profiles.active' imported from
location 'class path resource [application-local.yml]' is invalid in a profile specific resource
```

`application.yml` 이 이미 `spring.profiles.active: local` 로 프로필을 켜는데, **프로필 전용 파일인 `application-local.yml` 이 같은 속성을 다시 선언**하고 있었다. Spring Boot 2.4+ 는 이를 금지하며 **기동을 즉시 중단**한다.

- 영향: catalog · order · pay · coupon · gateway · config-server · **user · cart · chat** = **9개 서비스**
  (⚠️ 최초 수정 때 6개만 고쳤다가, 나머지 3개는 4칸 들여쓰기라 2칸 기준 grep 에 걸리지 않아 누락 → 2차 점검에서 발견·수정)
- 유입: 기존 커밋 `8747dd3`("application-local.yml Reafactoring") — 이번 작업의 변경과 무관
- 수정: 중복 선언 제거(프로필 활성화는 `application.yml` 이 담당하므로 기능 변화 없음)

### B-2. `feign.*-url` 프로퍼티가 어디에도 정의되지 않음 — 5개 서비스 🔴

```
java.lang.IllegalArgumentException: http://${feign.cart-url} is malformed
```

`@FeignClient(url = "${feign.cart-url}")` 가 참조하는 값이 **어떤 yml/properties/compose 에도 없었다.** k3s 작업([docs/08](08-k3s-deployment.ko.md))에서 "이대로면 기동 실패할 것"이라고 지적하고 ConfigMap 에만 넣어뒀는데, **실제로 기동 불가임이 실증**됐다.

- 영향: order(3개) · cart(2개) · coupon · chat · user **5개 서비스**
- 수정: 각 서비스 `application-local.yml` 의 `feign:` 블록에 환경변수 오버라이드 가능한 기본값 추가
  (`cart-url: ${FEIGN_CART_URL:https://localhost:8082}` 형태 — k8s ConfigMap 의 `FEIGN_*_URL` 이 그대로 우선 적용됨)

### B-3. order-service 의 Redis 프로퍼티 미정의 🔴

```
PlaceholderResolutionException: Could not resolve placeholder 'spring.data.redis.host'
```

order-service 의 `RedisCacheConfig`/`RedissonConfig` 가 `spring.data.redis.host/port/password/ssl.*` 를 요구하는데 **application-local.yml 에 redis 블록이 통째로 없었다**(다른 서비스에는 있음).

- 수정: 다른 서비스와 동일한 형태의 redis 블록 추가(환경변수 오버라이드 가능)

## 함께 발견한 비치명적 문제

### B-4. 인덱스 이름 충돌 `idx_order` (기존 버그)
```
WARN  GenerationTarget encountered exception ... [ERROR: relation "idx_order" already exists]
```
`Order` 와 `OrderProduct` 가 **같은 인덱스 이름 `idx_order`** 를 선언한다(PostgreSQL 인덱스명은 스키마 단위 유일). 결과적으로 **`OrderProduct` 의 인덱스는 영영 생성되지 않는다**(해당 조회 경로에 인덱스 부재). → `idx_order_product` 로 개명.

### B-5. Flyway ↔ Hibernate 인덱스 중복 생성 시도 (문서 정정)
`idx_payment_outbox_event` 를 Flyway 가 만든 뒤 Hibernate `ddl-auto: update` 가 **같은 이름으로 다시 CREATE 를 시도**해 WARN 을 남긴다. [docs/09 §3.1](09-postgresql-partitioning-sharding.ko.md) 에서 "인덱스 이름을 엔티티와 동일하게 두어 재생성 시도를 차단했다"고 적었는데 **그 주장은 부정확했다** — 시도는 하고 실패를 경고로 남긴다. 기동에는 지장 없으나, `ddl-auto: validate` 로 전환하면 사라진다.

### B-6. `RedissonConfig` 가 전체 주석 처리됨
커스텀 `RedissonConfig` 는 전부 주석 상태이고, 실제 `redisson` 빈은 **Redisson 자동설정**이 만든다. 이 때문에 `spring.data.redis.password` 가 빈 문자열이면 AUTH 실패로 기동이 막힌다(비밀번호 없는 Redis 를 쓸 경우의 함정).

### B-7. Redis 프로퍼티 이름 불일치 / 블록 부재 — 5개 서비스 🔴
`RedisCacheConfig` 가 `spring.data.redis.ssl.keystore` · `ssl.truststore` 를 요구하는데, yml 에는 **`cert`/`key` 라는 다른 이름**만 있었다. cart-service 는 redis 블록 자체가 없었다.
```
PlaceholderResolutionException: Could not resolve placeholder 'spring.data.redis.ssl.keystore'
```
- 영향: catalog · pay · chat · user(이름 불일치) + cart(블록 전무) — **5개 서비스 기동 불가**
- 수정: 코드가 요구하는 이름으로 키 추가(환경변수 오버라이드 가능), cart 는 블록 신설

### B-8. catalog 커스텀 EMF 가 `ddl-auto` 를 전달받지 못함 🔴
```
relation "product.category" does not exist
```
catalog 는 `DataSourceConfig` 에서 EMF 를 **손으로 생성**하는데 JPA 프로퍼티 맵이 **주석 처리**되어 있었다. 즉 `spring.jpa.hibernate.ddl-auto` 가 EMF 에 도달하지 못해 **빈 DB 에서는 스키마가 아예 생성되지 않는다**(신규 환경 기동 불가). `create-drop` 이 무해했던 것도 같은 이유 — 애초에 적용된 적이 없었다.
- 수정: `spring.jpa.*` 값을 `@Value` 로 읽어 `setJpaPropertyMap` 으로 명시 주입

### B-9. (내가 만든 버그) `ProcessedEvent` 가 EMF 스캔 대상 밖 🔴
멱등성 엔티티를 `message.model` 패키지에 뒀는데 catalog EMF 는 `com.project.catalog_service.model` 만 스캔한다 → `Not a managed type` 으로 기동 실패. **단위 테스트는 리포지토리를 목킹해서 통과했다** — 기동 테스트만이 잡을 수 있는 종류의 버그. `model` 패키지로 이동해 해결.

## 2차 기동 테스트 — user · pay · chat · cart (B-10 ~ B-14)

1차에서 미검증으로 남겼던 서비스들을 실제로 띄웠고, **또 5건의 버그가 나왔다.** 기동 테스트는 한 번으로 끝나지 않는다는 것이 요점이다.

### B-10. chat-service 환경변수 이름 오타 `LOCAL_KEY_STOE_PASSWORD` 🔴
```
PlaceholderResolutionException: Could not resolve placeholder 'LOCAL_KEY_STOE_PASSWORD'
  in value "${LOCAL_KEY_STOE_PASSWORD}" <-- "${spring.data.mongodb.keystorePassword}"
```
`STORE` 의 `R` 이 빠져 있다. `MongoSslConfig` 가 `@Value` 로 기본값 없이 읽으므로 **오타 난 이름의 환경변수를 그대로 export 하지 않는 한 chat-service 는 절대 뜨지 않는다.** → 올바른 이름으로 수정.

### B-11. user-service 액추에이터가 401 — k8s 프로브가 영원히 실패 🔴
user-service 만 Spring Security 를 쓰는데 `.anyRequest().authenticated()` 뿐이고 액추에이터 예외가 없었다. 결과:
```
GET /actuator/health/readiness  →  401
```
- **k8s 는 파드를 영원히 NotReady 로 둔다**(readiness 실패 → 트래픽 미전달, liveness 실패 → 재시작 루프).
- **Prometheus 도 user-service 만 스크레이프하지 못한다** — 이번 작업에서 만든 관측성 스택에 구멍이 생긴다.
- 수정: `EndpointRequest.to(HealthEndpoint.class)` 와 `prometheus`/`info` 만 `permitAll()`.
  게이트웨이는 `/user-service/api/**`·`/sse` 만 라우팅하므로 이 경로가 외부로 열리지는 않는다.
- 검증: `readiness 200` · `prometheus 200` · `/api/user/me 는 그대로 401`.

### B-12. chat-service 가 PostgreSQL 에 MySQL DDL 을 보냄 🔴
```
ERROR: syntax error at or near "engine"
ERROR: syntax error at or near "auto_increment"
```
데이터소스는 PostgreSQL 인데 `dialect: org.hibernate.dialect.MySQLDialect` 였다. Hibernate 가 MySQL 문법 DDL 을 생성해 **모든 테이블 생성이 실패**했다. 게다가 이 실패는 WARN 으로만 남아 **테이블 없이 서비스가 "떠 있는" 상태**가 된다.
→ `PostgreSQLDialect` 로 수정. 이 버그 덕에 chat 테이블이 애초에 만들어진 적이 없음이 증명되어, 아래 DB 분리도 안전하게 적용할 수 있었다.

### B-13. 엔티티가 쓰는 스키마를 아무도 만들지 않음 — chat · coupon 🔴(조용한 실패)
```
Error executing DDL "create table chat.member (...)" [ERROR: schema "chat" does not exist]
```
`@Table(schema = "chat")` 를 쓰지만 **Hibernate 는 테이블은 만들어도 스키마는 만들지 않는다.** 다른 서비스는 Flyway 마이그레이션에 `CREATE SCHEMA` 가 있었으나 chat·coupon 에는 없었다. WARN 만 남고 기동은 계속되므로 **로그를 끝까지 읽지 않으면 보이지 않는다.**
→ `V2__create_chat_schema.sql` · `V2__create_coupon_schema.sql` 추가.
→ 검증: 재생성한 빈 DB 에서 `chat.member`, `coupon.coupon` 생성 확인.

> coupon-service 는 1차 기동 테스트에서 "성공"으로 기록됐지만, **테이블이 없는 채로 떠 있었다.**
> "떴다"와 "동작한다"는 다르다는 것을 보여주는 사례다.

### B-14. Redisson 이 평문 Redis 에 TLS 핸드셰이크 — chat · cart 🔴
```
INFO  RedissonConfig - SSL is not enabled for Redisson connection.
Caused by: SslHandshakeTimeoutException: handshake timed out after 10000ms
```
주소가 `"rediss://" + host` 로 **고정**돼 있어 `ssl.enabled=false` 여도 TLS 를 시도한다. 로그는 "SSL 안 씀"이라고 찍으면서 실제로는 TLS 로 접속하는, 진단을 가장 어렵게 만드는 조합이다.
→ `(useSsl ? "rediss://" : "redis://")` 로 스킴을 플래그에 연동.

## 테스트 스위트가 빨간불이었다 (B-15 ~ B-18)

기동 문제를 정리한 뒤 `./gradlew test` 를 돌리자 **36건 중 22건 실패**였다. 즉 이 저장소의 테스트는 **신뢰할 수 없는 상태**였고, 그 안에 진짜 제품 버그가 섞여 있었다.

### B-15. 널 역참조로 인한 실제 500 — catalog 🔴
테스트가 드러낸 **제품 코드의 NPE 3종**:

| 위치 | 증상 |
|---|---|
| `ProductMapper.toDto` | `getShipping().toPlainString()` — 배송비가 비어 있는 상품 **하나만** 있어도 목록 조회 전체가 500 |
| `ProductMapper.toDto` | `getDetails()/getSubcategories()/getQuestions()/getSkus()` 의 무방비 `.stream()` — 컬렉션이 null 인 엔티티에서 500 |
| `ProductService.getCartProductInfo` | 같은 `getShipping()` 역참조 — 해당 상품을 **장바구니에 담는 순간** 500 |
| `ProductSkuItemProcessor.process` | CSV 의 `discount`/`sold` 는 `Integer`(널 가능)인데 엔티티는 `int` → **빈 셀 하나가 언박싱 NPE 로 임포트 잡 전체를 죽임** |

→ 전부 널 안전하게 수정(`mapList` 헬퍼 도입, 빈 셀은 엔티티 기본값 0 으로).

### B-16. 존재하지 않는 API 를 검증하던 테스트 🟠
- `GET /api/product/products/by-subcategory` — **그런 매핑이 없다.** 항상 404 였다. (실제로 `getProductsByCategoryAndSubcategory` 는 HTTP 로 노출된 적이 없고 캐시 워밍업 경로에서만 쓰인다.) → 삭제.
- `warmUpProductCaches` 테스트는 `@InjectMocks`(실객체)에 `when(...)` 스텁을 걸어, 스텁 시점에 진짜 메서드가 실행되며 `PageRequest.of(0, 0)` → *"Page size must not be less than one"* 으로 깨졌다. → `@Spy` + `doReturn` 으로 수정.
- 그 과정에서 `warmUpProductCaches` 의 `page++` 가 **감싸는 루프가 없는 죽은 코드**임이 드러났다. 워밍업은 각 조합의 **첫 페이지만** 데운다. 테스트는 상상한 동작이 아니라 실제 동작을 고정하도록 고쳤다.

### B-17. 명시적 `@ComponentScan` 이 테스트 슬라이스를 망가뜨림 — chat · cart · order 🟠
```
No bean named 'entityManagerFactory' available
```
`@SpringBootApplication` 은 기본으로 `TypeExcludeFilter` 를 달아 주는데, **명시적 `@ComponentScan` 이 이를 대체**해 버린다. 그 결과 `@WebMvcTest` 슬라이스가 동작하지 않고 Kafka 컨슈머·JPA 리포지토리까지 전부 로딩됐다.
→ `excludeFilters = @Filter(type = CUSTOM, classes = TypeExcludeFilter.class)` 로 필터 복원(`com.project.common` 스캔은 유지). 세 서비스 모두 **수정 후 재기동으로 런타임 무해함을 확인**했다.
→ 함께: `ChatControllerTest` 는 `/chat/room` 을 호출했으나 실제 매핑은 `ChatApiController` 의 `/api/chat/room` 이었고, 그 컨트롤러가 슬라이스에 없었다. 둘 다 수정.

### B-18. 인프라가 필요한 `contextLoads()` 가 전체 빌드를 빨간불로 🟠
`@SpringBootTest contextLoads()` 6종(catalog·eureka·gateway·order·pay·user)은 실 DB·Redis·Kafka·키스토어 환경변수가 있어야만 통과한다. 인프라 없는 CI 에서는 **항상 실패**하고, 그 때문에 **진짜 단위 테스트의 신호까지 묻혔다.**
→ 사유를 명시해 `@Disabled` 처리하고, 컨텍스트 검증은 기동 테스트/`ops/06-smoke-test.sh` 의 몫으로 넘겼다. **조용히 깨진 채 두지 않는 것**이 요점 — Testcontainers 도입이 후속 과제다.
→ 덤으로 **config-server 의 진짜 기동 불가 버그**가 잡혔다: `import: "classpath:application-common.yml"` 이 필수 import 인데 config-server 는 `common` 모듈에 의존하지 않는다. `optional:` 로 수정.

### B-20. 빌드가 `.gitignore` 된 비밀 파일에 의존하고 있었다 🔴
```
ConfigDataResourceNotFoundException:
  'classpath:kafka-secrets.properties' cannot be found
```
각 서비스의 `application-local.yml` 은 `classpath:kafka-secrets.properties` 를 **필수로** import 하는데, 그 파일(`common/src/main/resources/kafka-secrets.properties`)은 `.gitignore` 대상이다. 즉 **새로 클론한 저장소나 CI 에서는 컨텍스트를 로딩하는 테스트가 통째로 실패한다.**

이건 기동 테스트 중 임시로 만들어 둔 파일 때문에 한동안 가려져 있었다 — 정리 후 재실행해서야 드러났다. *"내 머신에선 되는데"* 의 교과서적 사례다.
→ `catalog-service`·`chat-service` 의 **테스트 리소스**에 더미 값 파일을 추가해 빌드가 비밀 파일에 의존하지 않게 했다. 운영 값은 여전히 저장소 밖에서 주입된다.

**결과: `./gradlew build` BUILD SUCCESSFUL** — 비밀 파일 없이, 깨끗한 상태에서. 이번 작업에서 처음이다.

### B-19. readiness 는 UP 인데 서비스는 못 쓰는 상태 🟡(설계 이슈)
chat-service 를 MongoDB 없이 띄웠더니:
```
/actuator/health/readiness  →  {"status":"UP"}      ← k8s 는 트래픽을 보낸다
/actuator/health            →  {"status":"DOWN"}    ← 실제로는 채팅 기능 불가
```
Spring Boot 의 readiness 그룹은 기본적으로 `readinessState` 만 본다 — **의존성이 죽어도 UP 이다.** 게다가 `/actuator/health` 는 Mongo 연결을 기다리며 **10초 이상 응답하지 않았다**(짧은 타임아웃의 프로브라면 플랩을 유발한다).

이건 트레이드오프라 코드로 단정하지 않았다: readiness 에 공유 의존성을 넣으면 **일시적 장애에 전 레플리카가 동시에 NotReady** 가 되어 더 큰 사고가 된다. 대신 **탐지 장치**를 넣었다 — `ops/06-smoke-test.sh` 가 readiness 뿐 아니라 **종합 health 와 무응답까지 검사**해 "떴지만 못 쓰는" 상태를 배포 직후에 잡는다.

## 기동 확인 현황

| 서비스 | 결과 | 비고 |
|---|---|---|
| order-service | ✅ | 파티션 8개 · Flyway V1 |
| coupon-service | ✅ | B-13 수정 후 `coupon.coupon` 생성 확인 |
| gateway-server | ✅ | 429 레이트리미팅 · `/fallback` 503 실증 |
| catalog-service | ✅ | `product` 스키마 11 테이블 |
| pay-service | ✅ | 18.4s · `pay.order_outbox_event` 파티션 8개 |
| user-service | ✅ | B-11 수정 후 readiness/prometheus 200 |
| chat-service | ✅ | B-10·12·13·14 수정 후 기동. MongoDB 는 검증 환경에 없어 health=DOWN |
| cart-service | ✅ | B-14 수정 후 기동 |

## 기동 후 검증한 것

order-service 기동 상태에서 실제로 확인:

| 항목 | 결과 |
|---|---|
| `/actuator/health/liveness` · `/readiness` | `{"status":"UP"}` — 이번 작업에서 추가한 프로브가 실제 동작 |
| `/actuator/prometheus` | 메트릭 154줄 노출 — 관측성 스택이 스크레이프할 대상 확인 |
| `/v3/api-docs` | OpenAPI 3.1 문서 응답 — springdoc 도입 검증 |
| Flyway | `V1 partition payment outbox event` **success=true** |
| 파티션 | `payment_outbox_event` 하위 **파티션 8개**(월 7 + DEFAULT), 복합 PK `(id, created_at)` 확인 |

즉 **이번 작업에서 추가한 프로브·메트릭·OpenAPI·파티션 마이그레이션이 실제 런타임에서 동작함을 확인**했다.

- **gateway 런타임 검증**: `/fallback` 503, 레이트리미터가 실제 **429 반환**(70req 중 10회), Redis 버킷 키가 `ip:127.0.0.1` 로 생성 — 즉 이번 작업에서 배선한 **서킷브레이커·레이트리미터와 KeyResolver NPE 수정이 런타임에서 동작함을 확인**.
- **catalog 런타임 검증**: readiness UP, `product` 스키마 테이블 11개 생성, Flyway `V1`+`V2(processed_event)` 적용 성공.

## 남은 한계

- **Kafka 브로커 없이 기동했다.** 컨슈머는 연결 재시도 로그만 남기며 기동을 막지는 않는다. 따라서 **사가 흐름(주문→결제→재고) end-to-end 는 여전히 미검증**이다.
- **MongoDB 가 검증 환경에 없다.** chat-service 는 기동하지만 채팅 저장/조회 경로는 미검증이며 health=DOWN 상태다.
- **Elasticsearch 미검증** — 검색 플래그는 기본값 `postgres` 이고 ES 경로는 여전히 설계·스크립트 수준이다(docs/07, `ops/03-enable-es-search.sh`).
- **`@Disabled` 로 미룬 컨텍스트 테스트 6종**은 Testcontainers 도입 전까지 공백으로 남는다.
- 따라서 다음 단계는 여전히 **스테이징에서 전체 스택 기동 + 사가 1건 통과**다.

## 후속: 사가·관측성 실증에서 나온 버그 2건 (B-21 · B-22)

이 문서 이후 실제 Kafka 와 Prometheus/Zipkin 을 붙여 검증하면서 **또 2건이 나왔다**. 자세한 내용은
[docs/16](16-saga-e2e-and-observability-verification.ko.md) §2 에 있다.

- **B-21** 아웃박스 `created_at` 이 `Asia/Seoul` 로 하드코딩돼 다른 컬럼과 9시간 어긋남 — 이 컬럼이 **파티션 키**라 월 경계에서 이벤트가 잘못된 파티션에 들어간다(pay·order·cart·user 4개 서비스).
- **B-22** 공유 설정 `application-common.yml` 의 `spring.application.name: common` 이 각 서비스 이름을 덮어써, **모든 서비스가 스스로를 `common` 이라고 보고**하고 있었다. Zipkin 의 분산 추적이 사실상 무력화된 상태였다.

## 이 문서에서 얻어야 할 교훈

1. **"컴파일된다"·"단위 테스트가 통과한다"는 "뜬다"를 의미하지 않는다.** B-1~B-14 는 전부 기동해야만 보이는 버그였다.
2. **"떴다"조차 "동작한다"를 의미하지 않는다.** coupon 은 테이블 없이(B-13), chat 은 MongoDB 없이(B-19) "성공적으로" 떴다. **기동 로그의 WARN 을 끝까지 읽어야 한다.**
3. **빨간 테스트를 방치하면 테스트 전체가 무의미해진다.** 인프라가 필요한 테스트 6건이 항상 실패하는 바람에, 그 안에 섞여 있던 **진짜 제품 NPE 4건**(B-15)이 오랫동안 가려져 있었다.
4. **기동 테스트는 1회성이 아니다.** 1차에서 4개 서비스를 통과시킨 뒤에도, 2차에서 5건이 더 나왔다.
