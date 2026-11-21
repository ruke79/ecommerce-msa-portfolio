# 運用スクリプト — シナリオ別の使い方

**日本語** | [한국어](96-ops-scripts-by-scenario.ko.md)

`ops/` のスクリプトは番号順にできましたが、番号は作った順番にすぎず、使う順番ではありません。
[ops/README](../ops/README.md) は番号ごとに何をするかを並べた一覧で、
この文書は **「いまこういう作業をしなければならない」** から出発して、どのスクリプトをどの順で呼ぶかをまとめます。
各スクリプトの細かいオプションはファイル冒頭のコメント(`sed -n 2,60p ops/<ファイル>`)が基準です。

> 状態: 2026-11-19 時点のリポジトリのスクリプト(01 ~ 61 · `promote-replica.sh` · `tools/`)をまとめました。
> 運用の記録(いつ何を適用したか)は [docs/85](85-release-deploy-runbook.md) と各文書にあります。

## 0. まず — すべてのスクリプトに共通する約束

| 約束 | 意味 |
|---|---|
| 既定は見るだけ | 変更するスクリプトの多くは、引数なし · `check` · `status` · `plan` なら何も変えません。変えるには `apply` · `APPLY=1` · `ACTION=setup` · `CONFIRM=yes` のように明示します。最初は必ず見るだけから |
| `DRY_RUN=1` · `--dry-run` | 実行するコマンドだけを出力(01 ~ 04 · 07 · 28 · 31 · 33) |
| 秘密は出力しない | 長さ · 接頭辞 · A·B·C のような名前だけ。値を尋ねるスクリプトは標準入力で受け取ります — コマンドライン · シェル履歴 · 会話には書きません |
| 自己検証 | 変更後、その結果が実際に通るか(ログイン · 往復 · 403/200)まで確かめ、失敗すれば 0 以外の終了コード |
| 本番ノードの保護 | 負荷 · 大量投入 · 在庫の操作は実習クラスター専用か、本番では `PROD_READONLY=1` · `ALLOW_PROD=1` のような明示があるときだけ動きます |

必ず守ること:

- デプロイは `ops/28-deploy.sh` ひとつ。ConfigMap の変更は `ops/28 … --config-only` — `kubectl patch` · オーバーレイへの `kubectl apply -k` は禁止。
- バックアップ CronJob(`k8s/85`)は `ACTION=cronjobs ./ops/29-enable-pitr-in-cluster.sh` でのみ — そのまま `kubectl apply` するとバックアップが黙って止まります。
- 本番の可観測性 compose コマンドは常に 2 ファイル: `-f docker-compose-observability.yml -f docker-compose-observability.oci.yml`。

## 1. ひと目で — シナリオ → スクリプト

| シナリオ | 使うスクリプト(順に) | 節 |
|---|---|---|
| 新しい本番ノードを初めて立てる | 26 → 23 → 24 → 07 → (25) → 29 → 02 → 38 / 39 → 17 → 28 → 30 → 05 · 06 → 40 | §2 |
| 普段のリリースをデプロイする | 32 → バックアップ → 02 · 38 → 28 → 06 · 32 · 56 · 05 → 37 | §3 |
| 設定(ConfigMap)だけ変える · 戻す | 28 `--config-only` · 28 `--rollback` | §3-3 |
| 開発中にいくつかのサービスだけすぐ上げる | 31 · 33 | §3-4 |
| DB マイグレーションを確認 · 直す | 32 · 15 | §4 |
| バックアップ · 復元を整える · 確かめる | 29 · 08 · 09 · 10 | §5 |
| パスワード · 鍵 · 証明書を替える | 01 · 36 · 46 · 59 · 07 · 38 · 39 · 27 → 62(暗号文の更新) | §6 |
| 本番 Secret を暗号化してリポジトリに置く · 適用する | 62 | §6-4 |
| 秘密が git に漏れたらしい | 20 → 34 → (失効 · 再発行) → 21 / 22 | §6-3 |
| セキュリティ境界が実際に塞いでいるか見る | 11 · 35 · 37 · 27 | §7 |
| 障害 — 何が壊れたかを探す | 05 · 41 · 56 · 32 · 27 · promote-replica | §8 |
| 負荷 · 容量 · JVM を測る | 44 · 48 · 54 → 53 → 57 · 58 · 60 · 61 · `tools/k6-job.sh` | §9 |
| ローカルでデプロイをリハーサルする | 12 · 13 · 14 · 18 · 19 | §11 |
| 検索を Elasticsearch へ | 03 · 53 `search` | §12 |
| 管理者アカウント · データの手当て | 30 · 42 | §13 |

