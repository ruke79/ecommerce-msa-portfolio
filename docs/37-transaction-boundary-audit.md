# 37. トランザクション内の外部呼び出し — 全モジュール点検

**日本語** | [한국어](37-transaction-boundary-audit.ko.md)

> 🕰️ **状態（2026-09-27）— 一部古い。** ① と #5 は適用しました。`CartService.saveCart` と `TrackingPoller.poll` は残っています。

> 点検の問い: DB トランザクション中に外部 API を呼び出している箇所はあるか？ 全 Spring モジュールを対象に点検した。
>
> 答え: **ある。多い。** 12 モジュール中 9 つで見つかり、そのうちいくつかはこのリポジトリで最も長い
> トランザクションである。そして点検の過程で、**すべての Feign 呼び出しのタイムアウトが実際には適用されて
> いない**という別件の設定欠陥が出てきた — これが他のすべての発見の深刻度を変える。

この文書は**点検結果である。何も直していない。** §6 の勧告は順序まで書いたが、実行は
別の決定である。

---

## 1. 何を「外部呼び出し」と数えたか、どう確認したか

**数えた**: Feign/HTTP クライアント、`KafkaTemplate.send`（およびそれをラップした Producer/Publisher）、
PayPal API、S3、SMTP、SMS HTTP、配送業者の追跡 HTTP、Elasticsearch のインデックス登録。
**別に分類した**: Redis、MongoDB（chat-service）。往復が短く同じクラスター内なので
リスクは一段低いが、DB コネクションを握ったまま実行されるという事実は同じである。

**方法**: `@Transactional` が付いたクラス・メソッドのうち、外部呼び出しコンポーネントを注入されている 21 個の
クラスを抽出してメソッド本文をすべて読み、呼び出し経路を 1〜2 ホップ辿った。名前から推測はしなかった。
クラスレベルの `@Transactional` はすべての public メソッドに適用されるものとみなし（実際そう動作する）、
`private` メソッドの `@Transactional` は無視した（プロキシを通らないので無効である — §4 参照）。

**検証**: 下表の file:line はすべて直接開いて確認した。2 つの前提（§2）はライブラリ
jar のバイトコードと設定メタデータで確認した。

---

## 2. 点検中に覆った前提 2 つ

この 2 つを先に読まないと §3 の深刻度が理解できない。

### 2.1 Feign タイムアウトは適用されていない — 実際の値は接続 10 秒 / 読み取り 60 秒

`common/src/main/resources/application-common.yml:91-96` と各モジュールの yml にこう書かれている:

```yaml
feign:
  client:
    config:
      default:
        connectTimeout: 3000
        readTimeout: 5000
```

**このキーは読まれない。** このリポジトリは Spring Cloud 2024.0.0（`build.gradle:39`）→ OpenFeign
4.2.0 である。4.x でプレフィックスが `spring.cloud.openfeign.*` に変わり、旧プレフィックスへの
フォールバックはない。jar で確認:

```
$ strings FeignClientProperties.class | grep openfeign
spring.cloud.openfeign.client                ← @ConfigurationProperties の prefix

$ spring-configuration-metadata.json で 'feign.' から始まるプロパティ数: 0
```

`Request.Options` Bean もどこにもない。すると Feign のデフォルト値が適用される。feign-core 13.5 の
`Request$Options()` コンストラクタのバイトコード:

```
1: ldc2_w  long 10l  ; TimeUnit.SECONDS   ← connectTimeout
7: ldc2_w  long 60l  ; TimeUnit.SECONDS   ← readTimeout
```

つまり下表のすべての Feign 項目は「最大 5 秒」ではなく **「最大 60 秒間 DB コネクションを
握る」** と読まなければならない。プールサイズは全サービスで 5 である（`maximum-pool-size: ${DB_POOL_SIZE:5}`）。
相手サービスが止まれば **60 秒 × 同時リクエスト 5 件 = プール枯渇**であり、その後すべてのリクエストは
`connection-timeout: 5000` を待って失敗する。1 つのサービスの障害が隣の DB プールへ
広がる正確な経路である。

