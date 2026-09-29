# 사가 E2E · 관측성 스택 실증 검증

[日本語](16-saga-e2e-and-observability-verification.md) | **한국어**

> 🕰️ **상태(2026-09-27) — 이전 기록.** 경보 수신 채널 · 채팅 저장 · 배치 메타 스키마는 이후 해결됐습니다. 본문 끝의 "남은 것" 일부는 같은 문서 §4·§7 과 어긋납니다.

> 작성일: 2026-07-27
> 배경: [docs/14](14-boot-test-findings.ko.md)까지는 "서비스가 뜬다"까지만 확인했고, **사가가 실제로 도는지**,
> **관측성 스택이 실제로 관측하는지**, **채팅·검색이 실제로 동작하는지**는 미검증으로 남아 있었다.
> 이 문서는 실제 Kafka·MongoDB·Elasticsearch·Prometheus 를 띄워 검증한 기록이다.
> **결과: 사가·관측성은 동작했고, 채팅 저장과 ES 검색 경로는 한 번도 동작한 적이 없었다**(B-23~B-27).
> 관련: [아키텍처/장애 시나리오](03-architecture-and-reliability.ko.md) · [DLQ·멱등성](12-dlq-rollout-idempotency-alerts.ko.md)

## 0. 검증 환경

| 구성요소 | 방식 | 비고 |
|---|---|---|
| Kafka 3.7.2 | KRaft 단일 노드, `localhost:9092` | 토픽 9종 + `.DLT` 9종 생성 |
| PostgreSQL 16.13 | 로컬 인스턴스, DB 8개 | 서비스별 DB 분리 그대로 재현 |
| Redis 7 | 로컬, 비밀번호 설정 | |
| Prometheus 2.53 / Alertmanager 0.27 / Zipkin 3.4 | 로컬 바이너리 | 저장소의 실제 설정 파일을 그대로 사용 |
| MongoDB 8.0.23 | conda-forge 패키지 | 드라이버(5.2.1) 요구사항을 만족하는 정품 서버 |
| Elasticsearch 8.15.5 | Maven Central `integ-test-zip` | **클라이언트와 동일 버전** |
| 애플리케이션 8종 | 로컬 JVM | TLS·Eureka 만 비활성화, 나머지는 `local` 프로필 그대로 |

> ⚠️ Docker 이미지 레지스트리(CDN)가 검증 환경의 네트워크 제한으로 막혀 있어 컨테이너 대신 바이너리로 띄웠다.
> MongoDB 와 Elasticsearch 도 공식 배포처는 막혀 있었으나, **conda-forge** 와 **Maven Central 의 integ-test-zip**
> 이라는 우회 경로로 정품 서버를 확보했다 — 드라이버나 클라이언트 버전을 낮추지 않고 검증했다는 뜻이다.

## 1. 사가 E2E — 주문 → 결제 → 재고 ✅

`ops/06-smoke-test.sh` 가 "수동 확인 항목"으로 남겨 두었던 흐름을 실제로 통과시켰다.

### 1.1 검증 시나리오
`orders.orders` 에 `NOT_PROCESSED` 주문(7010, 상품 100번 4개)을 만들고, `order` 토픽에 결제 완료 이벤트를 넣었다.

```
order 토픽 (PaymentRequest, COMPLETED)
   └─> pay-service: pay.payment 생성 (TSID PK 869715992679896062)
        └─> pay.order_outbox_event 기록 → 스케줄러가 발행 → outbox_status=COMPLETED
             └─> payment 토픽
                  ├─> order-service: "Processing successful payment for order id: 7010"
                  │     └─> orders.orders.order_status : NOT_PROCESSED → **PAID**
                  ├─> product-update 토픽 → catalog-service: 재고 **45 → 41**, sold **5 → 9**
                  └─> cart-empty 토픽 → cart-service
```

