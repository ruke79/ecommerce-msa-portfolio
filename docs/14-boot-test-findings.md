# 実際の起動テスト — 発見された起動不能バグと検証結果

**日本語** | [한국어](14-boot-test-findings.ko.md)

> 🕰️ **状態（2026-09-27）— 過去の記録。** 起動テスト当時の記録です。ガードはその後 B-69 まで増えました。テスト戦略は [docs/17](17-testing.md)。

> 作成日: 2026-07-27
> 背景: それまでの作業ではコンパイル・単体テスト・SQL 実行までを検証しただけで、**アプリケーションを一度も起動したことがなかった。**
> それが残っている最大の未検証リスクだったので、実際の PostgreSQL/Redis を立ち上げてサービスを起動した。

## 結論から

**サービスは起動すらできない状態だった。** コンパイルと単体テストでは決して捕まらない起動阻害バグが次々と明らかになり、すべて修正した後になって初めてアプリケーションが立ち上がった。

```
2026-07-27 21:34:28  INFO  Started OrderServiceApplication in 19.051 seconds
```

**最終的にアプリケーションサービス 8 種すべての起動を確認した**（下記 §「起動確認の状況」）。
そして起動テストを終えた後に実行した `./gradlew test` は **22 件失敗で赤信号だった** — これも併せて直し、初めて全体が緑信号になった（B-15〜B-18）。

## 発見された起動不能バグ

### B-1. profile-specific ファイル内の `spring.profiles.active` — 6 サービス 🔴

```
InvalidConfigDataPropertyException: Property 'spring.profiles.active' imported from
location 'class path resource [application-local.yml]' is invalid in a profile specific resource
```

`application.yml` がすでに `spring.profiles.active: local` でプロファイルを有効にしているのに、**プロファイル専用ファイルである `application-local.yml` が同じプロパティを再宣言**していた。Spring Boot 2.4+ はこれを禁止しており、**起動を即座に中断**する。

- 影響: catalog · order · pay · coupon · gateway · config-server · **user · cart · chat** = **9 サービス**
  （⚠️ 最初の修正時に 6 つだけ直し、残り 3 つは 4 スペースのインデントだったため 2 スペース前提の grep に引っかからず漏れた → 2 回目の点検で発見・修正）
- 流入: 既存コミット `8747dd3`（"application-local.yml Reafactoring"）— 今回の作業の変更とは無関係
- 修正: 重複宣言を除去（プロファイルの有効化は `application.yml` が担うので機能の変化なし）

### B-2. `feign.*-url` プロパティがどこにも定義されていない — 5 サービス 🔴

```
java.lang.IllegalArgumentException: http://${feign.cart-url} is malformed
```

`@FeignClient(url = "${feign.cart-url}")` が参照する値が**どの yml/properties/compose にもなかった。** k3s 作業（[docs/08](08-k3s-deployment.md)）で「このままでは起動に失敗するだろう」と指摘して ConfigMap にだけ入れておいたが、**実際に起動不能であることが実証**された。

- 影響: order（3 個）· cart（2 個）· coupon · chat · user の **5 サービス**
- 修正: 各サービスの `application-local.yml` の `feign:` ブロックに、環境変数で上書き可能なデフォルト値を追加
  （`cart-url: ${FEIGN_CART_URL:https://localhost:8082}` の形 — k8s ConfigMap の `FEIGN_*_URL` がそのまま優先適用される）

### B-3. order-service の Redis プロパティ未定義 🔴

```
PlaceholderResolutionException: Could not resolve placeholder 'spring.data.redis.host'
```

order-service の `RedisCacheConfig`/`RedissonConfig` が `spring.data.redis.host/port/password/ssl.*` を要求するのに、**application-local.yml に redis ブロックが丸ごとなかった**（他のサービスにはある）。

- 修正: 他のサービスと同じ形の redis ブロックを追加（環境変数で上書き可能）

## あわせて見つかった非致命的な問題

### B-4. インデックス名の衝突 `idx_order`（既存バグ）
```
WARN  GenerationTarget encountered exception ... [ERROR: relation "idx_order" already exists]
```
`Order` と `OrderProduct` が**同じインデックス名 `idx_order`** を宣言している（PostgreSQL のインデックス名はスキーマ単位で一意）。結果として **`OrderProduct` のインデックスは永遠に作成されない**（該当する照会経路にインデックスがない）。→ `idx_order_product` に改名。

