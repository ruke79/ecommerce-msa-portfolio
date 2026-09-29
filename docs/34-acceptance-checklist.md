# 34. 受け入れチェックリスト — デプロイしたものを手で確認する

**日本語** | [한국어](34-acceptance-checklist.ko.md)

> 🕰️ **状態（2026-09-27）— 一部古い。** カート API は `PUT/PATCH/DELETE /api/cart/items` であり、`/api/pay/confirm` は存在しません。

このリポジトリには単体・統合テストが268個あり、その大部分は **実際に経験した障害を回帰テストとして
固めたもの** である（docs/14 · 16）。ところが、まだ誰も確認していないことが1つある。

> **ユーザーが最初から最後まで実際に使えるのか。**

テストが見ているのは「このコードがこの入力にこう反応する」であって、「会員登録して商品を買い、
配送を照会できる」ではない。その2つは違い、後者はデプロイされたシステムを人が実際に操作してみて初めて
わかる。

このドキュメントはその巡回経路である。自動化スクリプトではなく **チェックリスト** である理由は、初めて回る
回ではどこが壊れるかわからないからだ。ここで出た失敗が次の回帰テストのリストになる。

---

## 0. どう使うか

1. **順番どおりに回る。** 後の項目は前の項目の結果に依存する（会員登録なしには注文できない）
2. 失敗したら **直さずにまず記録する。** 一度に1つずつ直すと原因が混ざる
3. 各項目の **「失敗をどこで見るか」** をあらかじめ開いておく — 画面だけ見ていると半分は見逃す

### あらかじめ開いておくウィンドウ4つ

```bash
# ① 全 Pod の状態
kubectl -n ecommerce get pods -w

# ② ゲートウェイ + 対象サービスのログ（タブを分けて）
kubectl -n ecommerce logs -f -l app=gateway-server
kubectl -n ecommerce logs -f -l app=user-service

# ③ DB（外部化したならマネージドコンソールの SQL エディタ）
psql "$APP_URL"      # product ロール

# ④ Kafka の消費遅延 — 非同期経路が詰まったかどうかはここで最初に見える
kubectl -n kafka exec deploy/kafka -- \
  kafka-consumer-groups --bootstrap-server localhost:9092 --all-groups --describe
```

### デフォルト値の確認 — まずこれを見てから始める

デプロイのデフォルト値は **何も実際には送信しない。** 知らずに「メールが届かない」で30分を費やしがちだ。

| 設定 | デフォルト値 | 意味 |
|---|---|---|
| `notification.mail.provider` | `logging` | メールは **送信されずログに出力されるだけ** |
| `notification.sms.provider` | `logging` | SMS も同様 |
| `SEARCH_ENGINE` | `postgres` | 検索は LIKE 経路。ES/nori へは `ops/03` で切り替え |
| `DELIVERY_KOREAPOST_SERVICE_KEY` | 空の場合がある | 実際の配送照会の代わりに `SIMULATED` 配送業者 |

ログ専用モードは **開発のデフォルト値として正しい**（docs/29 — 誤送信したメールは取り消せない）。
実際の送信まで確認するには §6 で明示的に有効にする。

---

## 1. 起動 — ユーザーを乗せる前に

| | 確認 | 失敗をどこで見るか |
|---|---|---|
| ☐ | Pod がすべて `Running` · `READY 1/1` | `kubectl -n ecommerce get pods` |
| ☐ | `CrashLoopBackOff` がない | あれば `kubectl logs --previous` で **直前の** ログを見る。起動失敗は現在のログには残らない |
| ☐ | Eureka にサービスがすべて登録されている | `kubectl -n ecommerce port-forward svc/eureka-server 8761:8761` → ブラウザ。Pod は Running なのに登録0件ということが実際にあった（プライベート CA を信頼していなかった、docs/18） |
| ☐ | Flyway マイグレーションの適用 | `select * from flyway_schema_history order by installed_rank desc limit 5;` — 各 DB ごとに |
| ☐ | `ops/06-smoke-test.sh` の通過 | `GATEWAY=https://shop.example.com ./ops/06-smoke-test.sh` |
| ☐ | ゲートウェイの fallback が 503 | スモークテストが検査する。200 ならサーキットブレーカーの配線が死んでいる |

