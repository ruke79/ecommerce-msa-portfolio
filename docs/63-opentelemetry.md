# 63. OpenTelemetry — OTel デモの可観測性構成を適用する

**日本語** | [한국어](63-opentelemetry.ko.md)

> 作成日: 2026-09-27 · 更新: 2026-09-27（テールサンプリング · 永続保存）· 状態: ✅ 適用（デプロイ待ち）
>
> 参考: [OpenTelemetry デモ](https://github.com/open-telemetry/opentelemetry-demo)（ruke79/kafka の `demo-kafka-guide/` は
> その上で作業したもの）、ruke79/kafka の `compose.tracing.yaml` · `test/show-trace.sh`。

## 0. 要約

| | 前（Brave · Zipkin） | 後（OpenTelemetry） |
|---|---|---|
| サービス内のトレーサー | Micrometer Tracing + **Brave** ブリッジ | Micrometer Tracing + **OpenTelemetry** ブリッジ |
| エクスポート | Zipkin 形式 → `zipkin:9411` | **OTLP/HTTP** → `otel-collector:4318` |
| 収集 · 加工 | なし（ストレージへ直接） | **OTel Collector** — メモリ上限 · 資格情報のマスク · バッチ · 分岐 |
| 保存 · UI | Zipkin（インメモリ — 再起動で消える） | **Jaeger v2**（badger ファイル、**7日保管**）— 検索 · サービスマップ · **Monitor タブ（RED）** |
| サンプリング | ヘッドサンプリング（リクエスト開始時に決定） | **テールサンプリング**（トレース終了後に決定）— エラー · 遅いリクエスト · 注文/決済/配送は **すべて**、残りは 10% |
| トレース → メトリクス | なし | **span_metrics** コネクタ → Prometheus（`traces_span_metrics_*`）— サンプリング **前** の全スパンで数える |
| メトリクス → トレース | なし | **exemplar** — レイテンシグラフの点を押すとそのトレース |
| ログ ↔ トレース | Loki ↔ Zipkin | Loki ↔ Jaeger（同じ方式） |
| ターミナルで見る | なし | `ops/41-show-trace.sh` — トレースをツリーで |
| パイプラインのアラート | なし | `tracing-pipeline` グループ（Collector ダウン · エクスポート失敗 · 拒否 · サンプリング待ち枠のあふれ） |

コードはベンダー中立 API（`io.micrometer.tracing.*`）だけを使っていたので、**サービスのコードは1行も変わっていない。**
変わったのは依存関係2行、共有設定1ブロック、マニフェストの環境変数1つ、そして可観測性スタックである。

## 1. 構成

```
 k3s の Pod（11 サービス）                  ノードの docker compose (docker-compose-observability.yml)
 ─────────────────────                     ─────────────────────────────────────────────────────────
 Micrometer Tracing (OTel ブリッジ)
   │ OTLP/HTTP  http://$(HOST_IP):4318/v1/traces
   ▼
                                            otel-collector ── memory_limiter → transform/redact → redaction
                                              │  (全スパン)                       │
                                              │                                   └─▶ span_metrics ─▶ :8889 ◀─ Prometheus
                                              ▼                                                             │ exemplar
                                            tail_sampling → batch  (エラー · 遅延 · お金の経路は全部 + 残り 10%)  │
                                              │ OTLP/gRPC                                                   │
                                              ▼                                                             │
                                            jaeger (badger · ボリューム jaeger-data · 7日) ◀── Monitor タブが Prometheus を読む
                                              ▲                                                             ▼
                                              └──────────── Grafana (Jaeger · Prometheus · Loki データソース) ─┘
                                                              ▲
                                            loki ◀── alloy ◀── /var/log/pods (ログの traceId → Jaeger)
```

OTel デモと同じ形である — サービスは Collector ひとつだけを知り、保存 · 派生メトリクス · マスクは Collector が決める。
デモとの違い:

- デモは Prometheus の OTLP 受信（`--web.enable-otlp-receiver`、Prometheus 3）へ **プッシュする。** ここは Prometheus
  2.53 なので Collector の `prometheus` エクスポーター（:8889）を **スクレイプする。** 結果のメトリクス名は同じ。
- デモはサービスがログ · メトリクスも OTLP で送る。ここは **トレースだけ** が OTLP。メトリクスはすでに `/actuator/prometheus`、
  ログはすでに Loki（Alloy）へ行っているので、移す理由がなかった（§8）。
- デモは Jaeger · Grafana をフロントプロキシの下に置く。ここは Jaeger UI を **ループバックだけ** に開く（§4）。

## 2. 何を変えたか

| どこ | 変わったもの |
|---|---|
| `build.gradle`（ルート） | `micrometer-tracing-bridge-brave` · `zipkin-reporter-brave` → `micrometer-tracing-bridge-otel` · `opentelemetry-exporter-otlp` |
| `application-common.yml` | `management.zipkin.*` → `management.otlp.tracing.endpoint`（`OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`）· `transport: http`、サンプリングを `TRACING_SAMPLING_PROBABILITY` に、resource 属性 `deployment.environment` |
| `k8s/31,40-49` | `ZIPKIN_ENDPOINT` → `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT = http://$(HOST_IP):4318/v1/traces` |
| `k8s/10-configmap.yaml` | `TRACING_SAMPLING_PROBABILITY: "1.0"` · `DEPLOYMENT_ENVIRONMENT: "production"` |
| `observability/otel-collector.yml` | 新規 — 受信 · 加工 · 分岐（§1）、テールサンプリング（§5） |
| `observability/jaeger.yml` | 新規 — Jaeger v2 の設定（badger ファイル保存 · 7日、Monitor タブ = Prometheus） |
| `docker-compose-observability.yml` | `zipkin` → `otel-collector` · `jaeger`（ボリューム `jaeger-data` + 権限を合わせる `jaeger-data-init`）、Prometheus に `--enable-feature=exemplar-storage` |
| `observability/prometheus.yml` | ジョブ3つ — `otel-span-metrics`（:8889）· `otel-collector`（:8888）· `jaeger`（:8888） |
| `observability/alert-rules.yml` | `tracing-pipeline` グループ4ルール |
| Grafana | データソース Zipkin → **Jaeger**（`deleteDatasources` で旧いものを削除）、Prometheus に exemplar → Jaeger、Jaeger → ログ · メトリクス、ダッシュボード **Tracing RED** を追加 |
| `ops/05` | Zipkin の点検 → Collector · Jaeger の点検、トレースパイプラインの数値、Pod → ノード :4318 の到達点検 |
| `ops/41-show-trace.sh` | 新スクリプト — ruke79/kafka の `show-trace.sh` を移植 |
| テスト | トレーステスト4つを本番と同じ OTel ブリッジに（`OtelTestTracing` テストフィクスチャ）、`OtlpTracingExportTest`（設定 → 実際の OTLP リクエスト）、ガード B-59 改訂 · **B-71** · **B-72** 新設 |

### テストが本番と同じトレーサーを使うように

トレーステスト（アウトボックスをまたぐトレース · リスナーがヘッダーを引き継ぐか）は Brave のトレーサーを手で組み立てていた。
本番だけ OTel に変えると「テストは緑、本番はヘッダーがつながらない」になりうる。そこで common のテストフィクスチャ
`OtelTestTracing` が本番と **同じブリッジ · 同じ W3C 伝播** でトレーサーを作り、終わったスパンをメモリに集める。サービスの
トレーステストは `testImplementation(testFixtures(project(':common')))` で受け取る。

### 設定が実際に OTLP で出ていくか — `OtlpTracingExportTest`

キー名がひとつでも違うと、起動は問題なくトレースだけが静かに消える（Zipkin 時代の Pod 内 `localhost:9411` がそうだった — B-59）。
このテストは `application-common.yml` をそのまま読んで自動設定を起動し、コレクターのアドレスを **マニフェストの環境変数名**
（`OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`）で入れたうえで、偽のコレクター（JDK HTTP サーバー）が `/v1/traces` で protobuf を
受け取るか、その中に `service.name` と `deployment.environment` があるかを見る。伝播形式が W3C `traceparent` かも見る
（B3 が混ざるとサービス間でトレースが切れる）。

## 3. どう見るか

| やりたいこと | どこで |
|---|---|
| ログ1行 → そのリクエスト全体 | Grafana → Explore → Loki、行の traceId → **Jaeger で見る** |
| トレースのスパン → その時刻のログ | Jaeger データソースのスパン → **Logs for this span** |
| 遅いリクエストを1つ選んで原因を見る | ダッシュボード **Tracing RED** の p95 グラフで **点（exemplar）** をクリック → そのトレース |
| サービスごとの呼び出し率 · エラー率 · レイテンシ | Grafana **Tracing RED**、または Jaeger **Monitor** タブ |
| サービス間の呼び出し関係 | Jaeger **System Architecture**（サービスマップ） |
| ブラウザなしで | `./ops/41-show-trace.sh [--longest | --errors | <traceId>]` |

`ops/41` の出力はこうなる（ローカル検証で実際に出たもの）:

```
trace 81f28780dc908b1e4c1a3547bb41a259  스팬 2개  서비스 gateway-server
  service          span                                                         at        took
✗ gateway-server   http get /api/auth/verify                            +       0ms       4.3ms
  gateway-server     kafka publish payment-request                      +       0ms       3.2ms
```

`at`（トレース開始からの時刻）が要である。注文 Saga では親が終わってしばらく後に始まる子が見えるが、その隙間が
アウトボックスのリレー · Kafka をまたいだ時間である（docs/56 の遅延をトレース1つで見られる）。

### Jaeger UI への接続

Jaeger は **ループバック（127.0.0.1:16686）だけ** に開いている。手元の PC から:

```bash
ssh -L 16686:localhost:16686 <サーバー>     # そして http://localhost:16686
```

Grafana は Docker ネットワーク内で `jaeger:16686` につなぐので、別途やることはない。

## 4. セキュリティ — トレースもログである

トレースは URL · ヘッダー · エラーメッセージを載せる。ログと同じルール（資格情報 · 個人情報を残さない）を適用する必要がある。

**URL のクエリ文字列を消す（`transform/redact`）。** クエリには OAuth コールバックの `code` · `state`、会員登録の認証リンクの
`token`、パスワード再設定リンクが載る。Spring MVC のサーバースパンはパスだけを載せるが、ゲートウェイのクライアントスパン ·
Feign · WebClient のスパンはクエリまで載せる — **Zipkin 時代はそのまま保存されていた。** `http.url` · `url.full` · `http.uri` ·
`uri` の `?` 以降を `?[redacted]` に置き換え、`url.query` は削除する。パスは残す（どの API だったかは必要）。

**知らない属性まで防ぐ網（`redaction`）。** キー名に password · secret · token · authorization · cookie を含む属性、値が JWT の
形またはメールアドレスの属性を `****` でマスクする。OTel デモの `transform/redact_sensitive_data` + `redaction` と同じ二重構成。
`allow_all_keys: true` が重要 — 既定値（false）は許可リストにない属性を **すべて** 消す。

ローカルで実際のスパンで確認した:

```
http.url       https://www.ysyproject.com/api/auth/verify?token=SECRET…  →  …/api/auth/verify?[redacted]
uri            /api/auth/verify?code=OAUTHCODE&state=S                   →  /api/auth/verify?[redacted]
customer.email someone@example.com                                       →  ****
authorization  Bearer abc                                                →  ****
jwt            eyJhbGciOi….eyJzdWIi….sig                                 →  ****
```

**ポート。**

| ポート | 誰が | バインド | OCI セキュリティリスト |
|---|---|---|---|
| 4318（OTLP/HTTP） | k3s の Pod → ノード IP | すべてのインターフェース（ループバックだと Pod が届かない） | 🔒 **開けない** |
| 16686（Jaeger UI） | 人 — ssh トンネル | `127.0.0.1` | 🔒 開けない |
| 13133（Collector ヘルス） | ops/05 | `127.0.0.1` | 🔒 開けない |
| 4317 · 8888 · 8889 | Docker ネットワーク内 | ホストに出さない | — |

ガード **B-71** が、4318 がすべてのインターフェースに、16686 がループバックだけにあるかを見る。

## 5. 運用

**メモリ。** Zipkin（512m）を外し Collector（256m）+ Jaeger（512m）を足した — 純増 256m。
Collector は `memory_limiter`（200MiB）で受信を止める（サービスはそのバッチを捨てて動き続ける）。ローカルで 30 秒以内にトレース
約 3,100 件を入れたとき、Collector は 57MiB、拒否 0 件だった。

### テールサンプリング — 何を残すかはトレースが終わった後に選ぶ

ヘッドサンプリング（サービスがリクエスト開始時にコインを投げる）は **結果を知らずに** 選ぶ。トラフィックが増えて 0.1 に下げると、
エラーのトレースも 90% 捨てられる — 本当に見るべきものが消える。そこでサービスはすべて送り（`TRACING_SAMPLING_PROBABILITY=1.0`）、
Collector がトレースのスパンが揃うまで（30 秒）保持してから、トレース単位で選ぶ。

| ポリシー | 残すもの | 理由 |
|---|---|---|
| `errors` | スパンが1つでも ERROR のトレースを **すべて** | 障害調査の出発点 |
| `slow` | 1 秒を超えたトレースを **すべて**（`TAIL_SAMPLING_SLOW_MS`） | レイテンシグラフで押す価値のある点（exemplar）はたいていこちら |
| `money-path` | order · pay · delivery を通ったトレースを **すべて** | お金が動いたリクエストは問い合わせが来たら見る必要がある。閲覧よりずっと少ない |
| `baseline` | 残りのうち 10%（`TAIL_SAMPLING_PERCENT`） | 正常な流れのサンプル |

ポリシーは OR である（1つでも残すなら残す）。ローカルでの実測（合成トレース）:

| 種類 | 送った数 | Jaeger に残った数 |
|---|---|---|
| 速くて正常（catalog） | 200 | 27（13.5% — 10% サンプルの揺れの範囲） |
| エラー | 20 | **20** |
| 遅い（2.5 秒） | 10 | **10** |
| 決済（pay-service） | 10 | **10** |

**RED メトリクスはサンプリングの前で数える。** span_metrics をサンプリングの後ろに置くと、正常は 10% だけ、エラーはすべて残るので、
呼び出し数は 10% に減り **エラー率は何倍にも膨らむ** — ダッシュボードとアラートが一緒に嘘をつく。そこでパイプラインを2つに
分けた（`traces` → span_metrics · `forward/sampling` → `traces/sampled` → tail_sampling → Jaeger）。上の実測で Prometheus の
呼び出し数は **240 件すべて**（正常 210 · エラー 20 · 決済 10）だった。ガード **B-72** がこの順序と configmap の 1.0 を見る —
元に戻して失敗することを確認した。

**遅れて届くスパン。** 決済 Saga では、ユーザーが PayPal の承認を数分後に押すこともある。決定の後に届いたスパンが新しい決定を
受けるとトレースが半分だけ残るので、決定を記憶し（`decision_cache`、残す · 捨てるそれぞれ 10 万件）、遅いスパンも同じ決定に
従うようにした。

**exemplar との関係。** exemplar（レイテンシグラフの点）はサンプリング前のスパンから取られる。遅い側 · エラーはすべて残るので
押す価値のある点は開くが、**速くて正常な区間の点は 10% しか開かない**（残りは Jaeger にない）。

`ops/05` が直近1時間の「残した / 捨てた」を表示する。待ち枠（`num_traces` 2 万）があふれて決定なしに捨てると
`TailSamplingDroppedTooEarly` アラートが鳴る。

### 保管 — badger、7日

最初はメモリ保存だったので **再起動するとトレースが消えた** — 「昨夜の決済失敗」の問い合わせが来ても、ログの traceId を押すと
空の画面だった。今は Jaeger に内蔵のファイルストア（badger）を Docker ボリューム `jaeger-data` に置く。保管期間は
`JAEGER_TRACE_TTL`（既定 168h）で、過ぎると badger が自ら消す。イメージが uid 10001 で動くため新しいボリューム（root 所有）に
書けないので、`jaeger-data-init`（busybox）が一度だけ所有者を合わせて終わる。ローカルでエラートレースを1つ、`restart` ·
コンテナ再作成の後に再照会し、どちらも 200 であることを確認した。

**値の長さ上限（B-94）。** badger はタグの値で索引キーを作り、キーの上限は 65,000 バイトである。🐛 2026-10-19 本番で、catalog の「バインド変数 65,535 超過」エラースパンが SQL 原文（`?` 24 万個、約 1MB）をステータスメッセージと例外メッセージに載せてきた。Jaeger は `Key with size 965733 exceeded 65000 limit` でそのスパンを保存できず、失敗を繰り返すうちにメモリ上限（512m）に掛かって `OOMKilled` で止まった。一日近くトレースが途切れた。

今は Collector の `transform/truncate` がスパン属性 · イベント属性 · リソース属性 · ステータスメッセージを 4,096 文字に切る。切るのはマスク（`redaction`）の**後**だ — 先に切ると、切れた JWT · メールアドレスの断片が正規表現をすり抜ける。エラーの原文はログ（Loki）にそのまま残る。

ローカル（collector 0.160 + jaeger 2.20）で同じ大きさのスパンを送って確認した。切る前は保存 0 件で `exceeded 65000 limit` が 2 回、切った後は `db.statement` · `otel.status_description` が 4,096 文字で保存された。適用は `docker compose -f docker-compose-observability.yml up -d --force-recreate otel-collector`（定義が同じだと `up -d` だけでは起動し直さない）。

**起動し直せなかった理由。** `restart: unless-stopped` なのに Jaeger は起動し直せなかった（`restartmanger wait error: failed to mount … snapshots/…`）。10-01 のディスク整理で `/var/lib/containerd/io.containerd.snapshotter.v1.overlayfs` を消したためだ。このディレクトリは k3s ではなく Docker（containerd イメージストア）のコンテナファイルシステムである。すでに動いていたコンテナは付いていたファイルシステムで 3 週間持ちこたえ、止まったものは作り直すこともできなかった。復旧は compose `down`（ボリューム維持）→ docker · containerd 停止 → そのディレクトリを横に移す → `pull` · `up -d`。ボリューム（トレース 7 日 · メトリクス · ログ）は `/var/lib/docker/volumes` なので無事だった。整理は `docker builder prune` · `docker image prune` までにする。

**サービス指標 — クラスタ内エージェント（B-95）。** 🐛 2026-10-20 本番: ノードの Prometheus が開発 PC の設定（`observability/prometheus.yml` の `host.docker.internal:80xx`）で動いていた。k3s のサービスはクラスタ内にしかないので、過去 7 日の `max_over_time(up{job="spring-actuator"}[7d])` が 9 つすべて 0 だった。JVM · コネクションプール · HTTP の指標が一つもなく、それを見るアラート（`High5xxRate` · `HighP99Latency` · `JvmHeapNearLimit` · `CircuitBreakerOpen` …）は鳴れず、`ServiceDown` だけが鳴り続けていた。

今は本番も実習と同じ道だ。
- `k8s-overlays/external/metrics-agent.yaml`（本番の `local` · `oci` が継承）— エージェントモードの Prometheus が指標を出す 11 サービスを TLS 検証付きで集める（CA だけをマウント、SAN 内の名前）。
- 送り先は **その Pod が載ったノードの IP:9090** だ。トレースがノード IP:4318 へ行くのと同じ道である。ノード IP はリポジトリに置けないので、initContainer が `HOST_IP`（downward API）で設定の雛形を埋める。空なら止まる。
- ノードの Prometheus は `docker-compose-observability.oci.yml` を重ねて起動する。remote write の受信を有効にし、設定は `observability/prometheus.oci.yml`（開発 PC のターゲットだけを抜いた）だ。受信とは 9090 に届く誰もが書き込めるということだ。だから 9090 は VCN のセキュリティリストが塞ぐ（22 番のみ — 2026-10-20 確認、NSG なし）。セキュリティリストで 9090 を開けない。
- 名前はそのまま（`job="spring-actuator"`、`service=<サービス>`）なので、ダッシュボード · アラートは変わらない。
- 新アラート `ServiceMetricsMissing`（`absent(up{job="spring-actuator"})`、10 分）: エージェントが止まったり受信なしで起動したりすると `up` 自体が消え、`ServiceDown` は鳴れない。
- ローカル確認（同じ v2.53.0）: 描画（空の `HOST_IP` は失敗）· `promtool check config`（エージェント · ノードの設定）· エージェント → 受信で `up=1` · `absent` 解消。`ops/05` は受信が無効のままエージェントがあれば FAIL にする。

適用（イメージのデプロイとは別 — 次の ops/28 デプロイにもそのまま含まれる）:
```bash
cd ~/ecommerce-msa   # このコミット以降へ switch
docker compose -f docker-compose-observability.yml -f docker-compose-observability.oci.yml up -d prometheus   # 受信を有効化（指標ボリュームはそのまま）
kubectl apply -f k8s-overlays/external/metrics-agent.yaml   # エージェントだけ — オーバーレイ全体を apply しない（古いイメージに新しいマニフェスト）
kubectl -n ecommerce rollout status deploy/metrics-agent && kubectl -n ecommerce logs deploy/metrics-agent --since=5m | grep -ci error
./ops/05-check-observability.sh   # 「サービス指標」に 11 個の up
```
⚠️ 本番では compose のコマンドごとに二つのファイルを一緒に渡す。既定のファイルだけで prometheus を作り直すと開発 PC の設定に戻る（`ServiceDown` · `ServiceMetricsMissing` が鳴る）。

**アラート（`tracing-pipeline`）。** `OtelCollectorDown`（5分）· `TraceExportToJaegerFailing`（10分）· `TracesRefusedByCollector`（10分）·
`TailSamplingDroppedTooEarly`（5分）。
どこが切れてもサービスはエラーなしで動く — だから人が「トレースが見えない」と気づく前にアラートが先に鳴る必要がある。
Collector のメトリクス名はバージョンによって `_total` が付くことがあるので、`{__name__=~"…(_total)?"}` で両方を受ける。

**ホストのファイアウォール。** Pod → ノード IP がふさがれるとトレースは静かに捨てられる。以前 9411 を許可するルールを入れていたなら
**4318 に変える必要がある。** `ops/05` が busybox の Pod で `POST /v1/traces`（空のリクエスト `{}` — スパン 0 件なので保存されない）を
送って確認する。GET は 405 なので点検に使えない。

## 6. バージョン固定と上げ方

| イメージ | 固定 | 理由 |
|---|---|---|
| `otel/opentelemetry-collector-contrib` | **0.160.0** | OTel デモと同じバージョン。設定のコンポーネント名（`otlp_grpc` · `span_metrics`）はこのバージョンの名前 — 以前の名前（`otlp` · `spanmetrics`）と違う |
| `jaegertracing/jaeger` | **2.20.0** | **2.21 が v1 HTTP API の検索（`/api/services` · `/api/traces?service=`）を削除した**（「UI がもう呼ばないエンドポイントの削除」）。Grafana の Jaeger データソースと `ops/41` がそれを使う。2.21 では ID 参照（`/api/traces/{id}`）だけが残り、ログ → トレースはできるが検索が 404 になる — ローカルで実測した |

上げる前に:

```bash
# Collector の設定検証 — コンポーネント名が変わっていればここで引っかかる
docker run --rm -v $PWD/observability/otel-collector.yml:/c.yml otel/opentelemetry-collector-contrib:<新バージョン> validate --config=/c.yml
# Jaeger の設定検証
docker run --rm -v $PWD/observability/jaeger.yml:/c.yml jaegertracing/jaeger:<新バージョン> validate --config=file:/c.yml
# Jaeger を上げたなら: Grafana → Explore → Jaeger → Search ができるか、./ops/41-show-trace.sh が動くか
```

## 7. 検証したこと

検証環境で Docker を使ってスタックを実際に起動して確認した。

- `otelcol-contrib validate` · `jaeger validate` 通過、`promtool check rules`（21 ルール）· `check config` 通過。
- Spring Boot の自動設定（共有設定そのまま）で作った実際のスパンを Collector へ → Jaeger に `gateway-server` · `order-service` が
  現れ、resource に `deployment.environment` がある。
- マスク（§4） — クエリ · メール · JWT · authorization が保存前に消えた。
- span_metrics → Prometheus（`traces_span_metrics_calls_total` · `_duration_milliseconds_bucket`、ラベル `service_name` ·
  `span_name` · `span_kind` · `status_code`）。ダッシュボード **Tracing RED** のクエリ3種がデータを返す。
- exemplar — Prometheus に `trace_id` 付きの exemplar が保存される。
- Jaeger Monitor タブの API — 20 件中 4 件エラー → エラー率 **0.2** と正確に出る。
- Grafana（11.1）→ Jaeger 2.20 のプロキシでサービス一覧 · ID 参照ができる。2.21 ではサービス一覧が 404（§6）。
- busybox `wget --post-data '{}'` → `{"partialSuccess":{}}`（ops/05 の Pod 点検方式）。
- テールサンプリング — エラー · 遅延 · 決済経路は 100%、残りは 13.5%。RED の呼び出し数は 240 件すべて（§5）。
- badger — 再起動 · コンテナ再作成の後もトレースが残る。負荷（トレース 3,100 件/30 秒）で Collector 57MiB、拒否 0。
- `ops/05` · `ops/41` を実際のスタックに対して実行した。
- テスト: `OtlpTracingExportTest` 2件、トレーステスト4件（OTel ブリッジ）、common（B-59 · B-71 · B-72 を含む）、バックエンド全体。

検証できなかったこと: OCI ノードの実際のファイアウォールルール、本番トラフィックでの Collector のメモリ使用量。

## 8. 限界と改善点

> 最初の適用時の限界のうち、**テールサンプリング** と **メモリ保存** は解決した（§5）。

- **サンプリング決定の後に起きたエラーは拾えない。** 30 秒以内に捨てると決めたトレースに後からエラースパンが来ると、決定キャッシュが
  「捨てる」を覚えているのでそのエラーも捨てられる。お金の経路はポリシーですべて残すので、実際に該当するのは閲覧リクエストの遅い
  非同期エラーだけである。エラー自体はログ（Loki）と RED メトリクスに残る。
- **badger はノード1台のストア。** ノードが増えたら（または保管を数か月に延ばすなら）OpenSearch/ClickHouse に移す。
- **テールサンプリングは、1つのトレースのスパンが1つの Collector に集まる必要がある。** 今はノード1台なので自然にそうなる。
  ノードが増えたら、トレース id で Collector を選ぶ `loadbalancing` エクスポーターを前段に置く。
- **ログ · メトリクスは OTLP ではない。** デモのように3つとも OTLP で集めると、resource 属性（サービス · 環境）が一か所で付く。
  今は動いている経路（actuator · Alloy）をあえて移さなかった。
- **ノード1台前提。** Pod は自分のノードの Collector に送る。ノードが増えたら Collector を DaemonSet（ノードごと）または
  クラスター内の Deployment に移す — サービスの設定はアドレスだけが変わる。
- **Grafana が 11.1。** 上げれば Jaeger 2.21+ の v3 API を使うデータソースに移れる（デモは Grafana 13 · Jaeger 2.21）。
- **フロントエンドのトレースがない。** ブラウザから始まるトレース（OTel Web SDK）は CORS · 公開の収集経路が必要なので行っていない。

## 9. デプロイ

1. 可観測性スタック: `docker compose -f docker-compose-observability.yml up -d --remove-orphans` — `zipkin` が消え、`otel-collector` ·
   `jaeger` が起動する。最初の1回だけ `jaeger-data-init` がボリュームの権限を合わせて `Exited (0)` で終わる（正常）。
2. ホストのファイアウォールに 9411 のルールがあったなら 4318 に変える。
3. バックエンド: 依存関係と共有設定が変わったので **すべてのサービスイメージ** を作り直し、マニフェスト（環境変数）が変わったので
   `./ops/28-deploy.sh local <バージョン>`（または `--config-only` でマニフェストを先に）で上げる。
4. 確認: `./ops/05-check-observability.sh` — Pod → :4318 OK、Jaeger にサービスが見えるか。注文を1件入れて
   `./ops/41-show-trace.sh --longest`。