**결과: 주문 상태 전이와 재고 차감이 정확히 일치했다**(주문 수량 4 = 재고 감소 4 = 판매량 증가 4).
아웃박스 → 스케줄러 → 토픽 → 소비 → 상태 전이의 **트랜잭셔널 아웃박스 패턴 전 구간이 실제로 동작**한다.

### 1.2 멱등성 (docs/12 §2) ✅
같은 `eventId` 로 `product-update` 를 **3번 더** 재전송:

| 항목 | 결과 |
|---|---|
| 재고 | 47 → **47** (변화 없음) |
| 로그 | `Duplicate product-update event ... skipping` **3회** |
| `product.processed_event` | `order-9001` 1행만 유지 |

Kafka 재전달/사가 재실행에도 **재고가 이중 차감되지 않는다**는 것이 실증됐다.
또한 사가가 실제로 만든 이벤트의 키가 `order-7010` 으로 기록되어, 멱등 키가 **테스트용이 아니라 실제 생산자 경로에 배선**되어 있음도 확인했다.

### 1.3 다중 항목 주문 ✅
docs/02 에서 고친 "첫 항목만 처리하고 return 하던" 버그를 실제로 확인했다. 2개 항목 주문(상품 100번 2개 + 101번 5개):

| 상품 | 이전 | 이후 |
|---|---|---|
| Saga Test Shirt | 47 | **45** (-2) |
| Saga Test Pants | 80 | **75** (-5) |

수정 전 코드였다면 두 번째 항목은 조용히 누락됐을 것이다.

### 1.4 DLQ 격리 (docs/11 §3) ✅
의도치 않게 가장 좋은 DLQ 테스트가 됐다. 세 가지 실패를 모두 DLT 가 잡아냈다:

| 실패 | 결과 |
|---|---|
| 타입 헤더 없는 메시지 | 재시도 후 `product-update.DLT` 로 격리 |
| 잘못된 enum 값(`orderStatus: PENDING`) | `DeserializationException` 이 헤더에 담겨 격리 |
| 다운스트림 장애(user-service 다운 → Feign 연결 거부) | `cart-empty.DLT` 로 격리 |

DLT 레코드 헤더에 **원인 사슬이 그대로 남는다**:
```
exception-message: Listener method '...CartEmptyConsumer.receive(...)' threw exception
Caused by: BatchListenerFailedException: cart-empty
Caused by: feign.RetryableException: Connection refused executing GET https://localhost:8080/api/auth/user/email/42
```
즉 **poison message 가 컨슈머를 영구 정지시키지 않고**, 운영자가 원인을 바로 읽을 수 있다.

## 2. 검증 중 발견한 새 버그 2건

### B-21. 아웃박스 `created_at` 이 파티션 키인데 시간대가 어긋나 있었다 🔴 — 4개 서비스

같은 사건인데 두 테이블의 시각이 **정확히 9시간** 달랐다:
```
UTC now                  : 2026-07-27 23:03:51
pay.payment.created_at   : 2026-07-27 23:02:20     ← JPA 감사 = JVM 기본 시계
pay.outbox.created_at    : 2026-07-28 08:02:20     ← 하드코딩된 Asia/Seoul
```

원인은 아웃박스 엔티티의 `LocalDateTime.now(ZoneId.of("Asia/Seoul"))` 하드코딩이다. 문제는 이 컬럼이 단순 기록용이 아니라 **[docs/09](09-postgresql-partitioning-sharding.ko.md)에서 만든 월 단위 RANGE 파티션의 파티션 키**라는 점이다.

- 매월 말 **9시간 동안 생성된 이벤트가 다음 달 파티션에 들어간다.**
- 그 상태로 보존 정책(`DETACH`/`DROP`)을 돌리면 **아직 살아 있어야 할 행을 건드리거나, 지워야 할 행을 놓친다.**
- 서비스 간 시각 비교(주문 vs 결제)도 어긋나 장애 분석이 틀어진다.

