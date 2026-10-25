# 📚 ecommerce-msa 문서

[日本語](README.md) | **한국어**

설계 판단과 장애 대응의 기록입니다. **무엇을 만들었나** 보다 **왜 그렇게 정했고, 어떻게 깨졌고, 어떻게 고쳤나** 를 남깁니다.

- 문서 번호는 바뀌지 않습니다. 코드 주석이 `docs/51 §4h` 처럼 번호로 가리키기 때문입니다. 52번은 결번입니다.
- **상태** 칸은 이렇게 읽습니다.
  - ✅ **현행** — 지금 코드와 맞습니다.
  - 🟡 **일부 낡음** — 뼈대는 맞지만 뒤에 바뀐 곳이 있습니다. 무엇이 바뀌었는지 문서 맨 위 상태 표시에 적어 두었습니다.
  - 🕰️ **이전 기록** — 그 시점의 진단이나 작업 기록입니다. 지금 상태는 표시된 후속 문서에 있습니다.

## 처음 읽는다면

| 시간 | 순서 |
|---|---|
| 5분 | [62 기술 사례집](62-engineering-casebook.ko.md) — 중요한 사례 18개를 문제 · 처리 · 한계와 참고서로 |
| 15분 | 위에 더해 [00 전/후 종합 색인](00-before-after.ko.md) → [54 개선 총정리와 실무 기준](54-improvements-vs-practice.ko.md) |
| 주제별 | 아래 표에서 관심 영역을 고릅니다 |
| 운영자 | [33 배포 런북](33-deploy-runbook.ko.md) → [36 배포 파이프라인](36-deployment-pipeline.ko.md) → [34 인수 체크리스트](34-acceptance-checklist.ko.md) → [운영 스크립트](../ops/README.ko.md) |
| 개발자 | [61 새 서비스 추가 체크리스트](61-adding-a-service.ko.md) → [17 테스트 전략](17-testing.ko.md) → [55 무중단 마이그레이션](55-zero-downtime-migrations.ko.md) |

---

## 1. 개요 · 총정리

| # | 문서 | 내용 | 상태 |
|---|---|---|---|
| 62 | [기술 사례집](62-engineering-casebook.ko.md) | 사례 19개 — 문제 · 처리 · 한계 · 개선할 점, 사례마다 참고서 1권 | ✅ |
| 00 | [전/후 종합 색인](00-before-after.ko.md) | 발견한 결함(B-1~29 · FE · CAT …)과 해결을 한 표로 | 🟡 |
| 54 | [개선 총정리 — 실무 기준과 비교](54-improvements-vs-practice.ko.md) | 영역별로 무엇이 부족했고 지금은 어떤가, 아직 부족한 것, 교훈 10가지 | 🟡 |
| 03 | [아키텍처와 신뢰성](03-architecture-and-reliability.ko.md) | 구조도 · 사가 흐름 · 장애 시나리오 F-1~9 와 대응 | 🟡 |
| 38 | [전체 코드 리뷰](38-code-review.ko.md) | 백엔드 · 프론트 · CDC 전수 리뷰. 운영에서 깨져 있던 10건과 고치는 순서 | 🕰️ |
| 57 | [남은 우선순위를 닫은 기록](57-remaining-priorities.ko.md) | 예외 문구 누출 · 토큰 수명 단위 착오 · 별점 덮어쓰기 · mTLS 서비스별 신원 등 | 🟡 |
| 01 | [개선 이전의 문제 분석](01-branch-issues.ko.md) | 착수 전 진단 — 근거 파일 · 라인 포함 | 🕰️ |
| 02 | [개선 내역](02-improvements.ko.md) | 01 에 대한 첫 번째 수정과 로드맵 | 🕰️ |

## 2. 결제 · 주문 · 재고

