# 91. SLO と容量測定 — 移行段階 0

**日本語** | [한국어](91-slo-and-capacity.ko.md)

> 📝 **状態（2026-11-14）— SLO · バジェットの規則 · スループット目標を決めて適用、実測は残り。** [docs/89](89-scale-out-migration-plan.md) 段階 0（目標と測定）のリポジトリ側の作業です。SLO 四つ（§2）を記録ルール · バーンレート警報に、14 日のエラーバジェットとその規則（§2-4）、段階別のスループット目標（§2-5）、客側の経路を見る外部プローブ（§2-6）、コンシューマー遅延の指標（§3）、天井を測る `ops/53 ceiling`（§4）。残るのは実習クラスターでの天井測定とノード外の監視です（§6）。

## 0. 要約

| | 何を | どこ |
|---|---|---|
| SLO 四つ | `catalog-browse`（ホーム · 詳細）· `catalog-search`（一覧 · フィルター · 名前検索）· `order-create`（注文作成）· `site-probe`（外部プローブ）— 今（ノード一台）の値と段階 1 · 2 後の目標 | `observability/alert-rules.yml` の `slo` グループ |
| 遅延の境界バケット | ヒストグラムに**正確な** 0.3 · 1.0 秒の境界 — 既定のバケットにはなく「300ms 以内の割合」を数えられなかった | `application-common.yml` の `management.metrics.distribution.slo` |
| バーンレート警報 | 多窓の四つ — 速い（1h · 5m、14.4 倍、critical）· 遅い（6h · 30m、6 倍、warning）、リクエストが少なければ鳴らない | `slo` グループ · promtool テスト |
| 14 日のエラーバジェット | バジェット使用率 · 半分（warning）· 使い切り（critical）の警報とそのときの規則 | `slo-budget` グループ |
| スループット目標 | 段階 2 の終わりに毎秒 1,000 · 段階 3 の終わりに毎秒 5,000 | §2-5 · `ops/53 ceiling` の `CEILING_TARGET_RPS` |
| 外部プローブ | 本番ノードの blackbox-exporter が Cloudflare 経由で最初の画面 · ホーム API を 30 秒ごとに、警報 `SiteProbeDown` | `docker-compose-observability.oci.yml` · `observability/blackbox.yml` · `prometheus.oci.yml` |
| コンシューマー遅延 | **これまで指標がなかった** — すべてのコンシューマーファクトリーに Micrometer リスナー、警報 `KafkaConsumerLagHigh` | common `KafkaConsumerMetricsAutoConfiguration` · `kafka-consumer` グループ |
| 天井の測定 | 到着率の階段ごとに SLO を見て、破れた階段で止まる — サービス別の Pod あたりリクエスト/秒 · CPU · DB 接続待ち · Kafka 遅延、スループット目標と比べる | `./ops/53-load-k6.sh ceiling browse|order|mixed` |

---

## 1. なぜ今

docs/89 は「数字なしで次の段階を選ぶと、機材 · マネージドの大きさを当て推量で決めることになる」としました。段階 0 を始めてみると、数字を出すツールから空いていました。

- **SLO がリポジトリのどこにもなかった**（docs/86 §0）。あったのはサービス別の 5xx 5% · p99 3 秒の警報だけ — 「目標をどれだけ使っているか」ではなく「大きく壊れたか」しか見ない。
- **p99 300ms を数えられなかった。** `percentiles-histogram` の既定のバケットには 0.3 · 1.0 秒の境界がなく、`le="0.3"` の時系列がない（`SloRulesTest` が確認）—「300ms 以内の割合」は近似値（`histogram_quantile`）でしか出なかった。
- **コンシューマー遅延の指標がなかった。** Spring Boot は自分の自動設定のコンシューマーファクトリーにだけ Micrometer リスナーを付けるが、このリポジトリのサービスはすべてファクトリーを自前で作る（`KafkaConsumerConfig` · `KafkaDltConfig`）。
- **サービス側の指標は客側の経路を見ない** — Cloudflare · トンネル · ゲートウェイ · フロントが詰まっても catalog の 5xx は 0 だ。
- **`ops/53` は一度に一つの形の負荷**をかけていた — 天井を探すには、人が到着率を変えながら何度も回し、Grafana で数字を書き写す必要があった。

---

## 2. SLO