> ⚠️ `ops/06` はヘルス・レートリミッター・Eureka・DLT・カタログ照会までしか見ない。**注文・決済は見
> ない。** それが以下が必要な理由である。

---

## 2. 認証 — 最もよく壊れる場所

画面: `/register` → メールのリンク → `/signin` → `/profile`

| | 確認 | 失敗をどこで見るか |
|---|---|---|
| ☐ | 会員登録（`POST /api/auth/public/register`） | レスポンス 200 |
| ☐ | **確認メールが送られる** | `provider=logging` なら notification-service のログにリンクが出力される。`mail_message` テーブルに行ができ、状態が `SENT` になる |
| ☐ | 🔒 **トークンがログにない** | `kubectl logs -l app=user-service \| grep -i token` — 生のトークンが見えたら **ただちに中断して直す**。DB には SHA-256 ハッシュだけがあるべきだ |
| ☐ | 確認リンクが **アクセス可能なアドレス** | `APP_PUBLIC_URL` がクラスター内部の DNS だとブラウザで開けない。以前は特定のホストがハードコードされていた |
| ☐ | リンクをクリック → アカウント有効化（`GET /registrationConfirm`） | `users.enabled = true` |
| ☐ | リンクの **再利用時に拒否** | 2回目のクリックは失敗しなければならない |
| ☐ | ログイン（`POST /api/auth/public/signup`） | JWT の発行 |
| ☐ | 再送（`POST /api/auth/public/resend-verification`） | レートリミットにかかるかも合わせて |
| ☐ | パスワード再設定の2段階 | `forgot-password` → メール → `reset-password` |
| ☐ | 2FA 有効化 → ログアウト → ログイン | `enable-2fa` → `verify-2fa` → ログインのレスポンスが `two_factor_required`（トークンなし）→ `public/verify-2fa`（待機クッキー）→ フルリロード後もログイン状態（REFRESH クッキーで復元） |
| ☐ | トークンの更新（`GET /token/refresh`） | 期限切れ後もセッション維持 |
| ☐ | CSRF トークン（`GET /api/csrf-token`） | フロントがこの値をヘッダーに載せないと書き込みリクエストが通らない |

### ソーシャルログイン — PKCE · Pod 間の引き継ぎ（docs/27 付録 2）

**2つの Pod が起動した状態で確認しないと意味がない。** 認可リクエストをセッションに置いていた頃の欠陥は
Pod が1つだと決して再現しない。

```bash
kubectl -n ecommerce get pods -l app=user-service      # まず2つあるかを見る
```

| | 確認 | 失敗をどこで見るか |
|---|---|---|
| ☐ | ボタンは設定されたプロバイダーのものだけが表示される | `GET /api/auth/public/oauth2/providers` のレスポンスと画面が一致 |
| ☐ | **認可 URL に `code_challenge` がある** | ブラウザの開発者ツール → Network → `/oauth2/authorization/google` の 302 `Location`。`code_challenge=...&code_challenge_method=S256` がなければ PKCE がかかっていない |
| ☐ | Redis に認可リクエストが一瞬できて消える | `kubectl exec -it <redis> -- redis-cli --scan --pattern 'oauth2:authreq:*'` — ログイン途中には見え、コールバック後にはないはずだ |
| ☐ | **ログインを5回繰り返してもすべて成功** | Pod が2つだと、以前の実装はおよそ半分が `authorization_request_not_found` で失敗した。一度成功したからといって先に進まないこと — この欠陥は **再試行すれば済む** 形なので、一度だけ試すと見逃す |
| ☐ | 戻るボタンでコールバック URL に再訪すると失敗 | `GETDEL` で一度だけ使われるので、2回目は拒否されなければならない |
| ☐ | ソーシャルアカウントで 2FA が有効なら確認段階へ進む | `/signin?oauth2fa=1` にリダイレクト |
| ☐ | 🔒 `code_verifier` がブラウザにない | 開発者ツール → Application → Cookies/Storage に `code_verifier` が見えてはならない（サーバーの Redis にだけあるべき） |

