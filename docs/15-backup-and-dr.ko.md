# 백업 · 재해복구(DR) 설계

[日本語](15-backup-and-dr.md) | **한국어**

> 🕰️ **상태(2026-09-27) — 일부 낡음.** 백업 CronJob 은 생겼지만 `postgres-backup` · `restore-drill` 은 기본 `suspend: true` 입니다([docs/22](22-alerting-and-backup-automation.ko.md)). 클러스터 밖 보관은 아직입니다.

> 작성일: 2026-07-27
> 배경: 최종 점검(G5)에서 "이번 작업에서 **한 번도 다루지 않은 영역**"으로 분류했던 항목.
> 관련: [복제/HA](13-secrets-dbha-es-search.ko.md) · [파티셔닝](09-postgresql-partitioning-sharding.ko.md) · [운영 스크립트](../ops/README.ko.md)

## 0. 현재 상태

| 저장소 | 백업 | 복구 절차 | 상태 |
|---|---|---|---|
| PostgreSQL (논리 DB 8개) | ✅ [ops/08](../ops/08-backup-postgres.sh) base backup + WAL 아카이빙 | ✅ [ops/09](../ops/09-restore-drill.sh) PITR — **실측 검증됨** | 🟢 |
| MongoDB (채팅 메시지) | ✅ [ops/10](../ops/10-backup-mongo.sh) mongodump + 복원 대조 | ✅ 같은 스크립트 `ACTION=restore` | 🟢 |
| Redis (세션·리프레시 토큰·캐시) | ❌ 없음 | — | 🟠 |
| Elasticsearch (상품 색인) | ❌ 없음 | (재색인 가능) | 🟡 |
| Kafka (이벤트 로그) | ❌ 없음 | — | 🟠 |
| 인증서/키스토어 | ✅ 언제든 재발급 가능 | ✅ [ops/07](../ops/07-issue-certificates.sh) | 🟢 |

> 2026-07-28 갱신. 이 문서는 원래 **설계**만 담고 있었고 위 표는 전부 ❌ 였다.
> 지금은 스크립트가 있고, **PostgreSQL 은 실제로 복구까지 해 봤다**(§0.1).
> 다만 스크립트가 있다는 것과 여러분의 운영 DB 에 cron 이 걸려 있다는 것은 다른 얘기다 — §6 을 볼 것.

### 0.1 실측 — 사고를 내고 되돌렸다

`ops/09-restore-drill.sh` 는 설명이 아니라 실행이다. 아카이빙을 켠 PostgreSQL 16 을 띄우고,
주문 데이터를 넣고, base backup 을 뜨고, 데이터를 더 넣고, **`DROP TABLE orders` 로 사고를 낸 뒤**,
base backup + WAL 로 사고 직전 시점으로 되돌린다. 이 저장소에서 실제로 돌린 결과:

```
>>> 4/7  백업 이후 주문 300건 추가 → 이 시점까지 복구되어야 한다
    목표 시각 T = 2026-07-28 03:48:22.830766+00
    T 시점 상태: 800건, 합계 170400000.00
>>> 5/7  사고 재현 — DROP TABLE orders
    사고 후 상태: 1건 (쓰레기 데이터)
>>> 7/7  검증
    OK   행 수 800 = T 시점과 일치
    OK   합계 170400000.00 = T 시점과 일치
    OK   원래 스키마(4개 컬럼) 복원 — DROP TABLE 이 되돌려졌다
    OK   사고 이후 쓰레기 데이터 없음
    RTO(실측) : 3초 / 사용한 WAL 6개
```

이 훈련이 **동어반복이 아닌** 이유: base backup 시점에는 500건이었고 사고 후에는 1건이었다.
결과가 800건이라는 것은 WAL 재생이 실제로 일어났고(500→800), 정확히 목표 시각에서 멈췄다는
뜻이다(800≠1). 너무 일찍 멈춰도 너무 늦게 멈춰도 이 검사는 실패한다.

RTO 3초는 데이터 46MB 기준이다 — 운영 규모의 RTO 는 아니지만, **절차가 동작한다는 사실**은
규모와 무관하게 증명된다. 실제 RTO 는 운영 데이터로 같은 스크립트를 돌려 측정할 것.

MongoDB 도 같은 방식으로 확인했다: 290개 문서를 덤프해 빈 인스턴스에 복원하고 개수를 대조 —
`OK 문서 수 일치 — 이 덤프는 복원 가능합니다`.

**복제(replication)는 백업이 아니다.** [docs/13 §2](13-secrets-dbha-es-search.ko.md)에서 추가한 스트리밍 복제는 *노드 장애*를 막지만, `DROP TABLE`·잘못된 마이그레이션·랜섬웨어는 **복제본에도 즉시 전파**된다. 되돌리려면 시점 복구(PITR)가 필요하다.