| # | 문서 | 내용 | 상태 |
|---|---|---|---|
| 35 | [결제 검증](35-payment-verification.ko.md) | 서버가 브라우저의 "결제했다" 를 믿었다 — 재조회 · 금액 대조 · 재사용 차단 · fail closed | 🟡 |
| 49 | [결제수단 확장](49-payment-providers.ko.md) | 결제 포트, Stripe · 포트원 V1/V2 · 무통장 · 가상계좌, 통화, 환불 · 부분 환불 · 반품 | ✅ |
| 59 | [결제 전 재고 예약](59-stock-reservation.ko.md) | 마지막 1개 이중 판매 → 30분 예약, 전부 아니면 전무, 남은 수량 표시 | ✅ |
| 53 | [품절과 재입고](53-restock-and-sold-out.ko.md) | 운영자에게 품절 경보, 손님에게 재입고 알림, 초과 판매 자동 부분 환불 | ✅ |
| 56 | [결제 사가 지연](56-saga-latency.ko.md) | 12초 = 10초 폴러 두 번 → 커밋 직후 릴레이 | ✅ |
| 47 | [주문 생성 400 이 보이지 않던 것](47-order-create-failure-visibility.ko.md) | 로그 없는 catch, null → NPE 문구가 화면에 | 🕰️ |

## 3. 메시징 · 사가 · 정합성

| # | 문서 | 내용 | 상태 |
|---|---|---|---|
| 11 | [관측성 스택과 Kafka DLQ](11-observability-and-kafka-reliability.ko.md) | Prometheus · Grafana · Zipkin, 레코드 단위 DLQ 의 첫 적용 | 🕰️ |
| 12 | [DLQ 확산 · 컨슈머 멱등성 · 경보](12-dlq-rollout-idempotency-alerts.ko.md) | 재고 이중 차감을 막는 멱등 원장, 경보 규칙, 대시보드 코드화 | 🕰️ |
| 21 | [수평 확장](21-horizontal-scaling.ko.md) | 레플리카를 늘리자 아웃박스 이중 발행 — `SKIP LOCKED`, 늘리면 깨지는 서비스 | 🟡 |
| 48 | [사용자 복제](48-user-replication-debt.ko.md) | 한 이벤트 · 세 소비자 · 세 가지 실패 → 복제 제거 · 아웃박스 · 대사 배치 | 🟡 |
| 37 | [트랜잭션 안의 외부 호출](37-transaction-boundary-audit.ko.md) | 12개 모듈 중 9개. Feign 타임아웃이 적용되지 않던 것 | 🟡 |

## 4. 데이터 · 스키마 · 백업

| # | 문서 | 내용 | 상태 |
|---|---|---|---|
| 20 | [스키마 소유권](20-schema-ownership.ko.md) | `ddl-auto: update` → Flyway + `validate`, 빈 DB · 레거시 DB 양쪽 검증 | ✅ |
| 69 | [ERD — 실제 스키마](69-erd.ko.md) | 깨진 이미지 첨부 대신 마이그레이션을 적용한 DB 에서 자동 생성(Mermaid), 서비스 사이 논리 참조 개요, `ops/tools/gen-erd.py --check` | ✅ |
| 55 | [무중단 마이그레이션](55-zero-downtime-migrations.ko.md) | expand-contract, 배포 열차, 스스로 멈추는 마이그레이션 | ✅ |
| 09 | [PostgreSQL 파티셔닝 · 샤딩](09-postgresql-partitioning-sharding.ko.md) | 아웃박스 월 파티션과 샤딩 단계 설계(DDL 포함) | 🟡 |
| 15 | [백업 · 재해복구](15-backup-and-dr.ko.md) | 등급별 RPO/RTO, PITR — `DROP TABLE` 을 실제로 되돌려 검증 | 🟡 |
| 22 | [경보 전달과 백업 자동화](22-alerting-and-backup-automation.ko.md) | 수신자가 플레이스홀더라 경보가 어디로도 가지 않던 것, 백업 CronJob | ✅ |
| 42 | [배치 점검](42-batch-review.ko.md) | 멱등하지 않은데 재실행되는 임포트 잡, 스케줄 작업 8개의 레플리카 안전성 | ✅ |

## 5. 보안 · 인증

