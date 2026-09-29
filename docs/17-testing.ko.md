# 테스트 전략 — 단위 테스트 + Testcontainers

[日本語](17-testing.md) | **한국어**

> 🕰️ **상태(2026-09-27) — 일부 낡음.** 테스트 수 · 가드 수 · CI 잡 구성은 작성 당시 값입니다. 지금은 백엔드 1,637 · 프론트 930 · B-1~69, CI 는 commit → integration · acceptance → release-candidate([docs/36](36-deployment-pipeline.ko.md)).

> 작성일: 2026-07-28
> 배경: [docs/14](14-boot-test-findings.ko.md)·[docs/16](16-saga-e2e-and-observability-verification.ko.md)에서
> 드러난 27건의 결함은 **거의 전부 "실제로 띄워야만" 보이는 종류**였다. 그 검증을 사람 손이 아니라
> 빌드가 하게 만드는 것이 이 문서의 목적이다.

## 0. 왜 이 작업이 필요했나

이전 상태를 숫자로 보면 문제가 분명하다.

| 항목 | 이전 | 이후 |
|---|---|---|
| 전체 테스트 | 47건 | **126건** |
| 비활성화(@Disabled) | 6건 | **0건** |
| 테스트가 있는 모듈 | 8/11 | **11/11** |
| 실인프라 기동 검증 | ❌ 수동(`ops/06-smoke-test.sh`) | ✅ **빌드가 자동 수행** |
| 사가 **전 구간**(주문→결제→재고) | ❌ 수동(docs/16) | ✅ **서비스 컨테이너 3종으로 자동 검증** |
| 채팅 **전 구간**(STOMP→Kafka→저장→브로드캐스트) | ❌ 없음 | ✅ **WebSocket 클라이언트로 자동 검증** |
| 백업 복구 | ❌ 없음 | ✅ **PITR 복원 훈련이 CI 주간 스케줄로 실행**(ops/09) |
| CI | ❌ 없음 | ✅ **푸시/PR 마다 실행** |

특히 **비활성화 6건**이 문제였다. `@SpringBootTest contextLoads()` 가 실DB·Redis·키스토어를 요구해
인프라 없는 곳에서는 항상 실패했고, 그 빨간불이 상수처럼 켜져 있는 바람에 **진짜 단위 테스트의 신호까지 묻혔다.**
실제로 그 안에는 제품 NPE 4건(docs/14 B-15)이 섞여 있었는데 오랫동안 아무도 보지 못했다.

## 1. 두 갈래로 나눈다

Docker 가 없는 곳에서도 단위 테스트는 돌아야 하므로 통합 테스트는 `containers` 태그로 분리했다.

```bash
./gradlew test                    # 단위 테스트만 — Docker 불필요, ~1분
./gradlew test -PwithContainers   # 통합 테스트까지 — Docker 필요, ~4분
```

태그가 붙은 테스트는 기본 실행에서 자동으로 제외된다(`build.gradle`의 `excludeTags 'containers'`).

## 2. 회귀 테스트 — 고친 버그마다 하나씩

이번 작업에서 고친 결함은 **재발 방지 테스트로 고정**했다. 각 테스트 주석에 원래 장애가 적혀 있다.

| 테스트 | 고정하는 결함 |
|---|---|
| `ConfigurationGuardrailTest` (common) | **설정 결함 15종** — §3 참조 |
| `MessageMapperTest` (chat) | B-24 roomId/nickname 인자 뒤바뀜, createdAt 파싱 무방비 |
| `ProductMapperNullSafetyTest` (catalog) | B-15 nullable shipping·연관 컬렉션 NPE |
| `PageJacksonModuleTest` (catalog) | B-27 `Page` 캐시 역직렬화 불가(두 번째 검색부터 500) |
| `*OutboxEventCreatedAtTest` (order·pay·cart·user) | B-21 아웃박스 `created_at` 시간대 하드코딩(파티션 키!) |
| `CustomUserKeyResolverTest` (gateway) | 레이트리미터 키 NPE |
| `ChatStompIntegrationTest` (chat) | B-24 저장 누락 + STOMP 인증·명의 도용 방어 (§6.1) |

