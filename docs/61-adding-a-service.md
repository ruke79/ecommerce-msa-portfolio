# 61. 新しいサービス追加チェックリスト

**日本語** | [한국어](61-adding-a-service.ko.md)

新しいバックエンドサービス（例: `review-service`）をこのリポジトリに加えるときに **登録すべき場所のすべて** である。最も最近追加された
`delivery-service` がリポジトリのどこに登録されているかを全数調査して書き写した — 1 つでも抜けると、たいてい **静かに** おかしくなる
（イメージが作られない・デプロイから漏れる・サービス間呼び出しが 403・ログが集まらない）。各行の「抜けると」がその症状である。

> 基準となるサービスを 1 つ選んでコピーして始めるのが最も速い — DB・Kafka があるなら `delivery-service`、
> なければ `saga-projection`（DB なし）が近い。下の表のすべての場所で基準サービスの名前を grep すればよい:
>
> ```bash
> grep -rln "delivery-service" --exclude-dir={node_modules,build,.git,frontend,docs} . | grep -v "^./delivery-service/"
> ```

---

## 1. コード

| # | やること | どこ | 抜けると |
|---|---|---|---|
| 1-1 | モジュール登録 | `settings.gradle` の `include('…')` | ビルド・テストに含まれない |
| 1-2 | 依存関係 — `implementation project(':common')`、Boot スターター。**バージョンを書かない**（Boot BOM が決める） | `<서비스>/build.gradle` | バージョンを固定すると Boot を上げても追従しない — Tomcat 10.1.34 の固定がセキュリティパッチを妨げていた（1f1031e） |
| 1-3 | 設定 — `application.yml`・`application-local.yml`（ポート、`spring.config.import` で `application-common.yml`、SSL keystore、DB、Kafka） | `<서비스>/src/main/resources/` | 起動失敗 |
| 1-4 | スキーマは Flyway — `db/migration/V1__…sql`、`ddl-auto: validate` | 同じ場所 | 元に戻せない変更は `-- ROLLBACK-UNSAFE:` を宣言（B-54）、広げるだけなら `-- ROLLBACK-SAFE:` |
| 1-5 | 身元はゲートウェイが検証した `User-Id` ヘッダーのみ — 本文の id・email は信じない | コントローラー | 他人のデータへのアクセス（IDOR） |
| 1-6 | 人に見せるエラーは `ClientMessage.of(e)`・`StateConflictException` — `IllegalStateException` のメッセージは運用者向け | コントローラー・サービス | 内部メッセージの露出 |
| 1-7 | ユーザーに見える文言の日本語 | `src/main/resources/i18n/client-text.ja.txt` | `ClientTextCoverageTest` 失敗 |
| 1-8 | イベント発行は Outbox（common）— トランザクション内で行を書き、発行器が送る | サービス | DB はコミットされたのにイベントがない（またはその逆） |
| 1-9 | 🔴 **実際の DB で動く JPA テスト** を 1 つ以上 — リクエストごとにトランザクションを別々に開き、次のリクエストで読んでみる | `src/test`（H2: `CartPersistenceJpaTest` 参照） | モックリポジトリのテストは「保存されない」・「読み取り失敗（デフォルトコンストラクターなし）」を捕まえられない — どちらも本番で初めて露見した |
| 1-10 | エンティティに `@NoArgsConstructor(access = PROTECTED)` | すべての `@Entity` | 書き込みはできて読み取りがすべて失敗（B-69） |
| 1-11 | ブラウザへ出ていく TSID（`Long` 18〜19 桁）は **文字列** で | レスポンス DTO（`@JsonSerialize(using = ToStringSerializer.class)`） | JS が末尾の桁を丸める — 存在しない id でリクエスト（レビューのいいね 1→0） |

## 2. ゲートウェイ・サービス間呼び出し

