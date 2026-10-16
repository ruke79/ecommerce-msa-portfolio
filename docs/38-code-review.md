# 38. 全体コードレビュー — バックエンド 12 モジュール · フロントエンド · CDC

**日本語** | [한국어](38-code-review.ko.md)

> 🕰️ **状態（2026-09-27）— 過去の記録。** 点検当時の記録なので状態欄がありません。相当数がその後修正されました。最新のオープン項目は [docs/54 §10](54-improvements-vs-practice.md)、事例ごとの限界は [docs/62](62-engineering-casebook.md)。

> 範囲: バックエンド全体と CDC 実装のコードレビュー。フロントエンドも楽観的更新とロールバックが抜けていて
> 直すところが多いと見て、あわせて点検した。
>
> このドキュメントは **点検結果である。** §8 に直す順序を書いた。
>
> 例外は §1.12 の 1 つだけである — `save()` が merge になって不要な select が呼ばれる部分を別途
> 確認していたところ欠陥まで露見し、その場で直した。§1.13 も同じ流れで
> 適用した。その 2 節だけが **適用済み** である。

## 0. 範囲と方法、そしてこのドキュメントをどう読むべきか

- **範囲**: Java 13 モジュール（約 38,000 行）、フロントエンド（`frontend/src`、約 16,000 行）、ルートの CDC コネクター
  設定 5 つと Outbox の経路。
- **方法**: モジュール単位に分けてコードを読み、呼び出し経路をたどった。基準は正確性の欠陥 → セキュリティ →
  効率 → 整理の順であり、「もっときれいにできる」類の印象は除外した。すでにドキュメント化されたもの（docs/27
  セキュリティレビュー、docs/37 トランザクション点検、docs/26·28 フロントエンド）は改めて書いていない。
- **検証の表記**: 下記の 🔴 項目は **すべて私が直接ファイルを開いて確認した。** 🟠 以下はレビュアーの
  報告をそのまま移したが、私が確認したものは「確認」と表示した。レビュアーが間違っていたものも 1 つ
  あり（§6）、それはライブラリのバイトコードで反証して除いた。
- **読み方**: §1 だけ読めばよい。そこにあるものは、今本番で実際に誤動作しているか、
  誰でも再現できる欠陥である。§2 以下はモジュール別の詳細である。

---

## 1. 今すぐ — 本番で実際に壊れているもの

深刻度順。同じ原因が複数のモジュールで見つかったものは 1 つにまとめた。

### 1.1 🔴 注文作成が失敗する — cart-service の契約変更に order-service が追従できなかった

| 何 | どこ |
| --- | --- |
| cart-service の `/checkout` は docs/27 の修正で `?userId=` の代わりに **`User-Email` ヘッダー** を要求する | `cart-service/.../CartController.java:123-129` |
| order-service の Feign クライアントは **まだ `?userId=`** で呼んでいる。`FeignConfig` は `Authorization` しかコピーしない | `order-service/.../client/CartServiceClient.java:18-19`, `config/FeignConfig.java:28` |

`createOrder` → cart 401 → `FeignErrorDecoder` 例外 → トランザクションのロールバック → **すべての注文作成が
500。** Saga E2E（`SagaEndToEndTest`）は cart-service を起動しないので、この経路を通らない。
docs/34 の「カート → 注文 → 決済」の受け入れ項目はまだ人が回していない。

### 1.2 🔴 決済金額の検証がクライアントの決めた値と照合している

docs/35 が「ブラウザの『決済した』を信じない」として直した検証器は、PayPal が知らせてきた金額を
**`order.total` と** 比較する（`OrderDataService.java:270`）。ところが `order.total` は注文作成時に
**リクエスト本文の `total` をそのまま保存した値** である（`:166`）。サーバーは商品一覧だけを cart-service から
読み直し、合計金額は再計算しない。

```
カート 59.98 → POST /api/order/create {"total": 1, ...}
→ orders.total = 1 → PayPal で 1 USD 決済 → verified 1 == expected 1 → 通過
```

docs/35 の ①②③④ がすべて通過する。**ユニーク制約も本物の決済なので無意味である。**
同じ箇所の 2 つ目の欠陥: `total` が `int` である（`common/.../OrderRequest.java:19`,
`order-service/.../Order.java:76`）。Jackson のデフォルト（`ACCEPT_FLOAT_AS_INT`）が小数点以下を切り捨てるので
59.98 → 59。正常な注文ごとに最大 0.99 が帳簿から消える。

### 1.3 🔴 管理者の商品・カテゴリ登録画面が空の値を送る — 7 月の修正のリグレッション

docs/28 §2 が「入力文字の消失」を直そうとして `AdminInput` の展開順序を `{...props}` →
`{...field}` に変えた（`AdminInput.jsx:22-23`、コミット `6f64410`）。Formik の `onChange` が勝つように
なったこと自体は正しい。問題は 3 つの呼び出し元が **依然として自前の `useState` を送信** していることである:

- `CreateProduct.jsx:254` `onSubmit={() => {...}}` — Formik の `values` を受け取らず、
  `product.name`（`:112`）を使う。`AdminInput` に渡した `onChange={handleChange}`（`:340`）は
  上書きされて **一度も実行されない** → `product.name` は永遠に初期値。
- `CreateCategory.jsx:38,84`、`CreateSubCategory.jsx:10,27` も同じ構造。

結果: 名前・説明・ブランドが空の商品が作られるか（slug は `product.sku` で代替される）、
カテゴリは `slugify(undefined)` で落ちて常に "Failed to add a category"。私が今回の作業で
書いた `CreateProduct.test.jsx` は **state をあらかじめ埋めて送信だけを押す** ので、これを捕まえられない —
テストは入力をタイピングすべきだった。

### 1.4 ✅ 登録確認メールが一度も送られたことがない — `AFTER_COMMIT` リスナーがトランザクションの外で呼ばれる

`RegistrationListener.onRegistrationComplete` は `@TransactionalEventListener(phase = AFTER_COMMIT)`
である（`:72`）。イベントを発行するのはコントローラーである（`AuthController.java:196`, `:247`）。コントローラーには
`@Transactional` がなく、直前の `userService.registerNewUserAccount()` は戻る時点ですでに
コミットされている。すると発行時点でアクティブなトランザクションがなく、`fallbackExecution` のデフォルト値（false）なので
Spring はリスナーを **スキップする** — DEBUG ログを 1 行残すだけで。

つまり `createVerificationTokenForUser` も、`outgoing-mail` の発行も実行されない。今は
`APP_REQUIRE_EMAIL_VERIFICATION` がオフなので登録自体はできるが、**そのスイッチをオンにした瞬間、
すべての新規アカウントが永久にロックされる。** 再送（`/resend-verification`）も同じ理由で「送信しました」を
返すだけである。

> docs/37 §5 はこのリスナーを「このリポジトリで最も模範的な箇所」に挙げた。コードの形は模範で
> 間違いない — **実行されないだけである。** そのドキュメントに訂正を付けた（§7）。

**本番で実測してから修正。** 2026-09-12、実際のクラスターで登録してみて確認した。登録は
成功するのにメールが届かず、証拠が 3 つとも同じところを指していた:

| 確認 | 結果 |
| --- | --- |
| `kafka-run-class kafka.tools.GetOffsetShell --topic outgoing-mail` | `outgoing-mail:0:0` — 発行 0 件 |
| `SELECT … FROM notification.mail_message` | 0 rows |
| user-service のログ | 発行成功も失敗もなし |

3 つ目が決定的である。リスナーは発行が成功すれば info、失敗すれば error を残すが、**どちらもない。**
実行自体がされていないのである。notification-service は正常だった — マイグレーション 3 つがすべて適用され、
`메일 발송기: smtp (실제 발송)` で起動していた。つまり消費側をいくら見ても原因は出てこない。

**直し方:** リスナーに `fallbackExecution = true`。当初の提案（§8-4）は「発行を
`registerNewUserAccount` の中に移す」だったが、見直すとそちらは正しくない。
再送の経路には使うトランザクションがそもそもないので、アノテーションを満たすためにトランザクションを
作らなければならず、登録の経路は **すでにコミットされた後に** 発行するので「コミット後にだけ送る」という保証が
すでに成立している（サービスが例外を投げれば発行の行に到達しない）。移して得るものがない。このフラグは
追加の設定なので、後で誰かが発行をトランザクションの中に移しても `AFTER_COMMIT` がそのまま適用される。

`RegistrationListenerDeliveryTest` はアノテーションを文字列で検査せず、**Spring のイベント
インフラを実際に通す。** トランザクションマネージャーを置かないので、発行時点の条件が本番と同じである。フラグを
外して回し、2 つのテストがどちらも失敗することを確認した。このリグレッションはログを残さないので、
数えるテストがなければまた静かに戻ってくる。

**その後に出てきたもの — メール認証はそもそもゲートではなかった。** 「メールが送られなかったならその登録は
無効になるべきではないか」という問いをたどっていて発見した。メールを直しても認証は何も防げない。
2 重に無力化されていた:

```java
// UserDetailsImpl — enabled を保持すらせずハードコーディング
@Override public boolean isEnabled() { return true; }

// CustomAuthenticationProvider — AuthenticationProvider を直接実装。
// DaoAuthenticationProvider の事前検査（isEnabled）がまったく走らない。
if (passwordEncoder.matches(password, userDetails.getPassword())) { … 認証成功 … }
```

つまり `APP_REQUIRE_EMAIL_VERIFICATION=true` でオンにしてアカウントを `enabled=false` にしても、**そのまま
ログインできる。** 他人のメールアドレスで登録しても使えるということである。

直し方: `UserDetailsImpl` がエンティティの値を保持して返し、プロバイダーが **パスワード検査の後に**
`DisabledException` を投げる。前に置かなかった理由は、パスワードを知らない人に「このアドレスは
登録済みで未認証」と教えないためである — 登録有無の確認ツールになってはならない。
`AuthLoginFilter` はこの例外のメッセージをそのまま出す。一般的な文言を返すと、ユーザーはパスワードを
何度も打ち直してロックされてしまうが、本当に必要なのはメールの確認である。

`DisabledAccountLoginTest` 6 つ。2 重のそれぞれを元に戻して 3 つ・2 つが失敗することを確認した —
どちらか一方だけでもゲートが消えるので、両方を別々に数える。

残りの 3 つ（`isAccountNonLocked`・`isAccountNonExpired`・`isCredentialsNonExpired`）は **意図的に**
true を維持する。ロックは `lockExpiresAt` と `LoginAttemptService` が判定し、期限切れは日付カラムだけが
あってそれを検査するコードがない。ブール値だけを強制すると「期限切れなのに通過」となり、かえって守られている
ように見える。判定ロジックを先に作ってから付けること — コードにその理由を書いておいた。

**整理スケジューラー — `UnverifiedAccountCleanup`。** 期限切れトークンを消すリポジトリメソッドが
`VerificationToken`・`PasswordResetToken` の両方にあったが、**誰も呼んでいなかった。** ゲートをオンにすると
リンクを押さなかったアカウントが無効のまま溜まり、そのアカウントは何の機能も果たさないのに **そのメール
アドレスを永久に占有する** — 同じアドレスで再度登録すると「すでに存在する」が返ってくるだけである。

判断の根拠は明快である。認証に成功すると `validateAndGetUser` がアカウントを有効化し、**トークンを消す。**
だから「無効なアカウント + 残っているトークン」なら一度も認証していないということである。再送は同じトークン行の
有効期限を更新するので、最近再送を依頼した人は猶予の計算から自動的に外れる。

安全装置を 4 つ重ねた。元に戻せない作業だからである。

| 装置 | 内容 |
| --- | --- |
| デフォルトでオフ | `delete-accounts` はデフォルト false。オフの間は「消していたら何件」だけをログに残し、オンにする前に影響範囲を見られるようにする |
| 猶予期間 | トークンの期限切れ後、デフォルトでさらに 7 日待つ。期限切れ直後に消すと、再送で復旧しようとしていたユーザーを切り捨ててしまう |
| 行単位のトランザクション | 1 つが失敗しても残りは進む。照会と削除の間に認証を済ませていれば、再確認で除外される |
| 外部キーに委ねる | 注文・レビュー・配送先・ウィッシュリストがあれば DB が止める。その一覧をコードで改めて列挙しないので、後で参照が増えてもこのコードはそのまま安全である |

期限切れトークンの整理には落とし穴が 1 つある。**確認トークンをすべて消してはならない** — 無効なアカウントのトークンは
上の判定の唯一の根拠なので、猶予期間が終わる前に消すとそのアカウントは永遠に候補に上がら
ない。そこで `deleteExpiredOfEnabledUsers` で **すでに有効化されたアカウントのものだけ** を消す。パスワード
再設定トークンは判定に使われないので、期限切れのものをすべて消す。

`UnverifiedAccountCleanupTest` 9 つ。安全装置 3 つをそれぞれ元に戻して、すべて失敗することを確認した。
JPQL 自体は実際の DB がないと検証できない — このテストが固定するのはスケジューラーの判断と順序である。

**オンにする順序:** メールが実際に届くことを確認 → `require-email-verification=true` → 数日後にログの
「정리 대상 N건」（整理対象 N 件）が予想と合っているか確認 → `delete-accounts=true`。ゲートがオフだとすべてのアカウントが
有効な状態で作られるので、削除対象そのものが生じない。

### 1.5 🔴 割引価格の計算式が間違っている — バックエンド 2 か所、フロントはすでに修正済み

```java
priceBefore.subtract(priceBefore.divide(discount, 2, HALF_UP))   // 定価 − 定価/割引率
```

`catalog-service/.../ProductService.java:757`、`cart-service/.../CartService.java:445`（そして
`:462-473`）。`discount` はパーセントである（フロントの `Infos.jsx` が `-{discount}%` と表示し、
`util/pricing.js:21` は `p * (1 - d/100)` で計算する）。

定価 10,000 / 50% 割引 → 画面では 5,000、カートでは **9,800**。割引率が大きいほど実際の割引は
小さくなる（70% → 1.4%）。10% のときだけ偶然合う。cart-service が catalog のこの値を読み直して
合計金額を作るので、**カート・注文の金額が広告価格と異なる。**

### 1.6 🔴 他人のデータに手が届く経路 — docs/27 以降も残っているもの

