# 85. まとめリリースのデプロイ・ランブック — 10-10 以降に溜まった修正を本番へ一度に

**日本語** | [한국어](85-release-deploy-runbook.ko.md)

> **状態(2026-10-24)** — **デプロイ完了: 本番は `4415e498565c`** です(1 回目は §7-1)。同じ日の続くデプロイ(`6501d3fd8e60`)と 2026-10-25 · 26 の続くデプロイ四つ(`dc25b0f8a920` · `61f546a8b125` · `d837605d7902` · `d68f0307e979`)で、今の本番は **`d68f0307e979`** です(§0「続くデプロイ」)。残りは §8。以前の本番は `b7c5053a45e8`(2026-10-10、[docs/72](72-model-refactor-deploy-runbook.md))でした。その後 improve にコミットが 260 件ほど溜まりました。決済 · クーポン · アウトボックスの欠陥修正、セキュリティ(身元ヘッダー · Eureka 書き込み · セキュリティヘッダー)、注文の進行タイムライン(docs/76)、機能フラグ(docs/74)、負荷測定の CPU 修正(docs/83)、カート画面のエラー修正が入っています。
> **対象バージョン**はこの文書が入ったコミット以降の improve 最新コミットです(パイプライン緑、12 桁 SHA)。デプロイ前に §0 の表に書きます。
> 戻せないマイグレーション(ROLLBACK-UNSAFE)はありません。そのため **自動復帰が有効なデプロイ**です。ただし user V18 は重複行を消すので、バックアップは必須です(§3)。

## 0. 記録

| 項目 | 値 |
|---|---|
| 対象バージョン(12 桁) | `4415e498565c`(1 回目 `eeecf0344023` は復帰 — §7-1) |
| リハーサルの日付 · 結果 | 2026-10-24 06:42 UTC · 本番バックアップのコピー 03:00 UTC(783MB) · マイグレーション `c22cd7bb5990` — **九つのサービスが通過**(下記) |
| 事前点検(§2)の日付 | 2026-10-24 本番 — 出発バージョンのまま、カート · バナー · 企画展 0、`pg_trgm` なし(V21 が作る) |
| バックアップのフォルダー(§3) | `~/backups/pre-eeecf0344023-20261024T070609Z` — 5.0G(物理 · 論理 · Mongo `chat` 7 · `migration_marker` 1 · `auto_sequence` 1) |
| デプロイ開始 · 終了 · 所要時間 | 1 回目 2026-10-24 `eeecf0344023` — スモーク 3 番(429 が出ない)で自動復帰(§7-1) · 2 回目 ~08:25 UTC `4415e498565c` — 完了(自動復帰なし) |
| スモーク | 2 回目通過 — ゲートウェイの流入制限の温め 941ms |
| デプロイ直後 · 確認(§5 · §6) | アウトボックス FAILED 0 · coupon-rollback · cart-empty DLT 0 · Redis 整理。スキーマ cart 7 · coupon 9 · order 28 · user 18 · catalog 22、Pod 再起動 0 · メモリ 4 〜 501Mi、ゲートウェイ ClusterIP 8000、セキュリティヘッダー · font-awesome 0 · 一覧応答に description 0 · LazyInitializationException 0、ops/05 すべて通過。payment.DLT · order.DLT の notification グループ 6 組はデプロイ前(10-16 〜 10-22)— 再送しない |
| 続くデプロイ(2026-10-24 UTC 夕方) | `6501d3fd8e60` — 通知 NPE(§7-2) · NFKC(catalog V23、[docs/60 §14](60-i18n-audit.md)) · PortOne 日本決済(リダイレクトの戻り · 購入者名 · 店舗情報 · 文言、[docs/49 §6](49-payment-providers.md)) · 商品一覧フィルター。frontend イメージ · バンドルのチャネルキー · 店舗情報を確認、`/browse` 正常、payment.DLT · order.DLT は 8 件のまま(最新 10:32 UTC — デプロイ前) · coupon-rollback · cart-empty 0。デプロイ後の Stripe の新しい注文 → 「注文受付」メールが届き、payment.DLT は 8 件のまま(通知 NPE の修正を確認)。PortOne カード決済は決済画面がイニシスのテストサーバー(`devcbt.inicis.com/cbtauth`)へ移った後に接続タイムアウト — PC · 本番インスタンスとも `curl` 15 秒超過なのでイニシス側(PortOne に問い合わせ) |
| 続くデプロイ 2(2026-10-25 UTC 朝) | `dc25b0f8a920` — PortOne `customData` の二重包み(order · frontend) · チェックアウトで PortOne を隠す(`HIDDEN_METHODS`、イニシスの回答まで) · 色を変えてもサイズを保つ · PortOne の電話番号は数字のみ([docs/49 §6](49-payment-providers.md))。マイグレーションなし、253 秒。スモーク通過(429 11 回)、order-service · frontend イメージ `dc25b0f8a920`、チェックアウトに PortOne なし · 色を変えてもサイズそのまま、payment.DLT · order.DLT は 8 件のまま(最新 2026-10-24 10:32 UTC — デプロイ前)。戻し先 `6501d3fd8e60` |
| 続くデプロイ 3(2026-10-25 UTC 昼) | `61f546a8b125` — フィルター検索は件数を最後まで数えない(catalog、[docs/83 §2.8](83-load-test.md)) · chat の続き(プロデューサーのブロック上限 5 秒 · 相談通知の非同期失敗 · `user-created` earliest) · CSP の強制(frontend、[docs/57 §19](57-remaining-priorities.md))。マイグレーションなし、252 秒。スモーク通過(429 15 回)。`/browse?category=16&price=4_` も価格の下限を 1 ずつ変えても遅れなく一覧(デプロイ前はすべて 503 — 件数で 42 秒)、応答ヘッダー `content-security-policy` が強制(Report-Only なし)。戻し先 `dc25b0f8a920` |
| 続くデプロイ 4(2026-10-26) | `d837605d7902` — 価格の下限 > 上限(「4 ~ 0」)は DB に聞かず 0 件 · どの検索も件数を数えない(サブカテゴリ lab equipment が常に 503 — 一覧 16ms · 件数が 5 秒超、[docs/83 §2.8](83-load-test.md))。マイグレーションなし、252 秒。スモーク通過(429 19 回)。`subCategory=102` 200 · 0.35 秒(デプロイ前 503 · 5.7 秒)、`category=16&lowPrice=4&highPrice=0` 200 · 0.99 秒。デプロイ前に `VACUUM (PARALLEL 0, ANALYZE) product.product` · `product_subcategory`(並列 VACUUM は `/dev/shm` 64MB で失敗 — Pod に shm のマウントなし)、可視ページ 97,679 / 97,715。戻し先 `61f546a8b125` |
| 続くデプロイ 5(2026-10-26) | `d68f0307e979` — 別カテゴリのサブカテゴリを選んだ組み合わせは 0 件確定(画面は親も変え、サーバーは DB に聞かない — [docs/83 §2.8](83-load-test.md)) · 身元ヘッダー enforce(ConfigMap) · Eureka `eureka.server.my-url`(自己複製をなくす)。マイグレーションなし。スモーク通過。`ops/37`: モード · ルール · 身元ヘッダーすべて enforce、① · ①-2 · ①-3 0、② 403/200、証明書なしの `User-Role: ROLE_ADMIN` は 403(②-2)。戻し先 `d837605d7902` |

