# 100. 公開デモアカウント — 会員登録なしで試す

> 2026-11-21 · 第 1 段階(バックエンド · 画面)· 第 2 段階(毎日の初期化、§5-1)· 第 3 段階(注文上限 · 管理表示 · チャット上限、§5-2)— **スキーマ変更なし**。残りは本番での有効化(§4)。
> 韓国語版: [100-demo-accounts.ko.md](100-demo-accounts.ko.md)

## 0. なぜ

採用担当者 · 訪問者が会員登録なしでカート → 注文 → テスト決済まで見られるようにする。本番の決済はすべてテストモード(Stripe `sk_test_` · PayPal sandbox · ポートワン CBT · 銀行振込は自動決済なし)— 実際の請求はない。

複数の訪問者がアカウントを**使い回す**。止めるのは「他の訪問者を追い出す · アカウントを乗っ取ること」と「他人に見える投稿」。

## 1. 決めたこと(2026-11-21)

| 質問 | 決定 |
|---|---|
| アカウントの配り方 | ログイン画面のボタン + アカウント群(既定 5)— パスワードは誰も知らない(公開しない) |
| 他人に見える投稿 | レビューは止め、相談チャットは許可(管理者にだけ見える) |
| 初期化 | 毎日 1 回(次の段階) |
| 注文 · テスト決済 | 許可 |
| スキーマ | 変えない — デモ表示は既存の `users.sign_up_method = 'demo'`、順番は Redis |

## 2. 流れ

```
ログイン画面 ── GET  /user-service/api/auth/public/demo-login  → {enabled}   (無効ならボタンなし)
             └─ POST /user-service/api/auth/public/demo-login  → 通常ログインと同じ応答(ヘッダー access · REFRESH Cookie)
                                                                  トークンに demo=true
ゲートウェイ JwtAuthorization ── demo=true かつ止める書き込みなら 403「デモアカウントではご利用いただけない機能です。」
画面 ── user.demo なら最上部に案内バー(DemoBanner)
```

- **作成** — `DemoAccountService.provision()`(起動時、`DEMO_ACCOUNTS_ENABLED=true` のときだけ): `demo1 〜 N@demo.invalid`(`.invalid` はメールが出ない予約ドメイン)、パスワードは 32 バイトの乱数、`emailVerified` · `enabled`、ロール ROLE_USER。加入と同じ道で `user-created` アウトボックス行 — ウェルカムクーポン · チャット会員の複製ができる。同じメールの通常アカウントがあれば触らない。
- **払い出し** — 候補は `sign_up_method='demo'` · 有効 · 未ロック · 未退会(60 秒キャッシュ)、順番は Redis `INCR demo:accounts:next` — 原子的なので同時に押した二人は別のアカウント、一周すると最も前に払い出したもの。Redis が失敗したら任意のアカウント(止めない)。アカウント数より訪問者が多ければ共用になる。
- **しないこと** — 端末記録 · 新端末通知 · ログインリスク確認(受け取る人のいないドメイン · 訪問者ごとに新端末)。
- **トークン更新** — `JwtUtils.generateTokenFromUser` が `user.isDemo()` なら `demo` クレームを載せる。リフレッシュも同じメソッドなので表示が続く。

`signUpMethod` を決めるのは加入(`email`)とソーシャルログイン(設定の provider 名 — google · github)だけなので、外から `demo` は作れない。

## 3. ゲートウェイが止める書き込み(`DemoAccessPolicy`)

読み取り(GET · HEAD · OPTIONS)は止めない。経路は **RewritePath 前の元の経路**(サービス名まで)で比べる — フィルターが書き換えの後に回るため。重なった `/` · 末尾 `/` · パーセントエンコードで回り込めない。

| まとまり | 経路(`/user-service` …) | なぜ |
|---|---|---|
| アカウント · 資格 | `/api/user/profile/update-password` · `/api/user/profile/me` · `/me/**` | 変えると他の訪問者が入れない |
| セッション · 端末 | `/api/user/profile/sessions/**` · `/devices/**` | 「全端末からログアウト」は共用の訪問者を全員切る |
| 2 段階認証 · パスキー | `/api/auth/enable-2fa` · `disable-2fa` · `verify-2fa` · `2fa/**` · `sms-2fa/**` · `/api/user/passkeys/**` | 有効にすると他の訪問者が入れない |
| 退会 | `/api/user/withdrawal/**` | |
| 他人に見える投稿 | `/api/review/**` | 商品ページに残る |
| 認証 SMS | `/notification-service/api/notifications/phone/**` | 任意の番号へ送らせない(費用 · スパム) |

許可: 閲覧 · カート · お気に入り · 住所録 · 注文 · テスト決済 · 通知 · 相談チャット。指標 `gateway_demo_blocked_total`。

## 4. 有効化 · 無効化

