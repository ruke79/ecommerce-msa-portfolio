# 47. 注文作成 400 — 失敗が見えない

**日本語** | [한국어](47-order-create-failure-visibility.ko.md)

> 🕰️ **状態（2026-09-27）— 過去の記録。** 顧客のバックフィルと `User-Email` ヘッダーはその後なくしました（[docs/48](48-user-replication-debt.md)）。

`POST /api/order/create` が 400 を返した。order-service のログをその時刻で開いてみると
**その時刻の行が一行もない。** Kafka のリバランスと eureka のハートビートだけである。

ログがないことがこの文書のテーマである。**原因を見つけられなかったのではなく、見つけられないように
なっていた。**

---

## 1. なぜ何も残らなかったのか

```java
@PostMapping("/create")
ResponseEntity<?> createOrder(@RequestBody OrderRequest request, @RequestHeader("Authorization") String token) {
    try {
        Order newOrder = orderService.createOrder(request, token);
        String orderID = Long.toString(newOrder.getOrderId());
        ...
    } catch (RuntimeException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new MessageResponse(e.getMessage()));
    }
}
```

**すべての `RuntimeException` を捕まえ、ログなしで 400 に変える。** cart-service の呼び出しが
失敗しても、パースが壊れても、null が出ても、すべて同じ 400 でログはない。

## 2. しかもサービスが `null` を返していた

`createOrder` には `return null` が 3 つの分岐にあった。

| 分岐 | 意味 | そのとき残っていたログ |
|---|---|---|
| カートが空だった | ユーザーが直せる | `장바구니가 비어 있어…`（warn） |
| order DB に顧客がいない | **運用上の問題** | `고객을 찾지 못해…`（warn） |
| 配送先がない | ユーザーが直せる | （なし — NPE で落ちていた） |

コントローラーはその `null` に対して `newOrder.getOrderId()` を呼ぶ。**NPE** が発生し、それが
上の `catch` に捕まって、**本文が JVM の NPE 文言である 400** になる:

```
Cannot invoke "com.project.order_service.model.Order.getOrderId()" because "newOrder" is null
```

フロントは `error.response.data.message` をそのまま画面に表示する — **ユーザーがこの文を
読む。** 3 つの原因が 1 つの意味不明な 400 に潰され、内部実装まで漏れた。

> `warn` はあったが、それだけでは不十分だった。今回のように **NPE がその前で発生すると**（配送先
> なし）何も残らず、残っていたとしても「この 400 があの warn なのか」を結び付ける根拠がない。

## 3. 3 つ目の問題 — すべて 400 だった

cart-service が落ちても、JSON が壊れても「あなたのリクエストが間違っている」になる。ユーザーは直すものが
ないのに直せと言われ、運用者は 4xx の指標だけを見て「クライアントの問題」に分類する。

## 4. 直し方

**失敗を 2 種類に分ける。**

| | ステータス | 本文 | ログ |
|---|---|---|---|
| `DomainException`（ユーザーが直せる） | 400 | その文言（すでにユーザー向けに書かれている） | `warn` |
| それ以外の `RuntimeException` | **500** | 一般的な文言 | **`error` + スタックトレース** |

例外メッセージをそのまま出さない理由は、それがたいてい内部事情だからである。

そして `null` の返却をなくした。各分岐が **理由を付けて**投げる。

| 分岐 | ユーザーへ | ログ |
|---|---|---|
| カートが空 | "장바구니가 비어 있습니다. 상품을 담은 뒤…" | `주문 생성 실패: 장바구니가 비어 있다` |
| 顧客なし | "주문자 정보를 찾지 못했습니다…" | `주문 생성 실패: order DB 에 고객이 없다 — user-created 이벤트가 처리됐는지 확인할 것` |
| 配送先なし | "배송지를 선택해 주세요." | `주문 생성 실패: 배송지가 없다` |

配送先の検査を **先に**行う理由: それがないと、その下の 9 行がすべて NPE になる。

## 5. 「顧客なし」はなぜ起こるのか — あらかじめ書いておく

order-service の `orders.customer` は **`user-created` イベントで埋められる**
（`CustomerMessageListener`）。そのイベントは **会員登録の時点ではなく、メール認証の時点で**
発行される（`UserServiceImpl.registerConfirmed`）。

そのため、次のようなアカウントが生じうる:

- 認証メールが送られていなかった期間に登録したアカウント（docs/29・38 参照）— 後で認証を済ませても、
  そのとき order-service が起動していなかったりコンシューマーが遅延していたりすれば、行がない。
- 管理者が DB に直接作ったアカウント — 誰もイベントを発行しない。

症状は **ログインはできるのに注文だけができない**ことである。確認:

```bash
# order DB にその顧客がいるか
kubectl -n ecommerce exec -it deploy/postgres -- \
  psql -U postgres -d orderdb -c \
  "select customer_id, email from orders.customer where email = '<이메일>';"

# コンシューマーが遅延しているか
kubectl -n ecommerce logs deploy/order-service | grep -i "Customer is created\|user-created"
```

### ✅ 実際にそうだった

確認の結果、`orders.customer` に該当アカウントの行が **なかった**。そして order-service の起動
ログがその理由を教えてくれる:

```
Setting offset for partition user-created-0 to the committed offset FetchPosition{offset=0 ...}
리밸런스 — 그룹 'user-1' 이 파티션 [user-created-0] 을 받았다. 받을 때 lag={user-created-0=0}
```

**オフセット 0・lag 0 ＝ そのパーティションにメッセージが 1 つもない。** コンシューマーが取りこぼしたのではなく、
**一度も発行されていない。**

### なぜ発行されなかったのかも分からない — それが 2 つ目の問題

```java
public void publish(UserCreatedRequest req) {
    kafkaTemplate.send("user-created", String.valueOf(req.getUserId()), req);
}
```

`send` は **非同期**である。future を返してすぐに終わる。シリアライズの失敗でも、ブローカーの拒否でも、
タイムアウトでも — **その future を誰も見ないので、ログが一行も残らない。**

この文書のテーマがここで繰り返される。**失敗が見えない場所が 2 箇所重なると、症状と
原因の距離がそれだけ遠くなる。** ユーザーが見たのは「注文ボタンを押すと 400」、原因は
「数週間前のメール認証のときに Kafka への発行が黙って失敗していた」であった。

## 5-1. 直し方 — 知らせる側と耐える側

| | |
|---|---|
| **user-service** | `send(...).whenComplete(...)` で結果を見る。失敗は `error` + userId。**例外は投げない** — メール認証はすでに終わっており、ここで失敗したからといってユーザーに「認証失敗」を見せるわけにはいかない |
| **order-service** | 注文時点で行がなければ **user-service に問い合わせて自分で作る**（`OrderService.resolveCustomerId` → `backfillCustomer`） |

イベントを直すのとは **別に**バックフィルを置いた理由: イベントは速い道にすぎず、**取りこぼしたものを
永遠に直せない構造はそれ自体が欠陥**である。すでにそういう状態になったアカウントは、イベントを
直しても復活しない。

> 🧩 **ただし、このバックフィルは復旧用のバルブであって設計ではない。** 同じイベントを chat・coupon も受け取り、
> 取りこぼしたときにそれぞれ異なる壊れ方をする（クーポンは受け取れなかったという事実すら分からない）。order は
> そもそもこの複製が不要である — 必要な id はすでにトークンにある。整理は
> **[docs/48](48-user-replication-debt.md)** にあり、構造の改善はまだ行っていない。

> ⚠️ バックフィルは **リモート呼び出し**なので、必ずトランザクションの外である（docs/37 #5）。そのため
> `OrderDataService.createOrder` は customerId を **引数で受け取る** — 自分で探すようにすると、
> いつかその場で user-service を呼びたくなる。ガードは
> `OrderTransactionBoundaryTest.thePersistStepTakesTheCustomerId` であり、`OrderDataService`
> に `UserServiceClient` フィールドが **ないこと**まで固定する。

競合（その間にイベントが到着）は `DataIntegrityViolationException` を捕まえて読み直す —
失敗ではなく勝ち負けの問題であり、望む結果（行がある）はすでに達成されている。

### アカウントを消して作り直せばよいのでは

**勧めない。** 発行が一度も成功したことがないなら（トピックが空である）、新しいアカウントも同じ
場所に落ちる — 注文・カート・クーポンを失うだけで、問題はそのままである。発行ログが出るようになった後
（上記の修正）なら再登録が実際に何を生むかを確認できるようになるが、それまではバックフィルの方が確実である。

## 5-2. この 400 がなぜよりによって `{"message": null}` だったのか

2 箇所でメッセージが消えていた。

```java
@AllArgsConstructor
public class CustomException extends RuntimeException {
    private final ErrorCode errorCode;
}
```

Lombok の `@AllArgsConstructor` はフィールドを代入するだけで、**`super(message)` を呼ばない。**
`ErrorCode.USER_NOT_FOUND` に "해당 회원 정보를 찾을 수 없습니다." が入っていても、例外の
`getMessage()` は **常に `null`** だった。その値をそのままレスポンス本文に入れるので
`{"message": null}` になる。→ `detail` を `super` に渡すようにした。

```java
case 400: break;
...
return null;     // FeignErrorDecoder
```

