# 운영 액션 스크립트

[日本語](README.md) | **한국어**

앞선 개선 작업([docs/](../docs/README.ko.md) 01~13)이 저장소 안에서 끝낼 수 없었던
**운영 인프라 조치**들을 실행 가능한 스크립트로 만든 것이다. 각 스크립트는:
- `set -euo pipefail` + 필수 변수 검증 + 파괴적 조치에는 확인 프롬프트
- `DRY_RUN=1` 로 실제 실행 없이 수행할 명령을 미리 볼 수 있음(01~04)
- 실행 후 **자체 검증**까지 수행 (예: 새 비밀번호 로그인, 복제 왕복 확인)

## 실행 순서

| # | 스크립트 | 조치 | 긴급도 |
|---|---|---|---|
| ① | `01-rotate-db-password.sh` | **DB 비밀번호 로테이션** — git 이력에 노출된 비밀번호 무효화 + 전파 체크리스트 | 🔴 즉시 |
| ② | `02-create-dlt-topics.sh` | **DLT 토픽 9종 + 컴팩션 상태 토픽 `order-saga-state` 사전 생성** — 실행 위치를 스스로 찾는다(kubectl → docker → 로컬 PATH). 명령 이름도 `kafka-topics`(Confluent)·`kafka-topics.sh`(Apache) 둘 다 시도한다. 예전에는 k8s 에서 아예 동작하지 않았다(docs/33 을 쓰다 발견). 상태 토픽은 **이미 있으면 `cleanup.policy` 를 읽어 delete 면 고친다** — 앱이 먼저 뜨면 브로커가 delete 로 자동 생성해 버리고 `--if-not-exists` 는 그걸 성공으로 넘긴다(docs/38 §1.17). `--check` 로 진단만, `FIX_CONFIGS=0` 으로 경고만 | 🟠 배포 직후 |
| ③ | `03-enable-es-search.sh` | 재색인 → alias/문서수/nori 검증 → `SEARCH_ENGINE=es` 전환(k8s 자동 패치 옵션) | 🟡 선택 |
| ④ | `04-deploy-postgres-ha.sh` | 프라이머리-레플리카 기동 + DB 8종 생성 + **복제 왕복 검증** | 🟠 권장 |
| — | `promote-replica.sh` | 장애 시 레플리카 승격(CONFIRM=yes 필수, 후속 체크리스트 출력) | 장애 시 |
| ⑤ | `05-check-observability.sh` | Prometheus 타깃/경보 규칙/Grafana/OTel Collector · Jaeger 헬스, 추적 파이프라인 수치, 파드 → 노드 :4318 도달 일괄 점검 | 스택 기동 후 |
| ⑥ | `06-smoke-test.sh` | **배포 직후 스모크 테스트** — 서비스 기동·게이트웨이·레이트리미터 429·디스커버리·DLT·핵심 경로. 실패 시 exit 1 (CI 게이트 가능) | 매 배포 후 |
| ⑦ | `07-issue-certificates.sh` | **인증서 발급 + 주입** — 로컬 CA 또는 Let's Encrypt(무료) → PKCS12/JKS 스토어 → `certs.env` + k8s Secret. 자체 검증 6종 | 🔴 최초 1회 · 갱신 시 |
| ⑧ | `08-backup-postgres.sh` | **PostgreSQL 백업** — PITR 준비 상태 점검·설정, base backup, 무결성 확인, 보존 정리, 백업 지표 노출 | 🔴 매일 (cron) |
| ⑨ | `09-restore-drill.sh` | **PITR 복원 훈련** — `DROP TABLE` 사고를 내고 시점 복구로 되돌린 뒤 행 수·합계·스키마를 대조. RTO 실측. 실패 시 exit 1 | 🟠 주 1회 (CI 스케줄) |
| ⑩ | `10-backup-mongo.sh` | **MongoDB 백업** — mongodump + gzip 무결성 + **빈 인스턴스에 실제 복원해 문서 수 대조** | 🟠 매일 (cron) |
| ⑪ | `11-check-db-exposure.sh` | **DB 포트 외부 노출 점검** — git 이력에 공개됐던 호스트의 스토리지 포트 7종이 공인망에서 닫혀 있는지. **반드시 인프라 밖에서 실행** | 🔴 즉시 · 방화벽 변경 후 |
| ⑫ | `12-staging-rehearsal.sh` | **스테이징 배포 리허설** — 인프라 5종 + 전 서비스 9종을 실제 인증서로 동시 기동 → Eureka 등록 → DLT 생성(②) → 스모크(⑥)까지 한 번에 | 🟠 배포 전 |
| ⑬ | `13-k3s-deploy.sh` | **k3s 실클러스터 배포 검증** — 이미지 빌드→임포트→인프라(90-dev-infra)→서비스 9종→DLT→스모크. 매니페스트가 진짜 클러스터에서 도는지 확인 | 🟠 배포 전 |
| ⑭ | `14-load-test.sh` | **로컬 부하 하네스** — 동시성 스윕, 응답코드 분포·p50/p95/p99. 절대 수치가 아니라 **오류의 변곡점**을 찾는 도구(docs/19) | 🟡 튜닝 시 |
| ⑮ | `15-schema-baseline-rehearsal.sh` | **`ddl-auto` 끄기 전 리허설** — 기존 DB 에 베이스라인을 적용해 보고 Hibernate `validate` 와 같은 판정을 미리 내린다. 실패 시 exit 1 (docs/20 §5) | 🔴 **validate 배포 전 필수** |
| ⑰ | `17-configure-alert-receivers.sh` | **경보 수신 채널 설정 + 도달 증명** — 웹훅 URL 을 커밋되지 않는 파일로, severity 라우팅, 테스트 경보를 실제로 발사해 **전달 성공까지** 확인(수용됐다 ≠ 전달됐다). 실패 시 exit 1 | 🔴 **경보가 필요하다면 필수** |
| ⑳ | `20-scan-history-secrets.sh` | **git 이력 자격증명 검사** — 작업 트리가 아니라 **이력 전체**를 훑는다. 값은 절대 출력하지 않는다. `--head` 는 커밋 전 빠른 점검용, **`--remote` 는 GitHub 의 `refs/pull/*` 까지** 검사한다(정리 후 필수 — 브랜치만 보면 거짓 통과가 나온다) | 🔴 즉시 · 커밋 전 · 정리 후 |
| ㉑ | `21-purge-history-secrets.sh` | **git 이력 정리** — 신선한 미러 클론에서 값을 패턴 치환하고 ⑳ 로 재검사. **푸시하지 않는다**(force-push 는 사람이). ⚠️ 이건 뒷정리일 뿐, 유출은 **폐기·재발급**으로만 해결된다(docs/30) | 🟠 **폐기 완료 후에만** |
| ㉒ | `22-bfg-purge-history.sh` | **git 이력 파일 삭제 (BFG)** — 비밀이 든 파일을 이력에서 통째로 지우고 남은 값은 치환. BFG 는 **경로가 아니라 파일명**으로 매칭하고 **HEAD 를 보호**한다 — 둘 다 모르면 안 지워진다. HEAD 무결성 검사 내장 | 🟠 **폐기 완료 후에만** |
| ㉓ | `23-oci-ampere-bootstrap.sh` | **OCI Ampere(arm64) k3s 부트스트랩** — OCI 이미지가 미리 넣어 둔 차단 규칙이 파드 네트워크를 막는 문제를 처리(규칙을 지우지 않고 파드 CIDR 만 허용). **Ubuntu(iptables)와 RHEL 계열(firewalld + SELinux) 양쪽**을 다룬다 — Oracle Linux 는 enforcing 이라 끄지 않고 `container-selinux`·`k3s-selinux` 정책을 넣고 설치 후 실제로 들어갔는지 확인한다. ES 용 `vm.max_map_count`, servicelb 비활성. `--check` 로 진단만 | 🟠 인스턴스 최초 1회 |
| ㉔ | `24-cloudflare-tunnel.sh` | **Cloudflare Tunnel 연결** — 터널 생성·DNS 라우트·자격증명 Secret 주입. **인바운드 포트를 하나도 열지 않고** 외부 노출, TLS 는 Cloudflare 가 종단(ACM·cert-manager 불필요). **`--preflight`** 는 터널을 만들기 전에 걸리는 것들을 본다 — 특히 **네임서버가 실제로 Cloudflare 를 가리키는지**(가입만으로는 안 되고, 전파에 하루까지 걸린다). 대시보드 설정은 읽을 수 없으므로 통과했다고 말하지 않고 목록으로만 준다 (docs/33 §9) | 🟠 외부 공개 시 |
| ㉕ | `25-provision-external-postgres.sh` | **관리형 PostgreSQL 준비** — 프로비저닝 **전에** SKIP LOCKED·jsonb·IDENTITY·선언적 파티셔닝을 실제로 실행해 본다(HeatWave 는 MySQL, Autonomous 는 Oracle 이라 여기서 걸린다). 통과하면 역할 1 + 데이터베이스 10개를 멱등 생성. `--check` 로 진단만 (docs/32) | 🟠 인프라 외부화 시 |
| ㉖ | `26-oci-launch-retry.sh` | **OCI Ampere A1 인스턴스 재시도 발사** — 도쿄·서울에서는 `Out of host capacity` 로 거의 항상 막힌다. 용량은 이미지·리전 선택과 무관하므로 재시도가 유일한 해법이다. **재시도할 실패와 하지 말아야 할 실패를 가른다**(용량 부족은 계속, LimitExceeded·인증 오류는 즉시 중단). AD 가 여럿이면 순회(단일 AD 리전에서는 무의미하다고 경고) + 429 백오프. `--list-ads/images/subnets` 로 OCID 조회. **`--check-network`** 는 SSH 가 들어갈 수 있는 경로인지를 발사 **전에** 확인한다(퍼블릭 서브넷 · IGW 라우트 · 22번 허용) — 22번은 기본 보안 리스트에 이미 있으므로 대개 문제는 서브넷이나 라우트다. 덤으로 22번 외에 인터넷으로 열린 규칙을 찾아 경고한다 (docs/33 §4) | 🟠 인스턴스 생성 시 |
| ㉗ | `27-check-secret-drift.sh` | **자격증명이 아직 통하는지 확인** — Secret 의 값과 관리형 DB 가 따로 바뀌어 어긋나도 **아무도 알려주지 않는다.** 이미 떠 있는 파드는 기동 시점 값을 들고 계속 돌아서 전부 초록불이고, 다음 롤아웃에서 한꺼번에 죽는다. 값을 비교하지 않고 **클러스터 안에서 실제로 로그인해 본다**(비밀번호가 로컬에 나오지 않고, 서비스가 받는 값과 정확히 같은 값을 시험한다). PostgreSQL 28P01 · Redis WRONGPASS · Mongo 인증 · `20-secret.yaml` ↔ 클러스터 불일치를 가른다. `--only postgres\|redis\|mongo\|file` (docs/33 §10) | 🔴 배포 전마다 |
| ㉘ | `28-deploy.sh` | **배포 — 모든 환경에 같은 절차로.** 환경은 인자다(`28-deploy.sh prod <버전>`). 레지스트리에 그 버전이 **실제로 있는지 먼저 확인**하고(없는 태그로 apply 하면 옛 파드가 종료되기 시작한 뒤에야 ImagePullBackOff 로 안다), 커밋된 오버레이를 건드리지 않고 `.deploy/` 임시 오버레이에 태그를 주입하고, user-service→gateway-server 순서를 **코드로** 지키고, `ops/06` 스모크를 **게이트**로 건다. 실패하면 직전 성공 버전으로 자동 복귀한다 — **되돌리기 전용 경로가 없다**(평소 쓰는 배포 함수를 옛 버전으로 다시 부를 뿐이라, 매 배포가 곧 롤백 경로의 테스트다). `--dry-run` · `--rollback` · `--report`(사이클 타임) (docs/36) | 🔴 배포할 때마다 |
| ㉙ | `29-enable-pitr-in-cluster.sh` | **클러스터 안 PostgreSQL 을 PITR 가능하게.** `ops/08` 은 서버와 같은 머신에서 도는 것을 전제로 쓰여서, 파드 안의 아카이브 디렉터리와 `pg_hba.conf` 의 replication 항목을 만들지 못한다(전자는 서버가 실행하는 경로라, 후자는 파일이라 ALTER SYSTEM 밖). 그 둘만 `kubectl exec` 로 채운다 — 백업 자체는 여전히 `ops/08` 이 한다. 기본은 **점검만** 하고 아무것도 바꾸지 않는다; `ACTION=setup` 은 파드를 재기동하므로 확인을 받는다. **`ACTION=cronjobs` — 백업 CronJob(k8s/85) 적용 + postgres-backup · restore-drill 켜기**: 매니페스트가 둘을 `suspend: true` 로 들고 있어 그냥 `kubectl apply` 하면 다시 꺼진다 — k8s/85 는 늘 이것으로 적용한다(원격 replication 항목이 없으면 아무것도 바꾸지 않는다) | 🟡 백업을 켜기 전 1회 · k8s/85 를 적용할 때마다 |
| ㉚ | `30-admin-account.sh` | **첫 관리자 부트스트랩 · 비밀번호 재설정 · 계정 활성화.** `AdminController` 가 통째로 `hasRole('ROLE_ADMIN')` 이라 관리자가 0명이면 API 로 관리자를 만들 수 없고, 가입 폼의 role 주입은 보안 수정으로 막혔다. 실무에서도 이 순환은 UI 가 아니라 배포 경로로 끊는다(Grafana 의 `GF_SECURITY_ADMIN_PASSWORD`, Django 의 `createsuperuser`) — **공개 URL 에 관리자 등록 화면을 두지 않는 것**이 핵심이다. 비밀번호는 argv 가 아니라 대화형으로 받고(히스토리·`ps` 노출 없음), 해시는 앱과 **같은** BCrypt(strength 10)로 만든다. `status` · `promote` · `password` · `enable` | 🟡 환경을 새로 세울 때 |
| ㉛ | `31-deploy-one.sh` | **서비스 하나만 빌드해서 그 자리에 올린다 — 개발 루프용.** ops/28 의 대체재가 **아니다**: 저널에 남지 않고 스모크를 거치지 않으며 클러스터가 여러 버전이 섞인 상태가 된다(이 저장소는 그 답이 흐려져 배포가 일곱 시간 막힌 적이 있다 — docs/39 §7). 고치고 확인하기를 반복하는 동안만 쓰고, 원인을 잡으면 `ops/28` 로 한 버전에 수렴시킨다(끝날 때 그 명령을 출력한다). 레지스트리·컨테이너 이름을 **지금 도는 이미지에서** 읽으므로 오버레이를 다시 해석하지 않는다. 같은 태그면 노드가 캐시를 쓰므로 거부한다. `--dry-run` | 🟢 개발 중에만 |
| ㉜ | `32-db-migration.sh` | **DB 마이그레이션 상태 조회·복구.** 마이그레이션 도구(Flyway)는 이미 서비스 9개 전부에 있다 — 없던 것은 그걸 **운영에서 다룰 방법**이다. `summary`(적용/대기/실패) · `info <서비스>`(이력) · `pending`(**배포가 DB 를 무엇으로 바꾸나** — 배포 전에) · `drift`(적용된 파일을 누가 고쳤나 — 고쳤으면 다음 기동에서 checksum mismatch 로 죽는다) · `repair`(실패 행 제거). Flyway CLI 를 새로 들이지 않고 `flyway_schema_history` 로 판정한다. ⚠️ `ops/28 --rollback` 은 **스키마를 되돌리지 않는다** — 마이그레이션은 expand-contract 로 쓸 것 | 🟢 배포 전후 |
| ㉝ | `33-deploy-some.sh` | **서비스 여러 개를 한 태그로 빌드해서 차례로 올린다 — 개발 루프용.** ops/31 의 여러 서비스판이고 같은 이유로 ops/28 의 대체재가 **아니다**(저널·스모크 없음, 버전이 섞인다 — 끝날 때 수렴 명령을 출력). 빌드는 ops/19 한 번(`--only "a b c"`). 올리는 순서가 정해져 있다(기반 → 백엔드 → user-service → 게이트웨이 → 프론트). 기본은 **하나씩 올리고 실패하면 거기서 멈추며** 이미 올린 것의 되돌리기 명령을 역순으로 출력한다 — `--rollback-on-failure` 면 직접 되돌린다, `--parallel` 은 한꺼번에 바꾸고 기다린다. user-service ↔ gateway-server 는 짝이라 하나만 주면 다른 하나를 더한다(`--allow-split`). `--changed <git ref>` 는 그 뒤 바뀐 모듈로 대상을 고른다(common·루트 gradle·런타임 Dockerfile 이면 자바 서비스 전부). 같은 태그면 거부, `--dry-run` | 🟢 개발 중에만 |
| ㊶ | `41-show-trace.sh` | **추적 하나를 터미널에 나무로 그린다** — 최신 · `--longest`(사가) · `--errors` · 로그의 traceId. 스팬 사이 빈틈이 아웃박스 · Kafka 를 건넌 시간 (docs/63) | 조사할 때 |
| ㊷ | `42-repair-orphan-sizes.sh` | **부모 없이 저장된 사이즈 복구**(관리자 등록 버그, docs/65 §5) — `status` · `preview`(생성 시각으로 짝 추정 · 확신도 HIGH/CHECK/NONE, 고치지 않음) · `apply <size_id>:<스타일 id>`(확인한 짝만, 한 트랜잭션, 하나라도 틀리면 전부 되돌림) | 배포 후 한 번 |
| ㊹ | `44-bulk-catalog.sh` | **부하 테스트용 상품 대량 등록 · 삭제** — 생성기를 운영 노드에서 안전하게: 디스크 · 백업 시간대 점검, DB 를 잠깐 port-forward, 비밀번호는 Secret 에서 환경 변수로만, 끊기면 이어서, 끝나면 카탈로그 캐시 비우기. `estimate` · `load` · `status` · `purge` · `flush-cache` (docs/19 §5.1) | 🟡 부하 테스트 시 |
| ㊻ | `46-rotate-jwt-key.sh` | **JWT 서명 키 무중단 교체** — `add`(새 키를 링에 넣고 세 서비스 재시작, 서명은 그대로) → `activate`(세 서비스의 모든 파드가 지금 Secret 으로 떠 있을 때만 서명 키 전환) → `retire`(전환 뒤 30일이 지나야, 샜다면 `--force`). 값은 화면 · 인자 · 로그에 남기지 않는다(stdin · 환경 변수), 로컬 `20-secret.yaml` 도 같은 값으로. `status` 로 아는 키 · 서명 키 · 은퇴까지 남은 날. docs/68 §17 | 🟠 키 교체 · 유출 의심 때 |
| ㊽ | `48-bulk-users.sh` | **운영 데모용 사용자 대량 생성 · 확인 · 삭제** — 실제로 로그인되는 계정(예약 id 범위 · `@demo.invalid` · 같은 BCrypt 10 해시 · `ROLE_USER`) + 배송지 + 같은 트랜잭션의 user-created. `estimate` · `load`(이어서) · `status`(쿠폰 · 채팅 복제본까지) · `retry-failed` · `login-check`(표본, 초당 1회) · `purge`(범위 그리고 도메인만, 외래 키를 따라 흔적까지). 비밀번호는 0600 파일(저장소 밖) · stdin 으로만. docs/79 | 🟡 데모 · 부하 테스트 때 |
| (53) | `53-load-k6.sh` | **k6 부하 시나리오** — `browse`(비회원 조회, 도착률 계단) · `order`(데모 사용자 로그인 → 담기 → 주문 → 무통장 입금 → 취소) · `spike`(HPA 가 따라오는 동안) · `smoke`(한 번씩). 운영 주소 거절, 데모 CA 를 믿는다(검증을 끄지 않음), 비밀번호는 k6 환경 변수로만, 결과 · 이미지 판 · 레플리카 · HPA · 유입 제한을 `load-results/` 한 폴더에. `limits load/default/status` — 측정 동안만 게이트웨이 기본 유입 제한을 올린다(운영 클러스터면 거절). docs/83 | 🟡 부하 측정 때 |
| — | `tools/generate-catalog-data.py` | **부하용 카탈로그 데이터 생성** — 대형 쇼핑몰식 분류(대분류 20 · 소분류 99) 또는 시드 증식. 묶음 단위 COPY 로 수천만 개까지(`--resume` · `--purge`), `--estimate` 로 용량 미리 보기 — 상품당 약 2.1KB 실측(docs/19 §5.1) | 🟡 튜닝 시 |
| — | `tools/hbm2flyway.py` | **스키마 베이스라인 생성** — Hibernate 가 뽑은 DDL 을 멱등 Flyway 마이그레이션으로 변환. 손으로 쓰면 반드시 어긋나므로 기계 변환한다(docs/20 §3) | 엔티티 대개편 시 |

