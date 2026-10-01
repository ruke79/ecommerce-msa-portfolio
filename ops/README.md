# 運用アクションスクリプト

**日本語** | [한국어](README.ko.md)

先の改善作業（[docs/](../docs/README.md) 01〜13）がリポジトリの中では完結できなかった
**運用インフラの対応**を、実行可能なスクリプトにしたものである。各スクリプトは:
- `set -euo pipefail` + 必須変数の検証 + 破壊的な操作には確認プロンプト
- `DRY_RUN=1` で、実際には実行せずに行うコマンドを事前に確認できる（01〜04）
- 実行後に **自己検証**まで行う（例: 新しいパスワードでのログイン、レプリケーションの往復確認）

## 実行順序

| # | スクリプト | 対応 | 緊急度 |
|---|---|---|---|
| ① | `01-rotate-db-password.sh` | **DB パスワードのローテーション** — git 履歴に露出したパスワードの無効化 + 伝播のチェックリスト | 🔴 即時 |
| ② | `02-create-dlt-topics.sh` | **DLT トピック 9 種 + コンパクション状態トピック `order-saga-state` の事前作成** — 実行場所を自分で探す（kubectl → docker → ローカルの PATH）。コマンド名も `kafka-topics`（Confluent）・`kafka-topics.sh`（Apache）の両方を試す。以前は k8s ではまったく動作しなかった（docs/33 を書いていて発見）。状態トピックは **すでにあれば `cleanup.policy` を読み、delete なら直す** — アプリが先に起動するとブローカーが delete で自動作成してしまい、`--if-not-exists` はそれを成功として通してしまう（docs/38 §1.17）。`--check` で診断のみ、`FIX_CONFIGS=0` で警告のみ | 🟠 デプロイ直後 |
| ③ | `03-enable-es-search.sh` | 再インデックス → alias/ドキュメント数/nori の検証 → `SEARCH_ENGINE=es` への切り替え（k8s の自動パッチオプション） | 🟡 任意 |
| ④ | `04-deploy-postgres-ha.sh` | プライマリ-レプリカの起動 + DB 8 種の作成 + **レプリケーションの往復検証** | 🟠 推奨 |
| — | `promote-replica.sh` | 障害時のレプリカ昇格（CONFIRM=yes 必須、後続のチェックリストを出力） | 障害時 |
| ⑤ | `05-check-observability.sh` | Prometheus のターゲット/アラートルール/Grafana/OTel Collector · Jaeger のヘルス、トレースパイプラインの数値、Pod → ノード :4318 の到達を一括点検 | スタック起動後 |
| ⑥ | `06-smoke-test.sh` | **デプロイ直後のスモークテスト** — サービスの起動・ゲートウェイ・レートリミッターの 429・ディスカバリー・DLT・コア経路。失敗時は exit 1（CI のゲートにできる） | 毎デプロイ後 |
| ⑦ | `07-issue-certificates.sh` | **証明書の発行 + 注入** — ローカル CA または Let's Encrypt（無料）→ PKCS12/JKS ストア → `certs.env` + k8s Secret。自己検証 6 種 | 🔴 初回 1 回・更新時 |
| ⑧ | `08-backup-postgres.sh` | **PostgreSQL のバックアップ** — PITR の準備状態の点検・設定、base backup、整合性の確認、保持期間の整理、バックアップ指標の公開 | 🔴 毎日（cron） |
| ⑨ | `09-restore-drill.sh` | **PITR 復元訓練** — `DROP TABLE` の事故を起こし、ポイントインタイムリカバリで戻した後、行数・合計・スキーマを照合。RTO を実測。失敗時は exit 1 | 🟠 週 1 回（CI のスケジュール） |
| ⑩ | `10-backup-mongo.sh` | **MongoDB のバックアップ** — mongodump + gzip の整合性 + **空のインスタンスに実際に復元してドキュメント数を照合** | 🟠 毎日（cron） |
| ⑪ | `11-check-db-exposure.sh` | **DB ポートの外部露出の点検** — git 履歴で公開されていたホストのストレージポート 7 種が、公衆網から閉じているか。**必ずインフラの外から実行** | 🔴 即時・ファイアウォール変更後 |
| ⑫ | `12-staging-rehearsal.sh` | **ステージングでのデプロイリハーサル** — インフラ 5 種 + 全サービス 9 種を実際の証明書で同時に起動 → Eureka 登録 → DLT 作成（②）→ スモーク（⑥）まで一度に | 🟠 デプロイ前 |
| ⑬ | `13-k3s-deploy.sh` | **k3s 実クラスターでのデプロイ検証** — イメージのビルド→インポート→インフラ（90-dev-infra）→サービス 9 種→DLT→スモーク。マニフェストが本物のクラスターで動くかを確認 | 🟠 デプロイ前 |
| ⑭ | `14-load-test.sh` | **ローカルの負荷ハーネス** — 同時実行数のスイープ、レスポンスコードの分布・p50/p95/p99。絶対的な数値ではなく **エラーの変曲点**を探すツール（docs/19） | 🟡 チューニング時 |
| ⑮ | `15-schema-baseline-rehearsal.sh` | **`ddl-auto` を切る前のリハーサル** — 既存の DB にベースラインを適用してみて、Hibernate の `validate` と同じ判定を事前に下す。失敗時は exit 1（docs/20 §5） | 🔴 **validate のデプロイ前に必須** |
| ⑰ | `17-configure-alert-receivers.sh` | **アラート受信チャネルの設定 + 到達の証明** — Webhook の URL をコミットされないファイルに、severity によるルーティング、テストアラートを実際に発して **配信成功まで**確認（受理された ≠ 配信された）。失敗時は exit 1 | 🔴 **アラートが必要なら必須** |
| ⑳ | `20-scan-history-secrets.sh` | **git 履歴の資格情報検査** — 作業ツリーではなく **履歴全体**を走査する。値は絶対に出力しない。`--head` はコミット前の素早い点検用、**`--remote` は GitHub の `refs/pull/*` まで**検査する（整理後は必須 — ブランチだけを見ると偽の通過が出る） | 🔴 即時・コミット前・整理後 |
| ㉑ | `21-purge-history-secrets.sh` | **git 履歴の整理** — 新しいミラークローンで値をパターン置換し、⑳ で再検査。**プッシュはしない**（force-push は人が行う）。⚠️ これは後片付けにすぎず、漏洩は **失効・再発行**でしか解決しない（docs/30） | 🟠 **失効完了後のみ** |
| ㉒ | `22-bfg-purge-history.sh` | **git 履歴のファイル削除（BFG）** — シークレットの入ったファイルを履歴から丸ごと消し、残った値は置換。BFG は **パスではなくファイル名**でマッチし、**HEAD を保護**する — どちらも知らないと消えない。HEAD の整合性チェックを内蔵 | 🟠 **失効完了後のみ** |
| ㉓ | `23-oci-ampere-bootstrap.sh` | **OCI Ampere（arm64）の k3s ブートストラップ** — OCI のイメージにあらかじめ入っている遮断ルールがポッドネットワークを塞ぐ問題に対処（ルールは消さず、ポッドの CIDR だけを許可）。**Ubuntu（iptables）と RHEL 系（firewalld + SELinux）の両方**を扱う — Oracle Linux は enforcing なので無効化せず、`container-selinux`・`k3s-selinux` のポリシーを入れ、インストール後に実際に入ったかを確認する。ES 用の `vm.max_map_count`、servicelb の無効化。`--check` で診断のみ | 🟠 インスタンスの初回 1 回 |
| ㉔ | `24-cloudflare-tunnel.sh` | **Cloudflare Tunnel の接続** — トンネルの作成・DNS ルート・資格情報 Secret の注入。**インバウンドポートを 1 つも開けずに**外部公開し、TLS は Cloudflare が終端（ACM・cert-manager 不要）。**`--preflight`** はトンネルを作る前に引っかかる点を確認する — 特に **ネームサーバーが実際に Cloudflare を指しているか**（登録だけでは足りず、伝播に最大 1 日かかる）。ダッシュボードの設定は読めないので、通過したとは言わずにリストとしてだけ提示する（docs/33 §9） | 🟠 外部公開時 |
| ㉕ | `25-provision-external-postgres.sh` | **マネージド PostgreSQL の準備** — プロビジョニングの **前に** SKIP LOCKED・jsonb・IDENTITY・宣言的パーティショニングを実際に実行してみる（HeatWave は MySQL、Autonomous は Oracle なのでここで引っかかる）。通過すればロール 1 + データベース 10 個を冪等に作成。`--check` で診断のみ（docs/32） | 🟠 インフラ外部化時 |
| ㉖ | `26-oci-launch-retry.sh` | **OCI Ampere A1 インスタンスの再試行による起動** — 東京・ソウルでは `Out of host capacity` でほぼ常に弾かれる。容量はイメージ・リージョンの選択とは無関係なので、再試行が唯一の解決策である。**再試行すべき失敗としてはいけない失敗を分ける**（容量不足は継続、LimitExceeded・認証エラーは即中断）。AD が複数あれば巡回（単一 AD のリージョンでは無意味だと警告）+ 429 のバックオフ。`--list-ads/images/subnets` で OCID を照会。**`--check-network`** は SSH が入れる経路かどうかを起動の **前に**確認する（パブリックサブネット・IGW ルート・22 番の許可）— 22 番はデフォルトのセキュリティリストにすでにあるので、たいていの問題はサブネットかルートである。おまけとして、22 番以外にインターネットに開いたルールを探して警告する（docs/33 §4） | 🟠 インスタンス作成時 |
| ㉗ | `27-check-secret-drift.sh` | **資格情報がまだ通用するかの確認** — Secret の値とマネージド DB が別々に変わって食い違っても、**誰も教えてくれない。** すでに起動しているポッドは起動時点の値を持ったまま動き続けるのですべて青信号で、次のロールアウトで一斉に落ちる。値を比較せず、**クラスターの中で実際にログインしてみる**（パスワードがローカルに出てこず、サービスが受け取る値とまったく同じ値を試す）。PostgreSQL 28P01・Redis WRONGPASS・Mongo の認証・`20-secret.yaml` ↔ クラスターの不一致を区別する。`--only postgres\|redis\|mongo\|file`（docs/33 §10） | 🔴 デプロイ前に毎回 |
| ㉘ | `28-deploy.sh` | **デプロイ — すべての環境に同じ手順で。** 環境は引数である（`28-deploy.sh prod <버전>`）。レジストリにそのバージョンが **実際にあるかを先に確認**し（存在しないタグで apply すると、古いポッドが終了し始めてから ImagePullBackOff で気づくことになる）、コミットされたオーバーレイには触れずに `.deploy/` の一時オーバーレイにタグを注入し、user-service→gateway-server の順序を **コードで**守り、`ops/06` のスモークを **ゲート**にする。失敗すると直前の成功バージョンに自動で戻る — **ロールバック専用の経路はない**（普段使うデプロイ関数を古いバージョンでもう一度呼ぶだけなので、毎回のデプロイがそのままロールバック経路のテストになる）。`--dry-run`・`--rollback`・`--report`（サイクルタイム）（docs/36） | 🔴 デプロイのたびに |
| ㉙ | `29-enable-pitr-in-cluster.sh` | **クラスター内の PostgreSQL を PITR 可能にする。** `ops/08` はサーバーと同じマシンで動くことを前提に書かれているため、ポッド内のアーカイブディレクトリと `pg_hba.conf` の replication エントリーを作れない（前者はサーバーが実行するパスだから、後者はファイルなので ALTER SYSTEM の範囲外だから）。その 2 つだけを `kubectl exec` で埋める — バックアップ自体は引き続き `ops/08` が行う。デフォルトは **点検のみ**で何も変更しない; `ACTION=setup` はポッドを再起動するので確認を取る | 🟡 バックアップを有効にする前に 1 回 |
| ㉚ | `30-admin-account.sh` | **最初の管理者のブートストラップ・パスワード再設定・アカウントの有効化。** `AdminController` が丸ごと `hasRole('ROLE_ADMIN')` なので、管理者が 0 人だと API で管理者を作れず、登録フォームでの role 注入はセキュリティ修正で塞がれた。実務でもこの循環は UI ではなくデプロイ経路で断ち切る（Grafana の `GF_SECURITY_ADMIN_PASSWORD`、Django の `createsuperuser`）— **公開 URL に管理者登録画面を置かないこと**が核心である。パスワードは argv ではなく対話形式で受け取り（履歴・`ps` への露出なし）、ハッシュはアプリと **同じ** BCrypt（strength 10）で作る。`status`・`promote`・`password`・`enable` | 🟡 環境を新たに立てるとき |
| ㉛ | `31-deploy-one.sh` | **サービスを 1 つだけビルドしてその場に載せる — 開発ループ用。** ops/28 の代替では **ない**: ジャーナルに残らず、スモークを通らず、クラスターが複数バージョンの混在した状態になる（このリポジトリではその答えがあいまいになってデプロイが 7 時間止まったことがある — docs/39 §7）。直して確認することを繰り返す間だけ使い、原因を突き止めたら `ops/28` で 1 つのバージョンに収束させる（終了時にそのコマンドを出力する）。レジストリ・コンテナ名を **今動いているイメージから**読むので、オーバーレイを解釈し直さない。同じタグだとノードがキャッシュを使うので拒否する。`--dry-run` | 🟢 開発中のみ |
| ㉜ | `32-db-migration.sh` | **DB マイグレーションの状態照会・復旧。** マイグレーションツール（Flyway）はすでに 9 つのサービスすべてにある — なかったのは、それを **本番で扱う方法**である。`summary`（適用済み/待機/失敗）・`info <서비스>`（履歴）・`pending`（**デプロイが DB を何に変えるのか** — デプロイ前に）・`drift`（適用済みのファイルを誰かが直したか — 直していれば次の起動で checksum mismatch で落ちる）・`repair`（失敗した行を削除）。Flyway CLI を新たに導入せず、`flyway_schema_history` で判定する。⚠️ `ops/28 --rollback` は **スキーマを戻さない** — マイグレーションは expand-contract で書くこと | 🟢 デプロイ前後 |
| ㉝ | `33-deploy-some.sh` | **複数のサービスを 1 つのタグでビルドして順に載せる — 開発ループ用。** ops/31 の複数サービス版で、同じ理由で ops/28 の代替では **ない**（ジャーナル・スモークなし、バージョンが混在する — 終了時に収束コマンドを出力）。ビルドは ops/19 で 1 回（`--only "a b c"`）。載せる順序は決まっている（基盤 → バックエンド → user-service → ゲートウェイ → フロント）。デフォルトは **1 つずつ載せ、失敗したらそこで止まり**、すでに載せたものを戻すコマンドを逆順に出力する — `--rollback-on-failure` なら自分で戻す、`--parallel` は一度に変えて待つ。user-service ↔ gateway-server は対なので、片方だけを指定するともう片方を加える（`--allow-split`）。`--changed <git ref>` はそれ以降に変わったモジュールで対象を選ぶ（common・ルートの gradle・ランタイムの Dockerfile なら Java サービスすべて）。同じタグなら拒否、`--dry-run` | 🟢 開発中のみ |
| ㊶ | `41-show-trace.sh` | **トレース1つをターミナルにツリーで描く** — 最新 · `--longest`（Saga）· `--errors` · ログの traceId。スパン間の隙間がアウトボックス · Kafka をまたいだ時間 (docs/63) | 調査時 |
| ㊷ | `42-repair-orphan-sizes.sh` | **親なしで保存されたサイズの復旧**（管理者登録バグ、docs/65 §5）— `status` · `preview`（作成時刻でペアを推定 · 確信度 HIGH/CHECK/NONE、変更しない）· `apply <size_id>:<スタイル id>`（確認したペアのみ、1 トランザクション、1 つでも誤りがあれば全部戻す） | デプロイ後に一度 |
| ㊹ | `44-bulk-catalog.sh` | **負荷テスト用の商品の大量登録 · 削除** — 生成器を運用ノードで安全に: ディスク · バックアップ時間帯の点検、DB を一時的に port-forward、パスワードは Secret から環境変数でのみ渡す、途切れたら続きから、終わったらカタログのキャッシュを消す。`estimate` · `load` · `status` · `purge` · `flush-cache`(docs/19 §5.1) | 🟡 負荷テスト時 |
| — | `tools/generate-catalog-data.py` | **負荷用カタログデータの生成** — 大型ショッピングモール型の分類(大分類 20 · 小分類 99)またはシードの増殖。チャンク単位の COPY で数千万件まで(`--resume` · `--purge`)、`--estimate` で容量を事前確認 — 商品あたり約 2.1KB を実測(docs/19 §5.1) | 🟡 チューニング時 |
| — | `tools/hbm2flyway.py` | **スキーマベースラインの生成** — Hibernate が出力した DDL を冪等な Flyway マイグレーションに変換。手で書くと必ずずれるので機械変換する（docs/20 §3） | エンティティの大改編時 |