## 3. 설정 가드레일 — 가장 비싼 버그가 나온 곳

이 저장소에서 **가장 비싼 결함들은 코드가 아니라 설정**에 있었다. 리뷰로는 거의 걸러지지 않으므로
`common/src/test/.../ConfigurationGuardrailTest` 가 빌드마다 검사한다:

| 검사 | 막는 장애 |
|---|---|
| 프로필 전용 파일에 `spring.profiles.active` 금지 | B-1 — 9개 서비스가 기동 즉시 중단 |
| 공유 설정에 `spring.application.name` 금지 | B-22 — 모든 서비스가 스스로를 `common` 이라 보고, 분산 추적 무력화 |
| `es-settings.json` 중복 `settings` 래퍼 금지 | B-26 — ES 인덱스 생성 자체가 실패 |
| 애널라이저 이름 일치 | 문서가 참조하는 애널라이저 미정의 방지 |
| PostgreSQL + MySQLDialect 조합 금지 | B-12 — 모든 테이블 생성 실패(WARN 으로만 남음) |
| 환경변수 이름 오타(`STOE`) 금지 | B-10 — placeholder 해석 실패로 기동 불가 |
| 설정에 개발자 PC 절대 경로 금지 | B-28 — `file:E:/github/...` 가 7개 서비스에 박혀 있었다(docs/18 §1) |
| Kafka SSL 스토어 형식 하드코딩 금지 | B-29 — `.p12` 를 `"JKS"` 라 선언, mTLS 켜는 순간 전 서비스 기동 불가 |
| Redis 비밀번호의 빈 값 기본화 금지 | B-30 — 인증이 조용히 꺼진다. 게이트웨이·order·cart 에서 **세 번** 재발했다 |
| Kafka SSL 프로퍼티는 공유 설정 한 곳에서만 | 주입 경로가 갈라져 환경변수가 죽는 것 |
| 서비스 설정이 요구하는 환경변수를 매니페스트가 전부 제공 | B-31 — `AMAZON_ACCESS_KEY` 가 어느 매니페스트에도 없어 두 서비스가 placeholder 해석 실패로 기동 불가였다 |
| `데이터소스 × 풀크기 × 레플리카 ≤ max_connections` | B-32 — 산술적으로 넘치는 풀 설정. 부하가 걸린 뒤에야 드러난다 |
| `ddl-auto` 는 `validate`/`none` 만 + 베이스라인 마이그레이션 존재 | B-33 — Hibernate 가 스키마를 소유하면 변경 이력이 없고, 운영 DB 에 검토 없는 DDL 이 나가며, **제약을 지우지 않는다**(docs/20) |
| 확장 불가 서비스는 replicas 1 + HPA 제외 | B-34 — chat 은 인메모리 STOMP 브로커, eureka 는 standalone. 늘리면 **조용히** 깨진다: 파드는 Running 인데 사용자만 메시지를 못 받는다(docs/21 §2) |

> 이 가드레일들은 **실제로 동작함을 확인했다.** `spring.application.name: common` 을 되돌려 넣자
> 해당 테스트가 즉시 실패했다. 실패하지 않는 테스트는 테스트가 아니다.

## 4. Testcontainers 통합 테스트

