# 74. 機能フラグ — 第 1 段階: 検索エンジン(search.engine)を実行中に比率で切り替える

**日本語** | [한국어](74-feature-flags.ko.md)

> **状態(2026-10-06)** — 第 1 段階の実装完了、本番デプロイ待ち。本番(OCI)には Elasticsearch が無いため、デプロイしても動作は変わらない
> (フラグを評価しない、§5)。比率を上げるには先に ES を立ち上げる必要がある。
> きっかけ: *Observability Engineering, 2nd Edition* の「デプロイ(deploy)とリリース(release)を切り離し、有効側と無効側を観測で比較せよ」。

---

## 0. 概要

| | 以前 | 現在 |
|---|---|---|
| 何が決めるか | `search.engine` — 起動時に一度 | `search.engine=es` は「ES が**あるか**」、フラグ `catalog.search-engine` は「あるなら**この検索に使うか**」 |
| 変更方法 | ConfigMap 修正 + `ops/28 --config-only --restart catalog-service` | `k8s/15-feature-flags.yaml` の比重を修正 → `ops/28 --config-only`(**再起動なし**、1 分前後) |
| 変更範囲 | すべて一度に | 検索語の 0 〜 100% |
| どちらで動いたか | 分からない | サーバースパンの `feature_flag.result.variant`、メトリクス `feature_flag.evaluations`、span_metrics の変形別 RED |
| フラグ定義が無い・誤っている | — | コードのデフォルト値 = フラグ導入前の動作(ES があれば ES) |

---

## 1. なぜ search.engine からか

[docs/13](13-secrets-dbha-es-search.md) は ES 検索の有効化を「`search.engine=es` をデプロイ → 検索品質・レイテンシを比較 → 異常があればフラグを戻す」と書いた。しかしその「フラグ」は起動設定だった。

- **比較対象が無い。** 切り替えた瞬間にすべての検索が ES へ行く。同じ時間帯の二群で比べられず、切り替え前後の時間帯を比べることになる(その間にトラフィックが違う)。
- **戻すのに再起動が要る。** ConfigMap にはハッシュ接尾辞が無く Pod が再起動しない — `--restart` が必要(ops/28 のコメント)。シングルノードで catalog の再起動は 1 〜 2 分。
- **リスクがあり、頻繁に変えたいスイッチ**である — 決済・セキュリティのスイッチと違い、検索エンジンは誤っても結果の質が下がるだけで、ES 障害時の LIKE フォールバックも既にある。最初のフラグに適している。

---

## 2. 構成

```
k8s/15-feature-flags.yaml (ConfigMap feature-flags, flags.json)
        │  ディレクトリマウント /app/flags  ← subPath ではない(B-76)
        ▼
FileFlagProvider (common)  — 評価時に 5 秒経っていればファイルを再確認、誤っていれば直前の定義を維持
        │  OpenFeature SDK (隔離された OpenFeatureAPI、自動構成)
        ▼
FeatureFlagObservabilityHook — 現在のスパンに feature_flag.* タグ、feature_flag.evaluations メトリクス
        │
SearchController ── SearchEngineSelector.select(params) ──▶ SearchService.searchProducts(params, engine)
                     (キャッシュの外で評価)                     @Cacheable キー = (params, engine)
```

### 2-1. なぜ OpenFeature + ファイルプロバイダーか

| 選択肢 | 判断 |
|---|---|
| **OpenFeature SDK + 自作ファイルプロバイダー**(採用) | 依存は SDK ひとつ(+slf4j)。サービスコードは標準 API(`Client.getStringValue`)だけを使う — 後でプロバイダーだけ flagd に替えればよい。新しい Pod・ネットワークポリシーなし |
| flagd プロバイダー(`dev.openfeature.contrib.providers:flagd`) | gRPC・protobuf を引き込む。このリポジトリは gRPC 1.84・protobuf 3.25.9 を合わせて固定している(docs/66) — 噛み合わせのリスクに比べて第 1 段階で得るものが少ない |
| Redis + 管理 API | 管理画面・認可・監査記録を新たに作る必要がある。変更の記録が git に残らない |

定義ファイルは**flagd 形式そのまま**である。受け付けるのは `state`・`variants`・`defaultVariant`・`metadata`、targeting は `fractional` だけ —
その他の規則は**読み込み時にファイル全体を拒否**する。知らない規則を黙って無視すると「10% だけ」が 100% で動く。(比率割り当てのハッシュは flagd と同じであることを保証しない — flagd に移ると誰がどちらに入るかは一度変わる。)

### 2-2. 何を基準に分けるか — 検索語

検索 `GET /catalog-service/api/search` は公開ルートなので、ゲートウェイが `User-Id` を入れない。そのため:

- **リクエストごとに無作為** — 同じ検索の 1 ページ目は ES、2 ページ目は LIKE に分かれ、無限スクロールの一覧がずれる。不採用。
- **ユーザー** — 大半がログインしていない。ゲートウェイに「任意認証」を足すのはセキュリティ経路に触れる。第 1 段階の範囲外。
- **正規化した検索語**(採用) — 小文字・前後の空白・連続する空白だけを揃える。同じ検索語は常に同じエンジンなので、ページ・キャッシュがずれない。

