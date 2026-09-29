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
| **chat-service** | STOMP ブローカーがインメモリ（`enableSimpleBroker`）である。しかも Kafka コンシューマーグループはメッセージをポッド **1 つにだけ**渡す。A ポッドに接続したユーザーは、B ポッドが消費したメッセージを受け取れない | ブローカーリレー（RabbitMQ/ActiveMQ）または Redis pub/sub ファンアウト |
| **eureka-server** | standalone 設定（`register-with-eureka: false`）である。2 つにすると互いを知らない**独立したレジストリ 2 つ**になり、どのポッドに登録されたかによってディスカバリー結果が分かれる | peer-aware 設定 + 安定した DNS（StatefulSet） |

どちらも**静かに**壊れる。ポッドは Running でログもきれいなのに、ユーザーだけがメッセージを受け取れない。
そのためコメントで残すだけでは不十分と判断し、ガードレール **B-34** がビルドで止める —
レプリカが 1 でないか、HPA の対象に入ると失敗する。

## 3. まだ残っていること — 消費側の冪等性

冪等台帳（`processed_event`）は **catalog-service と coupon-service にしか**ない。
order・pay・cart・chat にはない。Kafka の再配信や Outbox の再発行が起きると:

| サービス | 重複消費時 |
|---|---|
| pay-service | `UNIQUE(order_id, tracking_id)` が防いでくれる — §4 参照 |
| order-service | 楽観ロック例外を NO-OP として吸収する（部分的） |
| cart-service | 保護なし |
| chat-service | 保護なし — 同じメッセージが 2 回保存されうる |

pay と order はたまたま防御になっているが、**設計された防御ではない。** ロードマップに残す。

## 4. 決済の重複検知が一度も動作していなかった

`PaymentRequestKafaListener` は一意制約違反を捕まえて「処理済み」として流そうとしていた。
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
| chat-service | 1（固定） | 対象外 |
| eureka-server | 1（固定） | 対象外 |

コネクションの総和はガードレール B-32 が引き続き検証する — HPA の最大値が 3 のままなので、計算は変わらない。

## 7. 残りのロードマップ

| 項目 | 状態 |
|---|---|
| order・pay・cart・chat の冪等台帳 | 🟠 §3 |
| chat ブローカーリレー（その後 chat を拡張） | 🟠 |
| peer-aware Eureka（または k8s ネイティブのディスカバリーへ移行） | 🟡 |
| Outbox 発行の遅延・再試行メトリクス | 🟡 現在はログのみ |