| # | 문서 | 내용 | 상태 |
|---|---|---|---|
| 27 | [보안 리뷰](27-security-review.ko.md) | IDOR · 인증 없는 쓰기 · actuator 노출 · 헤더 위조, 되돌려 확인한 회귀 테스트 | 🟡 |
| 30 | [git 이력 자격증명 유출](30-git-history-secret-exposure.ko.md) | 이력을 지워도 해결되지 않는 이유, 폐기 우선순위, 실측 확인 | 🟡 |
| 41 | [API 보안 점검](41-api-security-applied.ko.md) | 『API Security in Action』 랩 17종과 대조 — 레이트리밋 키 스푸핑 · 쿠폰 BOLA | 🟡 |
| 18 | [인증서 · 키스토어](18-certificates.ko.md) | 발급 → 스토어 변환 → 주입, 하드코딩 경로 제거, 게이트웨이 TLS 검증 | 🟡 |
| 46 | [다중 로그인 판정](46-multiple-login-detection.ko.md) | 이름이 Redis 였던 HashMap — 거짓 양성과 거짓 음성 | ✅ |
| 68 | [리프레시 토큰 회전 · 재사용 감지](68-refresh-token-rotation.ko.md) | 갱신해도 옛 토큰이 14일간 살아 있던 버그, Lua 비교 후 삭제, 묶음(fam) · 재사용이면 묶음 끊기, 동시 갱신 유예, 로그아웃 = 묶음 끊기, 경보 · 사용자 알림, 모든 기기에서 로그아웃, 긴 연결(채팅 · 알림)을 로그인에 묶기, 토큰은 해시로만 저장 | ✅ |
| 70 | [2FA 비밀 노출 · 결제 원자성](70-2fa-secret-and-payment-atomicity.ko.md) | 관리자 목록이 전원의 TOTP 비밀을 응답 · 평문 저장 → DTO · 저장 암호화(키 표시 · 기동 백필), 결제와 아웃박스를 한 트랜잭션으로 | ✅ |

## 6. 배포 · 인프라 · 운영