### B-5. Flyway ↔ Hibernate のインデックス重複作成の試み（文書訂正）
`idx_payment_outbox_event` を Flyway が作った後、Hibernate `ddl-auto: update` が**同じ名前で再び CREATE を試み**て WARN を残す。[docs/09 §3.1](09-postgresql-partitioning-sharding.md) で「インデックス名をエンティティと同じにして再作成の試みを遮断した」と書いたが、**その主張は不正確だった** — 試みはするし、失敗を警告として残す。起動に支障はないが、`ddl-auto: validate` に切り替えれば消える。

### B-6. `RedissonConfig` が全体コメントアウトされている
カスタム `RedissonConfig` はすべてコメント状態で、実際の `redisson` Bean は **Redisson の自動設定**が作る。このため `spring.data.redis.password` が空文字列だと AUTH 失敗で起動が止まる（パスワードなしの Redis を使う場合の罠）。

### B-7. Redis プロパティ名の不一致 / ブロックの欠如 — 5 サービス 🔴
`RedisCacheConfig` が `spring.data.redis.ssl.keystore` · `ssl.truststore` を要求するのに、yml には **`cert`/`key` という別の名前**しかなかった。cart-service は redis ブロック自体がなかった。
```
PlaceholderResolutionException: Could not resolve placeholder 'spring.data.redis.ssl.keystore'
```
- 影響: catalog · pay · chat · user（名前の不一致）+ cart（ブロック皆無）— **5 サービスが起動不能**
- 修正: コードが要求する名前でキーを追加（環境変数で上書き可能）、cart はブロックを新設

### B-8. catalog のカスタム EMF が `ddl-auto` を受け取れない 🔴
```
relation "product.category" does not exist
```
catalog は `DataSourceConfig` で EMF を**手で生成**しているが、JPA プロパティマップが**コメントアウト**されていた。つまり `spring.jpa.hibernate.ddl-auto` が EMF に届かず、**空の DB ではスキーマがまったく作られない**（新規環境で起動不能）。`create-drop` が無害だったのも同じ理由 — そもそも一度も適用されていなかった。
- 修正: `spring.jpa.*` の値を `@Value` で読み、`setJpaPropertyMap` で明示的に注入

### B-9. （自分が作ったバグ）`ProcessedEvent` が EMF のスキャン対象外 🔴
冪等性エンティティを `message.model` パッケージに置いたが、catalog の EMF は `com.project.catalog_service.model` しかスキャンしない → `Not a managed type` で起動失敗。**単体テストはリポジトリをモックしていたので通った** — 起動テストでしか捕まえられない種類のバグ。`model` パッケージに移動して解決。

## 2 回目の起動テスト — user · pay · chat · cart（B-10 〜 B-14）

1 回目で未検証として残したサービスを実際に立ち上げたところ、**さらに 5 件のバグが出た。** 起動テストは 1 回では終わらない、というのが要点だ。

### B-10. chat-service の環境変数名の誤字 `LOCAL_KEY_STOE_PASSWORD` 🔴
```
PlaceholderResolutionException: Could not resolve placeholder 'LOCAL_KEY_STOE_PASSWORD'
  in value "${LOCAL_KEY_STOE_PASSWORD}" <-- "${spring.data.mongodb.keystorePassword}"
```
`STORE` の `R` が抜けている。`MongoSslConfig` が `@Value` でデフォルト値なしに読むので、**誤字のある名前の環境変数をそのまま export しない限り chat-service は決して起動しない。** → 正しい名前に修正。

### B-11. user-service のアクチュエータが 401 — k8s プローブが永遠に失敗 🔴
user-service だけが Spring Security を使っているが、`.anyRequest().authenticated()` だけでアクチュエータの例外がなかった。結果:
```
GET /actuator/health/readiness  →  401
```
- **k8s は Pod を永遠に NotReady のままにする**（readiness 失敗 → トラフィック未到達、liveness 失敗 → 再起動ループ）。
- **Prometheus も user-service だけスクレイプできない** — 今回の作業で作った可観測性スタックに穴が開く。
- 修正: `EndpointRequest.to(HealthEndpoint.class)` と `prometheus`/`info` だけを `permitAll()`。
  ゲートウェイは `/user-service/api/**`・`/sse` だけをルーティングするので、この経路が外部に開かれることはない。
