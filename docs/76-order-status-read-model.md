# 76. 注文状態イベントと進行タイムラインの読み取りモデル — CQRS を注文進行の照会へ広げる

**日本語** | [한국어](76-order-status-read-model.ko.md)

> **状態(2026-10-07)** — 実装完了、本番デプロイ待ち。順序は [docs/72](72-model-refactor-deploy-runbook.md) → [docs/74](74-feature-flags.md) → これ。
> きっかけ: 「イベントソーシング・CQRS は適用されているか」という問い。答えは「CQRS は二箇所(決済サーガのプロジェクション・ES 検索)に部分的に、イベントソーシングは無い」であり、その次の一歩がこの文書である。

---

## 0. 概要

| | 以前 | 現在 |
|---|---|---|
| 注文状態の変更 | 十箇所がそれぞれ `order.setOrderStatus(...)` — DB にだけ残る | `OrderStatusChanger` 一箇所 — 状態を書き、**同じトランザクション**でアウトボックス行(`order-status`)を残す |
| イベント | 決済リクエスト(`order`)・決済レスポンス(`payment`)のみ | + `order-status`(OrderStatusChanged、Avro) — 作成・決済・配送・確定・キャンセル・返金すべて |
| 読み取りモデル | 決済サーガの状態(`order-saga-state`) | + 注文進行タイムライン(`order-timeline`) — いつ・何が変えたか、今の状態 |
| 照会 | 内部のみ(`GET /api/saga/{orderId}`、ゲートウェイのルートなし) | ゲートウェイ `/saga-projection/api/saga/{orderId}` — JwtAuthorization、**他人の注文は 404** |
| 画面 | 注文進行バー(状態ひとつ) | + 進行の記録(タイムライン) — 読み取りモデルから、無ければ静かに隠す |

**イベントソーシングは依然として違う**([docs/77](77-event-sourcing-decision.md) に理由と、やるならの設計)。真実の源は order の DB である。イベントは「変わった」という通知で、タイムラインはそれを畳み込んだ**読み取り専用**モデルである。再生して状態を作ることはなく、ずれたら入力トピックから畳み直せばよい(アプリケーションリセット)。

---

## 1. なぜこの形か

### 1-1. なぜ注文一覧全体ではなく「進行タイムライン」か

最初の候補は注文一覧の照会を丸ごと読み取りモデルへ移すことだった。見てみると理由が弱かった — `/orders/page` はすでにカーソルページングで、N+1 も直っている(OrderDataService)。商品・色・住所まで読み取りモデルに複製すればコストは大きく得るものは少ない。

一方で**「この注文がいつどうやってここまで来たか」**はどこにも無かった。状態は上書きされ、`paidCreatedAt`・`deliveredCreatedAt`・`completedAt` の三つの時刻だけが残る。キャンセル・返金・配送キャンセルで戻った履歴はログだけ。これが CQRS で得られる最も安い価値だった。

### 1-2. なぜアウトボックスか — 同じトランザクション

状態変更とイベントが別々にコミットされると、どちらか一方だけが残る瞬間が生まれる。このリポジトリには決済リクエスト用のトランザクショナルアウトボックス(`payment_outbox_event`、リレー `PaymentOutboxScheduler`)がすでにある — 同じ表に `event_type=order-status` の行として入れる。リレーの at-least-once・トレース文脈の復元(docs/51 §4e)・コミット直後のリレー(docs/56)がそのまま適用される。

### 1-3. なぜ一箇所(OrderStatusChanger)に集めたか — ガード B-78

誰かが `setOrderStatus` を直接呼ぶと DB は変わるのにイベントが無い。読み取りモデルが**静かに**ずれ、テストはすべて緑である。だから呼び出し場所をひとつに束ね、B-78 が `.setOrderStatus(`・`.markPaid(` をそのクラスの外で見つけたらビルドを赤くする。

同じ状態への「変更」はイベントではない — 決済レスポンスの再配信などの冪等経路でタイムラインは増えない。

サービスは変更器を**セッター**で受け取る(`OrderSaga.setStockHold` と同じ理由 — コンストラクタを使うテストが 13 箇所)。セッター前のデフォルトは `withoutEvents()`(状態だけ変える)なので既存のユニットテストはそのまま。Spring では `@Autowired` セッターが必須なので Bean が無ければ起動に失敗する — 本番で静かに抜けることはない。

### 1-4. なぜ別のストアか — スキーマ進化を避ける

