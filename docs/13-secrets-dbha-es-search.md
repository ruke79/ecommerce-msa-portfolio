# シークレットの整理（G1） · PostgreSQL レプリケーション／HA（G17） · 検索の ES 移行（G19）

**日本語** | [한국어](13-secrets-dbha-es-search.ko.md)

> 🕰️ **状態（2026-11-14）— 2026-07-27 の記録。** 漏えいした DB パスワードは、2026-09-25 に「どこでも通用しない」ことを確認してクローズしました（[docs/30 §6](30-git-history-secret-exposure.md)）。§3 の ES 検索は今、起動設定 `search.engine=es`（ES がこのデプロイにあるか）＋ 機能フラグ `catalog.search-engine` の比率で選びます（[docs/74](74-feature-flags.md)）。DB → ES は商品の保存ごとのリアルタイム索引（`ProductIndexService`）です。本番（OCI）は ES がないので PostgreSQL 検索のままです。

> 作成日: 2026-07-27 · 最終点検で残った最後の 🔴 の塊 3 件の適用記録
> **実行可能なスクリプト**: このドキュメントの運用手順は [`ops/`](../ops/README.md) にガード付きスクリプトとして用意されている。

## 1. G1 — コミットされた DB パスワードの除去 ＋ ローテーション・ランブック

**リポジトリ側の対処（適用済み）**: コネクタ JSON 4 つ（`*-outbox-connector-{postgresql,mysql}.json`）の平文 `database.password` を、Kafka Connect の **ファイル config provider** 参照に置き換えた:

```json
"database.password": "${file:/etc/kafka-connect/secrets/connect-secrets.properties:db-password}"
```

Connect ワーカーで provider を有効化する必要がある（worker properties）:
```properties
config.providers=file
config.providers.file.class=org.apache.kafka.common.config.provider.FileConfigProvider
```
そしてワーカーホストの `/etc/kafka-connect/secrets/connect-secrets.properties` に:

```properties
db-password=<새 비밀번호>
db-host=<DB 호스트>
```

**ホスト名も一緒に外した**（2026-07-28）。コネクタ JSON には特定のデプロイ環境の公開ホスト名がそのまま
残っていた — パスワードほどではないにせよ、リポジトリを読む誰に対しても「このアドレスの 5435 ポートに DB が
ある」と教えてしまう情報だ。偵察のコストをただで下げてやる理由はない。

**⚠️ これだけでは終わらない — ローテーション・ランブック（リポジトリ外の対処、必須）**:
1. 露出したパスワードは **git 履歴と GitHub ですでに公開** されている。ファイルの修正は再発防止にすぎず、唯一の実質的な解決は **パスワードの変更** だ。
2. PostgreSQL: `ALTER ROLE product WITH PASSWORD '<new>';`（MySQL を併用している場合も同様）。
3. 新しい値をデプロイチャネルに反映: 各サービスの `LOCAL_DB_PASSWORD` env / `k8s/20-secret.yaml` / Connect の secrets ファイル → サービス・コネクタを再起動。
4. リポジトリからホスト名は除去したが（上記参照）、**すでに公開された履歴は元に戻せない。** そのホストがパブリックネットワークで 5435 ポートを開けていないか、ファイアウォール／セキュリティグループを必ず点検すること — DB ポートがインターネットに開いていれば、パスワードのローテーションは時間稼ぎにしかならない。
5. （任意）git 履歴の破棄（BFG/`git filter-repo`）はフォーク・クローンには伝播しないため、ローテーションの代わりにはならない — ローテーションが本命で、履歴の破棄は付加的な措置だ。

## 2. G17 — PostgreSQL ストリーミングレプリケーション（SPOF 除去の第 1 段階）

**検証環境の PostgreSQL 16.13 で全手順を実際に検証したうえで**、そのまま compose にエンコードした:

