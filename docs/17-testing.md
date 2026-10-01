# テスト戦略 — 単体テスト + Testcontainers

**日本語** | [한국어](17-testing.ko.md)

> 🕰️ **状態（2026-09-27）— 一部古い。** テスト数 · ガード数 · CI ジョブ構成は執筆当時の値です。現在はバックエンド 1,637 · フロント 930 · B-1~69、CI は commit → integration · acceptance → release-candidate（[docs/36](36-deployment-pipeline.md)）。

> 作成日: 2026-07-28
> 背景: [docs/14](14-boot-test-findings.md)・[docs/16](16-saga-e2e-and-observability-verification.md) で
> 明らかになった27件の欠陥は、**ほぼすべてが「実際に起動してみないと」見えない種類** だった。その検証を人手ではなく
> ビルドにやらせることがこのドキュメントの目的である。

## 0. なぜこの作業が必要だったのか

以前の状態を数字で見ると問題ははっきりしている。

| 項目 | 以前 | 以後 |
|---|---|---|
| 全テスト | 47件 | **126件** |
| 無効化（@Disabled） | 6件 | **0件** |
| テストがあるモジュール | 8/11 | **11/11** |
| 実インフラでの起動検証 | ❌ 手動（`ops/06-smoke-test.sh`） | ✅ **ビルドが自動で実行** |
| Saga の **全区間**（注文→決済→在庫） | ❌ 手動（docs/16） | ✅ **サービスコンテナ3種で自動検証** |
| チャットの **全区間**（STOMP→Kafka→保存→ブロードキャスト） | ❌ なし | ✅ **WebSocket クライアントで自動検証** |
| バックアップからの復旧 | ❌ なし | ✅ **PITR 復元訓練が CI の週次スケジュールで実行**（ops/09） |
| CI | ❌ なし | ✅ **プッシュ/PR ごとに実行** |

特に **無効化6件** が問題だった。`@SpringBootTest contextLoads()` が実 DB・Redis・キーストアを要求するため
インフラのない場所では常に失敗し、その赤信号が常時点灯していたせいで **本物の単体テストのシグナルまで埋もれた。**
実際その中には製品の NPE 4件（docs/14 B-15）が混ざっていたのに、長いあいだ誰も気づかなかった。

## 1. 二系統に分ける

Docker がない場所でも単体テストは回るべきなので、統合テストは `containers` タグで分離した。

```bash
./gradlew test                    # 単体テストのみ — Docker 不要、~1分
./gradlew test -PwithContainers   # 統合テストまで — Docker 必要、~4分
```

タグが付いたテストはデフォルトの実行から自動的に除外される（`build.gradle` の `excludeTags 'containers'`）。

## 2. 回帰テスト — 直したバグごとに1つずつ

今回の作業で直した欠陥は **再発防止テストで固定** した。各テストのコメントに元の障害が書いてある。

| テスト | 固定する欠陥 |
|---|---|
| `ConfigurationGuardrailTest` (common) | **設定の欠陥15種** — §3 参照 |
| `MessageMapperTest` (chat) | B-24 roomId/nickname の引数の取り違え、createdAt のパースが無防備 |
| `ProductMapperNullSafetyTest` (catalog) | B-15 nullable な shipping・関連コレクションの NPE |
| `PageJacksonModuleTest` (catalog) | B-27 `Page` のキャッシュがデシリアライズ不可（2回目の検索から 500） |
| `*OutboxEventCreatedAtTest` (order·pay·cart·user) | B-21 Outbox の `created_at` のタイムゾーンのハードコード（パーティションキー！） |
| `CustomUserKeyResolverTest` (gateway) | レートリミッターのキーの NPE |
| `ChatStompIntegrationTest` (chat) | B-24 保存漏れ + STOMP 認証・なりすまし防御（§6.1） |

## 3. 設定ガードレール — 最も高くついたバグが出た場所

このリポジトリで **最も高くついた欠陥はコードではなく設定** にあった。レビューではほとんど弾けないので、
`common/src/test/.../ConfigurationGuardrailTest` がビルドごとに検査する:

| 検査 | 防ぐ障害 |
|---|---|
| プロファイル専用ファイルでの `spring.profiles.active` 禁止 | B-1 — 9つのサービスが起動直後に停止 |
| 共有設定での `spring.application.name` 禁止 | B-22 — すべてのサービスが自分を `common` と報告し、分散トレーシングが無力化 |
| `es-settings.json` の重複した `settings` ラッパー禁止 | B-26 — ES インデックスの作成自体が失敗 |
| アナライザー名の一致 | ドキュメントが参照するアナライザーの未定義を防止 |
| PostgreSQL + MySQLDialect の組み合わせ禁止 | B-12 — すべてのテーブル作成が失敗（WARN としてしか残らない） |
| 環境変数名のタイプミス（`STOE`）禁止 | B-10 — placeholder の解決失敗で起動不可 |
| 設定への開発者 PC の絶対パス禁止 | B-28 — `file:E:/github/...` が7つのサービスに埋め込まれていた（docs/18 §1） |
| Kafka SSL ストア形式のハードコード禁止 | B-29 — `.p12` を `"JKS"` と宣言、mTLS を有効にした瞬間に全サービスが起動不可 |
| Redis パスワードの空値デフォルト化禁止 | B-30 — 認証が静かに無効になる。ゲートウェイ・order・cart で **3回** 再発した |
| Kafka SSL プロパティは共有設定の1か所でのみ | 注入経路が分岐して環境変数が効かなくなること |
| サービス設定が要求する環境変数をマニフェストがすべて提供 | B-31 — `AMAZON_ACCESS_KEY` がどのマニフェストにもなく、2つのサービスが placeholder の解決失敗で起動不可だった |
| `データソース × プールサイズ × レプリカ ≤ max_connections` | B-32 — 算術的にあふれるプール設定。負荷がかかって初めて表面化する |
| `ddl-auto` は `validate`/`none` のみ + ベースラインマイグレーションの存在 | B-33 — Hibernate がスキーマを所有すると変更履歴がなく、本番 DB にレビューなしの DDL が流れ、**制約を削除しない**（docs/20） |
| スケール不可能なサービスは replicas 1 + HPA 除外 | B-34 — chat はインメモリ STOMP ブローカー、eureka は standalone。増やすと **静かに** 壊れる: Pod は Running なのにユーザーだけがメッセージを受け取れない（docs/21 §2） |

> これらのガードレールが **実際に機能することを確認した。** `spring.application.name: common` を戻して入れると
> 該当テストがすぐに失敗した。失敗しないテストはテストではない。

## 4. Testcontainers 統合テスト

| テスト | コンテナ | 確認するもの |
|---|---|---|
| `CatalogServiceApplicationTests` | PG, Redis | B-8 空の DB でのスキーマ作成、B-9 冪等性台帳の存在 |
| `OrderServiceApplicationTests` | PG, Redis | 起動 + **Outbox パーティションの作成**（docs/09 P0） |
| `PayServiceApplicationTests` | PG, Redis | 起動 + `pay` スキーマのテーブル作成 |
| `CartServiceApplicationTests` | PG, Redis | 起動 + `cart` スキーマ |
| `CouponServiceApplicationTests` | PG, Redis | 起動 + B-13 `coupon` スキーマのテーブル（静かな失敗の防止） |
| `UserServiceApplicationTests` | PG, Redis | 起動 + **B-11 アクチュエーターが 401 ではない** + 業務 API は 401 を維持 |
| `GatewayServerApplicationTests` | Redis | 起動 + ルートのロード + KeyResolver ビーンの存在 |
| `EurekaServerApplicationTests` | （なし） | 単独モードでの起動 |
| `ChatServiceIntegrationTest` | **MongoDB**, PG, Redis | 保存・参照、カーソルページネーション、B-24 回帰2種 |
| `EsProductSearchServiceIntegrationTest` | **Elasticsearch**, PG, Redis | インデックス → 検索 → 結果 |
| `ProductUpdateSagaIntegrationTest` | **Kafka**, PG, Redis | §4.1 — Saga の在庫減算・冪等性・DLQ |
| `SagaEndToEndTest` (e2e-tests) | **サービス3種 + Kafka, PG, Redis** | §4.3 — Saga の全区間 |
| `ChatStompIntegrationTest` | **Kafka, MongoDB, PG, Redis** | §6.1 — STOMP 認証からブロードキャスト・保存まで |

### 4.1 Saga の在庫減算を実際の Kafka で検証する
docs/16 §1 で手で確認していたことをビルドが代わりに行う。Saga の全区間は3つのサービスのコンテキストが
同時に必要で1つのテストにまとめにくいが、**お金が絡む区間はここだ** — 在庫が二重に減算されれば損失である。