## 1. 何が変わるか

### 1-1. スキーマ — すべて追加だけ(列 · 表 · インデックス)

| サービス · DB | マイグレーション | すること | 戻し |
|---|---|---|---|
| cart · `cartdb` | V6 · V7 | 処理台帳 `cart.processed_event`(カートを空にする処理の冪等)· `processed_at` インデックス | イメージだけ |
| catalog · `productdb` | V19 〜 V22 | `processed_at` インデックス、`product(category_id, product_id)`、**`pg_trgm` 拡張** + 有効な商品の部分インデックス二つ、外部キー列のインデックス四つ | イメージだけ |
| coupon · `coupondb` | V6 〜 V9 | `used_order_id` · `payment_order_id` · `payment_until`(NULL 可の列)、`(user_id, status)` インデックス · 重複していた `idx_coupon` の削除、`processed_at` インデックス | イメージだけ |
| order · `orderdb` | V26 〜 V28 | `create_key`(冪等キー)+ 部分ユニーク、`payment_attempt` · `payment_attempt_until`、`order_product(order_id)` インデックス | イメージだけ |
| user · `userdb` | V17 · V18 | `scheduler_lock` 表、**レビュー · お気に入りの重複整理(行の削除)** + ユニーク | イメージだけ(消した行はバックアップから) |

chat · pay · notification · delivery のスキーマはそのままです。最後のマイグレーションのコミットは 2026-10-20 で、2026-10-21 のリハーサル(本番のコピー)で九つのサービスがすべて通りました。そのとき catalog V18 → V22 が 19 秒、user V18 の重複は 0 でした。

### 1-2. サービス間の契約 — 順序はあるが ops/28 は保証しない

`ops/28` は 1 段目でゲートウェイを除く **すべてのサービスを一度に** 上げ、2 段目でゲートウェイを上げます。そのため「coupon · cart を order より先に」は守られません。単一ノードなのでローリングの窓は数分で、その間に起こり得ることは下のとおりです。**デプロイ直後(§5)の確認で代えます。**

