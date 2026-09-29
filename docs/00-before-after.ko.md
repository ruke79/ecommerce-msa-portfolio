# 전/후 종합 — 무엇이 왜 문제였고, 어떻게 고쳤나

[日本語](00-before-after.md) | **한국어**

> 🕰️ **상태(2026-09-27) — 일부 낡음.** §0 의 숫자(테스트 126건 · CI 3잡 · 가드 15종)는 작성 당시 값입니다. 지금은 백엔드 1,637 · 프론트 930 · 가드 B-1~69, CI 는 [docs/36](36-deployment-pipeline.ko.md). 최신 열린 항목은 [docs/54 §10](54-improvements-vs-practice.ko.md), 사례별 한계는 [docs/62](62-engineering-casebook.ko.md).

> 작성일: 2026-07-28
> 이 문서는 **한눈에 보는 색인**이다. 각 항목의 재현 로그·근거·상세 절차는 링크된 문서에 있다.
> 심각도: 🔴 기동 불가/데이터 손실/보안 · 🟠 기능 결함/운영 위험 · 🟡 품질

## 0. 숫자로 보는 전/후

| 항목 | 전 | 후 |
|---|---|---|
| 새 클론에서 기동하는 서비스 | **0 / 8** | **8 / 8** (컨테이너 이미지 포함) |
| 발견·수정한 결함 | — | **기동 블로커 29건(B-1~29) + FE 10건 + CAT 16건 등** |
| 테스트 | 47건, 6건 영구 빨간불 | **126건 / 실패 0 / 스킵 0** (12개 모듈 전체, `cleanTest test -PwithContainers`) |
| CI | 없음 | 단위·통합·프론트 3잡 + **주간 PITR 복원 훈련** |
| 사가 전 구간(주문→결제→재고) | 실행된 적 없음 | 서비스 컨테이너 3종으로 **자동 검증** |
| 채팅 메시지 저장 | **한 건도 저장된 적 없음** | STOMP→Kafka→저장→브로드캐스트 자동 검증 |
| 백업 | 0 (설계 문서도 없음) | PG PITR + Mongo, **DROP TABLE 을 실제로 되돌려 검증** |
| 인증서 | 원저자 PC에만 존재 | 스크립트 발급→주입→자체검증 6종 |
| 커밋된 DB 비밀번호 | 평문 4개 파일 | 제거 + config provider (로테이션은 운영 몫 — **런북은 컨테이너 리허설로 검증**) |
| 스테이징 리허설 | ❌ 전 스택 동시 기동을 해 본 적 없음 | ✅ [ops/12](../ops/12-staging-rehearsal.sh) — 인프라 5종+서비스 9종, **스모크 13통과/0실패** |

---

## 1. 기동 불가 블로커 — B-1 ~ B-29

"컴파일도 되고 리뷰도 통과하지만 실행하면 죽는" 부류. **실제로 띄워 보고서야** 전부 드러났다.
상세: [docs/14](14-boot-test-findings.ko.md) · [docs/16](16-saga-e2e-and-observability-verification.ko.md) · [docs/18](18-certificates.ko.md)