- 検証: `readiness 200` · `prometheus 200` · `/api/user/me はそのまま 401`。

### B-12. chat-service が PostgreSQL に MySQL の DDL を送る 🔴
```
ERROR: syntax error at or near "engine"
ERROR: syntax error at or near "auto_increment"
```
データソースは PostgreSQL なのに `dialect: org.hibernate.dialect.MySQLDialect` だった。Hibernate が MySQL 文法の DDL を生成し、**すべてのテーブル作成が失敗**した。しかもこの失敗は WARN としてしか残らず、**テーブルなしでサービスが「起動している」状態**になる。
→ `PostgreSQLDialect` に修正。このバグのおかげで chat のテーブルがそもそも一度も作られていなかったことが証明され、下記の DB 分離も安全に適用できた。

### B-13. エンティティが使うスキーマを誰も作らない — chat · coupon 🔴（静かな失敗）
```
Error executing DDL "create table chat.member (...)" [ERROR: schema "chat" does not exist]
```
`@Table(schema = "chat")` を使っているが、**Hibernate はテーブルは作ってもスキーマは作らない。** 他のサービスは Flyway マイグレーションに `CREATE SCHEMA` があったが、chat・coupon にはなかった。WARN だけが残って起動は続くので、**ログを最後まで読まなければ見えない。**
→ `V2__create_chat_schema.sql` · `V2__create_coupon_schema.sql` を追加。
→ 検証: 作り直した空の DB で `chat.member`、`coupon.coupon` の作成を確認。

> coupon-service は 1 回目の起動テストで「成功」と記録されたが、**テーブルがないまま起動していた。**
> 「起動した」と「動作する」は違うことを示す事例だ。

### B-14. Redisson が平文の Redis に TLS ハンドシェイク — chat · cart 🔴
```
INFO  RedissonConfig - SSL is not enabled for Redisson connection.
Caused by: SslHandshakeTimeoutException: handshake timed out after 10000ms
```
アドレスが `"rediss://" + host` で**固定**されていて、`ssl.enabled=false` でも TLS を試みる。ログは「SSL を使わない」と出しながら実際には TLS で接続する、診断を最も難しくする組み合わせだ。
→ `(useSsl ? "rediss://" : "redis://")` でスキームをフラグに連動させた。

## テストスイートが赤信号だった（B-15 〜 B-18）

起動の問題を整理した後に `./gradlew test` を回すと、**36 件中 22 件が失敗**だった。つまりこのリポジトリのテストは**信頼できない状態**で、その中に本物の製品バグが混ざっていた。

### B-15. null 参照による実際の 500 — catalog 🔴
テストが明らかにした**製品コードの NPE 3 種**:

| 位置 | 症状 |
|---|---|
| `ProductMapper.toDto` | `getShipping().toPlainString()` — 送料が空の商品が**1 つ**あるだけで一覧照会全体が 500 |
| `ProductMapper.toDto` | `getDetails()/getSubcategories()/getQuestions()/getSkus()` の無防備な `.stream()` — コレクションが null のエンティティで 500 |
| `ProductService.getCartProductInfo` | 同じ `getShipping()` の参照 — その商品を**カートに入れた瞬間** 500 |
| `ProductSkuItemProcessor.process` | CSV の `discount`/`sold` は `Integer`（null 可）なのにエンティティは `int` → **空のセル 1 つがアンボクシング NPE でインポートジョブ全体を殺す** |

→ すべて null 安全に修正（`mapList` ヘルパーを導入、空のセルはエンティティのデフォルト値 0 に）。

### B-16. 存在しない API を検証していたテスト 🟠
- `GET /api/product/products/by-subcategory` — **そんなマッピングはない。** 常に 404 だった。（実際に `getProductsByCategoryAndSubcategory` は HTTP で公開されたことがなく、キャッシュウォームアップの経路でしか使われない。）→ 削除。
- `warmUpProductCaches` のテストは `@InjectMocks`（実オブジェクト）に `when(...)` スタブをかけていたため、スタブの時点で本物のメソッドが実行され、`PageRequest.of(0, 0)` → *"Page size must not be less than one"* で壊れた。→ `@Spy` + `doReturn` に修正。
- その過程で `warmUpProductCaches` の `page++` が**囲むループのない死んだコード**であることが明らかになった。ウォームアップは各組み合わせの**最初のページだけ**を温める。テストは想像した動作ではなく実際の動作を固定するように直した。