> トークン交換で `invalid_grant` が出たら、まず PKCE の変更を疑う — `code_challenge` は
> 送ったのに、コールバックを受けた Pod が `code_verifier` を見つけられなかった場合だ（つまり Redis の接続問題）。

---

### 🔒 ゲートウェイの身元ヘッダーの偽造（docs/27）

**これは必ずやる。** 通ってしまえば認証体系全体が無意味になる。

```bash
# クライアントが直接 User-Id を送る。ゲートウェイが **上書きしなければならない**。
curl -i https://shop.example.com/api/user/profile/address \
  -H "User-Id: 1" -H "User-Email: victim@example.com" -H "User-Role: ROLE_ADMIN"
```

- ☐ **401/403 でなければならない。** 他人の住所一覧が出たら致命的だ
- ☐ ゲートウェイのログに、クライアントが送ったヘッダーが除去された痕跡

---

## 3. カタログ · 検索

画面: `/` → `/browse` → `/product/:slug`

| | 確認 | 失敗をどこで見るか |
|---|---|---|
| ☐ | トップ画面に商品が表示される（`GET /api/product/frontpage`） | 空ならシードデータが未投入 |
| ☐ | 一覧のページネーション（`GET /api/product/products/page`） | |
| ☐ | 詳細（`GET /api/product/{slug}`） | |
| ☐ | 検索（`GET /api/search?...`） | デフォルトは PostgreSQL LIKE |
| ☐ | **2回目の検索が 500 ではない** | 検索結果キャッシュ（`search_cache`）の `Page` のデシリアライズが壊れていたことがある（docs/16 B-27）。**キャッシュヒット時にだけ爆発するので、2回押さないと見えない** |
| ☐ | キャッシュが実際に機能している | `redis-cli KEYS 'search_cache*'` または2回目の呼び出しのレスポンス時間 |
| ☐ | 管理者による商品登録（`POST /api/product`） | **画面で確認する。** 画像3枚 + スタイル画像 + 送料を埋めないと送信できない |
| ☐ | 商品の **編集**（`/admin/allproducts` → 編集） | 名前・ブランド・説明・送料のみ。slug（アドレス）は変わらない — 既存リンクの保護 |
| ☐ | **品切れトグル** | 一覧・詳細にバッジとして残り、**カートへの追加が失敗** しなければならない |
| ☐ | **削除** | 一覧・検索・詳細から即座に消える。⚠️ 画面・API では復旧不可（V8 の SQL のみ） |
| ☐ | **カラーバリエーションの連続登録** | 登録成功バナー → 「カラーバリエーションを続けて登録」→ 画像・色だけを埋めて再登録。同じ商品に SKU が付く |
| ☐ | **一時保存** | フォームを埋めている途中でリロード → テキストフィールドが復元される（画像は選び直し） |
| ☐ | **画像アップロード** | ⚠️ Cloudflare の無料プランは本文 100MB の制限があり、**アプリケーションログに何も残らない**（エッジで切られる）。大きなファイルで一度試す |