| 変わったもの | 窓の中で起こり得ること | デプロイ直後にすること |
|---|---|---|
| `coupon-rollback` Avro v2(orderId) | 旧 coupon Pod が v2 を読めず `coupon-rollback.DLT` へ | DLT を元のトピックへ送り直す(§5-2) |
| `cart-empty` Avro v3(orderId) | 旧 cart Pod が読めず `cart-empty.DLT` へ | 同じ |
| order アウトボックスの新しい行の種類 `product-update` · `cart-empty` · `order-status` | 旧 order Pod のリレーが知らない種類なので FAILED | `STARTED` に戻す(§5-1) |
| クーポンの決済押さえ `/api/coupon/internal/payment-claim` | 旧 coupon が 404 → order は以前どおり通す | なし |
| クーポン見積もりの本文(カート) | 旧 coupon は本文を無視して以前どおり読む | なし |
| クーポンの `EXPIRED` 結果 | 旧 order が知らず「クーポンを確認できず」で止める(しばらく) | なし |
| config-server の Bus 削除 + マニフェストのバス無効化 env の削除 | 旧イメージに新マニフェストだと health DOWN — イメージとマニフェストが同じデプロイなので起きない | なし |

### 1-3. 動作が変わるもの(利用者 · 運用者が気づけるもの)

- **カート**: カートのない利用者のエラー画面がなくなります。
- **ログイン · アカウント**
  - 管理者によるロック · 期限切れがパスワードとソーシャルログインも止めます。
  - 2FA の有効化は最近のログインから 10 分以内だけです。
  - ログイン失敗のロックは Redis に移しました。今ロックされているアカウントはデプロイで解除されます。
  - 通知設定の変更が初めて DB に保存されます。以前変えたつもりの設定は既定値で見えます。
- **登録確認メールのリンク · ソーシャルログインの開始**: 今は 404 で、デプロイで直ります。
- **注文 · 決済**
  - すでに決済された注文の二回目の決済を断ります。
  - 注文ごとに決済画面は一つだけ開きます(別のタブは 10 分間断る)。
  - 銀行振込の入金待ちは顧客あたり 3 件までです。
  - チェックアウトの金額は catalog の現在の値に合わせ直します。
- **商品一覧の応答**: カードのフィールドだけを載せます(説明などは詳細だけ)。トップの構成は Pod のメモリに 30 秒とどまります。一覧 · 検索カードの評価はキャッシュの期限(30 · 60 分)まで古い値です。
- **フロントエンド**: セキュリティヘッダーが付きます(CSP は Report-Only)。Font Awesome CDN を外しました。
- **ログ**
  - サービスのメソッドごとの `executed in N ms` の行とゲートウェイのリクエストパスの INFO 行がなくなります。
  - JVM 起動時にストアのパスワードがログに出ません(`JAVA_TOOL_OPTIONS` から外した)。
- **メモリ**: JVM の最大ヒープがコンテナ上限の 25% → 50% です。

## 2. 事前点検 — 本番 DB、読み取りのみ

件数だけを見ます。利用者 id · メールは出力しません。

```bash
# 本番インスタンスで実行する — 実習クラスターではない(先に `hostname` · `kubectl config current-context` を見る)。
# `!` を使わない — 対話の bash は二重引用符の中の `!~` を前のコマンドに置き換える(history expansion)。
q(){ kubectl -n postgres exec -i deploy/postgres -c postgres -- psql -U postgres -d "$1" -tAX -v ON_ERROR_STOP=1 -c "$2"; }
# 今適用されているバージョン — 出発点(cart 5 · catalog 18 · coupon 5 · order 25 · user 16)でなければ止める。
# catalog の履歴は product スキーマにある(public の V5 は古い記録)。
for db in cartdb coupondb orderdb userdb chatdb paydb notificationdb deliverydb; do
  echo "$db: $(q "$db" 'SELECT max(version::int) FROM flyway_schema_history WHERE success')"
done
echo "productdb: $(q productdb 'SELECT max(version::int) FROM product.flyway_schema_history WHERE success')"

# cart: 注文が止まるカートの行(数量 1 〜 1000 の外)— 0 であること(2026-10-21 本番 0)
q cartdb "select count(*) from cart.cart_product where cart_id is not null and (qty < 1 or qty > 1000)"

# catalog: バナー · 企画展のリンクが http(s) · '/…' の外なら画面が止める — 0 であること
q productdb "select count(*) from content.banner where link_url is not null and link_url <> '' and not (link_url ~ '^(https?://|/)')"
q productdb "select count(*) from content.event  where link_url is not null and link_url <> '' and not (link_url ~ '^(https?://|/)')"

# catalog: pg_trgm を DB の所有者(product)が作れるか — すでにあればすぐ終わる
q productdb "select extname from pg_extension where extname = 'pg_trgm'"
```