→ 4개 서비스(pay·order·cart·user)의 아웃박스 엔티티를 나머지 컬럼과 **같은 시계**(`LocalDateTime.now()`)로 통일했다.
운영에서는 JVM·DB 를 UTC 로 맞출 것을 문서에 남긴다.

### B-22. 모든 서비스가 스스로를 `common` 이라고 보고하고 있었다 🔴

Zipkin 에 트레이스는 들어오는데 **서비스 이름이 전부 하나**였다:
```
GET /api/v2/services  →  ["common"]
```
확인해 보니 메트릭도 마찬가지였다:
```
catalog-service /actuator/prometheus  →  application="common"
order-service   /actuator/prometheus  →  application="common"
```

원인: **모든 서비스가 공유하는** `common/src/main/resources/application-common.yml` 이 `spring.application.name: common` 을 선언하고 있었다. 각 서비스가 `application-local.yml` 에서 자기 이름을 선언해도 이 값이 이겼다.

피해 범위가 넓다:
- **Zipkin**: 모든 스팬이 한 서비스로 뭉쳐 **분산 추적의 존재 이유가 사라진다.** 이번 작업에서 도입한 추적(R-4)이 사실상 무력화돼 있었다.
- **Prometheus**: `management.metrics.tags.application` 이 전부 `common` → 이 태그로 거는 질의·경보는 서비스를 구분하지 못한다.
- **Eureka**: 서비스들이 같은 이름으로 등록될 위험. 게이트웨이의 `lb://catalog-service` 라우팅 근간이 흔들린다(이번 테스트는 Eureka 를 끄고 진행해 직접 확인하지는 못했다).

→ 공유 설정 파일에서 `spring.application.name` 을 제거했다. 이름은 각 서비스가 선언한다.
→ 수정 후 재기동 검증:
```
catalog-service /actuator/prometheus  →  application="catalog-service"
order-service   /actuator/prometheus  →  application="order-service"
Zipkin /api/v2/services →  ["catalog-service", "common", "order-service"]
```
("common" 은 수정 전 남은 스팬 + 아직 구 jar 로 돌던 나머지 서비스)

> 이 버그는 **서비스를 띄우는 것만으로는 안 보이고, 수집기를 실제로 붙여야만 보인다.**
> docs/14 의 교훈("떴다 ≠ 동작한다")이 관측성 계층에도 그대로 적용된 사례다.

## 3. 관측성 스택 ✅

저장소의 실제 설정 파일(`observability/`)을 그대로 사용해 검증했다.

### 3.1 Prometheus — 수집
```
9/9 targets UP
  prometheus · gateway-server · user-service · catalog-service · cart-service
  order-service · chat-service · pay-service · coupon-service
```
**user-service 가 UP 인 것이 특히 중요하다** — docs/14 B-11 에서 고친 액추에이터 401 문제가 실제 스크레이퍼 앞에서 재현되지 않음을 확인한 것이다.

수집된 시리즈 표본:

| 질의 | 결과 |
|---|---|
| `up` | 9 series |
| `jvm_memory_used_bytes` | 64 series |
| `http_server_requests_seconds_count` | 8 series |
| `resilience4j_circuitbreaker_state` | 6 series |

### 3.2 경보 규칙 — 로딩 · 발화 · 해소 ✅
`observability/alert-rules.yml` 5종이 모두 로딩됐고, **양방향으로 동작**했다.

1. 서비스가 없을 때 → `ServiceDown` **pending**
2. 8종 기동 후 → 전부 **inactive** (해소됨)
3. coupon-service 를 강제 종료 → `CircuitBreakerOpen` **firing** + `High5xxRate` pending

특히 3번은 인위적 조작 없이 **인과가 그대로 드러난 결과**다. 서비스 하나를 죽였더니 게이트웨이의 서킷브레이커가 열렸고(이번 작업에서 배선한 resilience4j), 그 상태를 경보 규칙이 잡아냈다. **복원력 장치와 관측 장치가 서로를 증명했다.**

