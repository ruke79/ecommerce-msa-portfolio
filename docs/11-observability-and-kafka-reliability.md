# 可観測性スタック · 分散トレーシング · Kafka DLQ の適用

**日本語** | [한국어](11-observability-and-kafka-reliability.ko.md)

> 🕰️ **状態（2026-11-14）— 2026-07-27 の記録。ここに書かれた「残り」はすべて完了し、トレーシングは Zipkin から OpenTelemetry + Jaeger に変わりました。** 本文（§1 〜 §4）は当時の文章のまま残し、節ごとに 🕰️ で今を書きました。今の構成は [docs/63](63-opentelemetry.md)（トレース）· [docs/51](51-logging.md)（ログ）· [docs/12](12-dlq-rollout-idempotency-alerts.md)（DLQ · 冪等 · 警報）· [docs/22](22-alerting-and-backup-automation.md)（警報の配送）· [docs/91](91-slo-and-capacity.md)（SLO · コンシューマー遅延）を見てください。

| 2026-07 のこの文書 | 今（2026-11-14） | どこ |
|---|---|---|
| トレースの保存先は Zipkin 3.4（インメモリ） | **OTel Collector 0.160 → Jaeger 2.20**（badger、7 日）。Collector が個人情報のマスク · テールサンプリング（B-102）· スパンの切り詰め（B-94）を受け持つ。Grafana の古い Zipkin データソースは削除する設定 | docs/63 |
| サービス側 `micrometer-tracing-bridge-brave` + `zipkin-reporter-brave` | `micrometer-tracing-bridge-otel` + OTLP（HTTP）— サービスは Collector にだけ送る | ルートの `build.gradle` |
| サンプリング 1.0 は「本番では下げること」 | **1.0 固定**（B-72）— 何を残すかはトレース完了後に Collector が選ぶ | `application-common.yml` |
| Kafka のトレースは catalog · order のコンシューマーだけ、プロデューサーはまだ | コンシューマーは Kafka を受ける 8 サービスすべて、プロデューサーはすべての `KafkaTemplate`（共通の後処理）、アウトボックスの行にもトレース文脈 | docs/51 §4d · `KafkaTemplateObservationPostProcessor` · `OutboxTraceContext` |
| Grafana ダッシュボードなし | ダッシュボード二つを自動登録（`ecommerce-overview` · `tracing-red`）、指標 ↔ トレース ↔ ログの往復 | `observability/grafana/provisioning/` |
| 警報は次のステップ | Alertmanager 0.28.1 + 警報ルール（サービス · リクエスト · JVM · サガ · アウトボックス · バックアップ · トレース · **SLO · コンシューマー遅延**）、Discord へ配送（B-96） | `observability/alert-rules.yml` · docs/22 · docs/91 |
| スクレイプは `insecure_skip_verify` | ローカル開発（`prometheus.yml`）だけ。本番はクラスター内のエージェントが CA で検証してノードの Prometheus へ送る（B-95） | `k8s-overlays/external/metrics-agent.yaml` |
| DLQ は catalog · order だけ、バックオフ 3 回 | Kafka を受ける 8 サービス（+ user の DLT 設定）、共通ポリシー `DltRetryPolicy` — 一時的なエラーは 1 · 2 · 4 … 30 秒で 10 回（約 3 分）、DLT カウンター · 警報 `KafkaDeadLetterPublished`（起動時に 0 — B-101）· 再処理 `ops/56` | docs/12 |
| G8 冪等性（eventId なし） | eventId + 処理台帳（catalog · coupon · cart · order）、30 日の整理（`ProcessedEventPurger`） | docs/12 §2 |
| ログ収集なし | Loki + Alloy、ログ行の traceId → Jaeger | docs/51 §4 |
| コンシューマーラグの監視 | 2026-11-14 から指標 `kafka_consumer_fetch_manager_records_lag_max` · 警報 `KafkaConsumerLagHigh` | docs/91 §3 |

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

> 🕰️ 今: 上の四つの限界はすべて解消した — 本番のスクレイプは CA で検証（B-95）、ダッシュボード二つを自動登録、Alertmanager · 警報ルール（docs/12 §3 · docs/22）、本番は kube-prometheus-stack ではなくノードの compose（二ファイル — `docker-compose-observability.oci.yml`）+ クラスター内の `metrics-agent`（docs/63 §5）。Zipkin は Jaeger に変わった（docs/63）。サービスは今 11 個。

## 2. 分散トレーシング (G4)

**追加**:
- ルートの `build.gradle`（subprojects 共通）: `micrometer-tracing-bridge-brave` + `zipkin-reporter-brave` → **全サービス**（gateway を含む）に適用。バージョンは Boot BOM で管理。
- `application-common.yml`: `management.tracing.sampling.probability: 1.0`（本番では下げること）、`management.zipkin.tracing.endpoint`（デフォルト `localhost:9411`、`ZIPKIN_ENDPOINT` でオーバーライド — k8s ConfigMap から注入可能）。
- **全サービスのログパターンに `[%X{traceId:-},%X{spanId:-}]` を挿入** — サービスがカスタムパターンで Boot デフォルトの相関出力を上書きしていたため、明示的に追加（10個の yml）。
- Kafka 経路の伝播: 今回修正した catalog/order のコンシューマーファクトリに `setObservationEnabled(true)` を適用。

