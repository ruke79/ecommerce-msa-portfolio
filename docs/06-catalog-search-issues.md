# catalog-service（商品登録/検索 · Elasticsearch）問題点分析

**日本語** | [한국어](06-catalog-search-issues.ko.md)

> 🕰️ **状態（2026-09-27）— 過去の記録。** ES 検索 · リアルタイム索引 · `ddl-auto` はその後修正しました（[07](07-catalog-search-improvements.md) · [13](13-secrets-dbha-es-search.md) · [16](16-saga-e2e-and-observability-verification.md) · [20](20-schema-ownership.md)）。

> 作成日: 2026-07-24
> 対象: `catalog-service`
> 観点: 検索の正確性/性能 · DB↔ES 整合性 · キャッシュ · セキュリティ/DoS · 本番での堅牢性
> 関連: [改善結果](07-catalog-search-improvements.md)

この文書は**改善以前**の状態をファイル・行の根拠とともに記録する。

---

## 0. 要約 — 最も重要な発見

**「Elasticsearch 検索サービス」なのに、肝心の検索が Elasticsearch を使っていない。**

- 実際の検索エンドポイント `GET /api/search` → `SearchService.searchProducts()` は **Querydsl/JPA で PostgreSQL を照会**する。ES のドキュメント/リポジトリは存在するが、**検索経路から一度も呼ばれない**（`SearchService` に `ProductDocumentRepository`/`ElasticsearchOperations` の参照 0 件）。
- しかも商品名/ブランド/詳細は `likeIgnoreCase("%" + term + "%")` — **先頭ワイルドカード LIKE** のためインデックスが使えない**フルスキャン**（O(N)）で、関連度ランキング・形態素解析・誤字補正は皆無。ES が存在する理由をまったく活かせていない。
- ES は**手動の `POST /api/products/sync` バッチ再索引**のときだけ埋まり、`addProduct`（商品登録）は **ES に書き込まない** → DB と ES が常にずれている。

| # | 問題 | 深刻度 |
|---|---|---|
| CAT-1 | 検索が ES 未使用、Postgres `LIKE '%..%'` フルスキャン | 🔴 High |
| CAT-2 | 検索/フィルタキャッシュの**無効化がない** → 商品を登録しても最大 60 分検索に反映されない | 🔴 High |
| CAT-3 | ページパラメータの検証がない → `page=0` で **500**、`pageSize` 無制限で **deep pagination/DoS** | 🟠 Med |
| CAT-4 | `category` 名で検索すると `Long.parseLong` → **NumberFormatException 500** | 🟠 Med |
| CAT-5 | DB↔ES の**整合性がない**（登録は DB のみ、ES は手動バッチ） | 🔴 High |
| CAT-6 | `/products/all` など**無制限 findAll** + `/products/sync` が**認証なしで**再索引をトリガー | 🟠 Med |
| CAT-7 | ES 接続設定の空白（タイムアウト/レプリカ/TLS 不明）、単一ノード前提 | 🟡 Low |
| CAT-8 | 商品更新 Kafka コンシューマに DLQ/冪等性がない（共通の問題） | 🟠 Med |
| CAT-9 | **ES マッピング/アナライザが実際には適用されていない**（`createIndex=false` + 索引前にマッピング未作成） | 🔴 High |
| CAT-10 | **エイリアス（alias）ロールオーバーのバグ**で再索引に成功しても公開されない | 🔴 High |
| CAT-11 | `search_cache` の**キャッシュキー不一致**（DTO に equals/hashCode がない）→ ヒット率 0% + 無限に蓄積 | 🟠 Med |
| CAT-12 | 在庫更新経路の**自己呼び出し（self-invocation）**で `@CachePut`/`@Transactional` が無効化 | 🟠 Med |
| CAT-13 | `productUpdated` が**注文の最初の項目だけ**在庫に反映（ループ内 return） | 🔴 High |
| CAT-14 | `~*`（POSIX regex）にユーザー入力 → **正規表現インジェクション/ReDoS**、クエリタイムアウトなし、N+1 | 🟠 Med |
| CAT-15 | `ddl-auto: create-drop` がデフォルトの `local` プロファイルで有効 → **再起動時にスキーマ削除（データ損失）** | 🔴 High |
| CAT-16 | Kafka `JsonDeserializer.addTrustedPackages("*")` → デシリアライズガジェット（RCE 類）の危険 | 🟠 Med |

