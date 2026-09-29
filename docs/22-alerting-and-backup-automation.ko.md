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

## 4. 남은 것

| 항목 | 상태 |
|---|---|
| 백업을 클러스터 **밖**으로 (S3/GCS) | 🟠 지금은 PVC — 클러스터가 통째로 사라지면 백업도 함께 사라진다 |
| PVC 크기를 실제 데이터 기준으로 | 🟠 50Gi 는 자리표시자다 |
| Alertmanager 고가용성 (클러스터 모드) | 🟡 단일 인스턴스 |
| 경보 피로도 조정 | 🟡 실제 운영 데이터를 봐야 임계값을 정할 수 있다 |
