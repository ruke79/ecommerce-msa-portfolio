# 20. スキーマの所有権 — `ddl-auto` を切って Flyway に任せる

**日本語** | [한국어](20-schema-ownership.ko.md)

> この文書は、「誰がスキーマを作るのか」を Hibernate から Flyway に移した作業の記録である。
> 関連: [docs/09](09-postgresql-partitioning-sharding.md)（パーティショニング）· [docs/19 §5.1](19-local-performance-tuning.md)（カラーのマッピングエラー）

## 1. 何が問題だったのか

移行前、7 つのサービスすべてがこうなっていた:

```yaml
spring.jpa.hibernate.ddl-auto: update
```

Flyway はすでに導入されていたが（[docs/09](09-postgresql-partitioning-sharding.md) P0）、パーティションテーブルの
ような **特殊なものだけ**を作り、通常のテーブルは依然として Hibernate が作っていた。過渡期として意図した
状態だったが、その過渡期が 3 つのコストを払わせ続けていた。

**① スキーマ変更が履歴に残らない。** どのカラムがいつ、なぜできたのかを知る方法がない。コードの
diff はエンティティが変わったことを示すだけで、それがどのデプロイでどんな DDL として反映されたのかは教えて
くれない。ロールバック計画を立てる根拠もない。

**② 本番 DB にレビューされていない DDL が流れる。** `update` は起動のたびに、その時点のエンティティから
DDL を推測して実行する。レビューも、承認も、実行計画の確認もない。大きなテーブルにインデックスを張る
文がデプロイ中に黙って流れうる。

**③ 制約を消さない — これが実際に事故を起こした。** `update` は **追加しかしない**。そのため
`ProductSku.color` が `@OneToOne` だった頃に Hibernate が `color_id` に張った UNIQUE 制約は、
マッピングを `@ManyToOne` に直した後も DB にそのまま残った。エンティティを直すだけでは何も治ら
なかったので、結局 [V4 マイグレーション](../catalog-service/src/main/resources/db/migration/V4__relax_product_sku_color_unique.sql)
を手で書く必要があった。**③は①・②と違って理論上のリスクではなく、すでに支払ったコストである。**

## 2. 何に置き換えたのか

| | 前 | 後 |
|---|---|---|
| スキーマ生成 | Hibernate `ddl-auto: update` | Flyway `V*__baseline_schema.sql` |
| Hibernate の役割 | 生成 + 検証 | **検証のみ**（`validate`） |
| スキーマ変更の手順 | エンティティを直してデプロイ | マイグレーション作成 → レビュー → デプロイ |
| ずれたとき | 黙って ALTER | **起動失敗**（`Schema-validation: missing column`） |

`validate` はずれを **うるさく**する。これは副作用ではなく目的である — 黙って直されるより、
デプロイが止まる方がよい。

設定は `${DDL_AUTO:validate}` にして、環境変数で上書きはできるようにした。危険なのは緊急時の
意図的なオーバーライドではなく **コミットされたデフォルト値**だからであり、ガードレールもデフォルト値だけを見る。

## 3. ベースラインを手で書かなかった理由

`validate` に移行するには、Flyway が **エンティティと完全に一致する**スキーマを作れなければならない。
カラム 1 つ、型 1 つがずれただけでも起動が失敗する。それを手で書き写すのは失敗が約束された
作業である — catalog-service 1 つだけでもテーブル 10 個にカラム 85 個だ。

そこで **Hibernate 自身に書き取らせた**:

```bash
# ① Hibernate が作る DDL をそのまま取り出す（コンテキストを実際に起動しながら）
./gradlew -PexportSchema -PwithContainers :catalog-service:test --tests '*ApplicationTests*'
#   → build/schema/catalog-service.sql

# ② 冪等なマイグレーションに機械変換
./ops/tools/hbm2flyway.py build/schema/catalog-service.sql \
    -o catalog-service/src/main/resources/db/migration/V5__baseline_schema.sql
```

`-PexportSchema` は `jakarta.persistence.schema-generation.scripts.action=create` を有効にする
開発用スイッチである（CI は使わない）。エンティティを大きく変えた後にベースラインを取り直すときだけ使う。

### なぜそのまま使えず、変換が必要なのか

DB が 2 つの状態で存在するからである:

- **空の DB**（新しい環境・Testcontainers）— マイグレーションがすべてを作らなければならない
- **`ddl-auto` がすでに作ってある DB**（本番・開発サーバー）— すべてがすでにある

後者に `create table` をそのまま投げると、`relation already exists` で起動が落ちる。そこで
`hbm2flyway.py` がすべての文を **すでにあればスキップする**形に変える:

| Hibernate の出力 | 変換結果 |
|---|---|
| `create schema S` | `CREATE SCHEMA IF NOT EXISTS S` |
| `create table T (...)` | `CREATE TABLE IF NOT EXISTS T (...)` |
| `create index I on T (...)` | `CREATE INDEX IF NOT EXISTS I on T (...)` |
| `alter table T add constraint N ...` | `pg_constraint` で名前の存在を確認する `DO` ブロック |
| （カラムごとに追加生成） | `ALTER TABLE T ADD COLUMN IF NOT EXISTS c ...` |

最後の行が核心である。`create table if not exists` は **すでにあるテーブルに触れないので**、
既存 DB がエンティティより遅れていると（カラムが抜けていると）そのまま残り、`validate` が失敗する。
カラムごとの `add column if not exists` がその隙間を埋める。