| 経路 | 何がないか | 位置 |
| --- | --- | --- |
| `DELETE /api/user/profile/delete/{addressId}` | 住所の所有権検査がない — 他人の配送先を削除 | `AddressService.java:154-173` |
| `GET /api/user/profile/select/{addressId}` | `findByActive(true)` が **グローバル** クエリ + `Optional` — ユーザーが 2 人以上なら `IncorrectResultSize` で常時失敗、1 人なら他人のアクティブな住所を無効にする | `AddressService.java:92-100`, `ShippingAddressRepository.java:34` |
| `GET /api/auth/user/profile/{userId}`, `/user/email/{userId}`, `/user/name?userName=` | 本人確認がない — メールアドレス・実名・2FA の有無・デフォルトの決済手段を列挙 | `AuthController.java:299-344` |
| ~~`POST /api/cart/savecart`~~ ✅ | ~~身元を **リクエスト本文の email** から読む — 他人のカートを消して上書きする~~ → 身元は `User-Id`（トークンの uid）、本文の DTO に email フィールドなし（2026-09, docs/54 §1.1） | `CartController.saveCart` · `CartIdentityContractTest` |
| `POST /api/coupon/applycoupon` | 身元を本文の `userEmail` から、金額を本文の `totalPrice` から — 他人のクーポンの列挙 + 他人のカート合計の汚染 | `CouponController.java:61-66`, `CouponService.java:46-70` |
| `POST /api/order/payment/paypal` | 注文の所有権検査がない、`customerId` を本文の `userEmail` として解釈 | `OrderDataService.java:247-258`, `:303` |
| `PUT /api/product/rating` | ログインさえすれば **どの商品の評価でも上書きできる**（0〜5 の範囲・購入有無・重複の検査なし）。`AdminWriteInterceptor` の明示的な例外経路である | `ProductController.java:274-279`, `ProductRepository.java:52-54` |

docs/27 は「他人のものに手が届くか」を問うたが、照会・削除経路の一部と **書き込み経路の身元の
出どころ** を見落とした。パターンは 1 つである — ゲートウェイが検証してくれた `User-Id`/`User-Email` ヘッダーが
あるのに、本文/パラメーターの値を使っている。

### 1.7 🔴 2FA の認証番号がデフォルトの送信器の INFO ログにそのまま出力される

`LoggingSmsSender.java:33` — `body={}` に `"[쇼핑몰] 인증번호 483920 …"` の全文。すぐ隣の
`LoggingMailSender.java:35-41` は本文を除いて長さだけを出力する（リンクが入っているため）。ルールは
分かっていて、SMS 側だけが抜けていた。デフォルト値が `logging` なので（`SmsConfig.java:27`）、`SMS_PROVIDER` を
明示していない環境では本番でもこの経路である。ログの閲覧 = 他人の 2FA の通過。

同じサービスの隣接する欠陥: 発行制限が **番号ごと** しかない（`VerificationService.java:104,110` の
キーが `sms:cooldown:{e164}`, `sms:daily:{e164}`）。ログインしたアカウント 1 つが番号を変えながら
送れば、ゲートウェイの 20 req/s まで実際に送信される。料金は私たちが払う。pepper のデフォルト値が空
文字列であること（`:69`）も同じファイルである — 空なら `SHA-256(code)` なので、100 万個の表ですぐに逆算される。

> **2026-09 修正。** 送信器が本文を残さない。実際のログをキャプチャする `LoggingSmsSenderTest` が固定する — docs/51 §1。

### 1.8 🔴 `delivery-status` トピックを誰も読んでいない

`DeliveryStatusPublisher` が発行するトピックのコンシューマーがリポジトリのどこにもない。クラスのコメントは
notification・order の 2 つのコンシューマーを明記しているが、どちらもそのトピックを購読していない。結果:
`SHIPPING_UPDATED` 通知は一度も生成されず、docs/25 §0 がこのサービスを作った理由として
挙げた「注文が PAID で止まる」がそのまま残っている。

### 1.9 🔴 商品登録がサイズを SKU に結び付けない

```java
request.getSizes().forEach(size -> ProductSizeMapper.toEntity(size).setSku(skuProject)); // 捨てられるインスタンス
skuProject.setSizes(request.getSizes().stream().map(ProductSizeMapper::toEntity).collect(...)); // sku == null
```

`ProductService.java:662-665`（`loadSku` `:585-602` も同様）。`sizes` は `mappedBy` の逆方向なので
FK は `ProductSize.sku` から来るが、それが null である。`product_size.skuproduct_id` は
nullable（V5）→ **孤児行が静かに INSERT** される。`details`/`questions` は `product_id not null`
なので制約違反で 500。単体テストがすべて Mockito なので捕まえられない。1.3 が直って実際に登録が
できるようになると、すぐに露見する。

### 1.10 🔴 決済後の画面が「未決済」のまま残り、二重請求を誘発する

`pages/order.jsx:22,49,54` — 決済成功時に更新する `order` state は **どこにもレンダリングされて
いない。** 画面は react-query の `orderData` で描画するが、`invalidateQueries` も `refetch` も
ない。ユーザーは `Payment Status ✗` と PayPal ボタンをそのまま見て、もう一度決済する。サーバーは同じ
`paypalOrderId` の重複しか無視しないので（`OrderDataService.java:285-288`）、**新しい決済は受け付けて Outbox に
2 回目として発行する。** 注文一覧の Paid バッジは条件が逆である（`orders.jsx:80` —
`=== "UNPAID"` のときに緑のチェック）。

### 1.11 🔴 コントローラーの状態フラグによる分岐 — 3 つが誤動作する

コントローラー内でブール値・文字列の状態で分岐する箇所を全数調べた（5 か所）。そのうち 3 つが欠陥である。

**① カテゴリ作成が永久にブロックされている** — `CategoryController.java:63`

```java
boolean exist = categoryService.findCategory(request.getName());
if (!exist) { ...作成... } else { return 400 CATEGORY_IS_EXISTED; }
```

`findCategory` は `null != categoryRepository.findByCategoryName(name)` だが、その戻り値の型が
**`Optional<Category>`** である（`CategoryRepository.java:24`）。`Optional` は決して null にならないので
**常に true** → `!exist` は常に false → 何を入れても「すでに存在します」。

すぐ下のサブカテゴリ（`:96`）は **問題ない** — `findBySubcategoryNameAndCategory_CategoryId` の
戻り値の型が `Subcategory`（`SubcategoryRepository.java:31`）なので `null !=` の比較が成立する。同じ
ファイルに並んでいる 2 つのメソッドの一方は正しく、一方は間違っていたので目立たなかった。

**② `/api/auth/public/verify-2fa-login` は一度も動作したことがない** — `AuthController.java:495-507`

```java
String username = jwtUtils.getIdFromJwtToken(jwtToken);
User user = userService.findByUsername(username);
boolean isValid = userService.validate2FACode(user.getUserId(), code);   // ← NPE
```

`JwtUtils` のトークン生成 4 か所はすべて `.subject(email)` だが（`:113,132,160,188`）、
`findByUsername` はリポジトリのコメントが明かしているとおり **実名で検索** する（`UserRepository.java:29`
「실명으로 검색」（実名で検索））。メールアドレスで実名を探すので null になり、次の行で NPE → **500**。つまり通常の
2FA ログインの完結経路が最初から塞がっている。

おまけに、この **公開** エンドポイントにはコードの試行回数制限がなく、`getIdFromJwtToken` は
`ExpiredJwtException` を捕まえて期限切れトークンの subject も返す（§2.1 #5）。

**③ 決済エンドポイントに注文状態のガードがない** — `OrderDataService.java:247-300`

重複の遮断が **同じ `paypalOrderId`** に対するユニーク制約だけである。注文がすでに `PAID` かどうかを
見ない。新しい PayPal 注文で再度決済すると、サーバーが受け付けて Outbox に 2 回目の決済を
発行する。§1.10（決済後の画面が「未決済」のまま残り再決済を誘発）と噛み合うと、**誘発する側と
止めない側が同時に成立する。** 2 つのうちサーバーのガードがないこちらのほうがより根本的である。

**逆に正しい 2 つ** — 参考用:

- `NotificationController.java:145` — `preference.enableTwoFactor(...)` が `phoneVerified`
  でなければ false を返し、コントローラーは **409** を返す（`NotificationPreference.java:137-144`）。
  判断はドメインオブジェクトが行い、コントローラーは結果を移すだけである。
- 品切れガード — `ProductService.java:740` が、cart-service がカートに入れる直前に呼ぶ
  `getCartProductInfo` で `SOLD_OUT` なら null を返す。`DELETED` は
  `@SQLRestriction` が照会段階で除外する。

**共通の原因**: ①② はコントローラーが **ブール値/文字列に平たくされた結果** を受け取って分岐するが、その値を
作る側の契約（`Optional` なのかエンティティなのか、subject がメールアドレスなのか実名なのか）が食い違っており、
コンパイラーは何も言わない。`findCategory` が `Optional` をそのまま返していたか、
`verify-2fa-login` が `findByEmail` を使っていれば、どちらも起きなかった。

### 1.12 🔴 `save()` が `persist` ではなく `merge` に行くエンティティ 8 つ — **適用済み**

`SimpleJpaRepository.save()` は 1 行で分岐する。

```java
if (entityInformation.isNew(entity)) em.persist(entity);
else                                 return em.merge(entity);
```

そして `isNew()` のデフォルトの判定は **「`@Id` が null か」** である。だから id をコードが直接
割り当てるエンティティ — コンストラクターの `UUID.randomUUID()`、Kafka から来た `eventId`、user-service から来た
`userId` — は save() 時点で id がすでに埋まっており、それだけで「既存の行」と判定されて `merge()` に
行く。**merge は対象を見つけるためにまず SELECT を投げる。**

実際に動かして確認した（一時的な PostgreSQL + 実際の `SimpleJpaRepository`）:

| マッピング | `isNew()` | 新規 1 件の save() が出す SQL |
| --- | --- | --- |
| 割り当て ID、`@Version` なし | `false` → `merge()` | **`select … where id=?`** + `insert` |
| 割り当て ID + ラッパー `@Version`（order-service `Customer`） | `true` → `persist()` | `insert` |
| `@Tsid`（残りの大多数） | `true` → `persist()` | `insert` |
| `Persistable` の実装（修正案） | `true` → `persist()` | `insert` |

`@Tsid` が安全な理由は、`TsidGenerator` が `IdentifierGenerator` なので save() の直前まで id が
依然として null だからである（実行して確認 — save() 前は `id = null`、後は `id = 8846…`）。

**該当するエンティティ 8 つ。** すべて Outbox・冪等性台帳・イベント消費の経路にある — つまり注文・決済
1 件ごとに掛け算される箇所である。

| エンティティ | 実際の save 経路 |
| --- | --- |
| `order-service/.../PaymentOutboxEvent` | `PaymentOutboxHelper.java:34` — 注文ごと |
| `pay-service/.../OrderOutboxEvent` | `OrderOutboxHelper.java:34` — 決済ごと |
| `catalog-service/.../ProcessedEvent` | `ProductUpdateMessageListener.java:45` |
| `coupon-service/.../ProcessedEvent` | `PaymentResponseMessageListener.java:48` |
| `chat-service/.../ChatMember` | `UserMessageListener.java:27` |
| `cart-service/.../OutboxEvent` | なし — Outbox 全体が未使用（§下記） |
| `user-service/.../OutboxEvent` | なし — `UserServiceImpl.java:549-550` がコメントアウト |
| `delivery-service/.../Carrier` | なし — 照会専用、Flyway が埋める |

#### 性能より悪いこと — 冪等性ガードが無力化されていた

`merge` は同じ id が **すでにコミットされていれば、PK 衝突の代わりに静かに UPDATE を出す。** これも
実行して確認した — T1 がコミットした後に T2 が同じ id で save() すると、例外なしで `update` が
出てコミットされる。

そのため「挿入が失敗してくれること」に頼っていたコード 3 つがすべて成立していなかった。

- `ProductUpdateMessageListener.java:43-44` のコメント: *「동시 중복(희귀)은 PK 충돌로 전체 롤백 →
  재전달 시 existsById 로 스킵된다.」*（同時の重複（まれ）は PK 衝突で全体ロールバック → 再配信時に existsById でスキップされる。）— 衝突が起きないのでロールバックもない。`existsById` を
  同時に通過した 2 つのコンシューマーがあれば、**在庫が 2 回引かれ、台帳は UPDATE で上書きされる。**
- coupon-service も同じ構造 — クーポンの使用が 2 回反映される。
- `UserMessageListener.java:42` の `catch (DataIntegrityViolationException)` は **一度も
  実行されたことがない。** 再配信された user-created イベントが既存会員の `nickname`・`imageUrl` を
  静かに上書きしうる状態だった。

3 か所とも `existsById` の単独検査（先に読んで後で書く構造）なので、並行性に対してはもともと防げず、
PK 衝突が最後の防御線だった。まさにその防御線だけが抜けていた。

#### 修正

8 つのエンティティすべてに `Persistable<ID>` を実装させた。

```java
@Transient
private boolean persisted;              // デフォルト値 false = 新しいもの

@Override public boolean isNew() { return !persisted; }

@PostPersist @PostLoad
private void markPersisted() { this.persisted = true; }
```

フラグを `isNew = true` ではなく **`persisted = false` の方向** に反転させたのには理由がある。
`ChatMember` は `@SuperBuilder` を、残りは `@NoArgsConstructor` を使うが、Lombok が
`@Builder.Default` のないフィールド初期化をどう扱うかによって値が変わる。デフォルト値 `false`
がそのまま「新しいもの」であれば、どちらに転んでも安全である。実際にコンパイルされた `ChatMember` で確認した —
`ChatMember.builder()…build().isNew() == true`。

`@PostLoad` が核心である。これがないと読み込んだエンティティも `isNew()=true` のまま残り、Outbox
リレーの状態変更（`claim → setOutboxStatus → save`）が UPDATE の代わりに INSERT を試みて、**PK
衝突でリレーが丸ごと死ぬ。** この修正が生みうる最悪のリグレッションなので、両方の経路を
動かして確認した（新規 → `insert` のみ、読み込んだもの → `update`）。