| ID | 전 (증상) | 원인 | 후 (해결) |
|---|---|---|---|
| B-1 🔴 | 6개 서비스 기동 즉시 중단 | profile 전용 파일 안의 `spring.profiles.active` (Boot 2.4+ 금지) | 제거 + **가드레일 테스트** |
| B-2 🔴 | 5개 서비스 기동 불가 | `feign.*-url` 프로퍼티가 어디에도 정의 안 됨 | 기본값 정의 + `FEIGN_*_URL` 오버라이드 |
| B-3 🔴 | order-service 기동 불가 | Redis 프로퍼티 미정의 | 정의 추가 |
| B-4/5 | 인덱스 이름 충돌·중복 생성 | `idx_order` 중복 등 | 정정 |
| B-6 | Redisson 설정 전체 주석 | — | 복원 |
| B-7 🔴 | 5개 서비스 기동 불가 | Redis 프로퍼티 이름 불일치 | 코드가 읽는 이름으로 통일 |
| B-8 🔴 | catalog 스키마 미생성 | 커스텀 EMF 가 `ddl-auto` 를 전달받지 못함 | 전달 배선 |
| B-9 🔴 | (작업 중 발생) 멱등 원장 미탐지 | `ProcessedEvent` 가 EMF 스캔 밖 | 스캔 포함 |
| B-10 🔴 | chat-service 기동 불가 | 환경변수 오타 `LOCAL_KEY_STOE_PASSWORD` | 수정 + **오타 가드레일** |
| B-11 🔴 | k8s 프로브 영원히 실패, Prometheus 수집 불가 | user-service 액추에이터 401 | `EndpointRequest` permitAll + 실HTTP 테스트 |
| B-12 🔴 | chat 테이블이 하나도 안 만들어짐 | PostgreSQL 에 **MySQL Dialect** → DDL 전부 실패(WARN 만 남음) | 수정 + 가드레일 |
| B-13 🔴 | coupon·chat 이 테이블 없이 "정상" 기동 | 엔티티가 쓰는 스키마를 아무도 생성 안 함 | Flyway 마이그레이션 |
| B-14 🔴 | chat·cart Redis 접속 불가 | Redisson 이 평문 Redis 에 TLS 핸드셰이크 | `ssl.enabled` 존중 |
| B-15 🔴 | 실서비스 500 (테스트에 가려짐) | catalog 널 역참조 4건 | 널 안전화 + 회귀 테스트 |
| B-16~18 🟠 | 테스트 스위트 상시 빨간불 22건 | 존재하지 않는 API 검증, `@ComponentScan` 오염, 인프라 필요 테스트 | 정리 → **처음으로 전체 초록불** |
| B-19 🟡 | readiness UP 인데 실제론 불능 | MongoDB 다운을 프로브가 반영 안 함 | 설계 이슈로 문서화 |
| B-20 🔴 | **새 클론은 빌드조차 불가** | 빌드가 `.gitignore` 된 비밀 파일에 의존 | `optional:` import + 기본값 분리 |
| B-21 🔴 | 아웃박스 이벤트가 **월 경계에서 잘못된 파티션**행 | `created_at` 이 `Asia/Seoul` 하드코딩 (파티션 키!) — 4개 서비스 | UTC 통일 + 회귀 테스트 4건 |
| B-22 🔴 | **모든 서비스가 스스로를 `common` 이라 보고** — 분산 추적 무력화 | 공유 설정의 `spring.application.name` | 제거 + 가드레일 |
| B-23 🔴 | TLS 없는 MongoDB 에 접속 자체 불가 | Mongo 클라이언트가 TLS 하드코딩 | 접속 문자열의 `?ssl=` 존중 |
| B-24 🔴 | 채팅이 전달만 되고 **저장 0건** + roomId/닉네임 뒤바뀜 + 파싱 크래시 | 저장 호출 부재, 매퍼 인자 순서, 무방비 파싱 | 컨슈머 측 저장 배선 + 매퍼 수정 + **STOMP E2E 테스트** |
| B-25 🔴 | 배치 잡 실행 불가 | Spring Batch 메타 스키마 미생성 | Flyway `meta.BATCH_*` + 소유권 결정([docs/16 §7](16-saga-e2e-and-observability-verification.ko.md)) |
| B-26 🔴 | **ES 인덱스 생성 자체가 실패** | `es-settings.json` 이 `settings` 로 이중 래핑 | 래퍼 제거 + 가드레일 |
| B-27 🔴 | 검색이 첫 호출만 성공, 캐시 히트마다 500 | `PageImpl` 역직렬화 불가 | 커스텀 Jackson 모듈 + 테스트 |
| B-28 🔴 | 어떤 머신에서도 존재하지 않는 경로 참조 | 7개 서비스에 `file:E:/github/...` (원저자 PC 경로) 하드코딩 | 환경변수 주입 + 가드레일 |
| B-29 🔴 | **mTLS 켜는 순간 전 서비스 기동 불가** (잠복) | 19개 클래스가 `.p12` 를 `"JKS"` 로 선언 | 형식도 주입 + 가드레일 |

---

## 2. 회복탄력성 · 게이트웨이 · Kafka 신뢰성

상세: [docs/01](01-branch-issues.ko.md) → [docs/02](02-improvements.ko.md) · [docs/11](11-observability-and-kafka-reliability.ko.md) · [docs/12](12-dlq-rollout-idempotency-alerts.ko.md)