**リハーサル(推奨)**: その間に本番データが増えたので、最新の本番バックアップのコピーでもう一度回します。
— そのコピーを載せた PostgreSQL で対象バージョンの Flyway を回し、九つのサービスが通る · ROLLBACK-UNSAFE なし · user V18 の重複数を見ます。

**リハーサルの記録(2026-10-24、本番バックアップ 03:00 UTC)** — その後マイグレーションのファイルは変わっていないので、対象バージョンが `c22cd7bb5990` より後でもこの結果がそのまま当てはまります。

| サービス · DB | バージョン | 所要時間 | 件数が変わった表 |
|---|---|---|---|
| cart · `cartdb` | V5 → V7 | 6.2 秒 | `cart.processed_event` 新しい表(0) |
| catalog · `productdb` | V18 → V22 | 23.9 秒(Flyway 17.8 秒 — インデックス) | なし(履歴の表だけ) |
| coupon · `coupondb` | V5 → V9 | 6.2 秒 | なし |
| order · `orderdb` | V25 → V28 | 6.2 秒 | なし |
| user · `userdb` | V16 → V18 | 6.2 秒 | `users.scheduler_lock` 新しい表(0) — **V18 が消した重複 0 行** |
| chat · pay · notification · delivery | そのまま | 各 6 秒 | なし |

- ROLLBACK-UNSAFE なし、失敗した履歴 0。6 秒台はほとんどが Flyway コンテナの起動です。
- カートのない行 14 個(数量 0、2026-10-21 と同じ)は誰も読みません — 削除は任意。
- productdb の `public.flyway_schema_history`(V5)は古い記録です — Flyway は `product` のものだけを見ます。

## 3. デプロイ前の準備

### 3-1. バックアップ — 必須

user V18 が重複したレビュー · お気に入りの行を消します。毎日のバックアップ(03:00 UTC)とは別に、デプロイ直前にもう一つ取ります。コマンドは [docs/72 §3](72-model-refactor-deploy-runbook.md) のとおりです(物理 · 論理 · Mongo)。フォルダーは `~/backups/pre-<対象バージョン>-<時刻>` とし、§0 に書きます。

### 3-2. トピック — `ops/02`

```bash
./ops/02-create-dlt-topics.sh
```
- `order-timeline` を **コンパクション** で作ります(docs/76 §3-1)。作らないとプロジェクションが delete ポリシーで自動作成し、保持期間の後にタイムラインが消えます。
- saga-projection の入力トピック(`order` · `payment` · `order-status`)がなければ作ります。
- 秘密を載せる `outgoing-mail` には短い保持を掛けます。

### 3-3. サービスの身元 — `ops/38`

```bash
./ops/38-issue-service-identities.sh --only gateway-server   # 身元ヘッダーはゲートウェイの CN からだけ(docs/57 §16)
./ops/38-issue-service-identities.sh --only config-server    # config-server の身元が新しくできた — なければ ops/28 が止まる
```
(cert-manager を使うなら ops/39。)

### 3-4. 守ること

- **対象バージョンのイメージが GHCR にあること。** CI がそのコミットのパイプラインを緑で終えたかを見ます — `ops/28` も最初の段でイメージの有無を確かめ、なければクラスタに触れる前に止まります。
- デプロイ中は管理者の作業(商品 · クーポン · お知らせの編集)を止めます。旧 Pod と新 Pod が混ざっています。
- ディスクの空きを確かめます。全サービスの新しいイメージを取得します: `df -h /`

## 4. デプロイ

インスタンスで手で行います(本番デプロイのランナー `oci-ampere` はまだありません — docs/72 §4-2)。

```bash
cd ~/ecommerce-msa
git fetch origin main
git switch --detach <対象バージョン>          # マニフェストもそのコミットのものに
./ops/28-deploy.sh --report                  # 前回のデプロイの環境名(local)と直前のバージョン(b7c5053a45e8)を確認
./ops/28-deploy.sh local <対象バージョン> --dry-run
./ops/28-deploy.sh local <対象バージョン>
```

- 1 段目でゲートウェイを除くサービスがすべて起動する間に、各サービスが自分のマイグレーションを適用します。catalog のインデックス作成は本番の商品数で数秒です。
- 2 段目でゲートウェイを上げ、スモーク(`ops/06`)を回します。スモークが失敗すれば `b7c5053a45e8` へ **自動復帰** します(§7)。
- ゲートウェイの Service が LoadBalancer → ClusterIP に変わります(B-85)。外からはトンネルだけで入ります。

## 5. デプロイ直後 — 10 分以内に

### 5-1. アウトボックス — 旧リレーが FAILED を付けた新しい種類の行

(`q` は §2 の関数です。)

