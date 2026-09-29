# Saga E2E · 可観測性スタックの実地検証

**日本語** | [한국어](16-saga-e2e-and-observability-verification.ko.md)

> 🕰️ **状態（2026-09-27）— 過去の記録。** アラートの受信チャネル · チャットの保存 · バッチのメタスキーマはその後解決されました。本文末尾の「残っているもの」の一部は、同じドキュメントの §4・§7 と食い違っています。

> 作成日: 2026-07-27
> 背景: [docs/14](14-boot-test-findings.md) までは「サービスが起動する」ことまでしか確認しておらず、**Saga が実際に回るか**、
> **可観測性スタックが実際に観測しているか**、**チャット・検索が実際に動作するか** は未検証のまま残っていた。
> このドキュメントは、実際の Kafka・MongoDB・Elasticsearch・Prometheus を立ち上げて検証した記録である。
> **結果: Saga・可観測性は動作し、チャットの保存と ES 検索の経路は一度も動作したことがなかった**（B-23〜B-27）。
> 関連: [アーキテクチャ/障害シナリオ](03-architecture-and-reliability.md) · [DLQ・冪等性](12-dlq-rollout-idempotency-alerts.md)

## 0. 検証環境

| 構成要素 | 方式 | 備考 |
|---|---|---|
| Kafka 3.7.2 | KRaft シングルノード、`localhost:9092` | トピック 9 種 + `.DLT` 9 種を作成 |
| PostgreSQL 16.13 | ローカルインスタンス、DB 8 個 | サービスごとの DB 分離をそのまま再現 |
| Redis 7 | ローカル、パスワード設定あり | |
| Prometheus 2.53 / Alertmanager 0.27 / Zipkin 3.4 | ローカルバイナリ | リポジトリの実際の設定ファイルをそのまま使用 |
| MongoDB 8.0.23 | conda-forge パッケージ | ドライバー（5.2.1）の要件を満たす正規のサーバー |
| Elasticsearch 8.15.5 | Maven Central `integ-test-zip` | **クライアントと同一バージョン** |
| アプリケーション 8 種 | ローカル JVM | TLS・Eureka だけ無効化、それ以外は `local` プロファイルのまま |

> ⚠️ Docker イメージレジストリ（CDN）が検証環境のネットワーク制限でブロックされているため、コンテナではなくバイナリで立ち上げた。
> MongoDB と Elasticsearch も公式の配布元はブロックされていたが、**conda-forge** と **Maven Central の integ-test-zip**
> という迂回経路で正規のサーバーを確保した — ドライバーやクライアントのバージョンを下げずに検証したということだ。

## 1. Saga E2E — 注文 → 決済 → 在庫 ✅

`ops/06-smoke-test.sh` が「手動確認項目」として残していたフローを、実際に通した。

### 1.1 検証シナリオ
`orders.orders` に `NOT_PROCESSED` の注文（7010、商品 100 番を 4 個）を作り、`order` トピックに決済完了イベントを投入した。

```
order トピック (PaymentRequest, COMPLETED)
   └─> pay-service: pay.payment 作成 (TSID PK 869715992679896062)
        └─> pay.order_outbox_event 記録 → スケジューラーが発行 → outbox_status=COMPLETED
             └─> payment トピック
                  ├─> order-service: "Processing successful payment for order id: 7010"
                  │     └─> orders.orders.order_status : NOT_PROCESSED → **PAID**
                  ├─> product-update トピック → catalog-service: 在庫 **45 → 41**, sold **5 → 9**
                  └─> cart-empty トピック → cart-service
```

**結果: 注文の状態遷移と在庫の引き当てが正確に一致した**（注文数量 4 = 在庫減少 4 = 販売数増加 4）。
Outbox → スケジューラー → トピック → 消費 → 状態遷移という **トランザクショナル Outbox パターンの全区間が実際に動作** する。

### 1.2 冪等性（docs/12 §2）✅
同じ `eventId` で `product-update` を **さらに 3 回** 再送:

| 項目 | 結果 |
|---|---|
| 在庫 | 47 → **47**（変化なし） |
| ログ | `Duplicate product-update event ... skipping` **3 回** |
| `product.processed_event` | `order-9001` の 1 行のみ維持 |