### 3.3 Alertmanager — 전달 ✅
```
amtool check-config observability/alertmanager.yml  →  SUCCESS
Prometheus /api/v1/alertmanagers  →  active: http://localhost:9093/api/v2/alerts
Alertmanager /api/v2/alerts       →  1 alert
Alertmanager /api/v2/alerts/groups→  receiver=default
                                     groupLabels={alertname, service}
```
규칙 발화 → 라우팅 → 수신자 그룹화까지 **전 구간이 연결됨을 확인**했다.

> ⚠️ 수신 채널은 여전히 플레이스홀더다. 실제 Slack/웹훅으로 바꾸지 않으면 **아무도 알림을 받지 못한다**(`ops/README.md` 주의사항).

### 3.4 Zipkin — 분산 추적 ✅(B-22 수정 후)
스팬 수집·조회 정상. B-22 수정 전에는 모든 스팬이 `common` 하나였고, 수정 후 서비스별로 분리됐다.

### 3.5 Grafana — 대시보드 질의 검증(간접) ⚠️
Grafana 배포본은 검증 환경의 네트워크 제한으로 받지 못해 **실행하지 못했다.** 대신 대시보드가 실제로 그려질 수 있는지를 다른 방식으로 확인했다:

- `ecommerce-overview.json` **JSON 유효**, 패널 6개
- **패널 6개의 PromQL 질의를 살아 있는 Prometheus 에 그대로 실행 → 전부 데이터 반환**

| 패널 질의 | 결과 |
|---|---|
| `up{job="spring-actuator"}` | 8 series |
| `sum by (service) (rate(http_server_requests_seconds_count[5m]))` | 8 series |
| 5xx 비율 | 2 series |
| p99 지연 `histogram_quantile(0.99, ...)` | 8 series |
| 힙 사용률 | 8 series |
| `resilience4j_circuitbreaker_state{state="open"}` | 2 series |

즉 **대시보드가 참조하는 지표가 실제로 존재하고 값을 반환한다.** 렌더링 자체만 미확인이다.
(대시보드가 `service` 라벨로 묶어 놓은 덕에 B-22 의 `application` 라벨 오염에는 영향받지 않았다.)

## 4. MongoDB 채팅 경로 ✅ (2차 시도에서 서버 확보)

1차에서 "서버를 구할 수 없다"고 적었으나, **conda-forge 패키지 저장소가 열려 있어 MongoDB 8.0.23 서버를 확보**했다
(Docker 레지스트리·`repo.mongodb.org`·미러는 여전히 차단). 드라이버를 낮추지 않고 **정품 서버로** 검증했다.

### B-23. Mongo 클라이언트가 TLS 를 하드코딩 🔴
`MongoSslConfig` 가 `.applyToSslSettings(b -> b.enabled(true)...)` 로 **TLS 를 고정**하고 인증서 로딩도 무조건 실행했다.
연결 문자열에 `?ssl=false` 를 줘도 무시되므로 **TLS 를 쓰지 않는 환경에서는 chat-service 를 아예 띄울 수 없다**
(로컬 개발, TLS 를 사이드카/프록시에서 종단하는 배포). Redisson 의 `rediss://` 하드코딩(B-14)과 같은 부류다.
→ 연결 문자열의 `ssl` 값을 따르도록 수정(미지정 시 기존대로 TLS 사용 — 안전한 기본값).
→ **검증**: 수정 후 chat-service 기동, `/actuator/health` 가 `DOWN` → **`UP`** 으로 바뀌었다(docs/14 B-19 에서 지적한 그 상태가 해소).

### 검증 결과
| 항목 | 결과 |
|---|---|
| 기동 | `Started ChatServerApplication in 25.2s`, health **UP** |
| 대화 이력 조회 | `GET /api/chat/room/{roomId}?cursor=-1` → 3건, **id 역순 정렬 정확** |
| 커서 페이지네이션 | `cursor=1003` → `1002, 1001` **정확** |
| Spring Data 매핑 | `_id` ↔ `id`, `createdAt`(문자열 epoch) → long 변환 정상 |