サーガ状態(`OrderSagaState`)にフィールドを足すとそのスキーマが変わる。状態トピック・変更ログ・RocksDB に溜まった**旧フィンガープリント**のレコードをすべて読めなければならず(単一オブジェクト符号化はフィンガープリントで書き込みスキーマを探す)、その手順は docs/55 §4 の二リリースである。新しいレコード(`OrderTimeline`)・新しいストア・新しいトピックならその問題が無い。照会 API が二つを合わせる。

### 1-5. 畳み込み規則(OrderTimelineReducer)

- **同じ eventId は一度だけ。** eventId = アウトボックス行 id。リレーが再発行すれば `eventCount` だけ増える — その差が再発行の記録である。
- **順序は到着ではなく発生(`occurredAt`)。** リレーが二つのレプリカで動くと後の変更が先に届くことがある。到着順を信じると「配達完了の後に決済完了」になる。今の状態 = 最も遅く*起きた*変更。
- **上限 50。** バグでピンポンするとレコードが際限なく育つ。超えたら古いものから捨てる。

### 1-6. 所有権 — 他人の注文は 404

ゲートウェイが `User-Id` を入れ(JwtAuthorization)、saga-projection がレコードの `customerId` と照合する。違えば**本当に無いのと同じ 404** — 存在すら知らせない。customerId の分からないレコードも他人のものと見なす。`User-Id` が無ければ 401(ゲートウェイを経ていない呼び出し)。

---

## 2. 構成

```
order-service                                   saga-projection
  OrderStatusChanger.change / markPaid / created      stream(order-status) ─ map(orderId, delta)
    ├ order.setOrderStatus                              ─ groupByKey ─ aggregate(OrderTimelineReducer)
    └ outbox(event_type=order-status) ─┐                 ├ store  order-timeline-store  ← interactive query
                                       │                 └ to(order-timeline, compact)
  PaymentOutboxScheduler ──────────────┘
    └ OrderStatusKafkaPublisher → topic order-status   SagaQueryService.compose(saga, timeline, User-Id)
      (Avro 単一オブジェクト, AvroSpecificSerde, キー = orderId)  → GET /api/saga/{orderId}  (ゲートウェイ /saga-projection/**)
                                                        frontend OrderTimeline (注文詳細)
```

| 場所 | ファイル |
|---|---|
| スキーマ | `common/src/main/avro/OrderStatusChanged.avsc`・`OrderTimeline.avsc` |
| 書き込み | `order-service/.../service/OrderStatusChanger.java`、`PaymentOutboxHelper.saveOrderStatusOutbox`、`OrderStatusKafkaPublisher`、`PaymentOutboxScheduler`(分岐)、`KafkaProducerConfig`(テンプレート) |
| 呼び出し元 | OrderSaga(決済・キャンセル)・OrderDeliveryStatusService(配送・配送キャンセル)・OrderPurchaseConfirmationService(確定)・OrderDataService(作成・キャンセル・返金・銀行振込・仮想口座・期限切れ) |
| 読み取り | `saga-projection/.../topology/SagaTopology`(二つ目の分岐)・`OrderTimelineReducer`・`SagaQueryService.compose`・`OrderSagaView`・`SagaQueryController` |
| 運用 | `ops/02-create-dlt-topics.sh` — `STATE_TOPICS="order-saga-state order-timeline"` をコンパクション(B-38) |
| ゲートウェイ | ルート `saga-projection`(GET、JwtAuthorization) |
| 画面 | `frontend/src/components/order/OrderTimeline.jsx`、`api.getOrderTimeline`、i18n `order.timeline.*` |

出所(`source`)の値: `created`・`payment-saga`・`delivery`・`delivery-cancelled`・`customer`・`admin`・`bank-transfer`・`virtual-account`・`deposit-expired`・`auto-confirm`。画面は知らない値をコードのまま表示する。

---

## 3. 運用

### 3-1. デプロイ前

`ops/02-create-dlt-topics.sh` を一度実行して `order-timeline` をコンパクションで作る。作らないとプロジェクションが書いた瞬間にブローカーが delete ポリシーで自動生成し、保持期間が過ぎた後に過去の注文のタイムラインが静かに消える(既存 `order-saga-state` と同じ罠、docs/38 §1.17)。入力 `order-status` はプロデューサが自動生成してよい(通常トピック)。

### 3-2. ローリング中 — 旧リレーは新しい行を知らない

