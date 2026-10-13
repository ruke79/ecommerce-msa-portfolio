# 72. モデルリファクタリング 2 次のデプロイ手順書 — 元に戻せないデプロイを安全に

**日本語** | [한국어](72-model-refactor-deploy-runbook.ko.md)

> **状態(2026-10-06)** — 対象バージョンは **`b7c5053a45e8`** です(パイプライン緑)。`d7f87c9` 以降に加わったのは user の猶予トークン長チェック(a906312)、文書、デプロイの手動実行化(B-75)だけです。機能フラグ(docs/74、`c291eba` 以降)は**このデプロイが安定した後に別途**出します — そのため Run workflow のバージョンを空にせずこの SHA を入れます(空にするとデフォルトブランチの最新 = フラグまで一緒に出る)。何をなぜ直したかは [docs/62 §18](62-engineering-casebook.md)、デプロイスクリプト自体は [docs/36](36-deployment-pipeline.md)・[docs/33](33-deploy-runbook.md) です。
>
> **デプロイ完了(2026-10-10)** — インスタンス上で `ops/28-deploy.sh local b7c5053a45e8` により手作業でデプロイ、328 秒、スモーク通過(4 通過・0 失敗・5 省略)。リポジトリに本番デプロイ用ランナー(`oci-ampere`)がないため Actions ではなく手作業で行い、作業コピーは先に対象コミットへ `git switch --detach` しました(Actions も対象コミットをチェックアウトして同じスクリプトを動かす — 別のコミットのマニフェストが混ざらないように)。サガ E2E(決済)は手動確認の項目です。
> デプロイ後の確認(§5): Flyway は cart 5・chat 9・catalog 18・order 25・pay 7・notification 11・user 16、新しいインデックス・制約(`uq_cart_user`・`uq_participant_room_member`・`uq_orders_order_address`・`idx_payment_tracking`・`idx_review_product`・`LOGIN_CHALLENGE` の check)がすべてあり、旧 `idx_payment`・`idx_review` はなし、パスキーの表あり、番号のまま残ったお知らせ・ラージオブジェクト 0、全サービス `b7c5053a45e8` で READY。chat の時刻補正は 1 回目で 7 件(Mongo `chat` の全件 — 旧コードが書いたメッセージだけだった)。`productdb` の `public` スキーマに残る古い Flyway 履歴(V5)はスキーマを `product` へ移す前のもので、今回のデプロイとは無関係です。
>
> **事前点検(2026-10-09)** — 本番は **`a813ae314f8c`**(2026-10-01 のコミット、kubectl で全サービス同じタグ)です。§2 の点検はすべて想定どおりでした — Flyway は cart 4・chat 8・catalog 17・order 24・pay 6・notification 10・user 14、重複・OID・ラージオブジェクト・許可外のメール種別はすべて 0。したがって V5・V9 が削除する行はなく、V11 の check も通ります。

今回のデプロイは **自動ロールバックが無効になるデプロイ** です。catalog V18 が元に戻せないマイグレーション(ROLLBACK-UNSAFE)だからです。そこで通常のデプロイに加えて、**事前点検・バックアップ・デプロイ中の禁止事項・デプロイ後の確認** をこの文書の順に行います。

1 次のモデル修正(user V16 など)はまだ本番に出ていません(本番の user は V14) — 同じデプロイで一緒に出ます。デプロイは常に一つのバージョン(コミット SHA)で全サービスです。

## 1. 何が変わるか

| サービス | 変更 | 内容 | 元に戻す |
|---|---|---|---|
| cart | V5 | ユーザーごとの重複カートを最新の一つにまとめ(**行を削除**)、`user_id` をユニークに | イメージを戻すだけでよい |
| chat | V9 | 同じ(ルーム, 会員)の参加の重複を削除 + ユニーク | イメージを戻すだけでよい |
| chat | 起動時にバックグラウンド | Mongo の会話インデックス作成、旧メッセージ時刻 +9 時間(15 分後に 2 回目) | 無効化: `chat.message-maintenance.enabled=false` |
| catalog | V18 | お知らせ本文をラージオブジェクト(OID)から本文へ移し、ラージオブジェクトを解放 | **不可 — ROLLBACK-UNSAFE** |
| order | V25 | `order_address.order_id` の NOT NULL を解除、`tax_price` の既定値 0、注文ごとの住所ユニーク | イメージを戻すだけでよい |
| pay | V7 | `tracking_id` インデックス、ユニークと重複していた `idx_payment` を削除 | イメージを戻すだけでよい |
| notification | V11 | メール種別の check に `LOGIN_CHALLENGE` を追加(check を消して作り直す — 許可外の行があると失敗、§2) | イメージを戻すだけでよい(許可値を増やすだけ) |
| user | V15 | パスキー資格情報の表 `users.passkey_credential` を新規作成 | イメージを戻すだけでよい(旧イメージは使わない) |
| user | V16 | レビュー本文を含む `idx_review` を削除(長い韓国語レビューが B-tree の上限で拒否されていた)、`product_id` インデックス | イメージを戻すだけでよい |
| frontend | — | 注文詳細の税金行を「込み」に | — |