Kafka の再配信/Saga の再実行でも **在庫が二重に引き当てられない** ことが実証された。
また、Saga が実際に作ったイベントのキーが `order-7010` として記録されており、冪等キーが **テスト用ではなく実際のプロデューサー経路に配線** されていることも確認した。

### 1.3 複数項目の注文 ✅
docs/02 で修正した「最初の項目だけ処理して return していた」バグを実際に確認した。2 項目の注文（商品 100 番を 2 個 + 101 番を 5 個）:

| 商品 | 前 | 後 |
|---|---|---|
| Saga Test Shirt | 47 | **45**（-2） |
| Saga Test Pants | 80 | **75**（-5） |

修正前のコードなら、2 番目の項目は静かに欠落していたはずだ。

### 1.4 DLQ への隔離（docs/11 §3）✅
意図せず最高の DLQ テストになった。3 種類の失敗をすべて DLT が捕捉した:

| 失敗 | 結果 |
|---|---|
| 型ヘッダーのないメッセージ | 再試行後に `product-update.DLT` へ隔離 |
| 不正な enum 値（`orderStatus: PENDING`） | `DeserializationException` がヘッダーに載って隔離 |
| 下流の障害（user-service ダウン → Feign の接続拒否） | `cart-empty.DLT` へ隔離 |

DLT レコードのヘッダーには **原因の連鎖がそのまま残る**:
```
exception-message: Listener method '...CartEmptyConsumer.receive(...)' threw exception
Caused by: BatchListenerFailedException: cart-empty
Caused by: feign.RetryableException: Connection refused executing GET https://localhost:8080/api/auth/user/email/42
```
つまり **poison message がコンシューマーを永久に停止させず**、運用者が原因をすぐに読める。

## 2. 検証中に見つけた新しいバグ 2 件

### B-21. Outbox の `created_at` はパーティションキーなのにタイムゾーンがずれていた 🔴 — 4 サービス

同じ出来事なのに、2 つのテーブルの時刻が **ちょうど 9 時間** 違っていた:
```
UTC now                  : 2026-07-27 23:03:51
pay.payment.created_at   : 2026-07-27 23:02:20     ← JPA 監査 = JVM のデフォルトクロック
pay.outbox.created_at    : 2026-07-28 08:02:20     ← ハードコードされた Asia/Seoul
```

原因は Outbox エンティティの `LocalDateTime.now(ZoneId.of("Asia/Seoul"))` のハードコードである。問題は、このカラムが単なる記録用ではなく **[docs/09](09-postgresql-partitioning-sharding.md) で作った月単位 RANGE パーティションのパーティションキー** だという点だ。

- 毎月末の **9 時間に生成されたイベントが翌月のパーティションに入る。**
- その状態で保持ポリシー（`DETACH`/`DROP`）を回すと、**まだ生きているべき行に手を出したり、消すべき行を取りこぼしたりする。**
- サービス間の時刻比較（注文 vs 決済）もずれ、障害分析が狂う。

→ 4 サービス（pay・order・cart・user）の Outbox エンティティを、他のカラムと **同じクロック**（`LocalDateTime.now()`）に統一した。
本番では JVM・DB を UTC に揃えることをドキュメントに残す。

### B-22. すべてのサービスが自分を `common` と報告していた 🔴

Zipkin にトレースは入ってくるのに、**サービス名がすべて 1 つ** だった:
```
GET /api/v2/services  →  ["common"]
```
確認してみると、メトリクスも同様だった:
```
catalog-service /actuator/prometheus  →  application="common"
order-service   /actuator/prometheus  →  application="common"
```

原因: **すべてのサービスが共有する** `common/src/main/resources/application-common.yml` が `spring.application.name: common` を宣言していた。各サービスが `application-local.yml` で自分の名前を宣言しても、この値が勝っていた。

被害範囲は広い:
- **Zipkin**: すべてのスパンが 1 つのサービスにまとまり、**分散トレーシングの存在理由がなくなる。** 今回の作業で導入したトレーシング（R-4）が事実上無力化されていた。
- **Prometheus**: `management.metrics.tags.application` がすべて `common` → このタグで掛けるクエリ・アラートはサービスを区別できない。
- **Eureka**: サービスが同じ名前で登録される危険。ゲートウェイの `lb://catalog-service` ルーティングの根幹が揺らぐ（今回のテストは Eureka を切って行ったため、直接は確認できなかった）。