代償: 「ユーザーの 10%」ではなく**「検索語の 10%」**である。人気の検索語ひとつがどちらに入るかで実際のトラフィック比率は 10% と違いうる — 実際の比率はメトリクスで見る(§4)。
検索語はトレース・メトリクス・ログのどこにも残さない(タグの値は変形名だけ)。

### 2-3. キャッシュキーにエンジンを入れた

`searchProducts` は `@Cacheable("search_cache")`(60 分)である。エンジンをキーに入れないと、比率を変えた後最大 60 分間**反対側のエンジンの結果**が返り、スパンの変形タグと実際の結果が食い違う。
そこでエンジンをキャッシュの**外**(コントローラー)で決めて引数で渡す — キーは `(params, engine)`。キャッシュヒットでも評価は毎リクエスト行われるので、サーバースパンには常に変形が残る。
(デプロイ直後、旧形式のキーの項目は読まれず TTL で消える。)

### 2-4. デフォルト値の規則

| 状況 | 結果 | 理由 |
|---|---|---|
| ES の無いデプロイ(`SEARCH_ENGINE=postgres`、OCI) | POSTGRES、**評価しない** | 行き先が無い。評価して "es" と記録されるとトレースが嘘をつく |
| 検索語が無い | POSTGRES、評価しない | エンジンが結果に影響しない — キャッシュキーもひとつにまとまる |
| 定義ファイルが無い・フラグが無い | ES(コードのデフォルト値) | フラグ導入前の動作 |
| 定義ファイルが誤っている | 直前の定義 | 誤字ひとつですべてのフラグがデフォルトに落ちれば、それが障害である |
| `state: DISABLED` | コードのデフォルト値(ES)、理由 DISABLED | フラグを切る = フラグが無かった頃に戻る |

---

## 3. 比率の上げ方(ES がある環境)

1. ES を立ち上げ `SEARCH_ENGINE=es` でデプロイ → `POST /api/products/sync` で全件再インデックス(エイリアス `product-alias`)。このとき比重は `["es", 0]` なので検索はまだすべて LIKE。インデックスだけが埋まる。
2. `k8s/15-feature-flags.yaml` を `["es", 5], ["postgres", 95]` に直してコミット → `./ops/28-deploy.sh <環境> --config-only`。
3. 1 分前後後、`feature_flag_evaluations_total{key="catalog.search-engine"}` の変形別増加率で実際の比率を見る。
4. 変形別のレイテンシ・エラーを比較する(§4)。問題なければ 5 → 25 → 50 → 100。**前方の変形(es)の比重を増やす限り、既に es だった検索語は es のまま**(テストで固定)。
5. 異常があれば `["es", 0]` に戻して `--config-only`。再起動なし。
6. 100% で安定したら**フラグを削除する** — 定義ファイルの項目と `SearchEngineSelector` を外し、既定経路を ES に。期限(`metadata.expires`)を過ぎるとガード B-76 がビルドを赤くする。

🔴 `kubectl patch`・`kubectl edit` で直さない。次の `--config-only` が git の値に戻す。

---

## 4. どう見るか

**Jaeger** — サービス `catalog-service`、タグ `feature_flag.result.variant=es`(または `postgres`)。スパンには `feature_flag.key`・`feature_flag.result.reason`(SPLIT・DEFAULT・ERROR …)・`feature_flag.provider.name`、失敗時は `feature_flag.error.code` が付く。
ひとつのリクエストで複数のフラグを評価すると、セマンティック規約の名前は最後のものが勝つため `feature_flag.variant.<キー>` も併せて残す。

**Prometheus** — Collector の span_metrics に `feature_flag.result.variant` 次元を足した(値は変形名だけ → 時系列に上限あり)。テールサンプリングの**前**に計算するので、標本ではなく全量である。

```promql
# 変形別 p95 レイテンシ — 検索のサーバースパン(span_name は Jaeger で確認する)
histogram_quantile(0.95, sum by (le, feature_flag_result_variant) (
  rate(traces_span_metrics_duration_milliseconds_bucket{service_name="catalog-service", span_name=~".*search.*"}[5m])))

# 変形別エラー率
sum by (feature_flag_result_variant) (rate(traces_span_metrics_calls_total{service_name="catalog-service", span_name=~".*search.*", status_code="STATUS_CODE_ERROR"}[5m]))
  / sum by (feature_flag_result_variant) (rate(traces_span_metrics_calls_total{service_name="catalog-service", span_name=~".*search.*"}[5m]))

# 実際に何 % がどちらへ行ったか — サービス自身のメトリクス(actuator/prometheus)
sum by (variant, reason) (rate(feature_flag_evaluations_total{key="catalog.search-engine"}[5m]))
```

