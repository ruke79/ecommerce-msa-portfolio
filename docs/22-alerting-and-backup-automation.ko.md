# 22. 경보 전달과 백업 자동화 — "설정된 것처럼 보이는 미설정" 없애기

[日本語](22-alerting-and-backup-automation.md) | **한국어**

> 배경: [docs/00 §10](00-before-after.ko.md) 이 운영 준비의 걸림돌로 꼽은 두 가지.
> 경보 규칙은 있는데 **수신 채널이 플레이스홀더**였고, 백업 스크립트는 검증까지 마쳤는데
> **어디서도 돌고 있지 않았다.** 둘 다 대시보드에서는 정상으로 보인다.

## 1. 경보가 아무 데도 가지 않고 있었다

`observability/alertmanager.yml` 이 이 상태였다:

```yaml
receivers:
  - name: default
    # webhook_configs:
    #   - url: "https://hooks.slack.com/services/XXX/YYY/ZZZ"
```

규칙 9종은 정상 발화하고([docs/12](12-dlq-rollout-idempotency-alerts.ko.md)) Alertmanager 도 잘
떠 있다. 그런데 **새벽에 서비스가 죽으면 아무도 모른다.** 설정 파일이 그럴듯해서 더 나쁘다.

### 웹훅 URL 은 설정이 아니라 비밀이다

Slack Incoming Webhook URL 은 **그 자체가 인증 수단**이다 — URL 을 아는 사람은 누구나 그
채널에 글을 쓸 수 있다. 커밋하면 안 된다. 그래서 값이 아니라 **파일 경로**를 가리킨다:

```yaml
receivers:
  - name: critical
    webhook_configs:
      - url_file: /etc/alertmanager/secrets/webhook-url-critical
```

파일이 없으면 Alertmanager 는 **기동에 실패한다.** 일부러 그렇게 뒀다 — "알림이 설정된 줄
알았는데 실은 아무 데도 안 가고 있었다"보다 낫다. 알림을 정말 원치 않으면 receiver 를 비운다.

### severity 라우팅과 억제

```yaml
routes:
  - matchers: [severity="critical"]
    receiver: critical
    group_wait: 10s        # 사람을 깨워야 하는 것들 — 서비스 다운, 5xx 급증,
    group_interval: 2m     # 백업 정지, WAL 아카이빙 실패
    repeat_interval: 30m

inhibit_rules:
  - source_matchers: [alertname="ServiceDown"]
    target_matchers: [severity=~"warning|critical"]
    equal: [service]
```

억제 규칙이 없으면 서비스 하나가 죽었을 때 그 서비스의 5xx·지연 경보가 함께 쏟아진다.
전부 같은 사건의 증상인데 채널은 파생 경보로 뒤덮이고 정작 원인이 묻힌다.

**실측으로 확인했다.** pay-service 다운 + pay-service 5xx + catalog-service 5xx 세 건을
동시에 발사하고 수신 측을 봤다:

```
전달됨: ServiceDown / pay-service
전달됨: High5xxRate / catalog-service
✅ pay-service 의 5xx 는 억제됨 (ServiceDown 이 원인)
✅ catalog-service 의 5xx 는 정상 전달 (다른 서비스는 억제 안 됨)
```

### "수용됐다"와 "전달됐다"는 다른 얘기다

[`ops/17-configure-alert-receivers.sh`](../ops/17-configure-alert-receivers.sh) 를 처음
만들었을 때, 검사가 **전부 초록불인데 알림은 하나도 안 갔다.**

```
✅ critical 채널 파일 존재
✅ 리시버 등록 확인: critical, warning
✅ Alertmanager 가 경보를 수용했다
```

수신 측을 직접 열어 보니 아무것도 없었다. Alertmanager 로그에 원인이 있었다:

```
Notify for alerts failed ... open /etc/alertmanager/secrets/webhook-url-critical:
  permission denied
```

시크릿 파일을 `chmod 600` 으로 만들었는데 `prom/alertmanager` 이미지는 **nobody(65534)로
돈다.** 컨테이너가 자기 시크릿을 못 읽고 있었다.

두 가지를 고쳤다:

1. **권한** — 640 + 컨테이너 UID 소유. 여전히 world-readable 은 아니다. k8s 에서는 Secret 이
   이 문제를 대신 처리한다.
2. **검사** — Alertmanager 자신의 지표로 **전달 성공까지** 확인한다:
   `alertmanager_notifications_total{integration="webhook"}` 이 늘었는지,
   `alertmanager_notifications_failed_total` 이 0 인지. 수신 채널에 접근할 필요가 없다.

```
✅ 웹훅 전달 성공 (시도 1 · 실패 0) — 채널이 실제로 메시지를 받았다
```

이게 없었으면 "설정 끝났다"고 보고하고 넘어갔을 것이다.

## 2. 백업 스크립트는 있는데 돌고 있지 않았다

`ops/08`·`ops/10`·`ops/09` 는 실제 사고를 되돌리는 것까지 검증했다([docs/15](15-backup-and-dr.ko.md)).
그런데 실행은 문서의 crontab 예시뿐이었다. 누군가 서버에 손으로 등록해야 하고, **등록됐는지
확인할 방법도 없다.** 등록되지 않았다면 백업이 0인 것과 같다.

[`k8s/85-backup-cronjobs.yaml`](../k8s/85-backup-cronjobs.yaml) 이 셋을 CronJob 으로 만든다:

| CronJob | 주기 | 하는 일 |
|---|---|---|
| `postgres-backup` | 매일 03:00 UTC | `pg_basebackup -X stream` → gzip 무결성 확인 → 보존 정리 → 지표 기록 |
| `mongo-backup` | 매일 04:00 UTC | `mongodump --gzip` → 무결성 확인 → 보존 정리 → 지표 기록 |
| `restore-drill` | 매주 월 05:00 UTC | 최신 백업을 **실제로 기동해** 테이블이 읽히는지 확인. 실패 시 Job 이 남는다 |

`kubectl get cronjob` 하나로 마지막 실행 시각이 보인다. 실패하면 이벤트가 남고 경보가 뜬다.

몇 가지 선택의 이유:

- `concurrencyPolicy: Forbid` — 앞 백업이 안 끝났는데 또 시작하면 서로를 방해한다.
- `failedJobsHistoryLimit: 7` (성공은 3) — 실패는 원인을 봐야 하므로 더 오래 남긴다.
- `timeZone: Etc/UTC` — 명시하지 않으면 노드 시계를 따라간다. 아웃박스 파티션 키를 UTC 로
  통일한 것과 같은 이유다([B-21](00-before-after.ko.md)).
- `backoffLimit: 0` (복원 훈련만) — 재시도하지 않는다. **실패 자체가 알아야 할 신호**다.

### 첫날 실패할 뻔한 것

매니페스트를 컨테이너에서 실제로 돌려 보고 알았다:

```
pg_basebackup: error: connection to server ... failed:
  FATAL: no pg_hba.conf entry for replication connection from host "..."
```

`pg_basebackup` 은 일반 접속이 아니라 **replication 접속**을 쓰는데, 기본 PostgreSQL 의
`pg_hba.conf` 에는 원격 replication 항목이 없다. 준비 없이 적용하면 배포 첫날 03:00 에
실패하고 — 그 시각의 실패는 아무도 보지 않는다.

이제 오류를 붙잡아 필요한 설정을 그대로 알려 준다.

> 처음에는 replication 접속을 **흉내 내는 프리플라이트 프로브**를 넣었다. 그런데 그 프로브가
> 틀려서(`dbname=replication` 이 replication 모드가 아니라 DB 이름으로 해석된다), 서버를
> 제대로 설정한 뒤에도 계속 실패를 보고했다. 검사 대상을 흉내 내지 말고 **실제 동작 자체를
> 검사로 삼는 편**이 정확하다 — 지금은 `pg_basebackup` 을 그냥 실행하고 stderr 를 해석한다.

### 운영에서 실제로 실패한 것 (2026-10-10)

운영(OCI, 클러스터 안 PostgreSQL)에서 `postgres-backup` 은 70일 동안 꺼진 채(`suspend: true`)였다. 켜려고 1회 실행해 보니 원인이 넷 겹쳐 있었다(넷째는 셋을 고친 뒤 2026-10-11 에 드러났다).