この欠陥はトランザクションとは無関係に**それ自体で直すべきである。** 1 行だ（§6 ①）。

> **適用済み。** `application-common.yml` の `spring.cloud.openfeign.client.config.default` に移し、
> モジュールごとの死んだブロックは削除した（全サービスが common を import するので定義箇所は 1 つ）。
> `ConfigurationGuardrailTest` B-37 が旧プレフィックスの復活を防ぎ、cart-service の
> `FeignTimeoutBindingTest` が ① 実際の yml が `FeignClientProperties` にバインドされるか、
> ② その値で 8 秒かかるサーバーを呼ぶと 5 秒で `Read timed out` が出るか、を固定する。
> だから下表の Feign 項目は今は「最大 5 秒」と読めばよい — ただし N 倍の増幅はそのままである。

### 2.2 Kafka プロデューサートランザクションは 4 モジュールで有効 — 半分の保護

先に「プロデューサートランザクションがない」と言ったのは誤りだった。`transactionIdPrefix` ではなく
`ProducerConfig.TRANSACTIONAL_ID_CONFIG` で有効になっている:

| モジュール | 位置 |
| --- | --- |
| user-service | `message/config/KafkaProducerConfig.java:82` |
| coupon-service | `config/KafkaProducerConfig.java:51` |
| order-service | `config/KafkaProducerConfig.java:55,79,103,126,150,174`（6 個すべて） |
| pay-service | `message/config/KafkaProducerConfig.java:59` |
| delivery-service · chat-service | **なし**（通常のプロデューサー） |

`KafkaTransactionManager` はどこにもないが、トランザクショナルなプロデューサーファクトリーはアクティブな JPA
トランザクションを検知すると Kafka トランザクションを開き、**JPA コミット時点で一緒にコミット**する。そして
すべてのコンシューマーが `isolation.level=read_committed` である（cart/catalog/coupon/delivery の
`KafkaConsumerConfig`）。

**そのため 4 モジュールでは**「DB はロールバックされたのにメッセージは出ていった」という問題が**防がれる。**
原子性（2PC）ではなく 2 つの独立したコミットなので「DB コミット成功 → Kafka コミット失敗」のウィンドウは残るが、
最もよくある事故は防がれている。これは評価すべき点である。

**防がれないもの**: DB コネクションを握ったままブローカーと通信するという事実。むしろコミット経路に
Kafka コミットの往復が**追加**される。そして delivery/chat にはこの保護がない。

---

## 3. 発見一覧

深刻度の基準: **🔴** ユーザーリクエスト経路 + トランザクション長が外部遅延に正比例 + N 倍増幅 /
**🟠** そのうち 1 つ / **🟡** 短い、頻度が低い、または readOnly / **⚪** Redis・Mongo。

### 🔴 最も急ぐべき 6 件

