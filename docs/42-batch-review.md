# 42. バッチ点検 — 商品インポートジョブとスケジュールタスク

**日本語** | [한국어](42-batch-review.ko.md)

2026-09-15。「バッチにも問題がないか」を確認した結果である。2 種類を見た:

1. **CSV 商品インポートジョブ**（Spring Batch、9 ステップ）— 運用者が手で押す一回限りの作業
2. **スケジュールタスク 8 個**（`@Scheduled`）— 本番で動き続けるもの

結論から: スケジュールタスクは問題ない。インポートジョブには**再実行の罠**があり、直した。
残る 3 件はこの文書の最後に根拠とともに書いた。

---

## 1. 直したもの

### 1.1 再実行するとカタログが 2 セットになる

**何が起きたか。** 画像アップロード（S3 権限）でジョブが死んだ後にもう一度押したら、カテゴリが
5→10、サブカテゴリが 15→30 になり、商品で `uq_product_slug` に引っかかって止まった。運用者は
psql で直接 TRUNCATE しなければならなかった。

**なぜそうなるか。** 2 つのことが重なる。

- プロセッサーが**照会せずに新しいエンティティを作る**。`JpaItemWriter` も `merge()` の代わりに
  `persist()` で上書きしてあった（BatchConfig のコメント参照）— だから insert しかしない。
- `ProductImportService` が実行ごとに `time` パラメータを入れて**新しい JobInstance** を作る。
  Spring Batch の再開（失敗したステップから続行）が動作せず、常に 1 段階目からやり直す。

つまり**再実行はできるのに冪等ではない**、最悪の組み合わせだった。一意制約がなかったら
商品 500 個のカタログが静かに作られていただろう — カテゴリ・サブカテゴリには制約が
なかったので、実際に静かに 2 倍になった。

**修正。** カタログが空でなければ**開始しない**（409）。メッセージに次の行動を
書く。空にして入れ直すには `reset=true` — 管理画面に「既存のカタログを空にして登録し直す」
ボタンがそのときだけ現れる。

`CatalogResetService` が `TRUNCATE ... RESTART IDENTITY CASCADE` を行う。対象は CSV が
作る 9 個のテーブルだけである — `product.processed_event`（Saga の冪等性台帳）と `content.*`
（バナー・お知らせ・ホームセクション）には触れない。

### 1.2 ⚠️ インポートは識別子が 1 から始まることに依存している

上の `RESTART IDENTITY` が任意ではない理由である。そしてこれは**まだ残っている設計上の欠陥**である。

```java
// CategoryItemProcessor — id を CSV から取らない（DB が生成）
return Category.builder().categoryName(...).slug(...).build();

// ProductItemProcessor — ところが CSV に書かれた id で探す
categoryRepository.findById(item.getCategory_id())
        .orElseThrow(() -> new IllegalArgumentException("Invalid category_id: " + ...));
```

2 つの値が一致するのは**シーケンスが 1 から始まるときだけ**である。したがって:

- 行だけ消してシーケンスを残すと、次のインポートが `Invalid category_id: 1` で死ぬ。
- インポート前に `/admin/category` でカテゴリを 1 つでも作ると id がずれて失敗する。

今は `reset` がシーケンスまで戻してこの前提を守る。きちんと直すにはプロセッサーが
**CSV の id ではなく名前/スラッグで**カテゴリを探す必要がある。それは CSV スキーマと一緒に
手を入れなければならないので、今回は前提を明示して守る方向にした。

### 1.3 ES 再インデックスがリクエストスレッドを掴み、重複実行を防いでいなかった

`POST /catalog-service/api/products/sync`（管理者専用 — `AdminWriteInterceptor` が
`ROLE_ADMIN` を要求する。認可は正常である。）

- `jobLauncher.run` を**リクエストスレッドで同期的に**実行していた。Boot のデフォルトランチャーは
  `SyncTaskExecutor` なので、ジョブが終わるまで応答が出ない → ゲートウェイ 10 秒・フロント 15 秒の
  タイムアウトに先に引っかかる。管理者は失敗と見るがジョブは動き続ける → **もう一度押す。**
- ところが重複実行ガードがなかった。リーダー Bean が `@StepScope` のない**シングルトン**なので
  （`ElasticsearchBatchConfig`）、同じジョブが 2 つ動くと同じリーダーインスタンスを分け合って読む。
  読み取り位置が絡まり、インデックスが途中から空になるか `ReaderNotOpenException` が出る。

`ProductImportService` と同じ方式で直した — 即座に 202、デーモンスレッドで実行、
同時に 1 つだけ（409）。

インポートと違ってこのジョブは**再実行が安全である**: 新しいインデックス（`products-<시각>`）に書いてからエイリアスを
切り替えるので、失敗しても現在の検索はそのままである。

---

## 2. すでに正しくなっていたもの

| 確認項目 | 結果 |
|---|---|
| 起動時の自動実行 | なし。`BatchJobRunner` はファイル全体がコメントで、`spring.batch.job.enabled: false` |
| バッチメタデータの場所 | デフォルトデータソース（productdb）の `meta` スキーマ。V3 マイグレーションが作る（docs/16 §7） |
| トランザクションマネージャー | `batchTransactionManager` = データ EMF の `JpaTransactionManager`。チャンクと `JpaItemWriter` が同じトランザクション — 正常 |
| CSV・画像リソース | `resources/csv` 9 個、`resources/images` 38MB すべて jar に含まれる |
| インポートの認可 | ゲートウェイ `catalog-service-write`（POST → JwtAuthorization）+ `AdminWriteInterceptor`（ROLE_ADMIN） |
| ストレージの事前点検 | 開始前に S3 書き込みを 1 回。失敗すれば 503 で拒否し、半分だけ入ったカタログを作らない |
| 画像アップロード失敗の処理 | 握りつぶさず投げる → ステップ失敗として履歴に残る（`uploadClasspathImage`） |

