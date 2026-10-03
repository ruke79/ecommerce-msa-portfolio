# 62. 기술 사례집 — 문제 · 처리 · 한계, 그리고 참고한 책

[日本語](62-engineering-casebook.md) | **한국어**

> 작성일: 2026-09-27
>
> 이 저장소에서 고친 문제 가운데 **기술적으로 중요한 것**을 골라, 사례마다 다음을 적었습니다.
> - 기준으로 삼을 수 있는 **오라일리(O'Reilly) 책 1권**
> - **문제**, **처리한 내용**, **한계와 개선할 점**
> - 자세한 근거 문서
>
> 📖 **책에 대해.** 책은 "이렇게 하라고 쓰여 있어서 그렇게 했다"는 뜻이 아닙니다. **같은 문제를 일반화해 설명한 참고서**라는 뜻입니다.
> 장 번호는 판본에 따라 다르므로, 번호를 확신할 수 있는 책만 번호를 적고 나머지는 주제로 적었습니다.
>
> ⚠️ **한계**는 이 문서를 쓰는 시점에 코드와 설정에서 확인한 것입니다. 저장소 전체의 열린 항목 목록은 [docs/54 §10](54-improvements-vs-practice.ko.md)입니다.

## 목차

| # | 영역 | 사례 | 참고서 |
|---|---|---|---|
| 1 | 결제 | 서버가 브라우저의 "결제했다"를 믿었다 | *Web Application Security* |
| 2 | 동시성 | 마지막 1개를 두 사람이 결제했다 | *Designing Data-Intensive Applications* |
| 3 | 메시징 | 레플리카를 늘리자 이벤트가 두 번씩 나갔다 | *Designing Data-Intensive Applications* |
| 4 | 메시징 | 한 건의 실패가 배치를 멈추고, 재전송이 재고를 두 번 뺐다 | *Kafka: The Definitive Guide* |
| 5 | 분산 트랜잭션 | 결제 뒤가 비어 있던 사가, 그리고 12초의 지연 | *Building Microservices* |
| 6 | 회복탄력성 | 설정했다고 믿은 타임아웃이 적용되지 않았다 | *Building Microservices* |
| 7 | 설정 | 컴파일은 되는데 기동하면 죽는 설정 결함 29건 | *The Site Reliability Workbook* |
| 8 | 인가 | 신원 헤더 위조와 IDOR — 클라이언트가 고른 값을 믿었다 | *Building Microservices* |
| 9 | 네트워크 신뢰 | 클러스터 안이면 누구나 내부 API 를 불렀다 | *Zero Trust Networks* |
| 10 | 비밀 | git 이력에 2년간 남아 있던 자격증명 | *Building Secure and Reliable Systems* |
| 11 | 인증 | 소셜 로그인 — 켜는 순간 실패했을 구글, 그리고 카카오·LINE | *Identity and Data Security for Web Development* |
| 12 | 스키마 진화 | 롤링 배포 중에 옛 코드와 새 코드가 같은 DB·토픽을 쓴다 | *Designing Data-Intensive Applications* |
| 13 | 배포 | 테스트한 것과 배포한 것이 다른 바이너리였다 | *Building Microservices* |
| 14 | 데이터 보호 | 백업은 있다고 믿었지만 복원해 본 적이 없었다 | *Site Reliability Engineering* |
| 15 | 관측성 | 경보가 어디로도 가지 않았고, 모든 서비스가 자기를 `common` 이라 불렀다 | *Observability Engineering* |
| 16 | 테스트 | 목(mock) 테스트는 통과했는데 운영에서 저장·조회가 깨졌다 | *Software Engineering at Google* |
| 17 | 프론트엔드 | 좋아요가 1에서 0으로 — 19자리 id 를 JS 가 반올림했다 | *JavaScript: The Definitive Guide* |
| 18 | 데이터 모델 | 컴파일되고 테스트도 통과하는데 데이터가 틀렸다 — 모델 계층 점검 | *Designing Data-Intensive Applications* |

---

## 1. 서버가 브라우저의 "결제했다"를 믿었다

**📖 참고서** — Andrew Hoffman, *Web Application Security*, 2nd ed. (O'Reilly, 2024). 클라이언트에서 온 값은 신뢰 경계 밖이라는 원칙과 비즈니스 로직 결함을 다룹니다.

**문제**
- 결제 완료 요청에 담긴 결제 id 와 금액을 서버가 그대로 받아 주문을 `PAID` 로 바꿨습니다.
- 로그인만 하면 결제 없이 주문을 완료할 수 있었습니다.
- 같은 결제 id 를 다른 주문에 다시 쓰는 것도 막지 못했습니다.

**처리한 내용**
- 서버가 결제사 API 를 다시 조회합니다. 상태, 금액, 통화를 **DB 에 저장된 주문 금액**과 대조합니다.
- `(payment_provider, provider_payment_id)` 유니크 제약으로 결제 id 재사용을 막습니다.
- 설정이 비어 있으면 결제를 거부합니다(fail closed).
- 결제수단을 포트 하나로 뽑아 공통 규칙을 한 곳에 모았습니다. 수단은 PayPal · Stripe · 포트원 V1/V2 · 무통장입금 · 가상계좌입니다.
- 부분 환불은 결제사 쪽 잔액 대조(`checksum` / `currentCancellableAmount`)로 같은 환불이 두 번 나가지 않게 했습니다.

**한계**
- 실제 API 로 확인한 것은 Stripe 뿐입니다(운영에서 결제와 환불).
- PayPal 과 포트원은 응답 필드 매핑이 문서 기준이라, 모양이 다르면 결제 확인이 전부 거부됩니다. fail closed 라서 돈이 새지는 않습니다.
- Stripe Checkout 은 결제수단이 `card` 로 고정돼 있습니다.

**개선할 점**
- 결제사 샌드박스로 도는 계약 테스트를 CI 인수 단계에 둡니다.
- 웹훅 재전송과 순서 뒤바뀜을 재현하는 테스트를 추가합니다.

→ [docs/35](35-payment-verification.ko.md) · [docs/49](49-payment-providers.ko.md)

---

## 2. 마지막 1개를 두 사람이 결제했다

**📖 참고서** — Martin Kleppmann, *Designing Data-Intensive Applications* (O'Reilly, 2017), 7장 "Transactions". 쓰기 스큐(write skew), 팬텀, 그리고 "충돌을 구체화(materializing conflicts)"해서 잠글 대상을 만드는 방법을 다룹니다.

**문제**
- 결제에서 재고 차감까지 사이에 재고를 붙드는 것이 없었습니다.
- 두 사람이 마지막 1개를 동시에 결제하면 둘 다 성공하고, 늦은 쪽은 나중에 부족으로 판명됐습니다.
- 차감이 모자라면 `null` 로 조용히 끝나 주문은 `PAID` 인 채였습니다. 판매량은 수량 확인 전에 올랐습니다.

**처리한 내용**
- **예방:** 주문 생성 때 30분 **예약 행**을 만듭니다. 책이 말하는 "충돌의 구체화"입니다.
  - 남은 수량은 실물에서 다른 사람의 살아 있는 예약을 뺀 값입니다.
  - 전부 잡거나 하나도 잡지 않습니다. SKU 를 정렬해서 잠가 교착을 피합니다.
  - 결제 차감과 같은 트랜잭션에서 예약을 확정합니다.
  - 만료는 잡이 풉니다. 무통장입금은 입금 기한까지 연장합니다.
- **보상:** 그래도 모자라면 SKU 잠금 아래에서 있는 만큼만 배정합니다. 부족분은 같은 트랜잭션의 아웃박스로 알리고, order 가 자동으로 부분 환불합니다. 무통장입금은 수동 환불로 넘깁니다.

**한계**
- catalog 에 닿지 못하면 예약 없이 진행합니다(fail-open). 그때는 보상 쪽에 기댑니다.
- 예약이 만료된 뒤 들어온 결제와 가상계좌 입금은 다시 부족해질 수 있습니다.

**개선할 점**
- 예약 만료 직전 결제를 막거나 연장하는 규칙을 둡니다.
- fail-open 횟수를 지표로 경보합니다.

→ [docs/59](59-stock-reservation.ko.md) · [docs/53](53-restock-and-sold-out.ko.md)

---

## 3. 레플리카를 늘리자 이벤트가 두 번씩 나갔다

**📖 참고서** — *Designing Data-Intensive Applications*, 11장 "Stream Processing". 이중 쓰기(dual writes)의 위험과, DB 변경을 로그로 내보내는 방식(CDC)을 다룹니다.

**문제**
- 아웃박스 폴러가 레플리카마다 같은 행을 읽어 같은 이벤트를 발행했습니다. 동시 트랜잭션 테스트로 재현했고, 중복은 100% 였습니다.
- 결제 뒤 `PAID` 가 되기까지 약 12초가 걸렸습니다. 사가가 느린 게 아니라 **10초 폴러를 두 번** 건너고 있었습니다.
- 아웃박스 행의 `created_at` 이 `Asia/Seoul` 로 하드코딩돼 있었습니다. 이 값이 파티션 키라서 월 경계에서 행이 다른 파티션으로 갔습니다.

**처리한 내용**
- `FOR UPDATE SKIP LOCKED` 로 행을 선점합니다.
- 커밋 직후 릴레이를 한 번 돌립니다. 단일 스레드이고, 도는 중에 들어온 요청은 합칩니다. 폴러는 안전망으로 남겼습니다.
- 시간은 UTC 로 통일하고 회귀 테스트를 붙였습니다.

**한계**
- ~~아웃박스 월 파티션은 2027-01 까지만 미리 만들어져 있고, 자동으로 만드는 장치가 없습니다.~~ → ✅ 2026-09-27 파티션 잡을 넣었습니다(아래).
- Debezium 커넥터 설정은 저장소에 있지만 운영에서는 폴링 릴레이를 씁니다.

**개선할 점**
- ✅ **파티션 잡**(common `OutboxPartitionAutoConfiguration`): 6시간마다 이번 달부터 3달 뒤까지 없는 파티션을 만듭니다.
  - DEFAULT 에 그 달의 행이 이미 들어와 있어도 만듭니다. 빈 테이블을 만들고 행을 옮긴 뒤 `ATTACH` 합니다. 그냥 `PARTITION OF` 로 만들면 `updated partition constraint for default partition would be violated` 로 실패합니다.
  - 레플리카가 여럿이어도 `pg_advisory_xact_lock` 으로 한 번만 만듭니다.
  - 지표 `outbox_partition_months_ahead` · `outbox_partition_default_rows` 와 경보 3개를 두었습니다. 설정을 빠뜨리면 가드 B-70 이 잡습니다.
  - 실제 PostgreSQL 16 으로 검증했습니다(`OutboxPartitionMaintainerPostgresTest`).
- 남은 것: 오래된 파티션의 폐기(보존 정책)는 사람이 합니다.
- 트래픽이 늘면 CDC 로 옮기는 것을 검토합니다.

→ [docs/21](21-horizontal-scaling.ko.md) · [docs/56](56-saga-latency.ko.md) · [docs/09](09-postgresql-partitioning-sharding.ko.md)

---

## 4. 한 건의 실패가 배치를 멈추고, 재전송이 재고를 두 번 뺐다

**📖 참고서** — Gwen Shapira 외, *Kafka: The Definitive Guide*, 2nd ed. (O'Reilly, 2021). 컨슈머 오프셋 커밋, 재처리, 정확히 한 번(exactly-once)의 범위를 다룹니다.

**문제**
- 배치 리스너에서 한 레코드가 실패하면 배치 전체가 재시도됐습니다. 독이 든 메시지 하나가 파티션을 멈췄습니다.
- 재전송된 `product-update` 는 재고를 한 번 더 뺐습니다. 컨슈머가 멱등하지 않았습니다.

**처리한 내용**
- `BatchListenerFailedException` 으로 실패한 레코드만 DLT 로 격리합니다. DLT 적재량과 lag 에 경보를 걸었습니다.
- 이벤트 id 에 `processed_event` 원장을 둬서, 같은 이벤트를 두 번 반영하지 않게 했습니다.
- 추적 컨텍스트가 Kafka 를 건너 이어지게 했습니다.

**한계**
- 멱등 원장은 **catalog 와 coupon 에만** 있습니다. 다른 컨슈머는 상태 전이 조건(이미 PAID 면 무시 등)에 기대고 있습니다.

**개선할 점**
- 부작용이 있는 컨슈머 전부에 원장이나 조건부 갱신을 적용하고, 이를 가드 테스트로 강제합니다.

→ [docs/11](11-observability-and-kafka-reliability.ko.md) · [docs/12](12-dlq-rollout-idempotency-alerts.ko.md)

---

## 5. 결제 뒤가 비어 있던 사가, 그리고 12초의 지연

**📖 참고서** — Sam Newman, *Building Microservices*, 2nd ed. (O'Reilly, 2021), 6장 "Workflow". 사가, 오케스트레이션과 코레오그래피, 보상 트랜잭션을 다룹니다.

**문제**
- 주문은 `PAID` 에서 더 나아가지 못했습니다. 배송, 구매 확정, 반품이 없었습니다.
- 사용자 액세스 토큰이 사가 메시지와 아웃박스 행에 실려 다녔습니다.

**처리한 내용**
- order 를 오케스트레이터로 두고 사가를 이었습니다: 결제 → 재고 → 배송(delivery-service) → 구매 확정.
- 보상은 쿠폰 복원, 장바구니 복원, 부분 환불입니다.
- 토큰은 모든 사가 메시지에서 빼고, 이미 쌓인 아웃박스 행에서도 지웠습니다(V15).
- saga-projection(Kafka Streams)으로 진행 상황을 조회하게 했습니다.

**한계**
- delivery-service 에는 아웃박스가 없습니다.
- `TrackingPoller.poll` 은 하나의 트랜잭션 안에서 외부 택배사를 호출합니다.

**개선할 점**
- delivery 발행을 아웃박스로 옮깁니다.
- 폴링을 "조회(트랜잭션 밖) → 반영(짧은 트랜잭션)" 으로 나눕니다.

→ [docs/25](25-delivery-service-design.ko.md) · [docs/37](37-transaction-boundary-audit.ko.md) · [docs/56](56-saga-latency.ko.md)

---

## 6. 설정했다고 믿은 타임아웃이 적용되지 않았다

**📖 참고서** — *Building Microservices*, 2nd ed., 12장 "Resiliency". 타임아웃, 재시도, 서킷 브레이커, 격벽을 다룹니다.

**문제**
- Feign 타임아웃을 설정했지만 Spring Cloud 4 에서 속성 접두사가 바뀌어 **한 번도 적용되지 않았습니다.** 실제로는 기본값(연결 10초 · 읽기 60초)으로 돌고 있었습니다.
- 12개 모듈 중 9개에서 트랜잭션 안에서 외부 호출을 하고 있었습니다.

**처리한 내용**
- 새 접두사로 옮기고, 속성이 실제로 바인딩되는지 확인하는 테스트(`FeignTimeoutBindingTest`)를 뒀습니다.
- 트랜잭션 경계를 점검해 권고 순서를 정했고, 우선순위가 높은 것부터 트랜잭션 밖으로 옮겼습니다.
- 같은 부류의 함정(Gateway 4.3 의 `spring.cloud.gateway.server.webflux` 이동)도 가드 테스트로 막았습니다.

**한계**
- **Feign 서킷 브레이커가 적용돼 있지 않습니다**(`spring.cloud.openfeign.circuitbreaker` 없음). 게이트웨이 라우트에만 있습니다.
- `CartService.saveCart` 는 여전히 `@Transactional` 안에서 Feign 을 부릅니다.

**개선할 점**
- Feign 에 Resilience4j 서킷 브레이커와 폴백을 붙입니다.
- 남은 트랜잭션 안의 호출을 [docs/37](37-transaction-boundary-audit.ko.md)의 순서대로 뺍니다.

→ [docs/37](37-transaction-boundary-audit.ko.md)

---

## 7. 컴파일은 되는데 기동하면 죽는 설정 결함 29건

**📖 참고서** — Betsy Beyer 외, *The Site Reliability Workbook* (O'Reilly, 2018), 14장 "Configuration Design and Best Practices"와 15장 "Configuration Specifics". 설정도 코드처럼 검증해야 한다는 관점입니다.

**문제**
- 새로 클론하면 서비스가 하나도 뜨지 않았습니다(0 / 8). 예:
  - 프로필 파일 안의 `spring.profiles.active`
  - PostgreSQL 에 MySQL Dialect
  - 원저자 PC 경로(`file:E:/…`)
  - `.p12` 를 `JKS` 로 선언
  - 공유 설정의 `spring.application.name` 때문에 모든 서비스가 스스로를 `common` 으로 보고
- 테스트는 설정을 읽지 않으니 전부 통과했습니다.

**처리한 내용**
- **설정 가드레일 테스트(B-1 ~ B-69)** 를 만들었습니다. 설정 파일, 매니페스트, 코드를 읽어 규칙 위반을 빌드에서 잡습니다. 예:
  - 되돌릴 수 없는 마이그레이션인데 `ROLLBACK-UNSAFE` 선언이 없는 것
  - Feign 이 부르는 내부 경로가 mTLS 규칙에 없는 것
  - 인증서 갱신 뒤 재시작 주석이 없는 것
- 가드마다 **되돌려서 실패하는지** 확인했습니다.

**한계**
- 정적 검사라서, 실제 클러스터의 값(Secret, 오버레이)은 보지 못합니다.
- 규칙이 한 파일(`ConfigurationGuardrailTest`)에 모여 커졌습니다.

**개선할 점**
- 배포 직전에 렌더링한 매니페스트(`kustomize build`)를 같은 규칙으로 검사합니다.
- 규칙을 영역별 파일로 나눕니다.

→ [docs/14](14-boot-test-findings.ko.md) · [docs/17](17-testing.ko.md)

---

## 8. 신원 헤더 위조와 IDOR — 클라이언트가 고른 값을 믿었다

**📖 참고서** — *Building Microservices*, 2nd ed., 11장 "Security". 게이트웨이 뒤의 암묵적 신뢰와 "혼동된 대리인(confused deputy)" 문제를 다룹니다.

**문제**
- 클라이언트가 `User-Email` 같은 신원 헤더를 직접 보내면 서비스가 그대로 믿었습니다.
- 4개 서비스에서 요청 본문의 id 와 email 로 남의 주문, 장바구니, 쿠폰, 배송지에 접근할 수 있었습니다(IDOR).
- 레이트리밋 키도 클라이언트가 바꿀 수 있는 `X-Forwarded-For` 였습니다.

**처리한 내용**
- 게이트웨이가 클라이언트의 신원 헤더를 **지우고**, 검증한 토큰에서 다시 씁니다.
- 신원은 `User-Id`(토큰의 uid)에서만 얻습니다. 본문의 신원 필드는 없앴습니다.
- 소유권 판정을 한 곳에 모으고, "남의 것을 요청하면 거절"을 회귀 테스트로 고정했습니다.
- 사용자 열거를 막기 위해 로그인 실패 문구를 하나로 통일했습니다. 계정 잠금(5회 / 15분)과 2FA 는 서버가 강제합니다.
- 인증 쿠키를 `SameSite=Lax` 로 명시했습니다. 쿠키로 신원을 싣는 요청(리프레시 회전, 2FA 확인, 로그아웃)은 모두 POST 라서, 다른 사이트에서 시작한 요청에는 쿠키가 실리지 않습니다. 액세스 토큰은 `Authorization` 헤더로 다니므로 CSRF 대상이 아닙니다. 예전에는 속성을 적지 않아 브라우저 기본값에 맡겼습니다(Safari·Firefox 는 None 으로 취급). `Set-Cookie` 헤더를 테스트로 고정했습니다(`CookieSameSiteTest`).

**한계**
- `SameSite` 는 **사이트** 단위라 같은 도메인의 하위 도메인에서 오는 요청은 막지 못합니다. 지금은 하위 도메인을 다른 용도로 쓰지 않아 문제가 없습니다.

**개선할 점**
- 하위 도메인을 늘리게 되면 쿠키를 쓰는 POST 에 `Origin` 헤더 검사를 더합니다.

→ [docs/27](27-security-review.ko.md) · [docs/41](41-api-security-applied.ko.md) · [docs/54 §1](54-improvements-vs-practice.ko.md)

---

## 9. 클러스터 안이면 누구나 내부 API 를 불렀다

**📖 참고서** — Razi Rais 외, *Zero Trust Networks*, 2nd ed. (O'Reilly, 2024). 네트워크 위치가 아니라 신원으로 판정하고, 워크로드마다 자격증명을 둔다는 원칙입니다.

**문제**
- 내부 전용 API 는 "신원 헤더가 없으면 내부 호출" 로 판정했습니다. 클러스터 안의 파드 하나만 장악하면 모두 부를 수 있었습니다.
- NetworkPolicy 는 0 개였습니다.
- 게이트웨이의 자동 라우트(`/{SERVICE-ID}/**`)에는 JWT 필터가 없었습니다.

**처리한 내용**
- 서비스 간 **mTLS** 를 도입했습니다. 서비스마다 인증서를 따로 둡니다(CN = 서비스 이름, cert-manager 가 90일 수명으로 자동 갱신하고 Reloader 가 재시작).
- 받는 쪽 `InternalCallerFilter` 가 인증서로 호출자를 판정합니다. 관찰 모드로 돌려 본 뒤 강제 모드로 바꿨습니다.
- 경로별 허용 목록(`INTERNAL_AUTH_PATH_RULES`)으로 "어느 서비스가 어느 내부 경로를 부를 수 있는가" 까지 봅니다. 이것도 관찰 단계를 거쳐 강제 모드로 바꿨고, Feign·gRPC 호출과 규칙이 맞는지는 가드(B-67·B-73)가 빌드에서 대조합니다.
- NetworkPolicy 는 기본 거부입니다. 자동 라우트는 껐습니다. 외부 노출은 Cloudflare Tunnel 로 해서 인바운드 포트가 0 개입니다.

**한계**
- 서버 인증서(`tls-keystore`)는 여전히 서비스들이 공유하고, 손으로 관리합니다.
- NetworkPolicy 는 들어오는 쪽(ingress)만 있고 나가는 쪽(egress) 규칙이 없습니다.
- Prometheus 수집이 TLS 검증을 생략합니다.

**개선할 점**
- 서버 인증서도 cert-manager 로 옮기고, egress 허용 목록과 Prometheus 의 CA 신뢰를 추가합니다.

→ [docs/57 §16·§18](57-remaining-priorities.ko.md) · [docs/18](18-certificates.ko.md)

---

## 10. git 이력에 2년간 남아 있던 자격증명

**📖 참고서** — Heather Adkins 외, *Building Secure and Reliable Systems* (Google/O'Reilly, 2020). 비밀 관리, 키 회전, 침해를 전제로 한 복구 설계를 다룹니다.

**문제**
- 공개 저장소 이력에 AWS 키, Gmail 앱 비밀번호, OAuth 시크릿, DB 비밀번호(커넥터 JSON 4개)가 남아 있었습니다.
- 이력을 지워도 포크, PR 참조(`refs/pull/*`), 캐시에 남습니다.

**처리한 내용**
- **폐기와 재발급**을 먼저 했습니다. 이력 정리는 그 다음이었습니다.
- 폐기 확인은 값을 비교하는 것이 아니라, **실제로 로그인해서 통하지 않는지**로 했습니다(`ops/34`).
- 비밀은 gitignore 된 Secret 으로만 다룹니다. CI 에서 커밋 범위를 비밀 스캔하고, 스크립트는 값을 출력하지 않게 했습니다(길이나 접두사만).

**한계**
- GitHub Push Protection 은 저장소 설정에 달려 있습니다(코드로 강제할 수 없습니다).
- 비밀이 k8s Secret(base64)에 평문과 같은 형태로 있습니다.

**개선할 점**
- Sealed Secrets, SOPS, 또는 외부 KMS 로 옮깁니다.
- 정기 회전을 자동화합니다.

→ [docs/30](30-git-history-secret-exposure.ko.md)

---

## 11. 소셜 로그인 — 켜는 순간 실패했을 구글, 그리고 카카오·LINE

**📖 참고서** — Jonathan LeBlanc · Tim Messerschmidt, *Identity and Data Security for Web Development* (O'Reilly, 2016). OAuth 2.0 과 OpenID Connect 의 흐름과 토큰 검증을 다룹니다.

**문제**
- 처음 코드는 클라이언트 id 와 시크릿이 `"아이디"` / `"비밀번호"` 로 하드코딩돼 있었고, 콜백 주소는 localhost 였습니다.
- 성공 핸들러는 `"google 12345"` 를 이메일로 조회해서, 모든 로그인이 `user_not_found` 로 끝났습니다.
- 그것을 고친 뒤에도 구글은 동작할 수 없었습니다.
  - 구글 등록은 `openid` scope 라서 **OIDC 경로**로 갑니다.
  - 그런데 ID 토큰 검증 라이브러리(`spring-security-oauth2-jose`)가 없어서 OIDC 처리기가 등록되지 않았습니다.
  - OIDC 용 사용자 서비스도 설정되지 않아서 계정 연결 코드가 불리지 않았습니다.
  - 운영에서 구글을 켠 적이 없어 드러나지 않았습니다.

**처리한 내용**
- 자격증명이 있는 프로바이더만 등록하고, 로그인 화면은 서버가 준 목록대로 버튼을 그립니다.
- PKCE 를 모든 등록에 적용하고, 인가 요청은 Redis 에 보관합니다(`code_verifier` 가 브라우저로 나가지 않게).
- 계정 연결은 **검증된 이메일**로만 합니다. 구글은 `email_verified`, 깃허브는 `/user/emails`, 카카오는 `is_email_valid` 와 `is_email_verified` 를 확인합니다.
- OIDC 경로(`CustomOidcUserService`)가 스프링의 ID 토큰 검증을 거친 뒤, OAuth2 경로와 **같은 연결 규칙**을 씁니다.
- LINE 은 ID 토큰이 ES256 이라 등록마다 알고리즘을 고릅니다.
- 2FA 계정은 소셜 로그인 뒤에도 2FA 를 거칩니다(별도 키로 서명한 보류 토큰).
- 실패하면 허용한 이유 코드만 들고 로그인 화면으로 돌아옵니다.

**한계**
- 구글, 카카오, LINE 모두 **실제 프로바이더로 끝까지 해 본 적이 없습니다.** 운영 Secret 에 아직 자격증명이 없습니다.
- LINE 은 채널의 이메일 권한이 승인돼야 로그인할 수 있습니다.

**개선할 점**
- 프로바이더마다 운영에서 한 번씩 끝까지 로그인해 보고 인수 체크리스트([docs/34](34-acceptance-checklist.ko.md))에 넣습니다.
- 소셜 로그인 실패 코드별 지표를 둡니다.

→ [docs/33 소셜 로그인](33-deploy-runbook.ko.md) · [docs/27](27-security-review.ko.md)

---

## 12. 롤링 배포 중에 옛 코드와 새 코드가 같은 DB·토픽을 쓴다

**📖 참고서** — *Designing Data-Intensive Applications*, 4장 "Encoding and Evolution". 앞뒤 호환성과 스키마 진화(Avro)를 다룹니다.

**문제**
- 신원을 이메일에서 uid 로 바꾸고, 옛 결제 컬럼을 지우고, 결제 메시지 스키마에서 필드를 추가하거나 삭제해야 했습니다.
- 롤링 배포 중에는 옛 파드와 새 파드가 **같은 DB, 같은 토픽, 같은 API** 를 동시에 씁니다. 한 번에 바꾸면 어느 한쪽이 깨집니다.

**처리한 내용**
- expand-contract 로 여러 릴리스에 나눴습니다(배포 열차 R1~R5).
  - 추가할 때는 "읽기는 새 스키마, 쓰기는 옛 스키마" 로 시작합니다.
  - 삭제할 때는 "다음 스키마를 먼저" 배포합니다.
- 마이그레이션이 스스로 멈추게 했습니다. 데이터 가드, Flyway 이력 시간 가드, 릴리스 표지를 둡니다.
- 되돌릴 수 없는 마이그레이션은 `ROLLBACK-UNSAFE` 로 선언하게 했습니다(B-54). `ops/28` 은 그것을 건너는 롤백을 거부합니다.

**한계**
- 결제 스키마 전환의 마지막 단계(C3 / R5)가 남아 있습니다. 옛 Avro v1·v2 이력이 있고, `providerOf` 호환 코드가 그대로입니다.

**개선할 점**
- 스키마 레지스트리의 호환성 모드를 CI 에서 검사합니다.
- 남은 단계를 끝내고 호환 코드를 지웁니다.

→ [docs/55](55-zero-downtime-migrations.ko.md) · [docs/20](20-schema-ownership.ko.md)

---

## 13. 테스트한 것과 배포한 것이 다른 바이너리였다

**📖 참고서** — *Building Microservices*, 2nd ed., 7장 "Build"와 8장 "Deployment". 산출물을 한 번만 만들어 환경마다 같은 것을 배포하는 원칙을 다룹니다.

**문제**
- 테스트용과 배포용 이미지를 따로 빌드했습니다.
- 이미지 태그는 사람이 고른 숫자였고, 배포와 롤백은 사람의 기억에 의존했습니다.

**처리한 내용**
- 커밋 스테이지에서 이미지를 **한 번만** 빌드합니다(amd64 · arm64, 태그 = 커밋 SHA). 같은 이미지로 통합 · 인수 테스트를 돌립니다.
- 배포는 스크립트 하나(`ops/28-deploy.sh <환경> <버전>`)로 합니다.
  - 이미지 존재를 확인하고, 되돌릴 수 없는 마이그레이션을 검사합니다.
  - 스모크 게이트가 실패하면 직전 버전으로 자동 복귀하고, 배포 이력을 남깁니다.
- 파이프라인이 초록이면 **사람이 승인한 뒤** 운영 노드의 셀프호스티드 러너가 같은 스크립트를 실행합니다.

**한계**
- 단일 노드라서 카나리나 블루-그린이 없습니다. 운영 오버레이는 HPA 를 적용하지 않습니다.
- 셀프호스티드 러너는 운영 자격증명을 갖습니다. 그래서 저장소가 비공개여야 합니다.

**개선할 점**
- 노드를 2 개로 늘리고 트래픽 일부를 새 버전으로 보내는 카나리를 둡니다.
- 배포 후 지표(오류율 · 지연)로 자동 판정합니다.

→ [docs/36](36-deployment-pipeline.ko.md)

---

## 14. 백업은 있다고 믿었지만 복원해 본 적이 없었다

**📖 참고서** — Betsy Beyer 외, *Site Reliability Engineering* (O'Reilly, 2016), 26장 "Data Integrity: What You Read Is What You Wrote". "필요한 것은 백업이 아니라 복원이다" 라는 관점입니다.

**문제**
- 백업이 없었고 설계 문서도 없었습니다.
- 백업 스크립트를 만든 뒤에도 cron 이 걸려 있지 않았습니다.

**처리한 내용**
- 데이터 등급별로 RPO / RTO 를 정했습니다.
- PostgreSQL PITR 과 MongoDB 백업을 두고, **실제로 `DROP TABLE` 을 낸 뒤 시점 복구로 되돌려** 검증했습니다.
- 백업 CronJob 과 주간 복원 훈련 잡을 만들었습니다.

**한계**
- 백업이 클러스터 안의 PVC 에 있습니다. 클러스터가 사라지면 백업도 사라집니다.
- `postgres-backup` 과 `restore-drill` 은 기본값이 `suspend: true` 라서 환경마다 사람이 켜야 합니다.

**개선할 점** (이 저장소에서 가장 마지막 순위로 둔 항목입니다)
- 오브젝트 스토리지로 보냅니다.
- 마지막 성공 백업 시각에 경보를 겁니다.

→ [docs/15](15-backup-and-dr.ko.md) · [docs/22](22-alerting-and-backup-automation.ko.md)

---

## 15. 경보가 어디로도 가지 않았고, 모든 서비스가 자기를 `common` 이라 불렀다

**📖 참고서** — Charity Majors 외, *Observability Engineering* (O'Reilly, 2022). 추적과 로그의 상관, 그리고 "경보가 실제로 도달하는가" 를 다룹니다.

**문제**
- Alertmanager 의 수신 채널이 플레이스홀더라서 경보가 **아무 데도 가지 않았습니다.**
- 공유 설정의 `spring.application.name` 때문에 모든 서비스가 스스로를 `common` 으로 보고해서 분산 추적이 무력했습니다.
- SMS 로그 발송기가 **휴대폰 인증번호를 평문으로** 운영 로그에 남겼습니다.
- 추적(Zipkin)에는 게이트웨이 · Feign 스팬의 **URL 쿼리(OAuth `code` · 인증 링크 `token`)가 그대로** 저장되고 있었습니다.

**처리한 내용**
- 경보는 "수용됐다" 가 아니라 "전달됐다" 를 지표로 확인합니다(`ops/17`).
- 서비스 이름을 바로잡았습니다. Kafka 를 건너도 추적이 이어지게 하고, Loki 로 모은 로그의 traceId 에서 추적으로 왕복할 수 있게 했습니다.
- 추적을 OpenTelemetry 데모의 구성(OTLP → Collector → Jaeger · span_metrics)으로 옮겼습니다. Collector 가 **저장 전에** 쿼리 · JWT · 이메일을 가리고, 추적에서 RED 지표를 만들며, 지연 그래프의 점(exemplar)에서 그 추적으로 갑니다. 서비스 코드는 벤더 중립 API 만 써서 바뀌지 않았습니다([docs/63](63-opentelemetry.ko.md)).
- 샘플링은 Collector 가 추적이 끝난 **뒤에** 합니다(꼬리 샘플링) — 오류 · 느린 요청 · 주문/결제/배송은 전부, 나머지 10%. RED 지표는 샘플링 **앞**에서 세어 비율과 상관없이 정확합니다. 추적은 파일(badger)에 7일 남습니다.
- 마스킹은 부르는 쪽 재량이 아니라 **출력 단계**에서 합니다. 인증번호는 로그에 남기지 않습니다.

**한계**
- 샘플링 결정(30초) 뒤에 난 비동기 오류는 추적에 남지 않습니다(로그 · 지표에는 남습니다).
- 추적 저장(badger)과 꼬리 샘플링은 노드 하나를 전제로 합니다.
- 로그 기반의 ERROR 급증 경보가 없습니다.
- 관측 스택이 노드의 compose 로 돌고 있습니다.

**개선할 점**
- 노드가 늘면 추적 id 기준 loadbalancing 내보내기 + OpenSearch/ClickHouse 저장으로 옮깁니다.
- SLO 기반 경보(오류 예산 소진율)로 옮깁니다.

→ [docs/11](11-observability-and-kafka-reliability.ko.md) · [docs/22](22-alerting-and-backup-automation.ko.md) · [docs/51](51-logging.ko.md) · [docs/63](63-opentelemetry.ko.md)

---

## 16. 목(mock) 테스트는 통과했는데 운영에서 저장·조회가 깨졌다

**📖 참고서** — Titus Winters 외, *Software Engineering at Google* (O'Reilly, 2020), 13장 "Test Doubles". "가능하면 실제 구현을, 다음으로 페이크를" 이라는 원칙입니다.

**문제**
- **장바구니가 저장되지 않았습니다.** 새 줄을 `merge` 로 붙였는데, PERSIST cascade 는 `merge` 에 적용되지 않습니다.
- **리뷰는 저장되는데 읽을 수 없었습니다.** 엔티티에 기본 생성자가 없었습니다.
- 둘 다 저장소를 목으로 대체한 테스트에서는 통과했습니다.

**처리한 내용**
- 요청마다 트랜잭션을 따로 열고 다음 요청에서 다시 읽는 **실제 JPA 테스트**(H2)를 추가했습니다.
- 기본 생성자는 가드(B-69)로 강제합니다.
- 새 서비스 체크리스트에 "실제 DB 로 도는 JPA 테스트 하나 이상" 을 넣었습니다.

**한계**
- H2 는 PostgreSQL 이 아닙니다. 부분 인덱스, `SKIP LOCKED`, jsonb 같은 방언 차이는 Testcontainers 통합 테스트(CI)에서만 잡힙니다.

**개선할 점**
- 저장소 계층 테스트를 Testcontainers PostgreSQL 로 옮깁니다. 속도 때문에 주기를 나눕니다.

→ [docs/61 §1](61-adding-a-service.ko.md) · [docs/17](17-testing.ko.md)

---

## 17. 좋아요가 1에서 0으로 — 19자리 id 를 JS 가 반올림했다

**📖 참고서** — David Flanagan, *JavaScript: The Definitive Guide*, 7th ed. (O'Reilly, 2020), 3장 "Types, Values, and Variables". JS 의 수는 IEEE 754 배정밀도라서, 2^53 을 넘는 정수는 정확히 표현되지 않습니다.

**문제**
- 리뷰 id 는 TSID(18~19자리 `Long`)입니다. JSON 숫자로 내보내면 브라우저가 끝자리를 반올림했습니다.
- 그래서 좋아요 요청이 **존재하지 않는 id** 로 갔고, 화면의 1 이 0 으로 돌아갔습니다.

**처리한 내용**
- 브라우저로 나가는 TSID 는 `ToStringSerializer` 로 문자열로 보냅니다.
- 새 서비스 체크리스트에 규칙으로 넣었습니다.

**한계**
- 모든 응답 DTO 를 전수 조사하지는 않았습니다.

**개선할 점**
- `Long` id 필드를 가진 응답 DTO 를 찾아 문자열 직렬화를 강제하는 가드를 둡니다(TSID 전수 작업).

→ [docs/61 §1-11](61-adding-a-service.ko.md)

---

## 18. 컴파일되고 테스트도 통과하는데 데이터가 틀렸다 — 모델 계층 점검

**📖 참고서** — Martin Kleppmann, *Designing Data-Intensive Applications* (O'Reilly, 2017), 7장 "Transactions". 읽고-고치고-쓰는 두 트랜잭션 사이의 **갱신 손실(lost update)** 과, 그것을 원자적 쓰기 · 조건부 갱신으로 막는 이야기입니다.

**문제** — 모델(엔티티) 폴더를 서비스마다 다시 읽자, 오류 없이 **틀린 데이터**를 만드는 결함이 나왔습니다.
- **가입이 DB 에서 거절되었습니다.** `User` 의 손으로 쓴 생성자가 `@Builder.Default` 초기값(등급 등)을 건너뛰어 NOT NULL 을 어겼습니다. Lombok 은 초기값을 빌더와 기본 생성자로만 옮깁니다.
- **쿠폰이 '사용됨' 이 되지 않아 다시 쓸 수 있었습니다.** 결제 응답 소비자가 헤더 조건에 걸려 상태 변경까지 가지 않았습니다.
- **`BaseEntity` 의 `@Data`** 가 equals 를 감사 필드로 만들어, 같은 순간에 만든 상세 행이 `Set` 에서 하나로 합쳐졌습니다.
- **상품 Q&A 가 저장되지 않고**(cascade 가 MERGE 뿐), **변형 등록은 늘 500** 이었습니다(orphanRemoval 컬렉션을 통째로 교체).
- **기본 배송지 변경**이 부분 유니크 인덱스에 걸렸고(Hibernate 의 flush 순서), **만료 토큰 정리**는 `Date` 를 `Instant` 필드에 넘겨 거절됐고, **긴 리뷰**는 B-tree 행 한도(2,704바이트)를 넘었습니다.
- **상담 배정이 사라졌습니다.** 고객 메시지가 스레드를 읽은 뒤 상담원이 조건부 UPDATE 로 맡으면, 메시지 쪽 커밋이 **모든 컬럼**을 읽어 둔 값(대기 · 담당자 없음)으로 다시 썼습니다. 채팅 시각은 UTC JVM 에서 9시간 일렀고, 상담 본문이 로그에 남았습니다.
- 셋째 줄 아래는 **H2 · 목 테스트에서 재현되지 않았습니다.**

**처리한 내용**
- 생성자는 `this()` 로 시작, 엔티티는 `@Getter @Setter`(식별자 동등성), 컬렉션은 내용만 교체(`clear + addAll`), cascade 는 `{PERSIST, MERGE}`.
- 할인가는 `common` 의 `Discount` 하나로 계산합니다. 결제 · 환불 시각은 엔티티가 스스로 기록합니다.
- `SupportThread` 에 `@DynamicUpdate` — 바뀐 컬럼만 씁니다. 배정은 원래 조건부 UPDATE 라 동시 배정은 이미 막혀 있었습니다.
- **가드** `EntityLombokGuardTest`(common) — 모든 서비스에서 엔티티의 `@Data` 를 금지하고, `@Builder.Default` 엔티티의 생성자가 `this()` 로 시작하는지 봅니다.
- 재현은 **실제 PostgreSQL**(Testcontainers)로 했습니다. 고친 것을 되돌리면 실패하는 것을 확인했습니다(`expected ACTIVE but was WAITING`, 중복 키, `index row size 6024 exceeds … 2704` 등).

**한계** — 스키마 변경이나 데이터 이전이 필요해 이번에 손대지 않은 것이 있습니다.
- catalog: `Notice` 의 `@Lob`(대형 객체 누수), `ProductSubcategory` 의 EAGER.
- cart: `Cart.userId` 유니크 없음, `CartProduct.style` 이 인덱스 값.
- chat: Mongo `ChatMessage` 인덱스, `ChatMember` 갱신 손실, `ParticipantChatRoom` 유니크, 쓰지 않는 Alarm 엔티티, 채팅방의 Redis 사본.
- order: `Order.taxPrice`, 주문 ↔ 주소 이중 외래 키, 쓰지 않는 `OrderCacheService`, 결제 `trackingId` 인덱스. coupon: `CouponStatus.EXPIRED`.
- 이미 저장된 채팅 메시지의 시각은 9시간 이른 채로 남습니다.

**개선할 점**
- 읽고-고치고-쓰는 엔티티에 `@Version`(낙관적 잠금)을 둡니다. `@DynamicUpdate` 는 **다른 컬럼**끼리의 덮어쓰기만 막습니다.
- 저장소 계층 테스트를 Testcontainers PostgreSQL 로 옮깁니다(§16 과 같은 결론).

→ [docs/17](17-testing.ko.md) · §16

---

## 참고서 목록

| 책 | 출판 | 사례 |
|---|---|---|
| Martin Kleppmann, *Designing Data-Intensive Applications* | O'Reilly, 2017 | 2 · 3 · 12 · 18 |
| Sam Newman, *Building Microservices*, 2nd ed. | O'Reilly, 2021 | 5 · 6 · 8 · 13 |
| Gwen Shapira, Todd Palino, Rajini Sivaram, Krit Petty, *Kafka: The Definitive Guide*, 2nd ed. | O'Reilly, 2021 | 4 |
| Andrew Hoffman, *Web Application Security*, 2nd ed. | O'Reilly, 2024 | 1 |
| Razi Rais, Christina Morillo, Evan Gilman, Doug Barth, *Zero Trust Networks*, 2nd ed. | O'Reilly, 2024 | 9 |
| Heather Adkins 외, *Building Secure and Reliable Systems* | O'Reilly, 2020 | 10 |
| Jonathan LeBlanc, Tim Messerschmidt, *Identity and Data Security for Web Development* | O'Reilly, 2016 | 11 |
| Betsy Beyer 외, *The Site Reliability Workbook* | O'Reilly, 2018 | 7 |
| Betsy Beyer 외, *Site Reliability Engineering* | O'Reilly, 2016 | 14 |
| Charity Majors, Liz Fong-Jones, George Miranda, *Observability Engineering* | O'Reilly, 2022 | 15 |
| Titus Winters, Tom Manshreck, Hyrum Wright, *Software Engineering at Google* | O'Reilly, 2020 | 16 |
| David Flanagan, *JavaScript: The Definitive Guide*, 7th ed. | O'Reilly, 2020 | 17 |