| # | モジュール | トランザクションメソッド | 外部呼び出し | なぜ急ぐか |
| --- | --- | --- | --- | --- |
| 1 | order | `OrderService` クラスレベル `@Transactional`（`OrderService.java:55`）配下の `getOrders`（`:90`）→ `OrderDataService.getOrders`（`:113`） | `convertToOrderDto`（`OrderService.java:128`）が **注文 N 件 × 商品 M 件** 分だけ `catalogServiceClient.getColorInfo` | 注文履歴の照会 1 回で Feign 数十〜数百回を**読み書き**トランザクション内で直列実行。`getOrders` にはメソッドアノテーションがなく `readOnly` ですらない。ページネーション版（`:126`、`:133`）もページサイズ分だけ同じ構造 |
| 2 | cart | `CartService.saveCart`（`CartService.java:197`） | `fetchUserByEmail`（`:336` Feign）→ `deleteCartByUserId` **DELETE** → 商品ごとに `createCartProduct`（`:488` Feign） | カートの DELETE ロックを握ったまま**商品数だけ Feign**。商品 10 個 = 最悪 10 分（60 秒×10）。チェックアウト直前の経路なので、ユーザーはまさにここで待つ |
| 3 | user | `ReviewService` クラスレベル（`:30`）配下の `addReview`（`:140`） | `imageService.upload`（`:170`、`:237` → S3 `putObject`）画像 N 枚 + `catalogServiceClient.updateRating`（`:191`、`:251`） | レビュー行の保存直後に S3 アップロード N 回 + Feign。S3 には**タイムアウト設定がない**。アップロード成功後にロールバックされると S3 に孤児オブジェクト、Feign 成功後にロールバックされると評価だけ反映された不整合 |
| 4 | delivery | `TrackingPoller.poll`（`TrackingPoller.java:60`） | `claimDueForPolling`（SKIP LOCKED）→ 配送ごとに `adapter.track`（`KoreaPostCarrierAdapter.java:133` HTTP、1 件あたり最大 10 秒）→ `publisher.publish` | **このリポジトリで最も長いトランザクション。** バッチ 20 件 × 10 秒 = 最悪 200 秒間、20 行の排他ロック + コネクション 1 つ。先取りトランザクションと HTTP ループが分離されていない |
| 5 | order | ~~`OrderDataService.createOrder`~~ | ~~`cartServiceClient.getCart`~~ | ✅ **解消。** リモート呼び出し（カート + クーポン見積もり）を `OrderService.createOrder` に上げ、そのメソッドを `NOT_SUPPORTED` にした。`OrderDataService` からは Feign クライアントのフィールドを**削除**して二度と呼べないようにし、ガードテストがその「ないこと」を守る（`OrderTransactionBoundaryTest`） |
| 6 | notification | `VerificationService.issue`（`:90`） | `verificationRepository.save`（`:123`）直後に `smsSender.send`（`:126` → HTTP） | ユーザー対面の同期リクエスト。トランザクション長 = SMS ゲートウェイの応答時間。コミット失敗時は**コードは SMS で届いたのに DB にない** — ユーザーは受け取ったコードで認証できない |

### 🟠 構造は同じで増幅が少ないもの

