# 99. 警報の基準はどう決めるか — 実務の原則と ERROR ログ警報の実測基準

**日本語** | [한국어](99-alert-thresholds.ko.md)

> 📝 **状態（2026-11-21）** — 信頼性 1 段階で Loki `ErrorLogSurge` を「5 分 30 行」で最初に入れたが、本番 Loki の 7 日分で**バックテスト**して形を変えた。
> この文書は ① 実務で警報の基準を決める原則 ② このシステムの計測 · バックテストの結果 ③ 基準の下に隠れていた常時の ERROR ④ 測り直す方法を書く。
> 関連: [51 ロギング](51-logging.md) · [91 SLO と容量](91-slo-and-capacity.md) · [62 技術事例集 §14](62-engineering-casebook.md)。

## 0. ひと目で

| | 結論 |
|---|---|
| 原則 | ユーザーの症状（SLO · 5xx）では人を起こし、原因の信号（ERROR ログ数）は warning。基準は**実データの分布**から、**平常比 + 下限**で、**バックテスト**で確かめる |
| 計測 | 本番 7 日: ERROR の 99% が eureka-server（5 分あたり ~100 行 — 自分への複製 403）· config-server（5 分あたり ~10 行 — Eureka ハートビート失敗）。他のサービスは p99 ≤ 1 行 |
| 最初の基準（5 分 30 行） | eureka のせいで **7 日間ずっと鳴りっぱなし** — 役に立たない |
| 今の基準 | 5 分 20 行超 **かつ** 直前 1 時間平均の 3 倍超、`for: 5m` — バックテストで eureka の雑音は 0、デプロイ時刻の gateway の急増（33 · 38 行）は捕まえる |
| 落とし穴 | LogQL の `and` は平常値のない（静かだった）サービスを落とす → `unless` で書く |
| 残り | 常時の ERROR 二つ（eureka · config-server）は**原因を直す** — 警報の基準で覆うことではない（§4） |

## 1. 実務で基準を決める原則

1. **症状では人を起こし、原因ではチケットを切る。** Google SRE の基本原則である。critical（呼び出し）はユーザーが感じるもの — SLO 消化率 · 5xx · 遅延 — にだけかける。ERROR ログ数は原因側の信号なので warning · チケット水準である。このシステムには SLO 消化率の警報がすでにあるので（docs/91）、`ErrorLogSurge` は warning である。
2. **基準は実データの分布から取る。** 2 〜 4 週（このシステムは Loki の保存が 7 日）の間、サービスごとに 5 分あたりの数を集め、平常の最大値 · p99 の上に余裕を置く。サービスごとに平常が違うので一つの数字をすべてのサービスに使わない — 使うなら「平常比」で使う。
3. **絶対値一つより「平常比」+「下限」。** 例: 直近 5 分が直前 1 時間平均の 3 倍を超え、同時に下限（例: 20 行）を超えるとき。下限がないと平常 0 〜 1 行のサービスの 2 行でも鳴り、平常比がないと常にうるさいサービスが常に鳴る。可能なら行数よりリクエスト比（ERROR ÷ リクエスト）の方が安定する。
4. **件数より「初めて見るエラー」の方が役に立つことが多い。** Sentry の類はエラーを種類（例外 + 位置）でまとめ、新しい種類が初めて出たときに知らせる — デプロイ直後の新しい例外一つは、見慣れたエラー数百行より重要である。Loki だけでは難しい — 規模が大きくなればエラー追跡ツールを別に置く。
5. **鳴るべきときだけ鳴るように整える。** `for`（持続時間）で一瞬の跳ねを除き、デプロイ時間はミュート。警報ごとに「鳴ったら何をするか」（ランブック）を書き、先月鳴ったもののうち**実際に対応した割合**を見る — 対応なしで流した警報は基準を直すか消す。
6. **ログレベルの衛生が先である。** ERROR は「人が何かをしなければならないこと」だけに。予想された失敗（入力エラー · 401 · 再試行で解ける一時エラー）は WARN 以下。これができていなければどんな基準も雑音である（§4）。
7. **バックテストする。** 候補ルールを過去のデータに流して「何回鳴ったか」を数えて選ぶ（§3）。Prometheus の警報は promtool の単体テスト（`observability/tests/*.test.yml`）で「鳴るときだけ鳴る」を固定する。