検証ログ（実行結果）: ① プライマリ起動 → ② `pg_basebackup -R` でスタンバイを作成 → ③ プライマリの INSERT がスタンバイで参照できる（`pg_is_in_recovery()=t`） → ④ `pg_ctl promote` で昇格した後、書き込みに成功（`pg_is_in_recovery()=f`）。

**追加の成果物**:
| ファイル | 内容 |
|---|---|
| `docker-compose-postgres-ha.yml` | primary（5435、既存サービスの設定とポート互換） ＋ replica（5436、hot standby の read-only）。`wal_level=replica`、`wal_keep_size=512MB`、healthcheck |
| `postgres-ha/init-primary.sh` | replicator アカウントの作成 ＋ pg_hba でレプリケーションを許可 |
| `postgres-ha/replica-entrypoint.sh` | PGDATA が空なら `pg_basebackup -R` の後にスタンバイを起動 |
| `k8s/80-postgres-cnpg.yaml.example` | k8s の完成形 — CloudNativePG 3 ノード（自動 failover、rw/ro サービス）。CRD が必要なので `.example` として置き、一括 apply から除外 |

**運用ノート**:
- compose 版は **手動 failover**（`pg_ctl promote` ＋ サービスの接続先切り替え）だ。自動 failover は Patroni または CNPG。
- 読み取りを分散する際、**read-your-writes の経路は必ずプライマリへ**（docs/09 §5.2、F-13）。
- 次の段階（docs/09 P2 の完成）: 書き込みの中核（orderdb/paydb）と読み取りの中核（productdb）のインスタンス分離。

## 3. G19 — 検索の Elasticsearch 移行（docs/07 R-C1/R-C2 の実装）

### 直したバグ 2 件（再索引パイプライン — docs/06 CAT-9/CAT-10）
1. **alias のロールオーバーが常に失敗**: Writer は `<ts>-products` に索引するのに、タスクレットは接尾辞なしの名前を確認 → 「インデックスなし」例外で永続的に失敗。接尾辞を揃え、**初回ロールオーバー時の alias 不在ガード** を追加。
2. **マッピング／アナライザーの死文化**: `save()` の動的マッピング自動生成により、nori/kuromoji・Nested の宣言がすべて無視されていた。ProductWriter が索引の前に `createSettings()/createMapping()` で **明示的なインデックスを先に作成する** よう修正 — 韓／英／日のアナライザーが実際に適用される。

### ES 検索経路（デフォルト OFF、フラグで opt-in）
- **`EsProductSearchService`**（新規、`@ConditionalOnProperty(search.engine=es)`）: `product-alias` を対象に multi_match（name/brand/description × 韓／英／日のマルチフィールド） → 関連度順の productId を最大 1000 件。
- **`SearchService`**: 検索語があり ES の Bean が存在すれば、LIKE の代わりに **`productId IN (esIds)`** で絞り込む — 「ES で関連度検索、Postgres で詳細／ファセットをハイドレーション」パターン。レスポンス形式（SearchResultDto）・ファセット・キャッシュはすべて従来のまま。
- **フォールバック**: ES の例外時は警告ログを出したうえで、従来の LIKE 経路へ自動フォールバック。ES が「マッチなし」なら空の結果。
- **デフォルトが postgres である理由（正直な限界）**: 当時の検証環境には ES インスタンスがなく、統合検証ができなかった。コードはコンパイル・単体テスト（ES 使用／フォールバックの 2 ケースを含む、SearchService 6/6）まで確認済みで、有効化は本番で以下の手順の後に `search.engine=es` で行う:
  1. 再索引を実行（`POST /api/products/sync`） → 今回の修正でマッピングが適用され、alias のロールオーバーが実際に完了する
  2. `GET product-alias/_search` で索引／アナライザーを確認
  3. `search.engine=es` をデプロイ → 検索品質／レイテンシを比較 → 異常があればフラグを戻すだけで即座に復旧