| # | 문서 | 내용 | 상태 |
|---|---|---|---|
| 36 | [배포 파이프라인](36-deployment-pipeline.ko.md) | 한 번 빌드한 이미지를 SHA 태그로, 스크립트 하나로 배포, 승인 후 자동 배포 | 🟡 |
| 33 | [배포 런북](33-deploy-runbook.ko.md) | 맥 · 리눅스 · OCI Ampere 1대로 올리는 순서와 자주 막히는 곳, 소셜 로그인 설정 | 🟡 |
| 34 | [인수 체크리스트](34-acceptance-checklist.ko.md) | 배포한 것을 사람이 끝까지 눌러 보는 경로, 실패를 어디서 보나 | 🟡 |
| 72 | [모델 리팩토링 2차 배포 런북](72-model-refactor-deploy-runbook.ko.md) | 자동 복귀가 꺼지는 배포(ROLLBACK-UNSAFE) — 사전 점검 쿼리 · 백업 · 배포 중 공지 편집 금지 · 배포 후 확인 · 실패 시 앞으로 고치기 · 다음 릴리스의 contract | ✅ |
| 73 | [주문 ↔ 배송지 expand → contract](73-order-address-expand-contract.ko.md) | 이중 외래 키를 두 릴리스로 — V25(한 방향 · 주문 쪽 유니크) → V26(열 삭제, ROLLBACK-UNSAFE), 롤링 중 옛 · 새 파드, 스스로 멈추는 시간 · 데이터 가드, 실제 PostgreSQL 마이그레이션 테스트 | ✅ |
| 74 | [기능 플래그 — 1단계](74-feature-flags.ko.md) | OpenFeature + 파일 공급자(flagd 형식, 재시작 없이 반영), search.engine 을 검색어 비율로 전환, 평가 결과를 스팬 · span_metrics 로, 캐시 키에 엔진, B-76 | ✅ |
| 76 | [주문 상태 이벤트와 진행 타임라인](76-order-status-read-model.ko.md) | CQRS 를 주문 진행 조회로 — OrderStatusChanger(한 곳 + 같은 트랜잭션의 아웃박스), order-status → saga-projection 의 OrderTimeline(eventId 중복 제거 · 발생 순 · 상한), 게이트웨이 조회는 남의 주문 404, 화면 "진행 기록", B-78 | ✅ |
| 77 | [이벤트 소싱 — 적용하지 않은 이유와 설계](77-event-sourcing-decision.ko.md) | 결정 기록 — 지금은 "DB 가 진실, 이벤트는 알림"(CDC 쪽). Kleppmann · Richardson · Vernon · Young · Fowler 를 근거로 약속과 대가, Order 37 필드 · 23 클래스의 숫자, 한다면 Order 집합체부터 PostgreSQL 이벤트 표 + 동기 프로젝션으로 네 릴리스, 다시 볼 신호 | ✅ |
| 79 | [운영 데모용 사용자 대량 생성](79-demo-users.ko.md) | 실제로 로그인되는 데모 계정 수십만 명 — `ops/48`(예약 id 범위 · `@demo.invalid` · 같은 BCrypt 해시 · `ROLE_USER`), 같은 트랜잭션의 user-created(쿠폰 · 채팅 복제본), 가입일 8일~2년 전(재조정기 회피), 1KB/명 · 10만 명 17초, 이벤트 흘리는 시간, 로그인 부하 주의(인증 경로 제한 · 다중 로그인 · 실패 집계), purge 는 범위 그리고 도메인만 | ✅ |
| 83 | [부하 시험 — k6 시나리오](83-load-test.ko.md) | 화면과 같은 주소 · 헤더로 조회 · 주문(로그인 → 담기 → 주문 → 무통장 입금 → 취소) · 급증 · 점검 — `ops/53`. 실습 클러스터에서만(운영 거절), 도착률 모델, JSON 검사(index.html 200 을 성공으로 보지 않는다), 429 따로, 로그인은 시작 전에 천천히 · 계정 하나에 VU 하나(리프레시 회전), 데모의 IP 하나 버킷 때문에 측정 동안만 유입 제한을 올린다(`limits`), 같이 볼 서버 지표 · 결과 기록 표 · 한계 | 🟡 |
| 85 | [묶음 릴리스 배포 런북](85-release-deploy-runbook.ko.md) | 운영 b7c5053(10-10) 이후 쌓인 수정을 한 번에 — 더하기만 하는 마이그레이션(cart · catalog · coupon · order · user), `ops/28` 이 보장하지 않는 순서(coupon · cart → order)를 배포 직후 확인으로 대신(아웃박스 FAILED 행 · DLT 다시 보내기), 사전 점검 · 리허설 · 백업 · 토픽 · 신원, 화면 한 바퀴, 며칠 뒤 observe → enforce | 🟡 |
| 61 | [새 서비스 추가 체크리스트](61-adding-a-service.ko.md) | 등록해야 하는 곳 전부와 빠지면 조용히 틀어지는 것 | ✅ |
| 31 | [환경 분리 · OCI Ampere · Cloudflare Tunnel](31-kustomize-oci-tunnel.ko.md) | kustomize 오버레이, arm64, 인바운드 포트 0 | 🟡 |
| 32 | [인프라 외부화](32-external-managed-infra.ko.md) | 관리형 무료 티어로 뺄 수 있는 것과 없는 것 | ✅ |
| 23 | [운영 인프라 선택](23-infrastructure-decision.ko.md) | 의존성이 실제로 쓰는 기능을 근거로 한 비교 | 🕰️ |
| 13 | [시크릿 · DB HA · ES 검색 전환](13-secrets-dbha-es-search.ko.md) | 로테이션 런북, PostgreSQL 복제 실검증 | 🟡 |
| 08 | [k3s 매니페스트](08-k3s-deployment.ko.md) | 첫 매니페스트 기록 | 🕰️ |

## 7. 관측성 · 성능

| # | 문서 | 내용 | 상태 |
|---|---|---|---|
| 63 | [OpenTelemetry](63-opentelemetry.ko.md) | OTel 데모의 구성 — OTLP · Collector · Jaeger · span_metrics · exemplar, 추적의 자격 증명 가림 | ✅ |
| 65 | [사이즈 단위 SKU](65-size-level-sku.ko.md) | SKU 를 색상에서 사이즈 한 줄로, 규칙은 DB 함수 한 곳, 장바구니 → 주문 → 포장 목록 기록, 관리자 등록 사이즈의 부모 누락 버그 | ✅ |
| 66 | [gRPC 시범 적용 — 재고 예약](66-grpc-stock-reservation.ko.md) | order → catalog 재고 예약을 gRPC 로(HTTP 와 나란히, 설정으로 전환), mTLS 판정 재사용, 기한 전파, 동등성 테스트, B-73 | ✅ |
| 51 | [로깅](51-logging.ko.md) | 인증번호 로그 누출, 출력 단계 마스킹, JSON 로그 · Loki, 추적 왕복 | 🟡 |
| 16 | [사가 E2E · 관측성 실증](16-saga-e2e-and-observability-verification.ko.md) | 실제 인프라로 검증 — 채팅 저장과 ES 검색은 한 번도 동작한 적 없었다 | 🕰️ |
| 19 | [로컬 성능 튜닝](19-local-performance-tuning.ko.md) | N+1 · 커넥션 풀 총합 초과 실측, 구조적 사실은 빌드로 고정 | 🟡 |