| テスト | 確認するもの |
|---|---|
| 単件の減算 | メッセージ1件が在庫を注文数量ぶん正確に減らす |
| **再配信の冪等性** | 同じ eventId をさらに3回送っても在庫はそのままだ。台帳には1件だけ残る |
| 複数項目 | 項目が複数あればすべて反映される（以前は最初の項目だけ処理して return していた） |
| 異なる eventId | 冪等性が正常な処理まで妨げはしない |
| 存在しない商品 | 他の在庫に触れない |
| 連続注文 | 累積で減算される |
| **DLQ 隔離** | 壊れたメッセージの後ろにある正常なメッセージが処理される（コンシューマーが詰まらない） |

> ⚠️ 非同期コンシューマーのテストの落とし穴を2つ実際に踏み、どちらもコードに残しておいた:
> - **パーティション割り当て前の発行**: コンシューマーがグループに参加する前に送ったメッセージは見えないことがある。
>   `ContainerTestUtils.waitForAssignment` で待つ。これを抜くと断続的に失敗する。
> - **テスト間の干渉**: コンシューマーが非同期なので、前のテストのメッセージが後のテストの初期化後に処理されることがある。
>   テストごとに **専用の商品** を使い、在庫を初期化しない。poison message のテストはリトライのバックオフで
>   同じパーティションを遅延させるので、`@Order` で **最後** に置く。

### B-11 は特に指摘しておく価値がある
`user-service` のアクチュエーターが 401 を返すと、**k8s は Pod を永遠に NotReady のままにし、Prometheus はそのサービスだけスクレイプできない。**
ところがこれは単体テストでは捕まえにくい — 実際に起動して HTTP で叩いてみる必要がある。そこでこのテストは
`RANDOM_PORT` で起動し、`@AutoConfigureObservability` を有効にする。

> `@SpringBootTest` は **デフォルトでメトリクスのエクスポートを無効にする。** すると `/actuator/prometheus` がそもそも
> 登録されず、「セキュリティのせいで 401」なのか「エンドポイントがないせい」なのか区別できない。本番と同じ条件で
> 検証するにはこのアノテーションが必要だ — これ自体がテストを書いていて学んだ落とし穴である。

### 4.3 Saga の全区間をサービスコンテナで

`ops/06-smoke-test.sh` が「手動確認項目」として残していたフロー全体をビルドが検証する。

**3つのサービスを1つの JVM に載せることはできない** — 各モジュールの `application-local.yml` がクラスパス上で衝突する。
そこで事前にビルドした Boot jar を最小イメージに載せて **コンテナとして起動する**。デプロイされる成果物そのものを
検証するという副次効果もある（`e2e-tests` モジュール）。

```
order トピック ─▶ pay-service ─▶ pay.payment + Outbox ─(スケジューラ)─▶ payment トピック
                                                                        │
                order-service ◀─────────────────────────────────────────┘
                    ├─▶ orders.order_status: NOT_PROCESSED → PAID
                    └─▶ product-update トピック ─▶ catalog-service ─▶ 在庫減算
```

段階ごとに DB を直接確認する: 決済の作成 → Outbox `COMPLETED` → 注文 `PAID` → 在庫減算 →
冪等台帳に `order-9100` を記録。最後に同じ決済を再送しても在庫がそれ以上減らないかを見る。

> このテストを作る過程で **デプロイ成果物の実際の欠陥** が1つ明らかになった（§4.4）。

### 4.4 コンテナイメージが起動できなかった問題

サービスをコンテナで起動した途端、すべてがこのエラーで落ちた:

```
Config data resource 'class path resource [kafka-secrets.properties]' does not exist
```

各サービスがそのファイルを **必須で** import しているが、パスワードを含むため `.gitignore` の対象である。
つまり **新たにクローンしたリポジトリも、ビルドしたコンテナイメージも起動できなかった。** 以前テストのクラスパスには
ダミーファイルを入れておいたが（docs/14 B-20）、肝心の **デプロイされる jar はそのまま塞がっていた。**

→ import を `optional:` に変え、シークレットではない Kafka のデフォルト値を `application-common.yml` に置いた。
本番ではシークレットファイルや `KAFKA_*` 環境変数がこの値を上書きする。

## 4.2 CI

`.github/workflows/build.yml` がプッシュ/PR ごとに3つのジョブを、週1回復元訓練を回す:

| ジョブ | 内容 |
|---|---|
| `unit` | `./gradlew build` — Docker 不要、速いフィードバック |
| `integration` | `./gradlew test -PwithContainers` — 実際のインフラを起動（unit 通過後のみ） |
| `restore-drill` | `ops/09-restore-drill.sh` — **週1回** の PITR 復元訓練（docs/15 §0.1） |
| `frontend` | `npm ci && npm run build` |

