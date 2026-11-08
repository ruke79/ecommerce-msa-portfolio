# 21. 水平スケーリング — レプリカを増やしても安全か

**日本語** | [한국어](21-horizontal-scaling.ko.md)

> 🕰️ **状態（2026-09-27）— 一部古い。** §5 の決済リスナーの `Authorization` ヘッダー要求はなくなりました（Saga メッセージからトークンを除きました）。notification · delivery · saga-projection も `replicas: 2` です。

> 背景: [docs/00 §10](00-before-after.md) が「全サービス `replicas: 1` — 再起動・ノード交換のたびに
> そのままダウンタイム」を本番準備の障害として挙げた。ところが、数字を 2 に変えることが作業のすべてでは
> なかった。**増やした瞬間に静かに壊れる箇所**があった。

## 1. Outbox ポーラーが同じイベントを 2 回発行していた

`order-service` と `pay-service` は Outbox テーブルをポーリングして Kafka へ送り出す。以前の実装は
レプリカが 1 つのときにしか正しくなかった。

```java
// 以前 — ロックなしの照会
Optional<List<PaymentOutboxEvent>> events =
        helper.getPaymentOutboxEventByOutboxStatus(OutboxStatus.STARTED);

events.get().forEach(outbox -> publisher.publish(outbox, this::updateOutboxStatus));
```

問題は**二重**である。

**① ロックがない。** レプリカ A と B が同じ瞬間にポーリングすると同じ行を読む。両方とも発行する。

**② 状態更新がトランザクションの外で起きる。** `publish` は非同期だった —
`kafkaTemplate.send(...).whenComplete(...)` の中で `COMPLETED` に変えていた。そのコールバックは Kafka
プロデューサーの IO スレッドで、**スケジューラーのトランザクションがすでにコミットされた後に**実行される。つまりロックをかけても
状態が変わる前にロックが外れる。①だけ直してもウィンドウは閉じない。

結果は**決済リクエストが 2 回発行される**ことである。お金がかかった経路だ。

> ⚠️ これは「レプリカを増やしたら起きる問題」ではなく、**すでに作動状態にあった地雷**だった。
> `k8s/70-autoscaling.yaml` の HPA が order-service・pay-service を `maxReplicas: 3` として
> 対象に入れていた。負荷が上がればオートスケーラーが自動でポッドを増やし、その瞬間に重複発行が
> 始まる。誰も replicas に手を触れなくても起こる。

### どう直したか

