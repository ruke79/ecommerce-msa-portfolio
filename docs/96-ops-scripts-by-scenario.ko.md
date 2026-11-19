# 운영 스크립트 — 시나리오별 사용법

[日本語](96-ops-scripts-by-scenario.md) | **한국어**

`ops/` 의 스크립트는 번호 순서로 생겼고, 번호는 만든 순서일 뿐 쓰는 순서가 아닙니다.
[ops/README](../ops/README.ko.md) 는 번호마다 무엇을 하는지 적은 목록이고,
이 문서는 **"지금 이런 일을 해야 한다"** 에서 출발해 어떤 스크립트를 어떤 순서로 부르는지 묶습니다.
각 스크립트의 자세한 옵션은 파일 머리 주석(`sed -n 2,60p ops/<파일>`)이 기준입니다.

> 상태: 2026-11-19 기준 저장소의 스크립트(01 ~ 61 · `promote-replica.sh` · `tools/`)를 묶었습니다.
> 운영 기록(언제 무엇을 적용했나)은 [docs/85](85-release-deploy-runbook.ko.md) 와 각 문서에 있습니다.

## 0. 먼저 — 모든 스크립트에 공통인 약속

| 약속 | 뜻 |
|---|---|
| 기본은 보기만 | 바꾸는 스크립트 대부분은 인자 없이 · `check` · `status` · `plan` 이면 아무것도 바꾸지 않습니다. 바꾸려면 `apply` · `APPLY=1` · `ACTION=setup` · `CONFIRM=yes` 처럼 따로 적습니다. 처음에는 늘 보기만 먼저 |
| `DRY_RUN=1` · `--dry-run` | 실행할 명령만 출력(01 ~ 04 · 07 · 28 · 31 · 33) |
| 비밀은 출력하지 않음 | 길이 · 접두사 · A·B·C 같은 이름만. 값을 묻는 스크립트는 표준입력으로 받습니다 — 명령줄 · 셸 기록 · 대화에 쓰지 않습니다 |
| 스스로 검증 | 바꾼 뒤 그 결과가 실제로 통하는지(로그인 · 왕복 · 403/200)까지 보고, 실패하면 0 이 아닌 종료 코드 |
| 운영 노드 보호 | 부하 · 대량 적재 · 재고 조작은 실습 클러스터 전용이거나, 운영에서는 `PROD_READONLY=1` · `ALLOW_PROD=1` 같은 명시가 있어야 돕니다 |

꼭 지킬 것:

- 배포는 `ops/28-deploy.sh` 하나. ConfigMap 변경은 `ops/28 … --config-only` — `kubectl patch` · 오버레이 `kubectl apply -k` 금지.
- 백업 CronJob(`k8s/85`)은 `ACTION=cronjobs ./ops/29-enable-pitr-in-cluster.sh` 로만 — 그냥 `kubectl apply` 하면 백업이 조용히 꺼집니다.
- 운영의 관측 compose 명령은 언제나 두 파일: `-f docker-compose-observability.yml -f docker-compose-observability.oci.yml`.

## 1. 한눈에 — 시나리오 → 스크립트

