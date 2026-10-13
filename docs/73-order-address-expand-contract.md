# 73. 注文 ↔ 配送先の二重外部キー — expand(V25) → contract(V26) のマイグレーション手順

**日本語** | [한국어](73-order-address-expand-contract.ko.md)

> **状態(2026-10-04)** — R1(V25、expand)はモデルリファクタリング 2 次とともに本番デプロイ待ち([docs/72](72-model-refactor-deploy-runbook.md))。
> R2(V26、contract)は別ブランチに準備済みで、CI のコンテナテストを通過した — R1 が安定してから既定ブランチに取り込む。
> 何をなぜ直したかは [docs/62 §18](62-engineering-casebook.md)、expand-contract の一般論と先行事例は [docs/55](55-zero-downtime-migrations.md) にある。

列を二つ削除する作業を**なぜ二つのリリースに分けたのか、各リリースでスキーマ・コード・テストをどう変えたのか、デプロイ時に何を確認するのか**を一か所にまとめる。

---

## 0. 概要

| リリース | マイグレーション | コード | 削除するもの | ロールバック |
|---|---|---|---|---|
| **R0**(本番稼働中) | — | 注文 ↔ 住所が互いを指す | — | — |
| **R1** expand | order **V25**(`0c105d5`) | 注文 → 住所の一方向。二つの列をマッピングしない | なし — `order_id` の NOT NULL 解除、`tax_price` の既定値 0、注文側のユニーク | イメージを戻すだけでよい |
| **R2** contract | order **V26** | なし(テスト・e2e シードのみ) | `order_address.order_id`、`orders.tax_price` | R1 イメージまでは安全、R0 は不可(**ROLLBACK-UNSAFE**) |

R1 と R2 の間には **R1 が行き渡り安定したという判断**(例: 1 週間)がある。V26 は R1 と同じデプロイで出ると自ら失敗する(§5-2)。

---

## 1. 何が問題だったか

- **外部キーが二つあった。** `orders.order_address_id → order_address` と `order_address.order_id → orders`。注文を一つ作るたびに「注文 INSERT → 住所 INSERT → 注文 UPDATE(住所 id)」の 3 回の書き込みが必要で、削除時はどちらかを先に切る必要があった。二つの値が食い違っても防ぐものがなかった。
- **税額の列は常に 0 だった。** `Order.taxPrice` を埋める箇所がなく、画面はそれを「税金無料」と表示していた。価格は税込みである。

目標の形: 注文が住所を指す**一方向**(`Order.shippingAddress`)、注文ごとに住所一つは `orders.order_address_id` のユニークで守り、税額の列はなし。

---

## 2. なぜ一度に削除しないのか

このクラスタのデプロイの性質([docs/55 §1](55-zero-downtime-migrations.md))がそのまま当てはまる。

- **ローリングデプロイ** — しばらくの間、旧 Pod と新 Pod が同じ DB を使う。新 Pod が起動時にマイグレーションを適用するので、旧 Pod は**新しいスキーマ**の上で動く。
- **Hibernate `ddl-auto: validate`** — エンティティがマッピングする列がなければ Pod が起動しない。既に動いている旧 Pod はその列の読み書きで失敗する。
- **`ops/28 --rollback` はイメージだけを戻す** — スキーマは進んだまま残る。

したがって R0 のコードが使う列を R0 → R1 のデプロイで削除すると、ローリング中の旧 Pod も、戻した旧イメージも動かなくなる。**まずコードがその列を使わないようにし(expand)、そのバージョンが行き渡ってから削除する(contract)。**

---

## 3. R1 — expand(order V25)

### 3-1. スキーマ

```sql
ALTER TABLE orders.order_address ALTER COLUMN order_id DROP NOT NULL;   -- 新しいコードは空のままにする
ALTER TABLE orders.orders ALTER COLUMN tax_price SET DEFAULT 0;         -- 新しいコードは書かない
-- 注文ごとに住所一つ — 以前は order_address.order_id のユニークが守っていた。今後は注文側で。
-- 食い違ったデータ(同じ住所を二つ以上の注文が指す)があれば作らずに NOTICE だけを残す(マイグレーションは成功)。
CREATE UNIQUE INDEX uq_orders_order_address ON orders.orders (order_address_id);
```

列を削除も変更もしないので、B-54(破壊的マイグレーションの宣言)の対象ではない。