**先取り（claim）+ 同期発行。** 1 つのトランザクション内で行をロックし、発行を待ち、状態を確定する。

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))  // SKIP_LOCKED
@Query("select e from PaymentOutboxEvent e where e.outboxStatus = :status order by e.createdAt asc")
List<PaymentOutboxEvent> claimByOutboxStatus(OutboxStatus status, Pageable pageable);
```

`SKIP LOCKED` が核心である。通常の `FOR UPDATE` なら B が A を**待ってから**、A がコミットした後に同じ
行を再び掴む — 重複がそのまま起きる。スキップしてこそ互いに重ならない集合を取っていく。

発行は `.get(timeout)` でトランザクション内で待つ。ロックが状態確定まで保持されるので②のウィンドウが
消え、おまけに「トランザクションなしで `save()` が呼ばれていた」おかしな状態もなくなる。

バッチサイズも制限した（`outbox-batch-size`、デフォルト 100）。制限がないと、1 つのレプリカが溜まったイベントを
すべてロックし、残りは遊んでしまう。

### おまけで明らかになったこと — 一時的な失敗がイベントを永久に捨てていた

以前は送信失敗時に `FAILED` と記録していた。ところがスケジューラーは `STARTED` しか照会しない。
**ブローカーが一瞬揺らいだだけなのに、その決済リクエストは二度と試行されない** — 静かに消える。

今は一時的な失敗では状態に触れない。次のポーリングが自然に再試行する。`FAILED` は
再試行しても無駄な場合（ペイロードのパース不能、未知のイベントタイプ）にのみ使う。
そして失敗が起きたら**そのバッチを中断**する — 試行し続けると、タイムアウト × バッチサイズ分だけ
行ロックと DB コネクションを握り続けることになる。

### 検証

単体テストでは捕まえられない。本物の同時トランザクション 2 つと本物の PostgreSQL が必要である。
`PaymentOutboxClaimConcurrencyTest` は 2 つのスレッドがそれぞれのトランザクションで先取りし、**両方が終わる
までコミットしない**（先にコミットするとロックが外れ、何も証明できない）。

ロックを外して実行すると:

```
AssertionError: 두 레플리카가 같은 아웃박스 이벤트를 집으면 결제 요청이 두 번 발행된다.
겹친 id: [5245196f-…, c813a273-…, 0cc99a79-…, a2830689-…]
```

バッチ 4 件が **100% 重なった。** ロックを戻すと重なりは 0。

> Kafka は依然として at-least-once である。コミット直前にポッドが死ねば再発行される。この変更がなくす
> のは**レプリカのせいで生じる**重複であって、配信保証から来る重複ではない。消費側の冪等性は
> 別途必要である（§3）。

## 2. 増やしてはいけないサービス 2 つ

| サービス | なぜ駄目か | 増やすには |
|---|---|---|
| **chat-service** | STOMP ブローカーがインメモリ（`enableSimpleBroker`）である。しかも Kafka コンシューマーグループはメッセージをポッド **1 つにだけ**渡す。A ポッドに接続したユーザーは、B ポッドが消費したメッセージを受け取れない | ✅ **2026-11-01 Redis pub/sub のファンアウトで解決 — §2-1** |
| **eureka-server** | standalone 設定（`register-with-eureka: false`）である。2 つにすると互いを知らない**独立したレジストリ 2 つ**になり、どのポッドに登録されたかによってディスカバリー結果が分かれる | peer-aware 設定 + 安定した DNS（StatefulSet） |

どちらも**静かに**壊れる。ポッドは Running でログもきれいなのに、ユーザーだけがメッセージを受け取れない。
そのためコメントで残すだけでは不十分と判断し、ガードレール **B-34** がビルドで止める —
レプリカが 1 でないか、HPA の対象に入ると失敗する。

### 2-1. chat — Redis のファンアウトで増やせるようになった(2026-11-01)

ブローカーは Pod ごとのインメモリ(`enableSimpleBroker`)のままで、変えたのは**配る経路**だけ。

| 何を | 以前(1 個固定) | 今 |
|---|---|---|
| 友だち · 相談のメッセージ | Kafka を消費した Pod が自分の購読者にだけ `convertAndSend` | 消費した Pod が Redis チャネル `chat:stomp-fanout` に一度載せ、**すべての Pod**(自分を含む)が受けて自分の購読者に(`StompFanout`) |
| 相談デスクの通知 | 同じ方式 — 別の Pod の相談員には届かない | 同じファンアウト |
| 保存 | コンシューマーグループ一つ — メッセージごとに一度 | そのまま(配るだけが変わった — グループを Pod ごとに分けると保存も Pod 数だけになる) |
| 相談の presence(見ていれば返信通知を送らない) | Pod のメモリ | メモリ + Redis ハッシュ `chat:support-presence:<Pod>`(90 秒の寿命 · 30 秒ごとに更新)— 別の Pod の購読も見る |
| 返信通知の 60 秒抑制 | Pod のメモリ(Pod ごとに数え、Pod 数だけ届きえた) | Redis の `SET NX` `chat:support-reply:<相談>` |

- **同じ JSON** — ペイロードは STOMP の変換器(Spring Boot の ObjectMapper)で作った JSON のバイトをそのまま載せ、そのまま送る。JSON ツリーに移すと時刻の数値表現が変わった(`StompFanoutTest` が捕まえた)。
- **順序** — 受ける側は一本のスレッドで順に送る(`StompFanoutConfig`)。リスナーコンテナの既定の実行器はメッセージごとにスレッドを立て、順序が崩れる(変異確認: 300 件の順序の試験が失敗)。
- **Redis が駄目なとき** — 発行に失敗したら消費した Pod の購読者にだけでも送って数える(`chat_stomp_fanout_failed_total`、警報 `ChatFanoutPublishFailed`)。presence · 抑制は Pod のメモリに退く(間違えても「通知がもう一通」の側)。pub/sub は最大一回の配信なので、購読が一瞬切れた Pod はその間のリアルタイムのメッセージを取りこぼす — メッセージは先に保存されるので開き直せば履歴にある。
- **配置** — `k8s/46` は 1 から、HPA 1 〜 3(`k8s/70`)。最小 1 の理由: SockJS の WebSocket 以外のフォールバック(xhr)はリクエストが同じ Pod に行く必要があるが、ゲートウェイに固定セッションがない — Pod が二つ以上だとそのユーザーは再接続を繰り返しうる(WebSocket でつながる大部分は無関係)。本番(OCI の単一ノード、`ops/28 local`)は `k8s/70` を適用しないので chat は 1 のまま — 2026-11-03 `7ef62043a98f` でファンアウトのコードだけが入った(`PUBSUB NUMSUB chat:stomp-fanout` 1)。HPA は `prod` 環境か `APPLY_HPA=1` のときだけ。
- **ガード** — B-34 の 1 個固定の一覧から chat-service を外した(eureka-server だけが残る)。代わりに **B-107**: chat で購読者に送る所は `StompFanout` 一つ(直接の `convertAndSend` · `SimpMessagingTemplate` は禁止)· 受ける側は一本のスレッドの実行器。
- **接続数** — chat も最大 3 になり最悪 10 × 5 × 3 = **150**(以前の chat 1 固定のときは 140)。`max_connections` 300 · ガード B-32(≤ 200)の内側。
- **試験** — `ChatScaleOutRedisTest`(コンテナ、実際の Redis を「Pod」二つが共有): 一つの Pod が送った 300 件を二つの Pod がすべて順に、A の Pod で見ていれば B が分かる(解除 · 切断 · 終了 · TTL)、同じ相談の返信を二つの Pod が消費しても通知は一通。それぞれ戻すと失敗することを確認した。

## 3. まだ残っていること — 消費側の冪等性

> 2026-11-07 更新: cart（V6 — cart-empty、2026-10-15）· order の配送状態（V30 — 配送番号 · 状態、2026-11-07）に台帳ができた。下の表はその更新を反映した。コンシューマーのオフセット既定が earliest になり（B-110、docs/89 §2-1）読み直しが増えうるので、この表がより重要になった。

冪等台帳（`processed_event`）は最初 **catalog-service と coupon-service にしか**なかった。
order・pay・cart・chat にはなかった。Kafka の再配信や Outbox の再発行が起きると:

| サービス | 重複消費時 |
|---|---|
| pay-service | `UNIQUE(order_id, tracking_id)` が防いでくれる — §4 参照 |
| order-service | 決済応答: すでに PAID · 取消なら Saga が待機状態を見つけられず飛ばす。配送状態: **台帳（V30、2026-11-07）** — 同じ配送の同じ状態は一度だけ（前にしか進まない規則の外の配送キャンセルが再び来て注文を戻していたもの） |
| cart-service | **台帳（V6、2026-10-15）** — cart-empty は注文ごとに一度。coupon-use · cart-rollback は合計の再計算なので冪等 |
| chat-service | 同じ `_id`（送信側が決めた連番）で保存 — 同じ文書を書き直す。読み直した古いレコードは配信しない（2026-11-05） |

pay と order はたまたま防御になっているが、**設計された防御ではない。** ロードマップに残す。

## 4. 決済の重複検知が一度も動作していなかった

`PaymentRequestKafkaListener` は一意制約違反を捕まえて「処理済み」として流そうとしていた。
そのコードが**一度も実行されていなかった。** 2 つのことが重なっていた:

```java
"23000" == sqlException.getSQLState()
```

- **`==` で String 比較。** ドライバーが作った文字列はインターンされないので常に false である。
- **値も間違っていた。** PostgreSQL の一意制約違反は **23505** で、23000 は別の DB 系列が使う
  一般的な整合性違反クラスである。`.equals()` に直してもマッチしなかった。

つまり重複した決済メッセージはスキップされず、**すべて DLT へ**行っていた。今は Spring が 23505 を翻訳してくれる
`DuplicateKeyException` を直接捕まえる — SQLState 文字列を手で比較しないので、同じ
ミスは再発しえない。

## 5. Authorization ヘッダーがないとバッチが丸ごと消えていた

2 つの決済リスナーはどちらもこの形だった:

```java
if (authorizationHeader != null) {
    ... 処理 ...
} else {
    log.warn("Authorization header not found in the message batch.");   // 終わり
}
```

メッセージはコミットされ、決済は起きない。**音のない消失**である。order-service 側なら
注文が永遠に `NOT_PROCESSED` のまま残る（Saga が静かに止まる）。

処理を拒否する判断そのものは正しい — 認証なしで決済を作ることはできない。変えたのは**結果が
見えるように**したことだけである: 今は例外を投げて DLT に残る。アラートもかかっている（docs/12）。

## 6. 現在の状態

| サービス | replicas | HPA |
|---|---|---|
| gateway-server, user, catalog, cart, order, pay, coupon, frontend | **2** | min 2 / max 3 |
| chat-service | 1 | min 1 / max 3(2026-11-01 — §2-1) |
| eureka-server | 1（固定） | 対象外 |

コネクションの総和はガードレール B-32 が引き続き検証する — HPA の最大値が 3 のままなので、計算は変わらない。

## 7. 残りのロードマップ

| 項目 | 状態 |
|---|---|
| order・pay・cart・chat の冪等台帳 | 🟡 §3 — cart（V6）· order の配送状態（V30）済み、pay は一意制約 · chat は同じ `_id` |
| chat ブローカーリレー（その後 chat を拡張） | ✅ 2026-11-01 Redis のファンアウト(§2-1) |
| peer-aware Eureka（または k8s ネイティブのディスカバリーへ移行） | 🟡 |
| Outbox 発行の遅延・再試行メトリクス | 🟡 現在はログのみ |
