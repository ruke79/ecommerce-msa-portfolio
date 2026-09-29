# 37. 트랜잭션 안의 외부 호출 — 전 모듈 점검

[日本語](37-transaction-boundary-audit.md) | **한국어**

> 🕰️ **상태(2026-09-27) — 일부 낡음.** ① 과 #5 는 적용했습니다. `CartService.saveCart` 와 `TrackingPoller.poll` 은 남아 있습니다.

> 점검 질문: DB 트랜잭션 안에서 외부 API 를 호출하는 곳이 있는가? 전체 스프링 모듈을 대상으로 점검했다.
>
> 답: **있다. 많다.** 12개 모듈 중 9개에서 발견됐고, 그중 몇 곳은 이 저장소에서 가장 긴
> 트랜잭션이다. 그리고 점검 과정에서 **모든 Feign 호출의 타임아웃이 실제로는 적용되지
> 않고 있다**는 별건의 설정 결함이 나왔다 — 이것이 다른 모든 발견의 심각도를 바꾼다.

이 문서는 **점검 결과다. 아무것도 고치지 않았다.** §6 의 권고는 순서까지 적었지만 실행은
별도 결정이다.

---

## 1. 무엇을 "외부 호출"로 셌나, 어떻게 확인했나

**셌다**: Feign/HTTP 클라이언트, `KafkaTemplate.send`(및 그것을 감싼 Producer/Publisher),
PayPal API, S3, SMTP, SMS HTTP, 택배사 추적 HTTP, Elasticsearch 색인.
**따로 분류했다**: Redis, MongoDB(chat-service). 왕복이 짧고 같은 클러스터 안이라
위험이 한 단계 낮지만, DB 커넥션을 쥔 채 실행된다는 사실은 같다.

**방법**: `@Transactional` 이 붙은 클래스·메서드 중 외부 호출 컴포넌트를 주입받는 21개
클래스를 뽑아 메서드 본문을 전부 읽고, 호출 경로를 1~2홉 따라갔다. 이름으로 추측하지 않았다.
클래스 레벨 `@Transactional` 은 모든 public 메서드에 적용되는 것으로 봤고(그렇게 동작한다),
`private` 메서드의 `@Transactional` 은 무시했다(프록시를 타지 않아 무효다 — §4 참조).

**검증**: 아래 표의 file:line 은 전부 직접 열어 확인했다. 두 가지 전제(§2)는 라이브러리
jar 의 바이트코드와 설정 메타데이터로 확인했다.

---

## 2. 점검 중 뒤집힌 전제 두 가지

이 둘을 먼저 읽어야 §3 의 심각도가 이해된다.

### 2.1 Feign 타임아웃은 적용되고 있지 않다 — 실제값은 연결 10초 / 읽기 60초

`common/src/main/resources/application-common.yml:91-96` 과 각 모듈 yml 에 이렇게 적혀 있다:

```yaml
feign:
  client:
    config:
      default:
        connectTimeout: 3000
        readTimeout: 5000
```

**이 키는 읽히지 않는다.** 이 저장소는 Spring Cloud 2024.0.0(`build.gradle:39`) → OpenFeign
4.2.0 이다. 4.x 에서 접두사가 `spring.cloud.openfeign.*` 로 바뀌었고 옛 접두사에 대한
폴백은 없다. jar 로 확인:

```
$ strings FeignClientProperties.class | grep openfeign
spring.cloud.openfeign.client                ← @ConfigurationProperties 의 prefix

$ spring-configuration-metadata.json 에서 'feign.' 로 시작하는 속성 수: 0
```

`Request.Options` 빈도 어디에도 없다. 그러면 Feign 기본값이 적용된다. feign-core 13.5 의
`Request$Options()` 생성자 바이트코드:

```
1: ldc2_w  long 10l  ; TimeUnit.SECONDS   ← connectTimeout
7: ldc2_w  long 60l  ; TimeUnit.SECONDS   ← readTimeout
```

