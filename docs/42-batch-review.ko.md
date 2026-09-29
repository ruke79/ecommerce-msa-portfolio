# 42. 배치 점검 — 상품 임포트 잡과 스케줄 작업

[日本語](42-batch-review.md) | **한국어**

2026-09-15. "배치도 문제가 없는지" 확인한 결과다. 두 종류를 봤다:

1. **CSV 상품 임포트 잡**(스프링 배치, 9스텝) — 운영자가 손으로 누르는 일회성 작업
2. **스케줄 작업 8개**(`@Scheduled`) — 운영에서 계속 도는 것들

결론부터: 스케줄 작업은 문제없다. 임포트 잡에는 **재실행 함정**이 있었고 고쳤다.
남은 것 셋은 이 문서 끝에 근거와 함께 적었다.

---

## 1. 고친 것

### 1.1 재실행하면 카탈로그가 두 벌이 된다

**무슨 일이 있었나.** 이미지 업로드(S3 권한)에서 잡이 죽은 뒤 다시 눌렀더니 카테고리
5→10, 하위 카테고리 15→30 이 되고 상품에서 `uq_product_slug` 에 걸려 멈췄다. 운영자는
psql 로 직접 TRUNCATE 해야 했다.

**왜 그런가.** 두 가지가 겹친다.

- 프로세서들이 **조회 없이 새 엔티티를 만든다**. `JpaItemWriter` 도 `merge()` 대신
  `persist()` 로 덮어 두었다(BatchConfig 주석 참조) — 그래서 insert 만 한다.
- `ProductImportService` 가 실행마다 `time` 파라미터를 넣어 **새 JobInstance** 를 만든다.
  스프링 배치의 재시작(실패한 스텝부터 이어서)이 동작하지 않고 언제나 1단계부터 다시 돈다.

즉 **재실행은 되는데 멱등하지 않은**, 가장 나쁜 조합이었다. 유니크 제약이 없었다면
상품 500개짜리 카탈로그가 조용히 만들어졌을 것이다 — 카테고리·하위 카테고리에는 제약이
없어서 실제로 조용히 두 배가 됐다.

**고침.** 카탈로그가 비어 있지 않으면 **시작하지 않는다**(409). 메시지에 다음 행동을
적는다. 비우고 다시 넣으려면 `reset=true` — 관리자 화면에 "기존 카탈로그를 비우고 다시
넣기" 버튼이 그때만 나타난다.

`CatalogResetService` 가 `TRUNCATE ... RESTART IDENTITY CASCADE` 를 한다. 대상은 CSV 가
만드는 9개 테이블뿐이다 — `product.processed_event`(사가 멱등성 원장)와 `content.*`
(배너·공지·홈 섹션)는 건드리지 않는다.

### 1.2 ⚠️ 임포트는 식별자가 1부터 시작하는 것에 의존한다

위 `RESTART IDENTITY` 가 선택이 아닌 이유다. 그리고 이건 **아직 남아 있는 설계 결함**이다.

```java
// CategoryItemProcessor — id 를 CSV 에서 가져오지 않는다(DB 가 생성)
return Category.builder().categoryName(...).slug(...).build();

// ProductItemProcessor — 그런데 CSV 에 적힌 id 로 찾는다
categoryRepository.findById(item.getCategory_id())
        .orElseThrow(() -> new IllegalArgumentException("Invalid category_id: " + ...));
```

두 값이 맞아떨어지는 것은 **시퀀스가 1부터 시작할 때뿐**이다. 따라서:

- 행만 지우고 시퀀스를 남기면 다음 임포트가 `Invalid category_id: 1` 로 죽는다.
- 임포트 전에 `/admin/category` 로 카테고리를 하나라도 만들면 id 가 밀려 실패한다.

지금은 `reset` 이 시퀀스까지 되돌려 이 전제를 지킨다. 제대로 고치려면 프로세서가
**CSV 의 id 가 아니라 이름/슬러그로** 카테고리를 찾아야 한다. 그건 CSV 스키마와 함께
손봐야 해서 이번에는 전제를 명시하고 지키는 쪽으로 뒀다.

### 1.3 ES 재색인이 요청 스레드를 붙잡고, 중복 실행을 막지 않았다

