# 91. SLO と容量測定 — 移行段階 0

**日本語** | [한국어](91-slo-and-capacity.ko.md)

> 📝 **状態（2026-11-14）— ツールは適用、数字は草案。** [docs/89](89-scale-out-migration-plan.md) 段階 0（目標と測定）のリポジトリ側の作業です: ① SLO を記録ルール · 警報に ② コンシューマー遅延の指標 ③ 天井を測る `ops/53 ceiling`。**目標値（可用性 99.9% · 300ms · 1 秒）は docs/89 §1 の例をそのまま使った草案**で、確定 · セールのピーク目標 · 実際の天井測定（実習クラスター）は残っています（§6）。

## 0. 要約

| | 何を | どこ |
|---|---|---|
| SLO 二つ | `catalog-read`（商品 · 検索 · ホームの GET）· `order-create`（注文作成の POST）— 可用性 99.9% · 遅延は 99% が 300ms / 1 秒以内 | `observability/alert-rules.yml` の `slo` グループ |
| 遅延の境界バケット | ヒストグラムに**正確な** 0.3 · 1.0 秒の境界を足した — 既定のバケットにはなく「300ms 以内の割合」を数えられなかった | `application-common.yml` の `management.metrics.distribution.slo` |
| エラーバジェット警報 | 多窓のバーンレート四つ — 速い（1h · 5m、14.4 倍、critical）· 遅い（6h · 30m、6 倍、warning）、リクエストが少なければ鳴らない | 同じ `slo` グループ · promtool テスト |
| コンシューマー遅延 | **これまで指標がなかった** — すべてのコンシューマーファクトリーに Micrometer リスナー、警報 `KafkaConsumerLagHigh` | common `KafkaConsumerMetricsAutoConfiguration` · `kafka-consumer` グループ |
| 天井の測定 | 到着率を階段で上げ、SLO が破れた階段で止まり、階段ごとにサービス別の Pod あたりリクエスト/秒 · CPU · DB 接続待ち · Kafka 遅延を表に | `./ops/53-load-k6.sh ceiling browse|order|mixed` |

---

## 1. なぜ今

docs/89 は「数字なしで次の段階を選ぶと、機材 · マネージドの大きさを当て推量で決めることになる」としました。段階 0 を始めてみると、数字を出すツールから空いていました。

- **SLO がリポジトリのどこにもなかった**（docs/86 §0）。あったのはサービス別の 5xx 5% · p99 3 秒の警報だけ — 「目標をどれだけ使っているか」ではなく「大きく壊れたか」しか見ない。
- **p99 300ms を数えられなかった。** `percentiles-histogram` の既定のバケットには 0.3 · 1.0 秒の境界がなく、`le="0.3"` の時系列がない（`SloRulesTest` が確認）—「300ms 以内の割合」は近似値（`histogram_quantile`）でしか出なかった。
- **コンシューマー遅延の指標がなかった。** Spring Boot は自分の自動設定のコンシューマーファクトリーにだけ Micrometer リスナーを付けるが、このリポジトリのサービスはすべてファクトリーを自前で作る（`KafkaConsumerConfig` · `KafkaDltConfig`）。警報ルールの冒頭も「コンシューマーラグはロードマップ」だった。docs/89 段階 0 の測定項目（「Kafka の遅延を残す」）を測れなかった。
- **`ops/53` は一度に一つの形の負荷**をかけていた — 天井を探すには、人が到着率を変えながら何度も回し、Grafana で時間帯を合わせて数字を書き写す必要があった。

---

## 2. SLO 草案

| SLO | SLI の対象 | 可用性 | 遅延 | なぜこの経路 |
|---|---|---|---|---|
| `catalog-read` | catalog-service の GET — 管理者（`admin`）· 内部（`internal`）· actuator を除く | 99.9%（30 日で約 43 分） | 99% が **300ms** 以内 | セールのピークの読み取りの大部分 · 最初の画面 · 一覧 · 検索（docs/87 §3-1） |
| `order-create` | order-service の `POST /api/order/create` | 99.9% | 99% が **1 秒**以内 | 決済の直前 — 遅いか失敗すれば離脱 |

