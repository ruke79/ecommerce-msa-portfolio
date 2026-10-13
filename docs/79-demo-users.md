# 79. 運用デモ用ユーザーの大量生成 — 実際にログインできるアカウント

**日本語** | [한국어](79-demo-users.ko.md)

> **状態(2026-10-08)** — `ops/48-bulk-users.sh` + `ops/tools/generate-demo-users.py`。商品の大量登録(`ops/44`、docs/19 §5.1)と対になる。
> 目的は「商品もユーザーも多い」状態の運用デモと負荷テスト。作ったアカウントは**アプリのログイン判定をそのまま通る**(統合テストで確認、§8)。

---

## 0. 概要

```bash
./ops/48-bulk-users.sh estimate            # 行数・容量・イベントが出し切るまでの時間(既定 10 万人)
./ops/48-bulk-users.sh load                # 確認 → パスワード準備 → チャンクごとに投入(再実行で続きから)
./ops/48-bulk-users.sh status              # デモユーザー・配送先・user-created の状態・クーポン/チャットの複製
GATEWAY=https://<ゲートウェイ> ./ops/48-bulk-users.sh login-check   # 標本が実際にログインできるか
./ops/48-bulk-users.sh purge               # デモユーザーとその痕跡だけを消す
USERS=500000 ./ops/48-bulk-users.sh load   # 人数を変える(status・purge も同じ値で)
```

一人あたり作るもの:

| 表 | 内容 |
|---|---|
| `users.users` | ログインできる行 — `enabled`、BCrypt(`$2a$10$`)ハッシュ、`ROLE_USER`、2FA オフ |
| `users.shipping_address` | 既定の配送先を一つ(KR 7 : JP 3、国は ISO コード)— すぐ注文を試せる(`NO_ADDRESSES=1` で省略) |
| `users.outbox_event` | `user-created` を**同じトランザクション**で — ウェルカムクーポン(coupon)・チャット会員(chat)の複製が通常の加入と同じ道で作られる(`NO_EVENTS=1` で省略) |

---

## 1. なぜ API ではなく SQL 直接投入なのか

加入 API(`/api/auth/public/signup`)で 10 万人を作ると、次の三つにぶつかる:

- **ゲートウェイの認証前ルートの制限**: IP あたり毎秒 1 回(バースト 10)。1 IP から 10 万人なら約 28 時間かかる。
- **漏えいパスワード検査**: 加入ごとに外部 API(HaveIBeenPwned)を呼ぶ。
- **BCrypt の計算**: 加入ごとに一回走る。

直接投入なら、ローカルの PG16 で 10 万人が**約 17 秒**だった(§3)。代わりに、アプリが加入時にやることを生成器が同じ形でやる(§2)。統合テストは、その結果を本番の判定コードで確かめる。

生成器は外部ライブラリなしで、psql スクリプトを stdout に出す。`ops/48` はそれを PostgreSQL Pod の psql(`kubectl exec`)に流し込む。だから DB パスワードも port-forward も psycopg2 も要らない。

---

## 2. アプリと合わせた規則

| 項目 | 値 | 理由 |
|---|---|---|
| id | 予約範囲 `[ID_OFFSET, ID_OFFSET + USERS)`、既定 70 億から | アプリの TSID(約 10^18)とぶつからない。DB のシーケンスはない(TSID はアプリが作る) |
| メール | `demo<番号>@demo.invalid` · 小文字 | ログインの検索が完全一致。`.invalid`(RFC 2606)なのでメールはどこにも届かない |
| パスワード | 全員同じ一つの BCrypt ハッシュ(強度 10、接頭辞なし) | アプリは `new BCryptPasswordEncoder()`(`{bcrypt}` 接頭辞なし)。ハッシュは一回だけ作る — 一人ずつ作ると 10 万人で 1〜2 時間 |
| `enabled` · `email_verified` | true | ログインはメール認証の門として `enabled` を見る |
| 役割 | `ROLE_USER` を**名前で**引く | 種データの id は DB ごとに違いうる(V3 は「なければ入れる」) |
| 加入日 | 8 日〜2 年前に散らす | `UserReplicationReconciler` は毎日「直近 7 日の加入者」のイベントを書き直す。今の時刻で入れると、翌日に数十万件がまとめて再送される |
| user-created のペイロード | `{"userId","image","nickname"}` | `UserOutboxHelper.recordUserCreated` と同じ。メールは載せない(docs/48 §6-⑥) |
| ニックネーム | 姓名 + 番号 | 画面(チャット・レビュー)で見分けられるように |
| 再実行 | ステージング表に COPY → `ON CONFLICT DO NOTHING` | 途中で切れても、同じコマンドで続きから入れられる。イベントは「新しく入った人」の分だけ作り、二重の user-created は作らない |