---

## CAT-1. 検索が Elasticsearch を使っていない 🔴

`src/main/java/com/project/catalog_service/service/SearchService.java`
```java
// 商品名/ブランド/詳細をすべて先頭ワイルドカード LIKE で照会 → インデックス不可、フルスキャン
builder.and(product.name.likeIgnoreCase("%" + params.getSearch() + "%"));
builder.and(product.brand.likeIgnoreCase("%" + params.getBrand() + "%"));
detailsBuilder.or(productDetails.value.likeIgnoreCase("%" + params.getStyle() + "%"));
```
- `SearchController.searchProducts` → `searchService.searchProducts(params)` → **すべて JPA/Querydsl（Postgres）**。
- `ProductDocument`、`ProductDocumentRepository`（ES）およびバッチ索引ジョブは存在するが、**検索に接続されていない** → ES は事実上**死蔵**。
- 結果: 商品数が増えるほど検索遅延が線形に増加し、関連度順位/形態素/同義語/誤字補正は不可能、ES 導入の目的を達成できていない。

## CAT-2. 検索/フィルタキャッシュの無効化がない → 登録商品が最大 60 分間検索に反映されない 🔴

`SearchService` の `searchProducts`、`getColors`、`getSizes`、`getDetails`、`findAllByCategory` はすべて `@Cacheable`（Redis）で、`search_cache` の TTL は **60 分**（`config/redis/RedisCacheConfig.java`）。

しかし `ProductService` の書き込みメソッド（`addProduct`、`updateRating`、`updateSoldValue`、`updateProductSize_Qty`）には **`@CacheEvict` がまったくない**（`product`/`products` キャッシュに対する `@CachePut` のみ存在）。したがって:
- **新規商品を登録しても、検索結果・フィルタ（色/サイズ/ブランド/詳細）が最大 60 分間更新されない。**
- 在庫/販売数/評価が変わっても検索フィルタが stale。

→ ユーザーが体感する「商品を登録したのに検索に出てこない」バグの直接の原因。

## CAT-3. ページパラメータの検証がない 🟠

`SearchService.searchProducts`
```java
Pageable pageRequest = createPageRequest(params.getPage() - 1, params.getPageSize());
```
- `SearchController` が `SearchParamsRequest` を `@Valid` なしでバインド。DTO にも `@Min/@Max` がない。
- `page=0`（または未指定のデフォルト値 0）→ `PageRequest.of(-1, ...)` → **IllegalArgumentException → 500**。
- `pageSize` に上限なし → 非常に大きな値で**大量照会/deep pagination** を誘発（メモリ・DB 負荷、DoS）。

## CAT-4. category 名で検索すると 500 🟠

```java
Long categoryId = (params.getCategory() != null) ? Long.parseLong(params.getCategory()) : null;
```
- `category` はコード上 **id（数値）と名前（文字列）の両方**として使われる（後で `findBrandsByCategoryName(params.getCategory())` を呼ぶ）。名前（例: `"shoes"`）が入ると `Long.parseLong` が **NumberFormatException → 500**。

## CAT-5. DB↔ES の整合性がない 🔴

- `ProductService.addProduct`（商品登録）は **Postgres にだけ保存**し、ES（`ProductDocumentRepository`）には書き込まない。
- ES 索引は `SearchController.syncProducts()` がトリガーする**全件再索引バッチ**（`indexingJob`、新しいインデックス `products-<timestamp>` を作成）でのみ更新される。
- つまりバッチを実行するまで ES は DB と常に不一致。バッチ失敗/部分失敗時の整合性回復手段（リトライ/DLQ/リコンサイル）もコードからは見えない。
- （仮に検索を ES に切り替えても）**二重書き込みの原子性**（DB コミット後に ES が失敗した場合の処理）や **outbox ベースの索引**がなければ divergence が再発する。

## CAT-6. 無制限/無認証のエンドポイント 🟠