| 전 | 후 |
|---|---|
| 🔴 게이트웨이 서킷브레이커 주석 처리, 레이트리미터 미완성 | 서킷브레이커 배선 + Redis 토큰버킷 레이트리미터 활성(429 실측) |
| 🔴 Feign 타임아웃 없음 → 느린 다운스트림이 워커 스레드 고갈 → 연쇄 장애 | 공통 타임아웃 표준(3s/5s) |
| 🔴 Kafka 컨슈머 에러 처리 전무 — poison message 하나가 컨슈머를 영원히 정지 | **전 서비스 DLQ**(`DeadLetterPublishingRecoverer`), 격리 실증(망가진 메시지 뒤 정상 메시지 처리 확인) |
| 🔴 재전달 시 **재고 이중 차감** (금전 손실) | `processed_event` 멱등 원장(동일 트랜잭션) — 재전달 3회에도 재고 불변, 자동 테스트 |
| 🟠 배포 healthcheck/재시작/리소스 제한 전무 | k8s 매니페스트 전체([k8s/](../k8s/README.ko.md)): 프로브·리소스·HPA·PDB |
| 🔴 **아웃박스 폴러가 레플리카마다 같은 이벤트를 발행** — 결제 요청 이중 발행. HPA 가 order·pay 를 max 3 으로 대상에 넣고 있어 **부하만 오르면 저절로 터지는** 상태였다 | `FOR UPDATE SKIP LOCKED` 선점 + 트랜잭션 안 동기 발행. 동시 트랜잭션 2개로 재현·검증([docs/21](21-horizontal-scaling.ko.md)) — 잠금을 빼면 배치 4건이 100% 겹친다 |
| 🔴 브로커가 잠깐 흔들리면 그 이벤트가 **영구 유실** (FAILED 로 적히는데 스케줄러는 STARTED 만 조회) | 일시적 실패는 상태를 두어 다음 폴링이 재시도. FAILED 는 재시도가 무의미한 경우에만 |
| 🔴 결제 중복 감지가 **한 번도 동작한 적 없음** — `"23000" == getSQLState()` (String 을 == 로 비교, 값도 PostgreSQL 것이 아님) | `DuplicateKeyException` 으로 직접 포착 — SQLState 문자열을 손으로 비교하지 않는다 |
| 🔴 Authorization 헤더가 없으면 **배치가 통째로 조용히 사라짐** (WARN 한 줄) | 예외를 던져 DLT 에 남긴다. 처리 거부 판단은 유지하되 결과가 보이게 |
| 🟠 전 서비스 `replicas: 1` — 재시작·노드 교체마다 다운타임 | 확장 안전한 8종은 **2**, 상태를 파드에 두는 chat·eureka 는 1 고정 + 가드레일 B-34 |

## 3. 검색 · 카탈로그 (CAT-1~16)

상세: [docs/06](06-catalog-search-issues.ko.md) → [docs/07](07-catalog-search-improvements.ko.md) · [docs/13 §3](13-secrets-dbha-es-search.ko.md)

| 전 | 후 |
|---|---|
| 🔴 CAT-1: "ES 검색"이 사실 DB LIKE 쿼리 | ES 전환 스위치 + 재색인·검증 스크립트([ops/03](../ops/03-enable-es-search.sh)), 색인→검색 실증 |
| 🔴 CAT-2/12: 등록 상품이 **최대 60분간 검색 미반영** | 캐시 무효화 + 자기호출 우회 수정 |
| 🔴 CAT-13: 다중 항목 주문 시 **첫 항목만 재고 반영** | 수정 + 사가 테스트에 다중 항목 케이스 |
| 🔴 CAT-9/10: 매핑·애널라이저 미적용, alias 롤오버 버그 | 수정 (nori 만 잔여 — §7) |
| 🔴 CAT-15: `ddl-auto: create-drop` — 재기동마다 **전 데이터 삭제** | Flyway 가 스키마를 소유하고 Hibernate 는 `validate` 만([docs/20](20-schema-ownership.ko.md)) |
| 🟠 CAT-3/4/14: 페이지 파라미터 미검증, category 500, ReDoS | 검증·수정 |

## 4. 프론트엔드 (FE-1~10)

상세: [docs/04](04-frontend-issues.ko.md) → [docs/05](05-frontend-improvements.ko.md)

| 전 | 후 |
|---|---|
| 🔴 FE-2: axios 래퍼가 config 를 버림 → 무한 대기 | 정상화 + 요청 타임아웃 |
| 🔴 FE-3: 프로액티브 리프레시 타이머 시간 계산 오류 | 수정 |
| 🟠 FE-1/4/5: localStorage 토큰 잔존, 토큰 콘솔 유출, 401 큐 데드락 | 제거·수정 |
| 🟡 FE-7/8/9: 깨진 재시도, 미사용 의존성, 죽은 코드 | 정리, 프로덕션 빌드 CI 검증 |

## 5. 데이터 — 파티셔닝 · 마이그레이션 · 성능

상세: [docs/09](09-postgresql-partitioning-sharding.ko.md) · [docs/19](19-local-performance-tuning.ko.md) · [docs/20](20-schema-ownership.ko.md)