| SLO | SLI の対象 | 可用性 今 → 目標 | 遅延 | 根拠（docs/83 の実測など） |
|---|---|---|---|---|
| `catalog-browse` | catalog の GET — ホーム · 商品詳細 · バナー · お知らせ · 企画展（`/api/search*` · 管理者 · 内部 · actuator を除く） | **99.5% → 99.9%** | **99% が 300ms 以内** | 実測 p99 22 〜 25ms（商品 1,000 万、キャッシュ外の詳細を含む）— 本番の機材 · キャッシュ外まで余裕 |
| `catalog-search` | catalog の GET `/api/search*` — カテゴリー一覧 · フィルター · 名前検索 | **99.0% → 99.5%**（ES 後 99.9%） | **95% が 1 秒以内** | フィルターの組み合わせを広げた 8 時間の回で p95 1,513ms · 本番 200 万のまれなフィルターの組み合わせは 5 秒上限で 503（docs/83 §2.8）— ES まで残る限界 |
| `order-create` | order の `POST /api/order/create` | **99.5% → 99.9%** | **99% が 1 秒以内** | 実測 p99 113ms（毎秒 5 件 55 分）— 在庫の競合 · クーポン · カート呼び出しの余裕 |
| `site-probe` | 外部プローブ — 最初の画面 · ホーム API（§2-6） | **99.5% → 99.9%** | **99% が 2 秒以内** | Cloudflare · トンネル · ゲートウェイ · フロントまで一度に |

- **今 → 目標**: 今は PostgreSQL · Redis · Kafka が `replicas: 1` でノードが一台なので（docs/89 §0）、ノードの再起動 · PG Pod の作り直し一つがそのまま全停止です。99.9%（14 日で約 20 分）はメンテナンス一回でバジェットを使い切ります — 最初から掛けると初月から「バジェットなし」で始まります。99.5%（14 日で約 1.7 時間）はノード一台で守れる線です。docs/89 段階 1 · 2 を終えたら目標値に上げます（ルールのコメントに書きました）。
- **検索を分けた理由**: カテゴリー一覧の画面も `/api/search` を使います。まれなフィルターの組み合わせの 503 は既知の限界ですが、一つの SLO に混ぜると、それが**商品 · ホームのバジェットまで食います**。検索の遅延目標はテールが長いので 99% ではなく 95% です（境界の 1 秒はすでにあるバケット）。
- **エラーは 5xx だけ**です。4xx は客側（誤ったリクエスト · 在庫不足 · 他人のリソース · 流入制限の 429）なのでバジェットから外します。
- **サービス側 SLO の遅延はサービス側の時間**です（`http.server.requests`）。ゲートウェイ · Cloudflare · 回線の時間は `site-probe` が見ます。
- **数字を変える場所は一か所**: `alert-rules.yml` の `slo:objective:availability` · `slo:objective:latency` の二行（SLO ごと）。ルールは生成して貼ったので、SLO を足したりセレクターを変えたりするときは同じ形で四つ（窓）× 二つ（SLI）を一緒に直します。遅延の**境界**を変えるときは `application-common.yml` の `slo` バケット · ルールの `le` · `ops/53` の `CEILING_P99_MS` の既定値も — ガード `SloRulesTest` が照合します。

### 2-1. 記録ルール

SLO ごと、窓四つ（5m · 30m · 1h · 6h）ごとに:

| 記録名 | サービス側 SLO | `site-probe` |
|---|---|---|
| `slo:sli_error:ratio_rate<窓>` | 5xx / 全体 — 5xx がなければ 0（`or vector(0)`） | 1 − プローブ成功の割合 |
| `slo:sli_slow:ratio_rate<窓>` | 1 −（境界以内 / 全体）— 5xx も数える | プローブ時間 > 2 秒の割合 |
| `slo:requests:rate1h` | 1 時間平均の毎秒リクエスト — 警報の下限 | 毎秒のプローブ数（対象二つ × 30 秒 = 0.067） |
| `slo:requests:increase1h` · `slo:errors:increase1h` · `slo:slow:increase1h` | 1 時間の増分 — 14 日のバジェットが足し合わせる（§2-4） | 同じ意味のプローブ数 |
| `slo:objective:availability` · `slo:objective:latency{threshold_seconds}` | 目標（定数） | 同じ |

### 2-2. バーンレート警報

| 警報 | 条件（両方） | 意味（14 日バジェット基準） | 等級 |
|---|---|---|---|
| `SloErrorBudgetBurnFast` · `SloLatencyBudgetBurnFast` | 1h · 5m の割合 > 14.4 × バジェット | 14 日バジェットの約 4% を 1 時間で — 今も続く大きな問題 | critical |
| `SloErrorBudgetBurnSlow` · `SloLatencyBudgetBurnSlow` | 6h · 30m の割合 > 6 × バジェット | 約 11% を 6 時間で — ゆっくり漏れる問題 | warning |