> 🕰️ 今: `search.engine=es` は「ES の Bean · 索引がある」ことだけを決め、検索ごとにどのエンジンへ行くかは機能フラグ `catalog.search-engine`（ConfigMap `feature-flags` — `k8s/15-feature-flags.yaml` ＋ `ops/28 --config-only`、再起動なしで比率で · `SearchEngineSelector`、docs/74）が決める。戻すのもその比率を 0 に。

### ES を立てる手順（実習 · デモ — 本番に ES はない）

`k8s/90-dev-infra.yaml` に ES の四つ（Namespace · PVC 5Gi · Deployment · Service 9200）がある。**まるごと apply しない** — 他のインフラまでリポジトリの原本に巻き戻す（docs/22 §4）。ラベルで ES だけを選ぶ（ガード **B-120** が四つにラベルがあるかを守る）:

```bash
# 0) ノードの準備 — ES は vm.max_map_count ≥ 262144 がないと起動中に死ぬ（bootstrap check）
sudo sysctl -w vm.max_map_count=262144
echo 'vm.max_map_count=262144' | sudo tee /etc/sysctl.d/99-elasticsearch.conf   # 再起動後も

# 1) ES の四つだけ
kubectl apply -f k8s/90-dev-infra.yaml -l ecommerce.io/infra=elasticsearch

# 2) 起動まで — 初回はプラグイン（nori · kuromoji）を取得するので initContainer が 1 〜 2 分
kubectl -n elasticsearch get pod -w
kubectl -n elasticsearch logs deploy/elasticsearch -c install-analysis-plugins
kubectl -n elasticsearch exec deploy/elasticsearch -- curl -s localhost:9200/_cluster/health
```

- メモリ: ヒープ 1g · コンテナ上限 2Gi（`ES_JAVA_OPTS` · `resources.limits`）。ノードにその余裕が必要 — なければ `OOMKilled` を繰り返す。
- ディスク: PVC 5Gi（local-path = ノードのディスク）。プラグインも PVC の中（`.es-plugins`）に置く — 再起動にネットワークが要らない。
- `strategy: Recreate` · `replicas: 1` — 二つの Pod が同じ data 経路を開くと二番目が `node.lock` で死ぬ。
- ネットワークポリシー: `k8s/95` の `allow-ecommerce-only`（elasticsearch ネームスペース）が **ecommerce ネームスペースからだけ**受ける。⚠️ 複数ノードのクラスタでは kube-router が `namespaceSelector` で他ノードの Pod を塞ぐ— catalog と ES が別ノードならそこから見る。
- catalog はすでに `SPRING_ELASTICSEARCH_URIS=http://elasticsearch.elasticsearch.svc.cluster.local:9200`（k8s/41）を持っている。状態（`/actuator/health`）に ES を入れるならその Deployment に `MANAGEMENT_HEALTH_ELASTICSEARCH_ENABLED=true`（既定は無効 — ガード B-116: ES のない本番で health が常に DOWN だった）。

⚠️ **再索引は一度に一つだけ** — catalog のレプリカが 2 個以上だと二度目の要求が別のポッドへ行き、ジョブが二つ走ることがあった（2026-11-20 の実習で 21 時間、[docs/62 §23](62-engineering-casebook.md)）。今は Redis のロック（`catalog:product-reindex:lock`）なのでポッド数に依らない — 二度目は 409、Redis に届かなければ 503。ポッドがロックを持ったまま死んだら `redis-cli DEL catalog:product-reindex:lock`。

💡 **ジョブが終わる前でも検索は立てられる。** 検索が読むのは別名 `product-alias` 一つだけで（他の索引はバッチが書くが読むコードがない）、別名の切り替えは**最後の段階の後**だ。商品索引が埋まったら（親の数 = 商品数）手で付けてもよい:

```bash
ES="kubectl -n elasticsearch exec deploy/elasticsearch -c elasticsearch -- curl -s"
$ES "localhost:9200/_cat/indices/products-*-products?v"      # docs.count は親 + 入れ子
$ES -XPOST -H 'Content-Type: application/json' localhost:9200/_aliases \
  -d '{"actions":[{"add":{"index":"products-<タイムスタンプ>-products","alias":"product-alias"}}]}'
$ES localhost:9200/product-alias/_count                       # これは親だけ — 商品数と同じはず
```