| 전 | 후 |
|---|---|
| 🔴 스키마를 Hibernate `ddl-auto: update` 가 소유 — ①변경 이력 없음 ②운영 DB 에 검토 없는 DDL ③**제약을 지우지 않음**(③은 이미 사고를 냈다 — 아래 색상 항목) | **Flyway 가 전부 소유**, Hibernate 는 `validate` 로 검증만. 베이스라인은 손으로 쓰지 않고 Hibernate 의 DDL 을 [기계 변환](../ops/tools/hbm2flyway.py) — 7개 서비스 전부 빈 DB 에서 `validate` 통과로 실증, 레거시 DB 재현본에서는 **184개 스키마 항목 불변**. 되돌아오면 가드레일 B-33 이 막는다 |
| 🔴 아웃박스 테이블 무한 성장 | `created_at` RANGE 월 파티션 — 오래된 파티션 DETACH 로 백업/정리 비용을 활성 구간에 비례하게 |
| 🔴 **`ProductSku.color` 가 `@OneToOne`** — `color_id` 전역 UNIQUE 로 SKU 총량이 색상 행 수에 묶임. 시드가 509=509 로 제약을 포화시켜 **상품 1건만 더 넣어도 적재 실패**, 색상 필터 패싯도 무의미 | 저장소 코드가 일관되게 공유 어휘를 전제(`DISTINCT b.color`, `findByColor→Optional`, `ColorsFilter.jsx`, `ProductColor` 의 주석 처리된 `@OneToMany`)하므로 **매핑 오류**로 판정 → `@ManyToOne` + [V4 마이그레이션](../catalog-service/src/main/resources/db/migration/V4__relax_product_sku_color_unique.sql). 제약을 **이름이 아니라 정의**로 찾아 지우고, 사라지는 인덱스는 엔티티 `@Table(indexes=)` 로 보전. 회귀 테스트가 애노테이션 + `pg_constraint` 양쪽 검사 |
| 🟠 N+1 — 상품 목록이 상품당 별도 쿼리 | LAZY 컬렉션 4종에 `@BatchSize`, **SQL 개수 회귀 테스트**로 고정 |
| 🟠 커넥션 풀 무제한 가정 | `datasource × pool × replicas ≤ max_connections` 를 가드레일(B-32)이 산술 검증 |
| 🟠 성능은 추측 — 시드 509건으로는 인덱스 선택도를 볼 수 없음 | 시드 CSV 를 **분포 보존 증식**([생성기](../ops/tools/generate-catalog-data.py)) → 5만 상품/55만 행에서 `EXPLAIN ANALYZE` 실측. 이름 검색이 Seq Scan(8ms, 48,800행 버림)임을 **숫자로** 확인 |
| 🟠 샤딩 전략 부재 | 설계 문서화(적용은 규모 도달 시) |

## 6. 시크릿 · 인증서 · 보안

상세: [docs/13](13-secrets-dbha-es-search.ko.md) · [docs/18](18-certificates.ko.md)

| 전 | 후 |
|---|---|
| 🔴 DB 비밀번호가 커넥터 JSON 4개에 **평문 커밋** | 파일 config provider 참조로 교체. **git 이력에는 남아 있으므로 유일한 실질 해결은 로테이션**([ops/01](../ops/01-rotate-db-password.sh)) — 운영 몫 |
| 🟠 배포 호스트명이 코드·커넥터에 하드코딩 (정찰 정보 + 가입 메일 링크가 항상 한 호스트) | `app.public-url` 프로퍼티 + connect-secrets 의 `db-host` 로 외부화 |
| 🔴 게이트웨이 `useInsecureTrustManager: true` — 다운스트림 TLS 검증 전면 무력화(MITM) | 기본 `false`. 진짜 트러스트스토어가 생겨서 가능해짐 |
| 🔴 인증서 발급 절차 부재 — 새 환경에서는 아무것도 못 띄움 | [ops/07](../ops/07-issue-certificates.sh): 로컬 CA/Let's Encrypt 발급→PKCS12/JKS 변환→env·k8s Secret 주입→자체검증 6종. **eureka 실기동으로 증명** |
| 🟠 시크릿 값이 매니페스트에 섞일 위험 | `20-secret.example.yaml` 템플릿 + 실파일 gitignore |

## 7. 관측성

상세: [docs/11](11-observability-and-kafka-reliability.ko.md) · [docs/12](12-dlq-rollout-idempotency-alerts.ko.md) · [docs/16 §3](16-saga-e2e-and-observability-verification.ko.md)