## 2. 計測 — 本番 Loki 7 日（2026-11-14 〜 21）

サービスごとの 5 分あたりの ERROR 行数（`sum by (service) (count_over_time({job=~"ecommerce.*", level="ERROR"} [5m]))`、5 分間隔 · 空なら 0）:

| サービス | 7 日合計 | 0 でない 5 分窓 | p95 | p99 | 最大 | 30 行を超えた窓 |
|---|---|---|---|---|---|---|
| eureka-server | 165,174 | 2,018（全部） | 100 | 110 | 110 | 2,017 |
| config-server | 20,174 | 2,018（全部） | 10 | 10 | 12 | 0 |
| user-service | 119 | 24 | 0 | 1 | 20 | 0 |
| gateway-server | 73 | 3 | 0 | 0 | 38 | 2 |
| delivery-service | 37 | 37 | 0 | 1 | 1 | 0 |
| saga-projection | 4 | 4 | 0 | 0 | 1 | 0 |
| その他（order · catalog · cart · pay · coupon · chat · notification） | 0 | 0 | | | | |

最も多い文言（数字 · アドレス · 識別子を伏せてまとめた）:

| サービス | 文言 | 備考 |
|---|---|---|
| eureka-server | `ReplicationTaskProcessor - Batch update failure with HTTP status code 403; discarding N replication tasks` — 対象 `eureka-server.ecommerce.svc.cluster.local` | 7 日の標本 2,000 行すべて。今も続く（§4） |
| config-server | `DiscoveryClient … was unable to send heartbeat! … Cannot execute request on any known server` | 7 日間ずっと。今も続く（§4） |
| user-service | `JwtAuthEntryPoint - Unauthorized error: Full authentication is required …` 67 · `Application run failed … Unable to start web server` 19 · `Servlet.service() … threw exception` 29 | 401 は正常動作なのに ERROR。起動失敗はデプロイ時刻 |
| gateway-server | `RedisRateLimiter - Error calling rate limiter lua … QueryTimeoutException` 71 | デプロイ時刻（11-20 15:50 〜 16:00）に集中 |
| delivery-service | `TrackingPoller - 추적 영구 실패: … 택배사=KOREA_POST HTTP 400` | 約 4 時間に 1 行 |
| saga-projection | `StateDirectory - Error changing permissions for the directory /app/state` | 起動時 |

## 3. 候補ルールのバックテスト

同じ 7 日に候補三つを Loki のクエリで流し（5 分間隔）、二窓連続の真（≈ `for: 5m`）を「鳴った」と数えた:

| 候補 | 式（要旨） | eureka-server | gateway-server（デプロイの急増） | 判定 |
|---|---|---|---|---|
| A | `5 分 > 30` | 2,018 窓が真 — **一度鳴って 7 日間ずっと点灯** | 2 窓 | ✗ 常時点灯の警報 |
| B | `(5 分 > 20) and (5 分 > 3 × 1 時間平均)` | 雑音は除かれた | **取り逃がす** | ✗ 静かだったサービスを捕まえられない |
| C | `(5 分 > 20) unless (5 分 ≤ 3 × 1 時間平均)` | 雑音は除かれた | 2 窓（33 · 38 行）を捕まえる | ✅ 採用 |

**B の落とし穴**: 直前 1 時間に ERROR が 0 行だったサービスには「平常」の時系列がそもそもない。`and` は対のある時系列だけを残すので、そのサービスの条件全体が消える — 平常は静かだったサービスが急に壊れる、いちばん捕まえたい場合を取り逃がす。`unless` は右側（平常比で静か）に対がなければ左側を残す → 平常値がなければ下限だけで判断する。

（eureka の 11-14 04:05 の一回はクエリ範囲の始点で「直前 1 時間」が空だったための錯覚である — 実際の運用では平常値が常にある。）

採用したルール（`observability/loki-rules/ecommerce-log-alerts.yml` の `error-rate`）:

```
(sum by (service) (count_over_time({job=~"ecommerce.*", level="ERROR"} [5m])) > 20)
unless
(sum by (service) (count_over_time({job=~"ecommerce.*", level="ERROR"} [5m]))
  <= 3 * (sum by (service) (count_over_time({job=~"ecommerce.*", level="ERROR"} [1h] offset 5m)) / 12))
for: 5m · warning
```