### B-17. 明示的な `@ComponentScan` がテストスライスを壊す — chat · cart · order 🟠
```
No bean named 'entityManagerFactory' available
```
`@SpringBootApplication` はデフォルトで `TypeExcludeFilter` を付けてくれるが、**明示的な `@ComponentScan` がこれを置き換えて**しまう。その結果 `@WebMvcTest` スライスが機能せず、Kafka コンシューマ・JPA リポジトリまで全部ロードされた。
→ `excludeFilters = @Filter(type = CUSTOM, classes = TypeExcludeFilter.class)` でフィルタを復元（`com.project.common` のスキャンは維持）。3 サービスとも**修正後に再起動してランタイムで無害であることを確認**した。
→ あわせて: `ChatControllerTest` は `/chat/room` を呼んでいたが、実際のマッピングは `ChatApiController` の `/api/chat/room` で、そのコントローラがスライスになかった。両方修正。

### B-18. インフラが必要な `contextLoads()` がビルド全体を赤信号に 🟠
`@SpringBootTest contextLoads()` 6 種（catalog・eureka・gateway・order・pay・user）は、実 DB・Redis・Kafka・キーストアの環境変数があって初めて通る。インフラのない CI では**常に失敗**し、そのせいで**本物の単体テストのシグナルまで埋もれた。**
→ 理由を明記して `@Disabled` にし、コンテキストの検証は起動テスト/`ops/06-smoke-test.sh` の役目に回した。**静かに壊れたまま放置しないこと**が要点 — Testcontainers の導入が後続の課題だ。
→ おまけに **config-server の本物の起動不能バグ**が捕まった: `import: "classpath:application-common.yml"` が必須 import なのに、config-server は `common` モジュールに依存していない。`optional:` に修正。

### B-20. ビルドが `.gitignore` されたシークレットファイルに依存していた 🔴
```
ConfigDataResourceNotFoundException:
  'classpath:kafka-secrets.properties' cannot be found
```
各サービスの `application-local.yml` は `classpath:kafka-secrets.properties` を**必須で** import するが、そのファイル（`common/src/main/resources/kafka-secrets.properties`）は `.gitignore` の対象だ。つまり**新しくクローンしたリポジトリや CI では、コンテキストをロードするテストが丸ごと失敗する。**

これは起動テスト中に一時的に作っておいたファイルのせいでしばらく隠れていた — 片付けた後に再実行して初めて明らかになった。*「自分のマシンでは動くのに」* の教科書的な事例だ。
→ `catalog-service`・`chat-service` の**テストリソース**にダミー値のファイルを追加し、ビルドがシークレットファイルに依存しないようにした。本番の値は引き続きリポジトリの外から注入される。

**結果: `./gradlew build` BUILD SUCCESSFUL** — シークレットファイルなしで、クリーンな状態から。今回の作業で初めてだ。

### B-19. readiness は UP なのにサービスは使えない状態 🟡（設計上の課題）
chat-service を MongoDB なしで立ち上げたところ:
```
/actuator/health/readiness  →  {"status":"UP"}      ← k8s はトラフィックを送る
/actuator/health            →  {"status":"DOWN"}    ← 実際にはチャット機能は使えない
```
Spring Boot の readiness グループはデフォルトで `readinessState` しか見ない — **依存先が死んでも UP だ。** しかも `/actuator/health` は Mongo の接続を待って **10 秒以上応答しなかった**（タイムアウトの短いプローブならフラップを引き起こす）。

これはトレードオフなのでコードで断定しなかった: readiness に共有の依存先を入れると、**一時的な障害で全レプリカが同時に NotReady** になり、より大きな事故になる。代わりに**検知の仕組み**を入れた — `ops/06-smoke-test.sh` が readiness だけでなく**総合 health と無応答まで検査**し、「起動したが使えない」状態をデプロイ直後に捕まえる。

## 起動確認の状況

