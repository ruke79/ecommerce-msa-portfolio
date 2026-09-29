# 27. セキュリティレビュー — フロントエンド · バックエンド

**日本語** | [한국어](27-security-review.ko.md)

> 🕰️ **状態（2026-09-27）— 一部古い。** §14「サービス間の認証なし」は mTLS で解決しました（[docs/57 §16](57-remaining-priorities.md)）。冒頭の要約表は発見当時の記録として残します。最新の未解決項目は [docs/54 §10](54-improvements-vs-practice.md)。

認証・認可、シークレット管理、露出した管理エンドポイント、インジェクション、レート制限を、フロントエンドと全サービスに
ついて洗い出した。**見つかったものの半分は「権限チェックがまったくない」類** だった — 誤って実装されたのではなく、
実装されていなかったのである。

最も重要な結論から: **このサービスは、ログインさえすれば他人の注文・カート・クーポンを読むことが
できた。** 脆弱性が 1 つあったのではなく、同じミスが 4 つのサービスで繰り返されており、原因は共通だ —
**アイデンティティをクライアントが送った値から読んでいた。**

---

## 要約

| # | 場所 | 問題 | 深刻度 | 状態 |
|---|---|---|---|---|
| 1 | order-service | 注文番号さえ分かれば **どの注文でも** 照会できる（所有権チェックが皆無） | 🔴 Critical | 修正済み |
| 2 | order-service | `?email=` で他人の注文一覧を照会 | 🔴 Critical | 修正済み |
| 3 | cart-service | `?userId=` で他人のカートを照会 | 🔴 Critical | 修正済み |
| 4 | coupon-service | `?email=` で他人のクーポンを照会 | 🔴 High | 修正済み |
| 5 | coupon-service | クーポン削除に **権限チェックがない** | 🔴 High | 修正済み |
| 6 | delivery-service | 送り状の登録に **権限チェックがない**（私が書いたコード） | 🔴 High | 修正済み |
| 7 | config-server | actuator `include: "*"` → `/env` で **シークレットがすべて露出** | 🔴 Critical | 修正済み |
| 8 | gateway | actuator `gateway` の露出 → **ルートの操作が可能** | 🟠 High | 修正済み |
| 9 | user-service | メールアドレスによるユーザー照会が外部に開いている（アカウント列挙） | 🟠 High | 修正済み |
| 10 | gateway | ログイン経路のレート制限が 20 req/s（毎分 1,200 回の総当たり） | 🟠 High | 修正済み |
| 11 | gateway | `User-Id` ヘッダーにメールアドレスを入れており、名前と中身が不一致 | 🟠 High | 修正済み |
| 12 | frontend | JWT の `role` クレームを `roles` と誤って読む → ロールが常に空配列 | 🟠 High | 修正済み |
| 12b | gateway | アイデンティティヘッダーを外部入力から削除していない → ヘッダー偽造・レート制限の回避 | 🔴 Critical | 修正済み |
| 13 | user-service | ログイン失敗回数を数えていない（アカウントロック未実装） | 🟠 | 修正済み（[docs/54 §10.2](54-improvements-vs-practice.md)） |
| 14 | 全般 | サービス間の呼び出しに認証がない（ネットワーク分離にのみ依存） | 🟠 | 修正済み（[docs/57 §16](57-remaining-priorities.md)） |

---

## 1〜4. 🔴 アイデンティティをクライアントに尋ねていた（IDOR）

**同じミスが 4 つのサービスにある。** それぞれ別のバグではなく、1 つのパターンである。

### 何が問題だったか

```java
// order-service
@GetMapping("/{orderId}")
ResponseEntity<?> getOrder(@PathVariable String orderId, @RequestParam("email") String email)

@GetMapping("/orders")
ResponseEntity<?> getOrders(@RequestParam("filter") String filter, @RequestParam("email") String email)

// cart-service
@GetMapping("/loadcart")
ResponseEntity<?> loadCart(@RequestParam("userId") String userId, ...)

// coupon-service
@GetMapping("/coupons")
ResponseEntity<?> getNotUsedCoupons(@RequestParam("email") String userId, ...)
```

**「あなたは誰？」をリクエスト元に尋ね、その答えを信じていた。** ゲートウェイが JWT を検証するので
*ログインした人* であることは確かだが、その人が *誰なのか* はクエリパラメーターから読んでいた。そのため
ログインした誰もが次のようにできた:

```
GET /order-service/api/order/orders?email=victim@example.com
GET /cart-service/api/cart/loadcart?userId=victim@example.com
GET /coupon-service/api/coupon/coupons?email=victim@example.com
```

メールアドレスさえ分かれば、その人の注文履歴、カート、保有クーポンがすべて出てくる。

### しかも 1 番はメールアドレスすら必要なかった

`getOrder` はさらに悪い。`email` をパラメーターとして <b>受け取っておきながら使っていなかった</b>:

```java
// OrderDataService — email 引数がどこにも使われていない
public OrderDto getOrder(Long orderId, String email, String filter) {
    Order data = filter.isEmpty()
            ? orderRepository.findById(orderId).orElse(null)      // ← 注文番号しか見ない
            : orderRepository.findByOrderIdAndOrderStatus(orderId, ...).orElse(null);

    if (data != null) {
        return OrderService.convertToOrderDto(data, ...);          // ← そのまま返す
    }
    return null;
}
```

コントローラーにもチェックがない。したがって:

```
GET /order-service/api/order/1?email=아무거나
GET /order-service/api/order/2?email=아무거나
GET /order-service/api/order/3?email=아무거나
...
```

**注文番号を 1 から数えていけば、全顧客の注文が順番に出てくる。** 露出する内容は注文商品、
決済金額、決済手段、そして **配送先住所と受取人の電話番号** だ。連番で増える整数 id なので、
列挙のコストもほとんどない。

### どう直したか

**アイデンティティの出どころを 1 つに固定した: ゲートウェイが検証したトークン。**

ゲートウェイが JWT を検証した後、アイデンティティをヘッダーに入れて渡す。クライアントが同じ名前のヘッダーを
送っても、ゲートウェイが **削除してから** 自分の値を入れるため、なりすましはできない。

```java
// ゲートウェイ — クライアントが送った値を先に削除する
ServerHttpRequest.Builder mutated = request.mutate()
        .headers(h -> {
            h.remove("User-Id");
            h.remove("User-Email");
            h.remove("User-Role");     // これを削除しないとヘッダー 1 つで管理者になれる
        });
```

```java
// order-service — パラメーターの代わりに検証済みのヘッダー
@GetMapping("/orders")
public ResponseEntity<?> getOrders(@RequestParam(required = false) String filter,
        @RequestHeader(value = "User-Email", required = false) String userEmail) {
    if (userEmail == null || userEmail.isBlank()) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)...;
    }
    return new ResponseEntity<>(orderService.getOrders(userEmail, filter), HttpStatus.OK);
}
```

`getOrder` はデータ層で所有権を照合する — `OrderDto` には所有者のフィールドがなく、エンティティを
見る必要があるからだ:

```java
Long requesterId = getCustomerIdByEmail(email);
if (requesterId == null || !requesterId.equals(data.getCustomerId())) {
    log.warn("다른 고객의 주문 조회 시도를 차단: orderId={}", orderId);
    return null;
}
```

**403 ではなく 404 で答える。** 403 は「その注文は存在する」と教えるようなものなので、番号を総なめにして
どの注文が実在するかを確かめること自体は防げない。

`requesterId == null` も拒否する。トークンは有効だが注文ドメインに顧客レコードがない
ケースで、null を「一致」として扱うと、その経路からすべてが開いてしまう。

### フロントエンドも一緒に変えた

```js
// 修正前
export const getOrders = async (filter, userEmail) =>
  getRequest("/order-service/api/order/orders", { params: { filter, email: userEmail } });

// 修正後 — アイデンティティをそもそも送らない
export const getOrders = async (filter) =>
  getRequest("/order-service/api/order/orders", { params: { filter } });
```

サーバーがすでに無視しているので、機能上は送る必要がない。それでも削除した理由は、クライアントが送り続けると
**「この値がアイデンティティを決める」という誤解を残し**、後で誰かがサーバー側で再び信頼するように
戻してしまう余地を与えるからだ。

### 回帰テスト

この種の欠陥は **機能テストでは決して捕まらない** — 自分の注文を照会する正常経路は、
修正前でも問題なく動いていた。そこで「他人のものを要求したら拒否する」を明示的に固定した
（`OrderOwnershipTest`）。

テストが本当に捕まえるかを確かめるため、**修正を一時的に元に戻してみて** 失敗することを確認した:

```
OrderOwnershipTest > 남의 주문을 주문번호로 조회하면 아무것도 돌려주지 않는다 FAILED
OrderOwnershipTest > 존재하지 않는 사용자로 요청해도 주문이 새지 않는다 FAILED
3 tests completed, 2 failed
```

修正を戻しても通ってしまうテストは、何も守れない。

---

## 5〜6. 🔴 権限チェックがまったくない書き込みエンドポイント

### coupon-service — クーポン削除

```java
@DeleteMapping("/delete")
ResponseEntity<?> deleteCoupon(@RequestParam("id") String id) {
    couponService.delete(id);      // 権限チェックも所有権チェックもない
```

ログインした誰もが、クーポン id を入れるだけで削除できた。id を総なめにすれば、発行済みのプロモーションクーポンを丸ごと
削除することも可能だ。管理者ロールを要求するように直した。

### coupon-service — クーポンの発行・更新（2026-09 発見、docs/54）

削除だけを直し、**同じコントローラーの発行（`POST /api/coupon/add`）・更新（`PUT /update`）は開いたままだった。**
ゲートウェイはログインしか確認しないため、ログインした誰もが **自分の id で割引率を好きに決めたクーポン** を作ることができた。
注文総額はサーバーがそのクーポンで計算するので、決済金額の照合（docs/35）も通過する — 金額の検証では防げない
類のものだ。画面はこの経路を呼ばない（一般ユーザーの発行は、サーバーが条件を判定する `/events/{slug}/claim` だけ）。
どちらも `InternalCallGuard.isAdmin(User-Role)` で塞いだ（更新は今のところサービスが何もしないが、中身が埋まった瞬間に
割引率を変える経路になる）。ガード: `CouponAdminGuardTest`。