즉 아래 표의 모든 Feign 항목은 "최대 5초"가 아니라 **"최대 60초 동안 DB 커넥션을
쥔다"** 로 읽어야 한다. 풀 크기는 모든 서비스가 5다(`maximum-pool-size: ${DB_POOL_SIZE:5}`).
상대 서비스가 멈추면 **60초 × 동시 요청 5개 = 풀 고갈**이고, 그 뒤 모든 요청은
`connection-timeout: 5000` 을 기다리다 실패한다. 한 서비스의 장애가 이웃의 DB 풀로
번지는 정확한 경로다.

이 결함은 트랜잭션과 무관하게 **그 자체로 고쳐야 한다.** 한 줄이다(§6 ①).

> **적용됨.** `application-common.yml` 의 `spring.cloud.openfeign.client.config.default` 로 옮겼고
> 모듈별 죽은 블록은 지웠다(전 서비스가 common 을 import 하므로 정의 지점은 하나다).
> `ConfigurationGuardrailTest` B-37 이 옛 접두사의 부활을 막고, cart-service 의
> `FeignTimeoutBindingTest` 가 ① 실제 yml 이 `FeignClientProperties` 에 바인딩되는지,
> ② 그 값으로 8초 걸리는 서버를 부르면 5초에 `Read timed out` 이 나는지를 고정한다.
> 그러니 아래 표의 Feign 항목은 이제 "최대 5초"로 읽으면 된다 — 단 N배 증폭은 그대로다.

### 2.2 Kafka 프로듀서 트랜잭션은 4개 모듈에서 켜져 있다 — 절반의 보호

앞서 "프로듀서 트랜잭션이 없다"고 했던 것은 틀렸다. `transactionIdPrefix` 가 아니라
`ProducerConfig.TRANSACTIONAL_ID_CONFIG` 로 켜져 있다:

| 모듈 | 위치 |
| --- | --- |
| user-service | `message/config/KafkaProducerConfig.java:82` |
| coupon-service | `config/KafkaProducerConfig.java:51` |
| order-service | `config/KafkaProducerConfig.java:55,79,103,126,150,174` (6개 전부) |
| pay-service | `message/config/KafkaProducerConfig.java:59` |
| delivery-service · chat-service | **없음** (일반 프로듀서) |

`KafkaTransactionManager` 는 어디에도 없지만, 트랜잭셔널 프로듀서 팩토리는 활성 JPA
트랜잭션을 감지하면 Kafka 트랜잭션을 열고 **JPA 커밋 시점에 함께 커밋**한다. 그리고
모든 컨슈머가 `isolation.level=read_committed` 다(cart/catalog/coupon/delivery 의
`KafkaConsumerConfig`).

**그래서 4개 모듈에서는** "DB 가 롤백됐는데 메시지는 나갔다"는 문제가 **막힌다.**
원자성(2PC)은 아니고 두 개의 독립 커밋이라 "DB 커밋 성공 → Kafka 커밋 실패" 창은 남지만,
가장 흔한 사고는 막혀 있다. 이건 크레딧이다.

**막히지 않는 것**: DB 커넥션을 쥔 채 브로커와 통신한다는 사실. 오히려 커밋 경로에
Kafka 커밋 왕복이 **추가**된다. 그리고 delivery/chat 은 이 보호가 없다.

---

## 3. 발견 목록

심각도 기준: **🔴** 사용자 요청 경로 + 트랜잭션 길이가 외부 지연에 정비례 + N배 증폭 /
**🟠** 그중 하나 / **🟡** 짧거나 빈도가 낮거나 readOnly / **⚪** Redis·Mongo.

### 🔴 가장 급한 여섯