| 테스트 | 컨테이너 | 확인하는 것 |
|---|---|---|
| `CatalogServiceApplicationTests` | PG, Redis | B-8 빈 DB 에서 스키마 생성, B-9 멱등성 원장 존재 |
| `OrderServiceApplicationTests` | PG, Redis | 기동 + **아웃박스 파티션 생성**(docs/09 P0) |
| `PayServiceApplicationTests` | PG, Redis | 기동 + `pay` 스키마 테이블 생성 |
| `CartServiceApplicationTests` | PG, Redis | 기동 + `cart` 스키마 |
| `CouponServiceApplicationTests` | PG, Redis | 기동 + B-13 `coupon` 스키마 테이블(조용한 실패 방지) |
| `UserServiceApplicationTests` | PG, Redis | 기동 + **B-11 액추에이터가 401 이 아님** + 업무 API 는 401 유지 |
| `GatewayServerApplicationTests` | Redis | 기동 + 라우트 로딩 + KeyResolver 빈 존재 |
| `EurekaServerApplicationTests` | (없음) | 단독 모드 기동 |
| `ChatServiceIntegrationTest` | **MongoDB**, PG, Redis | 저장·조회, 커서 페이지네이션, B-24 회귀 2종 |
| `EsProductSearchServiceIntegrationTest` | **Elasticsearch**, PG, Redis | 색인 → 검색 → 결과 |
| `ProductUpdateSagaIntegrationTest` | **Kafka**, PG, Redis | §4.1 — 사가 재고 차감·멱등성·DLQ |
| `SagaEndToEndTest` (e2e-tests) | **서비스 3종 + Kafka, PG, Redis** | §4.3 — 사가 전 구간 |
| `ChatStompIntegrationTest` | **Kafka, MongoDB, PG, Redis** | §6.1 — STOMP 인증부터 브로드캐스트·저장까지 |

### 4.1 사가 재고 차감을 실제 Kafka 로 검증한다
docs/16 §1 에서 손으로 확인했던 것을 빌드가 대신 한다. 사가 전 구간은 서비스 3개의 컨텍스트가
동시에 필요해 한 테스트로 묶기 어렵지만, **돈이 걸린 구간은 여기다** — 재고가 두 번 차감되면 손실이다.

| 테스트 | 확인하는 것 |
|---|---|
| 단건 차감 | 메시지 1건이 재고를 주문 수량만큼 정확히 줄인다 |
| **재전달 멱등성** | 같은 eventId 를 3회 더 보내도 재고가 그대로다. 원장에 1건만 남는다 |
| 다중 항목 | 항목이 여러 개면 전부 반영된다(예전엔 첫 항목만 처리하고 return) |
| 서로 다른 eventId | 멱등성이 정상 처리까지 막지는 않는다 |
| 미존재 상품 | 다른 재고를 건드리지 않는다 |
| 연속 주문 | 누적 차감된다 |
| **DLQ 격리** | 망가진 메시지 뒤의 정상 메시지가 처리된다(컨슈머가 갇히지 않는다) |

> ⚠️ 비동기 컨슈머 테스트의 함정 두 가지를 실제로 밟았고, 둘 다 코드에 남겨 뒀다:
> - **파티션 배정 전 발행**: 컨슈머가 그룹에 합류하기 전에 보낸 메시지는 못 볼 수 있다.
>   `ContainerTestUtils.waitForAssignment` 로 기다린다. 이걸 빼면 간헐적으로 실패한다.
> - **테스트 간 간섭**: 컨슈머가 비동기라 앞 테스트의 메시지가 뒤 테스트의 초기화 후에 처리될 수 있다.
>   테스트마다 **전용 상품**을 쓰고 재고를 초기화하지 않는다. poison message 테스트는 재시도 백오프로
>   같은 파티션을 지연시키므로 `@Order` 로 **맨 뒤**에 둔다.

### B-11 은 특히 짚어 둘 만하다
`user-service` 의 액추에이터가 401 을 돌려주면 **k8s 는 파드를 영원히 NotReady 로 두고 Prometheus 는 그 서비스만 못 긁는다.**
그런데 이건 단위 테스트로 잡기 어렵다 — 실제로 띄워서 HTTP 로 때려 봐야 한다. 그래서 이 테스트는
`RANDOM_PORT` 로 띄우고 `@AutoConfigureObservability` 를 켠다.

> `@SpringBootTest` 는 **기본적으로 메트릭 내보내기를 끈다.** 그러면 `/actuator/prometheus` 가 아예
> 등록되지 않아 "보안 때문에 401" 인지 "엔드포인트가 없어서" 인지 구분할 수 없다. 운영과 같은 조건으로
> 검증하려면 이 애노테이션이 필요하다 — 이것 자체가 테스트를 쓰다가 배운 함정이다.