```bash
q orderdb "SELECT event_type, count(*) FROM orders.payment_outbox_event WHERE outbox_status = 'FAILED' GROUP BY 1"
q orderdb "UPDATE orders.payment_outbox_event SET outbox_status = 'STARTED'
            WHERE event_type IN ('product-update', 'cart-empty', 'order-status') AND outbox_status = 'FAILED'"
```
送り直しても安全です。受け取る側(catalog · cart · saga-projection)が eventId で重複を弾きます。上の三種類以外の FAILED は今回のデプロイと無関係です — その行は `OrderOutboxRowFailed` アラートで別に見ます。

### 5-2. DLT — ローリングの窓で旧 Pod が読めなかったメッセージ

```bash
for t in coupon-rollback.DLT cart-empty.DLT payment.DLT order.DLT; do ./ops/56-dlt-replay.sh list "$t"; done
# パーティションごとの最近の件 — 位置 · キー · 元の位置 · 失敗したグループ · 例外。今回のデプロイ時刻のものだけを選ぶ。
./ops/56-dlt-replay.sh replay coupon-rollback.DLT <パーティション> <オフセット|開始-終了>           # 何を送るかを見せるだけ
APPLY=1 ./ops/56-dlt-replay.sh replay coupon-rollback.DLT <パーティション> <オフセット|開始-終了>   # 送る — バイトそのまま · 元のパーティション
```
コンソールツールで送り直しません(Avro が壊れる)。元のトピックの **すべてのコンシューマーグループ** がもう一度受け取ります。そのため送り直すのは、受け取る側が冪等な `coupon-rollback` · `cart-empty`(処理台帳 · eventId)だけです。`payment.DLT` · `order.DLT` は送り直さず、下のように原因ごとに処理します(決済応答を送り直すと別のグループがもう一度処理し得る — ops/56 のコメント)。
- `payment.DLT` に delivery グループのレコードがあれば、その注文は配送が作られていません。管理者の配送復旧で埋めます(PAID · PROCESSING の注文だけ)。
- `order.DLT`(決済要求)にレコードがあれば、その注文は決済が始まっていないか、決済されたのに注文が決済前です。アラート `OrderPaymentUnconfirmed` · order のログ「결제 대사」で注文を探し、決済事業者のコンソールと合わせます。

### 5-3. Redis — 古い永続の商品キャッシュ

products キャッシュは今は 30 分の期限 · キー接頭辞です。古い永続キーを一度消します(次の照会がまた埋める)。
```bash
kubectl -n redis exec deploy/redis -- sh -c 'export REDISCLI_AUTH="$REDIS_PASSWORD";
  redis-cli --scan --pattern "products::*" | xargs -r -n 200 redis-cli del >/dev/null; echo done'
```

## 6. デプロイ後の確認

### 6-1. スキーマ
```bash
for db in cartdb coupondb orderdb userdb; do echo "$db: $(q "$db" 'SELECT max(version::int) FROM flyway_schema_history WHERE success')"; done
echo "productdb: $(q productdb 'SELECT max(version::int) FROM product.flyway_schema_history WHERE success')"
# 期待: cart 7 · coupon 9 · order 28 · user 18 · catalog 22(chat · pay · notification · delivery はそのまま)
```

### 6-2. 画面 — 一周

| 確認 | 期待 |
|---|---|
| カートのないアカウントでカートを開く → 商品を入れる → 最後の行を消す | エラー画面なしで「空」 |
| `https://www.ysyproject.com/user-service/registrationConfirm?token=x` | 失敗画面へリダイレクト(404 ではない) |
| ソーシャルログインの開始 | 事業者の画面へ行く |
| 注文 → カード(テスト)· PayPal(サンドボックス)· 銀行振込 | 決済完了、注文詳細に「進行記録」(docs/76) |
| 上の決済の間のブラウザ開発者ツールのコンソール | CSP 違反の報告がない(あればその出所をポリシーに足す — §8) |
| トップ · 検索 · 企画展のカード | 写真 · 価格 · 割引 · 在庫バッジ · 色見本が以前どおり |

### 6-3. コマンドで

```bash
kubectl -n ecommerce get pods                                         # 再起動 · OOMKilled なし
kubectl -n ecommerce top pods                                         # ヒープ 50% — 上限に届くサービスは JVM_MAX_RAM_PERCENTAGE(例: 45)
kubectl -n ecommerce get svc gateway-server                           # ClusterIP · ポート 8000 だけ
curl -sI https://www.ysyproject.com/ | grep -iE 'x-frame-options|content-security-policy-report-only|strict-transport'
curl -s https://www.ysyproject.com/ | grep -c font-awesome            # 0
curl -s 'https://www.ysyproject.com/catalog-service/api/search?page=1&pageSize=1' | grep -c '"description"'   # 0 — 一覧はカードのフィールドだけ
for d in order-service cart-service; do kubectl -n ecommerce logs deploy/$d --since=30m | grep -c LazyInitializationException; done   # 0
kubectl -n ecommerce logs deploy/catalog-service --since=30m --tail=-1 | grep -cE '검색 질의 시간 초과|검색 연결 소켓 읽기 시간 초과'    # 0(ログの文言は韓国語)
./ops/05-check-observability.sh                                       # サービス指標 11 個 up · node-textfile up
```