## 1. 무엇을 잃으면 안 되는가 — 데이터 등급

복구 목표는 데이터마다 달라야 한다. 전부 똑같이 다루면 비용만 커지고 정작 중요한 걸 놓친다.

| 등급 | 데이터 | 잃으면 | RPO(허용 손실) | RTO(복구 시간) |
|---|---|---|---|---|
| **C1 금전** | `orders.*`, `pay.payment`, 아웃박스 4종 | 매출 분쟁·정산 불가·법적 문제 | **≤ 5분** | ≤ 1시간 |
| **C2 계정** | `users.*`, `coupon.coupon` | 로그인 불가, 쿠폰 소실 | ≤ 1시간 | ≤ 4시간 |
| **C3 콘텐츠** | `product.*`(상품/카탈로그), MongoDB 채팅 | 카탈로그 유실, 대화 기록 손실 | ≤ 24시간 | ≤ 8시간 |
| **C4 파생** | Elasticsearch 색인, Redis 캐시 | 없음 — **재생성 가능** | 백업 불필요 | 재색인/워밍 |

> **C4는 백업하지 않는다.** ES 색인은 `POST /api/products/sync` 재색인으로, Redis 캐시는 워밍업으로 복원된다.
> 단 Redis 의 **리프레시 토큰**은 예외 — 유실 시 전 사용자 재로그인이 발생한다(치명적이진 않으나 인지 필요).

## 2. PostgreSQL — PITR (핵심)

C1(금전) 데이터의 RPO 5분을 만족하려면 **일일 덤프로는 부족**하다. WAL 아카이빙 기반 PITR 가 유일한 답이다.

### 2.1 구성
```
[프라이머리] --WAL 스트리밍--> [스탠바이]        (노드 장애 대비, docs/13 §2)
     └------- WAL 아카이브 ---> [오브젝트 스토리지(S3 등)]  (시점 복구 대비, 이 문서)
     └------- 주간 base backup -> [오브젝트 스토리지]
```

- **pgBackRest** 권장(증분·병렬·검증 내장). 대안: WAL-G, barman.
- 보존: base backup 주 1회 + WAL 연속 → **최근 14일 임의 시점**으로 복구 가능.
- WAL 아카이브 주기를 `archive_timeout = 60s` 로 두면 **최악 RPO ≈ 60초**.

### 2.2 왜 파티셔닝이 백업을 돕는가
[docs/09](09-postgresql-partitioning-sharding.ko.md)에서 아웃박스를 월 파티션으로 만든 덕에, 오래된 파티션은 `DETACH` 후 **백업 대상에서 제외**할 수 있다. 백업 크기와 복구 시간이 데이터 총량이 아니라 **활성 구간에 비례**하게 된다.

### 2.3 k8s 환경
[CloudNativePG](../k8s/80-postgres-cnpg.yaml.example)는 백업/PITR을 선언으로 지원한다:
```yaml
spec:
  backup:
    barmanObjectStore:
      destinationPath: s3://your-bucket/ecommerce-pg
      s3Credentials: { ... }
      wal: { compression: gzip }
    retentionPolicy: "14d"
```

## 3. 나머지 저장소

| 저장소 | 방식 | 주기 | 비고 |
|---|---|---|---|
| MongoDB | `mongodump` → 오브젝트 스토리지, 또는 Replica Set + oplog 기반 PITR | 일 1회(+oplog) | C3 |
| Redis | RDB 스냅샷(선택) | 일 1회 | 캐시는 불필요. **리프레시 토큰 때문에만** 고려 |
| Kafka | 토픽 복제계수 ≥3 + 필요 시 MirrorMaker2 | 상시 | 아웃박스가 원본이므로 **Kafka 자체 백업 우선순위는 낮다** |
| 인증서/키스토어 | **백업하지 않는다 — 재발급이 답이다** ([ops/07](../ops/07-issue-certificates.sh)) | — | 개인키 백업은 유출면만 넓힌다. 발급 절차가 스크립트로 재현 가능하면 백업할 이유가 없다([docs/18](18-certificates.ko.md)) |
| 설정(코드) | git | 상시 | 단 `k8s/20-secret.yaml` 은 gitignore — **별도 백업 필요** |

## 4. 복구 시나리오별 절차

### DR-1. 실수로 데이터 삭제 (가장 흔함)
`DELETE`/`DROP` 이 복제본에도 전파된 상태.
1. 사고 시각 특정(감사 로그·애플리케이션 로그).
2. **새 인스턴스**에 base backup 복원 → `recovery_target_time = '사고 직전'` 으로 PITR.
3. 복구본에서 **해당 테이블만** 추출해 운영에 반영(전체 롤백은 그 사이 정상 거래까지 잃는다).
4. C1 데이터라면 아웃박스/사가 상태 정합성 확인(주문·결제 상태 불일치 점검).