> ⚠️ **商品・カテゴリの登録は現在は管理者専用である。** 以前はこれらの書き込み API が
> **認証なし** だった — ゲートウェイの catalog ルートに JwtAuthorization がなく、サービスの
> 唯一の `@PreAuthorize` はコメントアウトされていた。サイトが公開されている間、誰でもトークンなしで
> 商品を登録・改ざんできた。今は二重になっている: ゲートウェイが書き込みメソッド（POST・PUT・
> DELETE・PATCH）に JWT を要求し、catalog-service の AdminWriteInterceptor が
> `ROLE_ADMIN` を要求する（評価の登録だけはログインで十分）。読み取りは以前と同じく公開である。
>
> **管理者アカウントの作り方** — 会員登録はすべて `ROLE_USER` で作られるため、最初の管理者は
> DB で一度昇格させなければならない（`users.users.role_id` → `users.roles`）:
>
> ```sql
> INSERT INTO users.roles (role_name)
>   SELECT 'ROLE_ADMIN' WHERE NOT EXISTS
>     (SELECT 1 FROM users.roles WHERE role_name = 'ROLE_ADMIN');
> UPDATE users.users
>   SET role_id = (SELECT role_id FROM users.roles WHERE role_name = 'ROLE_ADMIN')
>   WHERE email = '<管理者にするアカウントのメールアドレス>';
> ```
>
> ロールは **JWT のクレームに入るので**、昇格後に再ログインしないと反映されない。すでにログイン済みの
> セッションのトークンには古いロールが入っている。
>
> **商品登録画面は直した。** 以前は `setLoading(true)` の後のアップロード・送信ブロックが丸ごと
> コメントアウトされていて `setLoading(false)` に到達できず、ボタンが 'Loading...' のまま永遠に
> 止まっていた。Cloudinary への直接アップロードを取り除いたあと、S3 経路につなぎ直していない状態だった。
> 今は `/catalog-service/api/product` に multipart を送り、失敗したらダイアログで
> 知らせ、どの経路でもボタンが解除される。
>
> 操作してみるときに知っておくこと:
>
> - **画像3枚以上 + スタイル画像1枚** がないと送信されない（クライアント側の検証）。
>   サーバーが2つの値を無条件に使うので、空だと 500 になる — だから手前で止める
> - **送料を空にしておくと送信されない。** サーバーが `new BigDecimal(shippingFee)` を
>   そのまま呼ぶからだ
> - アップロード中はボタンに `Uploading… NN%` が表示される
> - Cloudinary アカウントは **不要である。** 画像の保存は完全に S3 だ（`imageUtil.js`）
>
> 回帰テスト: `frontend/src/components/admin/CreateProduct.test.jsx`
>
> **SKU は空でもよい。** サーバーが `<カテゴリ接頭辞3文字>-<連番6桁>`（例: `FAS-100042`）で
> 作る — 番号は DB シーケンス（V7）なので、レプリカがいくつあっても衝突しない。直接入力すると
> 重複時に 400 で知らせる。`product_sku.sku` には現在 UNIQUE 制約がある。
>
> **すでにシードを入れた DB なら**: シードの古い SKU（`ブランド-カテゴリ-サブカテゴリ`）は V7 が
> 重複だけを整理し、形式はそのままにする（管理者が手で入れた値をむやみに変えてはならないので、
> マイグレーションは最小限にとどめる）。すべてサンプルデータなので新しい形式に統一したいなら —
> 任意である:
>
> ```sql
> UPDATE product.product_sku ps
> SET sku = (CASE WHEN length(regexp_replace(c.category_name,'[^A-Za-z0-9]','','g')) >= 3
>                 THEN upper(substr(regexp_replace(c.category_name,'[^A-Za-z0-9]','','g'),1,3))
>                 ELSE 'PRD' END) || '-' || lpad(ps.skuproduct_id::text, 6, '0')
> FROM product.product p
> JOIN product.category c ON c.category_id = p.category_id
> WHERE ps.product_id = p.product_id;
> ```

### ES に切り替えた場合のみ

| | 確認 |
|---|---|
| ☐ | `ops/03-enable-es-search.sh` が `korean_analyzer` の検証を通過 |
| ☐ | **韓国語の形態素検索** — 「청바지」で検索して「청 바지」のようなものが出るか。nori がないと静かに悪化するだけでエラーは出ない |
| ☐ | インデックスの同期（`POST /api/products/sync`） |