| # | モジュール | トランザクションメソッド | 外部呼び出し | 備考 |
| --- | --- | --- | --- | --- |
| 7 | cart | `loadCart`（`:169`、readOnly） | 商品ごとに Feign 2 回（`:410`、`:419`）+ `fetchUserByEmail` | 照会 API なので呼び出し頻度が高い。readOnly なのでロックはないがコネクションは掴む |
| 8 | cart | `getCart`（`:71`、readOnly）のキャッシュミス経路 | `getColorInfo`（`:371`）を商品数だけ | キャッシュヒットなら早期リターン |
| 9 | cart | `updateCart`（`:153`） | `computeProductPrices`（`:160` → `:391` Feign） | **DB アクセスがまったくない純粋な計算**なのに、クラスレベルのアノテーションのせいでコネクションを掴む。純粋な無駄 |
| 10 | user | `ReviewService.deleteReview`（`:45`） | `deleteById` 直後に `updateRating`（`:74`、`:76`） | #3 の削除版 |
| 11 | user | `UserServiceImpl.generatePasswordResetToken`（`:240`、クラスレベル `:61`） | `emailService.sendPasswordResetEmail` → `OutgoingMailProducer.publish` → **`kafkaTemplate.send(...).get()`**（`:90` ブロッキング） | トークン保存直後にブローカーの応答を**待つ。** タイムアウトはある（`MAX_BLOCK_MS=5000`、`DELIVERY_TIMEOUT_MS=10000`）— 最大 10 秒 |
| 12 | order | `OrderDataService.persisitPaypalPayment`（`:247`） | `paypalOrderVerifier.verify`（`:258` → PayPal GET、トークン更新時は POST が追加） | PayPal は**タイムアウトがきちんとかかっている**（`PaypalConfig.java:50-59`、3s/5s）。最悪約 16 秒。決済検証（docs/35）なのでトランザクションの外に出すのも微妙 — §6 参照 |
| 13 | order | `OrderSaga.process`（`:71`）、`rollback`（`:191`） | `ProductUpdateKafkaPublisher:34`、`CartEmptyKafkaPublisher:36`、`CartRollbackKafkaPublisher:35`、`CouponRollbackKafkaPublisher:31` — すべて `send`（ノンブロッキング） | §2.2 の保護を受ける。残る問題: 発行失敗時に `whenComplete` コールバックが**ログを残すだけで Saga は静かに途切れる**（`ProductUpdateKafkaPublisher.java:41-43`、他のパブリッシャーも同じ形） |
| 14 | coupon | `CouponService.applyCoupon`（`:44`） | 最初の文が `userServiceClient.findUserByEmail`（`:48`）、最後に `couponUseProducer.publish`（`:65` → `send`） | このメソッドには **DB 書き込みがない**（照会のみ）。トランザクション自体が不要。しかもクーポン使用の事実を DB に残さずメッセージだけ送り、`KafkaException` を**空の catch で握りつぶす**（`CouponUseProducer.java:42-44`） |
| 15 | delivery | `DeliveryService.createFromOrder`（`:55`）、`registerTracking`（`:104`）、`cancel`（`:180`） | `saveAndFlush` 直後に `publisher.publish`（`DeliveryStatusPublisher.java:56` `send`） | delivery には**プロデューサートランザクションがない**（§2.2）。コミット前に出たメッセージはロールバックされても残る。クラスコメント（`:30`）が Outbox が定石であることを自ら明かしている |
| 16 | catalog | `ProductService.addProduct`（`:314`）、`load`（`:237`） | `createSku`/`loadSku` 内で `imageService.upload`（S3）画像 N 枚 | #3 と同じ構造。`addProduct` には補償削除があるが（`:684-689`）、それもトランザクション内で S3 DELETE N 回を追加する。`load` は補償なし |
| 17 | catalog | `addProduct`、`updateProduct`（`:454`）、`updateStatus`（`:491`） | `ProductIndexService` → ES `save`/`deleteById` | `search.engine=es` のときのみ。コミット前のインデックス登録なので、ロールバック時に ES に幽霊ドキュメント |
| 18 | catalog | Spring Batch のチャンクトランザクション（`BatchConfig.java:267`、`:277`） | `ProductColorItemProcessor:100`、`ProductSkuItemProcessor:45` の S3 アップロード | ItemProcessor はチャンクトランザクション内で動く。チャンク 10 × 画像数 |
| 19 | notification | `MailDispatcher.dispatch`（`:73`）、`SmsDispatcher.dispatch`（`:61`） | 先取り → `sendOne`（SMTP / SMS HTTP、各 5 秒）→ SENT 記録が **1 つのトランザクション** | **意図された設計である**（クラスコメント `:35-37`「同期送信 — トランザクション内で結果を確定する」）。ロックが状態確定前に外れるウィンドウを防ぐための選択。代償: バッチ 50 × 5 秒 = 最悪 250 秒。最初の一時的失敗でバッチを打ち切って（`:91`）上限を管理する。そして SMS はタイムアウト時に「受付の有無が不明」なので、**コミット失敗 = 料金のかかった重複送信** |

### 🟡 低 — 意図されたもの、または短いもの

| # | モジュール | 何か | 判定 |
| --- | --- | --- | --- |
| 20 | order · pay | `PaymentOutboxScheduler.processOutboxMessage`（`:57`）、`OrderOutboxScheduler.processOutboxMessage`（`:45`）— 先取り後に `send(...).get(5000ms)` **ブロッキング** | **設計されたトレードオフ。** ロックを状態確定まで保持するためのもので、コードコメント（`PaymentOutboxScheduler.java:22-41`）が理由を明記している。タイムアウト・バッチサイズ・初回失敗時の中断で上限が管理されている。触らないこと |
| 21 | chat | `ChatService.sendMessage`（`:124`）、`sendEnterMessage`（`:170`）、`sendLeaveMessage`（`:185`） | Kafka `send`（ノンブロッキング）。ところがこれらのメソッドには **JPA 書き込みがない** — クラスレベルの `@Transactional`（`:55`）が JPA トランザクションを開き、その中で Mongo・Kafka だけを行う。Mongo は JPA トランザクションに参加しない（MongoTransactionManager なし）。トランザクションが何も保護しないのにコネクションだけ掴む |
| 22 | user | `UserServiceImpl.registerConfirmed`（`:531`）→ `userCreatedProducer.publish`（`:552` `send`） | §2.2 の保護あり。Outbox のコードはコメントアウトされている（`:549-550`） |