⚠️ **Loki のルールファイルは LogQL のエラーが一つでもあると丸ごと抜ける**（API は `groups: []` で成功を返す — critical の `ManualActionRequired` まで消える）。`LokiRulesLoadTest`（コンテナ、Loki 3.4.2）がファイルのグループ名 = 読み込まれたグループ名を確かめる。

## 4. 基準の下に隠れたもの — 常時の ERROR

「平常比」は常に壊れているものを**警報から隠す**。隠したものは別に直す — この一覧がその仕事である。

| 何 | 影響 | やること |
|---|---|---|
| eureka-server が自分自身（`eureka-server.ecommerce.svc.cluster.local`）へピア複製 → **403**（Eureka 書き込み判定 enforce） | インスタンスが一つなので複製は無意味で捨てられるだけ — 機能への影響はないように見える。ERROR の 9 割 | ✅ 原因確定 · 修正（2026-11-21、次のデプロイ）: この Pod も ConfigMap の defaultZone（自分の Service アドレス）を受け取るが `my-url` は yml の localhost — Eureka は equals で比べ自分を除外できなかった（10-26 の yml 修正は k8s では効いていない）。k8s/30 の `EUREKA_SERVER_MYURL`（= defaultZone + `/`）、試験 `PeerSelfReplicationTest`。デプロイ後この行は 0 |
| config-server の Eureka **ハートビート失敗**（`Cannot execute request on any known server`） | config-server が Eureka に登録 · 更新できない。設定を Eureka で探すクライアントがあれば影響 | ✅ 原因確定 · 修正（2026-11-21、次のデプロイ）: 実際の要求は `http://localhost:8761`（Connection refused）。yml に `defaultZone` キーがないと env の値は小文字キー `defaultzone` に別に入り、既定値が使われる（Spring の Map バインディング）— config-server は common に依存せずそのキーがなかった。yml にキー、試験 `EurekaServiceUrlBindingTest`。デプロイ後初めて証明書を出して登録する（キーストア · トラストストアは Pod にあると確認） |
| user-service の正常な 401（`JwtAuthEntryPoint`）が ERROR | 雑音 — 攻撃 · 期限切れトークンごとに ERROR | WARN（または INFO）に下げる |
| gateway の流入制限 Redis タイムアウト · user の起動失敗 | デプロイ時刻だけ | デプロイ時刻の警報はミュート · 起動失敗は再起動数ですでに見ている |
| delivery の郵便局追跡 HTTP 400 | その送り状の追跡が止まる | サービスキー · 送り状の形式を確認（docs/25）、永続失敗なら WARN + 管理画面に表示 |
| saga-projection の状態ディレクトリの権限 | 起動時に一回 | ボリュームの権限（fsGroup）を確認 |

## 5. 測り直す方法

本番ノードで（Loki はノードの compose、保存 7 日）。クエリは読むだけ:

```bash
# サービスごとの 5 分あたりの ERROR 数 — 一日ずつ（クエリの上限）
curl -s -G http://localhost:3100/loki/api/v1/query_range \
  --data-urlencode 'query=sum by (service) (count_over_time({job=~"ecommerce.*", level="ERROR"}[5m]))' \
  --data-urlencode start=<開始 ns> --data-urlencode end=<終了 ns> --data-urlencode step=300

# 候補ルールをそのまま query_range に入れると「真の 5 分窓」が出る — 二窓連続を「鳴った」と数える
# 文言の標本: query={job=~"ecommerce.*", level="ERROR", service="<サービス>"} · limit=2000 · direction=backward
```

- 数字 · アドレス · 識別子は伏せてまとめる（文書 · 会話に原文の行を貼らない — メール · IP が混ざることがある）。
- 常時の ERROR を直した後 · サービスが増えたら測り直し、下限（20）と倍数（3）をこの文書に書く。

## 6. 他の警報にも

- Prometheus の警報は promtool の単体テストで「鳴るときだけ鳴る」を固定する（`reliability-rules.test.yml` · `slo-rules.test.yml` など）。基準を変えたら、テストの入力も実際の分布に合わせる。
- 新しい警報は「鳴ったら何をするか」をコメント · description に書く。月に一度鳴った警報の一覧を見て、対応なしで流れたものは基準を直すか消す。