## 8. 기능 설계

| # | 문서 | 내용 | 상태 |
|---|---|---|---|
| 25 | [배송 서비스 설계](25-delivery-service-design.ko.md) | 분할 · 국제 배송, 택배사 어댑터, 상태별 폴링 | 🟡 |
| 58 | [고객센터 채팅](58-customer-support-chat.ko.md) | 역할 하나로 상담원, 상담 스레드, 서버가 강제하는 권한, 1:1 화면 · 문의 유형 · 만족도 · 내부 메모 · 반납 | ✅ |
| 29 | [트랜잭션 메일](29-transactional-email.ko.md) | 발신 파이프라인을 notification-service 로, 확인 링크는 자격증명 | 🟡 |
| 24 | [SMS 알림 · 2단계 인증](24-sms-notifications.ko.md) | 한국 · 일본 번호, 중복 발송 3중 방어, 인증번호 남용 방지 | 🟡 |
| 44 | [공지 · 알림 · 이벤트](44-notice-notification-event-gap.ko.md) | 이미 있는 것과 실제로 빠진 것 | 🟡 |
| 39 | [상품 등록 절차](39-product-registration.ko.md) | 첫 화면에 상품이 나오기까지 | ✅ |
| 45 | [다국어(일본어)](45-internationalization.ko.md) | 의존성 없는 i18n, 1차 범위 | 🟡 |
| 60 | [다국어 전수 조사](60-i18n-audit.ko.md) | 화면 문구 1,091곳 · 서버 문구 약 290개, 가드 테스트 | ✅ |

## 9. 검색

| # | 문서 | 내용 | 상태 |
|---|---|---|---|
| 50 | [`/browse` 정밀 분석](50-browse-analysis.ko.md) | "맞는 게 없다" 가 "제한이 없다" 로 뒤집히던 필터, 추천 · 시맨틱 검색 순서 | ✅ |
| 06 | [catalog 검색 문제 분석](06-catalog-search-issues.ko.md) | ES 를 쓰지 않던 검색, 캐시 무효화 | 🕰️ |
| 07 | [catalog 검색 개선](07-catalog-search-improvements.ko.md) | 06 에 대한 수정과 ES 전환 설계 | 🕰️ |

## 10. 프론트엔드

| # | 문서 | 내용 | 상태 |
|---|---|---|---|
| 40 | [프론트엔드 결함 목록](40-frontend-bug-list.ko.md) | 사용자 결과 기준 P0~P2 20건과 수정 기록 | 🕰️ |
| 26 | [프론트엔드 전면 리뷰](26-frontend-review-and-refactor.ko.md) | 결함 24건 — PayPal 이중 캡처, `querykey` 오타 | 🕰️ |
| 43 | [데이터 로딩 전략](43-loading-strategy-review.ko.md) | loader · React Query · useEffect 를 한 기준으로 | 🕰️ |
| 28 | [프론트엔드 테스트 도입](28-frontend-test-suite.ko.md) | 도입 직후 잡힌 버그 2건 | 🕰️ |
| 04 | [프론트엔드 문제 분석](04-frontend-issues.ko.md) | 토큰 저장 · 리프레시 큐 · 로그 누출 | 🕰️ |
| 05 | [프론트엔드 개선](05-frontend-improvements.ko.md) | 04 에 대한 수정 | 🕰️ |

## 11. 테스트

| # | 문서 | 내용 | 상태 |
|---|---|---|---|
| 17 | [테스트 전략](17-testing.ko.md) | 단위와 Testcontainers 분리, 설정 가드레일 | 🟡 |
| 14 | [실제 기동 테스트](14-boot-test-findings.ko.md) | 새 클론에서 0/8 기동 — 기동 불가 결함 발견과 수정 | 🕰️ |
| 10 | [품질 하드닝](10-quality-hardening.ko.md) | 핵심 경로 테스트 · OpenAPI · HPA/PDB | 🕰️ |

---

그 밖의 문서: [운영 스크립트](../ops/README.ko.md) · [k8s 매니페스트](../k8s/README.ko.md) · [프론트엔드](../frontend/README.ko.md)