`SearchController`
- `/api/products/all`、`/api/categories/all`、`/api/product-skus/all` など **8 個**のエンドポイントが `repository.findAll()` を**ページネーションなしで**返す → ドキュメント全件ダンプ（メモリ/ネットワーク DoS）。
- `POST /api/products/sync` → **全件再索引バッチ**をトリガー。ゲートウェイの catalog ルートには `JwtAuthorization` フィルタがなく（商品照会は公開）、**認証なしで呼び出し可能** → 繰り返し呼び出しで ES/DB に負荷（DoS）。再索引のような管理作業は**管理者専用経路**であるべきだ。

## CAT-7. ES 接続/運用設定 🟡

`config/es/ElasticsearchConfig.java` には `@EnableElasticsearchRepositories` しかなく空である → 接続/タイムアウト/資格情報は Spring Boot 自動設定（`spring.elasticsearch.*`）に依存。 
- ソケット/コネクションタイムアウト、リトライ、TLS、レプリカ/シャード数（単一ノード前提）が明示されていない → 本番で ES が遅延するとリクエストが hang、単一ノード SPOF。

## CAT-8. 商品更新 Kafka コンシューマの信頼性 🟠

`message/consumer` + `message/config` の商品更新リスナー（注文成功時に販売数/在庫を更新）は、[`docs/03`](03-architecture-and-reliability.md) で指摘した **DLQ/リトライ/冪等性の欠如**という問題を共有している。重複消費時に在庫/販売数が二重に反映される危険。

## CAT-9. ES マッピング/アナライザが実際には適用されていない 🔴

`document/ProductDocument.java` は `name/description/brand` に韓/英/日の `@MultiField` アナライザ、`subcategories/skus` に `@Field(type=Nested)`、`@Setting(settingPath="/es/es-settings.json")`（nori/kuromoji）を宣言している。しかし:
- `@Document(indexName="products", createIndex=false)` → アプリがマッピングでインデックスを作成しない。
- バッチは `elasticsearchOperations.save(...)` で**タイムスタンプ付きインデックスにドキュメントをそのまま保存** → ES が**動的マッピング + デフォルトの `standard` アナライザ**でインデックスを自動作成する。
- 結果: 韓国語/日本語アナライザは**存在せず**、`@Nested` は通常の `object` に格下げされて nested クエリが失敗する。マッピング/アナライザ設計がすべて死文化。（しかも `@Setting` のパスは `common` jar にしか存在しない）

## CAT-10. エイリアス（alias）ロールオーバーのバグ — 再索引しても公開されない 🔴

`batch/config/ElasticsearchBatchConfig.java`: writer は `newIndexName + "-products"`（例: `products-<ts>-products`）に書き込むが、alias ロールオーバーのタスクレットは `products-<ts>`（接尾辞なし）の存在を確認・切り替えようとする → 対象インデックスがないため**常に例外（"New index ... does not exist"）** → `product-alias` が**永遠に再指定されない**。つまり再索引が「成功」しても新しいインデックスが検索に公開されない（ブルー・グリーンの意図は良いが実装が壊れている）。category/color など他の alias はそもそも管理されていない。

## CAT-11. search_cache ヒット率 0% + 無限に蓄積 🟠

`SearchService.searchProducts` は `@Cacheable(value="search_cache")` に **key 未指定** → デフォルトの `SimpleKeyGenerator` が引数 `SearchParamsRequest` からキーを作る。ところが `SearchParamsRequest`（common モジュール）には **`equals`/`hashCode` がない**（`@Getter/@Setter/@ToString` のみ）。リクエストごとに新しいオブジェクト → キーが絶対に一致せず、**キャッシュ照会は 100% miss、保存は毎回発生**（上限は 60 分 TTL のみ）。事実上純粋なオーバーヘッド。

また `products` キャッシュは **TTL なし（permanent）** + **`@CacheEvict` なし** → `getProductsByCategory/getProductsByName/getProducts('all')` などの一覧が**永久に stale**。

## CAT-12. 自己呼び出しでキャッシュ/トランザクションのアノテーションが無効化 🟠

`ProductService.productUpdated` が内部で `this.updateSoldValue`、`this.updateProductSize_Qty` を呼び出す。Spring AOP プロキシは**自己呼び出し（self-invocation）を横取りできない**ため、それらのメソッドの `@CachePut`/`@CacheEvict`/`@Transactional` は**実行されない**。（`private @Transactional createOrFindProduct` も同様 — private はプロキシ対象外。）→ 注文ベースの在庫変更時、キャッシュ更新は実際には起きていなかった。

