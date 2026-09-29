# 41. API セキュリティ点検 — www-security デモをこのシステムに照らし合わせる

**日本語** | [한국어](41-api-security-applied.ko.md)

> 🕰️ **状態（2026-09-27）— 一部古い。** クーポン · カートの身元は `User-Id` ヘッダ、注文金額はサーバーが決め（86010e1）、サービス間 mTLS は適用されました。

`ruke79/www-security`（『API Security in Action』の章別デモ + 脆弱性ラボ 17 種）の
チェックリストを ecommerce-msa に一つずつ照らし合わせた。デモの結論は 2 行だ:

> 認証（誰なのか）だけでは足りず、**認可（何ができるのか）** こそが本当の防衛線だ。
> **クライアントが選べる値**は、身元にも、キーにも、金額にもなり得ない。

この文書は「デモの項目 → このシステムの状態 → やったこと」である。直したものは 2 つ、調整したものは 1 つ、
すでに正しくなっていたものはそのまま書き、再び疑わなくて済むようにした。

---

## 直したもの

### シナリオ 1 — Rate Limit 回避（クライアントがバケットのキーを選ぶ）

**デモ**: `RateLimitingFilter` が `X-Forwarded-For` をそのままキーに使うので、ヘッダをリクエストごとに
変えれば制限がないのと同じになる。

**このシステム**（`gateway-server/.../resolver/CustomUserKeyResolver.java`）— 同じ罠が
**2 か所**にあった:

| 読んでいたもの | なぜ操作できるのか |
|---|---|
| `X-User-Id` フォールバック | ゲートウェイが入れる名前は `User-Id` だ。`X-User-Id` は**誰も入れない**ので、その値は常にクライアントが送ったものだ。未ログインのリクエストに値を変えながら付ければ、リクエストごとに新しいバケット |
| `X-Forwarded-For` の先頭項目 | プロキシはこのヘッダに**追記する**だけで消さない。先頭項目はクライアントが最初に書いた値 |

さらに悪いのは、既存テスト `CustomUserKeyResolverTest` が**まさにこの動作を固定**して
いたことだ（「X-User-Id があれば user: キーを使う」「XFF の先頭 IP にフォールバックする」）。回避
経路を回帰テストが守っていた。

**やったこと**:
- キーの優先順位を `User-Id`（ゲートウェイが検証後に入れる）→ `CF-Connecting-IP`（Cloudflare
  エッジが実際の接続 IP で**上書きする** — クライアントが同じ名前を送っても置き換えられる）→ ソケットの
  リモートアドレス、に変えた。`X-User-Id`・`X-Forwarded-For` は読まない。
- `IdentityHeaderScrubFilter` が `X-User-Id/Email/Role` の変種も消す。身元に見える
  名前は入ってきた端から消しておけば、後で誰がどの名前で読んでもクライアントの値は届か
  ない。
- テストを反転させた: なりすました `X-User-Id` と XFF は**無視されなければ**通らない。

### シナリオ 10 — IDOR / BOLA（他人のクーポンを適用する）

**デモ**: `/api/lab/idor/orders/{id}` が所有権を見ないため、`?asUser=alice` で bob の
注文を開ける。OWASP API セキュリティ 1 位。

**このシステム**（`coupon-service/.../CouponService.applyCoupon`）— リクエスト**本文の**
`userEmail` でユーザーを探していた:

```java
userServiceClient.findUserByEmail(request.getUserEmail(), token)   // 本文の値
Coupon coupon = getCouponNotUsed(request.getCouponName(), response.getUserId());
couponUseProducer.publish(CouponUseRequest.builder().userId(response.getUserId()) ...);
```

ログインさえしていれば、本文に他人のメールアドレスを入れて:
1. その人がその名前の**未使用クーポンを持っているか**を知り（存在有無の漏えい）、
2. その割引率で計算された `totalAfterDiscount` を受け取り、
3. **その人の id でクーポン使用イベント**を発行させることができた。

同じコントローラの `/coupons` はすでに `User-Email` ヘッダを使っていた（docs/27 で直した）。
このエンドポイント 1 つだけが漏れていた —「1 か所だけ塞ぐと新しいエンドポイントで漏れる」というデモ
付録 A.3 の指摘そのままだ。

**やったこと**:
- コントローラが `@RequestHeader("User-Email")` で身元を受け取り、なければ 401。
- サービスは本文の `userEmail` を**読まない。** フィールドは旧クライアントとの互換のため残すが
  `@Deprecated` と表示した。本文とヘッダが異なれば警告ログ（値は残さない）。
- フロントエンド（`util/api.js`、`Summary.jsx`）はメールアドレスをもう送らない。
- `CouponApplyOwnershipTest`: 本文に他人のメールアドレスを入れても、照会はヘッダのメールアドレスでのみ行われなければ
  ならない。コントローラのシグネチャも固定する — `(request, token)` の 2 引数版に戻ると失敗。

