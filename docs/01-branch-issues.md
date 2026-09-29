# 改善着手前の問題分析

**日本語** | [한국어](01-branch-issues.ko.md)

> 🕰️ **状態（2026-09-27）— 過去の記録。** 改善に着手する前の診断です。ここに書かれた問題の大半は [02](02-improvements.md) · [11](11-observability-and-kafka-reliability.md) · [12](12-dlq-rollout-idempotency-alerts.md) · [18](18-certificates.md) で修正しました。現在の状態は [docs/54](54-improvements-vs-practice.md)。

> 作成日: 2026-07-24
> 基準ブランチ: `amaon_clone_msa_postgresql_version` (default)
> 参考: [liquidslr/system-design-notes](https://github.com/liquidslr/system-design-notes) (Alex Xu, *System Design Interview* Vol.1-2) — Rate Limiting / Circuit Breaker, Message Queue, Caching, Payment System, Chat System, Monitoring & Alerting の章

このドキュメントは **改善作業以前の状態** を記録する。ここで導き出した問題点を根拠に、`docs/02-...` 以降の改善を進めた。

---

## 0. 要約

現在のブランチは default ブランチに対して **コミット 1 件** だけが追加されている。

```
bd76010 Harden chat-service WebSocket/STOMP for production
```

このコミットは **chat-service の WebSocket/STOMP のセキュリティ・整合性ハードニング** であり、品質は高い（下記 §1）。しかしシステム全体の観点では **chat の 1 サービスだけに限定** されており、残り 9 モジュールが共通して抱える **回復力（resilience）・メッセージング信頼性・可観測性・運用** の問題には手を付けていない。「画期的な改善」のためには、ブランチの範囲をシステム全体に広げる必要がある。

| 領域 | 深刻度 | 核心的な問題 |
|---|---|---|
| サービス間呼び出しの回復力 | 🔴 High | Feign にサーキットブレーカー/フォールバックがない → 障害の伝播（cascading failure） |
| API Gateway の防御 | 🔴 High | サーキットブレーカーフィルターがコメントアウト、レートリミッターが未完成 |
| Kafka メッセージングの信頼性 | 🔴 High | コンシューマーのエラーハンドラー/DLQ/リトライがない、冪等性の欠如 |
| 可観測性（Observability） | 🟠 Medium | 分散トレーシング・相関 ID がない、メトリクス公開が不完全 |
| デプロイ/運用 | 🟠 Medium | healthcheck/restart/リソース制限が皆無、TLS の無力化 |
| データ/拡張性 | 🟠 Medium | 状態ストアが単一インスタンス、設定の不一致 |
| テスト/ドキュメント | 🟡 Low | テスト 17 個、`docs/` が存在しない |

---

## 1. ブランチがすでにうまくやっている点（維持対象）

コミット `bd76010` は実際の脆弱性を的確に修正した:

- **STOMP の身元偽装の遮断**: CONNECT 時に JWT を 1 回検証し、検証済みの subject を STOMP セッションに保存。以降の SUBSCRIBE/SEND ではクライアントが送る `email` ヘッダーを信頼しない（`StompHandler.java`）。
- **認証バイパスの修正**: `TokenHandler.getUid()`（署名未検証のデコード）でユーザーを識別していた経路を `resolveVerifiedSubject()` に置き換え → 偽造 JWT による任意ユーザーのなりすましが不可能に。
- **CORS の縮小**: `setAllowedOriginPatterns("*")` → フロントのオリジンに制限。
- **トークンのログ漏えいの除去**、**ParticipantChatRoom の重複 insert の冪等化**。
- **Gateway ルートの分離**: `/chat-service/api/**` に JWT フィルターを適用し、WS ハンドシェイクのパスのみ例外。
- フロント: 誤った購読解除のバグ、STOMP クライアント再生成の問題、デバッグログでのトークン露出を修正。

→ これらの変更は **リグレッションさせずにそのまま維持** する。問題は「間違っている」ことではなく「範囲が狭い」ことである。

---

## 2. サービス間呼び出しの回復力の欠如 🔴

**根拠ファイル**
- `order-service/.../client/CartServiceClient.java`, `CatalogServiceClient.java`, `UserServiceClient.java`
- `cart-service/.../client/CatalogServiceClient.java`, `UserServiceClient.java`
- `coupon-service/.../client/UserServiceClient.java`
- 7 サービスすべてが `spring-cloud-starter-openfeign` **+** `spring-cloud-starter-circuitbreaker-resilience4j` を classpath に保有

**問題**
1. **サーキットブレーカー未適用**: すべての `@FeignClient` インターフェースに `fallback`/`fallbackFactory` がなく、`spring.cloud.openfeign.circuitbreaker.enabled` も有効になっていない。resilience4j が classpath にあるにもかかわらず、Feign 呼び出しをまったくラップしていない。
2. **フォールバックがない**: 依存サービスが落ちると例外がそのまま上位へ伝播し、ユーザーには 5xx。
3. **タイムアウトの不統一**: `order-service` は Feign `connect/readTimeout: 5000` を設けているが、他の呼び出し元（cart, coupon, user）には明示的なタイムアウトの標準がない。タイムアウトがないと遅い応答が Tomcat のワーカースレッドを占有 → **スレッドプール枯渇 → 障害の伝播**。

**影響シナリオ**: `catalog-service` が遅くなる → `order-service`/`cart-service` の Feign 呼び出しが待機 → ワーカースレッド枯渇 → 正常だった注文/カート機能まで巻き込まれて障害。（System Design Notes の *Circuit Breaker* パターンがまさにこの状況を扱っている。）

---

## 3. API Gateway の防御ロジックが未完成 🔴

**根拠**: `gateway-server/src/main/resources/application-local.yml`, `gateway-server/.../fallback/*`, `resolver/CustomUserKeyResolver.java`, `build.gradle.kts`

1. **サーキットブレーカーフィルターが無効**: `default-filters` の `CircuitBreaker` 項目が **コメントアウト** されており、どのルートにも CircuitBreaker フィルターが付いていない。肝心の `FallbackController`、`Resilience4jConfig`、`resilience4j:` 設定ブロックは **完成しているのに接続されていないだけ**。
2. **レートリミッターが未完成（スキャフォールディングのみ存在）**:
   - `build.gradle.kts` で `spring-boot-starter-data-redis-reactive` が **コメントアウト** されており、`RequestRateLimiter` が動作できない。
   - `CustomUserKeyResolver` が `X-User-Id` ヘッダーをそのまま返すが、ヘッダーがないと **`null` を返す → リアクティブパイプラインで NPE**。
   - どのルートにも `RequestRateLimiter` フィルターがない。
   - → ゲートウェイに **流入トラフィックを制御する手段がまったくない**。（System Design Notes の *Rate Limiter* の章が未反映）
3. **TLS 信頼の無力化**: `spring.cloud.gateway.httpclient.ssl.useInsecureTrustManager: true` → ゲートウェイがアップストリームの証明書を検証しない。README が掲げる mTLS の信頼体系を自ら壊している。

---

## 4. Kafka メッセージングの信頼性の欠如 🔴

**根拠**: 6 サービスの `KafkaConsumerConfig.java`（order/pay/cart/catalog/coupon/chat/user）、`order-service/.../listener/PaymentResponseMessageListener.java`

1. **コンシューマーのエラーハンドラー/DLQ がない**: `order-service/.../config/KafkaConsumerConfig.java` に `DefaultErrorHandler`、`DeadLetterPublishingRecoverer`、リトライ `BackOff` の設定がまったくない。デシリアライズ失敗や処理例外が発生すると **オフセットがコミットされず同じレコードを無限にリトライ** → **poison message がパーティション全体をブロック**。
2. **冪等性（idempotency）の欠如**: `pay-service` にのみ `IdempotencyKeyRepository`（Redis）があり、order/cart/catalog/coupon のコンシューマーには重複消費の防御がない。Kafka は at-least-once なので、リバランス/再処理時に **在庫の二重引き当て、クーポンの二重処理** の危険。
3. **Outbox relay の堅牢性不足**: `PaymentOutboxScheduler` は `@Scheduled` ポーリングで `STARTED` イベントを発行するが、
   - 発行失敗時のリトライ/バックオフ/最大試行回数のポリシーがコードに現れておらず、
   - スケジューラーが `@Transactional` の 1 トランザクションでバッチ全体を処理するため **1 件の失敗がバッチ全体に影響**、
   - `COMPLETED` イベントの整理（アーカイブ/削除）ポリシーがなく、outbox テーブルが無限に増加。

---

## 5. 可観測性（Observability）の不足 🟠

- **分散トレーシングがない**: Micrometer Tracing/Zipkin/OTel の依存関係がなく、サービス境界をまたぐ **相関 ID（correlation/trace id）** の伝播がない。Kafka・Feign をまたぐ Saga の流れで障害を追跡するのが非常に難しい。
- **メトリクス公開が不完全**: ゲートウェイは `prometheus` エンドポイントを公開しているが、個別サービスの actuator 公開は標準化されていない（サービスごとに `management.endpoints` の設定が異なる/存在しない）。
- README に monitoring/tracing/alerting への言及がまったくない → 運用の可視性の設計が欠如。

---

## 6. デプロイ/運用の信頼性 🟠

**根拠**: `docker-compose-core.yml`, `docker-compose-services-1.yml`, `docker-compose-services-2.yml`

1. **healthcheck が皆無**: どのコンテナにも `healthcheck` がない → コンテナが「起動しているがリクエストを受けられない」ゾンビ状態をオーケストレーターが検知できない。
2. **restart ポリシーがない**: `restart: unless-stopped` などがなく、クラッシュ後に自動復旧できない。
3. **リソース制限がない**: `mem_limit`/`cpus`/`deploy.resources` がなく、1 サービスのメモリリークが **ホスト全体を侵食**（noisy neighbor）。
4. **ビルドコンテキストの誤記**: `docker-compose-core.yml` の eureka サービスの `build.context: ./discovery-service` — 実際のディレクトリは `./eureka-server`。ビルド失敗を引き起こす。
5. **依存関係の順序が脆弱**: `depends_on` だけでは「コンテナの起動」しか保証されず、「サービスの準備完了」は保証されない（healthcheck condition 未使用）。
6. **ネットワーク分離**: `msa-core-network` / `services-1-network` / `services-2-network` に分かれており、サービス相互の通信経路が compose だけでは成立しない（外部ネットワークの前提がドキュメント化されていない）。
7. **シークレット/設定の欠陥**: compose env の `SERVER_SSL_KEY_STORE_PASSWORD=` などが **空の値**、`SPRING_CONFIG_IMPORT`/`EUREKA...DEFAULTZONE` のホストが空。`cart-service` だけが eureka/config を `http://` で、残りは `https://` で指定している **プロトコルの不一致**。

---

## 7. データ・拡張性 🟠

- 状態ストア（PostgreSQL/MongoDB/Redis/Elasticsearch/Kafka）が **単一インスタンス** 前提 → レプリケーション/failover/パーティショニングの欠如。（System Design Notes の *Replication/Partitioning* が未反映）
- キャッシュ（catalog Redis）の **無効化・TTL・スタンピード（cache stampede）防御ポリシー** がドキュメント化されていない。

---

## 8. テスト・ドキュメント 🟡

- リポジトリ全体のテストファイルは **17 個** — Saga/Outbox/サーキットブレーカーなど中核となる信頼性パスに対する統合テストが乏しい。
- `docs/` ディレクトリが存在しない。アーキテクチャ/障害対応のドキュメントがない（本改善で作成）。

---

## 9. 改善の優先順位（次のステップで反映）

1. **(P0) サービス間呼び出しの回復力**: Feign のサーキットブレーカー+タイムアウトの標準化 → `docs/02`, `03`。
2. **(P0) Gateway の防御**: サーキットブレーカーのルート接続 + Redis レートリミッターの有効化 + KeyResolver の堅牢化。
3. **(P1) Kafka の信頼性**: DLQ + リトライのバックオフ + コンシューマーの冪等性ガイド。
4. **(P1) 運用のハードニング**: compose の healthcheck/restart/リソース制限、ビルドコンテキストの誤記修正。
5. **(P2) 可観測性**: actuator の標準公開 + 分散トレーシング導入のロードマップ。

各項目の実際の変更内容は [`docs/02-improvements.md`](02-improvements.md)、システム全体の設計・障害対応は [`docs/03-architecture-and-reliability.md`](03-architecture-and-reliability.md) を参照。