→ 共有設定ファイルから `spring.application.name` を削除した。名前は各サービスが宣言する。
→ 修正後の再起動検証:
```
catalog-service /actuator/prometheus  →  application="catalog-service"
order-service   /actuator/prometheus  →  application="order-service"
Zipkin /api/v2/services →  ["catalog-service", "common", "order-service"]
```
（"common" は修正前に残ったスパン + まだ旧 jar で動いていた残りのサービス）

> このバグは **サービスを起動するだけでは見えず、コレクターを実際につないで初めて見える。**
> docs/14 の教訓（「起動した ≠ 動作する」）が、可観測性レイヤーにもそのまま当てはまった事例だ。

## 3. 可観測性スタック ✅

リポジトリの実際の設定ファイル（`observability/`）をそのまま使って検証した。

### 3.1 Prometheus — 収集
```
9/9 targets UP
  prometheus · gateway-server · user-service · catalog-service · cart-service
  order-service · chat-service · pay-service · coupon-service
```
**user-service が UP であることが特に重要だ** — docs/14 B-11 で修正したアクチュエーターの 401 問題が、実際のスクレイパーの前で再現しないことを確認したことになる。

収集されたシリーズの標本:

| クエリ | 結果 |
|---|---|
| `up` | 9 series |
| `jvm_memory_used_bytes` | 64 series |
| `http_server_requests_seconds_count` | 8 series |
| `resilience4j_circuitbreaker_state` | 6 series |

### 3.2 アラートルール — ロード · 発火 · 解消 ✅
`observability/alert-rules.yml` の 5 種がすべてロードされ、**双方向に動作** した。

1. サービスがないとき → `ServiceDown` **pending**
2. 8 種の起動後 → すべて **inactive**（解消）
3. coupon-service を強制終了 → `CircuitBreakerOpen` **firing** + `High5xxRate` pending

特に 3 番は、人為的な操作なしに **因果関係がそのまま現れた結果** だ。サービスを 1 つ落としたらゲートウェイのサーキットブレーカーが開き（今回の作業で配線した resilience4j）、その状態をアラートルールが捕捉した。**回復性の仕組みと観測の仕組みが互いを証明した。**

### 3.3 Alertmanager — 配信 ✅
```
amtool check-config observability/alertmanager.yml  →  SUCCESS
Prometheus /api/v1/alertmanagers  →  active: http://localhost:9093/api/v2/alerts
Alertmanager /api/v2/alerts       →  1 alert
Alertmanager /api/v2/alerts/groups→  receiver=default
                                     groupLabels={alertname, service}
```
ルールの発火 → ルーティング → 受信者のグループ化まで、**全区間がつながっていることを確認** した。

> ⚠️ 受信チャネルは依然としてプレースホルダーだ。実際の Slack/Webhook に置き換えなければ **誰も通知を受け取れない**（`ops/README.md` の注意事項）。

### 3.4 Zipkin — 分散トレーシング ✅（B-22 修正後）
スパンの収集・照会は正常。B-22 の修正前はすべてのスパンが `common` の 1 つだったが、修正後はサービスごとに分離された。

### 3.5 Grafana — ダッシュボードのクエリ検証（間接）⚠️
Grafana の配布物は検証環境のネットワーク制限で取得できず、**実行できなかった。** 代わりに、ダッシュボードが実際に描画されうるかを別の方法で確認した:

- `ecommerce-overview.json` は **JSON として有効**、パネル 6 個
- **パネル 6 個の PromQL クエリを稼働中の Prometheus にそのまま実行 → すべてデータを返した**

| パネルのクエリ | 結果 |
|---|---|
| `up{job="spring-actuator"}` | 8 series |
| `sum by (service) (rate(http_server_requests_seconds_count[5m]))` | 8 series |
| 5xx 比率 | 2 series |
| p99 レイテンシ `histogram_quantile(0.99, ...)` | 8 series |
| ヒープ使用率 | 8 series |
| `resilience4j_circuitbreaker_state{state="open"}` | 2 series |

