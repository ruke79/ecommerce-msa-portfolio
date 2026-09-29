# 23. 운영 인프라 선택 — 결정에 필요한 것만

[日本語](23-infrastructure-decision.md) | **한국어**

> 🕰️ **상태(2026-09-27) — 이전 기록.** 결정 자료입니다. 실제 결정은 PostgreSQL · MongoDB 관리형, Redis · Kafka 는 클러스터 안, Elasticsearch 는 운영에 두지 않음입니다([docs/32](32-external-managed-infra.ko.md)).

> 이 문서는 권유가 아니라 **결정 자료**다. PostgreSQL·Kafka·Redis·MongoDB·Elasticsearch 를
> 관리형으로 쓸지 클러스터 안에서 돌릴지에 따라 앞으로 만들 것이 완전히 달라지므로,
> 먼저 이 저장소가 **실제로 각 의존성에서 무엇을 쓰는지**부터 확인했다.

## 0. 먼저 — 좋은 소식

전환 지점이 **한 파일에 모여 있다.** `k8s/10-configmap.yaml` 의 여섯 줄이다:

```yaml
POSTGRES_HOST: "postgres.postgres.svc.cluster.local"
POSTGRES_PORT: "5432"
KAFKA_URL:     "kafka.kafka.svc.cluster.local:9092"
SPRING_DATA_REDIS_HOST: "redis.redis.svc.cluster.local"
SPRING_DATA_REDIS_PORT: "6379"
MONGODB_HOST:  "mongodb.mongodb.svc.cluster.local:27017"
```

**어느 쪽을 고르든 애플리케이션 코드는 바뀌지 않는다.** 관리형으로 가면 이 값들이 벤더
엔드포인트가 되고, TLS/인증 설정 몇 개가 따라 바뀐다. 되돌리는 것도 같은 크기다.
이건 이미 잘 돼 있던 부분이고, 결정을 되돌릴 수 있게 만들어 준다.

## 1. 이 저장소가 각 의존성에서 실제로 쓰는 것

추측을 배제하려고 코드에서 직접 확인했다. 이게 선택을 가르는 실질적 요인이다.

| 의존성 | 실제 요구사항 | 선택에 미치는 영향 |
|---|---|---|
| **PostgreSQL 16** | 파티셔닝, Flyway, **PITR(WAL 아카이빙)**, `pg_basebackup` 용 replication 접속 | 🔴 **관리형 쪽으로 강하게 기움** — §2 |
| **Elasticsearch 8** | `es-settings.json` 이 **`nori_tokenizer` 를 선언**한다 | 🔴 **관리형 쪽으로 강하게 기움** — §3 |
| **MongoDB 7** | 단순 CRUD + 커서 페이지네이션. replica set·트랜잭션·change stream **사용 안 함** | 🟡 어느 쪽이든 무리 없음 |
| **Kafka** | 프로듀서/컨슈머 + DLT. **Kafka Connect 는 배포되지 않음** — §4 | 🟡 어느 쪽이든 무리 없음 |
| **Redis 7** | Redisson 분산 락 (cart·chat·order·pay 4개 서비스) + 레이트리미터 | 🟢 자체 호스팅이 가장 쉬움 |

## 2. PostgreSQL — 관리형이 유리한 이유가 구체적이다

데이터 손실 위험이 가장 큰 곳이고, 방금 ④에서 겪은 일이 그대로 근거가 된다.

백업 CronJob 을 만들면서 `pg_basebackup` 이 **replication 접속**을 요구하고 기본
`pg_hba.conf` 에는 그 항목이 없어서 첫 실행에서 실패한다는 것을 확인했다([docs/22](22-alerting-and-backup-automation.ko.md)).
자체 호스팅이면 이런 것들이 계속 나온다:

- WAL 아카이빙 대상(어디에 쌓을 것인가)과 그 저장소의 보존·정리
- 백업이 **클러스터 밖**에 있어야 한다는 문제 — 지금 PVC 는 클러스터와 운명을 같이한다
- 자동 failover (CNPG 가 해 주지만 오퍼레이터 자체를 운영해야 한다)
- 마이너 버전 업그레이드, 커넥션 수 튜닝, `max_connections` (이미 B-32 가드레일로 계산 중)

관리형(RDS 등)이면 이 목록이 대부분 사라진다. 백업·PITR·failover 가 기본이고,
`k8s/85-backup-cronjobs.yaml` 의 postgres 부분은 **불필요해지거나 보조 수단으로 남는다.**

> ⚠️ 단, 관리형이라고 복구가 검증되는 것은 아니다. `ops/09` 복원 훈련은 **어느 쪽이든**
> 계속 돌려야 한다. "백업이 있다"와 "복구된다"는 여전히 다른 얘기다.

## 3. Elasticsearch — 이건 거의 결정적이다

`common/src/main/resources/es/es-settings.json` 이 한국어 분석기를 선언한다:

```json
"custom_nori_tokenizer": { "type": "nori_tokenizer", ... }
```

그런데 **공식 `elasticsearch` 이미지에는 nori 플러그인이 없다.** 그래서 이 항목이
[docs/00 §10](00-before-after.ko.md) 에 "❌ 보류 — ES 플러그인을 직접 작성·유지보수해야 해서
의도적으로 미룸"으로 남아 있다. 자체 호스팅을 택하면 **플러그인을 넣은 커스텀 이미지를 만들고
ES 버전마다 다시 만들어야 한다.**

관리형(AWS OpenSearch, Elastic Cloud)은 nori 를 기본 제공하거나 콘솔에서 켤 수 있다.
즉 **관리형을 고르면 보류 항목 하나가 저절로 해소된다.**

한국어 검색이 이 서비스의 핵심 기능이라는 점을 감안하면 이 차이는 작지 않다.

## 4. Kafka — 겉보기보다 요구가 가볍다

저장소 루트에 Debezium 커넥터 설정 4개가 커밋돼 있다
(`*-outbox-connector-*.json`, `io.debezium.connector.postgresql.PostgresConnector`).
관리형 Kafka 를 쓸 때 Connect 는 별도 서비스라(MSK Connect 등) 비용·복잡도가 붙는다.

**그런데 Kafka Connect 는 어느 compose 파일에도 없다.** 실제 아웃박스 발행은
폴링 스케줄러가 하고 있고([docs/21](21-horizontal-scaling.ko.md)), 그 경로는 방금 SKIP LOCKED
선점으로 정리했다. 즉 **커넥터 JSON 은 배포되지 않는 죽은 설정**이다.

> 짚어 둘 것: 이 죽은 설정이 **평문 DB 비밀번호 유출의 출처**였다([docs/13](13-secrets-dbha-es-search.ko.md)).
> 배포되지도 않는 파일이 자격증명을 유출시킨 셈이다. 어느 쪽을 고르든, CDC 로 갈 계획이
> 없다면 이 파일들은 정리하는 편이 낫다.

따라서 Kafka 요구는 "프로듀서·컨슈머 + DLT 토픽"뿐이고, 자체 호스팅(Strimzi)도 현실적이다.
다만 Kafka 운영은 ES 다음으로 손이 많이 간다 — 리밸런싱, 디스크 관리, 브로커 교체.

## 5. Redis — 가장 가벼운 쪽

Redisson 분산 락을 4개 서비스가 쓴다. 관리형(ElastiCache)도 자체 호스팅도 잘 동작한다.
데이터가 유실돼도 치명적이지 않다(리프레시 토큰 → 전원 재로그인, [docs/00 §10](00-before-after.ko.md)).

⚠️ 관리형에서 **클러스터 모드**를 켤 경우 Redisson 설정을 클러스터용으로 바꿔야 한다.
단일 노드/복제 모드면 지금 설정 그대로다.

## 6. 비용 — 구조만, 숫자는 직접 확인할 것