| # | やること | どこ | 抜けると |
|---|---|---|---|
| 2-1 | 外部ルート — `id`・`uri: lb://<서비스>`・`Path=/<서비스>/**`、認証要否、サーキットブレーカーの `fallbackUri` | `gateway-server/src/main/resources/application-local.yml` の `spring.cloud.gateway.server.webflux.routes`（旧 `spring.cloud.gateway.routes` ではない — `GatewayPropertiesLocationTest`） | 404。id が重なると後のものが静かに消える（B-62） |
| 2-2 | 公開してはいけないパス（actuator・api-docs・`/internal/**`）がルートに漏れないように | 同じ場所 | 内部 API の外部露出 |
| 2-3 | 内部 API は `InternalCallGuard.allowed(...)`・管理者は `isAdmin(User-Role)` | コントローラー | 誰でも呼び出せる |
| 2-4 | mTLS の許可主体にサービス名を追加 | `k8s/10-configmap.yaml` の `INTERNAL_AUTH_ALLOWED_SUBJECTS` | このサービスが呼ぶ内部呼び出しがすべて 403（enforce） |
| 2-5 | このサービスが呼ぶ・呼ばれる内部パスの許可ルール | 同じファイルの `INTERNAL_AUTH_PATH_RULES`（`경로=서비스\|서비스;…`） | Feign が呼ぶ内部パスがルールになければ B-67 失敗、ルールが間違っていれば 403 |
| 2-6 | Feign クライアントは `url: ${feign.<대상>-url}` とタイムアウト（`FeignTimeoutBindingTest` 参照） | `client/*Client.java`、yml | 無限待機 |

## 3. Kubernetes

| # | やること | どこ | 抜けると |
|---|---|---|---|
| 3-1 | Deployment・Service — 基準サービスのファイルをコピー（`48-delivery-service.yaml`）。番号は次の空き番号 | `k8s/4x-<서비스>.yaml` | — |
| 3-2 | そのファイルの必須要素: `EUREKA_INSTANCE_HOSTNAME`（証明書の SAN と同じサービス DNS）、`JAVA_TOOL_OPTIONS`（truststore + `/app/identity/keystore.p12`）、`SERVER_SSL_CLIENT_AUTH: want`、`tls-keystore`・`tls-id` ボリューム、Reloader アノテーション `secret.reloader.stakater.com/reload: tls-id-<서비스>`、プローブ、リソース | 同じファイル | mTLS の身元がない（呼び出し元が `localhost` と記録される）、証明書更新後に再起動されない（B-68） |
| 3-3 | リソース一覧に追加 | `k8s/kustomization.yaml` | デプロイから漏れる |
| 3-4 | サービスごとの設定値（スイッチ・アドレス） | `k8s/10-configmap.yaml` — サービスマニフェストの `env` に直書きしない | オーバーレイで変更できない |
| 3-5 | シークレット | `k8s/20-secret.example.yaml`（`REPLACE_ME`）+ 本番の `k8s/20-secret.yaml`（gitignore） | 起動失敗。🔒 実際の値はコミットしない |
| 3-6 | NetworkPolicy — 現在のポリシーは `default-deny-ingress` + 同一ネームスペース許可なので、同じネームスペースの新サービスには別途の項目は不要。**別のネームスペースから入ってくる必要があるなら**（例: 可観測性スタック）許可ルールを追加する。egress ルールはまだない | `k8s/95-network-policy.yaml` | 別のネームスペースからの接続が遮断される |
| 3-7 | サービス身元の証明書 | `k8s/96-service-identities.yaml` に `Certificate id-<서비스>` | B-68 失敗、enforce で 403 |
| 3-8 | オートスケール（水平スケールが安全な場合のみ） | `k8s/70-autoscaling.yaml` | — |
| 3-9 | オーバーレイのイメージ名一覧・リソースパッチ | `k8s-overlays/{external,oci,dev,prod}/kustomization.yaml`、本番の `k8s-overlays/local/`（gitignore — 手動で） | イメージタグが変わらない（古いイメージで起動する） |

## 4. データ・メッセージ