> **「では ddl-auto と何が違うのか？」** — ddl-auto は起動のたびにその時点のコードから DDL を新たに
> 推測し、**レビューなしで**実行する。ベースラインはコミットされたファイルに書かれた **固定された文のリスト**であり、
> レビューを経て、Flyway が記録するので **ちょうど 1 回だけ**実行される。その後スキーマをまた変えるには
> 新しいマイグレーションを書かなければならない。

カラムは必ず **NULL 許容で**追加する。既存の行があるテーブルにはデフォルト値のない NOT NULL は付け
られないからであり、Hibernate の `validate` は NULL 許容かどうかを見ない（テーブル・カラムの存在と型のみ）。

### マイグレーションの順序が重要である

ベースラインは各サービスの **最後の**バージョンである（catalog V5、order/pay/user/cart V2、chat V3、
coupon V4）。先行するマイグレーションが作るもの — 特にパーティション化された Outbox テーブル — を上書きしない
ためである。空の DB で V1 がパーティションテーブルを作ると、ベースラインの普通の `create table` は
`IF NOT EXISTS` でスキップされる。パーティション構造が勝つ。

## 4. どう検証したのか

### 空の DB — ベースラインはエンティティと完全に一致するか

7 つのサービスすべてを `ddl-auto: validate` にして、Testcontainers の **空の** PostgreSQL で起動した。
ずれたカラムが 1 つでもあればコンテキストが起動しないので、**起動の成功そのものが証明**である。

```
:common:test :catalog-service:test :order-service:test :pay-service:test
:user-service:test :cart-service:test :coupon-service:test :chat-service:test
→ BUILD SUCCESSFUL
```

### 既存 DB — ベースラインは本当に何もしないのか

こちらが実際のリスクである。レガシー DB を再現し（以前のマイグレーション V1〜V3 + Hibernate が作ったであろう
スキーマ + 当時の UNIQUE 制約）、スキーマを **184 項目**でスナップショットした後、新しいマイグレーションを
適用した。

```
② V4 + V5 適用 → エラーなし（スキップしたオブジェクト 104 個）
③ スキーマ差分 → なし（184 → 184、V4 が消した UNIQUE 以外の変化なし）
④ 再適用       → エラーなし、スキーマ不変
   外部キー 9 個を保持、product_sku の UNIQUE 削除を確認
```

> 最初にこの検証を回したとき「差分なし」と出たが、実はスナップショットのクエリが
> `text || "char"` の型エラーで丸ごと失敗しており、**空のファイル 2 つを比較**していた。
> スナップショットが 184 行であることを確認して、ようやく意味のある比較になった。

### ガードレールは実際に止めるのか

`ddl-auto: update` を order-service に戻してビルドした → B-33 で即座に失敗。元に戻すと通過。

## 5. 皆さんの DB でやるべきこと

移行のリスクは **皆さんの既存 DB がエンティティとずれている場合**である。そうなるとデプロイ直後に起動
失敗する。デプロイ前に同じ判定を先に下すスクリプトがある:

```bash
# 診断のみ — 今デプロイしたらどうなるか
PGURL='postgresql://user:pw@host:5432/productdb' SERVICE=catalog-service \
  APPLY=0 ./ops/15-schema-baseline-rehearsal.sh

# ベースラインを適用してみて何が変わるかを確認（本番 DB ではなく **コピー**に）
PGURL=... SERVICE=catalog-service ./ops/15-schema-baseline-rehearsal.sh
```

スクリプトは ①適用前のスナップショット → ②ベースライン適用 → ③変化のリスト → ④**Hibernate validate と同じ
判定**（必要なカラムがすべてあるか）→ ⑤ddl-auto の残骸（ハッシュ名の UNIQUE 制約）の点検を行う。
終了コード 0 なら、その DB で `validate` で起動できるという意味である。

カラムをわざと 1 つ消した DB で試すと、こう出る:

```
④ ❌ 없는 컬럼 1 개 — 지금 배포하면 기동 실패합니다:
      product.product.refundpolicy
```

適用モードで回し直すと、そのカラムを埋めて通過する。

**推奨順序**: `ops/09` で立ち上げた復元版 → `ops/15` で診断 → 問題なければ適用モード → ステージング
（`ops/12`）→ 本番。

## 6. 今後スキーマを変えるには

1. エンティティを直す。
2. **マイグレーションを書く**（`V<다음번호>__<설명>.sql`）。ベースラインは直さない — すでに
   適用されたマイグレーションを修正すると Flyway のチェックサムが壊れる。
3. `./gradlew :<서비스>:test -PwithContainers` — 空の DB で `validate` が通るかどうかがそのまま検証である。
4. デプロイ前に `ops/15` で実際の DB を確認する。

ベースライン自体を取り直すことはまれである（エンティティを大規模に再編したとき程度）。そのときは §3 の
2 つのコマンドを再度回し、**新しいバージョン番号で**保存する。

## 7. 残っているもの

| 項目 | 状態 |
|---|---|
| 皆さんの本番 DB で `ops/15` を実行 | 🔴 **運用対応が必要** — このリポジトリでは確認できない |
| `flyway.baseline-on-migrate` を無効にする | 🟡 すべての環境が履歴を持つようになった後に |
| マイグレーションのロールバック（undo）スクリプト | 🟡 Flyway Teams の機能。現在は復元（ops/09）がロールバック経路である |