### ⚪ Redis · Mongo — 短いが同じ原理

- `OrderService`（クラスレベル tx）配下で `OrderCacheService` の `@Cacheable`/`@CacheEvict` が動作する — Redis の往復がトランザクション内。`allEntries=true` の大量 EVICT（`:71`、`:116`）を含む。`spring.data.redis.timeout` 未設定（Lettuce のデフォルトは無制限）。
- `ProductService` も同じ構造だが、キャッシュアドバイザーとトランザクションアドバイザーの**順序が指定されていない**ため（`RedisCacheConfig.java:39` の `@EnableCaching` のみ）、どちらが外側か保証されない。
- `ChatService.createChatRoom`（`:142`）— JPA トランザクション内で Redisson `tryLock` **最大 10 秒待機**（`:148`）+ ネストしたロック。
- `VerificationService.issue` — Redis 4 回（`:104`、`:110`、`:112`、`:135`）。クールダウンの `set` がコミット前なので、ロールバックされてもクールダウンは残る。
- `CartService.getCart` — キャッシュヒットでもトランザクションが先に開かれ、コネクションを掴んでは離す（`:74`）。

---

## 4. 表にない観察 — なぜこうなったか

**クラスレベルの `@Transactional` が原因の半分である。** `OrderService:55`、`CartService:39`、
`ReviewService:30`、`UserServiceImpl:61`、`ChatService:55`、`ProductService:79`。クラスに
付けると「DB に触れるか」と無関係にすべての public メソッドがトランザクションになり、後から追加された
Feign 呼び出し・純粋な計算（#9）・Kafka 専用メソッド（#21）がすべてその中に入る。メソッドに
付けていれば #9、#14、#21 はそもそも生じなかった。

**`private @Transactional` はすべて無効である。** `CartService.java:334, 342, 353`、
`OrderSaga.java:45, 58, 97, 127, 145, 154`、`CouponService.java:242, 250`。自己呼び出しは
プロキシを通らない。問題は無効であることより、**分離されていると誤解させる**ことである —
`OrderSaga` の private メソッドは別トランザクションのように見えるが、すべて `process` のトランザクション
内である。

**Outbox は order・pay でしか生きていない。** user-service の `OutboxScheduler` は実装のない
空のインターフェース、cart-service の `outbox/*` はどこからも参照されていない。delivery は
テーブル自体がない。

**`REQUIRES_NEW` · `@Transactional(timeout=)` はリポジトリ全体で 0 件である。** トランザクション長に
上限を設ける仕組みがない。

**タイムアウトがきちんとかかったクライアントとそうでないものに分かれる。** PayPal（`PaypalConfig`）、
配送業者 HTTP（`KoreaPostCarrierAdapter:112, 136`）、SMS（`HttpSmsSender:64-66`）、
SMTP（yml `:65-67`）、Kafka ブロッキング発行（`OutgoingMailProducer:69-71`）はかかっている。
**Feign と S3 はない。** Feign は §2.1 の理由で、S3 は `AmazonS3Config` がデフォルト値のままだから。

---

## 5. すでに正しくなっているもの — そのままにすべきもの、そしてお手本

このリポジトリに正解がないわけではない。同じリポジトリ内に模範例があり、残りをそちらに
合わせればよい。