| # | 모듈 | 트랜잭션 메서드 | 외부 호출 | 왜 급한가 |
| --- | --- | --- | --- | --- |
| 1 | order | `OrderService` 클래스 레벨 `@Transactional`(`OrderService.java:55`) 아래의 `getOrders`(`:90`) → `OrderDataService.getOrders`(`:113`) | `convertToOrderDto`(`OrderService.java:128`) 가 **주문 N개 × 상품 M개** 만큼 `catalogServiceClient.getColorInfo` | 주문 이력 조회 한 번에 Feign 수십~수백 회를 **읽기/쓰기** 트랜잭션 안에서 직렬 수행. `getOrders` 에는 메서드 애노테이션이 없어 `readOnly` 조차 아니다. 페이지네이션 변형(`:126`, `:133`)도 페이지 크기만큼 같은 구조 |
| 2 | cart | `CartService.saveCart`(`CartService.java:197`) | `fetchUserByEmail`(`:336` Feign) → `deleteCartByUserId` **DELETE** → 상품마다 `createCartProduct`(`:488` Feign) | 카트 DELETE 잠금을 쥔 채 **상품 수만큼 Feign**. 상품 10개 = 최악 10분(60초×10). 체크아웃 직전 경로라 사용자가 정확히 여기서 기다린다 |
| 3 | user | `ReviewService` 클래스 레벨(`:30`) 아래 `addReview`(`:140`) | `imageService.upload`(`:170`, `:237` → S3 `putObject`) 이미지 N장 + `catalogServiceClient.updateRating`(`:191`, `:251`) | 리뷰 행 저장 직후 S3 업로드 N회 + Feign. S3 에는 **타임아웃 설정이 없다**. 업로드 성공 후 롤백되면 S3 고아 객체, Feign 성공 후 롤백되면 평점만 반영된 불일치 |
| 4 | delivery | `TrackingPoller.poll`(`TrackingPoller.java:60`) | `claimDueForPolling`(SKIP LOCKED) → 배송마다 `adapter.track`(`KoreaPostCarrierAdapter.java:133` HTTP, 건당 최대 10초) → `publisher.publish` | **이 저장소에서 가장 긴 트랜잭션.** 배치 20건 × 10초 = 최악 200초 동안 20개 행의 배타 잠금 + 커넥션 1개. 선점 트랜잭션과 HTTP 루프가 분리돼 있지 않다 |
| 5 | order | ~~`OrderDataService.createOrder`~~ | ~~`cartServiceClient.getCart`~~ | ✅ **해소.** 원격 호출(장바구니 + 쿠폰 견적)을 `OrderService.createOrder` 로 올리고 그 메서드를 `NOT_SUPPORTED` 로 두었다. `OrderDataService` 에서는 Feign 클라이언트 필드를 **제거**해 다시 부를 수 없게 했고, 가드 테스트가 그 "없음"을 지킨다(`OrderTransactionBoundaryTest`) |
| 6 | notification | `VerificationService.issue`(`:90`) | `verificationRepository.save`(`:123`) 직후 `smsSender.send`(`:126` → HTTP) | 사용자 대면 동기 요청. 트랜잭션 길이 = SMS 게이트웨이 응답시간. 커밋 실패 시 **코드는 문자로 갔는데 DB 에 없다** — 사용자는 받은 코드로 인증할 수 없다 |

### 🟠 구조는 같고 증폭이 덜한 것