`POST /catalog-service/api/products/sync` (관리자 전용 — `AdminWriteInterceptor` 가
`ROLE_ADMIN` 을 요구한다. 인가는 정상이다.)

- `jobLauncher.run` 을 **요청 스레드에서 동기로** 돌렸다. 부트 기본 런처는
  `SyncTaskExecutor` 라 잡이 끝날 때까지 응답이 안 나간다 → 게이트웨이 10초·프론트 15초
  타임아웃에 먼저 걸린다. 관리자는 실패로 보지만 잡은 계속 돈다 → **다시 누른다.**
- 그런데 중복 실행 가드가 없었다. 리더 빈이 `@StepScope` 없는 **싱글턴**이라
  (`ElasticsearchBatchConfig`), 같은 잡이 둘 돌면 같은 리더 인스턴스를 나눠 읽는다.
  읽기 위치가 뒤엉켜 색인이 중간부터 비거나 `ReaderNotOpenException` 이 난다.

`ProductImportService` 와 같은 방식으로 고쳤다 — 즉시 202, 데몬 스레드에서 실행,
한 번에 하나만(409).

임포트와 달리 이 잡은 **재실행이 안전하다**: 새 인덱스(`products-<시각>`)에 쓴 뒤 별칭을
바꾸므로 실패해도 현재 검색은 그대로다.

---

## 2. 이미 맞게 돼 있던 것

| 확인 항목 | 결과 |
|---|---|
| 기동 시 자동 실행 | 없음. `BatchJobRunner` 는 파일 전체가 주석이고 `spring.batch.job.enabled: false` |
| 배치 메타데이터 위치 | 기본 데이터소스(productdb)의 `meta` 스키마. V3 마이그레이션이 만든다(docs/16 §7) |
| 트랜잭션 매니저 | `batchTransactionManager` = 데이터 EMF 의 `JpaTransactionManager`. 청크와 `JpaItemWriter` 가 같은 트랜잭션 — 정상 |
| CSV·이미지 리소스 | `resources/csv` 9개, `resources/images` 38MB 모두 jar 에 포함 |
| 임포트 인가 | 게이트웨이 `catalog-service-write`(POST → JwtAuthorization) + `AdminWriteInterceptor`(ROLE_ADMIN) |
| 저장소 사전 점검 | 시작 전 S3 쓰기 한 번. 실패하면 503 으로 거절해 절반만 들어간 카탈로그를 안 만든다 |
| 이미지 업로드 실패 처리 | 삼키지 않고 던진다 → 스텝 실패로 이력에 남는다(`uploadClasspathImage`) |

### 스케줄 작업 8개 — 레플리카가 둘이어도 안전한가

운영에서 계속 도는 쪽이라 더 중요하다. prod HPA 는 `minReplicas: 2` 다.

| 작업 | 선점 방식 | 판정 |
|---|---|---|
| `PaymentOutboxScheduler` (order) | `FOR UPDATE SKIP LOCKED` + 발행을 트랜잭션 안에서 동기 확정 | ✅ |
| `OrderOutboxScheduler` (pay) | 같음 | ✅ |
| `TrackingPoller` (delivery) | `claimDueForPolling` — `FOR UPDATE SKIP LOCKED` | ✅ |
| `MailDispatcher` · `SmsDispatcher` (notification) | 발송 원장 + 상태 전이 | ✅ (docs/29) |
| `MailDispatcher` 스크럽 | 24h 지난 params 삭제 — 멱등 | ✅ |
| `NotificationService.heartbeat` (user) | 메모리 emitter 맵. 파드별로 독립적이어야 맞다 | ✅ |
| `WithdrawalProcessor` (user) | 잠금 없음. 상태 검사(`isWithdrawalPending`)로 걸러 낸다 | ⚠️ 아래 |
| `UnverifiedAccountCleanup` (user) | 잠금 없음 | ⚠️ 아래 |

뒤의 둘은 `FOR UPDATE SKIP LOCKED` 가 없어 레플리카 둘이 같은 행을 집을 수 있다. 다만
결과는 **중복 작업**이지 데이터 손상이 아니다 — 두 경로 모두 상태를 다시 읽고 확인하며
(`finalizeWithdrawal` 은 이미 처리됐으면 false 를 돌려준다), 하는 일이 익명화·삭제라
두 번 해도 결과가 같다. `User` 의 `@Version` 이 충돌을 잡으면 한쪽은 예외로 빠지고 다음
주기에 다시 시도한다. 우선순위가 낮아 두었다.