⚠️ `_cat/indices` の `docs.count` に驚かないこと — `ProductDocument` の `skus` · `subcategories` が `Nested` なので**入れ子のオブジェクトが Lucene 文書として別に数えられる**。実習の実測: 商品 1,000 万 → `docs.count` **37,003,969** = 1,000 万（親）＋ 17,003,969（SKU）＋ 1,000 万（下位分類）、`_count` はちょうど 1,000 万。重複ではない。

その後の再索引 · alias · 件数 · アナライザー · フラグは `ops/03-enable-es-search.sh` が一度に行う:

```bash
# 再索引は管理者の書き込み — ゲートウェイ経由で管理者トークンで呼ぶ（catalog への直接呼び出しは 401:
# AdminWriteInterceptor ＋ internal-auth.identity-mode=enforce — 身元ヘッダーはゲートウェイが mTLS で送ったものだけを信頼）
GATEWAY=https://shop.lab ADMIN_TOKEN=<管理者のアクセストークン> ES_URL=http://localhost:9200 INSECURE=1 \
  ./ops/03-enable-es-search.sh
```

再索引は 202 ですぐ返り、バッチは後ろで回る — スクリプトは alias `product-alias` ができるまで待ち、**索引中の件数**も出す（既定 30 分）。実測: 実習デモの**商品 1,000 万**が毎秒約 5.7 万件 · 約 3 分（`data-node` 6 vCPU · ES ヒープ 3g）。時間切れでも件数が増えていれば「遅いだけ」と言う — そのとき `sync` を再度呼ぶと 409（重複実行の防止）なので `TIMEOUT` を伸ばして待つ。管理者アカウントがなければ `./ops/30-admin-account.sh promote <メール>`。

切り替えは**二段階**で、どちらもリポジトリを直して `ops/28 --config-only` で上げる（`kubectl patch` は禁止）:

1. `SEARCH_ENGINE=es`（`k8s/10-configmap.yaml` またはその環境のオーバーレイ）＋ `--restart catalog-service` — 「ES の Bean · 索引がある」ことだけを決める。
2. 機能フラグ `catalog.search-engine` の比率（`k8s/15-feature-flags.yaml`）— 実際に検索を ES へ送るのはこちら。再起動なしで 1 分前後。

戻すのは 2 の比率を 0 に（最も速い）→ そのあと 1 を `postgres` に。

**いま決めた値（2026-11-20）**: 実習 · デモは `SEARCH_ENGINE=es`· 比率 **es 100%**（ベース `k8s/15-feature-flags.yaml` は本番基準の **0%**）。本番（OCI）はベースの `postgres` のままで、**比率が変わっても影響はない** — `SearchEngineSelector` は ES の Bean が無ければ（`esDeployed=false`）フラグを**評価すらしない**（トレースに 'es' と嘘が残らないように）。適用は `./ops/28-deploy.sh demo --config-only --restart catalog-service`（①）· `--config-only`（②、再起動なし）。最初は 10% にし、下の実測で 100% に上げた — 一日見て `catalog_search_*` · 応答のレイテンシ · ES 側のエラーを比べてから上げる。

#### 実測 — なぜデモは 100% か（2026-11-20、商品 1,000 万）

同じ検索語で ES に直接問い、同じ検索語を API で呼んで**キャッシュキーからエンジンを事後に確認した**（`search_cache::SimpleKey [SearchParamsRequest(...), POSTGRES]`）。

| 検索語 | ES 直接 | API（PostgreSQL 経路） |
|---|---|---|
| Parmesan | 5ms · 1 万件以上 | 200 · **3.97 秒** |
| Sandals | 2ms · 1 万件以上 | 200 · **3.57 秒** |
| Kettle | 2ms · 1 万件以上 | 200 · **1.60 秒** |
| Blower | 3ms · 1 万件以上 | 200 · **1.61 秒** |
| Patio | 2ms · 1 万件以上 | 200 · **1.61 秒** |
| 가방 · nike | 1〜11ms · **0 件**（デモの名前は英語） | **503** · 5.0〜5.4 秒（クエリ上限） |