| # | 모듈 | 트랜잭션 메서드 | 외부 호출 | 비고 |
| --- | --- | --- | --- | --- |
| 7 | cart | `loadCart`(`:169`, readOnly) | 상품마다 Feign 2회(`:410`, `:419`) + `fetchUserByEmail` | 조회 API 라 호출 빈도가 높다. readOnly 라 잠금은 없지만 커넥션은 잡는다 |
| 8 | cart | `getCart`(`:71`, readOnly) 캐시 미스 경로 | `getColorInfo`(`:371`) 상품 수만큼 | 캐시 히트면 조기 반환 |
| 9 | cart | `updateCart`(`:153`) | `computeProductPrices`(`:160` → `:391` Feign) | **DB 접근이 전혀 없는 순수 계산**인데 클래스 레벨 애노테이션 때문에 커넥션을 잡는다. 순수 낭비 |
| 10 | user | `ReviewService.deleteReview`(`:45`) | `deleteById` 직후 `updateRating`(`:74`, `:76`) | #3 의 삭제판 |
| 11 | user | `UserServiceImpl.generatePasswordResetToken`(`:240`, 클래스 레벨 `:61`) | `emailService.sendPasswordResetEmail` → `OutgoingMailProducer.publish` → **`kafkaTemplate.send(...).get()`**(`:90` 블로킹) | 토큰 저장 직후 브로커 응답을 **기다린다.** 타임아웃은 있다(`MAX_BLOCK_MS=5000`, `DELIVERY_TIMEOUT_MS=10000`) — 최대 10초 |
| 12 | order | `OrderDataService.persisitPaypalPayment`(`:247`) | `paypalOrderVerifier.verify`(`:258` → PayPal GET, 토큰 갱신 시 POST 추가) | PayPal 은 **타임아웃이 제대로 걸려 있다**(`PaypalConfig.java:50-59`, 3s/5s). 최악 약 16초. 결제 검증(docs/35)이라 트랜잭션 밖으로 빼기도 애매하다 — §6 참조 |
| 13 | order | `OrderSaga.process`(`:71`), `rollback`(`:191`) | `ProductUpdateKafkaPublisher:34`, `CartEmptyKafkaPublisher:36`, `CartRollbackKafkaPublisher:35`, `CouponRollbackKafkaPublisher:31` — 전부 `send`(논블로킹) | §2.2 의 보호를 받는다. 남는 문제: 발행 실패 시 `whenComplete` 콜백이 **로그만 남기고 사가는 조용히 끊긴다**(`ProductUpdateKafkaPublisher.java:41-43`, 다른 퍼블리셔도 같은 모양) |
| 14 | coupon | `CouponService.applyCoupon`(`:44`) | 첫 문장이 `userServiceClient.findUserByEmail`(`:48`), 끝에 `couponUseProducer.publish`(`:65` → `send`) | 이 메서드는 **DB 쓰기가 없다**(조회만). 트랜잭션 자체가 불필요. 게다가 쿠폰 사용 사실을 DB 에 안 남기고 메시지만 보내며, `KafkaException` 을 **빈 catch 로 삼킨다**(`CouponUseProducer.java:42-44`) |
| 15 | delivery | `DeliveryService.createFromOrder`(`:55`), `registerTracking`(`:104`), `cancel`(`:180`) | `saveAndFlush` 직후 `publisher.publish`(`DeliveryStatusPublisher.java:56` `send`) | delivery 는 **프로듀서 트랜잭션이 없다**(§2.2). 커밋 전에 나간 메시지는 롤백돼도 남는다. 클래스 주석(`:30`)이 아웃박스가 정석임을 스스로 밝히고 있다 |
| 16 | catalog | `ProductService.addProduct`(`:314`), `load`(`:237`) | `createSku`/`loadSku` 안에서 `imageService.upload`(S3) 이미지 N장 | #3 과 같은 구조. `addProduct` 에는 보상 삭제가 있으나(`:684-689`) 그것도 트랜잭션 안에서 S3 DELETE N회를 더한다. `load` 는 보상 없음 |
| 17 | catalog | `addProduct`, `updateProduct`(`:454`), `updateStatus`(`:491`) | `ProductIndexService` → ES `save`/`deleteById` | `search.engine=es` 일 때만. 커밋 전 색인이라 롤백 시 ES 에 유령 문서 |
| 18 | catalog | Spring Batch 청크 트랜잭션(`BatchConfig.java:267`, `:277`) | `ProductColorItemProcessor:100`, `ProductSkuItemProcessor:45` 의 S3 업로드 | ItemProcessor 는 청크 트랜잭션 안에서 돈다. 청크 10 × 이미지 수 |
| 19 | notification | `MailDispatcher.dispatch`(`:73`), `SmsDispatcher.dispatch`(`:61`) | 선점 → `sendOne`(SMTP / SMS HTTP, 각 5초) → SENT 기록이 **한 트랜잭션** | **의도된 설계다**(클래스 주석 `:35-37` "동기 발송 — 트랜잭션 안에서 결과를 확정한다"). 잠금이 상태 확정 전에 풀리는 창을 막으려는 선택. 대가: 배치 50 × 5초 = 최악 250초. 첫 일시 실패에서 배치를 끊어(`:91`) 상한을 관리한다. 그리고 SMS 는 타임아웃 시 "접수 여부 불명"이라 **커밋 실패 = 요금 나간 중복 발송** |