### k8s CronJob

`postgres-backup`·`mongo-backup`·`restore-drill` 은 모두 `suspend: true` 다. 의도된
상태다(docs/에 적힌 대로, 되는지 확인한 뒤 켠다). 현재 백업은 **돌지 않는다** — PVC 가
유일한 사본이라는 뜻이다. `ops/29` 로 PITR 을 켜는 것이 다음 순서다.

---

## 3. 남은 것 (고치지 않았다)

### 3.1 S3 업로드가 청크 트랜잭션 안에 있다

`ProductColorItemProcessor`·`ProductSkuItemProcessor` 가 `process()` 안에서 S3 로 동기
업로드한다. 스프링 배치의 청크 트랜잭션은 read→process→write 를 감싸므로, 청크 10건이
도쿄로 올라가는 동안(건당 수백 ms) **DB 트랜잭션과 커넥션이 계속 잡혀 있다.**
`connection-timeout: 5000`, 풀 5인 환경에서 1018건(색상 509 + SKU 509)을 이렇게 처리한다.

지금은 일회성 작업이고 한 번에 하나만 돌아 문제가 되지 않았다. 제대로 하려면 업로드를
별도 스텝으로 분리해 URL 만 들고 DB 스텝에 넘긴다. 구조 변경이라 미룬다.

### 3.2 내결함성이 없다

어떤 스텝도 `faultTolerant()`/`skip()`/`retry()` 를 쓰지 않는다. 한 행이 나쁘거나 S3 가
한 번 흔들리면 잡 전체가 실패한다. **1.1 을 고치기 전에는** 그 뒤 재시도가 중복을 만들어서
더 위험했다 — 지금은 `reset` 으로 안전하게 다시 돌릴 수 있으니 우선순위가 내려갔다.

이미지 업로드에 짧은 재시도(`retryLimit(2)` + `RetryableException`)를 붙이면 일시적
네트워크 실패로 전체를 다시 돌리는 일이 줄어든다.

### 3.3 동시 실행 가드가 JVM 하나짜리다

`AtomicBoolean` 이라 **레플리카가 둘이면 서로의 실행을 모른다.** prod HPA 는 catalog-service
`minReplicas: 2` 다. 관리자가 두 번 눌러 다른 파드로 가면 두 잡이 동시에 돈다 — 싱글턴
리더를 나눠 읽으므로 결과를 예측할 수 없다.

1.1 의 "비어 있지 않으면 거절"이 대부분을 막는다(두 번째가 첫 번째의 데이터를 보고 거절).
다만 **거의 동시에** 누르면 둘 다 빈 카탈로그를 보고 통과할 수 있다. 완전히 막으려면
Postgres 어드바이저리 락(`pg_try_advisory_lock`)으로 올려야 한다. 현재 운영(local 오버레이,
레플리카 1)에서는 발생하지 않는다.

---

## 4. 회귀 테스트

| 막는 것 | 테스트 |
|---|---|
| 카탈로그가 차 있는데 임포트가 시작되는 것 | `ProductImportGuardTest` |
| 거절 뒤 `running` 플래그가 잠긴 채 남는 것 | 같은 테스트(그러면 파드 재시작 말고는 방법이 없다) |
| 첫 임포트(빈 카탈로그)가 막히는 것 | 같은 테스트 |
| 저장소 불가 상태에서 시작하는 것 | 같은 테스트 + `ProductImportServiceTest` |
| 동기 실행·중복 실행 | `ProductImportServiceTest` |

둘 다 일부러 깨서 확인했다 — 비어있음 검사를 빼면 실패하고, 거절 경로에서 플래그를 안
풀면 실패한다.

⚠️ 프론트엔드에서 하나 더 잡았다. `onClick={start}` 는 클릭 **이벤트**를 첫 인자로 넘긴다.
`start(reset)` 로 바꾸면서 그대로 뒀다면 **일반 버튼이 카탈로그를 지웠을 것이다.** 호출부를
`() => start(false)` 로 고치고, 함수 안에서도 `resetArg === true` 로 좁혀 두 겹으로 막았다.
