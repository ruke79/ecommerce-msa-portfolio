# 53. 品切れと再入荷 — 運用者には品切れアラート、お客様には再入荷通知

**日本語** | [한국어](53-restock-and-sold-out.ko.md)

## 1. 誰に何を知らせるか

| 出来事 | 受け取る人 | 経路 | 理由 |
|---|---|---|---|
| **品切れ**（在庫あり → 0） | 運用者 | ログ `품절 발생` → Loki アラート `ProductSoldOut`（warning）→ Alertmanager Webhook | 在庫を補充するのは運用者である。お客様には商品画面の「品切れ」で十分だ |
| **再入荷**（0 → あり） | そのオプションに **申し込んだ** お客様 | `product-stock`（RESTOCKED）→ notification-service → `RESTOCK_AVAILABLE` 通知（アプリ・メール） | お客様が頼んだ知らせである。申し込んでいないお客様にとってはノイズだ |

在庫の復元そのもの（返金・返品の入庫）はお客様に知らせない — 店の内部事情である。お客様にとって大事なのは「お金が
戻ってきた」であり、それは返金通知（docs/49 §10、部分返金を含む）が担う。

## 2. catalog-service — 0 をまたぐ瞬間だけ

在庫が変わる場所は1か所だ: `ProductService.updateProductSize_Qty`（注文による減算・返金/返品による復元）。ここに
管理者による在庫調整（`setStock`、§4）が加わった。どちらも変更前・後の数量を `StockEventProducer.onQuantityChanged`
に渡す。

- **0 をまたぐときだけ** 何かをする（`transitionOf`）: あり → 0 は `SOLD_OUT`、0 → あり は `RESTOCKED`。5 → 3 のような
  普通の変化は静かだ — 注文ごとにイベントが出ればノイズである。
- **コミットの後に** 記録して送る。減算がロールバックされたのに「品切れ」アラートや「再入荷」通知が出たら嘘になる。必要な値
  （商品 id・名前・slug、色 id・名前、サイズ）はコミット前に読んでおく。
- 品切れは `log.warn("품절 발생: productId=… colorId=… size=… name=…")`。この文言はアラートルールとの **契約** である —
  `StockEventProducer.SOLD_OUT_MARKER` とルールファイルの `SOLD_OUT_MARKER` を B-61 が照合する。
- イベントは `product-stock` トピック（キー = 商品 id — 同じ商品の品切れ・再入荷が逆順に届かないように）:

```json
{"type":"RESTOCKED","productId":10,"slug":"tee","name":"티셔츠","colorId":3,"color":"black",
 "size":"M","quantity":12,"occurredAt":"…","eventId":"restocked:10:3:M:<ms>"}
```

## 3. notification-service — 申し込みと送信

**申し込み** — `notification.restock_subscription`（V10）。`(user_id, product_id, color_id, size)` UNIQUE。色のない
商品は `color_id = 0` — null にすると UNIQUE が同じ申し込み2つを防げない。1人のお客様がかけておける申し込みは
100個（`MAX_ACTIVE_PER_USER`）。

| API (`/api/notifications/restock-subscriptions`) | |
|---|---|
| `GET ?productId=` | この商品にかけておいたオプション `[{colorId, size}]` — 商品画面のボタンの状態 |
| `POST {productId, colorId, size, productName, productSlug}` | 申し込み。新規なら 201、既にあれば 200、上限なら 429 |
| `DELETE ?productId=&colorId=&size=` | 取り下げ。存在しないか、すでに通知済みの申し込みなら 404 |

身元はゲートウェイがトークンから取り出した `User-Id` だけである — 本文でユーザーの値を受け取らない。

**送信** — `RestockEventListener` が `product-stock` を購読する。`RESTOCKED` なら、そのオプションのまだ通知していない申し込みを
500個ずつ（`FANOUT_PAGE`、ページごとにトランザクションは別）読んで通知を作り、`notified_at` を記録する。`SOLD_OUT` では
何もしない（§1）。

- 通知: `RESTOCK_AVAILABLE` "재입고 알림" — "티셔츠 (black / M) 이(가) 다시 입고되었습니다. 품절되기 전에 확인해
  보세요." リンクは `product/<slug>`。アプリ・メールで、SMS ではない（急ぎではなく、見逃しても損がない）。
- キーは `restock:<申し込み id>` — 再配信されても1つだ。**一度通知した申し込みは終わる。** 次の再入荷も受け取りたければ再度
  申し込む — 通知済みの行を削除して新しく作るので、新しい id（= 新しいキー）になる。