### 🟡 낮음 — 의도됐거나 짧거나

| # | 모듈 | 무엇 | 판정 |
| --- | --- | --- | --- |
| 20 | order · pay | `PaymentOutboxScheduler.processOutboxMessage`(`:57`), `OrderOutboxScheduler.processOutboxMessage`(`:45`) — 선점 후 `send(...).get(5000ms)` **블로킹** | **설계된 트레이드오프.** 잠금을 상태 확정까지 유지하기 위한 것이고 코드 주석(`PaymentOutboxScheduler.java:22-41`)이 이유를 명시한다. 타임아웃·배치 크기·첫 실패 시 중단으로 상한이 관리된다. 건드리지 말 것 |
| 21 | chat | `ChatService.sendMessage`(`:124`), `sendEnterMessage`(`:170`), `sendLeaveMessage`(`:185`) | Kafka `send`(논블로킹). 그런데 이 메서드들은 **JPA 쓰기가 없다** — 클래스 레벨 `@Transactional`(`:55`) 이 JPA 트랜잭션을 열고 그 안에서 Mongo·Kafka 만 한다. Mongo 는 JPA 트랜잭션에 참여하지 않는다(MongoTransactionManager 없음). 트랜잭션이 아무것도 보호하지 않으면서 커넥션만 잡는다 |
| 22 | user | `UserServiceImpl.registerConfirmed`(`:531`) → `userCreatedProducer.publish`(`:552` `send`) | §2.2 보호 있음. 아웃박스 코드는 주석 처리돼 있다(`:549-550`) |

### ⚪ Redis · Mongo — 짧지만 같은 원리

- `OrderService`(클래스 레벨 tx) 아래에서 `OrderCacheService` 의 `@Cacheable`/`@CacheEvict` 가 동작한다 — Redis 왕복이 트랜잭션 안. `allEntries=true` 대량 EVICT(`:71`, `:116`) 포함. `spring.data.redis.timeout` 미설정(Lettuce 기본 무제한).
- `ProductService` 도 같은 구조인데, 캐시 어드바이저와 트랜잭션 어드바이저의 **순서가 지정돼 있지 않아**(`RedisCacheConfig.java:39` `@EnableCaching` 만) 어느 쪽이 바깥인지 보장되지 않는다.
- `ChatService.createChatRoom`(`:142`) — JPA 트랜잭션 안에서 Redisson `tryLock` **최대 10초 대기**(`:148`) + 중첩 락.
- `VerificationService.issue` — Redis 4회(`:104`, `:110`, `:112`, `:135`). 쿨다운 `set` 이 커밋 전이라 롤백돼도 쿨다운은 남는다.
- `CartService.getCart` — 캐시 히트여도 트랜잭션이 먼저 열려 커넥션을 잡았다 놓는다(`:74`).

---

## 4. 표에 없는 관찰 — 왜 이렇게 됐나