- 長い窓が「十分たくさん使った」、短い窓が「今も使っている」— 終わった事故で一時間ずっと鳴りません（Google SRE workbook 5 章の多窓 · 多バーンレート）。
- **リクエストが少なければ鳴りません**: `slo:requests:rate1h > 0.03`（1 時間に約 100 件）。今の本番のように客が少ないと、一、二件の失敗がバジェットを使い切ったように見えます — そうした失敗は既存の `High5xxRate` とプローブ（`SiteProbeDown`）が見ます。

### 2-3. テスト

- `observability/tests/slo-rules.test.yml`（promtool、1 分間隔）: browse 10% の 5xx で速い · 遅い · 2 分以内はまだ · 0.05% はバジェット内 · 毎秒 0.01 なら 33% 失敗でも静か · 注文の半分が 1 秒を超えると遅延の速い、10% なら遅延の遅いだけ · 管理者 · actuator はどこにも入らず、`/api/search` は search だけ · search の 40% が 1 秒を超えると遅延の遅いだけ · search 8% の 5xx は search の遅いだけ（browse のバジェットは 0）· プローブ一つが失敗すると `SiteProbeDown`（3 分）と site-probe の速い · プローブ失敗割合の値 · プローブが 3 秒なら遅延の速い · コンシューマー遅延 1,500 が 10 分。
- `observability/tests/slo-budget.test.yml`（promtool、指標は 10 分間隔 15 日 · 評価はグループと同じ 5 分 — 違うとグループの評価順が揺れて警報がときどき抜けた）: 0.4% の 5xx が 15 日ならバジェット 80% — 半分だけ、0.6% なら 120% — 使い切り（半分も）、リクエストが少なければ静か。
- 実行: `SloRulesPromtoolTest`（コンテナ — 本番と同じ `prom/prometheus:v2.53.0`。Docker Hub が塞がっていれば `PROMETHEUS_IMAGE=mirror.gcr.io/prom/prometheus:v2.53.0`）。
- `SloRulesTest` — バケット `le="0.3"` · `le="1.0"` が公開されるか（既定のバケットだけではない）、SLO ごとに目標の境界をその SLO の遅延 SLI が読むか、SLO 四つの目標 · SLI · 増分 · 警報、browse が検索を外しているか、14 日の窓 < 本番 Prometheus の保持、プローブの配線（版 · ポートなし · TLS 検証 · 本番アドレス · モジュール · 30 秒）。
- 変異の確認: リクエスト下限の削除 · 境界 `le` · `or vector(0)` の削除 · 管理者経路を含める · 検索を browse に · 半分の基準を 0.9 に · バジェットの下限の削除 · プローブ失敗割合の反転 · `SiteProbeDown` の `for` の削除 → promtool テストが失敗、yml の境界 250ms · プローブにポート → `SloRulesTest` が失敗。

### 2-4. 14 日のエラーバジェットと規則

- **窓は 14 日**です。本番 Prometheus の保持が 15 日（`--storage.tsdb.retention.time=15d`）なので 30 日のバジェットは数えられません。30 日にするには保持を 31 日に上げる必要があり、TSDB がおよそ倍になります — 先に `docker system df -v` で prometheus ボリュームの大きさとディスクの余裕（docs/63 §5 のディスク事故）を見て決めます。ガードは「窓 < 保持」を守ります。
- バジェット使用率 `slo:budget_used:ratio14d{slo, budget="error"|"latency"}` =（14 日の悪いリクエスト / 全体）/（1 − 目標）。重い `sum_over_time` なのでグループ `slo-budget` は 5 分ごとに回ります。
- **規則**（警報がその規則を知らせる）:

| バジェット使用 | 警報 | すること |
|---|---|---|
| 50% 以上 | `SloErrorBudgetHalfSpent`（warning、1 時間） | 大きなデプロイ（スキーマ変更 · まとめリリース — docs/85）は原因を直すまで延期する。`slo:sli_*:ratio_rate6h` でどの日に使ったかを見て、そのときのデプロイ · 事故の記録を探す |
| 100% 以上 | `SloErrorBudgetExhausted`（critical、1 時間） | 機能のデプロイを止めて安定化の修正だけ。繰り返すなら docs/89 段階 1（データ層をノードの外へ）を前倒しする根拠 |

- 14 日平均のリクエストが毎秒 0.03 未満なら鳴りません（上と同じ理由）。

### 2-5. スループット目標