サービス同士が絡む契約の変更はありません — `OrderDto.taxPrice` の削除は JSON のフィールドが消えるだけで読む箇所はなく、`CouponStatus.EXPIRED` はどの行も持っていませんでした。そのため **サービス間のデプロイ順は関係ありません**(ゲートウェイを最後にするのは `ops/28` が常に守ります)。

## 2. 事前点検 — 本番 DB、読み取りのみ

マイグレーションが何を変えるかを先に見ておきます。数字はデプロイ後の確認(§5)と比べます。件数だけを見ます — ユーザー id・メールアドレスは出力しません。

```sql
-- 各サービスの DB: 今適用されているバージョン — 本番バージョン(a813ae314f8c)なら cart 4・chat 8・catalog 17・order 24・pay 6・notification 10・user 14。
-- 違えば止める: この表(§1)が前提とした出発点ではない。
SELECT max(version::int) FROM flyway_schema_history WHERE success;

-- cart DB: まとめられる重複カート
SELECT user_id, count(*) FROM cart.cart GROUP BY 1 HAVING count(*) > 1;

-- chat DB: 削除される重複参加
SELECT room_id, member_id, count(*) FROM participant_chat_room GROUP BY 1, 2 HAVING count(*) > 1;

-- catalog DB: OID で保存されたお知らせの数・ラージオブジェクトの数
SELECT count(*) FROM content.notice WHERE body ~ '^[0-9]+$';
SELECT count(*) FROM pg_largeobject_metadata;

-- order DB: 同じ住所を指す注文 — あれば V25 はユニークを作らず NOTICE だけを残す
SELECT order_address_id, count(*) FROM orders.orders
 WHERE order_address_id IS NOT NULL GROUP BY 1 HAVING count(*) > 1;

-- notification DB: V11 が作り直す check の外にあるメール種別 — 0 であること(あれば V11 が失敗し notification が起動しない)
SELECT count(*) FROM notification.mail_message
 WHERE kind NOT IN ('REGISTRATION_CONFIRM', 'PASSWORD_RESET', 'LOGIN_CHALLENGE', 'ORDER_PLACED',
                    'PAYMENT_COMPLETED', 'PAYMENT_FAILED', 'PAYMENT_REFUNDED', 'SHIPPING_UPDATED',
                    'ORDER_COMPLETED', 'COUPON_ISSUED', 'SECURITY_ALERT', 'RESTOCK_AVAILABLE');
```

本番クラスタ内の PostgreSQL ではこう実行します(catalog は `productdb`、スキーマ `product`):

```bash
q(){ kubectl -n postgres exec -i deploy/postgres -- psql -U postgres -d "$1" -tAX -v ON_ERROR_STOP=1 -c "$2"; }
for db in cartdb chatdb productdb orderdb paydb notificationdb userdb; do
  echo "$db: $(q "$db" 'SELECT max(version::int) FROM flyway_schema_history WHERE success')"
done
```

**2026-10-09 の結果** — バージョンは上の想定値どおり、残りのクエリはすべて 0(重複カート・重複参加・OID のお知らせ・ラージオブジェクト・同じ住所の注文・許可外のメール種別)。

## 3. バックアップ — 必須

V18 は元に戻せず、V5・V9 は行を削除し、時刻の補正は Mongo の文書を変えます。