---

## 4. カート → 注文 → 決済（Saga 本体）

画面: `/product/:slug` → `/cart` → `/checkout` → `/order/:id`

| | 確認 | 失敗をどこで見るか |
|---|---|---|
| ☐ | カートに追加（`POST /api/cart/savecart`） | Redisson 分散ロックの経路 — **マネージド Redis（Upstash）を使うならここが最初の関門だ**（docs/32 §6） |
| ☐ | 数量変更 · 削除 | `PUT /api/cart/updatecart`、`DELETE /api/cart/deleteItem/{id}` |
| ☐ | 住所の登録・選択（`POST /api/user/profile/save_address`） | |
| ☐ | クーポンの適用（`POST /api/coupon/applycoupon`） | |
| ☐ | 注文の作成（`POST /api/order/create`） | |
| ☐ | **在庫が正確に1回だけ減算される** | `select stock from product_sku where ...` を注文前後で比較。Saga の再配信時の二重減算が実際にあった |
| ☐ | 決済（`POST /api/pay/confirm`） | |
| ☐ | 注文状態が最後まで進む | `/order/:id` 画面 + `select status from orders` |
| ☐ | カートが空になる | `cart-empty` イベントの経路 |

### 🔒 決済の偽造（docs/35）

**必ずやる。** この経路は以前は完全に開いていた。

```bash
# 自分の注文に対して、決済せずに決済完了を主張する。
curl -i -X POST https://shop.example.com/api/order/payment/paypal \
  -H "Authorization: Bearer <自分のトークン>" -H 'Content-Type: application/json' \
  -d '{"trackingId":"<自分の注文>","paypalOrderId":"위조-1","amounts":0.01,
       "paymentStatus":"COMPLETED","orderStatus":"NOT_PROCESSED"}'
```

- ☐ **400 でなければならない。** 200 ならデプロイを止める
- ☐ order-service のログに `PaypalVerificationException`
- ☐ `select * from orders.payment_outbox_event where ...` — **Outbox に何もあってはならない。**
  拒否レスポンスを受けたのに Outbox に入っていたら、決済はすでに下流に伝播している
- ☐ 正常な決済を1件終えたあと、その `paypalOrderId` を **別の注文** に付けてみる → 拒否
- ☐ 起動ログに `⚠️ PayPal 결제 검증이 꺼져 있습니다` が **ない**。あれば検証が無効になっている

### 失敗経路 — 成功より重要だ

| | 確認 | 方法 |
|---|---|---|
| ☐ | **在庫不足の注文がロールバックされる** | 在庫を1にして2個注文。在庫がマイナスになってはならず、クーポン・カートも元に戻らなければならない（`cart-rollback`、`coupon-rollback`） |
| ☐ | 決済失敗時に注文が宙ぶらりんにならない | PayPal のキャンセルで離脱 |
| ☐ | **同じリクエストを2回送っても注文は1つ** | 冪等性キー。ブラウザのリロードで再現 |

### 非同期経路 — 画面を見るだけではわからない

```sql
-- Outbox が滞っていないか（PENDING が溜まればポーラーが死んでいる）
select status, count(*) from users.outbox_event_p group by status;
select status, count(*) from orders.payment_outbox_event group by status;
```

- ☐ Outbox の `PENDING` が溜まり続けない
- ☐ コンシューマーグループの lag が 0 に収束する（§0 の ④番のウィンドウ）
- ☐ **DLT が空である** — `kafka-console-consumer --topic order.DLT --from-beginning`。ここに何かあれば、それがそのままバグのリストだ

---

## 5. 配送

画面: `/profile/orders` → 注文詳細