| # | やること | どこ | 抜けると |
|---|---|---|---|
| 4-1 | DB 作成（外部 Postgres） | `ops/25-provision-external-postgres.sh` の `DATABASES=(…)` | 起動失敗 |
| 4-2 | マイグレーション点検の対象 | `ops/32-db-migration.sh` のサービス一覧 | `pending`・`drift` から漏れる |
| 4-3 | Kafka トピック — 名前・パーティション・DLT、コンシューマーグループ。Avro ならスキーマとシリアライズ設定 | サービスの yml、`AvroWiringGuardrailTest` | メッセージが届かない、またはデシリアライズ失敗 |
| 4-4 | Outbox を月次パーティションで作ったら、そのテーブルをパーティションジョブに登録 — `outbox.partitions.tables: <スキーマ>.<テーブル>` | サービスの `application-local.yml` | V1 が作った最後の月より後は、すべての行が DEFAULT に入る（B-70 が捕まえる） |

## 5. 運用スクリプト

| # | やること | どこ | 抜けると |
|---|---|---|---|
| 5-1 | イメージビルド一覧 | `ops/19-build-images.sh` の `SERVICES` | イメージが作られない |
| 5-2 | 部分デプロイの順序・Java サービス一覧 | `ops/33-deploy-some.sh` の `ORDER`・`JAVA_SERVICES` | `--changed deployed` が選べない |
| 5-3 | 初回デプロイ一覧 | `ops/13-k3s-deploy.sh` の `SERVICES` | 最初に上げるときに漏れる（notification・delivery が実際に漏れていた） |
| 5-4 | mTLS 身元の発行・更新・点検 | `ops/38-issue-service-identities.sh`・`ops/39-cert-manager.sh`・`ops/37-check-mtls.sh` の `SERVICES` | 証明書がない |

## 6. CI/CD・可観測性

| # | やること | どこ | 抜けると |
|---|---|---|---|
| 6-1 | リリース候補イメージ一覧 | `.github/workflows/build.yml` の `release-candidate` → `services=` | 候補マニフェストから漏れる |
| 6-2 | GHCR パッケージ権限 — 初回発行後にパッケージの Settings → Manage Actions access → このリポジトリに **Write** | GitHub | CI のイメージ発行が `permission_denied: read_package` |
| 6-3 | ログ収集（ローカルの可観測性スタック） | `docker-compose-observability.yml` のログボリューム | Loki にログがない |
| 6-4 | メトリクス・アラート — `/actuator/prometheus` のスクレイプ、必要なら `observability/alert-rules.yml` | 可観測性の設定 | 障害に気づかない |
| 6-5 | 分散トレーシング — Deployment に `HOST_IP`（downward API）の次に `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://$(HOST_IP):4318/v1/traces`（docs/63） | `k8s/4x-<서비스>.yaml` の `env` | トレースが Pod 自身（localhost）に行って静かに消える（B-59） |
| 6-6 | ドキュメント — 設計（なぜ・何を信じ何を信じないか）、`docs/README.md` の目次 | `docs/` | — |

---

## 7. 完了したかの確認

```bash
./gradlew :<서비스>:test :common:test          # ガード（B-54・B-67・B-68・B-69 …）が登録漏れを捕まえる
./gradlew build -x test                           # 全モジュールのコンパイル
./ops/19-build-images.sh --only <서비스> --dry-run 2>/dev/null || true
./ops/28-deploy.sh local <버전> --dry-run          # 何が変わるか
# デプロイ後
./ops/37-check-mtls.sh --callers                  # このサービスが自分の名前（CN）で呼んでいるか — localhost なら 3-2 が抜けている
./ops/37-check-mtls.sh                            # パスルール違反 0 件
kubectl -n ecommerce logs deploy/<서비스> | grep -E "WARN|ERROR" | tail
```

> ⚠️ マニフェスト（`k8s/`）を変えたなら `ops/33` だけでは反映されない（イメージだけを変える）。全体を `ops/28` で上げるか、
> `./ops/28-deploy.sh local --config-only` で仕様だけを適用する（docs/36 §6, f29a42e）。