### B-24. 채팅 저장 경로의 결함 3건 🔴

문제는 **읽기가 아니라 쓰기**에 있었다.

1. **저장 자체가 호출되지 않는다.** MongoDB 에 쓰는 코드는 `ChatService.saveMessage()` **단 한 곳인데, 이 메서드를 호출하는 곳이 저장소 전체에 없다.**
   실제 흐름은 `STOMP → sendMessage() → Kafka(chat) → MessageConsumer → WebSocket 브로드캐스트` 로 끝난다.
   즉 **실시간 전달은 되지만 어떤 메시지도 저장되지 않는다.** 대화 이력 조회 API 와 커서 페이지네이션은 영원히 빈 결과를 돌려준다.
2. **인자 순서가 뒤바뀌어 있다.** `ChatMessage.get(type, nickname, email, roomId, ...)` 에 `(type, roomId, email, nickname, ...)` 을 넘긴다.
   저장이 살아나더라도 **roomId 자리에 닉네임이 들어가** `findByRoomId` 로 영영 조회되지 않는다. → 수정.
3. **`createdAt` 파싱이 무방비다.** `Long.parseLong(chat.getCreatedAt())` 이라 스키마리스 저장소에 값이 빠진 문서가 하나만 있어도
   **방 전체 이력 조회가 500** 이 된다. → 실제로 재현했고(문서 1건 삽입 → HTTP 500), 파싱 실패 시 해당 메시지만 0 으로 떨어지도록 수정. 재검증 결과 **200 + 4건 정상 반환**.

> ✅ **후속 조치(2026-07-28): 1번을 해결했다.** 사용자 확인을 받아 **소비자 측**(`MessageConsumer`)에서 저장하도록 배선했다 —
> 컨슈머 그룹 덕에 인스턴스를 늘려도 메시지당 한 번만 저장되고, 저장 실패는 Kafka 재시도/DLQ 경로를 그대로 탄다.
> 배선하면서 새 위험이 하나 생겼는데(재전달 시 중복 저장) 함께 막았다 — 매퍼가 생산자 시퀀스 id 를
> 문서 `_id` 로 싣지 않아 MongoDB 가 매번 새 ObjectId 를 만들고 있었다. id 를 실어 `save()` 를 upsert 로 만들었다.
> 재전달 3회에도 문서가 한 건인지 통합 테스트로 고정했다(docs/17).

## 5. Elasticsearch 검색 경로 ✅ (2차 시도에서 서버 확보)

ES 서버 역시 확보했다 — **Maven Central 의 `org.elasticsearch.distribution:integ-test-zip`** 은 라이선스 변경 이후 버전도 계속 배포되고 있어,
**클라이언트와 정확히 같은 8.15.5** 를 받아 띄웠다(`artifacts.elastic.co` 는 차단).

**이 경로는 지금까지 한 번도 동작한 적이 없었다.** 독립적인 결함 4건이 순서대로 막고 있었다.

### B-25. Spring Batch 메타데이터 스키마가 생성되지 않는다 🔴
```
ERROR: relation "meta.batch_job_instance" does not exist
```
`initialize-schema: never` + `table-prefix: meta.BATCH_` 인데 **`meta` 스키마를 만드는 곳이 없다**(주석에 "미리 실행" 이라고만 적혀 있다).
따라서 신규 환경에서는 **모든 배치 잡이 실패**한다 — ES 색인 잡도, CSV 상품 임포트도. `ops/03-enable-es-search.sh` 는 이 잡을 호출하므로 ES 전환 자체가 불가능했다.
덧붙여 배치 메타데이터는 `spring.datasource-meta`(metadb)가 아니라 **기본 데이터소스(productdb)** 에 기록된다 — 설정 의도와 실제가 어긋나 있다.
→ 이번엔 스키마를 수동 생성해 진행했다. **영구 수정은 하지 않았다**(어느 DB 가 배치 메타를 소유할지가 설계 결정이라 §7 참조).