| パターン | どこ | 何が正しいか |
| --- | --- | --- |
| **コミット後のイベントリスナー** ⚠️ **訂正** | `RegistrationListener.java:71-73` — `@Async` + `@TransactionalEventListener(AFTER_COMMIT)`。**コードの形はお手本だが、実際には呼ばれない** — イベントを発行する `AuthController.java:196` がトランザクションの外なので、`fallbackExecution=false` のデフォルト値によってリスナーがスキップされる（docs/38 §1.4）。引用するときはこの事実を併せて見ること | 登録トランザクションが閉じた**後に**トークン生成（新しい tx）→ Kafka 発行。このリポジトリで最も模範的な箇所 |
| **afterCommit 同期** | `CartService.java:135-143, 221-229, 250-259, 280-288, 311-320` | Redis 更新とユーザー照会 Feign をコミット後に。ただし、コネクションの返却は `afterCompletion` なので、その間の Feign（最大 60 秒）の間もコネクションはまだスレッドに縛られている — コミット前よりはるかにましだが、完全に自由ではない |
| **Outbox の定石** | `OrderDataService.java:317` → `PaymentOutboxHelper` / `PaymentService.java:57,69` → `OrderOutboxHelper` | トランザクション内ではテーブルにだけ書き、発行はスケジューラーが行う。pay-service にはインライン発行を取り除いた痕跡がコメントで残っている（`OrderOutboxHelper.java:77`） |
| **コンシューマーはキューに積むだけ** | `notification-service` の `OutgoingMailListener:29-31`、`NotificationEventListener` | 「ここではメールを送らない」— リスナーは行を入れるだけで、送信はディスパッチャーが行う |
| **先取り `FOR UPDATE SKIP LOCKED`** | `MailMessageRepository:38`、`NotificationRepository:36-44`、`ShipmentRepository:33`、`PaymentOutboxHelper:49-58` | レプリカによる重複処理の防止 |
| **外部の失敗がドメインの保存を失敗させないように** | `ProductIndexService:50-53`、`DeliveryStatusPublisher:60-63`、`ImageService:105-108` | 例外の隔離 |
| **外部呼び出しをオフにできるスイッチ** | `ProductIndexService:41-42`（デフォルト OFF）、`MailConfig:28`（デフォルト logging） | デフォルト値が安全側 |

---

## 6. 勧告 — 順番に、まだ実施していない

### ① Feign プレフィックス 1 行 — 何よりも先に ✅ 適用済み

```yaml
# application-common.yml および各モジュールの yml
spring:
  cloud:
    openfeign:
      client:
        config:
          default:
            connectTimeout: 3000
            readTimeout: 5000
```

これ 1 つで §3 の Feign 項目すべてが「最大 60 秒」から「最大 5 秒」に下がる。トランザクション構造に
手を付ける前にこれを先にやれば、最悪値が 12 分の 1 になる。**適用後に実際に効いているか確認すること** —
設定を変えたと信じていたのに読まれていなかったのが今の状態である。確認方法: 相手サービスを落として
呼び出し、5 秒で `Read timed out` が出るかを見る。

### ② 構造を変える 6 件（🔴）

| # | 何を | どうやって |
| --- | --- | --- |
| 1 | `getOrders` N×M | トランザクション内では注文・商品エンティティだけを読んでコミットし、**トランザクションの外で**色情報をバッチ照会（`getColorInfo` を id リストで受け取るエンドポイントを 1 つ追加）して DTO を組み立てる。`OrderService` のクラスレベルアノテーションをメソッドへ下ろすことと併せて |
| 2 | `saveCart` | Feign を**先にすべて**終わらせ（トランザクションの外）、その結果でトランザクションを短く開く: 照会 → 計算 → `@Transactional` 保存。`fetchUserByEmail` の無効な `@Transactional` を削除 |
| 3 | `ReviewService` | S3 アップロードをトランザクションの**前**に（失敗したらレビューを保存しない）、`updateRating` を `@TransactionalEventListener(AFTER_COMMIT)` に — `RegistrationListener` と同じ形。ロールバック時のアップロード済みオブジェクトの削除は補償で |
| 4 | `TrackingPoller` | **先取りトランザクションとポーリングを分離**: ① 短い tx で `claimDueForPolling` + `next_poll_at` を未来にずらし、ロックの代わりに予約で所有権を示す → コミット ② トランザクションの外で HTTP N 回 ③ 1 件ずつ短い tx で結果を反映。`MailDispatcher` が懸念した「ロックが状態確定前に外れるウィンドウ」と同じ問題はあるが、ポーリングは冪等なので（同じ追跡番号を 2 回照会しても無害）そのウィンドウは許容してよい |
| 5 | `createOrder` | ✅ **適用した。** ただし勧告文の「`OrderService.createOrder` がすでにその位置である」は半分しか正しくなかった — 位置は正しいが `OrderService` に**クラスレベルの `@Transactional`** が付いているため、そこでもすでにトランザクションが開かれる。そこでそのメソッドに `@Transactional(propagation = NOT_SUPPORTED)` を明示して無効にした。カートと金額を引数で受け取るよう `OrderCacheService`・`OrderDataService` のシグネチャも変えた —「このメソッドはリモート呼び出しをしない」ことがシグネチャに現れる |
| 6 | `VerificationService.issue` | 保存 → コミット → SMS 送信 → 失敗時は別 tx で行を削除（または状態 FAILED）。コミット失敗時の「コードは届いたのに DB にない」が消える。代わりに「DB にはあるが SMS 失敗」が生じるが、それはユーザーが再リクエストすれば解決する — 方向の正しい失敗である |