ES 経路の失敗は LIKE にフォールバックする(エラーにならない)。そのため「es 側のエラー率」だけを見ても ES 障害は見えない — フォールバックの警告ログ(`ES 검색 실패 — Postgres LIKE 경로로 폴백`)も併せて見る。

---

## 5. 本番(OCI)では

`k8s-overlays/oci` は ES を立ち上げず `SEARCH_ENGINE=postgres` である(2 OCPU・シングルノード)。そのため今回のデプロイ後も:

- `SearchEngineSelector` は ES の Bean が無いことを見て**評価しない** → 検索は従来どおり LIKE、スパンにフラグのタグなし。
- ConfigMap `feature-flags` は作られ catalog にマウントされる。仕組みは入っていて、ES を立ち上げる日に §3 を行えばよい。

つまり今回の変更の本番での効果は「仕組みの設置」である。比率の切り替えを実際に試すには ES を動かすリソース(ヒープ 1GB 以上)が先 — ノードをもう 1 台置くのが候補である。

---

## 6. フラグにしないもの

- **セキュリティ・決済のスイッチ** — `internal-auth.mode`、`auth.breached-password.enabled`、決済プロバイダーの有効化など。実行中に切れてはならず、変更者と時点が記録される必要がある。設定(ConfigMap・再起動)のままにする。
- **スキーマ変更** — フラグで隠せない。expand → contract の 2 リリース([docs/55](55-zero-downtime-migrations.md)、[docs/73](73-order-address-expand-contract.md))はそのまま。
- **REACT_APP_*** — ビルド時にだけ入る(B-51)。フロントのフラグは次の段階(§8)。

---

## 7. テスト・ガード

| テスト | 何を防ぐか |
|---|---|
| `FeatureFlagTest`(common, 11) | 比率割り当てが対象ごとに固定・比重どおり・比重を上げても前の対象を維持 / 未知の targeting を拒否 / ファイル再読み込み・誤ったファイルなら直前の定義 / スパンタグ・メトリクス、**対象キーは残らない** / 自動構成 |
| `SearchEngineSelectorTest`(catalog, 6) | 正規化した検索語で分かれる / 0%・100% / ES の無いデプロイ・検索語なしは評価しない / ファイルが無ければ ES / **キャッシュキーにエンジン** |
| `SearchServiceUnitTest` +1 | ES の Bean があってもエンジンが POSTGRES なら ES を呼ばない |
| **B-76**(`ConfigurationGuardrailTest`) | 定義ファイルがサービスの形式どおり読める・owner・期限(過ぎたら赤)・コードの `…FLAG` キー ↔ 定義ファイルの双方向・catalog がディレクトリマウント(subPath でない)・`FEATURE_FLAGS_FILE` がその中・ベース kustomization に含まれる |

変異確認(元に戻すと失敗するか)12 件: エンジン無視・キャッシュキーからエンジン除去・ES の無いデプロイで評価・未知の targeting を許可・スパンタグ除去・誤ったファイルで定義を空に・再読み込みしない・subPath・期限切れ・kustomization 漏れ・キーの誤字・targeting 名の誤字 — すべて検出された。

---

## 8. 次の段階(提案)

1. **ES を立ち上げた環境で実際に切り替え** — §3。結果(レイテンシ・0 件率・クリック)をこの文書に書く。
2. **2 つ目のフラグ** — 候補: `order.stock-hold.enabled`(在庫ホールド、ユーザー単位 — 注文はログイン経路なので `User-Id` で分けられる)、`paypal.verification.mode`。
3. **flagd へ移行** — 利用するサービスが複数になったら。プロバイダーだけ替え、定義ファイルはそのまま(比率割り当ては一度変わる)。
4. **フロントのフラグ** — OFREP(OpenFeature Remote Evaluation Protocol)をゲートウェイの後ろに置く方式。REACT_APP_* のビルド変数では行わない。

---

## 9. デプロイ

- **順序: [docs/72](72-model-refactor-deploy-runbook.md)(バージョン `b7c5053a45e8`)を先にデプロイし、安定した後にこれを出す。** ひとつのデプロイに混ぜると、V18(ROLLBACK-UNSAFE)で自動ロールバックが無効になったデプロイに変更がもうひとつ載る — 問題が起きたときどちらかを切り分けにくい。バージョンはこの文書が入ったコミット以降のパイプライン緑の SHA。
- イメージ: **全サービス**(common が変わった — 自動構成がひとつ増える。catalog 以外は `Client` Bean ができるだけで使わない) — 通常の `ops/28-deploy.sh <環境> <バージョン>`。
- マニフェスト: ConfigMap `feature-flags`(新規ファイル)、catalog Deployment にボリューム・環境変数。破壊的変更なし — 自動ロールバック可能。
- Collector: `observability/otel-collector.yml` の次元追加は Collector の再起動時に反映される。
- デプロイ後の確認: `kubectl -n ecommerce exec deploy/catalog-service -- cat /app/flags/flags.json`、catalog のログの `기능 플래그 파일 /app/flags/flags.json — 플래그 1개`。