| 원인 | 증상 | 처리 |
|---|---|---|
| `backup-credentials` 에 `PGPASSWORD` 키가 없었다(`MONGO_URI` 만) | 컨테이너가 시작조차 못 한다(`CreateContainerConfigError`) — 로그가 없다 | `postgres/pg-credentials` 에서 값을 복사(화면에 내지 않고 Secret → Secret) |
| `pg_hba.conf` 에 원격 replication 항목이 없었다 | 위 표의 `no pg_hba.conf entry for replication` | `ops/29` 와 같은 표식·같은 줄(`host replication all 10.42.0.0/16 scram-sha-256`)을 덧붙이고 `pg_reload_conf()` — 재기동 없음 |
| **NetworkPolicy(k8s/95) 반영 지연** | 첫 접속이 `Connection refused`. 오래 떠 있는 서비스는 같은 주소로 붙는데 백업 Job 만 실패 | 접속 확인을 5초 간격 최대 12회로, 끝내 실패하면 psql 오류를 그대로 출력(`BackupCronJobScriptTest`) |
| **지표 파일 권한** — `/metrics` 는 노드의 hostPath 라 fsGroup 이 먹지 않고, root 로 도는 `mongo-backup` 이 먼저 만들어 root 소유다 | 백업(821MB, 3분)과 검증은 성공했는데 `can't create /metrics/pg_backup.prom: Permission denied` 로 Job 이 실패 → **재시도가 백업 전체를 다시 뜬다** | initContainer(root)가 그 파일 하나만 만들어 999 에게 넘기고, 지표 쓰기 실패는 경고만(Job 은 성공) |

셋째가 오래 걸렸다. 스크립트가 `psql … >/dev/null 2>&1` 로 오류를 버리고 'ERROR: … 접속할 수 없습니다' 한 줄만 남겨, 비밀번호 · pg_hba · 네트워크를 가르려고 같은 조건의 파드를 세 번 따로 띄웠다. 원인은 k3s 의 정책 컨트롤러(kube-router)가 **새 파드의 IP 를 허용 목록에 넣기까지 몇 초** 거절한다는 것이었다(재시도 실험: 5초 뒤 성공). 백업 Job 은 뜨자마자 접속하므로 정책을 적용한 뒤로는 매번 그 틈에 걸린다. `mongo-backup` 은 바깥(Atlas)으로 나가는 접속이라 이 정책과 무관해 매일 성공했다.

> 교훈: 실패를 알리는 메시지에서 **원인을 지우지 않는다.** "실패했다" 한 줄은 다시 재현하라는 숙제를 남긴다.
> 그리고 **본 작업이 끝난 뒤의 부수 작업은 본 작업을 실패시키지 않는다** — 재시도가 비싼 본 작업을 다시 돌린다.

같은 점검에서 `restore-drill` 도 고쳤다. 백업에는 운영의 `postgresql.conf` 가 들어 있고, 클러스터 안 PostgreSQL 은 초기화 때 `ssl = on` 과 데이터 디렉터리 **밖**의 인증서 경로를 적어 둔다 — 그대로 띄우면 인증서가 없어 복원본이 기동하지 못한다. `-c ssl=off -c archive_mode=off` 로 띄운다.

운영 첫 복원 훈련(2026-10-12)은 **복원 자체는 성공**했다 — 821MB 를 풀고 PostgreSQL 이 `database system is ready to accept connections` 까지 약 2분(16:58:40 → 17:00:28). 그런데 판정이 틀려 실패로 끝났다. 훈련이 psql 의 기본 DB(`postgres`) 하나에서만 테이블을 셌는데, 서비스 데이터는 `cartdb` · `productdb` … 에 있고 `postgres` DB 는 원래 비어 있다. 이제 데이터베이스마다 세어 출력하고, 합이 0 일 때만 실패한다.
같은 훈련이 **노드 디스크 7G** 를 남겼다(여유 23G → 16G). 컨테이너 안에 풀린 복원본은 파드가 남아 있는 동안 노드 디스크에 그대로 있다 — 실패 파드는 원인을 보려고 남기고, 성공 파드도 이력 한도만큼 남는다. 이제 끝나면(통과든 실패든) 복원본을 멈추고 지운다(`trap cleanup EXIT`). 필요한 것은 로그다.