番号から探すときは末尾の[付録](#付録--番号--シナリオ)の表を見てください。

## 2. 新しい本番ノードを初めて立てる

OCI Ampere(arm64)1 台に k3s を載せ、外にはポートを開けず Cloudflare Tunnel だけで公開する順序です。
詳しい背景は [docs/33](33-deploy-runbook.md) · [docs/31](31-kustomize-oci-tunnel.md)。

```bash
# ① インスタンスを確保 — 人気リージョンでは "Out of host capacity" が普通。取れるまで再試行
./ops/26-oci-launch-retry.sh
# ② そのインスタンスで k3s をインストール · 上限を確認(nproc — テナンシーごとに 4 OCPU / 2 OCPU が違う)
./ops/23-oci-ampere-bootstrap.sh
# ③ インバウンドポートなしで外へ — Cloudflare Tunnel の作成 · クラスターへの接続
./ops/24-cloudflare-tunnel.sh
# ④ 証明書(CA · サーバー · ストア)+ certs.env + k8s Secret
./ops/07-issue-certificates.sh
```

| 段階 | スクリプト | メモ |
|---|---|---|
| DB をクラスター外(マネージド)に置くときだけ | `25-provision-external-postgres.sh` | PostgreSQL 固有機能(SKIP LOCKED · jsonb · パーティション)が使えるかを先に確認。いまの本番はクラスター内 PostgreSQL |
| PITR · バックアップ CronJob | `29-enable-pitr-in-cluster.sh` | §5 |
| トピックの事前作成 | `02-create-dlt-topics.sh` | DLT とコンパクション状態トピック。アプリが先に起動するとブローカーが delete ポリシーで作ってしまう — 既にあればポリシーを直す。`--check` で診断のみ |
| サービスごとの身元証明書 | `38-issue-service-identities.sh` または `39-cert-manager.sh` | 38 は 1 年ものの手動発行、39 は cert-manager が 90 日 · 自動更新(Secret 名は同じ — マニフェストはそのまま) |
| アラートの受信先 | `17-configure-alert-receivers.sh` | Discord の URL は尋ねてくる。`MODE=verify` で設定点検 · `MODE=test` でテストアラート |
| 初回デプロイ | `28-deploy.sh local <バージョン>` | §3 |
| 最初の管理者 | `30-admin-account.sh promote <メール>` | §13 |
| 点検 | `05-check-observability.sh` · `06-smoke-test.sh` | 06 は失敗すると exit 1 |
| デプロイランナー(任意) | `40-deploy-runner.sh check` → `install` | GitHub Actions → deploy を使うなら。外向きの接続だけを使う |

## 3. 普段のリリースをデプロイする

順序の原本は [docs/85](85-release-deploy-runbook.md)(まとめリリースのランブック)です。ここではスクリプトだけを追います。

### 3-1. デプロイ前

```bash
# このデプロイが DB を何に変えるか · 適用済みファイルを誰かが直したか(直していれば起動失敗)
./ops/32-db-migration.sh pending
./ops/32-db-migration.sh drift
# 大きなデプロイ — 本番バックアップで次の版の Flyway を先に当ててみる(本番 DB には触れない)
# トピック · 身元 — 新しいトピックや新しいサービス身元が増えたリリースなら
./ops/02-create-dlt-topics.sh
./ops/38-issue-service-identities.sh --only <サービス>
```

- デプロイ前のバックアップは [docs/72 §3](72-model-refactor-deploy-runbook.md) のコマンド(03:00 UTC のバックアップ時間帯は避ける)。
- イメージは CI(`build.yml` → `ops/19-build-images.sh --skip-gradle`)が GHCR に上げます。デプロイする SHA の CI が緑かを先に見ます。

### 3-2. デプロイ · デプロイ直後

```bash
./ops/28-deploy.sh local <SHA12桁> --dry-run    # 何が変わるか
./ops/28-deploy.sh local <SHA12桁>              # 適用 — 失敗すれば自分で直前のバージョンへ
./ops/06-smoke-test.sh                          # 28 も呼ぶが、手でもう一度見るとき
./ops/32-db-migration.sh                        # サービスごとのスキーマバージョン
./ops/56-dlt-replay.sh list order.DLT           # DLT が増えていないか(トピックごと)
./ops/05-check-observability.sh                 # メトリクス · アラート · トレースのパイプライン
```

- `ROLLBACK-UNSAFE` マイグレーションを含むデプロイでは 28 が自動復帰を切ります(B-54)。そのときの戻しは `FORCE_UNSAFE_ROLLBACK=1 ./ops/28-deploy.sh local --rollback`。
- 数日後の observe → enforce 切り替え(mTLS ルール · 身元ヘッダー · Eureka 書き込み)は、`./ops/37-check-mtls.sh` の ① · ①-2 · ①-3 が 0 かを見て決めます(§7)。

### 3-3. 設定だけ · 戻す · 記録

```bash
./ops/28-deploy.sh local --config-only --restart delivery-service   # ConfigMap だけ上げ、そのサービスだけ再起動
./ops/28-deploy.sh local --rollback                                 # 直前に成功したバージョンへ(同じコード経路)
./ops/28-deploy.sh --report                                         # デプロイ履歴 · サイクルタイム
```

### 3-4. 開発ループ — いくつかのサービスだけすぐに

```bash
./ops/31-deploy-one.sh user-service                       # HEAD でビルド → プッシュ → 入れ替え
./ops/33-deploy-some.sh --changed deployed --dry-run      # 動いているイメージ以降に変わったサービスだけ
./ops/33-deploy-some.sh catalog-service order-service --rollback-on-failure
```

本番の正式なデプロイは 28 です — 31 · 33 は開発 · 実習クラスター用です。

## 4. DB マイグレーション

| やりたいこと | コマンド |
|---|---|
| サービスごとのいまのバージョン | `./ops/32-db-migration.sh` — catalog の履歴は `product` スキーマにあります(ツールが設定から読みます、[docs/62 §22](62-engineering-casebook.md)) |
| 1 サービスの適用履歴 | `./ops/32-db-migration.sh info user-service` |
| デプロイすると適用されるもの | `./ops/32-db-migration.sh pending` |
| 適用済みファイルが変わったか · リポジトリにない適用があるか | `./ops/32-db-migration.sh drift` — 2026-11-19、記録になかったデプロイをこれで見つけた |
| 失敗行の削除(元に戻せない — 確認を求める) | `./ops/32-db-migration.sh repair <サービス>` |
| `ddl-auto` を切る前の validate 確認(コピーの DB で) | `PGURL=… SERVICE=catalog-service ./ops/15-schema-baseline-rehearsal.sh` |

ファイルは直さない(チェックサム) · 破壊的変更には `-- ROLLBACK-UNSAFE:` · 列の削除は expand → contract の 2 リリース — [docs/55](55-zero-downtime-migrations.md)。

## 5. バックアップ · 復元

```bash
# クラスター内 PostgreSQL — 点検のみ(既定) → アーカイブ適用(再起動) → CronJob を有効化
./ops/29-enable-pitr-in-cluster.sh
ACTION=setup ./ops/29-enable-pitr-in-cluster.sh
ACTION=cronjobs ./ops/29-enable-pitr-in-cluster.sh   # postgres-backup 03:00 · restore-drill 月曜 05:00
```

| スクリプト | いつ |
|---|---|
| `08-backup-postgres.sh` | クラスター外の PostgreSQL か、手で 1 回 — `ACTION=check` で replication 接続の許可から |
| `09-restore-drill.sh` | そのバックアップが本当に戻るか — 事故を起こして戻してみる |
| `10-backup-mongo.sh` | MongoDB(本番は Atlas の `chat`)のバックアップ + 復元検証 |
| `promote-replica.sh` | プライマリ障害時のレプリカ昇格(元に戻せない、`CONFIRM=yes`) — `04-deploy-postgres-ha.sh` で HA を組んだ場合のみ |

背景 · 実測は [docs/22 §2](22-alerting-and-backup-automation.md) · [docs/15](15-backup-and-dr.md)。

## 6. パスワード · 鍵 · 証明書の交換

### 6-1. 何を替えるか → どのスクリプト

| 対象 | スクリプト | 中断 |
|---|---|---|
| DB パスワード | `01-rotate-db-password.sh` | 伝播チェックリストを出力 |
| Redis パスワード(DB と分離 · 再交換) | `36-rotate-redis-password.sh` | 新しい値を足してから順に再起動 — Redis 再起動時に数秒 |
| JWT 署名鍵 | `46-rotate-jwt-key.sh add` → `activate <kid>` → (30 日後) `retire <kid>` | 誰もログアウトされない。漏れた鍵は `retire --force` |
| キーストア · トラストストアのパスワード | `59-rotate-store-password.sh` check → `apply` → `cleanup`(戻すなら `rollback`) | 1 つずつ再起動 |
| TLS 証明書の期限監視 | `MODE=verify WARN_DAYS=30 ./ops/07-issue-certificates.sh` | — |
| サービス身元証明書 | `38-…`(手動発行) · `39-cert-manager.sh check`(自動更新の状態) | — |

### 6-2. 替えたあと · 普段から

```bash
./ops/27-check-secret-drift.sh            # Secret の資格情報がいま実際に通るか(postgres · redis · mongo · file)
./ops/37-check-mtls.sh                    # 証明書を出せば 200 · 出さなければ 403 — 判定が生きているか
```

### 6-3. 秘密が git 履歴に漏れたらしいとき

順序が大事です — **失効 · 再発行が解決策で、履歴の掃除は後片付け**です([docs/30](30-git-history-secret-exposure.md))。

```bash
./ops/20-scan-history-secrets.sh          # ① 履歴全体から資格情報を探す(値は出力しない)
./ops/34-verify-leaked-passwords.sh       # ② その値がいまも通るか · 本番 Secret と同じか
# ③ 通るなら §6-1 で失効 · 再発行 — 先に
./ops/21-purge-history-secrets.sh         # ④ その後で履歴を掃除(git-filter-repo) — または 22(BFG)
```

21 · 22 は force-push ですべての SHA を変え、元に戻せません。GitHub は古いオブジェクトを SHA で残すため、Support への GC 依頼まで必要です。

### 6-4. 本番 Secret を暗号文でリポジトリに（SOPS + age、[docs/57 §24](57-remaining-priorities.md)）

```bash
./ops/62-secrets-sops.sh keygen                      # 機械ごとに一度 — 公開鍵だけを出力(秘密鍵は ~/.config/sops/age/keys.txt)
./ops/62-secrets-sops.sh keygen ~/age-offline-backup.txt   # 保管用の鍵は名前を渡して別に — オフラインへ移した後に消す
./ops/62-secrets-sops.sh init age1…ノード age1…PC age1…保管   # 受け取る人 — .sops.yaml(公開鍵なのでコミット)
./ops/62-secrets-sops.sh encrypt                     # k8s/20-secret.yaml → k8s/20-secret.enc.yaml(往復確認の後) → コミット
./ops/62-secrets-sops.sh check                       # 値がすべて暗号文 · 復号できるか · 例とキー名 · 平文と同じか
./ops/62-secrets-sops.sh apply                       # 平文ファイルなしで kubectl apply — 先にクラスタと比べる（変えるキーは EXPECT_CHANGED、docs/57 §24-2）
./ops/62-secrets-sops.sh from-cluster                # 暗号文が古ければクラスタの Secret から平文を作り直す
./ops/62-secrets-sops.sh drift                       # 暗号文 ↔ クラスタの Secret — 違うキー名だけ(ops/28 もデプロイ前に呼ぶ)
./ops/62-secrets-sops.sh rotation                    # 四半期の交換チェックリスト — キーごとに値が最後に変わった日、90 日を超えると ⏰
./ops/62-secrets-sops.sh drill /media/usb/age-offline-backup.txt   # 四半期ごと — 保管鍵一つで復号できるか(この機械の鍵は使わない)
```

§6-1 で値を替えた後は `encrypt`(平文がなければ `decrypt` → 修正 → `encrypt`)。受け取る人を替えたら `updatekeys` — 外した人が値を見たことがあれば値そのものを §6-1 で交換します。

## 7. セキュリティ境界が実際に塞いでいるか

| スクリプト | 見るもの |
|---|---|
| `11-check-db-exposure.sh` | DB ポートが外から開いていないか |
| `35-network-policy.sh check` · `apply` · `remove` | NetworkPolicy — 許可されるべき流れはつながり、塞ぐべきものは塞がるか · 管理ポート(9180、actuator)は指標エージェントだけ([docs/57 §25](57-remaining-priorities.md))。`apply` は間違っていれば自分で戻す |
| `35-network-policy.sh egress-check` · `egress-apply` · `egress-remove` | 外向きの接続(k8s/97 — 第 1 段階は 2 グループ、第 2 段階は 443 だけ · メール · Atlas · 指標エージェント · cloudflared · バックアップ、IP 一覧なし、[docs/57 §23 · §26](57-remaining-priorities.md))。`egress-render` はノード IP を展開したマニフェストの出力のみ、`egress-apply` も塞がれば戻す |
| `37-check-mtls.sh` | ② 証明書の有無で 403/200、① `[mTLS observe]` · ①-2 `[mTLS rule]` · ①-3 `[mTLS identity]` が 0 か — **0 でなければ enforce に上げない** |
| `27-check-secret-drift.sh` | Secret と実サーバーの資格情報がずれていないか |

## 8. 障害 — 何が壊れたかを探す

| 症状 | まず | 次に |
|---|---|---|
| メトリクス · アラートが来ない、ダッシュボードが空 | `./ops/05-check-observability.sh` | `MODE=test ./ops/17-configure-alert-receivers.sh` |
| 遅い · エラー — 1 リクエストがどこで詰まったか | `./ops/41-show-trace.sh --errors` · `--longest` · `<traceId>` | `SERVICE=order-service LOOKBACK=6h` で絞る |
| Kafka の処理失敗が DLT に溜まった | `./ops/56-dlt-replay.sh list <トピック>.DLT` — 「連鎖」行が原因 | 直した後 `APPLY=1 ./ops/56-dlt-replay.sh replay <トピック>.DLT <パーティション> <オフセット>` — バイトそのまま · 元のパーティション。すべてのグループが再受信するので、通知などは送り直さない判断もある |
| 起動失敗 — Flyway | `./ops/32-db-migration.sh drift` · `info <サービス>` | `repair`(確認あり) |
| 再起動後の DB · Redis 認証失敗 | `./ops/27-check-secret-drift.sh` | §6 |
| サービス間呼び出しが 403 | `./ops/37-check-mtls.sh` | 身元のマウント · 再起動 · `--only` で再発行 |
| プライマリ DB が落ちた(HA 構成) | `promote-replica.sh`(旧プライマリの停止を確認してから `CONFIRM=yes`) | 出力される後続チェックリスト |
| SSE(通知ストリーム)が nginx の後ろで切れる | `./ops/18-test-nginx-sse.sh` | フロント nginx の版 · ヘッダー(B-114) |

## 9. 負荷 · 容量 · JVM の測定

測定計画は [docs/83](83-load-test.md)(k6 シナリオ) · [docs/91](91-slo-and-capacity.md)(SLO · 天井) · [docs/92](92-jvm-measurement-scenarios.md)(JVM)。

### 9-1. データの準備 — 実習クラスター

```bash
./ops/44-bulk-catalog.sh estimate && ./ops/44-bulk-catalog.sh load    # 商品(既定 200 万)
./ops/48-bulk-users.sh load                                           # ログインできるデモユーザー(既定 10 万)
./ops/54-bulk-orders.sh load                                          # 注文履歴(既定 200 万)
```

3 つとも `estimate` · `load`(再実行すると続きから) · `status` · `purge`(生成範囲だけ)という同じ形です。件数を変えたなら purge も同じ値で。
キャッシュだけ消すのは `./ops/44-bulk-catalog.sh flush-cache`。

### 9-2. 負荷をかける

```bash
./ops/53-load-k6.sh smoke             # 本測定の前に経路 · アカウント · データ · 証明書
./ops/53-load-k6.sh browse            # 非会員の閲覧(階段状の到着率) — order · spike · mixed · contention も
PROM=http://<prometheus>:9090 ./ops/53-load-k6.sh ceiling browse    # 天井探し — SLO が崩れる段で止まり ceiling.md
./ops/53-load-k6.sh report            # 結果の整理
```

- 在庫競合の測定は `stock set` で合わせ、終わったら必ず `stock restore`。検索エンジンの比較は `search es` → 終わったら `search default`。
- 複数回を夜通し続けて回す: `./ops/57-load-queue.sh plan` · `status` · `stop`。
- 負荷中の CPU サンプル: `PROM=… WHEN=rps:gateway-server:1300 ./ops/58-cpu-profile.sh`(その負荷に達したらサービスごとに 1 Pod)。

### 9-3. 本番での読み取り天井 — 決まったモードだけで

```bash
PROD_READONLY=1 K6=ops/tools/k6-job.sh \
  GATEWAY=https://gateway-server.ecommerce.svc.cluster.local:8000 \
  CLIENT_IPS=2000 PROM=http://localhost:9090 ./ops/53-load-k6.sh ceiling browse
```

browse のみ · クラスター内の k6 Job(`tools/k6-job.sh`) · 到着率の上限 `PROD_MAX_RATE` · バックアップ時間帯は拒否 · Alertmanager のサイレンスは自動。本番で 53 はこのモード以外では動きません。

### 9-4. JVM

```bash
./ops/60-jvm-facts.sh                               # 負荷なしで — Pod ごとに JVM が選んだ GC · ヒープ · スレッド(本番は ALLOW_PROD=1)
PROM=http://localhost:9090 ./ops/61-gc-baseline.sh   # 平常時の GC 基準値(Prometheus の読み取りのみ、既定 7 日)
./ops/53-load-k6.sh jvm load catalog-service gc=g1 heap=50   # 比較実験 — 設定を変える
./ops/53-load-k6.sh jvm default catalog-service              # 終わったら必ず戻す(残っていれば ops/28 が警告)
```

JVM の設定は `kubectl set env` で触らず、この 2 つのコマンドだけで変えます。簡単な並行度スイープは `./ops/14-load-test.sh`(k6 以前のローカルハーネス)。

## 10. 実習クラスター

実習 · デモクラスターを立てるスクリプトはこのリポジトリに置きません — 機材ごとに違います。

## 11. ローカルでデプロイをリハーサルする

| スクリプト | 何を |
|---|---|
| `12-staging-rehearsal.sh` | インフラ 5 種 + サービス + スモークを立てて片付ける(`ACTION=down`) |
| `13-k3s-deploy.sh` | ローカル単一ノード k3s にイメージを直接入れてデプロイ → スモーク(`KEEP=1` なら残す) |
| `19-build-images.sh` | デプロイ用イメージ — アーキテクチャを選んでレジストリへ(CI もこのファイル) |
| `14-load-test.sh` | 簡単な並行度スイープ |
| `18-test-nginx-sse.sh` | nginx を通る SSE が本当にストリーミングされるか |

## 12. 検索を Elasticsearch へ

```bash
CATALOG_URL=https://<catalog>:8081 ES_URL=https://<es>:9200 ./ops/03-enable-es-search.sh   # 再インデックス → 検証 → 切り替え
./ops/53-load-k6.sh search status     # 実習でいまのエンジン · インデックス文書数
```

本番(OCI)には ES がありません。比率は機能フラグ(`k8s/15-feature-flags.yaml` + `ops/28 --config-only`)で — [docs/74](74-feature-flags.md)。

## 13. 管理者アカウント · データの手当て

```bash
./ops/30-admin-account.sh status                 # アカウント · ロール · 有効化
./ops/30-admin-account.sh promote <メール>        # ROLE_ADMIN + 有効化
./ops/30-admin-account.sh password <メール>       # パスワード再設定(対話式)
./ops/42-repair-orphan-sizes.sh preview          # 親なしで保存されたサイズの対応推定 — 直さない
./ops/42-repair-orphan-sizes.sh apply 901:77     # 確認した対応だけ
```

## 付録 — 番号 → シナリオ

| # | スクリプト | シナリオ |
|---|---|---|
| 01 | rotate-db-password | §6 交換 |
| 02 | create-dlt-topics | §2 初回 · §3 デプロイ前 |
| 03 | enable-es-search | §12 検索 |
| 04 | deploy-postgres-ha | §5(HA) |
| 05 | check-observability | §3 デプロイ後 · §8 障害 |
| 06 | smoke-test | §3 デプロイ後 |
| 07 | issue-certificates | §2 初回 · §6 期限監視 |
| 08 | backup-postgres | §5 バックアップ |
| 09 | restore-drill | §5 復元訓練 |
| 10 | backup-mongo | §5 バックアップ |
| 11 | check-db-exposure | §7 境界 |
| 12 | staging-rehearsal | §11 ローカル |
| 13 | k3s-deploy | §11 ローカル |
| 14 | load-test | §9 · §11 |
| 15 | schema-baseline-rehearsal | §4 マイグレーション |
| 17 | configure-alert-receivers | §2 初回 · §8 |
| 18 | test-nginx-sse | §8 · §11 |
| 19 | build-images | §3(CI) · §11 |
| 20 · 21 · 22 · 34 | 履歴の秘密の検出 · 掃除 · 確認 | §6-3 漏えい |
| 23 · 24 · 26 | OCI k3s · トンネル · インスタンス再試行 | §2 初回 |
| 25 | provision-external-postgres | §2(マネージド DB) |
| 27 | check-secret-drift | §6 · §7 · §8 |
| 28 | deploy | §3 デプロイ(唯一の正式経路) |
| 29 | enable-pitr-in-cluster | §5 バックアップ |
| 30 | admin-account | §13 |
| 31 · 33 | deploy-one · deploy-some | §3-4 開発ループ |
| 32 | db-migration | §3 · §4 · §8 |
| 35 | network-policy | §7 |
| 36 | rotate-redis-password | §6 |
| 37 | check-mtls | §3 enforce 判断 · §7 · §8 |
| 38 · 39 | service-identities · cert-manager | §2 · §3 · §6 |
| 40 | deploy-runner | §2(デプロイランナー) |
| 41 | show-trace | §8 |
| 42 | repair-orphan-sizes | §13 |
| 44 · 48 · 54 | bulk-catalog · users · orders | §9-1 |
| 46 | rotate-jwt-key | §6 |
| 53 | load-k6 | §9 · §12 |
| 56 | dlt-replay | §3 デプロイ後 · §8 |
| 57 · 58 | load-queue · cpu-profile | §9-2 |
| 59 | rotate-store-password | §6 |
| 60 · 61 | jvm-facts · gc-baseline | §9-4 |
| 62 | secrets-sops | §6-4 |
| — | promote-replica | §5 · §8 |
| tools | `k6-job.sh` §9-3 · `generate-*.py`(44 · 48 · 54 が呼ぶ) · `DltReplay.java`(56 が呼ぶ) · `gen-erd.py` · `hbm2flyway.py`(文書 · 移行ツール) | |