| いつ | 目標 | 根拠 · 確認 |
|---|---|---|
| docs/89 段階 2 の終わり（アプリのマルチノード） | **平時ピーク毎秒 1,000 リクエスト**を上の SLO 内で | docs/87 §0 の平時ピークの仮定。今の実習の天井（catalog Pod 三つで約 1,100）とほぼ同じなのですぐ測れる — `ceiling browse`（既定 `CEILING_TARGET_RPS=1000`） |
| docs/89 段階 3 の終わり（読みを前段へ） | **セールのピーク毎秒 5,000 リクエスト** | CDN キャッシュ · ES が読み取りを受ける前提（docs/87 §3-1） |
| その後 | 毎秒 10,000 | 段階 3 の測定を見て決める |

### 2-6. 外部プローブ（`site-probe`）

- 本番ノードの観測の上書き（`docker-compose-observability.oci.yml`）に `blackbox-exporter`（v0.25.0、ポート公開なし）、本番 Prometheus のジョブ `site-probe` が 30 秒ごとに呼ぶ（`prometheus.oci.yml` — 開発 PC の設定にはない、B-95 の比較から外した）。
- 対象は二つ — `https://www.ysyproject.com/`（モジュール `http_page`: 200 · TLS · 本文に `<div id="root">`）、`https://www.ysyproject.com/catalog-service/api/content/home`（モジュール `http_api`: 200 · `Content-Type: application/json` · 本文が JSON）。状態コードだけを見ると、Cloudflare のエラー · チャレンジ画面やフロントに漏れた API（index.html 200）も成功になる。TLS は検証する。
- 警報 `SiteProbeDown` — 対象一つが 3 分連続で失敗（critical）。バーンレート警報は `slo="site-probe"` で同じルールに乗る。
- ⚠️ **限界**: プローブは本番ノードから出る — **ノードが丸ごと落ちると Prometheus · Alertmanager · プローブが一緒に落ちて何も鳴らない。** その場合はノード外の監視（Cloudflare Health Checks · 外部の稼働監視サービスなど）が別に要る（§6）。また Cloudflare のボット対策がプローブを止めると 403 · チャレンジで失敗する — `probe_http_status_code` を見て、そうならその経路をボットのルールから外す。

---

## 3. Kafka コンシューマー遅延

- common の `KafkaConsumerMetricsAutoConfiguration` がすべてのコンシューマーファクトリーに `MicrometerConsumerListener` を付けます — ファクトリーの Bean ならそれに、リスナーコンテナファクトリーの Bean ならその中のファクトリーに（ほとんどのサービスは `new DefaultKafkaConsumerFactory` をコンテナファクトリーに直接渡します）。すでに付いていれば足しません。無効にするには `ecommerce.kafka.consumer-metrics.enabled=false`。
- 見る指標: `kafka_consumer_fetch_manager_records_lag_max`（クライアントごとにパーティション中で最大の遅延）。トピック · パーティション別は `kafka_consumer_fetch_manager_records_lag`。
- 警報 `KafkaConsumerLagHigh`: サービス · トピックごとに 1,000 件が 10 分（warning）。負荷測定中ならそれが結果です。
- テスト `KafkaConsumerMetricsTest` — コンテナファクトリーの中 · ファクトリーの Bean · すでに付いているもの（一つだけ）· 無効化。変異の確認: コンテナファクトリーの分岐の削除 · 重複確認の削除 → 失敗。

---

## 4. 天井の測定 — `ops/53 ceiling`

```bash
# 実習クラスター（本番アドレスは拒否）。先に流入制限を上げる（docs/83 §3）— でなければ天井は毎秒 20 の 429 になる
./ops/53-load-k6.sh limits load
PROM=http://<obs-vm>:9090 ./ops/53-load-k6.sh ceiling browse          # 全リクエストの p99 ≤ 300ms · スループット目標 1,000 と比べる
PROM=http://<obs-vm>:9090 CEILING_RATES=5,10,20,40 ./ops/53-load-k6.sh ceiling order   # order_create の p99 ≤ 1 秒
./ops/53-load-k6.sh limits default
```