| | 確認 | 備考 |
|---|---|---|
| ☐ | 注文後に配送レコードが作られる（`GET /api/deliveries/order/{orderId}`） | |
| ☐ | 配送業者の一覧（`GET /api/deliveries/carriers`） | キーがなければ `SIMULATED` しか出ない — それが正常である |
| ☐ | 配送照会の更新（`POST /api/deliveries/order/{orderId}/tracking`） | |
| ☐ | 配送先の変更（`PATCH .../address`） | 発送後は拒否されなければならない |
| ☐ | 配送のキャンセル（`POST .../cancel`） | |

---

## 6. 通知 · メール · SMS

| | 確認 | 失敗をどこで見るか |
|---|---|---|
| ☐ | 通知一覧（`GET /api/notifications`） | |
| ☐ | 未読数（`GET /api/notifications/unread-count`） | |
| ☐ | 既読処理（`PATCH /api/notifications/{id}/read`、`read-all`） | |
| ☐ | 受信設定（`GET`/`PATCH /api/notifications/preferences`） | |
| ☐ | **拒否しても登録確認・パスワード再設定メールは送られる** | `MailKind.isTransactional()` — これをオプトアウトで止めるとアカウントが作れない |
| ☐ | SSE のリアルタイム通知（`GET /sse`） | 別のタブでイベントを起こしてみる |
| ☐ | 携帯電話の認証（`POST /api/notifications/phone/verification` → `/confirm`） | |
| ☐ | 🔒 **認証番号がログにない** | `kubectl logs -l app=notification-service \| grep -E '[0-9]{6}'` — 見えたらただちに中断。DB にもペッパー付きハッシュだけがあるべきだ |
| ☐ | 🔒 **電話番号がマスキングされる** | ログに `010-****-1234` の形 |

### 実際の送信まで確認するには

```bash
kubectl -n ecommerce set env deploy/notification-service NOTIFICATION_MAIL_PROVIDER=smtp
```

- ☐ 実際の受信箱に届く
- ☐ 🔒 **送信後に `mail_message.params` が空になる** — 確認リンク・再設定リンクは資格情報である（docs/29）。`select params from mail_message where status='SENT'` がすべて null でなければならない
- ☐ SMTP の一時的な失敗時にリトライし、5回で止まる（グレイリスティング）

---

## 7. チャット

画面: `/chat/chatrooms`

| | 確認 | 備考 |
|---|---|---|
| ☐ | ルーム作成（`POST /api/chat/room`）· 一覧（`GET /api/chat/rooms`） | |
| ☐ | 2つのブラウザでリアルタイム送受信 | WebSocket/STOMP |
| ☐ | メッセージが MongoDB に残る | `db.chat.find().sort({_id:-1}).limit(5)` |
| ☐ | リロード後も履歴が維持される（`GET /api/chat/room/{roomId}`） | |

> ⚠️ chat-service は **レプリカ 1 固定** である。WebSocket セッションが Pod に紐付いているので、増やすには
> まずセッション共有が必要だ（docs/21）。Pod が再起動すると接続が切れるのは **正常** である。

---

## 8. 🔒 権限 — 他人のものに手が届くか（docs/27）

アカウントを **2つ** 作り、A のトークンで B のリソースをリクエストする。

| | 試行 | 期待 |
|---|---|---|
| ☐ | `GET /api/order/{B の orderId}` | 403/404 |
| ☐ | `GET /api/user/profile/select/{B の addressId}` | 403/404 |
| ☐ | `DELETE /api/user/profile/delete/{B の addressId}` | 403/404 |
| ☐ | `GET /api/deliveries/order/{B の orderId}` | 403/404 |
| ☐ | `DELETE /api/notifications/{B の id}` | 403/404 |
| ☐ | `PUT /api/admin/update-role`（一般アカウント） | 403 |
| ☐ | `GET /api/admin/getusers`（一般アカウント） | 403 |
| ☐ | `POST /api/review/add/{productId}`（未ログイン） | 401 |

**1つでも 200 ならデプロイを止める。**

### アクチュエーターの公開

```bash
curl -s https://shop.example.com/actuator/env | head
```