- 有効化: ConfigMap に `DEMO_ACCOUNTS_ENABLED: "true"` → `ops/28 --config-only --restart user-service` — 次の起動でアカウントを作る。数 `DEMO_ACCOUNTS_COUNT`(1 〜 50、既定 5)· ドメイン `DEMO_ACCOUNTS_EMAIL_DOMAIN`。
- 無効化: 同じ値を `false` — ボタンが消え、デモログインは 404。発行済みのトークンは寿命(最大 15 分)· リフレッシュ(14 日)まで有効 — すぐ切るにはデモアカウントをロックする(管理画面のロック、候補から外れる)。
- イメージ: gateway · user · frontend · cart · notification · order · chat。マイグレーションなし。

## 5. 試験

| 試験 | 見るもの |
|---|---|
| gateway `DemoAccessPolicyTest`(4) | 止める書き込み 403 · 回り込み(`//` · 末尾 `/` · `%77`)· 許可操作は通過 · 通常トークンは同じ経路も通過 — 書き換え後の経路で判定すると失敗(変異) |
| user `DemoLoginControllerTest`(5) | 無効 404 · 状態 GET · アカウントなし 503 · トークンの `demo` · 通常アカウントにはない — クレームを外すと失敗(変異) |
| user `DemoAccountClaimTest`(コンテナ 4) | 実スキーマの候補 SQL(通常 · ソーシャル · 無効 · ロック · 退会を除外)· 順番に回す · Redis 失敗 · 無効 — `sign_up_method` 条件を外すと失敗(変異) |
| 画面 `SignInDemo.test.jsx`(3)· `DemoBanner.test.jsx`(2)· `jwtUser.test.js`(+1) | 無効ならボタンなし · 押すと demo ユーザーで login · 失敗トースト · 案内バー |

## 5-1. 毎日の初期化(第 2 段階)

`DEMO_ACCOUNTS_ENABLED=true` のときだけ。注文は消さない — 決済 · アウトボックス · サーガが絡む(§6 の上限で扱う)。

| 時刻(UTC) | サービス | すること |
|---|---|---|
| 19:00 | user-service `DemoAccountReset` | クラスターで一つの Pod(`SchedulerLease` `demo-account-reset`、30 分)— すべてのデモアカウント(ロック · 無効を含む)の `users.wishlist` · `shipping_address` · `known_device` を一トランザクションで消し、notification `POST /api/notifications/internal/demo-reset`(経路規則 `=user-service`)で通知 · お知らせ既読表示を消す。通知呼び出しの失敗は翌日に再度 |
| 19:05 | cart-service `DemoCartReset` | user-service `GET /api/auth/user/demo-accounts`(経路規則 `=cart-service`、トークンなしで呼ぶのでその経路だけ permitAll — 止めるのは経路規則 · InternalCallGuard)で一覧を受け、カートを空にしてコミット後にキャッシュを消す。冪等なので Pod ごとに回る |

どちらもゲートウェイ経由の要求(身元ヘッダーあり)は管理者でも 403。時刻は `demo.accounts.reset.cron` · `demo.accounts.reset.cart-cron`。試験: user `DemoAccountResetTest`(5)· `DemoAccountInternalControllerTest`(2)· `DemoAccountClaimTest` +1(実スキーマの DELETE)· cart `DemoCartResetTest`(3)· notification `DemoNotificationResetTest`(コンテナ 3)。

## 5-2. デモだけの上限(第 3 段階)

ゲートウェイが検証済みのデモトークンにだけ `User-Demo: true` を載せる(客が送った同じヘッダーは `IdentityHeaderScrubFilter` と JwtAuthorization が消す)。

| 何 | どこ | 上限 |
|---|---|---|
| 決済前の注文 | order `DemoOrderLimit`(注文作成の前) | 決済開始前(`NOT_PROCESSED`)+ 入金待ち(`UNPAID`)の合計が `demo.orders.max-pending`(既定 3)以上なら 400 — 同じ冪等キーの再要求は作成済みの注文を返すので止めない |
| 相談チャット | chat `StompHandler` · `DemoChatLimiter` | CONNECT がトークンの demo クレームでセッションに表示、SEND はアカウントごとに 10 分の窓で `chat.demo.max-messages`(既定 20)件 — 超えたらそのメッセージだけ捨てる(接続はそのまま)、Redis 失敗は通過 |
| 管理表示 | 画面の注文管理(`Refunds.jsx`) | 客のメールが `@demo.invalid` なら「デモ」表示(`util/demoAccount` — 表示用、権限判断ではない) |

試験: order `DemoOrderLimitTest`(3)· gateway `DemoAccessPolicyTest` +1 · chat `StompHandlerDemoLimitTest`(3)· `DemoChatLimiterTest`(2)· 画面 `Refunds.test.jsx` +1 · `demoAccount.test.js`(1)。

## 6. 次の段階

1. ~~毎日 1 回の初期化~~ — §5-1。
2. ~~デモ注文の上限 · 管理者の「デモ」表示~~ — §5-2。
3. ~~相談チャットの速度制限~~ — §5-2。
4. 本番で有効化(§4)— イメージ gateway · user · frontend · cart · notification · order · chat をデプロイした後 `DEMO_ACCOUNTS_ENABLED: "true"`。