Feign はこの戻り値を `throw errorDecoder.decode(...)` でそのまま投げる。つまり **`throw null`**
であり、どのサービスが何番で応答したのかはその瞬間に失われる。→ 不明な状態は
`ErrorDecoder.Default` に任せて `FeignException`（ステータス・リクエスト・レスポンス本文を含む）を返す。
`default` 分岐の `new Exception(...)` もなくした — **検査例外**なので Feign がもう一度ラップし、
原因がさらに一枚埋もれていた。

## 5-3. ✅ 実際の原因 — カートの取得が成立したことがなかった

可視化が入ると、最初の再試行ですぐに出てきた。

```
WARN  FeignErrorDecoder - 서비스 간 호출 실패: CartServiceClient#getCart(String,String) -> 401
ERROR OrderController - 주문 생성 실패(예상치 못한 오류)
feign.FeignException$Unauthorized: [401] during [GET] to
  [https://cart-service…/api/cart/checkout?userId=…&Authorization=Bearer%20eyJ…]
  : [{"message":"로그인이 필요합니다."}]
```

cart-service の `/api/cart/checkout` は、セキュリティ強化の際に **クエリパラメータを捨て、ゲートウェイが
検証したヘッダーを読むように**変わっていた:

```java
ResponseEntity<?> getCart(@RequestHeader(value = "User-Email", required = false) String userEmail,
                          @RequestHeader("Authorization") String token) {
    if (userEmail == null || userEmail.isBlank()) return 401;
```

order-service の Feign クライアントはそのままだった:

```java
ResponseEntity<?> getCart(@RequestParam("userId") String email,
                          @RequestParam("Authorization") String token);
```

`User-Email` ヘッダーがないので **常に 401**。つまりこのデプロイ以降、**注文は一度も成功していなかった。**

### なぜこれほど長く見えなかったのか

- **フロントの経路は正常だった。** 決済画面がカートを読む呼び出しはゲートウェイを通るので
  ヘッダーが付く。壊れていたのは **サービス間の直接呼び出しだけ**なので、cart-service のログには正常な
  呼び出しだけが見え、失敗した呼び出しはコントローラー本体に入る前に終わって痕跡がなかった。
- **コンパイラーでは捕まえられない。** 両者は別モジュールで、契約はアノテーションにしかない。
- そして §1〜§5-2 の目隠し 3 つがその上に重なっていた。

ガードは `FeignContractTest` — Feign クライアントのアノテーションをリフレクションで見る。古い
シグネチャに戻すと失敗することを確認した。

## 5-4. 🔒 アクセストークンが URL に載っていた

上の例外メッセージを見直すと、**トークン全文がクエリ文字列に入っている。**

```
?userId=…&Authorization=Bearer%20eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI…
```

`UserServiceClient` の 4 つのメソッドと `CartServiceClient.getCart` がすべて
`@RequestParam("Authorization")` だった。**URL はどこにでも残る** — アクセスログ、プロキシ、
例外メッセージ、Referer。実際に本番のログに有効なアクセストークンが平文で記録された。

しかも **必要すらなかった。** `FeignConfig` のインターセプターが、受け取ったリクエストの `Authorization`
**ヘッダー**をそのまま付け直す。受け取る側の `/api/auth/user/id` はそのパラメータを読みも
しない。そこでパラメータを消した — ヘッダーとして **宣言し直すことはしない。** インターセプターと重なって
同じヘッダーが 2 回送られるからである。

> ⚠️ `User-Role` はインターセプターが転送しない。`/api/auth/user/id` はそのヘッダーが **あると**
> 外部からの呼び出しとみなして管理者だけを通すので、サービス間呼び出しに載せると 403 になる。これも
> `FeignContractTest` が固定する。

## 5-5. 🔒 注文者をリクエスト本文から読んでいた

`createOrder` は `request.getEmail()` — **リクエスト本文** — をカートの取得・クーポンの見積もり・注文者の
取得のすべてに使っていた。他人のメールアドレスを入れて送ると、**その人のカートで注文が作られ、
配送先は自分のもの**になる。

今はゲートウェイが検証した `User-Email` ヘッダーからのみ読む。このリポジトリの他のエンドポイントが
クエリパラメータ `email` を捨てたのと同じ理由である（`getOrder`・`getOrders` のコメント）。

## 6. 次にこの 400 に出会ったら

今は order-service のログに必ず一行が残る。

```bash
kubectl -n ecommerce logs deploy/order-service --since=10m | grep "주문 생성"
```

- `주문 생성 거절: …` → 400。その文言はユーザーが見たものと同じである。
- `주문 생성 실패(예상치 못한 오류)` + スタックトレース → 500。こちらの落ち度である。
- **何もない** → リクエストが order-service に届いていない。ゲートウェイ（ルート・JWT フィルター・
  サーキットブレーカーのフォールバック）を見る。

最後の行が重要である。直す前は、この 3 つのケースを区別できなかった。

## 検証

```
:order-service:test  通過
:user-service:test   通過
```
