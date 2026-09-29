# 可観測性スタック · 分散トレーシング · Kafka DLQ の適用

**日本語** | [한국어](11-observability-and-kafka-reliability.ko.md)

> 🕰️ **状態（2026-09-27）— 過去の記録。** ダッシュボード · Alertmanager · プロデューサー側のトレーシング · DLQ の展開はその後完了しました（[12](12-dlq-rollout-idempotency-alerts.md) · [22](22-alerting-and-backup-automation.md) · [51](51-logging.md)）。

> 作成日: 2026-07-27 · 解消対象: 最終点検の G3（可観測性の収集スタック不在）· G4（分散トレーシング不在）· G7（Kafka DLQ 不在）
> 関連: [docs/03](03-architecture-and-reliability.md) R-2/R-4, F-2 · [docs/10](10-quality-hardening.md)

## 1. 可観測性スタック (G3)

これまでは `/actuator/prometheus` と traceId を **公開するだけ** で収集器がなく、`docs/03` の障害対応ランブックが前提とする「検知」が不可能だった。

**追加**: `docker-compose-observability.yml` + `observability/`
| コンポーネント | ポート | 役割 |
|---|---|---|
| Prometheus v2.53 | 9090 | 8つのサービスの `/actuator/prometheus` をスクレイプ（HTTPS, 15s）、15日保持 |
| Grafana 11.1 | **3001**（フロントの 3000 との衝突回避） | Prometheus/Zipkin データソースを **自動プロビジョニング** |
| Zipkin 3.4 | 9411 | 分散トレースの収集/参照（デモ: インメモリストレージ） |

```bash
docker compose -f docker-compose-observability.yml up -d
```

注意（正直な限界）:
- サービスが自己署名 TLS で起動するためスクレイプは `insecure_skip_verify` — 本番ではプライベート CA を `ca_file` で信頼させること。
- Grafana **ダッシュボードは含まない**（データソースの自動接続のみ）。JVM（Micrometer）公式ダッシュボードの import を推奨（ID 4701 など）。
- アラートルール（Alertmanager）は次のステップ。`docs/03` 付録のメトリクスチェックリストがルールの出発点である。
- k8s ではこの compose の代わりに kube-prometheus-stack（Helm）を推奨（`k8s/README.md`）。

## 2. 分散トレーシング (G4)

**追加**:
- ルートの `build.gradle`（subprojects 共通）: `micrometer-tracing-bridge-brave` + `zipkin-reporter-brave` → **全サービス**（gateway を含む）に適用。バージョンは Boot BOM で管理。
- `application-common.yml`: `management.tracing.sampling.probability: 1.0`（本番では下げること）、`management.zipkin.tracing.endpoint`（デフォルト `localhost:9411`、`ZIPKIN_ENDPOINT` でオーバーライド — k8s ConfigMap から注入可能）。
- **全サービスのログパターンに `[%X{traceId:-},%X{spanId:-}]` を挿入** — サービスがカスタムパターンで Boot デフォルトの相関出力を上書きしていたため、明示的に追加（10個の yml）。
- Kafka 経路の伝播: 今回修正した catalog/order のコンシューマーファクトリに `setObservationEnabled(true)` を適用。

効果: HTTP（ゲートウェイ→サービス、Feign）と catalog/order の Kafka 消費まで **1つの traceId でログを接続 + Zipkin のタイムライン参照**。残っているもの: 残りのサービスの Kafka コンシューマー/プロデューサーの observation 有効化（同じ1行のパターン）、カスタム KafkaTemplate のプロデューサー側の伝播。

## 3. Kafka DLQ — リファレンス実装 (G7)

以前「batch listener なので危険」として先送りしていた項目を、**コンパイル・テストが可能になった今**、catalog（商品更新）と order（Saga の中核）に実際に実装した。

**パターン**（`KafkaDltConfig` — catalog/order それぞれ）:
1. `DeadLetterPublishingRecoverer` — 値の型ごとのテンプレート2つ（正常なペイロード→JSON 再シリアライズ、**デシリアライズ失敗→元の byte[] を保持**）、宛先 `<topic>.DLT`（パーティション -1: DLT のパーティション数が異なっても安全）。
2. `DefaultErrorHandler` = recoverer + **指数バックオフ 3回**（1s×2）、デシリアライズ/変換の例外は **リトライせず即座に DLT**。
3. コンシューマーファクトリ: 値のデシリアライザを **`ErrorHandlingDeserializer` でラップ** — これがないと poison ペイロードがリスナーに届く前にコンシューマースレッドで爆発し、**無限に再ポーリング**（F-2 の実際のメカニズム）される。
4. リスナー: `forEach` → **インデックスループ + `BatchListenerFailedException(cause, index)`** — 成功分のオフセットはコミットされ、失敗したレコードだけがリトライ/隔離されたあと残りが再配信される。（丸ごと失敗させると成功分まで重複して再処理される問題を防止）

**適用ファイル**:
| サービス | ファイル |
|---|---|
| catalog | `message/config/KafkaDltConfig.java`（新規）、`KafkaConsumerConfig`、`ProductUpdateKafkaListener` |
| order | `config/KafkaDltConfig.java`（新規）、`KafkaConsumerConfig`（両方のファクトリ）、`CustomerKafkaListener`、`PaymentResponseKafaListener`（既存の NO-OP 例外スキップは維持） |

**テスト**: `ProductUpdateKafkaListenerTest` — バッチの途中で失敗したときの「前は処理・失敗インデックスを報告・後ろは未処理」というセマンティクスを固定。

**本番反映チェックリスト**:
- DLT トピック（`product-update.DLT`、`payment.DLT`、`user-created.DLT`）の自動作成が無効なら事前に作成。
- DLT への蓄積の監視（コンシューマーラグ/トピックサイズ）+ 再処理手順（内容を修正して元トピックに再発行）のランブック化。
- **残り4つのサービス（cart/coupon/pay/user）に同じパターンを展開** — この2つの実装がコピペ可能なテンプレートである。

## 4. 依然として残っているもの

- **G8 冪等性**: `ProductUpdateRequest` などのイベントペイロードに `eventId` がなく、コンシューマーで dedupe できない。**イベント契約に eventId を追加**（JSON 下位互換）したうえで Redis SETNX/ユニーク制約による dedupe — 契約変更なので別途進める必要がある。
- Alertmanager のアラートルール、Grafana ダッシュボードのコード化、残りのサービスへの DLQ/observation 展開、ログ収集（Loki/ELK）。
  > **2026-09 更新。** ログ収集は Loki + Alloy で導入された — docs/51 §4。Kafka **プロデューサー** 側のトレース伝播はまだである。