#### 戻ってこないように

`common/src/test/.../EntityPersistenceGuardrailTest.java` を新設した。リポジトリのすべての
`@Entity` を走査し（継承の連鎖をたどって上がりながら）、`@GeneratedValue` · `@Tsid` · ラッパー `@Version` ·
`Persistable` のいずれもないエンティティを捕まえ、`Persistable` を実装していれば `@PostLoad` が
あるかどうかもあわせて見る。どちらもわざと元に戻して、まさにそのファイル名を指して壊れることを
確認した。

ここで **既存のガードレールの穴が 1 つ露見した。** このテストも `ConfigurationGuardrailTest`
も *他のモジュールの* ファイルを読むが、それらのファイルは `:common:test` の宣言された入力ではない。そのため
common 自身が変わらなければ Gradle が UP-TO-DATE としてスキップする — **ガードレールが守るべきまさにその
変更で実行されない。** 実際に他のモジュールのエンティティを元に戻して回したところ、通過して
しまった。`common/build.gradle` の test タスクに `outputs.upToDateWhen { false }` を入れて
塞いだ。

#### おまけで露見したこと — cart-service の Outbox が丸ごと空である

`cart-service/.../outbox/` に `OutboxEvent`・`OutboxEventRepository`・`OutboxStatus`・
`OutboxHelper`・`OutboxScheduler` がすべてあるが、**`OutboxHelper` はフィールド 2 つだけで、
`OutboxScheduler` は 5 行の空の殻である。** パッケージの外から参照するコードもない。つまり
cart-service には Outbox がない。user-service 側も `UserServiceImpl.java:549-550` で
コメントアウトされていて同様である。このドキュメントの §5（Outbox/CDC）とあわせて見る必要がある。


### 1.13 🟡 リポジトリの整理 · SKU のロック範囲 · スレッドプール — **適用済み**

3 つを 1 コミットに入れた。3 つとも「長い名前」や「プールを使おう」といった印象ではなく、実測が根拠である。

#### 未使用の派生クエリ 19 個の削除

名前が 16 文字以上の派生クエリのうち、テストまで含めてどこからも呼ばれていないものが 20 個あった。そのうち
最も長い `findBySizesPriceBetweenAndSizesSizeAndColorColor`（48 文字、引数 4 つ）は 4 行下の
native クエリが同じことをしており、`ProductSizeRepository.findBySkuproductIdAndSize` は
`nativeQuery=true` なのに本文が JPQL のエンティティ名で、`@Param("skuId")` と `:skuid` が食い違って
いて **呼ばれたときに壊れる** ものだった。`findByIdWithPessimisticLock` は名前に反して `@Lock` が
コメントアウトされたサンプルだった。1 つは残した — `NotificationPreferenceRepository.findByPhoneE164AndPhoneVerifiedTrue`
は §2.5 #11 が「宣言だけあって検査が抜けている」と指摘したまさにそのメソッドなので、消すと直す場所がなくなる。

#### `findByProductProductIdAndSizesSizeAndColorColorId` → `findForStockUpdate`（JPQL + `PESSIMISTIC_WRITE`）

まず **訂正**。先に「コレクション join + `Optional` なので、サイズ行が重複すると
`IncorrectResultSizeDataAccessException`」と書いたが、実際のカタログエンティティで動かしてみると
**間違っていた。** DB は 2 行を返すが、Hibernate 6 がルートエンティティを重複排除するので、
`getSingleResult()` も例外を出さない（スカラーの選択は 2 行のまま — 対照確認済み）。

変える本当の理由は **ロックの範囲** である。原作者がコメントで残した `PESSIMISTIC_WRITE` はオンにすべきである
（`sold` は読んで・足して・書く値）。ところが join の形に FOR UPDATE を付けると、PostgreSQL は FROM の
**2 つのテーブルの行をすべて** ロックする。実測: join+FOR UPDATE を握った T1 の下で、T2 の `product_size`
UPDATE → `lock timeout`; `exists` の形 → 通過。同じ Saga の次の段階がまさにその
`updateProductSize_Qty`（数量の差し引き）なので、join だったら自分の Saga が自分のロックに阻まれていただろう。
`distinct` で迂回する道はない — PostgreSQL は `DISTINCT … FOR UPDATE` を拒否する。

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("""
       select sk from ProductSku sk
        where sk.product.productId = :productId
          and sk.color.colorId = :colorId
          and exists (select 1 from ProductSize s where s.sku = sk and s.size = :size)
       """)