- **エラーは 5xx だけ**です。4xx は客側（誤ったリクエスト · 在庫不足 · 他人のリソース）なのでバジェットから外します。
- **遅延はサービス側の時間**です（`http.server.requests`）。ゲートウェイ · Cloudflare · 回線の時間は入りません — 利用者の体感は `ops/53` の k6 の p99 で見ます（§4）。ゲートウェイ側の SLI は残りの作業です（§6）。
- **数字を変える場所は一か所**: `alert-rules.yml` の `slo:objective:availability` · `slo:objective:latency` の二行（SLO ごと）。警報はその値からバジェット（1 − 目標）を計算します。遅延の**境界**（300ms · 1 秒）を変えるときは三か所を一緒に — `application-common.yml` の `slo` バケット、ルールの `le`、`ops/53` の `CEILING_P99_MS` の既定値。前の二つはガード `SloRulesTest` が照合します。
- **今の本番（ノード一台）がこの目標を守れているか**は、警報ルールを載せた後 30 日間 `slo:sli_error:ratio_rate6h` · `slo:sli_slow:ratio_rate6h` で見ます。守れなければ、それが段階 1（データ層をノードの外へ）の根拠になります。

### 2-1. 記録ルール

SLO ごと、窓四つ（5m · 30m · 1h · 6h）ごとに:

| 記録名 | 意味 |
|---|---|
| `slo:sli_error:ratio_rate<窓>` | 5xx / 全体 — 5xx が一つもなければ 0（`or vector(0)`、空だと警報が「不明」になる） |
| `slo:sli_slow:ratio_rate<窓>` | 1 −（境界以内 / 全体）— 5xx も数える |
| `slo:requests:rate1h` | 1 時間平均の毎秒リクエスト — 警報の下限 |
| `slo:objective:availability` · `slo:objective:latency{threshold_seconds}` | 目標（定数） |

### 2-2. エラーバジェット警報

| 警報 | 条件（両方） | 意味 | 等級 |
|---|---|---|---|
| `SloErrorBudgetBurnFast` · `SloLatencyBudgetBurnFast` | 1h · 5m の割合 > 14.4 × バジェット | 30 日バジェットの 2% を 1 時間で — 今も続く大きな問題 | critical |
| `SloErrorBudgetBurnSlow` · `SloLatencyBudgetBurnSlow` | 6h · 30m の割合 > 6 × バジェット | 5% を 6 時間で — ゆっくり漏れる問題 | warning |

- 長い窓が「十分たくさん使った」、短い窓が「今も使っている」— 終わった事故で一時間ずっと鳴りません（Google SRE workbook 5 章の多窓 · 多バーンレート）。
- **リクエストが少なければ鳴りません**: `slo:requests:rate1h > 0.03`（1 時間に約 100 件）。今の本番のように客が少ないと、一、二件の失敗がバジェットを使い切ったように見えます — そうした失敗は既存の `High5xxRate` が見ます。

### 2-3. テスト

- `observability/tests/slo-rules.test.yml`（promtool）— 5% の 5xx で速い · 遅いが鳴る、2 分以内はまだ鳴らない、0.05% はバジェット内、毎秒 0.01 なら 33% 失敗でも静か、注文の半分が 1 秒を超えると遅延の速いが · 10% なら遅延の遅いだけ、管理者 · actuator の経路は SLI に入らない、コンシューマー遅延 1,500 が 10 分。
  実行: `SloRulesPromtoolTest`（コンテナ — 本番と同じ `prom/prometheus:v2.53.0`。Docker Hub が塞がっていれば `PROMETHEUS_IMAGE=mirror.gcr.io/prom/prometheus:v2.53.0`）。