テストレポートは失敗時もアーティファクトとしてアップロードされる。

> このリポジトリのテストスイートは一時期 **.gitignore されたシークレットファイルに依存** しており、新たにクローンすると通過できない
> 状態だったが、誰も知らなかった — 自動で回してみる場所がなかったからだ（docs/14 B-20）。

## 5. 制約されたネットワークで実行する

このリポジトリを検証した環境では、Docker Hub レジストリの CDN がポリシー上ブロックされていた。そこでイメージの取得元を
変えられるようにしておいた:

```bash
export TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX=mirror.gcr.io/
export DOCKER_HOST=unix:///var/run/docker.sock
./gradlew test -PwithContainers
```

値を与えなければ通常どおり Docker Hub を使う。`build.gradle` が `DOCKER_HOST`・`DOCKER_API_VERSION` なども
テスト JVM に渡す。

> ⚠️ Spring Boot 3.4.1 が固定する Testcontainers 1.20.4 は docker-java が API 1.32 で接続するため、
> 最新の Docker Engine（29.x、最小 1.40 を要求）では `client version 1.32 is too old` で **コンテナをまったく起動できない。**
> `testcontainers-bom:1.21.4` に上げて回避した。

## 6. 依然として残る空白

正直に書いておく。

| 項目 | 状態 |
|---|---|
| **韓国語の形態素解析（nori）** | ❌ — プラグインをどこからも入手できない。Maven Central に `analysis-kuromoji` はあるが `analysis-nori` はなく、Lucene の `lucene-analysis-nori` には Elasticsearch プラグインのラッパークラスがないため、**プラグインを自作** する必要がある。それはこのリポジトリが抱え込む保守負担なのでやらなかった。nori がインストールされた環境での再確認が必要だ。 クラスタの ES は今は initContainer でインストールする(docs/19 §5.2) — 初回起動ログで確認する。 |
| **コンシューマー冪等性の展開** | ⚠️ — 12個中5個。残りは性質上再実行が無害か（cart-empty）、すでに状態チェックで保護されている（order Saga）。一括適用は儀式（ceremony）にすぎないのでやらなかった。 |
| **チャットの WebSocket/STOMP 経路** | ✅ 解決 — `ChatStompIntegrationTest` が全区間を通る（下記 §6.1）。 |
| **Grafana ダッシュボードのレンダリング** | ❌ — クエリがデータを返すところまでは確認したが（docs/16）、パネルが実際に描画されるかは確認できなかった（バイナリのダウンロードがブロックされていた）。 |

### 6.1 チャットの STOMP 全区間 — ついに実際に通す

これまでチャットの検証はすべてリポジトリを直接呼び出す方式だった。つまり **ユーザーが実際に通る道は
一度も実行されたことがなかった。** その区間には JWT 認証インターセプター、セッション属性ベースの本人確認、Kafka の往復、
MongoDB への保存がすべて入っている — このリポジトリで最も多くの欠陥が出た種類のコードだ。

`ChatStompIntegrationTest` は MongoDB・PostgreSQL・Redis・Kafka のコンテナ上で、実際の WebSocket
クライアント（SockJS）で接続して4つを確認する:

| 検査 | 防ぐ事故 |
|---|---|
| トークンなしの CONNECT を拒否 | 誰でも任意のルームを購読し、他人の会話をリアルタイムで読むこと |
| 別のキーで署名されたトークンを拒否 | トークンの偽造 |
| SEND → Kafka → ブロードキャスト → **MongoDB 保存** | docs/16 B-24 の回帰 — 配信だけされて履歴が空の状態 |
| ボディの email を無視してトークンの subject を使用 | 他人の名義でメッセージを送ること |

最後の項目が特に重要だ。`StompController` がクライアントの送った `email` をセッションの検証済みの値で
上書きするが、この防御は **STOMP フレームを実際に送ってみて初めて** 検証される。単体テストでは届かない。

## 7. 教訓

1. **無効化したテストは、ないテストより悪い。** 常に赤信号なら人々は全体を無視するようになり、その下に本物のバグが隠れる。
2. **設定はコードより危険なのに、レビューはコードしか見ない。** ガードレールテストがその差を埋める。
3. **テストも検証しなければならない。** バグを戻して入れて失敗するか確認していないガードレールは飾りだ。
4. **テスト環境は本番と同じ条件でなければならない。** `@AutoConfigureObservability` を入れ忘れると「通過したが何も検証していない」テストになる。