### 4.3 사가 전 구간을 서비스 컨테이너로

`ops/06-smoke-test.sh` 가 "수동 확인 항목"으로 남겨 두었던 흐름 전체를 빌드가 검증한다.

**서비스 3개를 한 JVM 에 올릴 수는 없다** — 각 모듈의 `application-local.yml` 이 클래스패스에서 충돌한다.
그래서 미리 빌드한 부트 jar 를 최소 이미지에 얹어 **컨테이너로 띄운다**. 배포되는 산출물 그대로를
검증한다는 부수 효과도 있다(`e2e-tests` 모듈).

```
order 토픽 ─▶ pay-service ─▶ pay.payment + 아웃박스 ─(스케줄러)─▶ payment 토픽
                                                                  │
                order-service ◀───────────────────────────────────┘
                    ├─▶ orders.order_status: NOT_PROCESSED → PAID
                    └─▶ product-update 토픽 ─▶ catalog-service ─▶ 재고 차감
```

단계마다 DB 를 직접 확인한다: 결제 생성 → 아웃박스 `COMPLETED` → 주문 `PAID` → 재고 차감 →
멱등 원장에 `order-9100` 기록. 마지막으로 같은 결제를 재전송해도 재고가 더 줄지 않는지 본다.

> 이 테스트를 만들면서 **배포 산출물의 실제 결함**이 하나 드러났다(§4.4).

### 4.4 컨테이너 이미지가 기동하지 못하던 문제

서비스를 컨테이너로 띄우자마자 전부 이 오류로 죽었다:

```
Config data resource 'class path resource [kafka-secrets.properties]' does not exist
```

각 서비스가 그 파일을 **필수로** import 하는데, 비밀번호를 담고 있어 `.gitignore` 대상이다.
즉 **새로 클론한 저장소도, 빌드한 컨테이너 이미지도 기동할 수 없었다.** 앞서 테스트 클래스패스에는
더미 파일을 넣어 두었지만(docs/14 B-20), 정작 **배포되는 jar 는 그대로 막혀 있었다.**

→ import 를 `optional:` 로 바꾸고, 비밀이 아닌 Kafka 기본값을 `application-common.yml` 에 두었다.
운영에서는 시크릿 파일이나 `KAFKA_*` 환경변수가 이 값을 덮어쓴다.

## 4.2 CI

`.github/workflows/build.yml` 이 푸시/PR 마다 세 잡을, 주 1회 복원 훈련을 돌린다:

| 잡 | 내용 |
|---|---|
| `unit` | `./gradlew build` — Docker 불필요, 빠른 피드백 |
| `integration` | `./gradlew test -PwithContainers` — 실제 인프라 기동 (unit 통과 후에만) |
| `restore-drill` | `ops/09-restore-drill.sh` — **주 1회** PITR 복원 훈련(docs/15 §0.1) |
| `frontend` | `npm ci && npm run build` |

테스트 리포트는 실패 시에도 아티팩트로 업로드된다.

> 이 저장소의 테스트 스위트는 한때 **.gitignore 된 비밀 파일에 의존**해 새로 클론하면 통과할 수
> 없는 상태였는데, 아무도 몰랐다 — 자동으로 돌려 보는 곳이 없었기 때문이다(docs/14 B-20).

## 5. 제약된 네트워크에서 실행하기

이 저장소를 검증한 환경은 Docker Hub 레지스트리 CDN 이 정책상 막혀 있었다. 그래서 이미지 출처를
바꿀 수 있게 해 두었다:

```bash
export TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX=mirror.gcr.io/
export DOCKER_HOST=unix:///var/run/docker.sock
./gradlew test -PwithContainers
```

값을 주지 않으면 평소대로 Docker Hub 를 쓴다. `build.gradle` 이 `DOCKER_HOST`·`DOCKER_API_VERSION` 등도
테스트 JVM 으로 전달한다.