```

生成 SQL の確認: `from product.product_sku ps1_0 where … and exists(select 1 from product.product_size …) for no key update`
— `product.productId`/`color.colorId` は FK カラムに解決されるので join がない。

#### スレッドプール — あるべき場所 2 つ、あっても答えにならない場所 2 つ

| | どこ | 何 | 措置 |
| --- | --- | --- | --- |
| 🟠 | notification-service | `@Scheduled` 3 つ（SMS 5 秒・メール 5 秒・スクラブ 1 時間）が **デフォルトのスケジューラースレッド 1 つ** を共有している（`TaskSchedulingProperties.Pool.size` のデフォルトは 1 — バイトコードで確認）。SMS のバッチがゲートウェイのタイムアウトにかかっている間、メールは 1 通も送られない | `spring.task.scheduling.pool.size: 3`。読み込まれるかどうかは `SchedulerPoolBindingTest` が固定（キーを間違えると壊れることを確認）。異なる作業が重なっても安全 — どちらも `SKIP LOCKED` で自分の分を先取りする |
| 🟠 | user-service `NotificationService:37` | `Executors.newSingleThreadExecutor()` フィールド — **スレッド 1 つが接続したすべてのブラウザへの SSE 送信** を担当。`emitter.send()` はブロッキングのソケット書き込みなので、遅いクライアント 1 つが全体を行列に並ばせる。キューは無制限、終了フックなし、名前なし | `AsyncSseConfig`（2–4 スレッド、キュー 200、満杯なら **破棄してログ**）+ `SseDispatcher` の `@Async("sseExecutor")`。`CallerRunsPolicy` は誤った選択 — 呼び出し元が Redis の購読スレッドなので、それが詰まると Pod の通知受信が止まる |
| 🟡 | cart-service `CartService` — `loadCart`・`updateCart`・`saveCart` | カートの項目ごとに `getProductInfo` Feign を **直列** に呼び、`loadCart` はそれに加えて項目ごとの `getCartProductInfo` まで（N 項目 = 2N 往復） | **プールは答えではない。** 並列化すると `FeignConfig` の `Authorization` のコピーがリクエストスレッドのコンテキストに縛られているため、ワーカーでは抜け落ちる。代わりに catalog にバッチを 2 つ — `GET /api/products/info?ids=…` と `GET /api/cart/info?items=1:0:0&…`（キー `productId:style:sizeIndex`、上限 100、見つからない項目は抜けるだけで 200）。3 つの経路がそれぞれ 1 回、`loadCart` は 2 回に。2 つ目が **GET である理由**: 最初は POST+JSON で作ったが `@WebMvcTest` で 401 — `AdminWriteInterceptor` が POST/PUT/DELETE にゲートウェイの `User-Id` を要求し、サービス間の Feign 呼び出しにはそのヘッダーがない。本番でもまったく同じように阻まれていただろう。読み取りなので GET が正しくもある。`CartServiceBatchLookupTest` が **呼び出し回数** を数える（項目 3 つにバッチ 1+1 回、単件 0 回、キー `[1:0:0, 1:0:1, 2:0:0]`）。単件に戻してもコンパイルも機能もそのままなので、数えなければ見えない |
| 🟡 | delivery `TrackingPoller`、notification `SmsDispatcher` | 外部 HTTP を **ロックしたトランザクションの中で直列に** 呼ぶ（リポジトリのコメントがすでに認めている: 「外部呼び出しがバッチサイズ分だけ直列につながり、トランザクションが長くなる」） | プールで並列化するには claim(tx) → 呼び出し(tx の外、プール) → 反映(tx) に分割する必要がある — 永続化コンテキストはスレッドに縛られている。構造変更なので未適用。今はバッチサイズの上限で持ちこたえている |

#### 色の照会も同じ形だった — order-service · cart-service

order-service の `CatalogServiceClient.getCartProductInfo` は **宣言だけあって呼び出しがなかった**（削除した）。
そのサービスが実際に項目ごとに呼んでいたのは `getColorInfo` である — `OrderService.convertToOrderDto` が注文
商品ごとに 1 回ずつ、注文一覧 1 ページ（注文 10 件 × 商品 3 つ）なら Feign **30 回**。cart-service の
`convertCartToDto`（キャッシュ更新 4 か所）も項目ごとに同じ呼び出しだった。

catalog に `GET /api/colors/info?ids=…` を設け、order は **ページ単位** で（`OrderService.fetchColors`
→ `convertToOrderDto(order, colors)`）、cart はカート単位で 1 回受け取る。見つからない色は以前と同じく
null である。`OrderColorBatchTest` がページあたり 1 回・単件 0 回を、`CartServiceBatchLookupTest.getCart_…` が
カートあたり 1 回を数える。`OrderMapper` は `convertToOrderDto` と同じことをする重複で、誰も呼んでいない
Bean だった（§2.3）— 項目ごとの `getColorInfo` を残す最後の場所だったので削除した。

#### StreamingResponseBody — 付ける場所がなかった、代わりに 2 つ

レスポンスをストリーミングするに値する場所（ファイルダウンロード・エクスポート・大きな一覧の生成）をコントローラー全体で探した。
**ない。** 画像は S3 の URL で出ていき、CSV は Spring Batch が *読むだけ* で、SSE はすでに
`SseEmitter` である。唯一大きなレスポンスは `SearchController` の `/{entity}/all` 8 つ（ES `findAll()`
の無制限ダンプ、§2.2 #9 · docs/06 CAT-6）だが、これは **ストリーミングではなく削除が答えである** — フロントエンド・
他のサービス・テストのどこにも呼び出し元がない。削除した（リポジトリのフィールド 7 つと一緒に）。

ストリーミングが実際に抜けていたのは **リクエスト側** だった。`ImageService`（catalog・user の両方）が
`IOUtils.toByteArray(file.getInputStream())` でアップロードファイルを丸ごとヒープに載せていた — S3 SDK の
Content-Length のためだが、その値は `MultipartFile.getSize()` がすでに知っている（Tomcat が受信しながら一時
ファイルに書いておく）。`file.getInputStream()` をそのまま渡すように変えた。商品登録は色ごとに画像を
何枚も一度にアップロードするので、その分の `byte[]` が 1 リクエストの中で同時に生きていたのがなくなる。
`encodeFileToBase64`（`ProductService:721`）は Base64 の特性上全体が必要なので、そのままである。

Kafka リスナーの `concurrency` は全モジュールでデフォルトの 1 である。パーティション数を増やしたトピックがあれば、そのときに
一緒に上げる値であって、今の欠陥ではない。


### 1.14 🟡 フロントエンドのメモリリーク点検 — 2 つ修正、残りはクリーン

> 範囲: フロントエンドでメモリリークが起こりうる箇所の点検。

購読・タイマー・リスナー・オブザーバー・`createObjectURL`・グローバルキャッシュ・アンマウント後の setState を `frontend/src`
の 192 ファイルで調べた。大きなものは先の作業ですでに閉じられていた — SSE（`util/Notification.js`）は
新しい接続の前に以前の接続を閉じ、アンマウント時にタイマー・接続を整理しており、STOMP（`util/stompProvider.js`）は
`deactivate()` と購読ごとの `unsubscribe` を返し、`useTokenRefresh` の axios インターセプター 2 つは
クリーンアップで `eject` される。`IntersectionObserver`・ポーリングの `setInterval`・`document` リスナーもすべて対が
揃っている。`AuthContext` の `login`/`logout` が `useCallback` なので、SSE の `connect` がレンダリングのたびに
再生成されることもない。

**直した 2 つ**

| | どこ | 何 |
| --- | --- | --- |
| 🟡 | `ChatArea.jsx` `useLayoutEffect` | クリーンアップ関数が `messageRef.current.removeEventListener` を呼ぶが、ルームの切り替え・アンマウントの時点では `current` が null か別の要素なので、**見当違いの対象に呼び出して catch で握りつぶしていた** — 元の要素にはリスナーが残る。この effect は `messages` が変わるたびに再実行されるので、残ったリスナーがメッセージの数だけ積み上がり、それぞれがその時点の `messages` 配列をクロージャで握る。付けた要素を `el` で捕まえて同じ要素から外すようにし、React が検知できない `messageRef.current` を deps から外した |
| 🟡 | `ChatFooter.jsx` `handleImg` | `URL.createObjectURL` だけがあって `revokeObjectURL` がリポジトリのどこにもない — ブラウザがファイルを握り続ける。添付 UI がコメントアウトされて眠っている経路だが、復活させるとリークも一緒に復活するので、`img.url` が変わるかアンマウントされるときに revoke する effect を入れた |

**リークではないが指摘しておくこと**

- `pages/admin/Index.jsx:71,84,108`、`components/admin/ProductColors.jsx:11` — 非同期リクエストの後に
  setState するが、アンマウントのガードがない。React 18 は警告せず、レスポンスが来ても閉じたコンポーネントの
  setState は no-op なので **リークではない**（レスポンスが来るまでクロージャが生きているだけ）。管理者画面なので
  そのままにする。
- `ChatArea.jsx:45` の `let prevScrollHeight` がモジュールスコープである — インスタンスが 1 つなので今は問題ないが、
  ルームを 2 つ開くと共有される。
- `pages/browse.jsx:243` はスクロールイベントのたびに `setScrollY` でページ全体を再描画していた — リークでは
  なく性能の問題。**直した**: rAF でフレームあたり 1 回にまとめ、値はピクセルではなく「ヘッダーを越えたか」の
  判定が変わったときだけ保存する（それが唯一の用途なので）。スクロール中の大半のフレームで setState が
  まったく起きない。`ChatArea.jsx:177` の `.current` 漏れの誤記も **直した**。
- react-query のキャッシュはデフォルトの `gcTime` が 5 分なので、`room_messages/<id>+cursor` の項目がページごとにできても
  有限である。`ChatArea` の `messages` 状態はルームに留まっている間ずっと増え続ける — セッション単位なのでそのままにするが、長い
  ルームなら上限を設ける価値がある。


### 1.15 🟠 Kafka プロデューサーのキー・トピック・パーティション — キーがほとんどない、パーティションはすべて 1 つ

> 点検の問い: Kafka プロデューサーの key・topic・partition の関係に問題はないか？

**今は問題が表に出ておらず、パーティションを増やした瞬間に表に出る。** 3 つの関係はこうである。

| | 事実 | 根拠 |
| --- | --- | --- |
| パーティション | 主要トピック（`order` `payment` `product-update` `user-created` `cart-*` `coupon-*` `chat` `outgoing-mail` `delivery-status` …）は **誰も作らない。** ブローカーの自動作成で `KAFKA_NUM_PARTITIONS` がないので、cp-kafka のデフォルト値 **1**。明示的に作るのは `.DLT` 9 種だけで、それは **3** である | `k8s/90-dev-infra.yaml:170-185`（パーティション設定なし）、`ops/02-create-dlt-topics.sh:35,47`、`ops/13:309-312` |
| キー | 発行 12 か所のうちキーを載せるのは **3 か所**（`DeliveryStatusPublisher` orderId、`TokenRequestKafkaPublisher`、`OutgoingMailProducer` toEmail）。Saga の中核トピック（`order` `payment` `coupon-use` `coupon-rollback` `cart-empty` `cart-rollback` `product-update` `user-created`）と **`chat` はすべてキーなし** | 上記の `kafkaTemplate.send` の一覧 |
| コンシューマー | `concurrency` はすべてデフォルトの 1 | §1.13 |

パーティションが 1 つなので、キーなしが **まだ** 順序を壊していない — 1 つのパーティションの中では発行順序がそのまま消費
順序である。しかしこれは偶然の安全である。誰かがスループットのために `order` を 3 パーティションにすると（DLT は
すでに 3 なのでそうしたくなる誘惑は大きい）、キーのないレコードはスティッキーパーティショナーがバッチ単位でばらまき、
同じ注文の決済応答とロールバックが別のパーティションに入り、**コンシューマーがロールバックを先に見ることがありうる。** `chat` は
もっと直接的である — 同じルームのメッセージが散らばると画面に入り乱れて届く。パーティションを増やした人は、キーを
漏らした発行コードを見たことがない。

**適用**: 9 か所にキーを載せた。パーティションが 1 つの今は動作が変わらない。

| トピック | キー | なぜこのキーか |
| --- | --- | --- |
| `order`, `payment` | `orderId` | Saga の順序の単位 |
| `coupon-use`, `coupon-rollback` | `userId` | ペイロードに orderId がない。クーポンはユーザー単位で使用/ロールバックされる |
| `cart-empty`, `cart-rollback` | `userId` | カート = ユーザー |
| `product-update` | `eventId`（= `order-<id>`） | 1 つのメッセージが注文の商品を複数含むので、商品単位のキーがない。元のメッセージと再配信を同じパーティションへ |
| `user-created` | `userId` | コンシューマー 3 つ（order・coupon・chat） |
| `chat` | `roomId` | ルーム内の順序がそのまま会話 |

`KafkaProducerKeyGuardrailTest`（common）がリポジトリのすべての `kafkaTemplate.send` を走査し、3 引数
`(topic, key, value)` であるか、`MessageBuilder` に `KafkaHeaders.KEY` があるかを見る。2 つをわざと
元に戻して、まさにそのファイルを指して壊れることを確認した。

**やらなかったこと — パーティションの増加。** キーが載ったので今は増やしても順序は安全だが、それは `concurrency` と
一緒に決めるべき運用上の変更であり、すでに作られたトピックのパーティションは `--alter` なのでデプロイスクリプトも変わる。
今のスループットでは 1 が問題ではない。増やすときは `ops/02` のように主要トピックも **明示的に** 作り（自動
作成に頼らず）、コンシューマーの `concurrency` をパーティション数に合わせればよい。

**CDC リレーをスケジューラーと同じルールに揃えた — 適用済み。** コネクター 4 つ（`*-outbox-connector-*.json`）:

| ルール | 前 | 後 |
| --- | --- | --- |
| トピック | `route.by.field=aggregate_type` → `Order.events`（誰も聞いていない、§5 A2） | `event_type` でルーティング + `RegexRouter` でスケジューラーと同じ名前: `payment-request→order`, `token-update→token-u`, `payment-response→payment` |
| キー | `aggregate_id` だがデフォルトの `JsonConverter` → `{"schema":…,"payload":"123"}` で包まれ、**アプリのプロデューサーの `"123"` とバイトが異なる** → 別のパーティション | `key.converter=StringConverter` — スケジューラーの `StringSerializer` と同じバイト |
| テーブル | `public.*`（何もキャプチャしない、§5 A1） | `orders.payment_outbox_event`, `pay.order_outbox_event` |

その過程で **pay-service の `aggregate_id` が誤っていたこと** が露見した。`OrderOutboxHelper.saveOrderOutbox` が
`paymentId` を入れるが、`PaymentRequestMessageListener` の冪等性検査は `orderId` で `findByAggregateId` を
行う — **一度も一致したことがない**（再配信を除外したことがない）。`orderId` に変えた。これでスケジューラーのキー・CDC のキー・
冪等性検査のキーがすべて注文 id で同じになった。`CdcConnectorGuardrailTest` が 4 つのファイルのルーティング表・キーカラム・コンバーター・
スキーマをスケジューラーのルールと照合する（ルートを 1 つ間違えておいて指摘されることを確認）。

**§5 A3 も適用 — コンシューマー側。** コンシューマー設定 9 つの `new JsonDeserializer<>()`（型なし = `__TypeId__` ヘッダーにだけ
依存）13 か所を `new JsonDeserializer<>(X.class, true)` に — ヘッダーがあればそれを使い、なければ X として読む。pay の
`PaymentRequestKafaListener` は `Authorization` ヘッダーがなければバッチを拒否していたが（CDC レコードはヘッダーを載せられない）、
そのヘッダーはログにしか使われず、実際のトークンはペイロード（`authorizationToken`）の中にある — 「ヘッダーまたはペイロードのトークン」に
緩和した。おまけ: coupon の `UserCreatedConsumer` が `@Header("Authorization")` を **必須** で受け取るが、発行側の
`UserCreatedProducer` はヘッダーを載せない → ヘッダーのないバッチが DLT へ。`required=false` に。
`KafkaConsumerGuardrailTest` が型なしのデシリアライザーを防ぐ。

#### リバランス — `RebalanceSettings` + `LoggingRebalanceListener`（common）

全コンシューマーがデフォルトの `RangeAssignor`（eager）だった — メンバーが 1 つ変わるたびに **グループ全員がすべてのパーティションを手放し**、
受け取り直す。Pod 1 つがローリングデプロイで入れ替わるとき、同じグループの残りの Pod まで止まる。order の設定には
`CooperativeStickyAssignor` が **コメントで** 残っていた — 知っていたがオンにしなかった痕跡。

- `RebalanceSettings.assignors(env)`: デフォルトは `[cooperative-sticky, range]`（第 1 段階 — 旧メンバーと混在しても安全）、
  `kafka.rebalance.protocol=cooperative` なら `[cooperative-sticky]`（第 2 段階）。いきなり cooperative だけにすると、このコミットの
  最初のローリングで Range しか知らない旧 Pod と混在してグループが壊れる。全員が第 1 段階を過ぎた後、yml 1 行で移行する。
- `LoggingRebalanceListener`: パーティションを手放す前に **同期コミット**（引き継ぐメンバーが同じバッチを読み直さないように）、
  受け取るときにパーティションごとの lag を記録、lost はコミットなしで警告。コンテナファクトリー 20 個すべてに登録。
- `LoggingRebalanceListenerTest`（MockConsumer でコミット/未コミットを確認）と `KafkaConsumerGuardrailTest`（グループごとに
  アサイナー、ファクトリーごとにリスナー）。

#### カスタムパーティショナー — `AggregateKeyPartitioner`（common）

キーを載せても 1 つ残る。トピックごとにキーの形が異なる — `order` は `"123"`、`product-update` は
`"order-123"`。デフォルトのパーティショナーはバイトをハッシュするので、同じ注文がトピックごとに **別のパーティション** に入る。パーティション数が
同じでもトピック間の共同パーティショニングにならず、後でストリーム結合やパーティション単位の再処理をしようとすると、そのときにキーをすべて
変えなければならない。

`AggregateKeyPartitioner` は `<접두사>-` を剥がして集約 id だけを残し、**デフォルトのパーティショナーとまったく同じ murmur2** で
パーティションを選ぶ。そのため (1) `"123"`・`"order-123"`・`"payment-123"` が同じパーティションになり、(2) 接頭辞のないキーはデフォルトの
パーティショナーと **まったく同じ** パーティションなので — Connect ワーカーのデフォルトのパーティショナーを使う CDC リレーとも共同パーティショニングされる。
キーがなければ `UNKNOWN_PARTITION` を返して組み込みのスティッキーパーティショニングに任せ（デフォルトの動作）、トピックごとに 1 回警告を残す。
プロデューサー設定マップ 14 個（プロデューサー 7 + DLT 7）に `PARTITIONER_CLASS_CONFIG` として登録し、`KafkaProducerKeyGuardrailTest`
が漏れたマップを捕まえる。`AggregateKeyPartitionerTest` が共同パーティショニング・デフォルトのパーティショナーとの同一性・null キー・分布を固定する。

common は `kafka-clients` を `compileOnly` でしか受け取らない — eureka/config-server のランタイムに漏れないという
既存の決定を守る。パーティショナーを使うモジュールは spring-kafka ですでに持っている。


### 1.16 ✅ Avro — スキーマレジストリなしで、JSON と混在しても安全に（Saga 中核の 2 トピック）

> 範囲: 最も重要な Saga 中核の 2 トピックに Avro を適用する。

**制約から。** 当時の検証環境のネットワーク制限で `packages.confluent.io` にアクセスできなかった（HTTP 000）。Confluent の
`KafkaAvroSerializer` とスキーマレジストリのクライアントを取得できず、クラスターにもレジストリがない。
Maven Central は開いているので、**Apache Avro 本体**（1.11.4）とコンパイラーは取得できる。そのためレジストリ方式では
なく、Avro 仕様の **単一オブジェクトエンコーディング**（single-object encoding）を使う — レコードの前に `C3 01` マーカーと
書き込みスキーマの 64 ビットのフィンガープリントが付き、読む側は `SchemaStore` で旧スキーマを探して自分のスキーマと突き合わせて読む。
進化ができ、サーバーがない。

**設計 — リスナーは DTO を受け取り続ける。** `AvroMapping<DTO, Record>` が DTO↔Avro の変換を抱え、
`AvroDtoSerializer`/`AvroDtoDeserializer` がその変換を抱えてワイヤーだけを Avro に変える。リスナー・サービスは
1 行も変わっていない。Avro の生成クラスをドメインまで引きずり込むと、スキーマを変えるたびにサービスが揺らぐ。

**ハイブリッド — コンシューマーは JSON も読む。** `AvroDtoDeserializer` はマーカー（`C3 01`）があれば Avro、なければ
（`{` で始まる）以前のまま spring の `JsonDeserializer` である。3 つの経路が JSON のまま残っているからである:
ローリングデプロイ中の旧プロデューサー、Debezium CDC リレー（Connect は Avro コンバーターなしでは文字列）、e2e テストの手書きの
JSON。delivery・notification は値を `List<String>` で受け取って `JsonNode` にパースするので、そちらには
`AvroJsonTextDeserializer` — トピックにマッピングがあって Avro なら JSON 文字列に変えてくれ、そうでなければバイトのまま。
**コンシューマーのコードには手を付けていない。**

| どこ | 何 |
| --- | --- |
| `common/src/main/avro/PaymentRequest.avsc`, `PaymentResponse.avsc` | スキーマが真実。生成クラス（`com.project.common.avro.*`）は `build/generated/avro` にあり、コミットしない |
| `common/build.gradle` `generateAvro` | Gradle の Avro プラグインはプラグインポータルで 404 なので、`avro-compiler` の Ant `SchemaTask` で直接。`stringType=String` |
| `common/.../kafka/avro/` | `AvroWire`（マーカー）、`AvroMapping`、マッピング 2 つ、シリアライザー 1 + デシリアライザー 2、`AvroMappings`（トピック → マッピング） |
| order `paymentRequestProducerFactory`、pay `paymentResponseProducerFactory` | 値 = `AvroDtoSerializer`（インスタンスで渡すので、マップの `JsonSerializer` は無視される） |
| pay・order・coupon の DTO コンシューマー、delivery・notification の文字列コンシューマー | ハイブリッドのデシリアライザー |

スキーマの決定 2 つ。`amounts` は `decimal` 論理型の代わりに **文字列**（`BigDecimal.toPlainString`）— 桁数を
スキーマに固定しなくても損失がない（テストが `"59.98"` のままであることを見る）。`createdAt` は
`timestamp-millis`（UTC）で `Instant` ↔ `LocalDateTime`。共有の enum（`PaymentStatus`）は 2 つのファイルに **同じ
定義** を置く — `SchemaTask` はファイルごとにパーサーを新しく作るので、名前による参照ができない。

`AvroWireTest` 6 つ: 往復で無損失（2 つ）、旧 `JsonSerializer` のバイトの受け入れ、ヘッダーのない手書き JSON の受け入れ、**スキーマの進化**
（フィールドが少ない旧書き込みスキーマのレコードを `SchemaStore` で読み、新しいフィールドは default）、文字列コンシューマーの変換の
トピック別の介入。`AvroWiringGuardrailTest` が 7 つの設定ファイルが Avro に結び付いているかを見る — プロデューサーを JSON に
戻してもハイブリッドのコンシューマーが静かに受け取ってくれるので、数えなければワイヤーが戻ったことに誰も気づかない。

**全トピックへ拡張 — 適用済み。** コンシューマーのいるトピックすべて（10 個）が Avro である。スキーマを 8 つ追加した:
`ProductUpdateRequest`（商品の配列を含む、DTO の誤記 `autorizationToken` をそのまま — JSON の下位互換ではフィールド名が
そのままキーである）、`UserCreatedRequest`、`CartEmptyRequest`、`CartRollbackRequest`、`CouponUseRequest`、
`CouponRollbackRequest`、`OutgoingMailRequest`（`params` は `map<string>`）、`ChatMessage`。マッピング 7 つは common に
1 つの型で生成し、chat は DTO（`MessageDto`）が chat-service のものなので、マッピングもそちら（`ChatMessageAvroMapping`）にある。

そこで文字列コンシューマー用の変換を **DTO なしで** 行うように変えた — `SpecificRecordJson` がスキーマをたどりながらユニオンを
剥がし、enum は名前で、`timestamp-millis` は ISO 文字列で出す（以前の Jackson の出力と同じ形なので
`event.get("customerId")` のような `JsonNode` アクセスがそのまま合う）。`AvroDecoders`（トピック → デコーダー）がスキーマだけで
登録されるので、chat のように DTO が common の外にあっても notification が読める。

おまけ: chat のコンシューマーファクトリーが `ConsumerFactory<String, ChatMessage>` と宣言されていたが、リスナー
（`MessageConsumer`）は `MessageDto` を受け取る — `__TypeId__` ヘッダーが `MessageDto` を指していたから動いていただけ
だった。Avro はヘッダーがないので、宣言を実際の型に合わせた。

**Avro に移さなかったもの — コンシューマーのいないトピック。** `token-u`（order が文字列で発行、聞いている所なし — 2026-10-14 に発行側まで削除した: アウトボックス行を書く所もなく、トークン原文を載せる形だった、`MessageDtoNoTokenGuardTest`）、
`delivery-status`（delivery が Map→JSON で発行、聞いている所なし）、`user-presence`（notification が聞いているが
発行する所なし）、user-service の `AlarmEventDto` のコンシューマー（発行側がコメントアウト）。死んだトピックにスキーマを付けると
生きているように見える。復活させるときに 5 か所を埋めればよい。プロデューサーの配線ファイル 6 つ + コンシューマー 8 つを
`AvroWiringGuardrailTest` が照合し、スキーマファイル数 = `AvroDecoders` の登録数 = `AvroMappings` + 2（chat、
order-saga-state — §1.17）を固定する。`AvroWireTest` 10 個。

### 1.17 ✅ Kafka トランザクション — あると信じていたが、なかった保証。そして Streams を置く場所

> 範囲: Kafka Streams の適用を念頭に、トランザクションを使うプロデューサー・コンシューマーを評価して推奨案を決め、そのまま適用した。

**評価 — トランザクショナルプロデューサー 14 個のうち、本当にトランザクションが必要なのは 4 個。** このリポジトリには `KafkaTransactionManager` も
`executeInTransaction` もない。トランザクショナルプロデューサーが動作していた理由は、すべて JPA の `@Transactional` の中で `send()` が
呼ばれていたからである — spring-kafka がその瞬間に Kafka トランザクションを開き、JPA コミットの `afterCommit` で Kafka をコミットする
（best-effort 1PC）。問題はその順序である。

- **Outbox リレー（order `order`・`token-u`、pay `payment`）** — リレーは発行を **トランザクションの中で待ってから**
  COMPLETED を記録する（`PaymentRequestKafkaPublisher`）。トランザクショナルプロデューサーで `send().get()` が返す ack は
  「トランザクションの中に書いた」であって「見える」ではない。その後 DB がコミットされ、**その次に** Kafka がコミットされる — その間に
  死ぬと Outbox は COMPLETED なのにレコードは中断（abort）される。トランザクションがかえって **消失のウィンドウ** を作っていた。
  レコードを 1 つ送るリレーにトランザクションが与えるものはない。→ トランザクションを除去、`enable.idempotence=true` + `acks=all`。
  これで ack は「レプリケーション完了」になり、ack の後 DB コミット前に死ねば次のポーリングが再発行する（at-least-once、消費側の台帳が防ぐ）。
- **単発の発行（user `user-created`、coupon `coupon-use`）** — 同じ理由で除去。メールのプロデューサーはすでにそうしていた。
- **order の Saga 補償 4 つ（coupon-rollback・cart-rollback・product-update・cart-empty）** — 決済応答のリスナーが 1 つの
  トランザクションの中で **2 つ以上のトピック** に送る。ここでは原子性に意味がある。維持するが id を直す。
- **`transactional.id=UUID.randomUUID()` はフェンシングを無力化する。** この id は「同じプロデューサーの次の化身」を識別する
  値である。Pod が死んで復活し、同じ id で `initTransactions()` すると、ブローカーが epoch を上げてゾンビの未完了トランザクションを
  中断させる。起動のたびに新しい UUID だと毎回初めて見るプロデューサーになり、何もフェンシングされず、ブローカーには二度と使われない
  トランザクション状態が `transactional.id.expiration.ms`（デフォルト 7 日）まで溜まる。設定はかかっているのに、その設定の保証は
  ない状態 — §5 の最初の原因そのままである。→ `KafkaTransactionalIds.stable("order-saga-cart-rollback")` =
  `<접두사>-<HOSTNAME>`。同じ Pod の再起動（ゾンビが生まれるまさにそのケース）では同じで、ファクトリーごとに接頭辞が異なるので、同じ
  JVM のプロデューサー 2 つが互いをフェンシングしない。
- **コンシューマー 15 個はすべて DB を更新する。** DB のコミットと Kafka のオフセットコミットは原子的にはなりえないので、exactly-once は
  成立しない。正解はすでにあるもの — `processed_event` の冪等台帳である。ここに Streams を挟むと、得るものなしに
  2 つのトランザクション境界ができる。

だから **Streams は既存のリスナーではなく新しいコンシューマーに** 与える。このリポジトリで EOS が成立する唯一の形は
Kafka→Kafka である。

**`saga-projection` — 注文決済 Saga の読み取りモデル（Kafka Streams、exactly_once_v2）。** `order` と `payment` を
注文 id で再キーイングして合わせ、1 つの集約に畳む。注文ごとに `REQUESTED → COMPLETED / FAILED / CANCELED`、そして
応答がリクエストより先に見えれば `ORPHAN_RESPONSE`（2 つのトピックは順序が独立なので正常の範囲 — 長く残るならリクエストのリレーが
死んでいる）。`requestCount ≥ 2` はリレーの at-least-once による再発行が実際に起きたことの計測である。状態は RocksDB +
変更ログであり、`order-saga-state` トピックにも出ていく。`GET /api/saga/{orderId}` がストアを開く。

| どこ | 何 |
| --- | --- |
| `common/src/main/avro/OrderSagaState.avsc` | スキーマがそのまま読み取りモデル。`authorizationToken` は **ない** — 照会 API で公開されるので、資格情報が入ってはならない |
| `common/.../kafka/avro/AvroSpecificSerde` | Streams の状態・出力用のレコード serde。形式は §1.16 と同じ単一オブジェクトエンコーディングなので、他のサービスのハイブリッドデシリアライザーが出力をそのまま読める |
| `saga-projection/.../topology/SagaTopology` | 入力は `AvroDtoDeserializer`（Avro・JSON の両方）、再キーイング（`"order-5"`/`"5"` → `"5"`）、`merge` → `aggregate` → KTable → `to` |
| `SagaEvents`, `SagaStateReducer` | デルタ変換と畳み込みのルール — 純粋関数なのでブローカーなしで検証 |
| `KafkaStreamsConfig` | `exactly_once_v2`、`auto.offset.reset=earliest`（過去の注文も畳む）、`replication.factor=-1`（ブローカーのデフォルト値 — ブローカー 1 台の dev で 3 を固定すると内部トピックの作成が失敗する）、`num.standby.replicas`、スレッド死亡時は SHUTDOWN_CLIENT + ログ |
| `SagaQueryService` | 対話型の照会。**他の Pod にフォワーディングしない** — Pod の IP に HTTPS をかけると SAN の検証に失敗する（k8s/48 の EUREKA_INSTANCE_HOSTNAME と同じ理由）。代わりにスタンバイレプリカ（`enableStaleStores`）からローカルで答え、`stale` を表示する。コピーがなければ 503 + `Retry-After` |
| `KafkaStreamsHealthIndicator` | Boot は Streams のヘルスを自動構成しない。readiness グループに入れ、クライアントが落ちたらトラフィックから外れる |
| `SagaDeserializationExceptionHandler` | 入力レコードを読めなかったとき。以前はデフォルト値（LogAndFail）だったので、壊れたレコード 1 つでスレッドが死に、再起動しても同じオフセットでまた死んでいた。**未知の書き込みスキーマ**（`MissingSchemaException`、この Pod より新しいプロデューサー）なら止まる — 飛ばすと EOS でオフセットがコミットされ、ローリング中の新しい Pod も読み直さず、プロジェクションがその記録を失う。**それ以外の壊れたレコード** は飛ばして座標を残す。カウンター `saga_projection_deserialization_failures_total{topic,action}` + アラート 2 つ（docs/55 §8） |
| `k8s/49-saga-projection.yaml` | replicas 2 = アクティブ 1 + スタンバイ 1。`SAGA_APPLICATION_SERVER=$(POD_IP):8190`（Pod ごとに異なる必要がある）、状態は emptyDir。`kustomization`・ops/13・ops/19 に登録 |
| `ops/02-create-dlt-topics.sh` | `order-saga-state` を **コンパクションで** 作る（下記）。`ops/06` の 6 番項目がデプロイ後に確認 |

**検証。** `SagaTopologyTest` 7 つ（TopologyTestDriver）: リクエスト→応答が出力トピックに REQUESTED・COMPLETED の 2 つの変更として出ていき、
ストアが最終状態になること、再キーイング、**旧 `JsonSerializer` のバイトも同じ状態に**、孤児の応答、注文 id のないレコードの破棄、出力が
Avro でありトークン文字列がないこと。`SagaStateReducerTest` 7 つ（遅い応答が勝つ、`updatedAt` は後戻りしない など）。
`KafkaTransactionGuardrailTest`: `TRANSACTIONAL_ID_CONFIG` は `KafkaTransactionalIds.stable("<접두사>")` でのみ、接頭辞は
リポジトリ全体で一意 — UUID を戻し入れて失敗することを確認した。

**`order-saga-state` のコンパクション — ops/02 で処理。** Streams はシンクトピック（`to()`）を作らない。内部トピック（リパーティション・
変更ログ）だけを自ら作り — 変更ログはすでにコンパクションである — シンクはブローカーの自動作成に任されるが、そのデフォルトのポリシーは
`delete` である。症状がないことが問題である: トピックはでき、レコードも溜まり、`retention.ms`（デフォルト 7 日）が過ぎてようやく過去の
注文の状態が静かに消える。

さらに厄介な順序が 1 つある。**アプリが先に起動すると、トピックはすでに存在する。** その後の `--create --if-not-exists` は「成功」
で終わり、ポリシーは依然として delete である — §5 の「適用されたと信じているが、そうではない」パターンそのままである。そこで ops/02 は存在
有無だけを見ず、**`cleanup.policy` を実際に読んで、異なれば `kafka-configs --alter` で直す**（`FIX_CONFIGS=0`
なら警告のみ）。設定 3 つにはどれも理由がある:

| 設定 | なぜ |
| --- | --- |
| `cleanup.policy=compact` | 注文ごとに最新の状態 1 つ |
| `segment.ms=86400000` | コンパクションは **アクティブなセグメントに触れない。** 低トラフィックのトピックでデフォルト値（1GB / 7 日）だと、1 つのセグメントが何週間も開いたままになる —「コンパクションをオンにしたのになぜ減らないのか」のほぼ唯一の原因 |
| `min.cleanable.dirty.ratio=0.1` | デフォルトの 0.5 は「重複が半分にはならないと掃除しない」。注文あたり 2〜3 回の更新なので、その比率になかなか届かない |

パーティション数は **入力トピック（`order`）から読んで合わせる。** 今は単純なシンクなので異なっていても動作するが、このトピックを KTable として
読み直して結合しようとした瞬間に共同パーティショニングが要求される（Streams はパーティション数が異なると `TopologyException` で
拒否する）。DLT のデフォルト値 3 をそのまま使っていたら、入力が 1 の今すでにずれている。

偽のブローカー（スタブ CLI）で 7 つのシナリオを回した: 新規作成、delete→compact の矯正、**再実行時は無変更**、`FIX_CONFIGS=0`、
パーティション不一致の警告、`DRY_RUN`、`SKIP_STATE`。再実行のシナリオで実際のバグが 1 つ出た — ポリシーを読む正規表現が
`.*cleanup\.policy=` で始まっていたため、貪欲マッチが `synonyms={... DEFAULT_CONFIG:log.cleanup.policy=delete}` の **最後の**
値を捕まえていた。すでに compact のトピックも毎回「delete → compact に変えた」と報告していた。行頭に固定して直した。
`ConfigurationGuardrailTest` B-38 がアプリの `saga-projection.topics.state` とスクリプトの `STATE_TOPIC` のデフォルト値が
食い違うのを防ぎ、`ops/06` の 6 番項目は存在ではなく **コンパクションかどうか** を見る。

**やっていないこと / 確認できなかったこと。**
- **ブローカーにつないでみていない。** TopologyTestDriver は EOS を模倣しない — ここで検証したのは畳み込みのルールと
  配線である。トランザクション・フェンシング・スタンバイレプリカの復旧はクラスターで初めて動く。ops/02 の状態トピックの経路もスタブ CLI でしか
  回していない: コマンドの組み立てと分岐は確認したが、実際のブローカーの応答形式ではない。
- **アプリより先に実行するほうがよい。** すでに delete ポリシーで消された状態は、ポリシーを直しても戻ってこない。
- 入力がパーティション 1 つなのでタスクも 1 つである。§1.15 のとおりパーティションを増やすとタスクが増え、そのときレプリカ > standby+1 なら
  コピーのない Pod ができる — 2 つを一緒に上げること。状態トピックのパーティションは **減らせないので** 再作成が必要である。
- ゲートウェイのルートは追加していない。内部ネットワークで `saga-projection:8190` で開く。


---

## 2. モジュール別の詳細 — 🟠 誤った動作 · 🟡 非効率/整理

§1 に挙げたものは除外。「確認」の表示は私がファイルを開いて見たもの。

### 2.1 user-service

| # | 深刻度 | 位置 | 何 | 確認 |
| --- | --- | --- | --- | --- |
| 1 | 🟠 | `NotificationService.java:44,48-52,68-70` | シングルトン Bean の `private String sessionId` を可変の共有フィールドとして使っている。B が SSE に接続した直後に A が重複ログインすると、A の「Logout」通知が **B のセッションに** 行く | |
| 2 | 🟠 | `ReviewService.java:153-156, 249-251` | 他人が先にレビューした商品への 2 つ目のレビュー → `findFirst().get()` で `NoSuchElement`。最初のレビューは `0.0F/0 = NaN` を `updateRating` に送る | |
| 3 | 🟠 | `UserServiceImpl.java:212-224` + `UserController.java:166-183` | 現在のパスワードが間違っていると何もせずに **"Password updated" 200** | |
| 4 | 🟠 | `AuthLoginFilter.java:83-89` | `catch (Exception)` が `AuthenticationException` まで握りつぶして `null` を返す → `unsuccessfulAuthentication` が決して呼ばれない。docs/27 §13 のロック案内メッセージがユーザーに届く道がなく、401 の代わりに本文のない 400 | 確認 |
| 5 | 🟠 | `JwtAuthFilter.java:87-96` + `JwtUtils.java:204-215` | 有効期限の検査がコメントアウトされており、`getIdFromJwtToken` が `ExpiredJwtException` を捕まえて subject を返す — **サービス自体は期限切れトークンを有効として扱う**（ゲートウェイ経由のときだけ阻まれる） | |
| 6 | 🟠 | `RefreshTokenRepository.java:50`, `JwtUtils.java:319` | 単位の誤り 2 つ: `expire(key, now+ms, MILLISECONDS)` → TTL ≈ 55 年（Redis のキーが永遠に溜まる）; `setMaxAge(ms)` → Cookie 38 年。`OAuth2SuccessHandler:87` は `/1000` をしていて意図が分かる | 確認 |
| 7 | 🟠 | `WishItemService.java:49-50` | `x.getProductId() == id` — `Long` の参照比較。TSID はキャッシュ範囲外なので常に false → ウィッシュリストの重複が無限 | 確認 |
| 8 | 🟠 | `UserServiceImpl.java:240-253`, `PasswordResetToken` | 再設定トークンを **平文で保存**（`VerificationToken` はハッシュに直したのにここだけ残っている）、リクエストごとに新しい行 + 既存の無効化なし → `findByUser` の `Optional` が 2 件で例外 | |
| 9 | 🟠 | `AuthController.java:429-440` | 未登録のメールアドレスに 500、登録済みは 200 → `forgot-password` で登録有無を列挙できる。すぐ下の `resend-verification` は応答を統一してある | |
| 10 | 🟠 | `AuthController.java:496-508` | `verify-2fa-login`: subject（メールアドレス）で `findByUsername` → 常に null → **NPE 500。** 正常に動作したことがないように見える。試行回数の制限もない | ✅ エンドポイントを削除、ログインの第 2 段階は `/public/verify-2fa`（待機 Cookie・5 回制限）の 1 つ — docs/27 §12b |
| 11 | 🟡 | `SecurityConfig.java:81` | `CorsConfigurationSource` をコンストラクター注入しているが、その型の Bean がない（`CorsConfig` 全体がコメントアウト）。使われてもいない（`cors.disable()`）。起動できているのでどこかから来ているが、消すべきである | |
| 12 | 🟡 | `AdminController.java:39-42` | `findAll()` で users 全体をエンティティのまま返す | |
| 13 | 🟡 | `security/RedisSessionManager` | 名前に反して JVM 内の `HashMap` — レプリカ 2 つでは重複ログインの検知が Pod ごとに異なる | |

デッドコード（呼び出し元 0）: `UserService.getUser(String)`、`getUsersFromSessionRegistry`、`createOutboxEvent`、
`deleteUser(User)`、`JwtUtils.isRequestAuthorized/generateJwtCookie/getCleanJwt*Cookie`（復活させると
path の不一致で Cookie が消えない）、`CsrfController`、`message/OutboxScheduler` インターフェース。

### 2.2 catalog-service

| # | 深刻度 | 位置 | 何 | 確認 |
| --- | --- | --- | --- | --- |
| 1 | 🔴 | `CategoryService.java:36-39` | `null != categoryRepository.findByCategoryName(...)` — 戻り値の型が `Optional` なので **常に true** → カテゴリ作成が永久に「すでに存在」で 400 | 確認 |
| 2 | 🔴 | `ProductIndexService.java:49,61` + `ProductDocument.java:16` | リアルタイムのインデックス作成が `@Document(indexName="products")` に書くが、検索は `product-alias`、バッチは `<ts>-products`。インデックスが異なる → 登録・削除が **次の手動 sync まで検索に反映されない**（このクラスが埋めようとしていたまさにその隙間） | 確認 |
| 3 | 🔴 | `ProductService.java:669-677` | 既存の商品に色のバリエーションを追加するとき、`product.setDetails/setQuestions` で **永続化済みの親の `orphanRemoval` コレクションを丸ごと置き換える** → 既存の詳細/Q&A の削除、または Hibernate の例外 | コード経路を確認 |
| 4 | 🟠 | `ProductService.java:972-990` + `:1031-1038` | 在庫不足なら `null` を返すが、その直前に `updateSoldValue` がすでに `sold` を上げて保存している → 販売数・在庫が永久に不一致、リスナーは冪等レコードを保存して再処理の機会もなくす | |
| 5 | 🟠 | `SearchService.java:174-176` | SKU フィルターの結果が **空なら条件そのものを外す** → その価格帯の商品がなければ全件一覧を返す。ES の分岐（`:190-192`）は `productId.eq(-1L)` で正しく処理している | |
| 6 | 🟠 | `ProductService.java:155-185` | `@Cacheable(key="'warmup'")` に `pageSize` がなく TTL もない（`permanentCacheConfig`）→ 最初の呼び出しが 4 件なら、以降 20 件のリクエストも 4 件 | |
| 7 | 🟠 | `ProductService.java:919-1011` | 自己呼び出しで無力化された `@CachePut`/`@CacheEvict` の束 + エントリーポイントの evict に `products`・`product` が漏れている → **品切れの在庫が詳細ページで無期限に 1 のまま** | |
| 8 | 🟡 | `ElasticsearchBatchConfig.java:126-147` | コレクション 4 つの `JOIN FETCH` + `JpaPagingItemReader` = メモリページング（HHH90003004）。5 万件で O(n²) | |
| 9 | 🟡 | `SearchController.java:74-112, 137-147` | `/{entity}/all` 8 つが ES を無制限にダンプ、`/search/category` は `pageSize` の検証なしにエンティティをシリアライズ | |
| 10 | 🟡 | `ProductSkuRepository.java:45-53` | フィルターがすべて null なら `where true` → 全 product_id を IN 句に | |
| 11 | 🟡 | `ElasticsearchBatchConfig.java:335-438` | ロールオーバーが `-products` だけをエイリアスに付け、古いものを 1 つだけ消す。残りの 7 種のインデックスは sync のたびに溜まる | |
| 12 | 🟡 | `ProductSubcategory.java:52,57` | リポジトリで **唯一 EAGER の関連 2 つ**（`@ManyToOne` に `fetch` 未指定 = デフォルト値 EAGER）。`subcategory` は商品一覧のマッピングのたびに発火し、**異なるサブカテゴリの数だけ SELECT** を出す — `Subcategory` クラスに `@BatchSize` がないのでバッチ化されない。`product` はすでに永続化コンテキストにある親を指す逆参照なので大抵はクエリが出ないが、`ProductSubcategory` を単独で照会するとそのときに発火する。`Product` の LAZY コレクション 4 つには `@BatchSize(20)` がきちんと付いているが（`Product.java:78-104`）、**その次の 1 ホップが空いている** | 確認 |

### 2.3 order-service · pay-service

| # | 深刻度 | 位置 | 何 | 確認 |
| --- | --- | --- | --- | --- |
| 1 | 🟠 | `OrderDataService.java:312` | 決済 Outbox の `paymentStatus` が **常に `COMPLETED` でハードコーディング** → `order` トピックに `CANCELED` が載ったことがなく、`cancelPayment → OrderSaga.rollback → クーポン・カートのロールバック` の全区間が **到達不能**。PayPal ウィンドウのキャンセルは 400 で終わり、何も記録されない | 確認 |
| 2 | 🟠 | `PaymentResponseKafkaPublisher.java:58,62` | `TokenContext.getToken()` は HTTP スレッドの ThreadLocal — スケジューラースレッドでは null → ヘッダーが文字列 **`"Bearer null"`** で出ていく。order 側の null 検査を通過し、`"null"` トークンが下流のカートを空にする処理まで伝播する | 確認 |
| 3 | 🟠 | `PaymentRequestMessageListener.java:31,45` ↔ `OrderOutboxHelper.java:72` | 冪等性の照会は `aggregateId = orderId` で行うのに、保存は `paymentId` で行う → **一度も真にならない。** 再配信のたびに Outbox の行 + `payment` メッセージが 1 つずつ増える | |
| 4 | 🟠 | `PaymentService.java:30` | `persistPayment` に `@Transactional` がなく、決済の行と Outbox の行が **別々のトランザクション** → Outbox の保存に失敗すると、お金はキャプチャされたのに応答が永遠に出ていかない | |
| 5 | 🟠 | `PaymentOutboxScheduler.java:57,82` + `KafkaProducerConfig.java:55` | トランザクショナルプロデューサー + JPA 同期 → **Kafka のコミットが DB のコミットの後** に起きる。`.get()` は append の確認にすぎない。DB が COMPLETED でコミットされた直後に Kafka のコミットが失敗すると、メッセージは `read_committed` のコンシューマーには永遠に見えず、行は二度と拾われない。スケジューラーのコメントが保証すると書いているのと順序が逆である | |
| 6 | 🟠 | `OrderCacheService.java:78` + `RedisCacheConfig.java:155` | `order` キャッシュが `disableCachingNullValues` なのに、`getOrder` は他人の注文/存在しない注文に null を返すのが正常（docs/27）→ `IllegalArgumentException` → 意図した 404 の代わりに **400 + キャッシュ内部のメッセージ** | 確認 |
| 7 | 🟠 | `OrderController.java:109-118` + `OrderDataService.java:96` | `/orders` の `filter` が null なら `filter.isEmpty()` で NPE、`?filter=all` なら `IllegalState` — 注文が 1 件以上あるユーザーは **500** | 確認 |
| 8 | 🟠 | `OrderSaga.java:75-94, 195-220` | 順序の逆転（`CANCELED` の後に `COMPLETED`）を警告ログだけで処理してコミット — お金はキャプチャされたのに注文はキャンセル状態 | |
| 9 | 🟡 | `PaymentResponseKafaListener.java:26-27` | バッチリスナーなのに `@Header String` で受け取る — 2 件以上なら `"Bearer a,Bearer b"` になる可能性。E2E は常に 1 件ずつしか送らない | 疑い |
| 10 | 🟡 | `KafkaProducerConfig.java:55…` | `TRANSACTIONAL_ID_CONFIG = UUID.randomUUID()` を起動のたびに — ゾンビのフェンシングの無力化、ブローカーに id の状態が蓄積 | |
| 11 | 🟡 | `OrderSaga.java:155-169, 185` + `CartService.java:303` | 補償が **数量を 1 に強制** する（カートのロールバックがすべての商品を 1 個に）。今は #1 のせいで到達不能 | |
| 12 | 🟡 | `OrderController.java:43-53` | `createOrder` が null を返す正常な失敗（空のカート）で NPE → `{"message":null}` 400 | |

### 2.4 notification-service · delivery-service

| # | 深刻度 | 位置 | 何 | 確認 |
| --- | --- | --- | --- | --- |
| 1 | 🔴 | `MailMessageRepository.java:39-47`, `SmsDispatcher.java:61-84` | **リトライのバックオフがない。** 先取りの条件が `attempts < max` だけで、`next_attempt_at` カラムがない。5 秒間隔で 5 回 = **25 秒で FAILED。** このサービスが `max-attempts=5` を選んだ根拠であるグレーリスティング（受信サーバーが最初の送信を 4xx で拒否し、5 分以上待つ）では 5 回すべて拒否 → そのドメインのユーザーは登録確認メールを永遠に受け取れない | 確認 |
| 2 | 🟠 | `NotificationService.java:57` vs `SmsDispatcher.java:100-101` | `create(..., smsParams)` の最後の引数が本文で参照されない → 注文番号が抜けて "결제가 완료되었습니다. 결제 완료"（決済が完了しました。決済完了） | |
| 3 | 🟠 | `KoreaPostCarrierAdapter.java:234-260` | 配送業者の時刻（KST）をタイムゾーン変換なしに `LocalDateTime` で受け取り、UTC として保存 → タイムラインが 9 時間ずれる | 変換の欠如は確認 / KST の前提は疑い |
| 4 | 🟠 | `DeliveryService.java:120-132`, `Shipment.java:164-173` | 送り状の再登録が既存の送り状を無条件に上書きし、状態を `INFO_RECEIVED` に戻す; UNIQUE 違反は 500 | |
| 5 | 🟠 | `NotificationService.java:197-210` | `markAllRead` は直近 500 件だけ、バッジは全件のカウント → 600 件以上だとバッジが永遠に残る | |
| 6 | 🟠 | `VerificationService.java:198-202` | `purgeExpired()` を呼ぶ所がない（`@Scheduled` の漏れ）→ 電話番号の台帳が無限に増加 | |
| 7 | 🟡 | `CarrierConfig.java:77-82` + `V3__seed_carriers.sql` | シミュレーターが `matchIfMissing=true` でデフォルト有効 — 本番でオフにし忘れると、現物が倉庫にあるのに「配達完了」 | |
| 8 | 🟡 | `SmsMessage.java:19,33-41` | `SMS_BYTE_LIMIT=90`（LMS の境界、料金 3〜4 倍）が宣言されているだけで使われていない | |
| 9 | 🟡 | `SmsDispatcher.java:124-129`, `MailDispatcher.java:122-127` | 上限到達時に `recordAttempt` を 2 回呼び、カウンターが +1 水増しされる | |
| 10 | 🟡 | `TrackingPoller.java:83-88` | 「ポーリング中断」というコメントに反して `recordSyncFailure()` しか呼ばない — アダプターのない配送業者が 12 時間ごとに永遠に先取りされる | |
| 11 | 🟡 | `NotificationPreferenceRepository.java:13` | 番号の重複登録を検査するメソッドが宣言だけ — 2 つのアカウントが同じ番号で 2FA できる | |

### 2.5 cart-service · coupon-service · chat-service

| # | 深刻度 | 位置 | 何 | 確認 |
| --- | --- | --- | --- | --- |
| 1 | 🔴 | `ChatMessageRepository.java:23,25` + `SequenceGeneratorService.java:27-31` | メッセージの `_id` が **文字列の増加シーケンス**（"1","2",…）なのに、ソート・カーソルが辞書順 → 12 件なら `9,8,…,2,12,11,10,1` と入り乱れ、カーソル `"10"` の次のページは `"1"` 1 つだけ | 確認 |
| 2 | 🟠 | `CouponUseProducer.java:29-40` + `CartService.java:250-259` | `CouponUseRequest` にトークンを埋めない → cart の afterCommit の Feign が 401 → **キャッシュが古い値のまま残り**（TTL なし）、レコードは DLT | |
| 3 | 🟠 | `CartService.java:303` | `cartRollback` がすべての商品の数量を 1 に（§2.3 #11 と対） | |
| 4 | 🟠 | `CouponService.java:53,202,226-231` | `startDate/endDate` をどこでも比較していない — 期限切れのクーポンが永遠に有効 | |
| 5 | 🟠 | `CouponUseMessageListener.java:30-35`, `CartRollbackMessageListener.java:27-32` | null の分岐で `log.error` の後に return せずに流れ落ちて NPE → DLT | |
| 6 | 🟠 | `ChatService.java:126-136` + `MessageMapper.java:46` | `chat.member` の行がないユーザー（コンシューマーが `latest` オフセットなので起動前の登録者）のメッセージは `createdAt` が null → コンシューマーのアンボクシングで NPE → 保存もブロードキャストもされない | |
| 7 | 🟠 | `ChatService.java:141-160, 204-217` | ルーム作成が JPA → Redis → 参加者の順なので、参加者の保存の NPE でロールバックされても Redis は残る → 幽霊ルーム; 逆に Redis の初期化時には `room_id NULL` の行が無限に蓄積 | |
| 8 | 🟠 | `StompHandler.java:60-77` | SUBSCRIBE の認可が `roomId` ヘッダーの存在だけを見る — `destination` と照合しないので他人のルームを購読できる。今は「公開チャットルーム」前提なので被害は小さいが、1:1 ルームを作った瞬間に漏えい経路になる | 確認 |
| 9 | 🟠 | `CouponService.java:174-190` | メールアドレスの分岐で `Long.parseLong(request.getUserId())` でメールアドレスをパース → メールアドレスではクーポンを発行できない | |
| 10 | 🟡 | `CartService.java:73-99` | カートのキャッシュを TTL なしで `set` — read-through と無効化の競合で、削除されたカートが復活しうる | |
| 11 | 🟡 | `ChatService.java:145-158` | ルーム作成のロックキーが `roomName` なのに識別子は UUID — 重複を防げない; leaseTime の 5 秒を超えると `unlock` が例外 | 疑い |

小さなもの: `FeignErrorDecoder`（cart）が本文を 2 回読み、400 に `null` を返して NPE に化ける;
`MessageMongoEventListener`（chat）は `@Component` のないデッドコード; `deleteCartItem`（cart）は
到達不能だが、合計金額で数量を掛けていない。

### 2.6 common · gateway-server

#### 🔴 ゲートウェイがたった今入れた身元ヘッダーを自ら消す — このレビュー最大の発見

`application-local.yml:128-135` のコメントにはこう書かれている:

> default-filters 는 모든 라우트에 적용되고 **라우트 필터보다 먼저 실행되므로**,
> 여기서 지운 뒤 JwtAuthorization 이 검증된 값을 다시 넣는 순서가 된다.
> （訳: default-filters はすべてのルートに適用され、**ルートフィルターより先に実行されるので**、ここで消した後に JwtAuthorization が検証済みの値を入れ直す順序になる。）

**その前提が間違っていた。** Spring Cloud Gateway 4.2.0 の `RouteDefinitionRouteLocator` は
`loadGatewayFilters` でフィルターに **自分のリスト内のインデックス+1** を order として付与し
（バイトコード: `new OrderedGatewayFilter(f, i + 1)`）、default のリストとルートのリストをそれぞれ
そのように番号付けした後、1 つに合わせて `AnnotationAwareOrderComparator.sort` する。つまり 2 つのリストの
order が **それぞれ 1 から改めて始まる**。

default-filters: `Dedupe=1`, `RemoveRequestHeader=User-Id=2`, `User-Email=3`, `User-Role=4`,
`RequestRateLimiter=5`。

**cart-service**（`JwtAuthorization=1`, `RemoveCookie=2`, `RewritePath=3`, `CircuitBreaker=4`）:

| 実行順序 | フィルター |
| --- | --- |
| 1 | Dedupe, **JwtAuthorization** ← ここで User-Id/Email/Role を注入 |
| 2 | **RemoveRequestHeader=User-Id** ← たった今入れたものを消す |
| 3 | **RemoveRequestHeader=User-Email** ← 消す |
| 4 | **RemoveRequestHeader=User-Role** ← 消す |

**cart-service・catalog-service-write は身元ヘッダーを 1 つも受け取れない。**
`user-service`（`RewritePath=1`, `JwtAuthorization=2`）は `RemoveUserId`（2）が JwtAuth と
同じ order なので、安定ソートで先に実行されて `User-Id` だけが生き残り、**`User-Email`・`User-Role`
は消される。**

結果:

| 症状 | 根拠 |
| --- | --- |
| `GET /cart-service/api/cart/loadcart` → `User-Email` が null → **401。** カートの全機能 | `CartController.java:85` |
| `POST /catalog-service/api/product`（管理者）→ `User-Id` が null → **401。** 商品登録不可 | `AdminWriteInterceptor.java:55` |
| クーポン削除・配送の送り状登録 → ロールヘッダーなし → **常に 403** | `CouponController.java:108`, `DeliveryController.java:161` |
| **セキュリティのリグレッション**: user-service が `User-Role` を受け取れず、「ヘッダーなし = 内部 Feign 呼び出し」の分岐に落ちる → 一般ユーザーのトークンで `GET /api/auth/user/id?email=victim@…` が **他人の userId・名前・プロフィール** を返す。docs/27 §9 の防御が丸ごと無効 | `AuthController.java:367` |

§1.1（注文作成 500）と §1.3（管理者画面）がそれぞれ別の原因で同じ画面を塞いでいたため、
この欠陥が隠れていた可能性が高い。**直し方は 1 行である** — `RemoveRequestHeader`
3 つを default-filters から外して各ルートの `JwtAuthorization` の **前** に置くか、
`JwtAuthorization` フィルターが注入の直前に自ら消すようにする（すでにそのコードがある）。

#### その他

| # | 深刻度 | 位置 | 何 | 確認 |
| --- | --- | --- | --- | --- |
| 2 | 🔴 | `application-local.yml:170-173` | `discovery.locator.enabled: true` — Eureka に登録されたすべてのサービスに `Path=/{serviceId}/**` の自動ルートができる。**`JwtAuthorization` も CircuitBreaker もない。** `curl https://gateway:8000/CHAT-SERVICE/api/chat/rooms` がトークンなしで通る | |
| 3 | 🔴 | `resolver/CustomUserKeyResolver.java:41-45` | レートリミットのバケットキーを `X-Forwarded-For` の最初の値から作るが、**信頼するプロキシの一覧がない。** ヘッダーを変えながら送るとリクエストごとに新しいバケット → ログインの総当たり制限が事実上ない | |
| 4 | 🟠 | `application-local.yml:177`, `:204` | ルート id が **どちらも `user-service`** — レートリミッターの設定がルート id で保存されるので last-wins。public のログインルートの 1/10 が認証ルートの 20/40 で上書きされる | |
| 5 | 🟠 | `JwtAuthorizationGatewayFilterFactory.java:115-121` | `ExpiredJwtException` しか捕まえない。`MalformedJwtException`・署名不一致はそのまま投げられ、**401 ではなく 500** | |
| 6 | 🟠 | `common/.../RedisConfig.java:25-31,33-53` | `ObjectMapper` の Bean を 2 つ登録 → Boot の `JacksonAutoConfiguration` が退く。この設定をスキャンする order/cart/chat は `WRITE_DATES_AS_TIMESTAMPS` ON、`FAIL_ON_UNKNOWN_PROPERTIES` ON → **日付が配列で出ていき**、未知のフィールド 1 つでデシリアライズが死ぬので、互換デプロイができない | |
| 7 | 🟠 | `common/.../CustomRestExceptionHandler.java:147-154` | `Exception` をすべて 500 で捕まえ、`getLocalizedMessage()` を本文に入れる → JDBC 例外のテーブル・カラム・パラメーターがクライアントへ | |
| 8 | 🟠 | `common/.../PaymentRequest.java` 他 4 つ | Kafka の DTO に **ユーザーのアクセストークンの原文** が入り、Outbox テーブルとトピックに平文で保存される。保持期間中、ブローカー・DB の読み取り権限がそのままそのユーザーのアカウントになる | ✅ 5 つの DTO・スキーマ（v2）から除去、コンシューマーの存在検査を除去、Outbox の行をスクラブ（V15）— docs/54 §1.7 |
| 9 | 🟠 | `application-local.yml:337-356` | `chat-service-ws` に `JwtAuthorization` なし（ハンドシェイクが無認証）; `chat-service-api` の uri が **`lb:ws://`** なので REST リクエストが WebSocket のルーティングに漏れる | 疑い |
| 10 | 🟡 | `JwtAuthorizationGatewayFilterFactory.java:117,132-134,173-176` | リクエストあたり JWT を 5 回パース + `key()` が毎回 `Jwts.SIG.HS256.key().build()` で **ランダムなキーを生成**（アルゴリズム名だけを使うために） | |
| 11 | 🟡 | `eureka-server/application-local.yml:80-92` | `logging:` が `eureka:` の下にインデントされて `eureka.logging.*` にバインド → **Eureka のログ設定がすべて無視** される | |
| 12 | 🟡 | `common/application-common.yml:46-51` | Eureka の zone が平文の `http://localhost:8761` なのにサーバーは TLS → ローカル実行時に登録失敗。k8s は ConfigMap が上書きするので隠れている | |

**問題なかったもの**: JWT のアルゴリズムの限定（`verifyWith(SecretKey)` — alg の混同は不可能）、有効期限の強制、
ゲートウェイ/発行者のシークレット導出の一致、`uid` クレームがないときは **401 + ログ**（docs/27 §11 の
デプロイ順序の結合がコードと一致）、CORS は明示的なオリジン + `User-*` 非許可、actuator 非公開、
`KafkaSecuritySettings` の空値ルール、共通 Kafka DTO のプロデューサー/コンシューマーの型ドリフトなし、
`TokenContext` の `finally` による後始末。


---

## 3. フロントエンド — 「楽観的更新とロールバックが抜けている」について

その通りである。ただ、正確にどこが抜けているのかを分けて見ると、直す場所が絞られる。

**カートはサーバーへの楽観的更新ではなくローカルストレージである。** `cart/Product.jsx:53,72`、
`Infos.jsx:73,78` は Redux に `dispatch(updateCart)` するだけで、サーバーを呼ばない
（`redux-persist` の whitelist `["cart"]`）。サーバーと接する地点はチェックアウトの `saveCart` の 1 つだけである。
だからここには「ロールバック」は必要ない — 代わりに **チェックアウトでサーバーが再計算した値と画面が
異なりうる** という問題があり、§1.5 の割引の式のせいで今実際に異なっている。

**サーバーが真実であるミューテーションには、楽観的更新も失敗時の復元もない。** 実測:

| 指標 | 値 |
| --- | --- |
| `useMutation` の呼び出し | 7 |
| そのうち `onMutate`（楽観的更新） | **0** |
| `onError` ハンドラー | 22（大半はエラーメッセージの表示だけで、状態の復元なし） |

| 画面 | 今の動作 | 抜けているもの |
| --- | --- | --- |
| 決済後の注文ページ（`pages/order.jsx`） | レンダリングされないローカル state だけを変えて **refetch なし** →「未決済」のまま残る（§1.10） | 成功時の `invalidateQueries(['order', id])`。楽観的更新よりこちらが先である |
| ウィッシュリスト | サーバーの応答後に更新 | 楽観的トグル + 失敗時に以前の値を復元 |
| 配送先の選択/削除 | サーバーの応答後に一覧を再取得 | 楽観的更新 + 復元（サーバーの §1.6 の欠陥が先） |
| クーポン適用（`Summary.jsx:153`） | `onChange` が上書きされて **入力が state に届かない**（§1.3 と同じ原因） | 入力経路そのものの復旧 |
| 通知の既読（`NotificationBell.jsx`） | **楽観的更新 + 失敗時に `refreshCount()` で復元 — よくできている** | — これを手本に |
| 配送追跡（`DeliveryTracker.jsx`） | アンマウントのガード・UTC 変換のガードあり | — |

**その他のフロントエンドの欠陥**（私が確認したものは「確認」）:

| # | 深刻度 | 位置 | 何 | 確認 |
| --- | --- | --- | --- | --- |
| 1 | 🔴 | `webpack.config.prod.js:14` + `Dockerfile.frontend-runtime` + `nginx.conf:121` | `devtool: "source-map"` で、`dist` 全体が nginx のルートへ → `main.bundle.js.map` が配信される。`sourcesContent` に元の 1,084 ファイルの全文（内部エンドポイント・脆弱性を説明するコメントを含む）。そして **`frontend/dist.zip`（3MB）が git で追跡されており**、マップファイルと `backend.ysyproject.com`、PayPal の client-id が入っている | 確認 |
| 2 | 🟠 | `pages/browse.jsx:77-89, 92-114` | `toggleParam(name, undefined)` が `delete` → ハンドラーが 1 つずつしか渡さないので、**残りの 12 個のパラメーターがすべて消される。** フィルターを 2 つ同時にかけられない。docs/26 §13 の修正のリグレッション | |
| 3 | 🟠 | `browse.jsx:402` vs `:111` | UI は `rating`、ローダーは `rate` → 評価のフィルターが何もしない | |
| 4 | 🟠 | `browse.jsx:510-514` | `sort` をサーバーに送らず、**現在のページの 10 件だけ** をクライアントでソート | |
| 5 | 🟠 | `browse.jsx:518,587-592`, `Home.jsx:53-58` | ローダーがエラーを握りつぶして null → 最初の行の `productsDB.categories` で ErrorBoundary; ホームは空の画面 | |
| 6 | 🟠 | `util/TokenRefresher.js:112-114` | **どんな 500 でもホームへ移動** — 決済中に 500 なら画面がアンマウントされ、ユーザーは注文ができたかどうか分からない | |
| 7 | 🟡 | `pages/admin/Index.jsx:108-126` | `product.name` が effect の依存関係 — 1 文字ごとに API 2 件（今は §1.3 のせいで隠れている） | |
| 8 | 🟡 | `ProductPage.jsx:24-60` | `reviews` を配列として扱うが、サーバーは `{reviews, num_reviews}` → 星評価の分布がすべて 0% | |
| 9 | 🟡 | `util/loadFake.js` | 未参照の 280 行。バンドルには入らないが、`@faker-js/faker` を dependencies に留めている | |

**問題なかったもの**: トークンはメモリ（アクセス）+ HttpOnly Cookie（リフレッシュ）だけで、`localStorage` にトークンなし。
`dangerouslySetInnerHTML`/`innerHTML`/`eval` は 0 件。`/admin` のガードと `role` クレームの読み取り（docs/27
§12）は有効。OAuth コールバックにオープンリダイレクトなし。401 の同時リクエストのキューは正常。バンドルにシークレット値なし
（#1 はソースの露出であって、シークレットの流出ではない）。

---

## 4. CDC — コネクターをオンにすると何が壊れるか、生きているリレーは正しいか

**前提**: Kafka Connect はどこにもデプロイされていない（docs/23 §4）。実際の Outbox の発行は
`PaymentOutboxScheduler`/`OrderOutboxScheduler` が行う。ルートのコネクター JSON 5 つは死んだ設定である。

### 4.1 今コネクターをオンにすると

| # | 深刻度 | 何 | 位置 |
| --- | --- | --- | --- |
| A1 | ✅ 適用 | `table.include.list` が `public.*` なのに、テーブルは `pay`/`orders` スキーマ → **何もキャプチャしない**（WARN のみ） | `*-postgresql.json:12` vs `OrderOutboxEvent.java:28`, `PaymentOutboxEvent.java:27` |
| A2 | ✅ 適用 | ルーティングが `${aggregate_type}.events` → `Order.events`/`Payment.events`/`UserToken.events`。コンシューマーは `order`/`payment` しか見ない | `:15-16` vs `PaymentOutboxHelper.java:67,98`, `OrderOutboxHelper.java:73` |
| A3 | ✅ 適用 | 値の形式: EventRouter は `payload` カラムだけを送り、`__TypeId__`・`Authorization` ヘッダーを付けない → order/pay の `JsonDeserializer` が型を決められず **全量 DLT** | `KafkaConsumerConfig.java:85`(order), `:53`(pay) |
| A4 | 🔴 | `pgoutput` は `wal_level=logical` + REPLICATION 権限が必要。HA の compose は `replica`、マネージドのプロビジョニング（ops/25）は一般ロール | `docker-compose-postgres-ha.yml:36`, `ops/25:338` |
| A5 | 🟠 | マイグレーションの `publish_via_partition_root` は `IF EXISTS dbz_publication` のときだけ — Flyway がコネクターより先に走るので **常に no-op** → オンにすると子パーティション単位のイベントになり、include list が再び不一致 | `V1__partition_*.sql:72-77` |
| A6 | 🟠 | `chat-connector.json`: MongoDB の **シンク** が `mongodb://mywebserv.site:27017` の **無認証** の公開ホストへ、`chat` コレクションに書く — chat-service がすでに同じコレクションに保存しているので、オンにすると **形式の異なる重複ドキュメント。** docs/13 が他の 4 つから外したホスト名がここだけ残っていた | `chat-connector.json:4-8` vs `MessageConsumer.java:41` |
| A7 | 🟡 | 世代の混在（`database.server.name` 1.x + `topic.prefix` 2.x、MySQL 専用の `database.history.*`）— Debezium 2.x の Postgres は `topic.prefix` だけを読み、残りは WARN で無視。致命的ではない | |
| A8 | 🟡 | MySQL 版の 2 つはホスト `""`・`<localhost>:<9092>` のプレースホルダーの残骸 | |

二重発行の有無: 今の JSON のままならトピックが異なるので「虚空への発行」である。A1・A2 を直して同じトピックに
送ると、すべてのイベントが 2 回行くが、コンシューマーの冪等キー（pay `UNIQUE(order_id, tracking_id)`、order
`NOT_PROCESSED` 状態の検査、coupon `payment-{id}`、delivery `findByOrderId`、notification
`payment:{orderId}:{status}` UNIQUE）が吸収する。スケジューラーの `STARTED→COMPLETED` の UPDATE は
EventRouter が `warn` でスキップする。

### 4.2 生きているリレー（スケジューラー）

| # | 深刻度 | 何 | 位置 | 確認 |
| --- | --- | --- | --- | --- |
| B1 | 🔴 | リトライ不可能な失敗（payload の JSON パース失敗 → `OrderDomainException`）が **try/catch なしで** スケジューラーのループの外へ飛び出し、トランザクションがロールバックされて行は STARTED のまま残る。`created_at asc` なので **毎回バッチの先頭** → その後ろのすべてのイベントが永遠に発行されない（head-of-line の停止）。最大試行回数・デッドレターなし | `PaymentRequestKafkaPublisher.java:67,98-105`, `PaymentOutboxScheduler.java:74-96` | 確認 |
| B2 | 🟠 | Kafka のコミットが DB のコミットの後（§2.3 #5） | | |
| B3 | 🟠 | `"Bearer null"`（§2.3 #2） | | 確認 |
| B4 | 🟠 | メッセージキーなし + 3 パーティション → 同じ注文のイベントの順序保証なし | `PaymentRequestKafkaPublisher.java:69-72`, `ops/02:35,47` | |
| B5 | 🟠 | 2027-01 までのパーティションだけを事前作成 + DEFAULT。2027-02 からは行が DEFAULT に行くが、その後に新しいパーティションを作ると PostgreSQL が DEFAULT 内の行と衝突するとして **拒否** → 運用の介入なしにはパーティション追加不可 | `V1__partition_*.sql:30-39` | |
| B6 | 🟡 | `PaymentService.java:73-85` がスケジューラーの外でロックなしに再発行する 2 つ目の経路 | | |

**正しいもの**: SKIP LOCKED による先取り + バッチの上限、発行 + 状態確定を 1 トランザクションで、一時的な失敗を FAILED
と記録しない、部分インデックスがクレームのクエリと正確に合っている、並行性テストが存在する。

**勧告**: コネクター JSON 5 つは **削除** し、docs/23 §4 を指す 1 行だけを残す。直すには
A1〜A5 をすべて手直しする必要があるが、その末に得られるのは、すでに整理されたスケジューラーと同じイベントを 2 回
入れる経路である。CDC の利点（ポーリングの遅延・DB の負荷）を取るにはスケジューラーを *オフにする* 決定まで一緒に
する必要があり、その決定は docs/23 がすでに逆に下している。一緒に整理すべきもの: `README.md:262,336`、
`ops/01:53-54`、`ops/promote-replica.sh:35` のコネクターの手順。**B1 と B5 は JSON とは無関係に
生きている経路の欠陥なので、別途直す必要がある。**

---

## 5. なぜこうなったのか — 繰り返される原因 3 つ

**① 契約を片側だけ変えた。** cart `/checkout` のヘッダー（§1.1）、`AdminInput` の展開順序（§1.3）、cart の
キャッシュキーの更新に必要なトークン（§2.5 #2）、冪等性の照会キー（§2.3 #3）。呼び出し元と呼び出し先が異なる
モジュール・異なるコミットにあり、その間を渡るテストがない。Saga E2E は cart を起動せず、
フロントのテストは state をあらかじめ埋める。

**② 身元をゲートウェイのヘッダーではなく本文から読んでいる。** §1.6 の 7 つの経路すべて。docs/27 が
照会経路を直し、書き込み経路が残った。ルールは 1 つである — *`User-Id`/`User-Email` ヘッダー以外の
身元は存在しない。*

**③「実行されない安全装置」。** `AFTER_COMMIT` リスナー（§1.4）、`publish_via_partition_root`
（A5）、`purgeExpired`（§2.4 #6）、`SMS_BYTE_LIMIT`（§2.4 #8）、`findByPhoneE164AndPhoneVerifiedTrue`
（§2.4 #11）、user/cart の Outbox クラス — あるように見えるが呼ばれていない。前の作業の Feign
タイムアウト（docs/37 §2.1）と同じ部類である。この部類はレビューでは捕まえられず、**「実際に呼ばれているか」を問う
テスト** でしか捕まらない。

---

## 6. レビューが間違っていたもの — 反証して除いた項目

catalog のレビュアーが `ProductUpdateMessageListener.productUpdated`（package-private）の
`@Transactional` が「プロキシは public しか見ないので無効」と報告した。**Spring 6.2.1 では
間違いである。** `AbstractTransactionManagementConfiguration` が `new AnnotationTransactionAttributeSource(false)`
で作り（バイトコードの `iconst_0` を確認）、6.0 からクラスベースのプロキシでは protected/package-private
にもトランザクションがかかる。**private は依然として不可**（CGLIB がオーバーライドできない）— docs/37
§4 の private に関する記述はそのまま有効であり、そこに書かれた package-private の 2 か所（`CouponService.java:242,250`）
は自己呼び出しなので、どのみちプロキシを通らない。

---

## 7. 以前のドキュメントの訂正

| ドキュメント | 何が間違っていたか | 訂正 |
| --- | --- | --- |
| docs/37 §5 | `RegistrationListener` を「最も模範的な箇所」として引用 | コードの形は正しいが **呼ばれていない**（§1.4）。脚注を付けた |
| docs/35 | 「DB の注文合計金額と照合する」を信頼の基準にした | その合計金額はクライアントが決めた値である（§1.2）。サーバーでの再計算が先行しなければならない |
| docs/28 §2 | `AdminInput` の展開順序の修正で「3 つの画面が一緒に直る」 | 3 つの画面の送信部を一緒に変えなかったので **リグレッション**（§1.3） |
| docs/25 | delivery-service が注文状態を `DISPATCHED/DELIVERED` として反映 | そのトピックを誰も読んでいない（§1.8） |

---

## 8. 直す順序 — 提案

各まとまりは 1 つの PR の大きさである。前のものが後ろのものを隠さないように順序を決めた。

1. **注文経路の復旧 + 金額のサーバー再計算** — §1.1（Feign クライアントのヘッダー）、§1.2（`createOrder` が
   cart から受け取った一覧で `total` を再計算、`int`→`BigDecimal`）、§1.5（割引の式 2 か所）、§2.3 #1
   （キャンセル状態の伝達）。このまとまりが終わって初めて、docs/34 の決済の受け入れテストを回せる。
2. **身元の出どころの統一** — §1.6 の 7 つの経路をゲートウェイのヘッダーに。パターンが同じなので 1 つの PR である。
3. **管理者画面のリグレッション** — §1.3（3 つの `onSubmit` を Formik の `values` に）+ §1.9（サイズの逆参照）+
   catalog #1（`Optional` の検査）+ #2（インデックス作成の対象）。テストは **入力をタイピング** するように直す。
4. **登録メール** — §1.4: `publishEvent` を `registerNewUserAccount` の中に移す（トランザクションの
   中で発行しないと `AFTER_COMMIT` が成立しない）。そしてリスナーが実際に呼ばれるテスト。
5. **notification** — §1.7（ログ・制限・pepper の検証）、§2.4 #1（`next_attempt_at` + 指数バックオフ）。
6. **フロントエンド** — §1.10（refetch）、バッジの反転、browse のフィルター 3 件、ソースマップの除去 + `dist.zip` を
   git から除去、`TokenRefresher` の 500 リダイレクトの除去。楽観的更新はその次であり、
   `NotificationBell` のパターンをそのまま使えばよい。
7. **Outbox リレー** — B1（パース失敗の隔離 + FAILED + アラート）、§2.3 #3・#4・#5、B5（パーティションの自動
   作成、または 2027-01 より前に手動で追加する手順）。
8. **整理** — コネクター JSON 5 つの削除、デッドコード（§2.1 の一覧、`MessageMongoEventListener`、
   `loadFake.js`）、`delivery-status` のコンシューマーを作るか発行をオフにする（§1.8 はどちらか一方でなければならない）。

---

## 9. 確認できなかったもの

- **再現の実行**: 当時の検証環境には Docker もクラスターもなかった。§1 の項目はコードで確認したのであって、実際に
  リクエストを送ってみたわけではない。特に §1.1 は、デプロイされたサービスで注文作成を 1 回すれば確定する。
- **§1.2 の `int` の切り捨て**: Jackson のデフォルト値と設定の不在から判断した。59.98 の注文を 1 つ作って
  `orders.total` を見れば終わる。
- **§2.3 #9 のバッチヘッダーの変換**: Spring Kafka を実行してみていない。`@Header List<String>`
  で受け取るテストが 1 つあれば結論が出る。
- **KST の前提（§2.4 #3）**: 韓国郵便（Korea Post）の API の実際の応答がない。
- **本番のプロファイル**: `SMS_PROVIDER`・`SMS_CODE_PEPPER`・`DELIVERY_SIMULATED_ENABLED` が本番に
  実際に注入されているかは config-server のリポジトリを見る必要がある。§1.7 の深刻度はここにかかっている。
- 読んでいないもの: catalog のバッチプロセッサー 4 つ、`document/` の残り 7 つ、chat のコンポーネントの大部分、
  `Delivery.overallStatus()`。