### ③ Outbox を生かすべき場所

delivery-service（#15）はプロデューサートランザクションもなく、最も露出している。order/pay の
`PaymentOutboxHelper` + スケジューラーをそのまま移せばよい。user/cart の死んだ Outbox コードは
生かすか消すか — 今のように「あるように見える」状態が最も悪い。

### ④ 触らないこと

- **#20 Outbox スケジューラーのブロッキング発行** — 意図された設計。上限が管理されている。
- **#19 `MailDispatcher`/`SmsDispatcher`** — 意図された設計だが、トレードオフを見直す価値はある。SMS の「タイムアウト = 受付不明 = コミット失敗時の重複料金」は実際のお金である。先取り tx を短く切り、送信後に別 tx で確定すればそのウィンドウは消える代わりに「確定失敗時に次の周期で再送」のウィンドウが生じる。**冪等キーがゲートウェイ側にあれば**後者のほうがよい — SMS ゲートウェイがクライアント参照 ID で重複を弾いてくれるか確認してから決めること。
- **#12 PayPal 検証** — 決済偽造の防御（docs/35）がトランザクション内にあってこそ「検証通過 → 保存」の間のウィンドウがない。タイムアウトがきちんとかかっているので、最悪 16 秒を許容するのが正しい。代わりに `@Transactional(timeout = 20)` で上限だけ明示すること。

### ⑤ 予防 — 再発させないために

- クラスレベルの `@Transactional` をメソッドレベルに下ろす（§4）。一度きりの整理だが、以後のすべての
  追加が安全になる。
- `private`/自己呼び出しの `@Transactional` を削除する。無効なうえに誤解を生む。
- 外部呼び出しコンポーネントを `@Transactional` クラスに注入することを ArchUnit ルールで防ぐ
  方法がある — このリポジトリはすでに構造上の事実をビルドで固定する方式を使っている（docs/19 の
  SQL count ガード）。同じ場所にもう 1 つ置けばよい。

---

## 7. 確認できなかったこと

- **実際のトランザクション長の分布。** この文書の「最悪 N 秒」はタイムアウトの算術であって測定ではない。
  `spring.jpa.properties.hibernate.session.events.log.LOG_QUERIES_SLOWER_THAN_MS` や
  HikariCP の `leak-detection-threshold`（例: 10000ms）を有効にすれば、本番で実際に長いトランザクションが
  ログに捕まる。直す前にまずそれを有効にして、§3 の順序が実際の頻度と合っているかを見るのが正しい。
- **Feign タイムアウト未適用の実証。** バイトコードとメタデータで確認したが、実際に相手サービスを
  落として 60 秒待ってみたわけではない。§6 ① の適用前後で一度再現してみること。
- **§2.2 の Kafka トランザクション同期が実際に効くか。** トランザクショナルファクトリー + アクティブな JPA tx という条件は
  揃っており Spring Kafka の文書化された動作だが、このリポジトリでロールバック時にメッセージが出ない
  ことをテストで固定してはいない。固定する価値がある — この保護に頼っているのだから。
