# catalog-service 改善内容（What Changed）

**日本語** | [한국어](07-catalog-search-improvements.ko.md)

> 🕰️ **状態（2026-09-27）— 過去の記録。** R-C1〜3 · R-C6 はその後適用されました。R-C4（フィルターの正規表現 · `addTrustedPackages("*")`）は残っています。

> 対象: `catalog-service` · 関連: [問題点分析](06-catalog-search-issues.md)

原則は以前と同じである: **回帰リスクが低く静的に検証可能な修正だけを適用** し、大規模・検証が必要な項目（検索の ES への移行、インデックス作成パイプラインの再設計）は設計案として残す。当時の検証環境では catalog-service をビルド/実行できないため、変更した Java は **構文・括弧バランスの静的レビュー** しか経ていない（マージ前にビルド + 手動 QA を推奨）。

---

## 1. 検索/フィルターのキャッシュ無効化 — 「登録しても検索に出ない」の修正 🔴（CAT-2, CAT-12）

`src/main/java/com/project/catalog_service/service/ProductService.java`

商品が変更される外部からのエントリーメソッドに、検索・フィルターのキャッシュ無効化を追加:

| メソッド | トリガー | 追加 |
|---|---|---|
| `addProduct` | 商品登録（コントローラー） | `@CacheEvict(search_cache, colors_cache, sizes_cache, details_cache, products_by_category, allEntries=true)` |
| `updateRating` | 評価の変更（コントローラー） | 同上 |
| `updateProductSize_Qty`, `updateSoldValue` | （直接呼び出しに備えて） | 同上 |
| **`productUpdated`** | **注文成功の Kafka 消費** | 同上 — **核心** |

- **CAT-12（自己呼び出し）の反映**: `productUpdated` は内部で `updateSoldValue`/`updateProductSize_Qty` を `this` で呼ぶため、それらのメソッドのアノテーションはプロキシを経由しない。そこで **実際の外部エントリーポイントである `productUpdated` に evict を配置** し、注文による在庫変更時に検索キャッシュが確実に無効化されるようにした。
- 効果: **新規商品登録/評価/在庫の変更が即座に検索・フィルターに反映**（従来の最大 60 分の stale を解消）。

## 2. 複数項目の注文の在庫反映バグ修正 🔴（CAT-13）

`ProductService.productUpdated`
```diff
- for (item : orderProducts) { ... return productSize; }   // 最初の項目だけ反映
- return null;
+ ProductSize lastUpdated = null;
+ for (item : orderProducts) { ... lastUpdated = updateProductSize_Qty(...); }  // 全項目を反映
+ return lastUpdated;
```
- `orderProducts` の null ガードも追加。効果: 複数の商品を含む注文の **すべての項目** が販売数/在庫に反映される。

## 3. 検索ページパラメーターの安全化 🟠（CAT-3）

`src/main/java/com/project/catalog_service/service/SearchService.java`
```java
int safePage = Math.max(1, params.getPage()) - 1;         // page=0/負数 → 500 を防止
int safeSize = params.getPageSize() <= 0 ? 20 : Math.min(params.getPageSize(), 100); // 上限 100
```
- 効果: `page=0` での `PageRequest.of(-1,..)` による **500 を解消**、`pageSize` 無制限による **deep pagination/大量取得（DoS）** を遮断。

## 4. category 名での検索時の 500 を解消 🟠（CAT-4）

`SearchService` に `parseCategoryId()` ヘルパーを追加 — 数値なら id として、名前/空白なら `null`（名前検索の経路へ）。従来の `Long.parseLong` による **NumberFormatException（500）** を解消。

---

## 適用せずロードマップとして残した項目（設計案）

当時の検証環境でビルド/検証できない、または動作変更・運用上の判断が必要な項目は適用せず、方法だけを残す。

### R-C1. 検索を実際の Elasticsearch に移行（CAT-1）
- `SearchService` が Postgres の LIKE ではなく **ES に問い合わせる** よう移行: `multi_match`（name/brand/description）+ フィルター（カテゴリ keyword、価格/色/サイズの nested）+ 関連度ソート + `search_after` ベースのページネーション（deep pagination を回避）。
- レスポンス形式は `SearchResultDto` との互換を維持（集計でブランド/色/サイズのファセットを生成可能）。

### R-C2. インデックスのマッピング/アナライザーの実適用 + alias ロールオーバーの修正（CAT-9, CAT-10）
- インデックス作成前に **明示的な settings/mappings でインデックスを生成**（nori/kuromoji アナライザー、`@Nested` を維持）。`@Setting` リソースを catalog モジュールに含める。
- バッチのインデックス名と alias タスクレットの命名規則を **一致** させ（`*-products` サフィックスに統一）、すべてのドメイン alias（product/category/color/…）をアトミックにスワップ。

### R-C3. DB↔ES の整合性: Outbox ベースの増分インデックス作成（CAT-5）
- `addProduct`/`productUpdated` などの書き込みトランザクションで **Outbox イベント** を残し、リレーが ES に増分反映する。全件の再インデックスはバックアップ/再構築の用途に限る。
- 失敗時の再試行・DLQ・リコンサイル（定期的な DB↔ES の突合）を追加。

### R-C4. 検索の堅牢性/セキュリティ（CAT-14, CAT-16）
- `~*` 正規表現フィルター → `=`/`IN` またはエスケープしたパラメーターに置き換え（正規表現インジェクション/ReDoS を排除）。
- 検索クエリの `statement_timeout`/JPA タイムアウトを設定、マッパーの N+1 を解消（fetch join/DTO プロジェクション）。
- Kafka `JsonDeserializer` の信頼パッケージを **明示的なホワイトリスト** に制限。

### R-C5. コンシューマーの信頼性（CAT-8）
- 商品更新コンシューマーに DLQ + 再試行バックオフ + **冪等性**（処理済みイベントの dedup または条件付き更新）を入れ、重複消費時の在庫の二重引き当てを防ぐ。（backend [`docs/03` R-2/R-3](03-architecture-and-reliability.md) と同じパターン）

### R-C6. 管理エンドポイント/運用（CAT-6, CAT-7, CAT-15）
- `/products/sync`（再インデックス）・`/*/all`（全件ダンプ）を **管理者専用パス** に分離 + ゲートウェイでの認証、`/*/all` にページネーション。
- ES タイムアウトのプロパティキーを訂正（`spring.elasticsearch.connection-timeout`/`socket-timeout`）+ 妥当な値、レプリカ数≥1。
- `ddl-auto` を環境別に分離（本番は `validate`/`none`）、Kafka トラストストアのパスのハードコーディング（Windows の絶対パス）を削除。

---

## 検証

- 修正した `ProductService.java`、`SearchService.java`: **波括弧/括弧のバランスを確認**、`@CacheEvict` の 4→5 箇所・`parseCategoryId`/クランプの反映を確認。
- ⚠️ catalog-service は当時の検証環境ではコンパイル不可 → **マージ前のビルドおよび手動 QA が必須**: (1) 商品登録直後の検索への反映、(2) 複数項目の注文での全項目の在庫反映、(3) `page=0`/`pageSize=99999`/カテゴリ名検索での正常レスポンス。