| 전 | 후 |
|---|---|
| 🟠 분산 추적 없음 | Micrometer Tracing + Zipkin — 스팬이 서비스별로 분리됨을 실증(B-22 수정 후) |
| 🟠 메트릭 노출 불완전 | Prometheus 9/9 타깃 수집, p99 히스토그램 노출 |
| 🟠 경보 없음 | 규칙 9종(가용성·5xx·p99·힙·**백업 4종**) — 발화→Alertmanager 전달까지 실측 |
| 🟡 대시보드 없음 | Grafana 코드화(쿼리 데이터 반환 확인, 렌더링만 미확인) |
| 🔴 **경보가 아무 데도 가지 않고 있었다** — 수신 채널이 주석 처리된 플레이스홀더. 규칙은 발화하고 대시보드는 초록불이라 알아채기 어렵다 | 웹훅 URL 을 커밋되지 않는 파일로(`url_file`), severity 라우팅 + 원인 경보만 남기는 억제 규칙. [ops/17](../ops/17-configure-alert-receivers.sh) 이 테스트 경보를 발사해 **전달 성공까지** 확인 — 처음엔 검사가 전부 초록불인데 시크릿 파일 권한 때문에 전달만 실패하고 있었다([docs/22](22-alerting-and-backup-automation.ko.md)) |

## 8. 백업 · 재해복구

상세: [docs/15](15-backup-and-dr.ko.md)

| 전 | 후 |
|---|---|
| 🔴 백업 **0** — DROP TABLE·랜섬웨어면 끝 (복제는 사고를 **전파**할 뿐) | [ops/08](../ops/08-backup-postgres.sh) base backup + WAL 아카이빙, 준비 상태 점검·경보 지표 |
| 🔴 복구 절차 없음 | [ops/09](../ops/09-restore-drill.sh): **사고를 실제로 내고 시점 복구** — 800/800행·합계·스키마 일치, RTO 실측, CI 주간 훈련 |
| 🔴 MongoDB(채팅, 재생성 불가) 무백업 | [ops/10](../ops/10-backup-mongo.sh): 덤프 후 **빈 인스턴스 복원·대조**까지 |
| 🟠 백업의 조용한 실패 | `backup-health` 경보 — 지표 **부재** 자체도 경보 |
| 🔴 백업 스크립트는 검증했지만 **어디서도 돌고 있지 않았다** (문서의 crontab 예시뿐) | [k8s/85-backup-cronjobs.yaml](../k8s/85-backup-cronjobs.yaml) — pg·mongo 일일 백업 + 주간 복원 훈련을 CronJob 으로. 컨테이너에서 실행해 백업→복원→데이터 읽기까지 확인. 적용 전 replication 접속 준비가 필요하다는 것도 그 과정에서 드러났다 |

## 9. 테스트 · CI

상세: [docs/17](17-testing.ko.md)

| 전 | 후 |
|---|---|
| 47건, 6건 영구 빨간불(그 뒤에 실제 NPE 4건이 숨어 있었음) | **126건 / 실패 0 / 스킵 0** (12개 모듈 전체, `cleanTest test -PwithContainers`) |
| 인프라 검증은 사람 손 | Testcontainers — 8개 서비스 실기동, 사가·채팅·검색 전 구간 |
| 설정 결함은 리뷰로만 (거의 못 잡음) | **가드레일 15종** — 실제로 버그를 되돌려 넣어 실패하는지 확인함 |
| CI 없음 (새 클론이 빌드 불가라는 사실을 아무도 모름) | 푸시/PR 마다 3잡 + 주간 복원 훈련 |

---

## 10. 정직하게: 아직 안 된 것

| 항목 | 상태 | 왜 |
|---|---|---|
| ~~DB 비밀번호 로테이션~~ | ✅ **종결(2026-09-25)** | 유출 값이 운영 DB·Redis·모든 Secret 어디서도 통하지 않음을 로그인으로 실측(대조군 포함). 배포 때 새로 만든 값을 쓰고 있었다 — docs/30 §6 |
| ~~노출됐던 DB 호스트 방화벽 점검~~ | ✅ 해당 없음 | 그 호스트(`mywebserv.site`)는 더 이상 쓰지 않는다 |
| 백업 cron 을 실제 운영 DB 에 | 🟠 | 스크립트 검증 ≠ 백업이 돌고 있음 |
| nori 한국어 분석기 | ❌ 보류 | ES 플러그인을 직접 작성·유지보수해야 해서 의도적으로 미룸 |
| Grafana 패널 렌더링 | ⚠️ 미확인 | 바이너리 다운로드 차단 환경 |
| Redis 리프레시 토큰 백업 | 🟠 | 유실 시 전원 재로그인 (치명은 아님) |
| 시크릿 관리 도구(Vault/SOPS) 이관, 부하 테스트 | ❌ | 로드맵 |