- V10 が `notification.type`・`mail_message.kind` の2つの CHECK に `RESTOCK_AVAILABLE` を加えた（B-55）。

## 4. 管理者の在庫 — 以前は補充する方法がなかった

在庫は注文で減り、返金・返品の入庫でしか戻らなかった。品切れアラートを受けても運用者にできることがなかった。

- `GET /api/content/admin/stock?soldOutOnly=true&after=<sizeId>` — 品切れ（または全体）のオプション、サイズ id のカーソル、1回に 200個
- `PUT /api/content/admin/stock/{sizeId}` `{"quantity": 12}` — その値に **設定する**（加算ではない）。0~1,000,000。
  0 から補充すると §2 の再入荷が送られる。
- `/api/content/admin` の下なので GET も管理者専用である（AdminWriteInterceptor）。
- 画面: `/admin/stock`（サイドバーの Orders → Stock）。保存後にサーバーから読み直す。

## 5. フロント — 商品画面の「再入荷通知を受け取る」

選択したサイズが品切れなら（`?size=` があり、そのサイズの在庫が 0）`RestockButton` が表示される。押すと申し込み、もう一度
押すと取り下げる（`aria-pressed`）。ログインしていなければログインへ送る。通知アイコンは `RESTOCK_AVAILABLE`。

## 6. ガード

| | 何を |
|---|---|
| `StockEventProducerTest` | 0 をまたぐときだけ、コミットの後に、オプション情報とともに |
| `ProductServiceUnitTest` | 在庫の変更が前・後の数量とオプションとともに渡される、管理者の調整（その値に・負数の拒否・存在しないサイズ） |
| `RestockSubscriptionServiceTest` | 新規申し込み（色 0）・重複・通知後の再申し込み（新しい id）・上限、送信（申し込み id のキー・リンク・通知済みの記録）、取り下げ |
| `RestockEventListenerTest` | 再入荷だけを通知する、ページが埋まらなくなるまで、品切れ・壊れたイベントは無視 |
| `RestockButton.test` · `Stock.test` | 申し込み/取り下げが送る値、ログイン、管理者の保存・検証・読み直し |
| B-41 · B-60b · B-61 | `product-stock` が DLT リストに、リスナーがトレースを引き継ぐ、品切れの文言 = アラートの文言 |

V10 はローカルの PostgreSQL 16 に V1~V10 を順に適用して確認した（色のデフォルト値 0、UNIQUE、部分インデックス、2つの CHECK）。

## 7. 売り越し — 決済後に在庫が足りないとき

### 🔴 何が問題だったか

在庫は **決済の後に** 減算する（order の Saga が `product-update` を送る）。最後の1個を2人が同時に決済すると、後の
減算が足りなくなる。そのとき起きていたこと:

- `updateProductSize_Qty` が静かに `null` を返し、リスナーは `Product could not be updated` という1行を残して終わった。
- 注文は決済完了として進んだ。返金もアラートもなかった — お客様はお金を払ったのに商品はなく、運用者は配送の段階になって初めて気づいた。
- 🐛 販売数は数量を確認する **前に** 増えていたので、減算が失敗しても販売数だけが増えた。
- 並行性そのものは問題ではなかった — SKU の行を `PESSIMISTIC_WRITE` でロックするので、同じオプションの減算は順番に並ぶ。問題は **足りないときの
  処理** だった。

### どう変えたか

```
catalog  product-update 消費 ─┬─ 行ごとに SKU ロック → 残数確認 → ある分だけ割り当て（販売数もその分）
                              └─ 不足数 → product.stock_shortage（同じトランザクション, V13）─ 発行器(2秒) → stock-shortage
order    stock-shortage 消費 ─┬─ 行ごとに short_qty を記録（V21, 冪等キー shortage_event_id）
                              └─ その数だけ部分返金（管理者権限の既存の返金経路）→ お客様に既存の返金通知
```