| 시나리오 | 쓰는 스크립트(순서대로) | 절 |
|---|---|---|
| 새 운영 노드를 처음 세운다 | 26 → 23 → 24 → 07 → (25) → 29 → 02 → 38 / 39 → 17 → 28 → 30 → 05 · 06 → 40 | §2 |
| 평소 릴리스를 배포한다 | 32 → 백업 → 02 · 38 → 28 → 06 · 32 · 56 · 05 → 37 | §3 |
| 설정(ConfigMap)만 바꾼다 · 되돌린다 | 28 `--config-only` · 28 `--rollback` | §3-3 |
| 개발 중 서비스 몇 개만 바로 올린다 | 31 · 33 | §3-4 |
| DB 마이그레이션을 확인 · 고친다 | 32 · 15 | §4 |
| 백업 · 복원을 갖춘다 · 확인한다 | 29 · 08 · 09 · 10 | §5 |
| 비밀번호 · 키 · 인증서를 바꾼다 | 01 · 36 · 46 · 59 · 07 · 38 · 39 · 27 | §6 |
| 비밀이 git 에 샌 것 같다 | 20 → 34 → (폐기 · 재발급) → 21 / 22 | §6-3 |
| 보안 경계가 실제로 막는지 본다 | 11 · 35 · 37 · 27 | §7 |
| 장애 — 무엇이 깨졌는지 찾는다 | 05 · 41 · 56 · 32 · 27 · promote-replica | §8 |
| 부하 · 용량 · JVM 을 잰다 | 44 · 48 · 54 → 53 → 57 · 58 · 60 · 61 · `tools/k6-job.sh` | §9 |
| 로컬에서 배포를 리허설한다 | 12 · 13 · 14 · 18 · 19 | §11 |
| 검색을 Elasticsearch 로 | 03 · 53 `search` | §12 |
| 관리자 계정 · 데이터 손보기 | 30 · 42 | §13 |