| サービス | 結果 | 備考 |
|---|---|---|
| order-service | ✅ | パーティション 8 個 · Flyway V1 |
| coupon-service | ✅ | B-13 修正後に `coupon.coupon` の作成を確認 |
| gateway-server | ✅ | 429 レートリミット · `/fallback` 503 を実証 |
| catalog-service | ✅ | `product` スキーマ 11 テーブル |
| pay-service | ✅ | 18.4s · `pay.order_outbox_event` パーティション 8 個 |
| user-service | ✅ | B-11 修正後 readiness/prometheus 200 |
| chat-service | ✅ | B-10・12・13・14 修正後に起動。MongoDB は検証環境になく health=DOWN |
| cart-service | ✅ | B-14 修正後に起動 |

## 起動後に検証したこと

order-service の起動状態で実際に確認:

| 項目 | 結果 |
|---|---|
| `/actuator/health/liveness` · `/readiness` | `{"status":"UP"}` — 今回の作業で追加したプローブが実際に動作 |
| `/actuator/prometheus` | メトリクス 154 行を公開 — 可観測性スタックがスクレイプする対象を確認 |
| `/v3/api-docs` | OpenAPI 3.1 ドキュメントを応答 — springdoc 導入の検証 |
| Flyway | `V1 partition payment outbox event` **success=true** |
| パーティション | `payment_outbox_event` 配下の**パーティション 8 個**（月 7 + DEFAULT）、複合 PK `(id, created_at)` を確認 |

つまり**今回の作業で追加したプローブ・メトリクス・OpenAPI・パーティションマイグレーションが、実際のランタイムで動作することを確認**した。

- **gateway のランタイム検証**: `/fallback` 503、レートリミッタが実際に **429 を返す**（70req 中 10 回）、Redis のバケットキーが `ip:127.0.0.1` で作成 — つまり今回の作業で配線した**サーキットブレーカー・レートリミッタと KeyResolver の NPE 修正がランタイムで動作することを確認**。
- **catalog のランタイム検証**: readiness UP、`product` スキーマのテーブル 11 個を作成、Flyway `V1`+`V2(processed_event)` の適用に成功。

## 残る限界

- **Kafka ブローカーなしで起動した。** コンシューマは接続リトライのログを残すだけで起動を妨げはしない。したがって **Saga フロー（注文→決済→在庫）の end-to-end は依然として未検証**だ。
- **MongoDB が検証環境にない。** chat-service は起動するが、チャットの保存/照会経路は未検証で health=DOWN の状態だ。
- **Elasticsearch 未検証** — 検索フラグはデフォルト値 `postgres` で、ES 経路は依然として設計・スクリプトのレベルだ（docs/07、`ops/03-enable-es-search.sh`）。
- **`@Disabled` で先送りしたコンテキストテスト 6 種**は、Testcontainers 導入までは空白として残る。
- したがって次のステップは依然として**ステージングでのフルスタック起動 + Saga 1 件の通過**だ。

## 後続: Saga・可観測性の実証で出たバグ 2 件（B-21 · B-22）

この文書の後、実際の Kafka と Prometheus/Zipkin をつないで検証する中で**さらに 2 件出た**。詳細は
[docs/16](16-saga-e2e-and-observability-verification.md) §2 にある。

- **B-21** Outbox の `created_at` が `Asia/Seoul` でハードコードされ、他のカラムと 9 時間ずれる — このカラムが**パーティションキー**なので、月の境界でイベントが誤ったパーティションに入る（pay・order・cart・user の 4 サービス）。
- **B-22** 共有設定 `application-common.yml` の `spring.application.name: common` が各サービスの名前を上書きし、**すべてのサービスが自らを `common` と報告**していた。Zipkin の分散トレースが事実上無力化された状態だった。

## この文書から得るべき教訓

1. **「コンパイルできる」「単体テストが通る」は「起動する」を意味しない。** B-1〜B-14 はすべて起動して初めて見えるバグだった。
2. **「起動した」ですら「動作する」を意味しない。** coupon はテーブルなしで（B-13）、chat は MongoDB なしで（B-19）「成功裏に」起動した。**起動ログの WARN を最後まで読まなければならない。**
3. **赤いテストを放置するとテスト全体が無意味になる。** インフラが必要なテスト 6 件が常に失敗していたせいで、その中に混ざっていた**本物の製品 NPE 4 件**（B-15）が長い間隠れていた。
4. **起動テストは 1 回きりではない。** 1 回目で 4 サービスを通した後でも、2 回目でさらに 5 件が出た。