## 7. 失敗したら

- **スモーク失敗**: `ops/28` が `b7c5053a45e8` へ自動復帰します。マイグレーションはすべて追加だけなので旧イメージがそのまま起動します(リハーサルで確認)。復帰の後は新しい種類のアウトボックス行(§5-1)が旧リレーによって FAILED になります — もう一度上げるときに同じ UPDATE をします。
- **一つのサービスだけ起動しない**(ローリングが止まる): 旧 Pod が動き続けます。`ops/28` が出した起動エラーを見て、直したイメージでもう一度デプロイします。
- **user V18 で消した行が必要**: §3-1 の論理ダンプからその表だけを戻します。

### 7-1. 1 回目(2026-10-24)— スモーク 3 番「429 が出ない」で自動復帰

- **何が**: イメージ · マイグレーション · 2 段目のゲートウェイまで上がった後、スモーク 3 番(同じ客の同時 70 発 → 429 が一つでも)が 429 を受けなかった。残り(ゲートウェイ health · フォールバック 503 · カタログ 200)は通過。
- **なぜ**: このリリースでゲートウェイの Redis コマンドタイムアウトが 60 秒(Lettuce 既定)→ 500ms になった(B-84)。超えた流入制限のコマンドは**通過**(fail-open)。起動直後のゲートウェイの最初のコマンドは接続 · AUTH · スクリプト読み込み(EVALSHA → NOSCRIPT → EVAL)を一緒に払い、全サービスが一度に上がった単一ノードでそれが上限を超えた。以前は 60 秒待って 429 が出た。再現(実際のゲートウェイ + 実際の Redis、開発機): 温めなしでは起動直後の最初の 70 発のうち **57 発が通過**(バースト 40)。
- **直したもの**: ゲートウェイが readiness の前に流入制限のスクリプトを一度実行する(`RateLimiterWarmup` — 客と重ならないキー、Redis に届かなければ警告だけで起動)。同じ再現で最初の 70 発のうちちょうど 40 発通過 · 30 発 429。スモーク 3 番は 429 がなければ 3 秒後に一度だけ撃ち直し、毎回コード別の数を残す(制限が抜けたゲートウェイは撃ち直しても 429 がない)。テスト `RateLimiterLiveTest`(コンテナ — 温めを外すと失敗)。
- **同じことは客にも**: デプロイ · 再起動直後の数秒は流入制限がなかった。本番 b7c5053 は逆に Redis が一瞬切れると全リクエストが最大 60 秒止まる。
- **再デプロイ**: 対象バージョンはこの修正が入ったコミット(CI 緑)。§2 · §3 はやり直さなくてよい(マイグレーションのファイルはそのまま · バックアップは今日)— §4 から。復帰が残したアウトボックスの FAILED 行は §5-1 の UPDATE が一緒に処理する。

### 7-2. デプロイ後の発見(2026-10-24)— 注文 · 決済の案内通知が作られていなかった

- **何が**: `order.DLT` · `payment.DLT` に notification グループ(`notification-order` · `notification-payment`)が対で溜まった — 10-16 〜 10-22 に 6 組(b7c5053)、デプロイ後の Stripe 注文 2 件で 8 組。`ops/56 list` の原因の連鎖: `BatchListenerFailedException → NullPointerException`。
- **なぜ**: 通知メールの件名を地域の言語に変える行が `Locale.forLanguageTag(locale)` だった。地域(`notification_preference.region`)は**電話番号を認証して**初めて入るので、未認証の利用者は `locale` が null → NPE。その行がメールの失敗を防ぐ try の外だったため、**通知の作成ごと**失敗した(メール受信をオンにした利用者の注文 · 決済 · 配送 · クーポン · 再入荷の案内すべて)。
- **直したもの**: 地域が分からなければ件名はそのまま · メールの言語は既定、メールの準備すべてを try の中へ(通知はメールと関係なく作る)、警告ログは例外メッセージの代わりにクラス名(`NotificationMailLocaleTest` — 戻すと失敗)。次のデプロイに入る。
- **溜まった DLT**: 再送しない(元のトピックのすべてのグループが受け直す)。逃した案内は注文 8 件 — 必要なら管理者のお知らせで。

## 8. 数日後 — 観察を経た次の段階