**재훈련(2026-10-12) 통과** — 서비스 DB 9개에서 사용자 테이블 110개(`metadb` 는 원래 빈 DB), 시작부터 끝까지 **1분 52초**(821MB 풀기 + 기동 + 확인). 이것이 이 노드에서 "백업 하나로 PostgreSQL 전체를 되살리는 시간"의 바탕값이다. 훈련 뒤 디스크도 그대로였다.

**운영 실측(2026-10-11)**: DB 5.6G → `base.tar.gz` 821MB, `pg_wal.tar.gz` 17KB, 약 3분, 접속은 1회 거절 뒤 성공. 노드 보관은 7일(≈ 6GB — 디스크 여유가 20G 남짓), 오래 보관하는 사본은 노드 밖에 둔다.

### 검증

컨테이너에서 세 스크립트를 전부 실행했다.

```
>>> base backup → /backup/pg/20260728T162823Z
    ok: base.tar.gz (3,149,150 bytes)
    ok: pg_wal.tar.gz (17,090 bytes)
>>> 보존 정리 (14일) / 완료
지표: pg_backup_last_success_timestamp_seconds 1785256104

>>> 복원 훈련: 복원본 기동 → "database system is ready to accept connections"
    사용자 테이블 1 개
>>> 복원 훈련 통과 (20260728T162823Z)
```

백업 → 복원 → 데이터 읽기까지 실제로 이어지는 것을 확인했다.

## 3. 적용 순서

```bash
# ① 경보 채널 — 설정하고 도달까지 확인
WEBHOOK_CRITICAL='https://hooks.slack.com/services/...' \
WEBHOOK_WARNING='https://hooks.slack.com/services/...' \
K8S=1 NAMESPACE=observability ./ops/17-configure-alert-receivers.sh

# ② PostgreSQL 이 replication 접속을 허용하는지 먼저 확인
ACTION=check PGHOST=<db호스트> ./ops/08-backup-postgres.sh

# ③ 백업 CronJob 적용
kubectl create secret generic backup-credentials -n ecommerce \
  --from-literal=PGPASSWORD=... --from-literal=MONGO_URI='mongodb://...'
kubectl apply -f k8s/85-backup-cronjobs.yaml

# ④ 기다리지 말고 한 번 돌려서 확인한다
kubectl create job -n ecommerce --from=cronjob/postgres-backup backup-test-1
kubectl logs -n ecommerce -l job-name=backup-test-1 -f
```

> **클러스터 안 PostgreSQL(운영 OCI)에서는** ③ 의 `kubectl apply` 대신 `ACTION=cronjobs ./ops/29-enable-pitr-in-cluster.sh` 를 쓴다 — 적용과 함께 postgres-backup · restore-drill 의 `suspend` 를 끈다. 매니페스트는 둘을 `suspend: true` 로 들고 있어서(관리형 DB 에서는 성공할 수 없으므로), 손으로 켠 뒤 다시 `kubectl apply` 하면 아무 표시 없이 다시 꺼진다.

**운영 적용(2026-10-13)** — 운영 노드에서 `ACTION=cronjobs ./ops/29-enable-pitr-in-cluster.sh` 로 `postgres-backup` · `restore-drill` 을 켰다(세 CronJob 모두 `SUSPEND False`).

## 4. 남은 것

| 항목 | 상태 |
|---|---|
| 백업을 클러스터 **밖**으로 (S3/GCS) | 🟡 노드 밖 사본은 따로 둔다 — 다른 지역 사본은 아직 없다 |
| PVC 크기를 실제 데이터 기준으로 | 🟠 50Gi 는 자리표시자다. k3s 의 `local-path` 는 이 크기를 **강제하지 않고 노드 디스크를 그대로** 쓴다 — 보관 일수 × 백업 크기가 노드의 여유를 넘으면 노드가 DiskPressure 로 파드를 내쫓는다(2026-10-10 운영: 디스크 여유 25G, DB 5.6G) |
| Alertmanager 고가용성 (클러스터 모드) | 🟡 단일 인스턴스 |
| 경보 피로도 조정 | 🟡 실제 운영 데이터를 봐야 임계값을 정할 수 있다 |