⚠️ この欠陥は docs/40 の 10 番（「金額をクライアントが決める」）と**連鎖**する。他人のクーポンで
計算された金額を受け取った後、その `total` を注文作成にそのまま送れば、サーバーが金額を再計算
しない限り割引がそのまま効く。10 番はまだ開いている（order-service の確認が必要）。

---

## 調整したもの

### 第 9 章 — トークン失効（Introspection / Revocation）

**デモの教訓**: 自己エンコード（JWT）トークンはローカル検証だけでは失効が反映されない →
**寿命を短く + refresh で補う**。

**このシステム**: ゲートウェイは署名だけを検証する（introspection なし）。`RedisSessionManager` は
名前に反してインメモリの `HashMap` で、それを読む `JwtUtils.isRequestAuthorized` は
**どこからも呼ばれていない。** つまりログアウトしても、アカウントが乗っ取られても、発行済みのアクセストークンは期限まで
有効だ。その期限が **2 時間**だった。

**やったこと**: アクセストークンを 15 分に短くした（`user-service/application-local.yml`）。ユーザーの
体感はない — `TokenRefresher` が期限の 10 秒前にリフレッシュ Cookie（14 日）で静かに更新し、
リフレッシュトークンはサーバーが管理するのでログアウト時に本当に無効化される。盗まれたアクセストークンの
有効な窓が 2 時間から 15 分に縮む。

残っているもの: 本当の失効が必要なら、`RedisSessionManager` を実際の Redis に移し、ゲートウェイが
それを見る。今は 15 分がその価値の大部分を果たしている。

---

## すでに正しくなっていたもの（確認済み）

| デモ項目 | このシステム | 根拠 |
|---|---|---|
| #15 JWT `alg:none` / 弱いキー | 防御済み | jjwt 0.12.6 `Jwts.parser().verifyWith(key())` — 署名のない JWS と `none` を拒否する。キーは Secret の `JWT_SECRET_KEY` |
| #10 IDOR — 注文照会 | 防御済み | `OrderDataService.getOrder` が `customerId` を照合し、他人のものなら **null → 404**（存在有無も漏れない）。`OrderOwnershipTest` あり |
| #10 IDOR — 配送照会/変更/キャンセル | 防御済み | `DeliveryController` が `User-Id` で `findByOrder(userId, orderId)` — クエリに所有者条件（デモ付録 A.3 の推奨パターン 1） |
| #10 IDOR — カート | 防御済み | `CartController` が `User-Email` ヘッダだけを使う（docs/27） |
| #10 IDOR — 住所選択/削除 | 防御済み | `@AuthenticationPrincipal` の username でサービスを呼ぶ |
| #11 Mass Assignment | 該当なし | エンティティを `@RequestBody` で直接受けるコントローラがない。すべて DTO |
| #9 XSS | 防御済み | React がエスケープする。`dangerouslySetInnerHTML` の使用箇所なし。お知らせ本文はプレーンテキストで描画する |
| 身元ヘッダ偽造（User-Role: ROLE_ADMIN） | 防御済み | `IdentityHeaderScrubFilter`（グローバル、HIGHEST_PRECEDENCE）+ B-53（docs/39 §7, af776f9） |
| #12 SSRF, #13 Path Traversal, #14 Command Injection, #16 SSTI, #17 NoSQL | 該当なし | URL を受け取って取得する機能・シェル呼び出し・テンプレート評価・Mongo クエリの組み立てがない。chat-service の Mongo は Spring Data メソッドだけを使う |
| ch4 mTLS | 部分的 | サービス間 TLS は内部 CA で構成されているが、**クライアント認証（mTLS）** ではない。ゲートウェイが身元をヘッダで渡す構造なので、下位サービスは「ゲートウェイから来た」ことをネットワークに依存している。サービスメッシュ（Istio など）なしに手で付けるには大きい — 先送りする |
| ch8 DPoP | 該当なし | フィンテック級の要件ではない。15 分の寿命 + httpOnly リフレッシュで十分 |

---

## デモの第 3 部「CI セキュリティ回帰テスト」をこのシステムで行う方法

デモは「安全なエンドポイントは攻撃ペイロードを遮断しなければならない」を自動テストにせよと言う。
このリポジトリでその役割を果たしているもの:

| 攻撃 | テスト |
|---|---|
| 身元ヘッダ偽造 | `ConfigurationGuardrailTest` B-53 |
| レートリミッタのキーのなりすまし | `CustomUserKeyResolverTest`（この文書） |
| 他人のクーポン適用 | `CouponApplyOwnershipTest`（この文書） |
| 他人の注文照会 | `OrderOwnershipTest` |
| アクセストークンをログに出力 | `ConfigurationGuardrailTest` B-52 |

新しいエンドポイントがユーザー所有のリソースを扱うなら、**同じ形のテストを 1 つ追加**する —
「本文/クエリに他人の識別子を入れても、検証済みの身元でのみ照会する」。コントローラ 1 か所で塞ぐ
だけでは足りないことが今回改めて明らかになった（`/coupons` は塞いだのに `/applycoupon` は
漏れていた）。