- `SloRulesTest` — `application-common.yml` を Boot と同じフィルター（`PropertiesMeterFilter`）で束ね、Prometheus 出力に `le="0.3"` · `le="1.0"` があるか（既定のバケットだけではない）、ルールが読む `le` がすべてその中にあるか、SLO ごとに目標 · SLI · 警報がそろっているか。
- 変異の確認: リクエスト下限の削除 · 境界 `le` の変更 · `or vector(0)` の削除 · 管理者経路を含める → promtool テストが失敗、yml の境界を 250ms に → `SloRulesTest` が失敗。

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
PROM=http://<obs-vm>:9090 ./ops/53-load-k6.sh ceiling browse          # catalog-read: 全リクエストの p99 ≤ 300ms
PROM=http://<obs-vm>:9090 CEILING_RATES=5,10,20,40 ./ops/53-load-k6.sh ceiling order   # order-create: order_create の p99 ≤ 1 秒
./ops/53-load-k6.sh limits default
```

- **階段** `CEILING_RATES`（既定 10,20,50,100,200,400 — 毎秒の反復）、階段ごとに `CEILING_RAMP`（30s）で上げて `CEILING_HOLD`（3m）維持。階段ごとにこのスクリプトを一回ずつ呼び直すので、検査 · `meta.txt` は通常の回と同じです。
- **判定**（k6 の要約）: p99 ≤ `CEILING_P99_MS` · 5xx の割合 ≤ `CEILING_MAX_5XX`（0.1% — 可用性 99.9% のバジェット）。破れた階段で止まり、**その前の階段が天井**です。捨てられた反復（`dropped_iterations`）が 1% を超えたら負荷機が先に足りないので止まり、「サーバーの天井はまだ分からない」と書きます — 負荷 VM を増やします（ceiling は `SPLIT` と一緒に使いません）。
- **サーバー側**（`PROM` があれば階段の間）: サービス別のリクエスト/秒 · Pod 数（→ **Pod あたりリクエスト/秒**）· CPU 平均 · DB 接続待ちの最大 · Kafka 遅延の最大。「詰まった場所の候補」は CPU 80% 以上 · 待ち > 0 · 遅延 > 1,000。
- 結果: `load-results/ceiling-<シナリオ>-<時刻>/ceiling.md`（階段の表 + サービス別の表）、階段ごとの回のフォルダーは `steps/<階段>/`。
- テスト `LoadK6ScriptTest`（偽の k6 · 偽の Prometheus）: 200 で止まり 400 は回さない · order は order_create の p99 · 5xx · 負荷機不足 · 本番アドレス · STAGES · SPLIT · 減っていく階段の拒否。変異の確認: 止まらない · 負荷機の判定の削除 → 失敗。

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
- **ルール**: 観測スタックに載せ直す — `docker compose -f docker-compose-observability.yml -f docker-compose-observability.oci.yml up -d prometheus`（二ファイル）。
- **確認**（デプロイ後の Prometheus）:
  - `count(http_server_requests_seconds_bucket{le="0.3"})` > 0
  - `slo:sli_error:ratio_rate1h` に `catalog-read` · `order-create` の二行（客がいなければ遅延の SLI は空のことがある）
  - `count by (service) (kafka_consumer_fetch_manager_records_lag_max)` に Kafka を使うサービス

---

## 6. 残り

1. **数字の確定（判断）** — 可用性 · 遅延の目標、セールのピークの毎秒目標（docs/87 §0 の 5,000 〜 10,000 は仮定）。今の値は docs/89 §1 の例。
2. **天井の測定** — 実習クラスターで `ceiling browse` · `ceiling order` を回して §4-1 を埋める。その表が docs/89 段階 2 · 3 · 4 の大きさを決める。
3. **ゲートウェイの SLI** — 利用者の体感により近い。ゲートウェイの指標をルート単位で束ねて同じ SLO を付ける作業はまだしていない（今の SLI はサービス側）。
4. **ダッシュボード** — SLO のパネル（残りのバジェット · バーンレート）は Grafana にまだない。