`ops/08`・`ops/10` は DB に外から接続するスクリプトなので、この本番構成には合いません — PostgreSQL はクラスタ内の Pod(`postgres` 名前空間)で既定の `pg_hba` がリモートの replication を拒否し、MongoDB は **Atlas**(chat の `SPRING_DATA_MONGODB_CLIENT`、クラスタ内の `mongodb` は 0/0)です。そこで Pod 内のツールで取得してサーバーに受け取ります。パスワードは画面・ファイルに出ません(PostgreSQL は Pod 内のローカル接続、Atlas は Secret を一時 Pod の環境変数にだけ渡す)。

```bash
B=~/backups/pre-<version>-$(date -u +%Y%m%dT%H%M%SZ); mkdir -p "$B"; df -h ~ | tail -1
kubectl -n postgres exec deploy/postgres -- pg_basebackup -U postgres -D - -Ft -X fetch --checkpoint=fast > "$B/pg-base.tar"
tar -tf "$B/pg-base.tar" | grep -c backup_label
kubectl -n postgres exec deploy/postgres -- pg_dumpall -U postgres | gzip > "$B/pg-all.sql.gz"
gzip -t "$B/pg-all.sql.gz" && zcat "$B/pg-all.sql.gz" | tail -3

kubectl -n ecommerce run mongo-backup --image=mirror.gcr.io/mongo:7 --restart=Never --overrides='{"spec":{"containers":[{"name":"mongo-backup","image":"mirror.gcr.io/mongo:7","command":["sleep","1800"],"env":[{"name":"MONGO_URI","valueFrom":{"secretKeyRef":{"name":"ecommerce-secrets","key":"SPRING_DATA_MONGODB_CLIENT"}}}]}]}}'
kubectl -n ecommerce wait --for=condition=Ready pod/mongo-backup --timeout=180s
kubectl -n ecommerce exec mongo-backup -- sh -c 'mongodump --uri="$MONGO_URI" --db=chat --archive --gzip --quiet' > "$B/mongo.archive.gz"
gzip -t "$B/mongo.archive.gz" && ls -l "$B/mongo.archive.gz"
kubectl -n ecommerce exec mongo-backup -- sh -c 'mongosh "$MONGO_URI" --quiet --eval "const d=db.getSiblingDB(\"chat\"); d.getCollectionNames().forEach(c => print(c + \": \" + d.getCollection(c).estimatedDocumentCount()))"'
kubectl -n ecommerce delete pod mongo-backup
```

- `kubectl exec` に `-t` を付けません — 端末モードになるとバイナリ出力が壊れます。
- 正常: `backup_label` が 1、ダンプの末尾に `dump complete`、Mongo はコレクションごとの文書数。物理バックアップ(`pg-base.tar`)は全体をその時点へ、論理ダンプ(`pg-all.sql.gz`)は表一つ(例: お知らせ)を戻すときに使います。
- **2026-10-10 の実測**: DB 全体 4,767 MB → `pg-base.tar` 4.7G・`pg-all.sql.gz` 304M、Mongo は `chat` 7 件・`auto_sequence` 1 件(936 バイト — 小さいのが正常)。サーバーのディスク空きは 30G から約 25G。
- バックアップは本番ディスクに同居しています — デプロイが安定したら別のマシンへ移します。Atlas M0 には自動バックアップがありません。

## 4. デプロイ

### 4-1. デプロイ中に守ること

- 🔴 **デプロイが終わるまで管理者のお知らせ編集を止めます。** ローリング中は旧 catalog Pod が残っており、その Pod でお知らせを保存すると本文が再び OID で保存されます。V18 はもう済んでいるので、その行は番号のまま残ります(§5 のクエリで見つけられます)。
- ローリング中に一時的に起きること — すべて想定どおりです。
  - 旧 catalog Pod がお知らせの読み込みに失敗することがあります(新しい Pod に替われば消えます)。
  - 旧 chat Pod が書いたメッセージは 9 時間早いまま保存されますが、15 分後の 2 回目の補正が同じ基準時刻で拾います。
  - 旧 order Pod が決済前の注文を破棄すると、住所の行が一つ残ることがあります(注文は削除されます)。
- インデックスの作成(cart・chat・order・pay)はその表への書き込みを一時的に止めます。今の表の大きさなら数秒です。

### 4-2. 実行