## クイックスタート

```bash
# ⑲+㉘ デプロイ — イメージを作り（タグは自動でコミット SHA）、1 つのスクリプトで載せる (docs/36)
./ops/19-build-images.sh --registry ghcr.io/<USER> --push
./ops/28-deploy.sh prod $(git rev-parse --short=12 HEAD) --dry-run   # まず何が変わるかを
./ops/28-deploy.sh prod $(git rev-parse --short=12 HEAD)
./ops/28-deploy.sh prod --rollback        # 問題があったとき — デプロイと同じコード経路である

# ⑦ まず証明書 — これがないとどのサービスも起動しない (docs/18)
./ops/07-issue-certificates.sh
set -a; source ./certs/certs.env; set +a

# ① パスワードのローテーション（最初に！）
PGHOST=<db호스트> ./ops/01-rotate-db-password.sh

# ② DLT トピック + コンパクション状態トピックの作成
BOOTSTRAP=localhost:29092 COMMAND_CONFIG=client-ssl.properties ./ops/02-create-dlt-topics.sh

# ④ Postgres HA
POSTGRES_PASSWORD=... REPLICATION_PASSWORD=... ./ops/04-deploy-postgres-ha.sh

# 可観測性スタックの起動 + 点検
docker compose -f docker-compose-observability.yml up -d
./ops/05-check-observability.sh

# ③ ES 検索への切り替え（ES + nori プラグインの準備後）
CATALOG_URL=https://<catalog>:8081 ES_URL=https://<es>:9200 INSECURE=1 ./ops/03-enable-es-search.sh

# 証明書の期限切れ監視 (cron)
MODE=verify WARN_DAYS=30 ./ops/07-issue-certificates.sh

# ⑧ バックアップ — まずサーバーが PITR 可能な状態かを確認する
ACTION=check PGHOST=<db호스트> ./ops/08-backup-postgres.sh
ACTION=setup PGHOST=<db호스트> ./ops/08-backup-postgres.sh   # 必要なら適用（再起動が必要）
PGHOST=<db호스트> BACKUP_ROOT=/var/backups/pg METRICS_DIR=/var/lib/node_exporter \
  ./ops/08-backup-postgres.sh

# ⑨ そのバックアップが本当に復旧できるか — 事故を起こして戻してみる
./ops/09-restore-drill.sh

# ⑮ ddl-auto を切る前に — 皆さんの DB が validate を通るか（本番 DB ではなくコピーで）
PGURL='postgresql://user:pw@host:5432/productdb' SERVICE=catalog-service \
  APPLY=0 ./ops/15-schema-baseline-rehearsal.sh
```