## 11. 2026-09 — 실무 기준에 대면 무엇이 부족했고, 무엇이 불필요했나

배포해 보고 쓰면서 드러난 것들이다. 각 항목은 **왜 문제였는지 → 실무에서는 보통 어떻게 하는지 → 여기서 무엇을
바꿨는지** 순서다. 상세 근거는 링크한 문서와 코드 주석에 있다.

> 📌 **프로젝트 전체의 개선을 같은 형식(문제 → 왜 → 실무 기준 → 최종 상태)으로 다시 묶은 것은
> [docs/54](54-improvements-vs-practice.ko.md) 다.** 이 절은 2026-09 작업분만의 요약이다.

### 11.1 보안 — 부족했던 것

| 문제(전) | 실무 기준 | 바꾼 것(후) |
|---|---|---|
| 🔴 **로그인한 아무나 남의 이메일·프로필을 id 로 조회**(`/api/auth/user/email/{id}` 등 셋, IDOR). 앞서 `/user/id` 하나만 막고 형제를 놓쳤고, 판정도 `User-Role` 하나만 봤다 | OWASP API1(BOLA). 내부 전용 API 는 외부 라우팅에서 빼거나 별도 포트, 서비스 간은 mTLS·서비스 토큰 | 다섯 엔드포인트 모두 `InternalCallGuard.allowed`(세 신원 헤더). 막을 때는 없는 사용자와 같은 404. **남은 격차**: 헤더 판정은 "서비스 포트가 밖에 안 열린다" 전제 — [docs/27 §9b·§14](27-security-review.ko.md) |
| 🔴 SMS 로그 발송기가 **인증번호를 로그에 남김** | OWASP Logging: OTP·토큰·비밀번호는 어떤 레벨로도 기록하지 않는다 | 수신자·길이만 남긴다([docs/51](51-logging.ko.md)) |
| 🟠 로그 마스킹이 서비스마다 제각각(또는 없음) | 중앙 로깅 설정에서 **출력 단계 마스킹**(심층 방어) — 개발자가 한 번 실수해도 새지 않게 | common 의 logback 한 벌 + `SensitiveDataMasker`(토큰·카드·전화·이메일). 서비스별 패턴 금지 가드(B-57) |
| 🟠 유출 DB 암호의 생사를 **몰랐다**. 이력 스캐너는 JSON 형식을 못 봐 "0건" 이라 보고 | 유출 = 즉시 폐기·교체, 그리고 **실제로 통하는지로 확인**(값 비교가 아니라). 스캐너(gitleaks 등)는 형식별 규칙을 갖는다 | 대조군·양성 대조 달린 로그인 실측으로 죽은 값임을 확인. 스캐너에 JSON 규칙 추가([docs/30 §6](30-git-history-secret-exposure.ko.md)) |
| 🟠 www 없는 주소로 열면 API 가 전부 CORS 403 | 정규 호스트 하나(엣지에서 301). CORS 를 넓혀 해결하지 않는다 — 쿠키·세션이 호스트별로 갈린다 | Cloudflare 루트→www 301 + 운영 번들의 출처 불일치 리다이렉트(안전망). CORS 허용 목록은 그대로([docs/33 §6](33-deploy-runbook.ko.md)) |
| 🔴 **2FA 를 서버가 강제하지 않았다** — 비밀번호 로그인은 토큰을 먼저 줬고, 소셜 보류 쿠키는 액세스 토큰과 같은 키라 Bearer 로 통과 | 두 번째 요소 전에는 자격증명을 발급하지 않는다. 대기 토큰은 별도 키, 시도 횟수 제한 | `PendingTwoFactor`(파생 키·5회·1회용), 두 경로 모두 대기 쿠키만([docs/27 §12b](27-security-review.ko.md)) |
| 🔴 **쿠폰 발급·수정에 권한 검사 없음** — 로그인한 아무나 임의 할인율 쿠폰 발급, 결제 대조도 통과 | 형제 엔드포인트 전수, 발급은 관리자·시스템만 | 관리자 전용([docs/27 §5~6](27-security-review.ko.md)) |
| 🟠 탈퇴가 **chat 복제본에 닿지 않았다**(이메일·이름·사진 잔존), 채팅 안내 문구에 이메일 | 삭제는 복제본까지 전파, 표시 이름은 식별자에서 파생하지 않는다 | `user-deleted` 익명화, 안내·대체 닉네임에서 이메일 제거([docs/48 §6-⑤·⑥](48-user-replication-debt.ko.md)) |
| 🟠 chat 의 신원이 **이메일** — 메시지마다 발신자 이메일이 방의 참여자에게 전달됐다 | 신원은 불변 id, 식별자를 상대에게 넘기지 않는다 | 세션·조회·메시지·화면 판별을 uid 로, 옛 기록 백필, Avro v2 + 스키마 이력([docs/48 §6-⑥](48-user-replication-debt.ko.md)) |
| 🔴 **남의 장바구니를 덮어쓸 수 있었다**(`/savecart` 가 본문 email 을 믿음) · 사가 메시지(결제 요청·응답, 상품 갱신, cart 셋)와 결제 **아웃박스 행**에 **사용자 토큰 원문** — 존재만 확인하고 아무도 쓰지 않았다 | 신원은 토큰의 불변 id, 토큰은 메시지에 싣지 않는다 | cart 신원·캐시 키를 `User-Id` 로, 사가 메시지 전부에서 토큰 제거(스키마 v2 + 이력), 아웃박스 행 스크럽(V15)([docs/54 §1.7](54-improvements-vs-practice.ko.md)) |
| 🔴 coupon 이 요청마다 이메일로 user-service 에 id 를 다시 물었고, 그 호출이 **액세스 토큰을 URL 쿼리**에 실었다 — URL 은 로그·프록시에 남는다 | 신원은 토큰의 불변 id, 토큰은 URL 에 싣지 않는다 | 적용·목록·기획전 수령·주문 견적을 사용자 id 로, 토큰 쿼리 파라미터 삭제. 무중단 배포용 전환 코드(이메일 대체 경로, 두 헤더 호출, 가짜 `Authorization` 표지, 옛 발신자 백필, 롤백용 테이블 둘)도 뒤이어 정리 — 앞 단계가 끝나지 않았으면 마이그레이션이 스스로 멈춘다([docs/48 §6-⑥](48-user-replication-debt.ko.md)) |
| 🟡 클러스터 Redis 가 **DB 비밀번호를 같이 씀**(`SPRING_DATA_REDIS_PASSWORD` 없으면 폴백) | 시스템마다 다른 자격증명 — 하나가 새면 전부 새는 구조를 피한다 | 교체 절차에 분리 방법을 적었다(적용은 운영 몫) |