1. CI が対象コミットのイメージを作ったか確認します(タグ = コミット SHA)。`b7c5053a45e8` はパイプライン #497 が緑です。
2. Actions → `deploy (OCI Ampere)` → Run workflow にバージョン(コミット SHA)を入れて実行します — 実行がそのまま承認です(自動デプロイはない、docs/36 §6)。内部では `ops/28-deploy.sh <環境> <バージョン>` で、手作業でも同じコマンドです。
   - リポジトリの Settings → Actions → Runners に **`oci-ampere` ラベルのランナーがあること。** なければデプロイジョブはランナーを待ったまま終わりません。その場合はどちらかです。
     - ランナー登録(docs/36 §6.1 の 4、`ops/40-deploy-runner.sh`) — 最新のランナーを取得するので、Node 24 のアクションに必要な 2.327.1 以上になります。
     - またはインスタンス上で手作業: まず `./ops/28-deploy.sh --report` で前回のデプロイの環境名を見て、同じ環境で `./ops/28-deploy.sh <環境> b7c5053a45e8`(デプロイワークフローの既定値は `local`)。履歴ファイルが同じなので `--report`・`--rollback` がそのまま続きます。
3. 開始時に **「元に戻せないマイグレーション(ROLLBACK-UNSAFE)がこの範囲にあります」** という警告と V18 のパスが出ます — 正常です。このデプロイは失敗しても自動ロールバックせず、`--rollback` も拒否されます。
4. 1 段階目: ゲートウェイを除く全サービス。各サービスは起動時に自分のマイグレーションを適用します。
5. 2 段階目: ゲートウェイ → スモーク(`ops/06`)。

## 5. デプロイ後の確認

```sql
-- 各サービスの DB: 適用されたバージョン(cart 5・chat 9・catalog 18・order 25・pay 7・notification 11・user 16)
SELECT max(version::int) FROM flyway_schema_history WHERE success;

-- catalog: 番号のまま残ったお知らせがなく、ラージオブジェクトが 0
SELECT notice_id FROM content.notice WHERE body ~ '^[0-9]+$';
SELECT count(*) FROM pg_largeobject_metadata;

-- cart・chat: 重複が消えた(§2 のクエリが 0 行)
```

- **chat のログ**: `옛 메시지 시각 보정: N건을 +9시간 옮겼다` — 15 分後の 2 回目も見ます。失敗すると `대화 기록 정비(…) 실패` の警告が残り、次の起動で再実行されます。
- **order のログ**: V25 の `NOTICE: 같은 배송지를 가리키는 주문이 있어 …` が出ていないこと。出ていれば住所のユニークが作られていません — データを確認してから手で作ります。
- **画面**: 注文を一つ作り、注文詳細に「税込み」が出るか。DB では新しい住所の `order_id` が NULL、注文の `tax_price` が 0。
- **任意**: Redis の旧ルームハッシュは読む箇所がありません — `DEL CHAT_ROOM` で消して構いません。

## 6. 失敗したら — 前に進めて直す

- 失敗したサービスは旧 Pod が動き続け(ローリングが止まった状態)、成功したサービスは新バージョンのまま残ります。`ops/28` が新しい Pod の起動エラーを出力します — 直したイメージで再デプロイします。
- どうしても戻す必要があるなら、**スキーマを先に** 戻してからだけ: `FORCE_UNSAFE_ROLLBACK=1 ./ops/28-deploy.sh prod --rollback`。V18 の場合はお知らせ本文を再びラージオブジェクトに入れる必要があります(§3 のバックアップから復元するほうが簡単です)。お知らせは運用者しか書かないので、戻さずにお知らせ画面だけが一時的に止まるほうが通常はましです。

## 7. 次のリリース — contract

今回のバージョンが安定し、戻すことはないと判断できたら(例: 1 週間後)、新しいマイグレーションで削除します。

```sql
-- ROLLBACK-UNSAFE: 列を削除する — このバージョンより前のイメージは order_address.order_id・orders.tax_price を使う。
ALTER TABLE orders.order_address DROP COLUMN IF EXISTS order_id;   -- 外部キー・idx_order_address・ユニークも一緒に消える
ALTER TABLE orders.orders DROP COLUMN IF EXISTS tax_price;
```

列の削除は破壊的な変更なので `-- ROLLBACK-UNSAFE:` の宣言が必要です(ガード B-54)。エンティティは既に二つの列を使っていないので、コードの変更はありません。