---

## 3. 容量・速度(実測、ローカル PG16)

| 項目 | 値 |
|---|---|
| 一人あたり(表 + 索引 + 配送先 + アウトボックス) | **約 1,012 バイト** — 10 万人で約 0.1 GB、100 万人で約 1 GB |
| 投入速度 | 10 万人 **約 17 秒**(5,000 人/トランザクション、`synchronous_commit=off`) |
| WAL | 投入中は一時的に同じくらい増える |

商品 200 万件(`ops/44`、約 4 GB)と合わせても、ラボ(データノード 60 GB)には十分に入る。

---

## 4. イベントを流す — いちばん遅いのはここ

- `user-created` は user-service のポーラーが送る。**レプリカあたり 10 秒に 100 件**(`app.outbox.batch-size=100`、`fixed-delay-ms=10000`)。
  - レプリカ 1 つなら 10 万人で約 2.8 時間、2 つ(`FOR UPDATE SKIP LOCKED` で分け合う)なら約 1.4 時間。
- **ラボで早く流すなら** ConfigMap に `APP_OUTBOX_BATCH_SIZE: "1000"` を入れ、`./ops/28-deploy.sh <環境> --config-only --restart user-service` を実行する。10 倍になる。
  - 終わったら元に戻す。運用ではバッチを大きくすると、一つのトランザクションがロックを長く持つ。
- 送信に失敗した行は `FAILED` で残り、**自動では再試行しない**(UserOutboxScheduler)。`./ops/48-bulk-users.sh retry-failed` が、デモ範囲の行だけを `STARTED` に戻す。
- `status` で「クーポン数・チャット会員数 = ユーザー数」になれば、複製まで終わったということだ。
- `NO_EVENTS=1` で入れると複製はできない。クーポンなし、チャットは初回使用時に仮のニックネームで作られる(docs/47 と同じ状態)。デモには勧めない。

---

## 5. ログイン負荷の注意 — 「同時接続を最大に」のとき

| 何が | どうなる | 対処 |
|---|---|---|
| ゲートウェイの認証前ルートの制限(IP あたり毎秒 1・バースト 10) | 1 IP から毎分約 60 ログインで、その先は 429。リフレッシュ(`/api/token/refresh`)も同じルート | ログインは時間をかけて増やし(ランプ)、トークンは使い回す(アクセス 15 分、リフレッシュ 14 日)。複数のマシン(ラボのホスト・VM)から分けて送る。制限値は変えない |
| 多重ログインの検知 | 同じユーザーで二つ目のセッションを開くと、一つ目が SSE で追い出される | 仮想ユーザー一人にデモユーザー一人 |
| `LoginRiskGuard` | **失敗した**ログインを数える。一つの IP から 10 アカウントの失敗が続くと、未知の機器はメールのコードを求められる | 正しいパスワードだけを使う(`login-check` は 401 なら止まる) |
| BCrypt の照合 | ログインごとに強度 10 の照合が一回(数十 ms の CPU) | ログイン数は CPU 予算として見る。同時接続数そのものとは別の指標 |
| 新しい機器の通知 | 最初の機器では通知しない。二つ目の機器からは SECURITY_ALERT メール | 宛先は `.invalid` で、ラボは `MAIL_PROVIDER=logging`(docs/71 §7) |