### B-26. 색인 잡을 막던 결함 2건 🔴
1. **`es-settings.json` 의 구조 오류** — `@Setting(settingPath=...)` 은 settings **본문**을 기대하는데 파일이 최상위에 `"settings"` 로 한 번 더 감싸고 있었다.
   그 결과 ES 가 `index.settings.analysis...` 라는 설정을 받고
   `unknown setting [index.settings.analysis.tokenizer...]` 로 **인덱스 생성 자체가 실패**했다. nori 설치 여부와 무관한 실패다. → 래퍼 제거.
2. **`MultipleBagFetchException`** — 리더 JPQL 이 `Product.subcategories` 와 `ProductSku.sizes` 두 개의 List(bag)를 동시에 `JOIN FETCH` 해
   `productIndexingStep` 이 **매번 FAILED**(readCount=0)였다. → `s.sizes` 패치를 빼고 `@BatchSize(100)` 으로 배치 로딩.

**수정 후**: `productIndexingStep status=COMPLETED, readCount=2, writeCount=2`, 색인 문서에 SKU·사이즈까지 정상 포함.

### B-27. 검색은 첫 호출만 성공하고 이후 캐시 히트마다 500 🔴
```
1회차 → 캐시 미스 → 정상 응답 + 캐시 저장
2회차 → 캐시 히트 → SerializationException → HTTP 500   (TTL 60분 내내)
```
`SearchResultDto` 가 담은 `Page<ProductDto>`(`PageImpl`)에 기본 생성자도 `@JsonCreator` 도 없어 **Redis 캐시에서 되읽을 수 없었다.**
→ 응답 형태를 바꾸지 않기 위해 `PageJacksonModule`(PageImpl 전용 역직렬화기)을 추가하고 캐시 직렬화기에 연결했다.
→ **검증**: 같은 검색어 3회 연속 호출 전부 200 + 동일 결과(수정 전에는 2회차부터 500).

### 최종 검증 — 실제 검색 성공
```
GET /api/search?search=패딩
→ {"product":{"content":[{"name":"겨울 패딩 자켓","brand":"NorthPeak",
     "skus":[{"sku":"SKU-W-1","sizes":[{"size":"M","quantity":30,"price":99.99}]}] ...
```
PostgreSQL → 배치 색인 → ES 인덱스 + alias(`product-alias`) → `EsProductSearchService` 조회까지 **전 구간이 처음으로 통과**했다.

> ⚠️ **한계**: `nori`/`kuromoji` 플러그인은 Maven Central 에 없어 설치하지 못했다. 검증 중에는 두 토크나이저를
> `standard` 로 임시 치환해 **배선(색인→질의→응답)** 을 확인했고, **커밋된 `es-settings.json` 은 nori/kuromoji 원본 그대로**다
> (구조 오류만 수정). 따라서 **한국어 형태소 분석 품질 자체는 미검증**이며, 플러그인이 설치된 환경에서 재확인이 필요하다.

## 6. 끝내 검증하지 못한 것 — 정직한 한계

| 항목 | 상태 | 이유 |
|---|---|---|
| **한국어 형태소 분석(nori)** | ❌ 미검증 | 플러그인 배포처(`artifacts.elastic.co`) 차단, Maven Central 에도 없음. 배선만 `standard` 토크나이저로 확인. |
| **채팅 메시지 저장** | ❌ 동작하지 않음 | 저장 경로가 연결돼 있지 않다(B-24 1번). 설계 결정이라 수정 보류. |
| **Grafana 렌더링** | ⚠️ 부분 | 바이너리 배포 차단. 대시보드 질의 6개를 실제 Prometheus 에 실행해 대체 검증(§3.5). |
| **Eureka 이름 충돌(B-22)** | ⚠️ 미확인 | Eureka 를 끄고 테스트했다. `spring.application.name` 이 전부 `common` 이었으므로 등록 충돌 가능성이 높으나 실측하지 않았다. |
| **Spring Batch 메타 DB 소유권(B-25)** | ⚠️ 미해결 | 어느 DB 가 배치 메타를 가질지 결정 필요(§7). |