- **階段** `CEILING_RATES`（既定 10,20,50,100,200,400 — 毎秒の反復）、階段ごとに `CEILING_RAMP`（30s）で上げて `CEILING_HOLD`（3m）維持。階段ごとにこのスクリプトを一回ずつ呼び直すので、検査 · `meta.txt` は通常の回と同じです。
- **判定**（k6 の要約）: p99 ≤ `CEILING_P99_MS` · 5xx の割合 ≤ `CEILING_MAX_5XX`（0.1% — 目標可用性 99.9% のバジェット。天井は段階 2 · 3 の大きさを決めるので目標値で測る）。browse は一覧 · 検索のリクエストも 300ms で見るので `catalog-search`（95% が 1 秒）より厳しい — 保守的な天井です。破れた階段で止まり、**その前の階段が天井**です。捨てられた反復（`dropped_iterations`）が 1% を超えたら負荷機が先に足りないので止まり、「サーバーの天井はまだ分からない」と書きます — 負荷 VM を増やします（ceiling は `SPLIT` と一緒に使いません）。
- **スループット目標**: `CEILING_TARGET_RPS`（browse · mixed の既定 1,000 — §2-5）— ceiling.md の冒頭に「超える · 届かない（目標の何 %）· まだ分からない」を書きます。
- **サーバー側**（`PROM` があれば階段の間）: サービス別のリクエスト/秒 · Pod 数（→ **Pod あたりリクエスト/秒**）· CPU 平均 · DB 接続待ちの最大 · Kafka 遅延の最大。「詰まった場所の候補」は CPU 80% 以上 · 待ち > 0 · 遅延 > 1,000。
- 結果: `load-results/ceiling-<シナリオ>-<時刻>/ceiling.md`（階段の表 + サービス別の表）、階段ごとの回のフォルダーは `steps/<階段>/`。
- テスト `LoadK6ScriptTest`（偽の k6 · 偽の Prometheus）: 200 で止まり 400 は回さない · スループット目標との比較（届かない · 超える · order はなし）· order は order_create の p99 · 5xx · 負荷機不足 · 本番アドレス · STAGES · SPLIT · 減っていく階段の拒否。変異の確認: 止まらない · 負荷機の判定の削除 → 失敗。

### 4-1. 天井の表（段階 0 の完了条件 — 測ったら埋める）

| サービス | Pod あたりリクエスト/秒（SLO 内） | 先に詰まった場所 | シナリオ · 階段 | 日付 · コミット · 環境 |
|---|---|---|---|---|
| catalog-service | — | — | browse | — |
| gateway-server | — | — | browse | — |
| order-service | — | — | order | — |
| cart-service | — | — | order | — |

参考までに、これまでの実習の回では catalog の Pod 一つが約 350 リクエスト/秒、ボトルネックは CPU（JSON シリアライズ）でした（docs/83 · docs/89 §0 #9）— そのときは SLO なしで「しきい値通過」で判定していました。実習クラスターの絶対値はコアを分け合った値なので、大きさの見積もりには比率 · ボトルネックの順序だけを使います（docs/83）。

---

## 5. 適用 · 確認

- **イメージ**: バケット · コンシューマー指標は common なので、次のデプロイのすべてのバックエンドイメージに入ります（動作の変化なし — 指標が増えるだけ。バケットは `http.server.requests` の時系列ごとに二つ）。
- **観測スタック**（本番ノード、二ファイル）: `docker compose -f docker-compose-observability.yml -f docker-compose-observability.oci.yml up -d blackbox-exporter prometheus` — プローブのコンテナが新しく立ち、Prometheus が新しいルール · ジョブを読みます。
- **確認**（Prometheus）:
  - `probe_success{job="site-probe"}` の二行が 1 — 0 なら `probe_http_status_code` で理由（403 なら Cloudflare のボット対策、§2-6）
  - デプロイ後 `count(http_server_requests_seconds_bucket{le="0.3"})` > 0
  - `slo:sli_error:ratio_rate1h` に SLO 四つ（客がいなければサービス側の遅延 SLI は空のことがある）
  - 15 分後に `slo:budget_used:ratio14d`（最初はデータが短く大きく揺れる — 14 日たまる前の半分の警報はリクエストの下限が止める）
  - `count by (service) (kafka_consumer_fetch_manager_records_lag_max)` に Kafka を使うサービス

---

## 6. 残り

1. **天井の測定** — 実習クラスターで `ceiling browse` · `ceiling order` を回して §4-1 を埋める。その表が docs/89 段階 2 · 3 · 4 の大きさを決める。
2. **ノード外の監視** — ノードが丸ごと落ちると今の警報は何も鳴らない（§2-6）。Cloudflare Health Checks や外部の稼働監視サービスを一つ置くのはアカウント · 費用の判断なので、まだしていない。
3. **バジェットの窓を 30 日に** — 保持を 31 日に上げるか（ディスクを確認した後、§2-4）。
4. **目標値に上げる** — docs/89 段階 1 · 2 を終えたら `slo:objective:availability` をコメントの目標に。
5. **ダッシュボード** — SLO のパネル（残りのバジェット · バーンレート · プローブ）は Grafana にまだない。