### cron の例

```cron
# 毎日 03:00 PostgreSQL の base backup（WAL はサーバーが継続的にアーカイブ）
0 3 * * *  cd /srv/ecommerce && PGHOST=db BACKUP_ROOT=/var/backups/pg METRICS_DIR=/var/lib/node_exporter ./ops/08-backup-postgres.sh >> /var/log/pg-backup.log 2>&1
# 毎日 04:00 MongoDB
0 4 * * *  cd /srv/ecommerce && MONGO_URI='mongodb://...' BACKUP_ROOT=/var/backups/mongo ./ops/10-backup-mongo.sh >> /var/log/mongo-backup.log 2>&1
# 毎週月曜日 05:00 復元訓練（CI でも動くが、本番環境でも確認）
0 5 * * 1  cd /srv/ecommerce && ./ops/09-restore-drill.sh >> /var/log/restore-drill.log 2>&1
# 毎日 06:00 証明書の期限切れ監視
0 6 * * *  cd /srv/ecommerce && MODE=verify ./ops/07-issue-certificates.sh
```

## 注意

- これらのスクリプトは **皆さんのインフラを変更**する。最初は `DRY_RUN=1` で出力から確認すること。
- `promote-replica.sh` は元に戻せない — 旧プライマリの停止を確認してから `CONFIRM=yes`。
- `21-purge-history-secrets.sh` の force-push も元に戻せず、**すべてのコミット SHA を変える。**
  そしてそれだけでは漏洩は解決しない — GitHub は force-push の後も古いオブジェクトを SHA で
  アクセス可能なまま残し、すでに広まったコピーは回収できない。**先に失効・再発行すること**（docs/30 §2）。
- Alertmanager の受信チャネルは `⑰ ops/17` で設定する。設定しないと Alertmanager が起動に失敗する —
  **通知が黙って届かないよりはましだと考え、あえてそうした**（docs/22）。
- バックアップは `k8s/85-backup-cronjobs.yaml` でクラスターの中で動かす。crontab の例は k8s を使わない場合のみ。
  適用前に `ACTION=check ./ops/08-backup-postgres.sh` で、サーバーが replication 接続を許可しているかを確認すること —
  許可されていないと pg_basebackup が失敗する（実際にコンテナで再現した）。