効果: HTTP（ゲートウェイ→サービス、Feign）と catalog/order の Kafka 消費まで **1つの traceId でログを接続 + Zipkin のタイムライン参照**。残っているもの: 残りのサービスの Kafka コンシューマー/プロデューサーの observation 有効化（同じ1行のパターン）、カスタム KafkaTemplate のプロデューサー側の伝播。

> 🕰️ 今: Brave · Zipkin は `micrometer-tracing-bridge-otel` + OTLP に変わった（Collector のアドレスは `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` → `management.otlp.tracing.endpoint` — `ZIPKIN_ENDPOINT` はない、docs/63 §2）。サンプリングは「下げる」のではなく **1.0 固定**（B-72 — テールサンプリングが結果を見て選ぶ）。ログパターンは共通の `logback-spring.xml` 一つ（`[traceId,spanId,userId]`）。残っていた二つは完了 — コンシューマーの observation は Kafka を受ける 8 サービスすべて、プロデューサーはすべての `KafkaTemplate` Bean に共通の後処理（`KafkaTemplateObservationPostProcessor`、docs/51 §4d）、アウトボックスのリレーのようにリクエストの外で送るものは行に保存したトレース文脈でつなぐ（`OutboxTraceContext`）。

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
| order | `config/KafkaDltConfig.java`（新規）、`KafkaConsumerConfig`（両方のファクトリ）、`CustomerKafkaListener`、`PaymentResponseKafkaListener`（既存の NO-OP 例外スキップは維持） |

**テスト**: `ProductUpdateKafkaListenerTest` — バッチの途中で失敗したときの「前は処理・失敗インデックスを報告・後ろは未処理」というセマンティクスを固定。

**本番反映チェックリスト**:
- DLT トピック（`product-update.DLT`、`payment.DLT`、`user-created.DLT`）の自動作成が無効なら事前に作成。
- DLT への蓄積の監視（コンシューマーラグ/トピックサイズ）+ 再処理手順（内容を修正して元トピックに再発行）のランブック化。
  - **再処理ツール(2026-10-22): `ops/56-dlt-replay.sh`**。`list <トピック>.DLT` は位置・キー・サイズ・元の位置・失敗したグループ・例外を示す(値は出力しない — 個人情報)。`replay <トピック>.DLT <パーティション> <オフセット>[-<終わり>]` は送らずに示すだけで、`APPLY=1` なら元のトピック・**元のパーティション**へ値・キー・ヘッダーをバイトのまま送る(DLT ヘッダーだけ外し `dlt-replayed-from` を足す)。
  - Kafka Pod の中でその Pod の Java・kafka-clients で動く(`ops/tools/DltReplay.java`、単一ファイル)。
  - コンソールツールでは無理 — 値を行単位の文字として扱い Avro の値が壊れる(実習ゲームデイ poison-message)。
  - ⚠️ 元のトピックの**すべての**コンシューマーグループが再び受け取る — 各コンシューマーが eventId・処理台帳で除くかを先に見る(docs/12 §2)。テストは `DltReplayScriptTest`(cp-kafka 7.6.1)。
- **残り4つのサービス（cart/coupon/pay/user）に同じパターンを展開** — この2つの実装がコピペ可能なテンプレートである。

> 🕰️ 今: 展開は完了 — Kafka を受ける 8 サービス（cart · catalog · chat · coupon · delivery · notification · order · pay）がすべてバッチリスナー + インデックスの報告 + `KafkaDltConfig`（user にも DLT の設定はある — 受けるリスナーはない）。リトライは「3 回（1s×2）」ではなく共通の `DltRetryPolicy`: 一時的なエラー（DB の一時的な失敗 · ロックなど）は 1 · 2 · 4 · 8 · 16 · 30 秒 … 10 回（約 3 分）、それ以外はすぐ DLT。DLT に行ったレコードはカウンター `kafka_dlt_records_total{topic}`（起動時に 0 — B-101）と警報 `KafkaDeadLetterPublished` で見える。⚠️ 2026-10-15 より前は pay · order のファクトリーのレコードアダプターが例外を飲み込み、このリトライ · DLT が動いていなかった（B-88 — 今は禁止）。

## 4. 依然として残っているもの

> 🕰️ **2026-11-14 — 下の一覧はすべて完了した。** 今残っている観測の作業は docs/91 §6（SLO の数字の確定 · 天井の測定 · ゲートウェイの SLI · SLO ダッシュボード）にある。

- ~~**G8 冪等性**: `ProductUpdateRequest` などのイベントペイロードに `eventId` がなく、コンシューマーで dedupe できない。~~ → ✅ eventId + 処理台帳（catalog · coupon · cart · order の配送状態）、30 日の整理 `ProcessedEventPurger`（docs/12 §2）。
- ~~Alertmanager のアラートルール~~ → ✅ docs/12 §3 · docs/22（Discord、B-96）· docs/91（SLO バーンレート · コンシューマー遅延）。
- ~~Grafana ダッシュボードのコード化~~ → ✅ `observability/grafana/provisioning/dashboards/`。
- ~~残りのサービスへの DLQ/observation 展開~~ → ✅ Kafka を受ける 8 サービスすべて（§3 の 🕰️）、プロデューサー側のトレースも（§2 の 🕰️ — 2026-09 の更新で「まだ」と書いたもの）。
- ~~ログ収集（Loki/ELK）~~ → ✅ Loki + Alloy（docs/51 §4）。