新しい order Pod が `order-status` 行を書くのに、**旧** Pod のリレーがその行を先取りすると "Unknown outbox event type" で **FAILED** にする(`PaymentOutboxScheduler` の安全装置 — 未知のタイプを STARTED のままにすると無限ループになるため)。そのイベントはトピックに出ない。窓はローリング時間(1〜2 分)で、失うのは読み取りモデルの項目であって注文状態ではない。

デプロイ後に確認し、あれば蘇らせる — リレーは at-least-once でリデューサーが eventId で弾くので、再送しても安全である:

```sql
-- order DB
SELECT count(*) FROM orders.payment_outbox_event WHERE event_type = 'order-status' AND outbox_status = 'FAILED';
UPDATE orders.payment_outbox_event SET outbox_status = 'STARTED'
 WHERE event_type = 'order-status' AND outbox_status = 'FAILED';
```

原則どおりなら docs/49 §3 のように「読む側(リレーの分岐)を先に、書く側を後に」の二リリースである。今回は失うのが読み取りモデルだけで、復旧が UPDATE 一行なので一リリースで行く。

### 3-3. デプロイ後の確認

- order のログ: `주문 상태 변경: orderId=… NOT_PROCESSED → PAID (payment-saga)`、リレー `OrderStatusChanged sent`。
- saga-projection のログ: トポロジー記述に `order-status-changes`・`fold-timeline`・`to-order-timeline`。
- `kubectl -n ecommerce exec deploy/saga-projection -- curl -sk -H 'User-Id: <customerId>' https://localhost:8190/api/saga/<orderId>` → `orderStatus`・`timeline[]`。別の `User-Id` なら 404。
- 画面: 注文詳細に「進行の記録」。
- メトリクス: `saga_projection_deserialization_failures_total{topic="order-status"}` が 0 — 増えたら §4。

### 3-4. ロールバック

イメージを戻すだけでよい(スキーマ変更なし、自動ロールバック可能)。戻した後に残る `order-status` 行は旧リレーが FAILED にする — 再度上げるときに §3-2 の UPDATE で蘇らせる。

---

## 4. 限界と次

- **Debezium 経路は無い。** `order-outbox-connector-*.json` は使われていない古いファイルである。誰かが order のアウトボックスに CDC を付けると `order-status` トピックに **JSON 文字列**が載り、プロジェクションの `AvroSpecificSerde` はそれを読めずスキップする(計数される)。そのときはハイブリッド逆直列化器(`AvroDtoDeserializer` 系)に替える必要がある。
- **注文一覧(複数注文)の照会はまだ書き込みモデルである。** ストアが注文 id キーなので顧客別の照会が無い。必要になれば顧客 id キーの二つ目のストア(直近 N 件)を足す。
- **古い注文にはタイムラインが無い** — イベント導入前の変更は記録されていない。画面はそのとき何も描かない。サーガ状態だけの応答も正常である。
- **状態トピックの保持。** コンパクションなので注文ごとに最後のタイムラインがひとつ残る。「削除した注文」にはまだ墓石(tombstone)を出していない。
- 次の候補: 通知(notification-service)が `order-status` を直接購読して「配送開始」通知を送ること — 今は order が通知を別に発行している。

---

## 5. テスト

| テスト | 何を防ぐか |
|---|---|
| `OrderStatusChangerTest`(order、8) | イベントの形・同じ状態はイベントでない・markPaid の時刻保持・作成・保存前の注文を拒否・アウトボックス行(event_type・キー・コミット後リレー) → Avro(eventId = 行 id)・スキーマに個人情報なし |
| `OrderStatusRelayTest`(order、1) | リレーの `order-status` 分岐 — トレース文脈の下で発行器へ |
| `OrderTimelineReducerTest`(saga-projection、5) | 順序・eventId 重複・遅れて届いた先に起きた変更・同時刻・上限 |
| `SagaTopologyTest` +2 | order-status → タイムラインストア・出力トピック(Avro)、サーガストアはそのまま |
| `SagaQueryServiceTest` +4 | 二つのストアの合成・**他人の注文は 404**・customerId 不明も 404・内部診断は所有権を省略 |
| `OrderTimeline.test.jsx`(frontend、6) | 記録の描画・404/503 は隠す・空の記録は隠す・stale 表示・状態が変われば再読・知らない出所 |
| **B-78** | `.setOrderStatus(`・`.markPaid(` は OrderStatusChanger の外で禁止 |
| **B-38**(更新) | ops/02 の `STATE_TOPICS` に `state`・`timeline` の両方 |

変異確認は §6 コミットメッセージに。