**100% に上げた後（同日、デモ `26e0ea1533ce`）** — 同じ五語が ES へ行った（キャッシュキーに `ES` ができたことで確認）:

| 検索語 | PostgreSQL 経路（前） | **ES 経路（後）** | ES のクエリ自体 |
|---|---|---|---|
| Parmesan | 3.97 秒 | **2.81 秒** | 5ms |
| Sandals | 3.57 秒 | **2.67 秒** | 2ms |
| Kettle | 1.60 秒 | **0.82 秒** | 2ms |
| Blower | 1.61 秒 | **0.76 秒** | 3ms |
| Patio | 1.61 秒 | **0.70 秒** | 2ms |
| 가방 · nike（0 件） | **503** · 5.0 〜 5.4 秒 | **200** · 0.052 · 0.063 秒 | 1 〜 11ms |

**端から端では約 2 倍**だ — ES のクエリは 2 〜 5ms なのに応答が 0.70 〜 2.81 秒なのは、残りが **ES の外**にあるということだ: 関連度で選んだ id で詳細を読む `productId IN (esIds)`（docs/07 R-C1 の「ES で関連度、Postgres でハイドレーション」）＋ 最初のページのフィルターメニュー。次の改善はそこだ（ページサイズ分だけ読む · メニューを ES の集計で）。いまの利得は速さよりも **5 秒上限の 503 が消えたこと**で、ES に 0 件の検索語も空の結果を速く返す（PostgreSQL は無いものを探して最後まで走査していた）。

十五の検索語のうち ES へルーティングされたのは一つだった（比率 10% と合う）— つまり**比率を上げなければ ES があっても使われない**。

⚠️ **エンジンは推測せずキャッシュキーで確認する。** 速かったから ES とは限らない（2026-11-20: `운동화` は 0.68 秒だったがキャッシュキーは `POSTGRES` — その条件が PG で安く済んだだけ）。
```bash
kubectl -n redis exec deploy/redis -- sh -c '
  redis-cli -a "$REDIS_PASSWORD" --no-auth-warning --scan --pattern "search_cache*"' \
  | grep -oE 'search=[^,]*|, (ES|POSTGRES)\]' | paste - -
```
⚠️ `grep -oE '(ES|POSTGRES)\]'` のように **`]` をすぐ後ろに置くと `POSTGRES` の末尾二文字（`ES]`）に当たって全部 ES に見える。** 前に `, ` を付けるか `grep -oE 'ES|POSTGRES'`（最長一致が勝つ）で数える。
⚠️ 日本語 · 韓国語の検索語を `curl` で送るときは `-G --data-urlencode` — エンコードしないと **400** になる（ブラウザは常にエンコードする）。



### 残っているもの（後続）
- 関連度の順序が Postgres のハイドレーションで保たれない（現在のソートは従来ロジック） — 順序の保持が必要なら、esIds の順序に基づく再ソートを追加。
- category/subcategory など他のインデックスの明示的マッピング、`search_after` ページネーション、DB↔ES の増分同期（Outbox ベース、docs/07 R-C3）。

> 🕰️ 今（2026-11-14）: 増分同期は商品の保存ごとの単件索引（`ProductIndexService` — 失敗しても保存は成功、次の sync で復旧）までが基本ブランチにある。関連度順の保持 · `search_after` · アウトボックスによる索引キュー（R-C3）はまだ基本ブランチにない — 検索全体を ES に移すロードマップ（R-C1 〜 R-C4）はES ロードマップのブランチ にだけある（そのブランチの docs/07 §5 — このブランチの [docs/07](07-catalog-search-improvements.md) は R-C1 〜 R-C6 の設計案まで、マージするかは判断待ち）。
