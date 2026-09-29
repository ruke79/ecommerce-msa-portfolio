# 改善内容 (What Changed)

**日本語** | [한국어](02-improvements.ko.md)

> 🕰️ **状態（2026-09-27）— 過去の記録。** ロードマップに回した項目のうち、DLQ・コンシューマー冪等性・分散トレーシング・TLS 検証はその後適用しました（[11](11-observability-and-kafka-reliability.md) · [12](12-dlq-rollout-idempotency-alerts.md) · [18](18-certificates.md)）。Feign サーキットブレーカーだけがまだありません。

> [`docs/01-branch-issues.md`](01-branch-issues.md) で導き出した問題を根拠とした、実際の変更一覧である。

今回の改善の原則: 当時の検証環境では **コンパイル／統合テストが不可能** だったため、
1. **動作を変えずに確実に安全な変更**（設定・ゲートウェイの配線・運用）は実際に適用し、
2. **ランタイムの回帰リスクを検証できない変更**（Kafka バッチコンシューマーの DLQ、Feign レベルのサーキットブレーカー）は **正確な実装テンプレートと併せてロードマップとして文書化**する。

こうすることで「動作するコード」だけがブランチに入り、危険な変更は根拠・方法と共にレビュー対象になる。

---

## 1. API Gateway — サーキットブレーカーの配線 + レートリミッターの有効化 🔴→✅

問題: サーキットブレーカー・レートリミッターの部品（FallbackController、Resilience4jConfig、CustomUserKeyResolver、resilience4j 設定ブロック）が **すべて存在するのに、ルートに接続されていないだけ**だった。

| ファイル | 変更 |
|---|---|
| `gateway-server/build.gradle.kts` | `spring-boot-starter-data-redis-reactive` のコメント解除（レートリミッターのバックエンド）、`micrometer-registry-prometheus` を有効化 |
| `gateway-server/.../resolver/CustomUserKeyResolver.java` | **NPE バグ修正**: `X-User-Id` がないと `null` を返していた → `user:<id>` → `ip:<X-Forwarded-For>` → ソケット IP → `anonymous` の順で **常に空でないキー**を返す |
| `gateway-server/.../application-local.yml` | ① `RequestRateLimiter` を `default-filters` に追加（Redis トークンバケット、replenish 20/burst 40） ② 各 **REST ルート**（catalog/cart/coupon/order/pay/chat-api）に `CircuitBreaker` フィルター + `fallbackUri: forward:/fallback` を接続 ③ `spring.data.redis` の接続情報を追加 |

注意点（文書化済み）:
- **WS ルート（`chat-service-ws`）にはサーキットブレーカーを付けなかった。** WebSocket は長寿命コネクションのため、リクエスト持続時間＝接続持続時間となり、slow-call として誤検知されるためである。
- レートリミッターは **Redis の可用性に結合**される。Redis ダウン時にはゲートウェイがトラフィックを処理できないため、本番では Redis の冗長化が前提となる（→ [`docs/03`](03-architecture-and-reliability.md) 障害シナリオ F-6）。

効果: 特定のサービスが落ちてもゲートウェイが **素早くフォールバック応答**を返すのでユーザー体験が維持され、急増トラフィックはユーザー／IP 単位で **シェーピング**される。（System Design Notes の *Circuit Breaker*、*Rate Limiter* を反映）

---

## 2. Feign 呼び出しタイムアウトの標準化 🔴→✅（部分）

問題: Feign 呼び出しのタイムアウトがサービスごとにまちまち／未設定 → 遅いダウンストリームがワーカースレッドを占有する **cascading failure**。

| ファイル | 変更 |
|---|---|
| `common/src/main/resources/application-common.yml` | 全サービス共通の `spring.cloud.openfeign.client.config.default`: `connectTimeout 3000ms`、`readTimeout 5000ms`、`loggerLevel basic`。⚠️ 最初は `feign.client.config` プレフィックスで入れたが、OpenFeign 4.x が読まないキーだったため **実際には適用されていなかった**（docs/37 §2.1）。プレフィックスを直し、バインディング・動作テストで固定した |

このファイルは **全サービスが import する単一の共通設定**なので、一箇所の修正で全サービスに適用される。