### 3-2. コード

- `Order.shippingAddress` を `@OneToOne(fetch = LAZY)` に。`OrderAddress` から `order` フィールド・`order_id` のインデックス・ユニークの宣言を外した。`Order.taxPrice` を削除した。
- 注文作成: **住所を先に保存し、注文がそれを指す** — INSERT 2 回(`OrderDataService`)。
- 決済前の注文の破棄: 注文が指す住所 id を読み → 切り離し → 住所・品目・注文の順に削除する(`OrderRepository`)。R0 の形(住所も注文を指す)の行も、住所を注文より先に消すので処理できる。
- フロントエンド: 注文詳細の税の行を「포함 / 込み」に。

### 3-3. ローリング中 — 旧 Pod と新 Pod が同時に動くとき

| 誰が | 何を | 結果 |
|---|---|---|
| R0 Pod | 注文作成 | これまで通り `order_id`・`tax_price` を埋める — 二つの列は残っているので正常 |
| R1 Pod | 注文作成 | 住所の `order_id` は NULL、`tax_price` は既定値 0 |
| R0 Pod | R1 が作った決済前の注文の破棄 | `order_id` で住所を探すので**住所の行が一つ残る** — 孤立した住所行にすぎず、注文は削除される |
| R1 Pod | R0 が作った注文の破棄 | 注文側の住所 id で削除するので正常 |

ロールバック: **イメージを戻すだけでよい。** R0 のコードは `order_id` を埋め続け、残るのは上記の孤立した住所行だけである。

### 3-4. テスト

- `OrderServiceApplicationTests`(Testcontainers): 新しい注文の住所は `order_id` が空で、税額の列は 0。決済前の注文の破棄が**新しい形と R0 の形の両方**で注文・住所を削除する。
- ガード `EntityMappingGuardTest`(to-one は LAZY)。

---

## 4. 間 — R1 が安定したかの確認

R2 を出す前に、本番 DB で(読み取り専用):

```sql
-- R1 の適用から十分に時間が経っている
SELECT installed_on FROM flyway_schema_history WHERE version = '25';
-- 注文側のユニークがある — NULL なら V25 が食い違ったデータのために作成を見送った(V26 は失敗する)
SELECT to_regclass('orders.uq_orders_order_address');
-- 見送られていた場合の原因調査: 同じ住所を指す注文
SELECT order_address_id, count(*) FROM orders.orders
 WHERE order_address_id IS NOT NULL GROUP BY 1 HAVING count(*) > 1;
```

そのうえで、R1 から戻すことはないと判断する — R2 を出すと **R0 には戻せない。**

---

## 5. R2 — contract(order V26)

### 5-1. スキーマ

```sql
ALTER TABLE orders.order_address DROP COLUMN IF EXISTS order_id;   -- 外部キー・idx_order_address・ユニークも一緒に消える
ALTER TABLE orders.orders DROP COLUMN IF EXISTS tax_price;
```

列の削除は破壊的な変更なので、ファイルに `-- ROLLBACK-UNSAFE:` を記した(ガード B-54)。`ops/28` はこの宣言を見て、そのデプロイの自動ロールバックを無効にする。
どうしても R0 に戻す必要があれば、列を作り直して値を埋める(ファイルのコメントに SQL がある — `order_id` は `orders.order_address_id` から逆に埋める)。

### 5-2. 自ら止まる二つの確認

削除の前に `DO` ブロックが確認し、満たさなければ `RAISE EXCEPTION` で失敗する。PostgreSQL は DDL もトランザクションなので**何も変わらず**、新しい Pod が準備できずに旧 Pod が動き続ける。

1. **時間ガード — V25 と同じデプロイではないこと。** V25 がこの実行で適用されたばかりなら(30 分以内、かつ DB の最初のマイグレーションより 10 分以上後)失敗する。ローリング中の R0 Pod が二つの列を使うからである。新しい DB(全マイグレーションを一度に適用)は旧 Pod がないので通す。order V18 と同じ方式である([docs/55 §3](55-zero-downtime-migrations.md))。
2. **データガード — 注文側のユニークがあること。** V25 が `uq_orders_order_address` の作成を見送った状態で `order_id` を削除すると、旧ユニークまで消えて注文ごとに住所一つを守るものがなくなり、「この住所の元の注文」という手掛かりも失われる。データを直してユニークを作ってから再デプロイする。

