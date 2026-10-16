# 22. アラート配信とバックアップ自動化 — 「設定済みに見える未設定」をなくす

**日本語** | [한국어](22-alerting-and-backup-automation.ko.md)

> 背景: [docs/00 §10](00-before-after.md) が本番準備の障害として挙げた二つ。
> アラートルールはあるのに**受信チャネルがプレースホルダー**で、バックアップスクリプトは検証まで終えていたのに
> **どこでも動いていなかった。** どちらもダッシュボード上では正常に見える。

## 1. アラートがどこにも届いていなかった

`observability/alertmanager.yml` がこの状態だった:

```yaml
receivers:
  - name: default
    # webhook_configs:
    #   - url: "https://hooks.slack.com/services/XXX/YYY/ZZZ"
```

ルール 9 種は正常に発火し([docs/12](12-dlq-rollout-idempotency-alerts.md))、Alertmanager もきちんと
起動している。それなのに**深夜にサービスが落ちても誰も気づかない。** 設定ファイルがそれらしく見えるだけに、なおさら悪い。

### Webhook URL は設定ではなくシークレットである

Slack Incoming Webhook URL は**それ自体が認証手段**である — URL を知っている者は誰でもその
チャネルに投稿できる。コミットしてはならない。そこで値ではなく**ファイルパス**を指す:

```yaml
receivers:
  - name: critical
    webhook_configs:
      - url_file: /etc/alertmanager/secrets/webhook-url-critical
```

ファイルがなければ Alertmanager は**起動に失敗する。** わざとそうしてある — 「通知が設定されていると
思っていたのに、実はどこにも届いていなかった」よりましだ。本当に通知が不要なら receiver を空にする。

### severity ルーティングと抑制

```yaml
routes:
  - matchers: [severity="critical"]
    receiver: critical
    group_wait: 10s        # 人を起こすべきもの — サービスダウン、5xx 急増、
    group_interval: 2m     # バックアップ停止、WAL アーカイブ失敗
    repeat_interval: 30m

inhibit_rules:
  - source_matchers: [alertname="ServiceDown"]
    target_matchers: [severity=~"warning|critical"]
    equal: [service]
```

抑制ルールがないと、サービスが一つ落ちたときにそのサービスの 5xx・レイテンシのアラートが一緒に押し寄せる。
すべて同じ事象の症状なのに、チャネルは派生アラートで埋め尽くされ、肝心の原因が埋もれる。

**実測で確認した。** pay-service ダウン + pay-service 5xx + catalog-service 5xx の 3 件を
同時に発火させ、受信側を見た:

```
전달됨: ServiceDown / pay-service
전달됨: High5xxRate / catalog-service
✅ pay-service 의 5xx 는 억제됨 (ServiceDown 이 원인)
✅ catalog-service 의 5xx 는 정상 전달 (다른 서비스는 억제 안 됨)
```

### 「受理された」と「配信された」は別の話だ

[`ops/17-configure-alert-receivers.sh`](../ops/17-configure-alert-receivers.sh) を最初に
作ったとき、チェックは**すべて緑なのに通知は一つも届かなかった。**

```
✅ critical 채널 파일 존재
✅ 리시버 등록 확인: critical, warning
✅ Alertmanager 가 경보를 수용했다
```

受信側を直接開いてみると何もなかった。Alertmanager のログに原因があった:

```
Notify for alerts failed ... open /etc/alertmanager/secrets/webhook-url-critical:
  permission denied
```

シークレットファイルを `chmod 600` で作ったが、`prom/alertmanager` イメージは **nobody(65534)で
動く。** コンテナが自分のシークレットを読めていなかった。

二つを直した:

1. **権限** — 640 + コンテナ UID 所有。それでも world-readable ではない。k8s では Secret が
   この問題を代わりに処理する。
2. **チェック** — Alertmanager 自身のメトリクスで**配信成功まで**確認する:
   `alertmanager_notifications_total{integration="webhook"}` が増えたか、
   `alertmanager_notifications_failed_total` が 0 か。受信チャネルにアクセスする必要がない。

```
✅ 웹훅 전달 성공 (시도 1 · 실패 0) — 채널이 실제로 메시지를 받았다
```

これがなければ「設定完了」と報告して済ませていただろう。

## 2. バックアップスクリプトはあるのに動いていなかった

`ops/08`・`ops/10`・`ops/09` は実際の障害を巻き戻すところまで検証した([docs/15](15-backup-and-dr.md))。
ところが実行はドキュメントの crontab 例だけだった。誰かがサーバーに手で登録しなければならず、**登録されたか
確認する方法もない。** 登録されていなければバックアップがゼロなのと同じだ。

[`k8s/85-backup-cronjobs.yaml`](../k8s/85-backup-cronjobs.yaml) がこの三つを CronJob にする:

| CronJob | 周期 | 役割 |
|---|---|---|
| `postgres-backup` | 毎日 03:00 UTC | `pg_basebackup -X stream` → gzip 整合性確認 → 保持期間の整理 → メトリクス記録 |
| `mongo-backup` | 毎日 04:00 UTC | `mongodump --gzip` → 整合性確認 → 保持期間の整理 → メトリクス記録 |
| `restore-drill` | 毎週月曜 05:00 UTC | 最新バックアップを**実際に起動して**テーブルが読めるか確認。失敗時は Job が残る |

`kubectl get cronjob` 一つで最終実行時刻が見える。失敗すればイベントが残り、アラートが上がる。

いくつかの選択の理由:

- `concurrencyPolicy: Forbid` — 前のバックアップが終わっていないのにまた始めると互いに邪魔し合う。
- `failedJobsHistoryLimit: 7` (成功は 3) — 失敗は原因を見る必要があるので、より長く残す。
- `timeZone: Etc/UTC` — 明示しないとノードの時計に従う。Outbox のパーティションキーを UTC に
  統一したのと同じ理由だ([B-21](00-before-after.md))。
- `backoffLimit: 0` (復元訓練のみ) — リトライしない。**失敗そのものが知るべきシグナル**だ。

### 初日に失敗しかけたこと

マニフェストをコンテナで実際に動かしてみて分かった:

```
pg_basebackup: error: connection to server ... failed:
  FATAL: no pg_hba.conf entry for replication connection from host "..."
```

`pg_basebackup` は通常接続ではなく **replication 接続**を使うが、デフォルトの PostgreSQL の
`pg_hba.conf` にはリモート replication のエントリがない。準備なしに適用すると、デプロイ初日の 03:00 に
失敗し — その時刻の失敗は誰も見ていない。

今はエラーを捕捉し、必要な設定をそのまま案内する。

> 最初は replication 接続を**模倣するプリフライトプローブ**を入れた。ところがそのプローブが
> 間違っていて(`dbname=replication` が replication モードではなく DB 名として解釈される)、サーバーを
> 正しく設定した後も失敗を報告し続けた。検査対象を模倣するのではなく、**実際の動作そのものを
> 検査とするほうが**正確だ — 今は `pg_basebackup` をそのまま実行し、stderr を解釈している。

### 本番で実際に失敗したこと (2026-10-10)

本番(OCI、クラスタ内 PostgreSQL)で `postgres-backup` は 70 日間止まったまま(`suspend: true`)でした。有効にする前に 1 回実行してみると、原因が四つ重なっていました(四つ目は三つを直した後、2026-10-11 に表れました)。

| 原因 | 症状 | 対処 |
|---|---|---|
| `backup-credentials` に `PGPASSWORD` キーがなかった(`MONGO_URI` だけ) | コンテナが起動すらしない(`CreateContainerConfigError`) — ログがない | `postgres/pg-credentials` から値をコピー(画面に出さず Secret → Secret) |
| `pg_hba.conf` にリモート replication の項目がなかった | 上の `no pg_hba.conf entry for replication` | `ops/29` と同じ目印・同じ行(`host replication all 10.42.0.0/16 scram-sha-256`)を追記して `pg_reload_conf()` — 再起動なし |
| **NetworkPolicy(k8s/95)の反映遅延** | 最初の接続が `Connection refused`。長く動いているサービスは同じアドレスで接続できるのに、バックアップ Job だけが失敗 | 接続確認を 5 秒間隔・最大 12 回にし、最後まで失敗したら psql のエラーをそのまま出力(`BackupCronJobScriptTest`) |
| **指標ファイルの権限** — `/metrics` はノードの hostPath なので fsGroup が効かず、root で動く `mongo-backup` が先に作って root 所有 | バックアップ(821MB、3 分)と検証は成功したのに `can't create /metrics/pg_backup.prom: Permission denied` で Job が失敗 → **再試行がバックアップ全体をやり直す** | initContainer(root)がそのファイルだけを作って 999 に渡し、指標の書き込み失敗は警告のみ(Job は成功) |

三つ目に時間がかかりました。スクリプトが `psql … >/dev/null 2>&1` でエラーを捨て、「ERROR: … 接続できません」の 1 行だけを残していたため、パスワード・pg_hba・ネットワークを切り分けるのに同じ条件の Pod を 3 回別に立てました。原因は k3s のポリシーコントローラ(kube-router)が **新しい Pod の IP を許可リストに入れるまでの数秒間** 拒否することでした(再試行の実験: 5 秒後に成功)。バックアップ Job は起動直後に接続するので、ポリシー適用後は毎回その隙間に当たります。`mongo-backup` は外(Atlas)への接続なのでこのポリシーとは無関係で、毎日成功していました。

