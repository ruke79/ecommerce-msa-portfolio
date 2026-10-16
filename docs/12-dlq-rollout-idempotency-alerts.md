# DLQ の全面展開 · コンシューマ冪等性（G8） · アラートルール · ダッシュボードのコード化

**日本語** | [한국어](12-dlq-rollout-idempotency-alerts.ko.md)

> 🕰️ **状態（2026-09-27）— 過去の記録。** アラートは現在 14 種類で、受信チャネルも接続済みです（[docs/22](22-alerting-and-backup-automation.md)）。order は `user-created` の購読をやめました（[docs/48](48-user-replication-debt.md)）。

> 作成日: 2026-07-27 · docs/11 §4 で「残り」として明記した項目の適用記録

## 1. Kafka DLQ の全面展開 — 全サービス完了

docs/11 のリファレンス実装（catalog/order）を残り 5 つのサービスへ展開した。これで **このシステムのすべての Kafka コンシューマ** が、リトライ＋DLT 隔離、デシリアライズ防御、トレース可観測性を備えた。

| サービス | DLT 設定 | 配線したファクトリ | インデックスループに切り替えたリスナー |
|---|---|---|---|
| cart | `config/KafkaDltConfig` | 4（record 1 を含む） | CartRollback/CartEmpty/CouponUse |
| coupon | `config/KafkaDltConfig` | 3 | PaymentResponse/CouponRollback/UserCreated |
| pay | `message/config/KafkaDltConfig` | 1 | PaymentRequestKafkaListener（既存の NO-OP スキップは維持） |
| user | `message/config/KafkaDltConfig` | 1（record） | —（record リスナー） |
| chat | `common/config/kafka/KafkaDltConfig` | 3（record 2 を含む） | UserCreatedConsumer（MessageConsumer は record モードなので変更なし） |

record モードのリスナーは、`BatchListenerFailedException` がなくても `DefaultErrorHandler` がレコード単位でリトライ／隔離する。

**本番反映時**: DLT トピックの事前作成リスト — `cart-rollback.DLT`、`cart-empty.DLT`、`coupon-use.DLT`、`payment.DLT`、`coupon-rollback.DLT`、`user-created.DLT`、`order.DLT`、`product-update.DLT`（＋ chat のトピック）。

## 2. コンシューマ冪等性（G8） — 在庫の二重減算防止

**お金が絡む経路（product-update → catalog の在庫減算）** に end-to-end で適用した。

- **イベント契約**: `ProductUpdateRequest`（common）に `eventId` を追加。JSON は後方互換 — 旧バージョンのプロデューサー（null）なら従来どおりの動作。
- **プロデューサー**（order `OrderSaga`）: `eventId = "order-<orderId>"`。ランダム UUID ではなく **自然な冪等キー** を選んだ — 「在庫減算は注文ごとに 1 回」が業務ルールなので、Kafka の再配信だけでなく **Saga 自体が再実行されて新しいメッセージを作っても** 重複が弾かれる。
- **コンシューマ**（catalog）: `product.processed_event` テーブル（Flyway `V2__create_processed_event.sql`）に処理履歴を **在庫変更と同じトランザクションで** 記録。再配信時は `existsById` でスキップ。同時重複（まれ）は PK 衝突 → 全体ロールバック → 再配信時にスキップ、という形で収束する。
- **テスト**: `ProductUpdateMessageListenerTest` の 3 ケース（重複スキップ／初回処理＋記録／eventId なしの後方互換）が通過。
- **保存**（2026-10-15）: 台帳が防ぐのは同じイベントの再到着で、その期間は元トピックの保存（7 日）と DLT の保存（14 日、ops/02 — 元トピックへ戻すとさらに 7 日）である。三つの台帳とも common `ProcessedEventPurger` で毎日、保存期間（`<サービス>.processed-event.retention`、既定 30 日・**22 日未満にはならない**）を過ぎた行を 5,000 行ずつ文ごとにコミットして削除する（一回の上限 200 回、残りは翌日）: coupon `coupon.processed_event` 04:40（V9）、catalog `product.processed_event` 04:45（V19）、cart `cart.processed_event` 04:50（V7）— 各マイグレーションは `processed_at` インデックスを足すだけ。DLT の保存を延ばすなら下限（`MIN_RETENTION`）も一緒に延ばす。

Redis（SETNX）ではなく **DB テーブル** を選んだ理由: 処理履歴の記録がビジネス上の変更と **アトミック** でなければならないため（Redis だと「記録されたのに DB はロールバック」という隙間が生じる）。

**残りの展開対象**（同じパターンを適用可能、ドキュメント化のみ）: user-created の 3 コンシューマ（order/chat/coupon — 現在は email のユニーク制約が事実上の防御）、payment 応答（order — 楽観ロックの NO-OP が部分的に防御）、cart-empty/rollback。

## 3. アラートルール ＋ Alertmanager

- `observability/alert-rules.yml`: **ServiceDown**（2m） · **CircuitBreakerOpen** · **High5xxRate**（>5%、5m） · **HighP99Latency**（>3s） · **JvmHeapNearLimit**（>90%）。
- Alertmanager（9093）を追加し、Prometheus に rule_files/alerting を配線。受信チャネルはプレースホルダー（Webhook/Slack への差し替えポイントをコメントで明示）。
- p99 ルールのために `application-common.yml` で `percentiles-histogram.http.server.requests: true` を有効化（バケットを公開）。
- 未対応（ロードマップ）: コンシューマラグ・DLT の滞留量（kafka exporter が必要）、Outbox の滞留（カスタムメトリクスが必要 — docs/03 付録）。

## 4. Grafana ダッシュボードのコード化

`observability/grafana/provisioning/dashboards/` — provider ＋ `ecommerce-overview.json` をプロビジョニング: Service Up（スタット） · リクエストレート · 5xx 比率 · p99 · JVM ヒープ · サーキットブレーカーの状態。起動するとすぐ「ecommerce-msa」フォルダに表示される。

## 検証

- DLQ 展開 ＋ eventId ＋ 冪等性を含め **全モジュールの compileJava BUILD SUCCESSFUL**。
- catalog の新規テスト **12 ケースすべて通過**（既存 9 ＋ 冪等性 3）。
- observability の YAML/JSON のパース検証。
- ⚠️ 実際のブローカー／DB に接続した統合検証ではない — DLT トピックの作成など、§1・§3 の本番反映手順が必要。