### 5-3. コード

エンティティは R1 で既に二つの列をマッピングしていないので、**変わるコードはない。** 消える列を使っていたテストだけを直した。

- `OrderServiceApplicationTests`: 二つの列がないことを確認。R0 の形の行を作っていたテストは、その形がもう存在し得ないので新しい形だけに。
- e2e `SagaEndToEndTest` のシードから `tax_price` を外した。

### 5-4. テスト — `OrderAddressContractMigrationTest`

Spring なしで Flyway だけを、実際の PostgreSQL(Testcontainers)で動かす。テストごとに新しいデータベースを作り、`flyway_schema_history` の適用時刻をずらして「以前からある本番 DB」を再現する。

| ケース | 準備 | 期待 |
|---|---|---|
| 正常 | V24 まで → R0 の形の注文 → V25 → R1 の形の注文 → V25 を 2 日前に | 二つの列がなく、住所・注文 → 住所は残り、同じ住所を指す注文は `uq_orders_order_address` で拒否 |
| 同じデプロイ | V24 まで(30 日前に) → V25・V26 を一度に | 失敗・二つの列とデータはそのまま |
| ユニークなし | 同じ住所を二つの注文が指す → V25(NOTICE) → 2 日前に → V26 | 失敗・`order_id` が手掛かりとして残る |
| 新しい DB | 全マイグレーションを一度に | 通過 |

結果: ブランチの CI(run 37195776469)で 4 件通過、order-service の統合テスト 306 件で失敗 0。受け入れテスト(Saga の E2E)も V26 のスキーマで通過。
ガード B-54 は、`ROLLBACK-UNSAFE:` の宣言を外すと V26 を検出して失敗することを確認した(変異確認)。

### 5-5. ローリング中・ロールバック

- ローリング中の旧 Pod は R1 のイメージである — 二つの列をマッピングしないので影響はない。
- R1 のイメージに戻すのは安全である。ただし `ops/28` は ROLLBACK-UNSAFE を含む範囲の `--rollback` を止めるので、どうしても必要なら `FORCE_UNSAFE_ROLLBACK=1` で明示する([docs/72 §6](72-model-refactor-deploy-runbook.md))。

---

## 6. デプロイ手順のまとめ

| 段階 | R1(V25) | R2(V26) |
|---|---|---|
| 事前点検 | 同じ住所を指す注文の数([docs/72 §2](72-model-refactor-deploy-runbook.md)) | §4 の三つのクエリ |
| バックアップ | `ops/08`・`ops/10`(同じデプロイに catalog V18 があるので必須) | `ops/08` — 列の削除 |
| デプロイ | `ops/28-deploy.sh prod <SHA>` — ROLLBACK-UNSAFE の警告(catalog V18) | `ops/28-deploy.sh prod <SHA>` — ROLLBACK-UNSAFE の警告(V26) |
| デプロイ後 | order の flyway が 25・V25 の NOTICE がない・新しい住所の `order_id` は NULL | order の flyway が 26・二つの列がない(`information_schema.columns`) |
| 失敗したら | イメージだけを戻す | 何も変わらない — 原因(時間・データガード)を直して再デプロイ |

---

## 7. 仕組みのまとめ

| 仕組み | 守るもの | 場所 |
|---|---|---|
| B-54 | 破壊的なマイグレーションは `ROLLBACK-UNSAFE:` を宣言する | `common` `ConfigurationGuardrailTest` |
| `ops/28` | ROLLBACK-UNSAFE を含むデプロイは自動ロールバックを無効にし、`--rollback` を止める | `ops/28-deploy.sh` |
| 時間ガード | expand と contract を同じデプロイで出さない | V26 の `DO` ブロック |
| データガード | 代わりのユニークなしに旧ユニークを消さない | V26 の `DO` ブロック |
| 実 PostgreSQL のマイグレーションテスト | 旧い形のデータ → 新バージョンの適用 | `OrderAddressContractMigrationTest` |

---

## 8. 残っていること

- R1 のデプロイと安定の確認 → contract ブランチを既定ブランチの最新に rebase → 既定ブランチに取り込む → R2 のデプロイ。
- R2 のデプロイ後、残作業の一覧から「次のリリース(contract)」を消す。