| 決定 | 理由 |
|---|---|
| **ある分だけ割り当て**、残りだけを返金 | 3個の注文に1個しか残っていなければ1個は送る。部分返金が数量単位なので可能である（docs/49 §10） |
| 不足の記録を **Outbox** で（コミット後の1回きりの送信ではなく） | お金が絡む通知である。コミット後の発行が失敗するとお客様が返金を受けられない。表に記録すれば次の周期で再送する。10分を超えて送れなければ「手動確認が必要」 |
| 注文 id を減算イベントの冪等キー（`order-<id>`）から読む | 減算メッセージ（Avro）にフィールドを足すとスキーマ進化が2段階必要になる（docs/55 §4）。キーにすでにある |
| 不足分を `short_qty` として **別に数える** | 発送前の返金は数量を「戻ってきた」と数え、在庫の復元を送る。不足した数は **減算されたことがない** — そのままにすると catalog に存在しない在庫が生まれる。`short_qty` は戻ってくる数・復元する数・送る数（配送情報）から除かれ、返金は不足分を先に消化する |
| 既存の返金経路をそのまま使う | 金額（割引の配分）・通貨（請求通貨）・プロバイダーの冪等キー・pay 通知・クーポン復元（全額のとき）・お客様への通知ルールを作り直さない |
| 🔒 **銀行振込は自動返金しない** | ゲートウェイが常に「受け付けた」と答える — 自動で呼ぶとお金は送られていないのに返金として記録される。不足は記録して「手動返金が必要」に回す |
| 返金が失敗したら **例外を投げる** | Kafka が再配信し、そのときは記録の段階を飛ばして返金だけをやり直す（返す数 = 記録された不足分 − すでに返金された数）。リトライがすべて失敗すると DLT に行き、失敗のたびに残した「手動返金が必要」で運用者が気づく |

すべて足りなければ注文全体が返金され（REFUNDED）、決済の取り消しが pay → `payment` CANCELED につながって配送も取り消される。

### 知らせるもの

| シグナル | いつ | どこ |
|---|---|---|
| `StockShortageOccurred` (warning) | 売り越しが起きた — 自動返金はされるが、在庫の数値が実物とずれたというシグナルである | Prometheus `catalog_stock_shortage_total` |
| `StockShortageRefundNotAutomatic` (critical) | 銀行振込・不明な注文 | Prometheus `order_stock_shortage_total{outcome}` |
| `ManualActionRequired` | 自動返金の失敗、発行の10分遅延、品目の不一致 | Loki — ログ "수동 확인/환불 필요"（B-61） |

### 限界 — 正直に

- **この節は事後の補償である。** 売り越しが起きてから数秒以内にお金を返す。決済前の在庫予約（注文作成時に確保し、決済失敗・離脱
  時に解放する、期限切れジョブ）は [docs/59](59-stock-reservation.md) で追加した — この節はその後も、予約が期限切れになった後の決済・catalog に
  届かなかった注文（fail-open）・バーチャル口座のセーフティネットとして残る。
- **一部不足なのに配送が先に作られている可能性がある。** delivery-service は決済完了イベントを受け取るとすぐに配送情報を一度読む。
  不足の記録より先に読んでいれば、配送数量は注文数量のままだ。すべて不足した場合は決済取り消しで配送が取り消されるが、一部だけ
  不足した場合は管理者が注文画面（品目の `shortQty`）を見て送り状を合わせなければならない。発送前の部分返金全般に共通する隙間である。
- 発行器は Pod ごとに動く — 同じ行を2つが送ることがあり、order が冪等キーで捨てる。

### ガード

| | 何を |
|---|---|
| `ProductServiceUnitTest` | 🔴 足りなければある分だけ割り当て・販売数もその分、0 なら何も変えない、商品なし = すべて不足、復元は不足なし、残数はロック後に読み負数は 0 |
| `ProductUpdateMessageListenerTest` | 🔴 足りなければ同じトランザクションで不足を記録、足りていれば記録なし |
| `StockShortageOutboxTest` | イベントキーから注文 id・JSON、注文が不明なら記録しない、送った後にマーク、失敗したらマークせずに止まる |
| `OrderStockShortageServiceTest` | 🔴 記録してその分を自動返金、解決済みの再配信は無視、🔴 返金失敗 → 再配信時は記録せず返金だけ、🔒 銀行振込は人に、返すものなし、不明な注文、行数の超過なし、配送数量 = 割り当てられた数 |
| `OrderRefundTest` | 🔴 不足分の返金は戻ってきた数として数えない（存在しない在庫を復元しないように）、発送後に不足分だけ返金したら返品待ちではない |
| B-41 · B-61 | `stock-shortage` が DLT リストに、"수동 … 필요" の文言 = アラートの文言 |

catalog V10 と order V21 は PostgreSQL 16 にそれぞれ2回ずつ適用して確認した（冪等、部分インデックス、重複キーの拒否）。