つまり **ダッシュボードが参照する指標は実際に存在し、値を返す。** 未確認なのはレンダリングそのものだけだ。
（ダッシュボードが `service` ラベルでまとめていたおかげで、B-22 の `application` ラベル汚染の影響は受けなかった。）

## 4. MongoDB チャット経路 ✅（2 回目の試行でサーバーを確保）

1 回目では「サーバーを入手できない」と書いたが、**conda-forge のパッケージリポジトリが開いていたため MongoDB 8.0.23 サーバーを確保** した
（Docker レジストリ・`repo.mongodb.org`・ミラーは依然としてブロック）。ドライバーを下げずに **正規のサーバーで** 検証した。

### B-23. Mongo クライアントが TLS をハードコード 🔴
`MongoSslConfig` が `.applyToSslSettings(b -> b.enabled(true)...)` で **TLS を固定** し、証明書のロードも無条件に実行していた。
接続文字列に `?ssl=false` を与えても無視されるため、**TLS を使わない環境では chat-service をそもそも起動できない**
（ローカル開発、TLS をサイドカー/プロキシで終端するデプロイ）。Redisson の `rediss://` ハードコード（B-14）と同類だ。
→ 接続文字列の `ssl` の値に従うよう修正（未指定時は従来どおり TLS を使用 — 安全なデフォルト）。
→ **検証**: 修正後に chat-service を起動し、`/actuator/health` が `DOWN` → **`UP`** に変わった（docs/14 B-19 で指摘したその状態が解消）。

### 検証結果
| 項目 | 結果 |
|---|---|
| 起動 | `Started ChatServerApplication in 25.2s`、health **UP** |
| 会話履歴の照会 | `GET /api/chat/room/{roomId}?cursor=-1` → 3 件、**id の降順ソートが正確** |
| カーソルページネーション | `cursor=1003` → `1002, 1001` **正確** |
| Spring Data のマッピング | `_id` ↔ `id`、`createdAt`（文字列の epoch）→ long 変換は正常 |

### B-24. チャット保存経路の欠陥 3 件 🔴

問題は **読み取りではなく書き込み** にあった。

1. **保存そのものが呼ばれていない。** MongoDB に書き込むコードは `ChatService.saveMessage()` **ただ 1 箇所なのに、このメソッドを呼ぶ箇所がリポジトリ全体に存在しない。**
   実際のフローは `STOMP → sendMessage() → Kafka(chat) → MessageConsumer → WebSocket ブロードキャスト` で終わる。
   つまり **リアルタイム配信はされるが、どのメッセージも保存されない。** 会話履歴の照会 API とカーソルページネーションは、永遠に空の結果を返す。
2. **引数の順序が入れ替わっている。** `ChatMessage.get(type, nickname, email, roomId, ...)` に `(type, roomId, email, nickname, ...)` を渡している。
   保存が復活しても **roomId の位置にニックネームが入り**、`findByRoomId` では永遠に照会されない。→ 修正。
3. **`createdAt` のパースが無防備だ。** `Long.parseLong(chat.getCreatedAt())` なので、スキーマレスなストアに値の欠けたドキュメントが 1 つでもあれば
   **ルーム全体の履歴照会が 500** になる。→ 実際に再現し（ドキュメント 1 件を挿入 → HTTP 500）、パース失敗時はそのメッセージだけ 0 に落ちるよう修正。再検証の結果 **200 + 4 件を正常に返却**。

> ✅ **フォローアップ（2026-07-28）: 1 番を解決した。** ユーザーの確認を得て、**コンシューマー側**（`MessageConsumer`）で保存するよう配線した —
> コンシューマーグループのおかげでインスタンスを増やしてもメッセージごとに 1 回だけ保存され、保存の失敗は Kafka の再試行/DLQ 経路にそのまま乗る。
> 配線の過程で新たなリスクが 1 つ生じたが（再配信時の重複保存）、併せて防いだ — マッパーがプロデューサーのシーケンス id を
> ドキュメントの `_id` として載せていなかったため、MongoDB が毎回新しい ObjectId を作っていた。id を載せて `save()` を upsert にした。
> 再配信 3 回でもドキュメントが 1 件であることを統合テストで固定した（docs/17）。