정확한 요금을 여기 적으면 금방 낡고 오해를 부른다. **구조**만 정리한다.

| | 관리형 | 클러스터 안 |
|---|---|---|
| 직접 비용 | 인스턴스 + 스토리지 + **IO/전송** (특히 AZ 간 전송이 의외로 큼) | 노드 비용만 (같은 노드에 얹으면 추가 비용 0에 가까움) |
| 숨은 비용 | 벤더 종속, 버전 업그레이드 시기를 벤더가 정함 | **사람 시간** — 장애 대응·업그레이드·용량 관리 |
| 대략적 감각 | 같은 스펙 자체 호스팅 대비 **2~3배 수준**이 흔함 | 인프라비는 싸지만 운영 인건비가 그 차이를 대개 넘음 |

> 실제 금액은 리전·인스턴스·스토리지 IOPS·전송량에 따라 크게 달라지므로 **각 벤더의 현재
> 요금표로 직접 계산**해야 한다. 이 문서의 수치는 방향 감각용이다.
>
> 판단 기준 하나: **한 사람이 이 인프라를 새벽에 복구할 수 있는가.** 팀에 전담 인프라 담당이
> 없다면 관리형의 프리미엄은 대개 보험료로 정당화된다.

## 7. 권장 — 섞는 쪽

사실을 종합하면 **의존성별로 갈리는 것이 명확하다.**

| 의존성 | 권장 | 결정적 이유 |
|---|---|---|
| PostgreSQL | 🔵 **관리형** | 데이터 손실 위험 1순위. PITR·failover·백업 저장소를 직접 운영하는 부담이 가장 큼 |
| Elasticsearch | 🔵 **관리형** | **nori** — 자체 호스팅이면 커스텀 이미지를 계속 유지보수해야 한다(그래서 지금 보류 상태) |
| MongoDB | 🔵 **관리형** | 채팅 데이터는 재생성 불가. 특수 기능을 안 쓰므로 전환이 가장 단순 |
| Kafka | ⚪ **둘 다 가능** | Connect 불필요. 예산이 빠듯하면 Strimzi, 손을 덜 쓰려면 MSK |
| Redis | 🟢 **클러스터 안** | 유실돼도 치명적이지 않고 운영 부담이 가장 작다. 관리형으로 해도 무방 |

**즉 "섞어서 — DB만 관리형"이 이 저장소의 사실관계와 가장 잘 맞는다.**
여기에 ES 를 관리형으로 포함시키는 것이 nori 때문에 특히 이득이다.

## 8. 선택별 후속 작업

| 선택 | 만들 것 |
|---|---|
| **관리형(일부/전부)** | 엔드포인트·TLS·인증을 외부화한 ConfigMap 변형 + 전환 체크리스트 + **연결 검증 스크립트**(엔드포인트가 실제로 닿고 권한이 맞는지, 배포 전에). 백업 CronJob 을 벤더 백업과 겹치지 않게 조정 |
| **클러스터 안** | CNPG 완성본(현재 `.example`) + Strimzi(Kafka) + Redis/Mongo/ES 운영 매니페스트, **k3s 에서 실제 기동 검증**. nori 포함 ES 이미지 빌드까지 |
| **섞어서** | 위 둘의 해당 부분 + 경계에서 자주 나는 문제(관리형 TLS 인증서 신뢰, 보안그룹/네트워크폴리시)를 점검하는 스크립트 |

## 9. 결정과 무관하게 남는 것

어느 쪽을 골라도 이것들은 그대로다:

- `ops/01` DB 비밀번호 로테이션, `ops/11` 방화벽 점검 — 🔴 여전히 최우선
- `ops/15` 스키마 베이스라인 리허설 — `validate` 배포 전 필수
- `ops/09` 복원 훈련 — 관리형이라고 복구가 검증되는 건 아니다
- `ops/17` 경보 채널 — 인프라와 무관
- chat-service·eureka-server 의 수평 확장 제약([docs/21 §2](21-horizontal-scaling.ko.md))