### 11.2 신뢰성 — 부족했던 것

| 문제(전) | 실무 기준 | 바꾼 것(후) |
|---|---|---|
| 🔴 환불·반품의 **재고 복원·쿠폰 복원이 매번 실패**("No transaction is in process") — 로그 한 줄만 | 트랜잭션 프로듀서는 트랜잭션 경계가 명시돼야 한다 | `TransactionalSends` — 트랜잭션이 없으면 한 건짜리로([docs/49 §10](49-payment-providers.ko.md)) |
| 🔴 결제됐는데 화면은 미결제(결제 버튼 재노출) — 커밋 **전에** 캐시를 비워 옛 값이 다시 캐시됨 | 캐시 무효화는 **커밋 뒤에**(transaction-aware cache, after-commit hook) | `RedisCacheManager.transactionAware()`([docs/25 §11.12](25-delivery-service-design.ko.md)) |
| 🟠 "수동 확인 필요" 는 **로그에만** — 아무도 안 봄 | 메트릭 없는 사건은 **로그 기반 경보**로 사람에게 보낸다 | Loki ruler → Alertmanager, 문구·규칙을 가드(B-61)로 묶음([docs/51 §4i](51-logging.ko.md)) |
| 🟠 부분 환불에 알림 없음 | 돈이 움직이면 고객에게 알린다(부분 포함) | `order-refunded` 토픽 → "부분 환불 완료"([docs/49 §10](49-payment-providers.ko.md)) |
| 🟠 품절을 아무도 모르고, 재고를 **채울 화면조차 없었다** | 품절은 운영자 경보, 재입고는 **신청한** 고객에게만 | 품절 경보 + 재입고 알림 + 관리자 재고 조정([docs/53](53-restock-and-sold-out.ko.md)) |
| 🔴 chat 이 **`user-created` 를 한 번도 받지 못함** — 토픽을 Avro 로 바꿀 때 chat 컨슈머만 JSON 으로 남아 전부 DLT | 형식 전환은 소비자 전수 확인, 가드 목록 누락 방지 | `AvroDtoDeserializer` + 가드 목록 보정([docs/48 §6-⑤](48-user-replication-debt.ko.md)) |
| 🟠 프로필 변경·탈퇴가 복제본에 전파되지 않음 | 생성·변경·삭제 모두 아웃박스, 순서 역전에 안전하게 | `user-updated`·`user-deleted`(+ `updatedAt` 으로 옛 이벤트 버림) |
| 🟠 결제 확인 → PAID 가 약 12초 — 10초 아웃박스 폴러를 order·pay 에서 두 번 건넜다 | 아웃박스 릴레이는 커밋 직후, 폴러는 안전망 | 커밋 콜백이 릴레이를 한 번 돌린다(`OutboxRelayTrigger`, 합치기·실패 무시), 폴러 간격은 그대로([docs/56](56-saga-latency.ko.md)) |
| 🟠 분산 추적이 아웃박스·배치 리스너에서 끊김 | W3C `traceparent` 를 메시지 헤더로 전달, 비동기 경계에서는 문맥을 **저장했다 되살린다** | 아웃박스 행에 문맥 저장, 모든 리스너가 레코드 헤더로 이어받음(B-60/60b)([docs/51 §4e~4h](51-logging.ko.md)) |