> ラボ(Cloudflare なし)ではゲートウェイが `CF-Connecting-IP` ヘッダーをそのまま信じるので、偽装して制限を避けられてしまう。負荷のためにそれを使わない。運用はトンネルの外から届かないことが前提だ(`CustomUserKeyResolver`)。

---

## 6. セキュリティ

- **デモアカウントはみな同じパスワードだ。** 公開サイト(運用)に作ると、そのパスワード一つで数万のアカウントに入れる。運用クラスターでは、デモが終わり次第 `purge` する。
- パスワードの扱い:
  - `PASSWORD_FILE`(既定 `~/.ecommerce-msa/demo-users.password`)は 0600 で、リポジトリの外にしか作らない。中にあれば拒否し、権限が 600 でなければ止まる。
  - 画面・コマンドライン・シェル履歴には残さない。平文は stdin でだけハッシュ器と `login-check` の本文に渡り、ハッシュは環境変数で生成器に渡る。
- `login-check` は応答の本文・ヘッダー(ACCESS)を捨てる。トークンは出力しない。
- メールは `.invalid` なので、クーポン・セキュリティ通知などのメールが実在の誰かに届くことはない。

---

## 7. 削除(`purge`)

1. users DB: 消す人を先に固定する(範囲**かつ** `@demo.invalid`)。範囲を間違えても実在のユーザーは消さない。
2. まだ送られていない user-created を消す。ユーザーがいないのにイベントだけ出ると、クーポン・チャットに幽霊ができるからだ。
3. `users.users` を指す外部キーをカタログから辿って、下から消す。デモ中にできた機器・通知・レビュー・ウィッシュリストまで消える。外部キーのない `user_id` 列の表(`known_device` など)も、カタログで見つけて消す。一つのトランザクションで行う。
4. coupondb の `coupon.coupon`、chatdb の `chat.member` を id の範囲で消す。
5. **注文(orderdb)は残る。** 注文履歴は消さない。

---

## 8. 検証

| テスト | 何を守るか |
|---|---|
| `DemoUserGeneratorIntegrationTest`(user-service、`@Tag("containers")`、5 件) | Flyway で上げた本物の users スキーマに生成器の SQL を psql で流し込む。**本番の `CustomAuthenticationProvider` · `UserDetailsServiceImpl` · Hibernate(validate)** で読む。ログインが通って `ROLE_USER` になる・誤ったパスワードは拒否・2FA オフ・加入日は 7 日より前・一人一つの user-created(STARTED、消費者 DTO で読める、メールなし)と配送先・再実行しても同じ・purge は範囲内の `@demo.invalid` だけ(範囲内の実ユーザー・範囲外は残す、外部キーのない機器行まで)・形式の違うハッシュ(`{bcrypt}…`)なら何も出さない |

**変異確認**— 次のように戻すと、それぞれテストが失敗した:

- `enabled=false` にする
- purge のドメイン条件を外す
- `ON CONFLICT` を外す
- 外部キーのない表の掃除を外す
- 加入日を今にする
- 重複イベントの防止を外す

**`ops/48` のリハーサル** — userdb・coupondb・chatdb をマイグレーションで上げた PG16 に、kubectl の代役を挟んで流した。確認したこと:

- `estimate → load → 再 load(続きから、件数そのまま) → retry-failed → purge` が通る
- パスワードファイルは 0600 で作られる
- purge は範囲外(実ユーザー)のクーポン・チャット行を残す
- `login-check` は偽のゲートウェイで、本文が JSON の `{email, password}` で届く。出力にはパスワードもトークンも出ない

> Gradle メモ: 統合テストはモジュール外の生成器を実行するので、`build.gradle` で user-service の test タスクの入力として宣言した。宣言しないと、生成器を直してもテストが UP-TO-DATE で飛ばされる — 最初の変異確認がそれで全部「通過」していた。