> 教訓: 1 つのエンドポイントの権限漏れを直すときは、**同じコントローラーの兄弟をすべて** 見る（§9b と同じ形）。

### delivery-service — 送り状の登録（私が書いたコードだ）

これは **私が今回の作業で書いたコード** であり、コメントで問題を認識しておきながら直さずに
残していた:

```java
/**
 * ⚠️ このエンドポイントは顧客ではなく販売者/管理者用である。現在はゲートウェイが
 * 認証しか確認せずロールまで区別しないため、本番の前に管理者ロールのチェックを入れる必要がある。
 */
@PostMapping("/order/{orderId}/tracking")
```

「後で入れる必要がある」と書いておくことは対処ではない。その間このエンドポイントは、**ログインした
誰もが** 他人の注文に任意の配送業者・送り状番号を付けられる状態であり、その結果は軽くない:

- 注文が発送済みに変わり（ポーリング開始 → `DISPATCHED`）
- **住所変更とキャンセルが塞がれる**（発送前にしかできないため）
- 顧客に、届くはずのない品物の配送通知が送られる

現在はゲートウェイが渡した `User-Role` を確認し、**ヘッダーがなければ拒否** する。ないときに
通してしまうと、ゲートウェイを迂回したリクエストがそのまま入ってくる。

> 参考: 同じ delivery-service の照会・住所変更・キャンセルは、最初から `findByOrder(customerId, orderId)`
> で所有権を確認していた。送り状の登録だけが抜けていた。

---

## 7〜8. 🔴 管理エンドポイントが開いている

### config-server — `include: "*"`

```yaml
management:
  endpoints:
    web:
      exposure:
        include: "*"        # 🔴
```

config-server は **すべてのサービスの設定を握っている** サービスだ。露出範囲が最も狭くあるべきなのに、
正反対にすべて開いており、認証もなかった。露出するもの:

- `/actuator/env` — **DB パスワード、JWT シークレットを含む環境変数のすべて**
- `/actuator/configprops` — バインドされた設定値のすべて
- `/actuator/heapdump` — ヒープダンプ（メモリ内の平文の資格情報まで）

`health, info, prometheus` だけを残し、`show-details: never` を設定した。

> これは、今回の作業でずっと気を配ってきたシークレット管理（ops/01 のパスワードローテーション、k8s Secret の分離、
> `.gitignore` の処理）を **一度に無意味にする** 穴だった。ファイルからシークレットを抜いても、
> HTTP でそのまま読めるなら意味がない。

### gateway — `gateway`, `beans`

```yaml
include: gateway, refresh, health, beans, http, busrefresh, info, metrics, prometheus
```

`gateway` アクチュエーターは、ルートの **照会だけでなく POST での追加・削除** ができる。認証なしで開いて
いれば、トラフィックを任意の宛先に向けられるということだ。`beans` は内部構造をそのまま
教えてしまう。どちらも削除した。

`refresh`/`busrefresh` は設定のリロードをトリガーする書き込みエンドポイントなので本来は認証の後ろに
あるべきだが、運用手順がこれに依存している可能性があるため、今回は残して別の課題に切り出した。

---

## 9. 🟠 メールアドレスでユーザーを照会する内部 API が外部に開いている

```java
@GetMapping("/user/id")
public ResponseEntity<?> findUserByEmail(@RequestParam("email") String email)
```

サービス間の Feign 呼び出し用なのに、ゲートウェイのルート（`/user-service/api/auth/**`）に含まれており、**ログインした
誰もが** 呼び出せた。メールアドレスを入れると userId・名前・ニックネーム・プロフィール画像が出てくるので、
メールアドレスのリストさえあれば、登録有無の確認（アカウント列挙）と個人情報の収集がそのまま可能だった。

**判定方法:** ゲートウェイを経由したリクエストには、ゲートウェイが `User-Role` を付ける（クライアントが
送った同名のヘッダーは削除する）。内部の Feign 呼び出しはゲートウェイを経由しないので、このヘッダーがない。
したがって *ヘッダーがある = 外部から来たリクエスト* であり、この場合は管理者だけを許可する。

メールアドレス全体を出力していた `log.info` も削除した — 個人情報だ。

> ⚠️ **この判定は、user-service に外部から直接到達できないという前提の上に成り立っている。** k8s では
> ClusterIP なので基本的に成立し（このプロジェクトのすべての Feign トラフィックがすでに同じ前提を使っている）、
> サービスのポートを露出した瞬間に崩れる。根本的な解決は §14 だ。

### 9b. 🔴 同じコントローラーの兄弟エンドポイント 3 つは開いたままだった（2026-09 発見）

§9 は `/user/id` の 1 つしか塞いでいなかった。すぐ隣の **id・名前で探す照会 3 つにはガードがなかった**:

| エンドポイント | 返していたもの | 呼び出し元 |
|---|---|---|
| `GET /api/auth/user/email/{userId}` | メールアドレス | cart-service（内部） |
| `GET /api/auth/user/profile/{userId}` | 名前・メールアドレス・画像・ロール・ロック状態・登録日… | なし |
| `GET /api/auth/user/name?userName=` | id・メールアドレス・ニックネーム・画像 | order-service Feign（宣言のみ） |

`/api/auth/user/**` は USER ロールしか要求しないので、**ログインした誰もが** id を変えながら他人のメールアドレスとプロフィールを
取得できた — OWASP API Top 10 の 1 番、**BOLA/IDOR** である。id が TSID（時間ベース）なので、総当たりよりも
はるかに狭い範囲をなめるだけで済む。

§9 の判定（`User-Role` があれば外部）にも穴があった: **role クレームのないトークンは `User-Role` なしで
届き、「内部呼び出し」として通過する。** 他のサービスはすでに `InternalCallGuard.allowed(role, email, id)`（3 つのアイデンティティ
ヘッダーのうち 1 つでもあれば外部）に移行していたのに、user-service だけが古い判定のままだった。

**直したこと** — 5 つのエンドポイントすべてで `InternalCallGuard.allowed`。塞ぐときの id・名前の照会は **存在しないユーザーと同じ
404** にする（403 だと「その id は存在する」と教えてしまう）。`/user/id`・`/user/segments` は既存のレスポンス（403）を維持。
Feign は `Authorization` しか転送しないので（各サービスの `FeignConfig`）、内部呼び出しには影響がない。
**ガード**: `UserLookupGuardTest`（5 件 — 他人の id は 404・照会自体を行わない、プロフィール・名前も、内部呼び出しは通過、管理者は
通過、ロールヘッダーなしで User-Id だけあっても外部）。

> **実務基準との差 — まだ残っているもの。** 実務では、内部専用 API を **別のポート/リスナー** に置くか、ゲートウェイの
> ルートから完全に外し（`/internal/**` は外部ルーティングしない）、サービス間では mTLS・サービストークンで呼び出し元を
> 証明する。ここでのヘッダー判定は「ゲートウェイが必ずアイデンティティヘッダーを付け、サービスのポートが外に開いていない」という
> 2 つの前提の上にある（§14）。判定をパスのルール 1 箇所に集約していないことも、今回のように **兄弟エンドポイントが漏れる**
> 原因だ — 次のステップは、ゲートウェイで `/api/auth/user/{email,profile,name,id,segments}` を外部ルーティングから
> 除外することだ。

---

## 10. 🟠 ログインの総当たり攻撃が事実上無制限

ゲートウェイのレート制限は、グローバルなデフォルトフィルター 1 つだけだった:

```yaml
default-filters:
  - name: RequestRateLimiter
    args:
      redis-rate-limiter.replenishRate: 20      # 毎秒 20 回
      redis-rate-limiter.burstCapacity: 40
```

一般的な API の基準としては妥当だが、**ログインには寛容すぎる** — IP 1 つで毎分 1,200 回
パスワードを試せるということだ。しかもアカウントロックが実装されておらず（§13）、実質的に
無制限の総当たりだった。

認証前の経路（`/api/auth/public/**` など）には、はるかに強い制限を別途かけた:

```yaml
- name: RequestRateLimiter
  args:
    redis-rate-limiter.replenishRate: 1        # 毎秒 1 回、毎分 60 回
    redis-rate-limiter.burstCapacity: 10       # 瞬間的に 10 回まで
```

この経路は認証前なので、バケットのキーはクライアント IP になる。正常なユーザーはログイン・会員登録を
1 秒に何度も行わないので、これで十分だ。

### 副次的に発見: レート制限の回避が可能だった

`CustomUserKeyResolver` はバケットのキーを `User-Id` ヘッダー → クライアント IP の順に決める。ところが
ゲートウェイがそのヘッダーを **外部入力から削除していなかったため**、クライアントがリクエストごとに異なる
`User-Id` を付けて送ると **毎回新しいバケット** が作られ、レート制限が事実上無力化されていた。§11 で
追加したグローバルな `RemoveRequestHeader` がこの経路を塞ぐ。

> 🔍 **正直に書いておくこと:** レート制限のフィルターは `default-filters` にあり、`JwtAuthorization`
> はルートフィルターなので、**レート制限が JWT フィルターより先に** 実行される。つまり制限の時点ではまだ
> `User-Id` が設定されていないため、キーは **実質的に常にクライアント IP** だ。「認証ユーザー
> 単位の制限」というリゾルバーのコメントと実際の動作が異なる。現在は安全な側（IP 基準）で動作しており
> 回避も塞がれたので急ぎではないが、ユーザー単位の制限を本当に望むなら、レート制限のフィルターを
> ルートフィルターに移して JWT フィルターの後ろに置く必要がある。**今回は変えなかった** — フィルターの順序
> 変更は全ルートのトラフィック制御に影響するため、負荷テストと一緒に行うべきだ。

---

## 11. 🟠 `User-Id` ヘッダーにメールアドレスが入っていた

ゲートウェイが下流のサービスに渡すアイデンティティヘッダーはこうだった:

```java
String userId = extractUserIdFromJwt(jwt);     // 実際には getSubject() = メールアドレス
request.mutate().header("User-Id", userId)
```

**名前は `User-Id` なのに、値はメールアドレスの文字列だ。** そのため、このヘッダーを数値の id として受け取るサービスは
すべて壊れていた:

```java
// delivery-service, notification-service — どちらも私が今回の作業で書いたコードだ
@RequestHeader(value = "User-Id", required = false) Long userId
```

Spring は `"user@example.com"` を `Long` に変換できず、**400 Bad Request** を返す。
つまり配送の照会・住所変更・キャンセル・通知一覧が、**実際のゲートウェイを経由するとすべて失敗** する。単体
テストは数値を直接入れて通過していたので表に出なかった — コンポーネントそれぞれは正しく、その間の
契約が間違っている、統合テストがなければ捕まらない類のものだ。

識別子としてメールアドレスを使うこと自体も問題だ。**メールアドレスは変わりうる値** なので、ユーザーがメールアドレスを
変更すると過去のデータとのつながりが切れる。所有権判定の基準は不変の id であるべきだ。

### 直し方

JWT に不変の数値 id を入れる `uid` クレームを追加し、ゲートウェイが 3 つに分けて渡す。

```java
// JwtUtils
.subject(email)
.claim(UID_CLAIM, user.getUserId())     // 新規追加
.claim("role", role)
```

| ヘッダー | 内容 | 使う場所 |
|---|---|---|
| `User-Id` | 不変の数値ユーザー id | delivery-service, notification-service |
| `User-Email` | メールアドレス | order/cart/coupon-service（既存ドメインがメールアドレスをキーにしている） |
| `User-Role` | `ROLE_USER` / `ROLE_ADMIN` | 管理者専用エンドポイント |

`uid` のないトークン（この変更以前の発行分、OAuth 経路）は、ゲートウェイが **拒否** する。アイデンティティを確認
できないリクエストを通すよりも、再ログインを求めるほうが安全だ。

> ⚠️ **デプロイ時の注意:** この変更は、既に発行されたアクセストークンをすべて無効にする。デプロイ直後、
> ユーザーは一度ログインし直す必要がある。ゲートウェイを user-service より **先に** デプロイすると、
> その間に発行されるトークンに `uid` がなく全員がログイン不能になるため、**user-service を先に**
> デプロイする必要がある。

OAuth のログイン経路（`OAuth2SuccessHandler`）も一緒に直した。そのままにしていたら、ソーシャルログインのユーザーは
`uid` のないトークンを受け取り、ログイン直後にすべての API で 401 を受けていたはずだ。

---

## 12. 🟠 フロントエンドがロールのクレームを誤って読んでいた

3 箇所（`SignInPage`、`TokenRefresher` の初期復元と 401 時の再発行）に同じコードがコピーされており、
3 つとも同じように間違っていた:

```js
roles: decodedToken.roles ? decodedToken.roles.split(",") : []
```

バックエンドが入れるクレームは `roles`（複数形）ではなく **`role`（単数形）** である。したがって
`decodedToken.roles` は常に `undefined` で、**`user.roles` は常に空配列** だった。

これまではロールを実際にチェックするコードがなかったので、誰も気づかなかった。ところが docs/26 で
**管理者専用のルートガードを追加した瞬間**、この空配列のせいで本物の管理者まで管理画面から
追い出される。ガードを入れるなら必ず一緒に直さなければならない部分であり、今回のレビューで捕まえた。

`util/jwtUser.js` ヘルパーにまとめた。単数形/複数形、配列/カンマ区切り文字列、`ADMIN`/`ROLE_ADMIN` をすべて
受け付ける — バックエンドが表記を変えても、画面が静かに権限を失わないように。

---

## 12b. 🔴 2 段階認証をサーバーが強制していなかった（2026-09 発見、docs/54）

2 つの経路のどちらも回避できた。

| 経路 | 以前 | 回避 |
|---|---|---|
| パスワードログイン | 2FA のアカウントにも **アクセストークン（レスポンスヘッダー）と REFRESH クッキーを先に** 渡していた。コードを待っていたのはフロントだけ | パスワードさえ分かれば、レスポンスヘッダーのトークンをそのまま使える |
| ソーシャルログイン | セッションの代わりに 5 分間の保留クッキー — ところが値は **アクセストークンと同じキーで署名した普通の JWT（uid を含む）** | その値を `Authorization: Bearer` で送ればゲートウェイが通してしまう |
| コードの確認 | 回数制限なし | 6 桁 = 100 万通り、5 分以内に総なめできる（ゲートウェイのレート制限は IP ごとなので、分散すれば役に立たない） |

直したこと:

- **`PendingTwoFactor`** — 待機トークンを **アクセストークンのキーから派生した別のキー**（HMAC(secret, "2fa_pending")）で
  署名する。ゲートウェイ・chat・user-service のフィルターなど、アクセストークンを検証するすべての場所が **手を加えなくても** 署名の不一致で
  拒否する。`uid` もない。セッションを登録しない。