| いつ | すること | 根拠 |
|---|---|---|
| デプロイの翌日 | `SINCE=24h ./ops/37-check-mtls.sh` の ①-3 が 0 なら `INTERNAL_AUTH_IDENTITY_MODE=enforce` → `ops/28 --config-only` + バックエンドの再起動 → ②-2 が 403 — ✅ 2026-10-26 ①-3 0(SINCE=24h)→ ConfigMap を enforce — 適用済み(`d68f0307e979`、②-2 403) | docs/57 §16「身元ヘッダー」 |
| デプロイの翌日 | `kubectl -n ecommerce logs deploy/eureka-server --since=24h \| grep -c '\[eureka write\]'` が 0 なら `EUREKA_WRITE_AUTH_MODE=enforce` → `--config-only --restart eureka-server` — 2026-10-26: 1 件(サーバーの自己複製 `peerreplication/batch` — `my-url` で修正、docs/57 §16)、そのイメージ(`d68f0307e979`、2026-10-26)のデプロイ後にもう一日 — ✅ 2026-10-30 0 件 → ConfigMap を enforce — 適用済み(16:51 UTC `907b06ab` の `--config-only --restart eureka-server`: ログ `mode=ENFORCE (CA 읽음)` · 10 分の `[eureka write]` 0 · サービス Pod の再起動 0) | docs/57 §16「Eureka 書き込み」 |
| 決済を一周した後 | CSP 違反がなければ frontend nginx の `Content-Security-Policy-Report-Only` → `Content-Security-Policy`(次の frontend イメージ) — ✅ 2026-10-25 コンソールの違反 0(カード・PayPal・銀行振込)→ `61f546a8b125` で強制を適用済み(応答ヘッダーを確認) | docs/57 §19 |
| デプロイが安定した後 | `LOCAL_KEY_STORE_PASSWORD` の交換 — `./ops/59-rotate-store-password.sh` check → apply(ストアのパスワードだけ · 鍵はそのまま · 一つずつ再起動)→ 一日後に cleanup。古いパスワードが過去のログに残っている — ✅ 2026-10-25 10:21 UTC apply(ストア 27 · 写し 14 · 13 個の再起動 0 · ops/37 403/200)、cleanup ✅ 2026-10-26(写し 21 個を削除 — Secret 14 · certs/ 6 · 20-secret.yaml)。⚠️ 全 Pod が起動し直したので identity · Eureka enforce の 24 時間の窓は 10-26 11 時 UTC から | docs/57 §16「ストアのパスワード」 |
| 1 週間ほど後 | contract リリース: order の `order_address.order_id` · `orders.tax_price`、user の `lock_expires_at` · `failed_login_attempts` 列の削除(ROLLBACK-UNSAFE の宣言)— ロック待ち行列の注意は §8-1 | docs/72 §7 |
| デプロイ後に一度 | 本番 b7c5053 の期間にアラートなしで過ぎた可能性のある「Pod ごとの最初の一件」— ログの「이중 결제」· DLT トピックを見渡す | B-101 |
| 判断の後(作業するか決めてから) | まれな価格フィルター検索の 503 をなくす — 商品ごとに画面価格(常時割引 · 企画展を適用)の最低 · 最高を列に持ち(サイズの価格 · 割引 · 企画展が変わるときに更新)、`(category_id, 最低価格)` のインデックスで候補を先に絞る。コスト: マイグレーション(列 · インデックスの追加、本番 200 万件の埋め込み) · 更新経路 · テスト。それまでは合う商品がごく少ない価格帯で 5 秒上限の 503 — 画面は「サーバーが一時的に応答していません」の案内(2026-10-26 本番の 10 分で「검색 질의 시간 초과」2 件)。下限 > 上限 · 件数は `d837605d7902` で解決済み。移行計画は §8-1 | docs/83 §2.8 |
| 判断の後 | まれ · 0 件の組み合わせへの一般的な対策(2026-10-26 整理) — `LIMIT` で数行だけ取る計画は、合う行がまれだとカテゴリを最後まで走査する(5 秒上限 → 503)。知られた手法: ① あり得ない組み合わせは DB に聞かない(下限 > 上限 · 別の親のサブカテゴリ — `d837605d7902` · `d68f0307e979` で適用済み) ② ファセット件数 — フィルターメニューの値ごとに今の条件での件数を前もって計算し、0 件の値は薄く · 非表示(EC サイトの標準、今の `SearchFilterFacets` は値だけ) ③ 非正規化列 + 複合インデックス(上の行) ④ 転置インデックスの検索エンジン(ES — フラグ `catalog.search-engine`、ファセット件数も集計で一緒に) ⑤ 時間上限に達したら 503 ではなく見つかった分 + 「これ以上なし」(小さい変更 — ③ より先にできる)。PostgreSQL の補助手段(まれな側 — 連結表 `(subcategory_id, product_id)` — から始める、`CREATE STATISTICS`、部分インデックス)は ③ の代わりにならない。勧める順序 ⑤ → ③(または ④)→ ② | docs/83 §2.8 |
| 判断の後 | カテゴリツリーの拡張(2026-10-26 整理) — 今は 2 段 · サブカテゴリは一つだけ選ぶ(商品に最上位の `category_id`、サブは `product_subcategory` のインデックス) — 子の数(10 個超)は性能と無関係。複数選択を入れるとき: グループ内は OR · グループ間は AND、`subcategory_id = ANY(:ids)`(今のインデックスのまま)、サーバーは別の親の子を捨て、子を全部選んだら親一つに置き換える、画面は子が 10 個を超えたら先頭の数個 + 「もっと見る」(多ければグループ内の検索欄 · ファセット件数と一緒に)。3 段以上: 毎回の再帰 CTE + 長い `IN (…)` ではなく、商品に祖先 id の配列(`int[]` + GIN、`@> ARRAY[…]`)または経路(`ltree` + GiST)、ツリー自体は materialized path · closure table(nested set は更新が高い)。親子の判定(`subcategoryOutsideCategory`)は「親が同じか」→「祖先か」に、ツリー(数百行)はメモリにキャッシュ · 管理者の変更時に消す | — |