**클래스 레벨 `@Transactional` 이 원인의 절반이다.** `OrderService:55`, `CartService:39`,
`ReviewService:30`, `UserServiceImpl:61`, `ChatService:55`, `ProductService:79`. 클래스에
붙이면 "DB 를 건드리는가"와 무관하게 모든 public 메서드가 트랜잭션이 되고, 나중에 추가된
Feign 호출·순수 계산(#9)·Kafka 전용 메서드(#21)가 전부 그 안으로 들어간다. 메서드에
붙였다면 #9, #14, #21 은 애초에 생기지 않았다.

**`private @Transactional` 은 전부 무효다.** `CartService.java:334, 342, 353`,
`OrderSaga.java:45, 58, 97, 127, 145, 154`, `CouponService.java:242, 250`. 자기호출은
프록시를 타지 않는다. 문제는 무효라는 것보다 **분리돼 있다고 오해하게 만든다**는 것이다 —
`OrderSaga` 의 private 메서드들은 별도 트랜잭션처럼 보이지만 전부 `process` 의 트랜잭션
안이다.

**아웃박스는 order·pay 에만 살아 있다.** user-service 의 `OutboxScheduler` 는 구현체 없는
빈 인터페이스, cart-service 의 `outbox/*` 는 어디서도 참조되지 않는다. delivery 는
테이블 자체가 없다.

**`REQUIRES_NEW` · `@Transactional(timeout=)` 은 저장소 전체에 0건이다.** 트랜잭션 길이에
상한을 두는 장치가 없다.

**타임아웃이 제대로 걸린 클라이언트와 아닌 것이 갈린다.** PayPal(`PaypalConfig`),
택배사 HTTP(`KoreaPostCarrierAdapter:112, 136`), SMS(`HttpSmsSender:64-66`),
SMTP(yml `:65-67`), Kafka 블로킹 발행(`OutgoingMailProducer:69-71`)은 걸려 있다.
**Feign 과 S3 는 없다.** Feign 은 §2.1 의 이유로, S3 는 `AmazonS3Config` 가 기본값이라.

---

## 5. 이미 올바르게 돼 있는 것 — 그대로 둘 것, 그리고 본보기

이 저장소에 정답이 없는 게 아니다. 같은 저장소 안에 모범 사례가 있고, 나머지를 그쪽으로
맞추면 된다.

| 패턴 | 어디 | 무엇이 옳은가 |
| --- | --- | --- |
| **커밋 후 이벤트 리스너** ⚠️ **정정** | `RegistrationListener.java:71-73` — `@Async` + `@TransactionalEventListener(AFTER_COMMIT)`. **코드 모양은 모범이지만 실제로는 호출되지 않는다** — 이벤트를 발행하는 `AuthController.java:196` 이 트랜잭션 밖이라 `fallbackExecution=false` 기본값으로 리스너가 건너뛰어진다(docs/38 §1.4). 인용할 때 이 사실을 함께 볼 것 | 가입 트랜잭션이 닫힌 **뒤에** 토큰 생성(새 tx) → Kafka 발행. 이 저장소의 가장 모범적인 지점 |
| **afterCommit 동기화** | `CartService.java:135-143, 221-229, 250-259, 280-288, 311-320` | Redis 갱신과 사용자 조회 Feign 을 커밋 이후로. 단, 커넥션 반납은 `afterCompletion` 이라 그 사이 Feign(최대 60초) 동안 커넥션은 아직 스레드에 묶여 있다 — 커밋 전보다 훨씬 낫지만 완전히 자유롭진 않다 |
| **아웃박스 정석** | `OrderDataService.java:317` → `PaymentOutboxHelper` / `PaymentService.java:57,69` → `OrderOutboxHelper` | 트랜잭션 안에서는 테이블에만 쓰고 발행은 스케줄러가. pay-service 는 인라인 발행을 걷어낸 흔적이 주석으로 남아 있다(`OrderOutboxHelper.java:77`) |
| **컨슈머는 큐에만 적재** | `notification-service` 의 `OutgoingMailListener:29-31`, `NotificationEventListener` | "여기서 메일을 보내지 않는다" — 리스너는 행만 넣고 발송은 디스패처가 |
| **선점 `FOR UPDATE SKIP LOCKED`** | `MailMessageRepository:38`, `NotificationRepository:36-44`, `ShipmentRepository:33`, `PaymentOutboxHelper:49-58` | 레플리카 중복 처리 방지 |
| **외부 실패가 도메인 저장을 실패시키지 않게** | `ProductIndexService:50-53`, `DeliveryStatusPublisher:60-63`, `ImageService:105-108` | 예외 격리 |
| **외부 호출을 끌 수 있는 스위치** | `ProductIndexService:41-42`(기본 OFF), `MailConfig:28`(기본 logging) | 기본값이 안전한 쪽 |

---

## 6. 권고 — 순서대로, 아직 하지 않음

### ① Feign 접두사 한 줄 — 다른 무엇보다 먼저 ✅ 적용됨

```yaml
# application-common.yml 및 각 모듈 yml
spring:
  cloud:
    openfeign:
      client:
        config:
          default:
            connectTimeout: 3000
            readTimeout: 5000
```

이것 하나로 §3 의 Feign 항목 전부가 "최대 60초"에서 "최대 5초"로 내려온다. 트랜잭션 구조를
손대기 전에 이걸 먼저 하면 최악값이 12배 줄어든다. **적용 후 실제로 걸리는지 확인할 것** —
설정을 바꿨다고 믿었는데 안 읽히고 있던 것이 지금 상태다. 확인 방법: 상대 서비스를 내리고
호출해서 5초에 `Read timed out` 이 나는지 본다.

### ② 구조를 바꿀 여섯 (🔴)

| # | 무엇을 | 어떻게 |
| --- | --- | --- |
| 1 | `getOrders` N×M | 트랜잭션 안에서 주문·상품 엔티티만 읽어 커밋하고, **트랜잭션 밖에서** 색상 정보를 배치 조회(`getColorInfo` 를 id 목록으로 받는 엔드포인트 하나 추가)해 DTO 조립. `OrderService` 의 클래스 레벨 애노테이션을 메서드로 내리는 것과 함께 |
| 2 | `saveCart` | Feign 을 **먼저 전부** 끝내고(트랜잭션 밖), 그 결과로 트랜잭션을 짧게 연다: 조회 → 계산 → `@Transactional` 저장. `fetchUserByEmail` 의 무효 `@Transactional` 제거 |
| 3 | `ReviewService` | S3 업로드를 트랜잭션 **앞**으로(실패하면 리뷰를 저장하지 않는다), `updateRating` 을 `@TransactionalEventListener(AFTER_COMMIT)` 로 — `RegistrationListener` 와 같은 모양. 롤백 시 업로드된 객체 삭제는 보상으로 |
| 4 | `TrackingPoller` | **선점 트랜잭션과 폴링을 분리**: ① 짧은 tx 로 `claimDueForPolling` + `next_poll_at` 을 미래로 밀어 잠금 대신 예약으로 소유권 표시 → 커밋 ② 트랜잭션 밖에서 HTTP N회 ③ 건별 짧은 tx 로 결과 반영. `MailDispatcher` 가 "잠금이 상태 확정 전에 풀리는 창"을 우려한 것과 같은 문제가 있지만, 폴링은 멱등이라(같은 추적번호를 두 번 조회해도 무해) 그 창을 감수해도 된다 |
| 5 | `createOrder` | ✅ **적용함.** 다만 권고문의 "`OrderService.createOrder` 가 이미 그 위치다" 는 절반만 맞았다 — 위치는 맞지만 `OrderService` 에 **클래스 레벨 `@Transactional`** 이 붙어 있어 거기서도 이미 트랜잭션이 열린다. 그래서 그 메서드에 `@Transactional(propagation = NOT_SUPPORTED)` 를 명시해 껐다. 장바구니와 금액을 인자로 받도록 `OrderCacheService`·`OrderDataService` 시그니처도 바꿨다 — "이 메서드는 원격 호출을 하지 않는다"가 시그니처에 드러난다 |
| 6 | `VerificationService.issue` | 저장 → 커밋 → SMS 발송 → 실패 시 별도 tx 로 행 삭제(또는 상태 FAILED). 커밋 실패 시 "코드는 갔는데 DB 에 없음"이 사라진다. 대신 "DB 에는 있는데 SMS 실패"가 생기는데, 그건 사용자가 재요청하면 해결된다 — 방향이 맞는 실패다 |

### ③ 아웃박스를 살릴 곳

delivery-service(#15)는 프로듀서 트랜잭션도 없어 가장 노출돼 있다. order/pay 의
`PaymentOutboxHelper` + 스케줄러를 그대로 옮기면 된다. user/cart 의 죽은 아웃박스 코드는
살리거나 지우거나 — 지금처럼 "있는 것처럼 보이는" 상태가 가장 나쁘다.

### ④ 건드리지 말 것

- **#20 아웃박스 스케줄러의 블로킹 발행** — 의도된 설계. 상한이 관리된다.
- **#19 `MailDispatcher`/`SmsDispatcher`** — 의도된 설계지만 트레이드오프를 다시 볼 가치는 있다. SMS 의 "타임아웃 = 접수 불명 = 커밋 실패 시 중복 요금"은 실제 돈이다. 선점 tx 를 짧게 끊고 발송 후 별도 tx 로 확정하면 그 창이 사라지는 대신 "확정 실패 시 다음 주기 재발송" 창이 생긴다. **멱등 키가 게이트웨이 쪽에 있으면** 후자가 낫다 — SMS 게이트웨이가 클라이언트 참조 ID 로 중복을 걸러 주는지 확인한 뒤 결정할 것.
- **#12 PayPal 검증** — 결제 위조 방어(docs/35)가 트랜잭션 안에 있어야 "검증 통과 → 저장" 사이의 창이 없다. 타임아웃이 제대로 걸려 있으니 최악 16초를 감수하는 것이 맞다. 대신 `@Transactional(timeout = 20)` 으로 상한만 명시할 것.

### ⑤ 예방 — 다시 생기지 않게

- 클래스 레벨 `@Transactional` 을 메서드 레벨로 내린다(§4). 일회성 정리지만 이후의 모든
  추가가 안전해진다.
- `private`/자기호출 `@Transactional` 을 지운다. 무효인 데다 오해를 만든다.
- 외부 호출 컴포넌트를 `@Transactional` 클래스에 주입하는 것을 ArchUnit 규칙으로 막는
  방법이 있다 — 이 저장소는 이미 구조적 사실을 빌드로 고정하는 방식을 쓴다(docs/19 의
  SQL count 가드). 같은 자리에 하나 더 두면 된다.

---

## 7. 확인하지 못한 것

- **실제 트랜잭션 길이 분포.** 이 문서의 "최악 N초"는 타임아웃 산술이지 측정이 아니다.
  `spring.jpa.properties.hibernate.session.events.log.LOG_QUERIES_SLOWER_THAN_MS` 나
  HikariCP 의 `leak-detection-threshold`(예: 10000ms)를 켜면 운영에서 실제로 긴 트랜잭션이
  로그에 잡힌다. 고치기 전에 그것부터 켜서 §3 의 순서가 실제 빈도와 맞는지 보는 것이 옳다.
- **Feign 타임아웃 미적용의 실증.** 바이트코드와 메타데이터로 확인했지만, 실제로 상대 서비스를
  내리고 60초를 기다려 본 것은 아니다. §6 ① 적용 전후로 한 번 재현해 볼 것.
- **§2.2 의 Kafka 트랜잭션 동기화가 실제로 붙는지.** 트랜잭셔널 팩토리 + 활성 JPA tx 조건은
  갖춰져 있고 Spring Kafka 의 문서화된 동작이지만, 이 저장소에서 롤백 시 메시지가 안 나가는
  것을 테스트로 고정해 두지는 않았다. 고정할 가치가 있다 — 이 보호에 기대고 있으니까.