- **パスワードログイン**（`AuthLoginFilter`）— 2FA のアカウントなら、トークン・REFRESH なしで待機クッキーだけを設定し、
  `code=two_factor_required` で応答する。デバイスの記録（新しいデバイスの通知）は行う — 新しいデバイスでパスワードが一致したこと
  自体が知らせるべきことだ。**多重ログインの遮断（以前のセッションの切断）は行わない** — パスワードだけで他人のセッションを切れてしまう。
- **コードの確認**（`POST /api/auth/public/verify-2fa`、旧名の `/public/oauth2/verify-2fa` も受け付ける）— 間違えたらトークンごとに
  5 回まで（Redis）、超えたらそのトークンを破棄する。通過したらすぐに破棄する（1 回限り）。その後に REFRESH クッキー。
- **フロント**（`SignInPage`）— トークンを持ったまま `login()` していた経路をなくした。パスワード・ソーシャルのどちらもコードだけを送り、
  通過したら全体のリロードで REFRESH クッキーからセッションを復元する（行こうとしていた先の `redirectTo` は維持）。

ガード: `TwoFactorLoginTest`（7）— 待機トークンはアクセスキーで検証 **されない**、アクセストークンは待機トークンとして受け付けない、
5 回の制限、1 回限り、2FA アカウントのログインレスポンスにトークン・REFRESH がない。`OAuth2SuccessHandlerTest` は、待機クッキーの値が
アクセストークンの生成器から出てこないことを固定する。フロントの `SignInPage.test`。

> 実務基準: 2FA は「2 つ目の要素を通過するまでは **いかなる資格情報も発行しない**」がすべてだ。画面が待って
> くれるのはセキュリティではなく UX だ。待機状態のトークンは用途を分離し（別のキー・audience）、他の検証器が決して受け付けないように
> し、2 つ目の要素は試行回数を制限する（NIST SP 800-63B §5.2.2）。

## 確認して問題がなかったもの

レビューしたが異常がなく手を付けなかったものも書いておく。「見ていない」と「見たが問題ない」は違う。

- **SQL インジェクション** — 全サービスが Spring Data JPA のパラメーターバインディングを使っている。文字列を連結して
  クエリを作る箇所はなかった。
- **XSS** — フロントエンドに `dangerouslySetInnerHTML` の使用箇所が 1 つもない。React のデフォルトの
  エスケープに依存しており、それで十分だ。
- **アクセストークンの保存場所** — localStorage ではなくメモリ（AuthContext）にだけ置く。リフレッシュ
  トークンは HttpOnly クッキーだ。XSS でトークンを盗みにくい正しい構造であり、`util/tokenUtil.js` に
  「ここにトークン保存メソッドを置かないこと」という意図が明記されている。
- **CORS** — ゲートウェイで `allowedOrigins` を明示的なリストにしている。ワイルドカード +
  `allow-credentials: true` の組み合わせ（ブラウザが拒否し、設定によっては危険な組み合わせ）はなかった。
- **JWT シークレット** — コードにハードコードされておらず、`${JWT_SECRET_KEY}` 環境変数で注入される。
- **パスワードの保存** — ハッシュを使っている。平文での保存はなかった。
- **STOMP のデバッグログ** — 本番で認証ヘッダーを含むフレームをコンソールに残さないよう、すでに
  分岐されている。

---

## 残っている課題（今回直さなかったもの）

### 13. ✅ アカウントロックが実装されていない — **解決済み**（`03422d0`: 5 回失敗 → 15 分ロック、自動解除、`LoginAttemptService`）

> 以下は発見当時の記録である。

`User` エンティティに `accountNonLocked` フィールドがあり、`UserDetailsImpl.isAccountNonLocked()` もその
値を読む。ところが **ログイン失敗回数を数えたりロックをかけたりするコードがどこにもない。** フィールドだけが
あり、それを `false` にする主体がないので、常に `true` だ。

レート制限（§10）で総当たりの速度は大きく落としたが、分散した IP からの攻撃は依然として防げない。
失敗回数のカウンター（Redis）と、しきい値を超えたときの一時ロックが必要だ。バックエンドのロジック追加が必要なため、
今回の範囲では行わなかった。

### 14. ✅ サービス間の呼び出しに認証がない — **解決済み**（サービス間 mTLS: 内部 CA のクライアント証明書で内部呼び出しを判定、`InternalCallerFilter` — [docs/57 §16](57-remaining-priorities.md)）

> 以下は発見当時の記録である。

すべての Feign 呼び出しが「内部ネットワークからしか到達しない」という前提に依存している。§9 の修正もその前提の上に
立っている。クラスターの中に侵入されたら（他の Pod を 1 つ掌握されただけでも）、すべての内部 API が無防備だ。

きちんとやるには、サービスメッシュ（mTLS）またはサービス間トークンが必要だ。インフラの決定が絡むため
（docs/23 のインフラ選定と合わせて）別の課題として残す。

### 15. 依然として有効な以前の課題