번호로 찾을 때는 맨 끝 [부록](#부록--번호--시나리오) 표를 보십시오.

## 2. 새 운영 노드를 처음 세운다

OCI Ampere(arm64) 한 대에 k3s 를 올리고, 바깥에는 포트를 열지 않고 Cloudflare Tunnel 로만 내보내는 순서입니다.
자세한 배경은 [docs/33](33-deploy-runbook.ko.md) · [docs/31](31-kustomize-oci-tunnel.ko.md).

```bash
# ① 인스턴스 잡기 — 인기 리전은 "Out of host capacity" 가 흔하다. 잡힐 때까지 재시도
./ops/26-oci-launch-retry.sh
# ② 그 인스턴스에서 k3s 설치 · 한도 확인(nproc — 테넌시마다 4 OCPU / 2 OCPU 가 다르다)
./ops/23-oci-ampere-bootstrap.sh
# ③ 인바운드 포트 없이 바깥으로 — Cloudflare Tunnel 생성 · 클러스터 연결
./ops/24-cloudflare-tunnel.sh
# ④ 인증서(CA · 서버 · 스토어) + certs.env + k8s Secret
./ops/07-issue-certificates.sh
```

| 단계 | 스크립트 | 메모 |
|---|---|---|
| DB 를 클러스터 밖(관리형)으로 둘 때만 | `25-provision-external-postgres.sh` | PostgreSQL 고유 기능(SKIP LOCKED · jsonb · 파티션)이 되는지부터 확인. 지금 운영은 클러스터 안 PostgreSQL |
| PITR · 백업 CronJob | `29-enable-pitr-in-cluster.sh` | §5 |
| 토픽 미리 만들기 | `02-create-dlt-topics.sh` | DLT 와 컴팩션 상태 토픽. 앱이 먼저 뜨면 브로커가 delete 정책으로 만들어 버린다 — 이미 있으면 정책을 고친다. `--check` 로 진단만 |
| 서비스별 신원 인증서 | `38-issue-service-identities.sh` 또는 `39-cert-manager.sh` | 38 은 1년짜리 손 발급, 39 는 cert-manager 가 90일 · 자동 갱신(Secret 이름 같음 — 매니페스트 그대로) |
| 경보 수신 | `17-configure-alert-receivers.sh` | Discord 주소는 묻는다. `MODE=verify` 설정 점검 · `MODE=test` 테스트 경보 |
| 첫 배포 | `28-deploy.sh local <버전>` | §3 |
| 첫 관리자 | `30-admin-account.sh promote <이메일>` | §13 |
| 점검 | `05-check-observability.sh` · `06-smoke-test.sh` | 06 이 실패하면 exit 1 |
| 배포 러너(선택) | `40-deploy-runner.sh check` → `install` | GitHub Actions → deploy 를 쓰려면. 나가는 연결만 쓴다 |

## 3. 평소 릴리스를 배포한다

순서의 원본은 [docs/85](85-release-deploy-runbook.ko.md)(묶음 릴리스 런북)입니다. 여기서는 스크립트만 따라갑니다.

### 3-1. 배포 전

```bash
# 이 배포가 DB 를 무엇으로 바꾸나 · 적용된 파일을 누가 고쳤나(고쳤으면 기동 실패)
./ops/32-db-migration.sh pending
./ops/32-db-migration.sh drift
# 큰 배포 — 운영 백업으로 다음 판의 Flyway 를 먼저 올려 본다(운영 DB 는 건드리지 않는다)
# 토픽 · 신원 — 새 토픽이나 새 서비스 신원이 생긴 릴리스면
./ops/02-create-dlt-topics.sh
./ops/38-issue-service-identities.sh --only <서비스>
```

- 배포 전 백업은 [docs/72 §3](72-model-refactor-deploy-runbook.ko.md) 의 명령(03:00 UTC 백업 창은 피한다).
- 이미지는 CI(`build.yml` → `ops/19-build-images.sh --skip-gradle`)가 GHCR 에 올립니다. 배포할 SHA 의 CI 가 초록인지 먼저 봅니다.

### 3-2. 배포 · 배포 직후

```bash
./ops/28-deploy.sh local <SHA12자> --dry-run    # 무엇이 바뀌나
./ops/28-deploy.sh local <SHA12자>              # 적용 — 실패하면 스스로 직전 버전으로
./ops/06-smoke-test.sh                          # 28 도 부르지만, 손으로 한 번 더 볼 때
./ops/32-db-migration.sh                        # 서비스마다 스키마 버전
./ops/56-dlt-replay.sh list order.DLT           # DLT 가 늘지 않았나(토픽마다)
./ops/05-check-observability.sh                 # 지표 · 경보 · 추적 파이프라인
```

- `ROLLBACK-UNSAFE` 마이그레이션이 든 배포는 28 이 자동 복귀를 끕니다(B-54). 그때 되돌리기는 `FORCE_UNSAFE_ROLLBACK=1 ./ops/28-deploy.sh local --rollback`.
- 며칠 뒤 observe → enforce 전환(mTLS 규칙 · 신원 헤더 · Eureka 쓰기)은 `./ops/37-check-mtls.sh` 의 ① · ①-2 · ①-3 이 0 인지 보고 결정합니다(§7).

### 3-3. 설정만 · 되돌리기 · 기록

```bash
./ops/28-deploy.sh local --config-only --restart delivery-service   # ConfigMap 만 올리고 그 서비스만 재시작
./ops/28-deploy.sh local --rollback                                 # 직전 성공 버전으로(같은 코드 경로)
./ops/28-deploy.sh --report                                         # 배포 이력 · 사이클 타임
```

### 3-4. 개발 루프 — 서비스 몇 개만 바로

```bash
./ops/31-deploy-one.sh user-service                       # HEAD 로 빌드 → 푸시 → 교체
./ops/33-deploy-some.sh --changed deployed --dry-run      # 떠 있는 이미지 이후 바뀐 서비스만
./ops/33-deploy-some.sh catalog-service order-service --rollback-on-failure
```

운영의 정식 배포는 28 입니다 — 31 · 33 은 개발 · 실습 클러스터용입니다.

## 4. DB 마이그레이션

| 하고 싶은 것 | 명령 |
|---|---|
| 서비스마다 지금 버전 | `./ops/32-db-migration.sh` |
| 한 서비스의 적용 이력 | `./ops/32-db-migration.sh info user-service` |
| 배포하면 적용될 것 | `./ops/32-db-migration.sh pending` |
| 적용된 파일이 바뀌었나 · 저장소에 없는 적용이 있나 | `./ops/32-db-migration.sh drift` — 2026-11-19 기록에 없던 배포를 이것으로 찾았다 |
| 실패 행 지우기(되돌릴 수 없음 — 묻는다) | `./ops/32-db-migration.sh repair <서비스>` |
| `ddl-auto` 를 끄기 전 validate 확인(사본 DB 에) | `PGURL=… SERVICE=catalog-service ./ops/15-schema-baseline-rehearsal.sh` |

파일을 고치지 말 것(체크섬) · 파괴적 변경은 `-- ROLLBACK-UNSAFE:` · 열 삭제는 expand → contract 두 릴리스 — [docs/55](55-zero-downtime-migrations.ko.md).

## 5. 백업 · 복원

```bash
# 클러스터 안 PostgreSQL — 점검만(기본) → 아카이빙 적용(재기동) → CronJob 켜기
./ops/29-enable-pitr-in-cluster.sh
ACTION=setup ./ops/29-enable-pitr-in-cluster.sh
ACTION=cronjobs ./ops/29-enable-pitr-in-cluster.sh   # postgres-backup 03:00 · restore-drill 월 05:00
```

| 스크립트 | 언제 |
|---|---|
| `08-backup-postgres.sh` | 클러스터 밖 PostgreSQL 이거나 손으로 한 번 — `ACTION=check` 로 replication 접속 허용부터 |
| `09-restore-drill.sh` | 그 백업이 정말 복구되는지 — 사고를 내고 되돌려 본다 |
| `10-backup-mongo.sh` | MongoDB(운영은 Atlas `chat`) 백업 + 복원 검증 |
| `promote-replica.sh` | 프라이머리 장애 때 레플리카 승격(되돌릴 수 없음, `CONFIRM=yes`) — `04-deploy-postgres-ha.sh` 로 HA 를 갖춘 경우만 |

배경 · 실측은 [docs/22 §2](22-alerting-and-backup-automation.ko.md) · [docs/15](15-backup-and-dr.ko.md).

## 6. 비밀번호 · 키 · 인증서 교체

### 6-1. 무엇을 바꾸나 → 어느 스크립트

| 대상 | 스크립트 | 끊김 |
|---|---|---|
| DB 비밀번호 | `01-rotate-db-password.sh` | 전파 체크리스트를 출력 |
| Redis 비밀번호(DB 와 분리 · 다시 바꾸기) | `36-rotate-redis-password.sh` | 새 값을 더한 뒤 차례로 재시작 — Redis 재시작 때 수 초 |
| JWT 서명 키 | `46-rotate-jwt-key.sh add` → `activate <kid>` → (30일 뒤) `retire <kid>` | 아무도 로그아웃되지 않는다. 샌 키는 `retire --force` |
| 키스토어 · 트러스트스토어 비밀번호 | `59-rotate-store-password.sh` check → `apply` → `cleanup`(되돌리기 `rollback`) | 하나씩 재시작 |
| TLS 인증서 만료 감시 | `MODE=verify WARN_DAYS=30 ./ops/07-issue-certificates.sh` | — |
| 서비스 신원 인증서 | `38-…`(손 발급) · `39-cert-manager.sh check`(자동 갱신 상태) | — |

### 6-2. 바꾼 뒤 · 평소에

```bash
./ops/27-check-secret-drift.sh            # Secret 의 자격증명이 지금 실제로 통하나(postgres · redis · mongo · file)
./ops/37-check-mtls.sh                    # 인증서를 내면 200 · 안 내면 403 — 판정이 살아 있나
```

### 6-3. 비밀이 git 이력에 샌 것 같을 때

순서가 중요합니다 — **폐기 · 재발급이 해결책이고, 이력 정리는 뒷정리**입니다([docs/30](30-git-history-secret-exposure.ko.md)).

```bash
./ops/20-scan-history-secrets.sh          # ① 이력 전체에서 자격증명 찾기(값은 출력하지 않음)
./ops/34-verify-leaked-passwords.sh       # ② 그 값이 지금도 통하나 · 운영 Secret 과 같은가
# ③ 통하면 §6-1 로 폐기 · 재발급 — 먼저
./ops/21-purge-history-secrets.sh         # ④ 그 뒤 이력 정리(git-filter-repo) — 또는 22(BFG)
```

21 · 22 는 force-push 로 모든 SHA 를 바꾸고 되돌릴 수 없습니다. GitHub 은 옛 객체를 SHA 로 남기므로 Support 에 GC 요청까지 필요합니다.

## 7. 보안 경계가 실제로 막는지

| 스크립트 | 보는 것 |
|---|---|
| `11-check-db-exposure.sh` | DB 포트가 바깥에서 열려 있지 않은가 |
| `35-network-policy.sh check` · `apply` · `remove` | NetworkPolicy — 허용돼야 할 흐름은 붙고 막혀야 할 것은 막히나. `apply` 는 틀리면 스스로 되돌린다 |
| `37-check-mtls.sh` | ② 인증서 유무로 403/200, ① `[mTLS observe]` · ①-2 `[mTLS rule]` · ①-3 `[mTLS identity]` 가 0 인가 — **0 이어야 enforce 로 올린다** |
| `27-check-secret-drift.sh` | Secret 과 실제 서버의 자격증명이 어긋나지 않았나 |

## 8. 장애 — 무엇이 깨졌는지 찾는다

| 증상 | 먼저 | 그다음 |
|---|---|---|
| 지표 · 경보가 안 온다, 대시보드가 빈다 | `./ops/05-check-observability.sh` | `MODE=test ./ops/17-configure-alert-receivers.sh` |
| 느리다 · 오류 — 한 요청이 어디서 막혔나 | `./ops/41-show-trace.sh --errors` · `--longest` · `<traceId>` | `SERVICE=order-service LOOKBACK=6h` 로 좁힌다 |
| Kafka 처리 실패가 DLT 에 쌓였다 | `./ops/56-dlt-replay.sh list <토픽>.DLT` — '사슬' 줄이 원인 | 고친 뒤 `APPLY=1 ./ops/56-dlt-replay.sh replay <토픽>.DLT <파티션> <오프셋>` — 바이트 그대로 · 원래 파티션. 모든 그룹이 다시 받으므로 알림 등은 다시 보내지 않는 판단도 |
| 기동 실패 — Flyway | `./ops/32-db-migration.sh drift` · `info <서비스>` | `repair` (묻는다) |
| 재시작 뒤 DB · Redis 인증 실패 | `./ops/27-check-secret-drift.sh` | §6 |
| 서비스 간 호출이 403 | `./ops/37-check-mtls.sh` | 신원 마운트 · 재시작 · `--only` 로 다시 발급 |
| 프라이머리 DB 가 죽었다(HA 구성) | `promote-replica.sh`(구 프라이머리 정지 확인 뒤 `CONFIRM=yes`) | 출력되는 후속 체크리스트 |
| SSE(알림 스트림)가 nginx 뒤에서 끊긴다 | `./ops/18-test-nginx-sse.sh` | 프론트 nginx 판 · 헤더(B-114) |

## 9. 부하 · 용량 · JVM 측정

측정 계획은 [docs/83](83-load-test.ko.md)(k6 시나리오) · [docs/91](91-slo-and-capacity.ko.md)(SLO · 천장) · [docs/92](92-jvm-measurement-scenarios.ko.md)(JVM).

### 9-1. 데이터 준비 — 실습 클러스터

```bash
./ops/44-bulk-catalog.sh estimate && ./ops/44-bulk-catalog.sh load    # 상품(기본 200만)
./ops/48-bulk-users.sh load                                           # 로그인되는 데모 사용자(기본 10만)
./ops/54-bulk-orders.sh load                                          # 주문 이력(기본 200만)
```

셋 다 `estimate` · `load`(다시 실행하면 이어서) · `status` · `purge`(생성 범위만) 같은 모양입니다. 개수를 바꿨다면 purge 도 같은 값으로.
캐시만 비우기는 `./ops/44-bulk-catalog.sh flush-cache`.

### 9-2. 부하를 건다

```bash
./ops/53-load-k6.sh smoke             # 본 측정 전에 경로 · 계정 · 데이터 · 인증서
./ops/53-load-k6.sh browse            # 비회원 조회(계단식 도착률) — order · spike · mixed · contention 도
PROM=http://<prometheus>:9090 ./ops/53-load-k6.sh ceiling browse    # 천장 찾기 — SLO 가 깨지는 계단에서 멈추고 ceiling.md
./ops/53-load-k6.sh report            # 결과 정리
```

- 재고 경합 측정은 `stock set` 으로 맞추고 끝나면 꼭 `stock restore`. 검색 엔진 비교는 `search es` → 끝나면 `search default`.
- 여러 회차를 밤새 이어 돌리기: `./ops/57-load-queue.sh plan` · `status` · `stop`.
- 부하 중 CPU 표본: `PROM=… WHEN=rps:gateway-server:1300 ./ops/58-cpu-profile.sh`(그 부하에 닿으면 서비스마다 한 파드).

### 9-3. 운영에서 읽기 천장 — 정해진 모드로만

```bash
PROD_READONLY=1 K6=ops/tools/k6-job.sh \
  GATEWAY=https://gateway-server.ecommerce.svc.cluster.local:8000 \
  CLIENT_IPS=2000 PROM=http://localhost:9090 ./ops/53-load-k6.sh ceiling browse
```

browse 만 · 클러스터 안 k6 Job(`tools/k6-job.sh`) · 도착률 상한 `PROD_MAX_RATE` · 백업 시간대 거절 · Alertmanager 묵음 자동. 운영에서 53 은 이 모드 밖으로 돌지 않습니다.

### 9-4. JVM

```bash
./ops/60-jvm-facts.sh                               # 부하 없이 — 파드마다 JVM 이 고른 GC · 힙 · 스레드(운영은 ALLOW_PROD=1)
PROM=http://localhost:9090 ./ops/61-gc-baseline.sh   # 평시 GC 기준값(Prometheus 읽기만, 기본 7일)
./ops/53-load-k6.sh jvm load catalog-service gc=g1 heap=50   # 비교 실험 — 설정 바꾸기
./ops/53-load-k6.sh jvm default catalog-service              # 끝나면 꼭 되돌린다(남으면 ops/28 이 경고)
```

JVM 설정은 `kubectl set env` 로 손대지 않고 이 두 명령으로만 바꿉니다. 간단한 동시성 스윕은 `./ops/14-load-test.sh`(k6 이전의 로컬 하네스).

## 10. 실습 클러스터

실습 · 데모 클러스터를 세우는 스크립트는 이 저장소에 두지 않습니다 — 장비마다 다릅니다.

## 11. 로컬에서 배포를 리허설한다

| 스크립트 | 무엇을 |
|---|---|
| `12-staging-rehearsal.sh` | 인프라 5종 + 서비스 + 스모크를 띄우고 정리(`ACTION=down`) |
| `13-k3s-deploy.sh` | 로컬 단일 노드 k3s 에 이미지를 직접 넣고 배포 → 스모크(`KEEP=1` 이면 남김) |
| `19-build-images.sh` | 배포용 이미지 — 아키텍처를 골라 레지스트리로(CI 도 이 파일) |
| `14-load-test.sh` | 간단한 동시성 스윕 |
| `18-test-nginx-sse.sh` | nginx 를 지나는 SSE 가 정말 스트리밍되나 |

## 12. 검색을 Elasticsearch 로

```bash
CATALOG_URL=https://<catalog>:8081 ES_URL=https://<es>:9200 ./ops/03-enable-es-search.sh   # 재색인 → 검증 → 전환
./ops/53-load-k6.sh search status     # 실습에서 지금 엔진 · 색인 문서 수
```

운영(OCI)에는 ES 가 없습니다. 비율은 기능 플래그(`k8s/15-feature-flags.yaml` + `ops/28 --config-only`)로 — [docs/74](74-feature-flags.ko.md).

## 13. 관리자 계정 · 데이터 손보기

```bash
./ops/30-admin-account.sh status                 # 계정 · 역할 · 활성화
./ops/30-admin-account.sh promote <이메일>        # ROLE_ADMIN + 활성화
./ops/30-admin-account.sh password <이메일>       # 비밀번호 재설정(대화형)
./ops/42-repair-orphan-sizes.sh preview          # 부모 없이 저장된 사이즈의 짝 추정 — 고치지 않는다
./ops/42-repair-orphan-sizes.sh apply 901:77     # 확인한 짝만
```

## 부록 — 번호 → 시나리오

| # | 스크립트 | 시나리오 |
|---|---|---|
| 01 | rotate-db-password | §6 교체 |
| 02 | create-dlt-topics | §2 처음 · §3 배포 전 |
| 03 | enable-es-search | §12 검색 |
| 04 | deploy-postgres-ha | §5 (HA) |
| 05 | check-observability | §3 배포 뒤 · §8 장애 |
| 06 | smoke-test | §3 배포 뒤 |
| 07 | issue-certificates | §2 처음 · §6 만료 감시 |
| 08 | backup-postgres | §5 백업 |
| 09 | restore-drill | §5 복원 훈련 |
| 10 | backup-mongo | §5 백업 |
| 11 | check-db-exposure | §7 경계 |
| 12 | staging-rehearsal | §11 로컬 |
| 13 | k3s-deploy | §11 로컬 |
| 14 | load-test | §9 · §11 |
| 15 | schema-baseline-rehearsal | §4 마이그레이션 |
| 17 | configure-alert-receivers | §2 처음 · §8 |
| 18 | test-nginx-sse | §8 · §11 |
| 19 | build-images | §3 (CI) · §11 |
| 20 · 21 · 22 · 34 | 이력 비밀 탐지 · 정리 · 확인 | §6-3 유출 |
| 23 · 24 · 26 | OCI k3s · 터널 · 인스턴스 재시도 | §2 처음 |
| 25 | provision-external-postgres | §2 (관리형 DB) |
| 27 | check-secret-drift | §6 · §7 · §8 |
| 28 | deploy | §3 배포(유일한 정식 경로) |
| 29 | enable-pitr-in-cluster | §5 백업 |
| 30 | admin-account | §13 |
| 31 · 33 | deploy-one · deploy-some | §3-4 개발 루프 |
| 32 | db-migration | §3 · §4 · §8 |
| 35 | network-policy | §7 |
| 36 | rotate-redis-password | §6 |
| 37 | check-mtls | §3 enforce 판단 · §7 · §8 |
| 38 · 39 | service-identities · cert-manager | §2 · §3 · §6 |
| 40 | deploy-runner | §2 (배포 러너) |
| 41 | show-trace | §8 |
| 42 | repair-orphan-sizes | §13 |
| 44 · 48 · 54 | bulk-catalog · users · orders | §9-1 |
| 46 | rotate-jwt-key | §6 |
| 53 | load-k6 | §9 · §12 |
| 56 | dlt-replay | §3 배포 뒤 · §8 |
| 57 · 58 | load-queue · cpu-profile | §9-2 |
| 59 | rotate-store-password | §6 |
| 60 · 61 | jvm-facts · gc-baseline | §9-4 |
| — | promote-replica | §5 · §8 |
| tools | `k6-job.sh` §9-3 · `generate-*.py`(44 · 48 · 54 가 부름) · `DltReplay.java`(56 이 부름) · `gen-erd.py` · `hbm2flyway.py`(문서 · 이행 도구) | |

