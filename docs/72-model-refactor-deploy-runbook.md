# 72. モデルリファクタリング 2 次のデプロイ手順書 — 元に戻せないデプロイを安全に

**日本語** | [한국어](72-model-refactor-deploy-runbook.ko.md)

> **状態(2026-10-04)** — 対象バージョンは `d7f87c9`(コードは `0c105d5` まで、最後のコミットは文書)です。何をなぜ直したかは [docs/62 §18](62-engineering-casebook.md)、デプロイスクリプト自体は [docs/36](36-deployment-pipeline.md)・[docs/33](33-deploy-runbook.md) です。

今回のデプロイは **自動ロールバックが無効になるデプロイ** です。catalog V18 が元に戻せないマイグレーション(ROLLBACK-UNSAFE)だからです。そこで通常のデプロイに加えて、**事前点検・バックアップ・デプロイ中の禁止事項・デプロイ後の確認** をこの文書の順に行います。

1 次のモデル修正(user V16 など)をまだデプロイしていなければ、同じデプロイで一緒に出ます — デプロイは常に一つのバージョン(コミット SHA)で全サービスです。

## 1. 何が変わるか

| サービス | 変更 | 内容 | 元に戻す |
|---|---|---|---|
| cart | V5 | ユーザーごとの重複カートを最新の一つにまとめ(**行を削除**)、`user_id` をユニークに | イメージを戻すだけでよい |
| chat | V9 | 同じ(ルーム, 会員)の参加の重複を削除 + ユニーク | イメージを戻すだけでよい |
| chat | 起動時にバックグラウンド | Mongo の会話インデックス作成、旧メッセージ時刻 +9 時間(15 分後に 2 回目) | 無効化: `chat.message-maintenance.enabled=false` |
| catalog | V18 | お知らせ本文をラージオブジェクト(OID)から本文へ移し、ラージオブジェクトを解放 | **不可 — ROLLBACK-UNSAFE** |
| order | V25 | `order_address.order_id` の NOT NULL を解除、`tax_price` の既定値 0、注文ごとの住所ユニーク | イメージを戻すだけでよい |
| pay | V7 | `tracking_id` インデックス、ユニークと重複していた `idx_payment` を削除 | イメージを戻すだけでよい |
| frontend | — | 注文詳細の税金行を「込み」に | — |

サービス同士が絡む契約の変更はありません — `OrderDto.taxPrice` の削除は JSON のフィールドが消えるだけで読む箇所はなく、`CouponStatus.EXPIRED` はどの行も持っていませんでした。そのため **サービス間のデプロイ順は関係ありません**(ゲートウェイを最後にするのは `ops/28` が常に守ります)。

## 2. 事前点検 — 本番 DB、読み取りのみ

マイグレーションが何を変えるかを先に見ておきます。数字はデプロイ後の確認(§5)と比べます。

```sql
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
```

## 3. バックアップ — 必須

V18 は元に戻せず、V5・V9 は行を削除し、時刻の補正は Mongo の文書を変えます。

```bash
./ops/08-backup-postgres.sh     # PostgreSQL base backup + WAL(PITR)
./ops/10-backup-mongo.sh        # MongoDB のダンプ + 空インスタンスへの復元検証
```

## 4. デプロイ

### 4-1. デプロイ中に守ること

- 🔴 **デプロイが終わるまで管理者のお知らせ編集を止めます。** ローリング中は旧 catalog Pod が残っており、その Pod でお知らせを保存すると本文が再び OID で保存されます。V18 はもう済んでいるので、その行は番号のまま残ります(§5 のクエリで見つけられます)。
- ローリング中に一時的に起きること — すべて想定どおりです。
  - 旧 catalog Pod がお知らせの読み込みに失敗することがあります(新しい Pod に替われば消えます)。
  - 旧 chat Pod が書いたメッセージは 9 時間早いまま保存されますが、15 分後の 2 回目の補正が同じ基準時刻で拾います。
  - 旧 order Pod が決済前の注文を破棄すると、住所の行が一つ残ることがあります(注文は削除されます)。
- インデックスの作成(cart・chat・order・pay)はその表への書き込みを一時的に止めます。今の表の大きさなら数秒です。

### 4-2. 実行

1. CI が対象コミットのイメージを作ったか確認します(タグ = コミット SHA)。
2. `deploy-oci` ワークフローを承認します(内部では `ops/28-deploy.sh prod <バージョン>`)。手動でも同じコマンドです。
3. 開始時に **「元に戻せないマイグレーション(ROLLBACK-UNSAFE)がこの範囲にあります」** という警告と V18 のパスが出ます — 正常です。このデプロイは失敗しても自動ロールバックせず、`--rollback` も拒否されます。
4. 1 段階目: ゲートウェイを除く全サービス。各サービスは起動時に自分のマイグレーションを適用します。
5. 2 段階目: ゲートウェイ → スモーク(`ops/06`)。

## 5. デプロイ後の確認

```sql
-- 各サービスの DB: 適用されたバージョン(cart 5・chat 9・catalog 18・order 25・pay 7)
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