- ~~**DB パスワードのローテーション（`ops/01`）**~~ ✅ **完了（2026-09-25）** — 漏洩した値（コネクター JSON の `product` のパスワード）は、
  本番の PostgreSQL・Redis のどちらも拒否し、どの Secret にも存在しないことを **実際にログインしてみる方法** で確認した（対照群・陽性
  対照を含む）。本番はデプロイ時に新しく作ったパスワードを使っていた。履歴スキャナー（`ops/20`）が JSON 形式を見逃して「0 件」と
  報告していた件も直した（docs/30 §6）。
- **ファイアウォールの点検（`ops/11`）** 🔴 — 露出していたホストが git 履歴に残っており、元に戻せない。
- **actuator の認証** — 今回露出範囲を減らしたが、残したエンドポイント（`refresh`、`busrefresh`、
  `prometheus`）も認証の後ろに置くのが正しい。

---

## デプロイの順序（重要）

§11 の変更のため、順序がある。

1. **user-service を先に** — `uid` クレームを入れたトークンの発行を開始する。
2. **次に gateway** — `uid` のないトークンの拒否を開始する。
3. 残りのサービス（order/cart/coupon/delivery）。

**順序を逆にすると**、ゲートウェイが先に `uid` を要求するのに user-service はまだそれを入れていないので、
**すべてのユーザーがログイン不能** になる。

デプロイ後、既存のアクセストークンはすべて無効だ — ユーザーは一度ログインし直す必要がある。短いログアウトを
受け入れる代わりに、アイデンティティを確認できないトークンが通過し続ける状況をなくす。

---

## 検証範囲

- バックエンドの全テストが通過（新規の `OrderOwnershipTest` 3 件を含む）
- `OrderOwnershipTest` は **修正を戻すと失敗** することを確認した（2 failed）
- フロントエンドのビルドが通過

⚠️ **実際のリクエストでは確認していない。** ゲートウェイを立ち上げ、別のユーザーのトークンで §1〜4 を
実際に試してみる検証が残っている。特に §11 のヘッダー契約の変更は、サービスをすべて立ち上げて
end-to-end で一度確認する必要がある — 今回のバグ自体が「単体テストは通るのに、実際のゲートウェイを
経由すると壊れる」類のものだったからだ。

---

## 付録. このレビューが見落としたもの — 決済の検証（docs/35）

このドキュメントは IDOR・権限・シークレットの露出を洗い出したが、**決済経路の信頼境界は見ていなかった。**
後に別件（PayPal シークレットの失効）を確認している最中に発見した:

> `POST /api/order/payment/paypal` が、ブラウザが送った金額・決済状態・PayPal の注文 ID を
> **何の検証もなく** そのまま Outbox に入れていた。ログインさえしていれば、決済なしで
> 注文を完了させることができた。

所有権チェックでは防げない — **自分の注文に向けて撃つ攻撃** だからだ。このレビューが
「他人のものに手が届くか」だけを問い、「自分のものについて嘘をつけるか」は問わなかった結果だ。

修正と回帰テストは [docs/35](35-payment-verification.md)。


---

## 付録 2. ソーシャルログインの PKCE と認可リクエストのストア

このレビュー（とソーシャルログインの実装）の後に出てきた 1 つの質問から始まった — **「OAuth ログインには
PKCE も適用されているか？」**。答えは **いいえ** で、確認する過程でそれよりもっと急ぎの欠陥が
もう 1 つ出てきた。

### なぜ PKCE が抜けていたのか — 「今どきデフォルトだろう」の落とし穴

`oauth2Login` を使えば最新の Spring が勝手にやってくれそうだが、そうではない。
`DefaultOAuth2AuthorizationRequestResolver` は PKCE を **パブリッククライアントにだけ** 付ける。
Spring Security 6.3.1 のバイトコードで確認した分岐:

```
54: getstatic     ClientAuthenticationMethod.NONE
58: invokevirtual ClientRegistration.getClientAuthenticationMethod
61: invokevirtual ClientAuthenticationMethod.equals
64: ifeq  76                     ← NONE でなければ PKCE を飛ばす
67: getstatic     DEFAULT_PKCE_APPLIER
```

`SocialClientRegistration` は 3 つともクライアントシークレットを使う **コンフィデンシャルクライアント** だ
（`CommonOAuth2Provider` が Google・GitHub は `CLIENT_SECRET_BASIC`、Facebook は
`CLIENT_SECRET_POST` と指定している）。そのため、あの分岐に一度も引っかからなかった。

コンフィデンシャルクライアントなので、PKCE がなくてもシークレットと `state` で防げる部分は大きい。だからこれは
🔴 ではなく 🟡 だ。ただし OAuth 2.1 と現行の Security BCP は、コンフィデンシャルクライアントにも PKCE を
推奨している — **認可コードインジェクション**（盗んだコードを攻撃者が自分のセッションに押し込む攻撃）がもう一層
防がれ、リダイレクト URI の設定ミスやプロバイダー側のオープンリダイレクトでコードが漏れた場合の
被害が減る。3 つのプロバイダーすべてがサポートしているので、有効にしない理由がない。

### 確認中に出てきたもっと急ぎのもの — 認可リクエストが HTTP セッションにあった

ソーシャルログインは 2 つのリクエストから成る。