## 7. 남은 결정 사항

1. ~~**채팅 메시지 저장 위치**~~ → ✅ 소비자 측(`MessageConsumer`)으로 결정·배선 완료(§4).
2. ~~**Spring Batch 메타데이터 DB**~~ → ✅ **결정·구현 완료.**
   **기본 데이터소스(productdb)의 `meta` 스키마가 소유한다.** JobRepository 가 `@Primary` 데이터소스를 쓰므로
   실제 동작이 이미 그러했고, 설정을 실제에 맞추는 편이 변경 폭이 가장 작다. Flyway 도 기본 데이터소스를
   대상으로 돌기 때문에 마이그레이션 하나(`V3__create_batch_metadata.sql`)로 스키마 생성이 자동화된다.
   metadb 로 옮기려면 두 번째 Flyway 대상을 도입해야 하는데, 배치 메타를 별도 DB 에 둘 운영상 이득이 없다 —
   업무 데이터와 함께 백업/복구되는 편이 낫다. 쓰이지 않는 `datasource-meta` 는 혼동을 막으려 주석으로 표시했다.
   → 통합 테스트가 메타 테이블 6종 존재와 JobExplorer 조회를 검증한다.

## 8. 후속 조치 요약 (2026-07-28)

| 항목 | 결과 |
|---|---|
| 채팅 저장 배선 | ✅ 소비자 측 저장 + id 기반 upsert 멱등성 |
| 쿠폰 컨슈머 멱등성 | ✅ `coupon.processed_event` 원장 추가 — 재전달이 가짜 DLT 로 쌓이던 문제 해소 |
| 사가 재고 차감 자동 검증 | ✅ 실제 Kafka 컨테이너로 7건(docs/17 §4.1) |
| CI | ✅ 푸시/PR 마다 단위+통합+프론트 빌드 |
| nori 형태소 분석 | ❌ 여전히 불가 — Maven Central 에 `analysis-nori` 가 없다(`analysis-kuromoji` 는 있다). Lucene 의 `lucene-analysis-nori` 는 있으나 ES 플러그인 래퍼 클래스는 포함돼 있지 않아, 플러그인을 직접 작성해야 한다. |
| 사가 전 구간 자동 검증 | ✅ 서비스 컨테이너 3종으로 자동화(docs/17 §4.3) — 이 문서에서 손으로 확인한 흐름이 이제 빌드에서 돈다 |
| Spring Batch 메타 DB | ✅ productdb 의 `meta` 스키마로 결정, Flyway 마이그레이션으로 자동 생성 |
| 컨테이너 이미지 기동 불가 | ✅ 시크릿 파일 필수 import 를 optional 로 바꾸고 기본값 제공(docs/17 §4.4) |

## 5. 이 검증으로 바뀐 것

1. **사가는 이제 "설계상 그럴 것"이 아니라 "실제로 돈다"**. 주문→결제→재고 전이가 수치로 일치했다.
2. **멱등성·DLQ·다중 항목 수정이 실증됐다.** 세 가지 모두 이번 작업에서 넣은 코드이고, 단위 테스트가 아니라 실제 브로커에서 확인했다.
3. **관측성은 "노출"에서 "관측"으로 넘어갔다.** 9/9 수집, 경보 발화·해소·전달까지 연결됐다.
4. **관측성을 실제로 붙였더니 관측성 자체의 치명적 결함(B-22)이 나왔다.** 검증하지 않은 관측성은 관측성이 아니다.