## 빠른 시작

```bash
# ⑲+㉘ 배포 — 이미지를 만들고(태그는 자동으로 커밋 SHA) 한 스크립트로 올린다 (docs/36)
./ops/19-build-images.sh --registry ghcr.io/<USER> --push
./ops/28-deploy.sh prod $(git rev-parse --short=12 HEAD) --dry-run   # 먼저 무엇이 바뀌는지
./ops/28-deploy.sh prod $(git rev-parse --short=12 HEAD)
./ops/28-deploy.sh prod --rollback        # 잘못됐을 때 — 배포와 같은 코드 경로다

# ⑦ 인증서 먼저 — 이게 없으면 어떤 서비스도 뜨지 않는다 (docs/18)
./ops/07-issue-certificates.sh
set -a; source ./certs/certs.env; set +a

# ① 비밀번호 로테이션 (가장 먼저!)
PGHOST=<db호스트> ./ops/01-rotate-db-password.sh

# ② DLT 토픽 + 컴팩션 상태 토픽 생성
BOOTSTRAP=localhost:29092 COMMAND_CONFIG=client-ssl.properties ./ops/02-create-dlt-topics.sh

# ④ Postgres HA
POSTGRES_PASSWORD=... REPLICATION_PASSWORD=... ./ops/04-deploy-postgres-ha.sh

# 관측성 스택 기동 + 점검
docker compose -f docker-compose-observability.yml up -d
./ops/05-check-observability.sh

# ③ ES 검색 전환 (ES + nori 플러그인 준비 후)
CATALOG_URL=https://<catalog>:8081 ES_URL=https://<es>:9200 INSECURE=1 ./ops/03-enable-es-search.sh

# 인증서 만료 감시 (cron)
MODE=verify WARN_DAYS=30 ./ops/07-issue-certificates.sh

# ⑧ 백업 — 먼저 서버가 PITR 가능한 상태인지 본다
ACTION=check PGHOST=<db호스트> ./ops/08-backup-postgres.sh
ACTION=setup PGHOST=<db호스트> ./ops/08-backup-postgres.sh   # 필요하면 적용 (재기동 필요)
PGHOST=<db호스트> BACKUP_ROOT=/var/backups/pg METRICS_DIR=/var/lib/node_exporter \
  ./ops/08-backup-postgres.sh

# ⑨ 그 백업이 진짜 복구되는지 — 사고를 내고 되돌려 본다
./ops/09-restore-drill.sh

# ⑮ ddl-auto 를 끄기 전에 — 여러분의 DB 가 validate 를 통과하는지 (운영 DB 말고 사본에)
PGURL='postgresql://user:pw@host:5432/productdb' SERVICE=catalog-service \
  APPLY=0 ./ops/15-schema-baseline-rehearsal.sh
```