### DR-2. 프라이머리 노드 장애
→ 백업이 아니라 **복제 failover** 영역: `ops/promote-replica.sh` (docs/13 §2).

### DR-3. 리전/클러스터 전체 상실
1. 신규 환경 프로비저닝 — [`k8s/`](../k8s/README.ko.md) 매니페스트로 애플리케이션 계층 재구축.
2. PostgreSQL: 오브젝트 스토리지의 base backup + WAL 로 복원(**여기가 RTO 의 대부분**).
3. MongoDB 복원, Kafka 토픽 재생성(`ops/02-create-dlt-topics.sh`).
4. Elasticsearch: 백업 대신 **재색인**(`ops/03-enable-es-search.sh`).
5. 시크릿 복원 → 서비스 기동 → `ops/06-smoke-test.sh` 로 검증.

### DR-4. 잘못된 마이그레이션 배포
Flyway 도입([docs/09 P0](09-postgresql-partitioning-sharding.ko.md)) 이후 스키마 변경은 버전 관리된다.
- 파괴적 마이그레이션(DROP/ALTER)은 **배포 전 스냅샷** 필수.
- 되돌릴 수 없는 변경은 PITR 로만 복구 → 위험한 마이그레이션 앞뒤로 base backup 을 강제하는 절차를 둘 것.

## 5. 반드시 지켜야 할 원칙

1. **복원을 테스트하지 않은 백업은 백업이 아니다.** → `ops/09-restore-drill.sh` 를 CI 주간 스케줄에 걸어 두었다(`.github/workflows/build.yml` 의 `restore-drill` 잡). 사람의 기억에 맡기지 않는다.
2. **백업은 운영과 다른 장애 도메인에 둔다** — 같은 계정/리전/디스크에 두면 동시에 잃는다. `BACKUP_ROOT` 를 오브젝트 스토리지 마운트나 별도 볼륨으로 지정할 것.
3. **백업 성공/실패를 모니터링한다.** 조용히 멈춘 백업이 가장 위험하다 → `observability/alert-rules.yml` 의 `backup-health` 그룹:
   - `PostgresBackupStale` — 48시간 내 성공 없음
   - `PostgresBackupMetricMissing` — 지표 자체가 없음(= 한 번도 성공한 적 없음). "경보가 안 울린다 = 정상"이라는 착각을 막는다
   - `PostgresWalArchivingFailing` — archive_command 실패. 방치하면 `pg_wal` 이 디스크를 채워 DB 가 멈춘다
   - `MongoBackupStale`
   ops/08·10 의 `METRICS_DIR` 이 node_exporter textfile 지표를 쓴다.
4. **RPO/RTO 를 문서가 아니라 훈련 결과로 검증한다.** → §0.1 에 실측값을 적었다. 운영 데이터로 다시 측정해 갱신할 것.

## 6. 도입 순서 — 현재 위치

| 단계 | 내용 | 상태 |
|---|---|---|
| **1** | PostgreSQL base backup + WAL 아카이빙 | ✅ [ops/08](../ops/08-backup-postgres.sh) — `ACTION=check` 로 서버 준비 상태 점검, `ACTION=setup` 으로 적용 |
| **2** | 복원 훈련 1회 + RTO 실측 | ✅ [ops/09](../ops/09-restore-drill.sh) — §0.1 |
| **3** | 백업 실패 경보 | ✅ `backup-health` 경보 4종 |
| **4** | MongoDB 백업 | ✅ [ops/10](../ops/10-backup-mongo.sh) — 덤프 후 빈 인스턴스에 복원해 문서 수 대조까지 |
| **5** | 정기 훈련 정례화 | ✅ CI 주간 스케줄(`restore-drill`) |
| **6** | 시크릿 관리 도구(Vault/SOPS) 이관 | ❌ 남음 — `k8s/20-secret.yaml` 은 여전히 저장소 밖 수동 관리 |
| **7** | 오브젝트 스토리지 연동 (pgBackRest/WAL-G) | ❌ 남음 — 현재 스크립트는 로컬/마운트 경로 기준. 증분·병렬 백업이 필요한 규모라면 pgBackRest 로 교체 |

> ⚠️ **스크립트가 있다는 것과 백업이 돌고 있다는 것은 다르다.** 이 저장소 안에서 검증한 것은
> *절차가 동작한다*는 사실이다. 여러분의 운영 DB 에 cron 을 걸고 `ACTION=check` 가 초록불인지
> 확인하기 전까지, 실제 데이터는 여전히 백업되지 않은 상태다.