> 教訓: 失敗を知らせるメッセージから **原因を消さない**。「失敗した」の 1 行は、もう一度再現しろという宿題を残す。
> そして **本処理が終わった後の付随処理で本処理を失敗させない** — 再試行が高くつく本処理をやり直す。

同じ点検で `restore-drill` も直しました。バックアップには本番の `postgresql.conf` が入っており、クラスタ内 PostgreSQL は初期化時に `ssl = on` とデータディレクトリ **外** の証明書パスを書き込みます — そのまま起動すると証明書がなく、復元したインスタンスが起動できません。`-c ssl=off -c archive_mode=off` で起動します。

本番で最初の復元訓練(2026-10-12)は **復元そのものは成功** しました — 821MB を展開して PostgreSQL が `database system is ready to accept connections` になるまで約 2 分(16:58:40 → 17:00:28)。ところが判定が誤っていて失敗で終わりました。訓練が psql の既定 DB(`postgres`)一つだけでテーブルを数えていましたが、サービスのデータは `cartdb`・`productdb` … にあり、`postgres` DB は元々空です。今はデータベースごとに数えて出力し、合計が 0 のときだけ失敗します。
同じ訓練が **ノードのディスクを 7G** 残しました(空き 23G → 16G)。コンテナ内に展開した復元先は、Pod が残っている間ノードのディスクにそのまま残ります — 失敗した Pod は原因を見るために残し、成功した Pod も履歴の上限まで残ります。今は終了時に(成否を問わず)復元先を止めて消します(`trap cleanup EXIT`)。必要なのはログです。

**再訓練(2026-10-12)は通過** — サービスの DB 9 個でユーザーテーブル 110 個(`metadb` は元々空)、開始から終了まで **1 分 52 秒**(821MB の展開 + 起動 + 確認)。これがこのノードで「バックアップ一つから PostgreSQL 全体を戻す時間」の基準値です。訓練後のディスクも元どおりでした。

**本番の実測(2026-10-11)**: DB 5.6G → `base.tar.gz` 821MB、`pg_wal.tar.gz` 17KB、約 3 分、接続は 1 回拒否された後に成功。ノードでの保持は 7 日(≈ 6GB — ディスクの空きが 20G 程度)、長期保管のコピーはノードの外に置きます。

### 検証

コンテナで三つのスクリプトをすべて実行した。

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

バックアップ → 復元 → データ読み取りまで実際につながることを確認した。

## 3. 適用順序

```bash
# ① アラートチャネル — 設定して到達まで確認
WEBHOOK_CRITICAL='https://hooks.slack.com/services/...' \
WEBHOOK_WARNING='https://hooks.slack.com/services/...' \
K8S=1 NAMESPACE=observability ./ops/17-configure-alert-receivers.sh

# ② PostgreSQL が replication 接続を許可しているかまず確認
ACTION=check PGHOST=<db호스트> ./ops/08-backup-postgres.sh

# ③ バックアップ CronJob を適用
kubectl create secret generic backup-credentials -n ecommerce \
  --from-literal=PGPASSWORD=... --from-literal=MONGO_URI='mongodb://...'
kubectl apply -f k8s/85-backup-cronjobs.yaml

# ④ 待たずに一度実行して確認する
kubectl create job -n ecommerce --from=cronjob/postgres-backup backup-test-1
kubectl logs -n ecommerce -l job-name=backup-test-1 -f
```

> **クラスタ内 PostgreSQL(本番 OCI)では** ③ の `kubectl apply` の代わりに `ACTION=cronjobs ./ops/29-enable-pitr-in-cluster.sh` を使う — 適用と同時に postgres-backup・restore-drill の `suspend` を外す。マニフェストは両者を `suspend: true` で持つので(マネージド DB では成功し得ないため)、手で有効にした後に再び `kubectl apply` すると、何の表示もなく再び止まる。

**本番への適用(2026-10-13)** — 本番ノードで `ACTION=cronjobs ./ops/29-enable-pitr-in-cluster.sh` により `postgres-backup`・`restore-drill` を有効にした(三つの CronJob すべて `SUSPEND False`)。

## 4. 残っていること

| 項目 | 状態 |
|---|---|
| バックアップをクラスタの**外**へ (S3/GCS) | 🟡 ノードの外のコピーは別に置く — 別地域のコピーはまだない |
| PVC サイズを実データ基準に | 🟠 50Gi はプレースホルダーだ。k3s の `local-path` はこのサイズを**強制せず、ノードのディスクをそのまま**使う — 保持日数 × バックアップサイズがノードの空きを超えると、ノードが DiskPressure で Pod を追い出す(2026-10-10 本番: ディスク空き 25G、DB 5.6G) |
| Alertmanager 高可用性 (クラスタモード) | 🟡 単一インスタンス |
| アラート疲れの調整 | 🟡 実際の本番データを見ないと閾値を決められない |