### 8-1. 移行計画 — 無停止スキーマ変更(上の「判断の後」·「1 週間ほど後」の行)

根拠: db_modeling リポジトリ `sql-tuning-lab/docs/2003-오라일리-튜닝북-실습-매핑.md` の X-3(PostgreSQL の無停止スキーマ変更 — 1,000 万行の実測)。その結論をこのリポジトリの二つの作業に当てはめたもの。

**価格範囲の列(まれな価格帯の 503)** — expand のみで contract はない。

| 段階 | 何を | X-3 の根拠 · 注意 |
|---|---|---|
| ① 列の追加 | catalog の新しいマイグレーション: `product.product` に `min_price` · `max_price`(NULL 許容、既定値なし) | `ADD COLUMN` はカタログだけを変える(1,000 万行で 35ms)— Flyway でそのまま |
| ② 新しい書き込み | 商品 · サイズ価格 · 常時割引 · 企画展が変わる箇所でアプリが二つの列を一緒に更新 | X-3 のトリガー二重書き込みの代わりにアプリのコード — 企画展の割引はメモリ上の規則(`EventPricing`)でトリガーでは計算できない |
| ③ 埋め込み | 200 万件を 1 万行のバッチ UPDATE で(ops スクリプト、冪等 — NULL の行だけ) | バッチ 1 万で約 10 万行/秒(X-3 ③)— 本番機はもっと遅いことがある。Flyway の一つのトランザクションでは行わない(起動プローブ 5 分 · 巨大な dead tuple) |
| ④ 後片付け | 埋めた直後に `VACUUM (PARALLEL 0, ANALYZE) product.product` | 大量更新直後の DDL は autovacuum の後ろで `deadlock_timeout`(1 秒)だけ塞がれる(X-3 ③ — 1,034ms → 44ms)。`PARALLEL 0` は postgres Pod の `/dev/shm` 64MB のため(§0 続くデプロイ 4) |
| ⑤ インデックス | `(category_id, min_price)` を `CREATE INDEX CONCURRENTLY` | その文一つだけのマイグレーション + `V…sql.conf` の `executeInTransaction=false`(Flyway 11)— 通常の `CREATE INDEX` は作成中に商品の書き込みを塞ぐ |
| ⑥ 検索の切り替え | `SearchService` が価格条件を二つの列で先に絞り、既存の EXISTS はその中だけで | 列が空の間(③ の前)は従来の経路 — 機能フラグまたは「NULL なら従来の条件」 |

**contract リリース(order · user の列削除)**

- `DROP COLUMN` はカタログだけを変えるが、一瞬 `ACCESS EXCLUSIVE` を取る。PostgreSQL のロック待ち行列は FIFO — その表に長い照会があると、DDL が待つ間に後ろの読み書きがすべて DDL の後ろに並ぶ。
- 短い `lock_timeout` + 再試行(gh-ost 方式)をそのまま使わない — X-3 では 29 倍悪化した(失敗した試行ごとに新しい要求が後ろに積もる)。長い照会のないデプロイの窓に回し、直前に `pg_stat_activity` でその表の長時間の照会がないか見る。
- `ALTER COLUMN … TYPE` のような再書き込みは読み取りまで塞ぐ(1,000 万行で 7.8 秒のうち 98%)— この二つのリリースにはない。出てきたらシャドー列 + バッチ埋め込み + RENAME に分け、RENAME は既に開いた接続で(新しい接続のコストが cutover 時間のほぼ全部 — X-3 の最後の節)。
- 該当なし: replica lag のスロットル(本番は replica のない単一 PostgreSQL)。

デプロイが終わったら残作業の一覧の「次のデプロイと一緒に」の項目を「適用済み」に移し、この文書の一番上の状態に結果を書きます。