## 5. Elasticsearch 検索経路 ✅（2 回目の試行でサーバーを確保）

ES サーバーも確保した — **Maven Central の `org.elasticsearch.distribution:integ-test-zip`** はライセンス変更以降のバージョンも配布され続けており、
**クライアントとまったく同じ 8.15.5** を取得して立ち上げた（`artifacts.elastic.co` はブロック）。

**この経路はこれまで一度も動作したことがなかった。** 独立した欠陥 4 件が順番に行く手を阻んでいた。

### B-25. Spring Batch のメタデータスキーマが作成されない 🔴
```
ERROR: relation "meta.batch_job_instance" does not exist
```
`initialize-schema: never` + `table-prefix: meta.BATCH_` なのに、**`meta` スキーマを作る場所がない**（コメントに「事前に実行」とだけ書かれている）。
したがって新規環境では **すべてのバッチジョブが失敗** する — ES のインデックス作成ジョブも、CSV の商品インポートも。`ops/03-enable-es-search.sh` はこのジョブを呼び出すため、ES への切り替え自体が不可能だった。
加えて、バッチのメタデータは `spring.datasource-meta`（metadb）ではなく **デフォルトのデータソース（productdb）** に記録される — 設定の意図と実際が食い違っている。
→ 今回はスキーマを手動で作成して進めた。**恒久的な修正はしていない**（どの DB がバッチメタを所有するかは設計上の決定なので §7 を参照）。

### B-26. インデックス作成ジョブを阻んでいた欠陥 2 件 🔴
1. **`es-settings.json` の構造エラー** — `@Setting(settingPath=...)` は settings の **本体** を期待するのに、ファイルは最上位でさらに `"settings"` で包んでいた。
   その結果 ES が `index.settings.analysis...` という設定を受け取り、
   `unknown setting [index.settings.analysis.tokenizer...]` で **インデックスの作成自体が失敗** していた。nori のインストール有無とは無関係の失敗だ。→ ラッパーを削除。
2. **`MultipleBagFetchException`** — リーダーの JPQL が `Product.subcategories` と `ProductSku.sizes` の 2 つの List（bag）を同時に `JOIN FETCH` しており、
   `productIndexingStep` が **毎回 FAILED**（readCount=0）だった。→ `s.sizes` のフェッチを外し、`@BatchSize(100)` でバッチロード。

**修正後**: `productIndexingStep status=COMPLETED, readCount=2, writeCount=2`、インデックスのドキュメントに SKU・サイズまで正常に含まれる。

### B-27. 検索は最初の呼び出しだけ成功し、以降はキャッシュヒットのたびに 500 🔴
```
1 回目 → キャッシュミス → 正常レスポンス + キャッシュ保存
2 回目 → キャッシュヒット → SerializationException → HTTP 500   (TTL 60 分の間ずっと)
```
`SearchResultDto` が保持する `Page<ProductDto>`（`PageImpl`）にデフォルトコンストラクターも `@JsonCreator` もなく、**Redis キャッシュから読み戻せなかった。**
→ レスポンス形式を変えないよう、`PageJacksonModule`（PageImpl 専用のデシリアライザー）を追加し、キャッシュのシリアライザーに接続した。
→ **検証**: 同じ検索語で 3 回連続呼び出し、すべて 200 + 同一結果（修正前は 2 回目から 500）。

### 最終検証 — 実際の検索に成功
```
GET /api/search?search=패딩
→ {"product":{"content":[{"name":"겨울 패딩 자켓","brand":"NorthPeak",
     "skus":[{"sku":"SKU-W-1","sizes":[{"size":"M","quantity":30,"price":99.99}]}] ...
```
PostgreSQL → バッチでのインデックス作成 → ES インデックス + alias（`product-alias`）→ `EsProductSearchService` の照会まで、**全区間が初めて通った。**

> ⚠️ **限界**: `nori`/`kuromoji` プラグインは Maven Central になくインストールできなかった。検証中は 2 つのトークナイザーを
> `standard` に一時的に置き換えて **配線（インデックス作成→クエリ→レスポンス）** を確認しており、**コミットされた `es-settings.json` は nori/kuromoji の原本のまま** だ
> （構造エラーのみ修正）。したがって **韓国語の形態素解析の品質自体は未検証** であり、プラグインがインストールされた環境での再確認が必要だ。