### スケジュールタスク 8 個 — レプリカが 2 つでも安全か

本番で動き続ける側なので、こちらのほうが重要である。prod の HPA は `minReplicas: 2` である。

| タスク | 先取り方式 | 判定 |
|---|---|---|
| `PaymentOutboxScheduler` (order) | `FOR UPDATE SKIP LOCKED` + 発行をトランザクション内で同期的に確定 | ✅ |
| `OrderOutboxScheduler` (pay) | 同じ | ✅ |
| `TrackingPoller` (delivery) | `claimDueForPolling` — `FOR UPDATE SKIP LOCKED` | ✅ |
| `MailDispatcher` · `SmsDispatcher` (notification) | 送信台帳 + 状態遷移 | ✅（docs/29） |
| `MailDispatcher` スクラブ | 24h 経過した params を削除 — 冪等 | ✅ |
| `NotificationService.heartbeat` (user) | メモリ上の emitter マップ。ポッドごとに独立しているのが正しい | ✅ |
| `WithdrawalProcessor` (user) | ロックなし。状態チェック（`isWithdrawalPending`）でふるい落とす | ⚠️ 下記 |
| `UnverifiedAccountCleanup` (user) | ロックなし | ⚠️ 下記 |

後ろの 2 つは `FOR UPDATE SKIP LOCKED` がないので、レプリカ 2 つが同じ行を掴みうる。ただし
結果は**重複作業**であってデータ破損ではない — どちらの経路も状態を読み直して確認し
（`finalizeWithdrawal` はすでに処理済みなら false を返す）、行う作業が匿名化・削除なので
2 回やっても結果は同じである。`User` の `@Version` が競合を捕まえれば片方は例外で抜け、次の
周期に再試行する。優先度が低いので残しておいた。

### k8s CronJob

`postgres-backup`・`mongo-backup`・`restore-drill` はすべて `suspend: true` である。意図された
状態である（docs/ に書かれているとおり、動くことを確認してから有効にする）。現在バックアップは**動いていない** — PVC が
唯一のコピーだという意味である。`ops/29` で PITR を有効にするのが次の順番である。

---

## 3. 残っているもの（直していない）

### 3.1 S3 アップロードがチャンクトランザクション内にある

`ProductColorItemProcessor`・`ProductSkuItemProcessor` が `process()` 内で S3 へ同期的に
アップロードする。Spring Batch のチャンクトランザクションは read→process→write を包むので、チャンク 10 件が
東京へ上がっている間（1 件あたり数百 ms）**DB トランザクションとコネクションが掴まれ続ける。**
`connection-timeout: 5000`、プール 5 の環境で 1018 件（色 509 + SKU 509）をこうして処理する。

今は一回限りの作業で、同時に 1 つしか動かないので問題にならなかった。きちんとやるならアップロードを
別ステップに分離し、URL だけを持って DB ステップに渡す。構造変更なので先送りする。

### 3.2 フォールトトレランスがない

どのステップも `faultTolerant()`/`skip()`/`retry()` を使っていない。1 行が不正だったり S3 が
一度揺らいだりすると、ジョブ全体が失敗する。**1.1 を直す前は**その後の再試行が重複を作るので
さらに危険だった — 今は `reset` で安全にやり直せるので優先度が下がった。

画像アップロードに短い再試行（`retryLimit(2)` + `RetryableException`）を付ければ、一時的な
ネットワーク障害で全体をやり直すことが減る。

### 3.3 同時実行ガードが JVM 1 つ分でしかない

`AtomicBoolean` なので、**レプリカが 2 つあれば互いの実行を知らない。** prod の HPA は catalog-service
`minReplicas: 2` である。管理者が 2 回押して別のポッドに行くと 2 つのジョブが同時に動く — シングルトンの
リーダーを分け合って読むので結果は予測できない。

1.1 の「空でなければ拒否」が大半を防ぐ（2 つ目が 1 つ目のデータを見て拒否する）。
ただし**ほぼ同時に**押すと、両方が空のカタログを見て通過しうる。完全に防ぐには
Postgres のアドバイザリーロック（`pg_try_advisory_lock`）に引き上げる必要がある。現在の本番（local オーバーレイ、
レプリカ 1）では発生しない。

---

## 4. 回帰テスト

| 防ぐもの | テスト |
|---|---|
| カタログが埋まっているのにインポートが始まること | `ProductImportGuardTest` |
| 拒否の後に `running` フラグがロックされたまま残ること | 同じテスト（そうなるとポッド再起動以外に方法がない） |
| 初回インポート（空のカタログ）がブロックされること | 同じテスト |
| ストレージ不可の状態で開始すること | 同じテスト + `ProductImportServiceTest` |
| 同期実行・重複実行 | `ProductImportServiceTest` |

どちらもわざと壊して確認した — 空チェックを外すと失敗し、拒否経路でフラグを解除
しないと失敗する。

⚠️ フロントエンドでもう 1 つ捕まえた。`onClick={start}` はクリック**イベント**を第 1 引数として渡す。
`start(reset)` に変えるときそのままにしていたら、**通常のボタンがカタログを消していただろう。** 呼び出し側を
`() => start(false)` に直し、関数内でも `resetArg === true` に絞って二重に防いだ。