1. `/oauth2/authorization/google` — サーバーが `state`（PKCE を有効にすれば `code_verifier` も）を
   作って **保存** し、プロバイダーへ送る
2. `/login/oauth2/code/google` — プロバイダーが戻してくると、サーバーがその値を **取り出して** 検証する

デフォルトのストア（`HttpSessionOAuth2AuthorizationRequestRepository`）は、その間の値を HTTP セッションに
入れる。ところがこのサービスは:

- **レプリカが 2 つだ**（`k8s/70-autoscaling.yaml` の minReplicas=2）。セッションのレプリケーションも
  セッションアフィニティもない。① を受けた Pod と ② を受けた Pod が異なれば、セッションには何もない。
- **`SessionCreationPolicy.STATELESS` を宣言している。** JWT でいくと言っておきながら、ソーシャル
  ログインの経路でだけサーブレットセッションが作られていた — 宣言と実際が食い違った状態。

症状は `authorization_request_not_found` によるログイン失敗だが、**確率がおよそ半分で、
再試行すればうまくいく場合があるので**「たまにうまくいかないな」としか見えない。診断するのに最悪の形だ。

> この欠陥は PKCE とは関係なく **すでに存在していた。** ただし PKCE を有効にすると、セッションに入るものが
> `state` 1 つから `code_verifier` までに増え、失敗の表面が広がる。そのため、ストアを
> 先に整理してから PKCE を有効にした。

### 何をしたか

**① PKCE を明示的に有効にする** — `SecurityConfig.pkceAuthorizationRequestResolver()`

```java
DefaultOAuth2AuthorizationRequestResolver resolver = new DefaultOAuth2AuthorizationRequestResolver(
        clientRegistrationRepository, DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
```

**② 認可リクエストを Redis に置く** — `RedisOAuth2AuthorizationRequestRepository`

`state` をキーに、TTL 10 分（デフォルト値）、取り出すときは **`GETDEL`**。Redis はすでにリフレッシュトークンに
使っているので、新しいインフラは増えない。

**なぜクッキーではないのか。** よくある代替案は、認可リクエストを丸ごとクッキーに入れることだ。動作はするが、
PKCE を有効にした今は **`code_verifier` がブラウザに出ていく。** PKCE の前提は「verifier を
知っている側だけがコードを交換できる」ことなのに、その値をクライアントに預けると保護の相当部分が
失われる（HttpOnly でも XSS・拡張機能の射程内だ）。シークレットを守れるコンフィデンシャル
クライアントなので、verifier もサーバーに置く。

**なぜ `GETDEL` なのか。** GET の後に DELETE と分けると、2 つのコールバックが同時に届いたときに両方が認可
リクエストを得てしまう窓ができる。短いが実在し、それこそがコード再利用攻撃が狙う窓だ。

**③ リクエストキャッシュを切る** — STATELESS を宣言したのに、セッションを作る場所がもう 1 つ残っていた。
未認証のリクエストが来ると、`ExceptionTranslationFilter` がエントリーポイントを呼ぶ **前に**
`HttpSessionRequestCache` で元のリクエストをセッションに保存する。つまり **401 を返すたびにセッションが
作られていた。** ところが誰もそれを読まない — ログイン成功の経路は JWT を発行し、
`OAuth2SuccessHandler` は保存されたリクエストではなく固定のフロントエンドのアドレスへ送る。
読まれない状態にはコストしかない。

### 検証

| 何を | どうやって | 結果 |
| --- | --- | --- |
| カスタマイザーを設定すると PKCE が付く | リゾルバーを実際に実行して `code_challenge`/`code_verifier` を確認 | ✅ |
| **設定しなければ付かない**（改修前の状態） | 同じリゾルバー、カスタマイザーなしで | ✅ ないことを確認 |
| `code_verifier` がシリアライズの往復で生き残る | 本物の保存/復元のコード経路（マッパーを含む） | ✅ |
| そのテストは実際に捕まえるか | **マッパーをわざと壊して再実行** | ✅ 往復テスト 2 件だけが正確に失敗 |
| Pod が異なっても引き継げる | ストアのインスタンス 2 つ + 実際の Redis 7 | 🕐 `containers` タグ — CI で |
| `GETDEL` による再利用の遮断 · TTL · 期限切れ | 実際の Redis 7 | 🕐 `containers` タグ — CI で |

Docker のない環境で作業したので、`containers` タグのテストは **この場では実行できなかった。**
パイプラインの統合ステージが実行する（docs/36）。最もリスクの高い部分（シリアライズ）はコンテナなしで
検証できるよう切り離しておき、それは通過した。

### 残っているもの

- **実際のプロバイダーとのエンドツーエンドの確認。** Google・GitHub・Facebook に実際につないでログインが
  最後まで通るかは、デプロイ後にしか分からない。`code_challenge` を送り始めたので、
  トークン交換で `invalid_grant` が出たら、まずこの変更を疑うこと。
- **Redis が落ちるとソーシャルログインができない。** 意図した動作だ（fail closed）— 保存に
  失敗したらログインを開始せずに落ちる。黙ってセッションにフォールバックすると、今直したレプリカの
  問題が見えない形で戻ってくる。