### 11.3 불필요했던 것 — 빼거나 줄인 것

| 불필요했던 것 | 왜 불필요한가(실무 기준) | 처리 |
|---|---|---|
| order-service 의 **고객 사본**(`orders.customer`) + 가입 이벤트 구독 + 주문 시점 백필 + `customers` 캐시 | 필요한 것은 고객 id 하나인데, 검증된 토큰의 `uid` 가 이미 `User-Id` 헤더로 온다. **토큰이 나르는 것을 복제하지 않는다** | ✅ 요청자 신원을 `User-Id` 로 옮기고 사본·백필·구독 제거([docs/48 §6-①](48-user-replication-debt.ko.md)) |
| `user-created` 이벤트의 **이메일** | 데이터 최소화 — 받는 쪽이 쓰지 않는 개인정보를 여러 DB 로 퍼뜨리지 않는다 | ✅ chat 신원을 uid 로 옮긴 뒤 제거([docs/48 §6-⑥](48-user-replication-debt.ko.md)) |
| 사용자 조회 엔드포인트마다 **따로 짠 헤더 판정** | 인가 판정은 한 곳 — 흩어지면 형제 엔드포인트가 빠진다(§9b 가 그랬다) | `InternalCallGuard` 로 통일 |
| 재고 이벤트를 **주문마다** 발행하는 안 | 알림은 상태가 바뀔 때만(0 을 건널 때) — 주문마다면 소음 | 0 을 건널 때만 발행(docs/53) |

### 11.4 아직 부족한 것 (정직하게)

| 항목 | 실무 기준 | 상태 |
|---|---|---|
| ✅ ~~**재고 초과 판매가 조용히 넘어감**~~ — 결제 뒤 차감이 실패해도 주문은 PAID | 결제 전 재고 예약, 또는 실패 시 자동 환불 보상 + 경보 | 있는 만큼 배정, 부족분은 아웃박스 → order 가 자동 부분 환불(무통장은 수동), 경보. 결제 전 예약은 다음 단계([docs/53 §7](53-restock-and-sold-out.ko.md)) |
| ✅ ~~**CI 첫 단계 실패**(cart-service 6건)~~ — 테스트가 서비스를 프록시 없이 만들어 트랜잭션 동기화가 없었다(코드는 정상) | 메인 브랜치는 항상 초록 | 테스트가 트랜잭션 경계를 재현, 커밋 뒤 캐시 삭제 테스트 추가(`CartEmptyLinesTest`) |
| ✅ ~~**서비스 간 인증 없음**(헤더 판정에 의존)~~ | mTLS 또는 서비스 토큰, 내부 API 는 외부 라우팅에서 제외 | 서비스 간 mTLS(docs/57 §16) |
| 🟠 config-server 보호 | 설정 서버는 인증·암호화 필수 | 설정 저장소를 연결하지 않은 상태로 운영 — 연결 시 인증 필요. 관리 엔드포인트는 외부 경로에서 제외 |
| 🟡 단일 노드 k3s | 최소 2노드 + 관리형 DB | 비용 결정 |

## 교훈 한 줄씩

1. **"컴파일된다"≠"뜬다", "뜬다"≠"동작한다".** B-1~14 는 띄워야 보였고, B-13·19·24 는 뜨고도 조용히 망가져 있었다.
2. **가장 비싼 버그는 코드가 아니라 설정에 있었고, 리뷰는 설정을 못 본다.** 그래서 가드레일 테스트가 빌드마다 본다.
3. **꺼 둔 테스트는 없는 테스트보다 나쁘다.** 상시 빨간불 6건이 진짜 NPE 4건을 가렸다.
4. **검증하지 않은 백업·관측성·인증서는 없는 것과 같다.** 그래서 전부 실제로 사고를 내거나 띄워서 확인했다.