- ☐ 外部から `/actuator/env`・`/heapdump` が **開いていない**。シークレットがそのまま出てくる

---

## 9. レートリミット · キャッシュ · 可観測性

| | 確認 | 方法 |
|---|---|---|
| ☐ | ゲートウェイのレートリミット | `for i in $(seq 1 70); do curl -s -o /dev/null -w "%{http_code} " URL; done` → 429 が出なければならない。出なければ Redis が未接続だ |
| ☐ | 認証番号の発行速度の制限 | 料金がかかる経路である |
| ☐ | Prometheus のターゲットがすべて UP | `ops/05-check-observability.sh` |
| ☐ | Grafana ダッシュボードにサービスごとに区別されて出る | すべて `common` にまとまるなら `spring.application.name` の問題（docs/14 B-22） |
| ☐ | Zipkin で1つのリクエストが複数のサービスにまたがって見える | 注文を1つ入れて追跡 |
| ☐ | アラートが実際に届く | `ops/17-configure-alert-receivers.sh` |

---

## 10. 外部化したインフラ（docs/32 を適用した場合）

| | 確認 | なぜ |
|---|---|---|
| ☐ | **Redisson ロックが機能する**（カートに追加） | Upstash 無料プランの Lua・pub/sub サポートは未検証である。`ERR unknown command` やタイムアウトなら Redis をクラスター内に戻す |
| ☐ | 散発的な `prepared statement "S_1" already exists` がない | あればプーラーのエンドポイントを使っている。JDBC URL に `&prepareThreshold=0` が必要だ（docs/32 §9、未解決項目） |
| ☐ | レスポンス時間がローカルに比べて大きく悪化していない | DB が別リージョンだと、クエリ20個のリクエストが 600ms になる |
| ☐ | コネクション上限の余裕 | `select count(*) from pg_stat_activity;` vs `show max_connections` |
| ☐ | provider のコンソールでアクセス元 IP が制限されている | 無料ティアで不可能ならパスワードが唯一の防御線だ |

---

## 11. 確認 **できない** もの — あらかじめ知っておくこと

OCI 1台構成では HPA `minReplicas=1` に下げないとリソースが合わない（docs/32 §2）。その状態では
**このリポジトリがレプリカ 2 で経験したバグは再現しない:**

- Outbox ポーラーがレプリカごとに同じイベントを発行する
- メール・SMS のディスパッチャーがロックなしで同じ行を2回拾う

並行性は `ops/12-staging-rehearsal.sh` や prod オーバーレイ（レプリカ 2）で **別途** 確認しなければならない。
「本番ではちゃんと動いてましたよ」は、これらの項目については証拠にならない。

---

## 12. 結果の記録

見つけたものはこの形式で残す。**そのまま次の回帰テストのリストになる。**

```
[番号] 画面/エンドポイント
  再現:      何を押したか（他人がそのままたどれるように）
  期待:
  実際:
  手がかり:  ログ · DB クエリ結果 · DLT · lag のうち何を見てわかったか
  分類:      🔴 デプロイ中止 / 🟠 直すべき / 🟡 把握したうえで見送る
```

🔴 は §2 のヘッダー偽造、§8 の権限、ログに残ったシークレットの3つだけである。残りは優先順位を付けて
順番に処理する。

---

## 13. このドキュメントの限界

- **ここに書かれたエンドポイントはソースから抜き出したが、実際にこの巡回を回したことはない。** 画面のフローと
  API のつながりがずれている箇所があるかもしれない — それも発見事項として記録すること
- 項目は **既知のリスク**（docs/14 · 16 · 21 · 27 · 29 · 32 で実際に起きたもの）を中心に
  選んだ。一度も問題がなかった領域は相対的に薄い
- 自動化はしなかった。1回目で何が壊れるかわからない状態でスクリプトを書くと、大部分を
  捨てることになる。**2回目から** 繰り返される項目を `ops/06` に取り込むのが正しい