### cron 예시

```cron
# 매일 03:00 PostgreSQL base backup (WAL 은 서버가 연속 아카이브)
0 3 * * *  cd /srv/ecommerce && PGHOST=db BACKUP_ROOT=/var/backups/pg METRICS_DIR=/var/lib/node_exporter ./ops/08-backup-postgres.sh >> /var/log/pg-backup.log 2>&1
# 매일 04:00 MongoDB
0 4 * * *  cd /srv/ecommerce && MONGO_URI='mongodb://...' BACKUP_ROOT=/var/backups/mongo ./ops/10-backup-mongo.sh >> /var/log/mongo-backup.log 2>&1
# 매주 월요일 05:00 복원 훈련 (CI 에서도 돌지만, 운영 환경에서도 확인)
0 5 * * 1  cd /srv/ecommerce && ./ops/09-restore-drill.sh >> /var/log/restore-drill.log 2>&1
# 매일 06:00 인증서 만료 감시
0 6 * * *  cd /srv/ecommerce && MODE=verify ./ops/07-issue-certificates.sh
```

## 주의

- 이 스크립트들은 **여러분의 인프라를 변경**한다. 처음에는 `DRY_RUN=1` 로 출력부터 확인할 것.
- `promote-replica.sh` 는 되돌릴 수 없다 — 구 프라이머리 정지 확인 후 `CONFIRM=yes`.
- `21-purge-history-secrets.sh` 의 force-push 도 되돌릴 수 없고 **모든 커밋 SHA 를 바꾼다.**
  그리고 그것만으로는 유출이 해결되지 않는다 — GitHub 은 force-push 후에도 옛 객체를 SHA 로
  접근 가능하게 남기고, 이미 퍼진 사본은 회수할 수 없다. **먼저 폐기·재발급할 것**(docs/30 §2).
- Alertmanager 수신 채널은 `⑰ ops/17` 로 설정한다. 설정하지 않으면 Alertmanager 가 기동에 실패한다 —
  **알림이 조용히 안 가는 것보다 낫다고 보고 일부러 그렇게 했다**(docs/22).
- 백업은 `k8s/85-backup-cronjobs.yaml` 로 클러스터 안에서 돌린다. crontab 예시는 k8s 를 쓰지 않을 때만.
  적용 전에 `ACTION=check ./ops/08-backup-postgres.sh` 로 서버가 replication 접속을 허용하는지 확인할 것 —
  허용되지 않으면 pg_basebackup 이 실패한다(실제로 컨테이너에서 재현했다).