## 6. 最後まで検証できなかったもの — 正直な限界

| 項目 | 状態 | 理由 |
|---|---|---|
| **韓国語の形態素解析（nori）** | ❌ 未検証 | プラグインの配布元（`artifacts.elastic.co`）がブロックされ、Maven Central にもない。配線だけを `standard` トークナイザーで確認。 |
| **チャットメッセージの保存** | ❌ 動作しない | 保存経路がつながっていない（B-24 の 1 番）。設計上の決定なので修正を保留。 |
| **Grafana のレンダリング** | ⚠️ 部分的 | バイナリの配布がブロック。ダッシュボードのクエリ 6 個を実際の Prometheus で実行して代替検証（§3.5）。 |
| **Eureka の名前衝突（B-22）** | ⚠️ 未確認 | Eureka を切ってテストした。`spring.application.name` がすべて `common` だったので登録衝突の可能性は高いが、実測していない。 |
| **Spring Batch メタ DB の所有権（B-25）** | ⚠️ 未解決 | どの DB がバッチメタを持つかの決定が必要（§7）。 |

## 7. 残っている決定事項

1. ~~**チャットメッセージの保存場所**~~ → ✅ コンシューマー側（`MessageConsumer`）に決定・配線完了（§4）。
2. ~~**Spring Batch メタデータ DB**~~ → ✅ **決定・実装完了。**
   **デフォルトのデータソース（productdb）の `meta` スキーマが所有する。** JobRepository が `@Primary` データソースを使うため
   実際の動作がすでにそうなっており、設定を実際に合わせるほうが変更幅が最も小さい。Flyway もデフォルトのデータソースを
   対象に回るため、マイグレーション 1 つ（`V3__create_batch_metadata.sql`）でスキーマ作成が自動化される。
   metadb に移すには 2 つ目の Flyway 対象を導入する必要があるが、バッチメタを別の DB に置く運用上の利点はない —
   業務データと一緒にバックアップ/リストアされるほうがよい。使われていない `datasource-meta` は、混乱を防ぐためコメントで印を付けた。
   → 統合テストが、メタテーブル 6 種の存在と JobExplorer での照会を検証する。

## 8. フォローアップのまとめ（2026-07-28）

| 項目 | 結果 |
|---|---|
| チャット保存の配線 | ✅ コンシューマー側での保存 + id ベースの upsert による冪等性 |
| クーポンコンシューマーの冪等性 | ✅ `coupon.processed_event` 台帳を追加 — 再配信が偽の DLT として積もっていた問題を解消 |
| Saga の在庫引き当ての自動検証 | ✅ 実際の Kafka コンテナで 7 件（docs/17 §4.1） |
| CI | ✅ プッシュ/PR ごとに単体 + 統合 + フロントのビルド |
| nori 形態素解析 | ❌ 依然として不可 — Maven Central に `analysis-nori` がない（`analysis-kuromoji` はある）。Lucene の `lucene-analysis-nori` はあるが ES プラグインのラッパークラスは含まれておらず、プラグインを自作する必要がある。 |
| Saga 全区間の自動検証 | ✅ サービスコンテナ 3 種で自動化（docs/17 §4.3）— このドキュメントで手作業で確認したフローが、今はビルドで回る |
| Spring Batch メタ DB | ✅ productdb の `meta` スキーマに決定、Flyway マイグレーションで自動作成 |
| コンテナイメージが起動しない | ✅ シークレットファイルの必須 import を optional に変え、デフォルト値を提供（docs/17 §4.4） |

## 5. この検証で変わったこと

1. **Saga はもはや「設計上そうなるはず」ではなく「実際に回る」**。注文→決済→在庫の遷移が数値で一致した。
2. **冪等性・DLQ・複数項目の修正が実証された。** 3 つとも今回の作業で入れたコードであり、単体テストではなく実際のブローカーで確認した。
3. **可観測性は「公開」から「観測」へ移った。** 9/9 の収集、アラートの発火・解消・配信までつながった。
4. **可観測性を実際につないだら、可観測性そのものの致命的な欠陥（B-22）が出てきた。** 検証していない可観測性は可観測性ではない。