**Feign レベルのサーキットブレーカーは今回は有効にしなかった — 理由:**
`order-service`/`cart-service` の `FeignConfig.requestInterceptor()` は `RequestContextHolder`（ThreadLocal）から `Authorization` ヘッダーを読んで伝播する。Spring Cloud の Feign サーキットブレーカーはデフォルトで **別スレッドで呼び出しを実行**するため、この ThreadLocal が失われる → ダウンストリーム呼び出しが **認証ヘッダーなしで**送られ、認証失敗を引き起こしうる。検証できない環境でこの回帰を受け入れるわけにはいかないので、**コンテキスト伝播と併せて導入**するよう、ロードマップにコードテンプレートとして残した（[`docs/03` §ロードマップ R-1](03-architecture-and-reliability.md)）。

効果: タイムアウトだけでも **スレッド枯渇型の障害伝播という最悪のシナリオは遮断**される。

---

## 3. 可観測性（Observability）の標準化 🟠→✅（部分）

| ファイル | 変更 |
|---|---|
| `common/src/main/resources/application-common.yml` | actuator の公開に `info, metrics` を追加、**health プローブ（liveness/readiness）を有効化**、`show-details: when_authorized` |
| `gateway-server/build.gradle.kts` | Prometheus レジストリを有効化 |

効果: コンテナオーケストレーターが `/actuator/health/readiness` で **トラフィックをルーティングするタイミング**を、`/liveness` で **再起動の判断**を区別できるようになった。Prometheus のスクレイプ対象を標準化。

---

## 4. デプロイ／運用のハードニング 🟠→✅

| ファイル | 変更 |
|---|---|
| `docker-compose-core.yml` | eureka の `build.context` の **タイポ修正**（`./discovery-service` → `./eureka-server`）、誤った `Dockerfile:` キー → `dockerfile:`、healthcheck/`restart: unless-stopped`/`mem_limit`/`cpus` を追加、gateway の `depends_on: eureka(condition: service_healthy)`、gateway に `REDIS_HOST/PORT` を注入 |
| `docker-compose-services-1.yml` | user/cart/catalog/chat の 4 つに healthcheck/restart/リソース上限を追加、`Dockerfile:`→`dockerfile:`、**cart-service の eureka/config を `http`→`https` に統一** |
| `docker-compose-services-2.yml` | coupon/pay/order の 3 つに同じハードニング |

healthcheck はベースイメージ（`eclipse-temurin:17-jre-jammy`）に curl/wget がないため、**bash の `/dev/tcp` によるポート確認**で実装。

効果: ゾンビコンテナの検知、クラッシュからの自動復旧、noisy-neighbor の防止、起動順序の整合。

---

## 5. ロードマップへ移した項目（安全のため未適用、テンプレート提供）

| 項目 | 理由 | 場所 |
|---|---|---|
| Kafka コンシューマー DLQ + リトライバックオフ | 対象コンシューマーが **batch listener** → `DeadLetterPublishingRecoverer` に SSL プロデューサー（KafkaTemplate）が必要、`BatchListenerFailedException` の処理など未検証のリスク | [`docs/03` R-2](03-architecture-and-reliability.md) |
| Kafka コンシューマー冪等性（order/cart/catalog/coupon） | pay-service の `IdempotencyKeyRepository` パターンを広げる必要があるが、スキーマ／トランザクションの検証が必要 | [`docs/03` R-3](03-architecture-and-reliability.md) |
| Feign サーキットブレーカー + フォールバック | ThreadLocal による認証伝播との衝突（§2） | [`docs/03` R-1](03-architecture-and-reliability.md) |
| 分散トレーシング（OTel/Zipkin） | 依存関係／コレクターのインフラが必要 | [`docs/03` R-4](03-architecture-and-reliability.md) |
| `useInsecureTrustManager: true` の削除 | トラストストア整合性の実環境での検証が必要 | [`docs/03` R-5](03-architecture-and-reliability.md) |

---

## 検証

- 変更した 5 つの YAML すべてが `yaml.safe_load_all` のパースを通過。
- ゲートウェイの部品（FallbackController/Resilience4jConfig/KeyResolver）は既存実装を **再利用**するよう配線だけを追加 → 新規のコンパイルリスクを最小化。
- ブランチの既存コミット（chat WS ハードニング）は **回帰なく維持**。