## CAT-13. productUpdated が注文の最初の項目だけを反映 🔴

`productUpdated` の `return productSize;` が **`for (orderProducts)` ループの中**にあり、複数の商品を含む注文でも**最初の項目だけ**販売数/在庫が更新され、残りは黙って漏れていた。在庫整合性を直接損なう。

## CAT-14. 正規表現インジェクション/ReDoS + クエリタイムアウトなし + N+1 🟠

- `repository/jpa/data/ProductSkuRepository.findProductIDBySizeAndPriceAndColor` が `b.size ~* :size`、`c.color ~* :color` で**ユーザー入力を Postgres 正規表現演算子（`~*`）の右辺**に入れる → パラメータバインディングは SQL インジェクションは防いでも**正規表現インジェクション**は防げない。悪意あるパターンで catastrophic backtracking（ReDoS/DoS）を誘発でき、メタ文字がマッチングを汚染する。
- 検索クエリに `statement_timeout`/JPA クエリタイムアウトがなく、フルスキャン LIKE + 正規表現フィルタが重なると **DB コネクションを無限に占有**。
- ページ照会後に `ProductMapper.toDto` が `category/subcategories/questions/skus`（→sizes/color）を遅延ロードで参照 → ページ全体で **N+1**。

## CAT-15. ddl-auto: create-drop（データ損失） 🔴

`application-local.yml` の `spring.jpa.hibernate.ddl-auto: create-drop` + デフォルト有効プロファイル `local` → **再起動のたびにスキーマを drop/再作成**。ローカルの使い捨て用でなければ再起動時に全データ損失であり、DB 自体が揮発的なら DB↔ES 整合性の議論も無意味になる。（付随的に Kafka トラストストアが `file:E:/github/...`  **Windows 絶対パス**でハードコード → コンテナ/CI で壊れる。）

## CAT-16. Kafka デシリアライズの信頼パッケージがワイルドカード 🟠

`message/config/KafkaConsumerConfig.java` の `JsonDeserializer.addTrustedPackages("*")` → 型ヘッダで指定された**すべての型を信頼**。攻撃者がトピックにアクセスできればデシリアライズガジェット（RCE 類）の危険。信頼パッケージを明示的に制限すべきだ。

---

## 改善の優先順位

- **即時（安全な）修正 — 適用済み**（[`docs/07`](07-catalog-search-improvements.md)）:
  - CAT-2 キャッシュ無効化（商品登録/評価/在庫変更時に検索キャッシュを evict）— CAT-12 の自己呼び出し問題を考慮し、**外部エントリポイント `productUpdated` に evict** を配置。
  - CAT-13 `productUpdated` が注文の**すべての項目**を処理するよう修正（ループ内 return を除去）。
  - CAT-3 ページパラメータのクランプ（0/負数→安全、`pageSize` 上限 100）。
  - CAT-4 `category` 名入力時の安全なパース（500 を除去）。
- **文書化 + 承認後に進行（大規模・検証が必要、未適用）**:
  - CAT-1・CAT-5・CAT-9・CAT-10 — 検索を**実際の ES に切り替え** + 明示的なマッピング/アナライザでインデックス作成 + alias ロールオーバー修正 + **outbox ベースの増分索引**。クエリの書き換え・レスポンス形式・無停止の索引切り替えが必要で、当時の検証環境ではビルド/検証できない → **設計案のみ提示**。
  - CAT-14（正規表現インジェクション/タイムアウト）、CAT-15（`create-drop`）、CAT-16（信頼パッケージ）、CAT-6（管理エンドポイントの分離）、CAT-7（ES タイムアウト/レプリカ）、CAT-8（コンシューマ DLQ/冪等性）、CAT-11（キャッシュキー/`products` evict）— 動作変更・運用判断・再検証が必要なためロードマップとして明記。

> ⚠️ 適用した修正は、当時の検証環境で catalog-service をコンパイル/実行できないため**静的レビュー（構文・括弧のバランス）のみ**を経た。マージ前にビルドおよび、商品登録→検索反映、複数項目注文の在庫反映シナリオの手動 QA を推奨する。