> ⚠️ Spring Boot 3.4.1 이 고정하는 Testcontainers 1.20.4 는 docker-java 가 API 1.32 로 붙어
> 최신 Docker Engine(29.x, 최소 1.40 요구)에서 `client version 1.32 is too old` 로 **컨테이너를 아예 못 띄운다.**
> `testcontainers-bom:1.21.4` 로 올려 회피했다.

## 6. 여전히 남은 공백

정직하게 적어 둔다.

| 항목 | 상태 |
|---|---|
| **한국어 형태소 분석(nori)** | ❌ — 플러그인을 어디서도 구할 수 없다. Maven Central 에 `analysis-kuromoji` 는 있으나 `analysis-nori` 는 없고, Lucene 의 `lucene-analysis-nori` 에는 Elasticsearch 플러그인 래퍼 클래스가 없어 **플러그인을 직접 작성**해야 한다. 그건 이 저장소가 떠안을 유지보수 부담이라 하지 않았다. nori 가 설치된 환경에서 재확인이 필요하다. |
| **컨슈머 멱등성 확산** | ⚠️ — 12개 중 5개. 나머지는 성격상 재실행이 무해하거나(cart-empty) 이미 상태 검사로 보호된다(order 사가). 일괄 적용은 의식(ceremony)일 뿐이라 하지 않았다. |
| **채팅 WebSocket/STOMP 경로** | ✅ 해결 — `ChatStompIntegrationTest` 가 전 구간을 밟는다(아래 §6.1). |
| **Grafana 대시보드 렌더링** | ❌ — 쿼리가 데이터를 돌려주는 것까지는 확인했지만(docs/16), 패널이 실제로 그려지는지는 확인하지 못했다(바이너리 다운로드가 막혀 있었다). |

### 6.1 채팅 STOMP 전 구간 — 이제 실제로 밟는다

그동안 채팅 검증은 전부 리포지토리를 직접 호출하는 방식이었다. 즉 **사용자가 실제로 지나는 길은
한 번도 실행된 적이 없었다.** 그 구간에는 JWT 인증 인터셉터, 세션 속성 기반 신원 확인, Kafka 왕복,
MongoDB 저장이 전부 들어 있다 — 이 저장소에서 가장 결함이 많이 나온 종류의 코드다.

`ChatStompIntegrationTest` 는 MongoDB·PostgreSQL·Redis·Kafka 컨테이너 위에서 실제 WebSocket
클라이언트(SockJS)로 붙어 4가지를 확인한다:

| 검사 | 막는 사고 |
|---|---|
| 토큰 없는 CONNECT 거절 | 아무나 임의의 방을 구독해 남의 대화를 실시간으로 읽는 것 |
| 다른 키로 서명된 토큰 거절 | 토큰 위조 |
| SEND → Kafka → 브로드캐스트 → **MongoDB 저장** | docs/16 B-24 회귀 — 전달만 되고 이력이 비는 상태 |
| 바디의 email 을 무시하고 토큰 subject 사용 | 남의 명의로 메시지 보내기 |

마지막 항목이 특히 중요하다. `StompController` 가 클라이언트가 보낸 `email` 을 세션의 검증된 값으로
덮어쓰는데, 이 방어는 **STOMP 프레임을 실제로 보내 봐야만** 검증된다. 단위 테스트로는 닿지 않는다.

## 7. 교훈

1. **꺼 둔 테스트는 없는 테스트보다 나쁘다.** 항상 빨간불이면 사람들은 전체를 무시하게 되고, 그 아래 진짜 버그가 숨는다.
2. **설정은 코드보다 위험한데 리뷰는 코드만 본다.** 가드레일 테스트가 그 격차를 메운다.
3. **테스트도 검증해야 한다.** 버그를 되돌려 넣어 실패하는지 확인하지 않은 가드레일은 장식이다.
4. **테스트 환경은 운영과 같은 조건이어야 한다.** `@AutoConfigureObservability` 를 빼먹으면 "통과했지만 아무것도 검증하지 않은" 테스트가 된다.
