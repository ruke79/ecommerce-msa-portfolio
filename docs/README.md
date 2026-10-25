# 📚 ecommerce-msa ドキュメント

**日本語** | [한국어](README.ko.md)

設計判断と障害対応の記録です。**何を作ったか** よりも、**なぜそう決め、どう壊れ、どう直したか** を残します。

- ドキュメント番号は変わりません。コードのコメントが `docs/51 §4h` のように番号で参照しているためです。52 番は欠番です。
- **状態** 欄は次のように読みます。
  - ✅ **現行** — 今のコードと一致しています。
  - 🟡 **一部古い** — 骨格は合っていますが、後から変わった箇所があります。何が変わったかはドキュメント冒頭の状態表示に書いてあります。
  - 🕰️ **過去の記録** — その時点での診断や作業の記録です。現在の状態は、示された後続のドキュメントにあります。

## 初めて読むなら

| 時間 | 順序 |
|---|---|
| 5 分 | [62 技術事例集](62-engineering-casebook.md) — 重要な事例 18 個を、問題 · 対処 · 限界と参考書で |
| 15 分 | 上に加えて [00 Before/After 総合索引](00-before-after.md) → [54 改善の総まとめと実務基準](54-improvements-vs-practice.md) |
| テーマ別 | 下の表から関心のある領域を選びます |
| 運用者 | [33 デプロイ Runbook](33-deploy-runbook.md) → [36 デプロイパイプライン](36-deployment-pipeline.md) → [34 受け入れチェックリスト](34-acceptance-checklist.md) → [運用スクリプト](../ops/README.md) |
| 開発者 | [61 新サービス追加チェックリスト](61-adding-a-service.md) → [17 テスト戦略](17-testing.md) → [55 無停止マイグレーション](55-zero-downtime-migrations.md) |

---

## 1. 概要 · 総まとめ

| # | ドキュメント | 内容 | 状態 |
|---|---|---|---|
| 62 | [技術事例集](62-engineering-casebook.md) | 事例 19 個 — 問題 · 対処 · 限界 · 改善点、事例ごとに参考書 1 冊 | ✅ |
| 00 | [Before/After 総合索引](00-before-after.md) | 見つけた欠陥（B-1〜29 · FE · CAT …）と解決を 1 つの表に | 🟡 |
| 54 | [改善の総まとめ — 実務基準との比較](54-improvements-vs-practice.md) | 領域ごとに何が足りず今はどうか、まだ足りないもの、教訓 10 個 | 🟡 |
| 03 | [アーキテクチャと信頼性](03-architecture-and-reliability.md) | 構成図 · Saga フロー · 障害シナリオ F-1〜9 と対応 | 🟡 |
| 38 | [全体コードレビュー](38-code-review.md) | バックエンド · フロント · CDC の全数レビュー。本番で壊れていた 10 件と直す順序 | 🕰️ |
| 57 | [残りの優先事項を閉じた記録](57-remaining-priorities.md) | 例外メッセージの漏洩 · トークン寿命の単位の取り違え · 星評価の上書き · mTLS のサービス別アイデンティティなど | 🟡 |
| 01 | [改善以前の問題分析](01-branch-issues.md) | 着手前の診断 — 根拠となるファイル · 行番号付き | 🕰️ |
| 02 | [改善内容](02-improvements.md) | 01 に対する最初の修正とロードマップ | 🕰️ |

## 2. 決済 · 注文 · 在庫

| # | ドキュメント | 内容 | 状態 |
|---|---|---|---|
| 35 | [決済の検証](35-payment-verification.md) | サーバーがブラウザの「決済した」を信じていた — 再照会 · 金額照合 · 再利用の遮断 · fail closed | 🟡 |
| 49 | [決済手段の拡張](49-payment-providers.md) | 決済ポート、Stripe · PortOne V1/V2 · 銀行振込 · バーチャル口座、通貨、返金 · 部分返金 · 返品 | ✅ |
| 59 | [決済前の在庫予約](59-stock-reservation.md) | 最後の 1 個の二重販売 → 30 分の予約、全部か無か、残り数量の表示 | ✅ |
| 53 | [品切れと再入荷](53-restock-and-sold-out.md) | 運用者へ品切れアラート、お客様へ再入荷通知、売り越しの自動部分返金 | ✅ |
| 56 | [決済 Saga の遅延](56-saga-latency.md) | 12 秒 = 10 秒ポーラー 2 回 → コミット直後のリレー | ✅ |
| 47 | [注文作成の 400 が見えなかった件](47-order-create-failure-visibility.md) | ログのない catch、null → NPE のメッセージが画面に | 🕰️ |

## 3. メッセージング · Saga · 整合性

| # | ドキュメント | 内容 | 状態 |
|---|---|---|---|
| 11 | [可観測性スタックと Kafka DLQ](11-observability-and-kafka-reliability.md) | Prometheus · Grafana · Zipkin、レコード単位 DLQ の最初の適用 | 🕰️ |
| 12 | [DLQ の展開 · コンシューマーの冪等性 · アラート](12-dlq-rollout-idempotency-alerts.md) | 在庫の二重引き当てを防ぐ冪等台帳、アラートルール、ダッシュボードのコード化 | 🕰️ |
| 21 | [水平スケーリング](21-horizontal-scaling.md) | レプリカを増やしたら Outbox の二重発行 — `SKIP LOCKED`、増やすと壊れるサービス | 🟡 |
| 48 | [ユーザーのレプリケーション](48-user-replication-debt.md) | 1 つのイベント · 3 つのコンシューマー · 3 種類の失敗 → レプリケーションの廃止 · Outbox · 突合バッチ | 🟡 |
| 37 | [トランザクション内の外部呼び出し](37-transaction-boundary-audit.md) | 12 モジュール中 9 個。Feign のタイムアウトが適用されていなかった件 | 🟡 |

## 4. データ · スキーマ · バックアップ

| # | ドキュメント | 内容 | 状態 |
|---|---|---|---|
| 20 | [スキーマの所有権](20-schema-ownership.md) | `ddl-auto: update` → Flyway + `validate`、空 DB · レガシー DB の両方で検証 | ✅ |
| 69 | [ERD — 実際のスキーマ](69-erd.md) | 壊れた画像添付の代わりに、マイグレーションを適用した DB から自動生成(Mermaid)、サービス間の論理参照の概要、`ops/tools/gen-erd.py --check` | ✅ |
| 55 | [無停止マイグレーション](55-zero-downtime-migrations.md) | expand-contract、デプロイトレイン、自ら止まるマイグレーション | ✅ |
| 09 | [PostgreSQL パーティショニング · シャーディング](09-postgresql-partitioning-sharding.md) | Outbox の月次パーティションとシャーディングの段階設計（DDL 付き） | 🟡 |
| 15 | [バックアップ · 災害復旧](15-backup-and-dr.md) | 等級別の RPO/RTO、PITR — `DROP TABLE` を実際に巻き戻して検証 | 🟡 |
| 22 | [アラート配信とバックアップの自動化](22-alerting-and-backup-automation.md) | 受信者がプレースホルダーでアラートがどこにも届かなかった件、バックアップ CronJob | ✅ |
| 42 | [バッチの点検](42-batch-review.md) | 冪等でないのに再実行されるインポートジョブ、スケジュールタスク 8 個のレプリカ安全性 | ✅ |

## 5. セキュリティ · 認証

| # | ドキュメント | 内容 | 状態 |
|---|---|---|---|
| 27 | [セキュリティレビュー](27-security-review.md) | IDOR · 認証なしの書き込み · actuator の露出 · ヘッダー偽造、巻き戻して確認した回帰テスト | 🟡 |
| 30 | [git 履歴からの資格情報漏洩](30-git-history-secret-exposure.md) | 履歴を消しても解決しない理由、失効の優先順位、実測での確認 | 🟡 |
| 41 | [API セキュリティ点検](41-api-security-applied.md) | 『API Security in Action』のラボ 17 種と照合 — レートリミットキーのなりすまし · クーポン BOLA | 🟡 |
| 18 | [証明書 · キーストア](18-certificates.md) | 発行 → ストア変換 → 注入、ハードコードされたパスの削除、ゲートウェイの TLS 検証 | 🟡 |
| 46 | [多重ログイン判定](46-multiple-login-detection.md) | 名前が Redis だった HashMap — 偽陽性と偽陰性 | ✅ |
| 68 | [リフレッシュトークンのローテーション・再利用検知](68-refresh-token-rotation.md) | 更新しても古いトークンが 14 日間生きていたバグ、Lua で比較して削除、ファミリー(fam)・再利用ならファミリー切断、同時更新の猶予、ログアウト = ファミリー切断、アラート・ユーザー通知、すべての端末からログアウト、長い接続(チャット・通知)をログインに結びつける、トークンはハッシュのみ保存 | ✅ |
| 70 | [2FA 秘密の露出・決済の原子性](70-2fa-secret-and-payment-atomicity.md) | 管理者一覧が全員の TOTP 秘密を応答・平文保存 → DTO・保存時暗号化(鍵の印・起動時バックフィル)、決済とアウトボックスを 1 トランザクションに | ✅ |

## 6. デプロイ · インフラ · 運用

| # | ドキュメント | 内容 | 状態 |
|---|---|---|---|
| 36 | [デプロイパイプライン](36-deployment-pipeline.md) | 一度ビルドしたイメージを SHA タグで、スクリプト 1 つでデプロイ、承認後の自動デプロイ | 🟡 |
| 33 | [デプロイ Runbook](33-deploy-runbook.md) | Mac · Linux · OCI Ampere 1 台に載せる手順とよく詰まる箇所、ソーシャルログインの設定 | 🟡 |
| 34 | [受け入れチェックリスト](34-acceptance-checklist.md) | デプロイしたものを人が最後まで操作してみる経路、失敗をどこで見るか | 🟡 |
| 72 | [モデルリファクタリング 2 次のデプロイ手順書](72-model-refactor-deploy-runbook.md) | 自動ロールバックが無効になるデプロイ(ROLLBACK-UNSAFE) — 事前点検クエリ・バックアップ・デプロイ中のお知らせ編集禁止・デプロイ後の確認・失敗時は前に進めて直す・次のリリースの contract | ✅ |
| 73 | [注文 ↔ 配送先の expand → contract](73-order-address-expand-contract.md) | 二重外部キーを二つのリリースで — V25(一方向・注文側のユニーク) → V26(列の削除、ROLLBACK-UNSAFE)、ローリング中の旧・新 Pod、自ら止まる時間・データガード、実 PostgreSQL のマイグレーションテスト | ✅ |
| 74 | [機能フラグ — 第 1 段階](74-feature-flags.md) | OpenFeature + ファイルプロバイダー(flagd 形式、再起動なしで反映)、search.engine を検索語の比率で切り替え、評価結果をスパン・span_metrics に、キャッシュキーにエンジン、B-76 | ✅ |
| 76 | [注文状態イベントと進行タイムライン](76-order-status-read-model.md) | CQRS を注文進行の照会へ — OrderStatusChanger(一箇所 + 同じトランザクションのアウトボックス)、order-status → saga-projection の OrderTimeline(eventId 重複排除・発生順・上限)、ゲートウェイ経由の照会は他人の注文を 404、画面の「進行の記録」、B-78 | ✅ |
| 77 | [イベントソーシング — 適用しなかった理由と設計](77-event-sourcing-decision.md) | 決定の記録 — 今は「DB が真実、イベントは通知」(CDC 側)。Kleppmann・Richardson・Vernon・Young・Fowler を根拠に、約束と代価、Order 37 フィールド・23 クラスの数字、やるなら Order 集約から PostgreSQL イベント表 + 同期プロジェクションで四リリース、見直すシグナル | ✅ |
| 79 | [運用デモ用ユーザーの大量生成](79-demo-users.md) | 実際にログインできるデモアカウントを数十万人 — `ops/48`(予約 id 範囲・`@demo.invalid`・共通の BCrypt ハッシュ・`ROLE_USER`)、同じトランザクションで user-created(クーポン・チャットの複製)、加入日は 8 日〜2 年前(再調整器を避ける)、1KB/人・10 万人 17 秒、イベント流しの時間、ログイン負荷の注意(認証ルートの制限・多重ログイン・失敗の数え方)、purge は範囲かつドメインだけ | ✅ |
| 83 | [負荷試験 — k6 シナリオ](83-load-test.md) | 画面と同じアドレス・ヘッダーで閲覧・注文(ログイン → カート → 注文 → 銀行振込 → 取消)・急増・点検 — `ops/53`。実習クラスタでだけ(本番は拒否)、到着率モデル、JSON の検査(index.html 200 を成功と見ない)、429 は別、ログインは開始前にゆっくり・アカウント一つに VU 一つ(リフレッシュのローテーション)、デモの IP 一つのバケットのため計測の間だけ流入制限を上げる(`limits`)、一緒に見るサーバー指標・結果の記録表・限界 | 🟡 |
| 85 | [まとめリリースのデプロイ・ランブック](85-release-deploy-runbook.md) | 本番 b7c5053(10-10)以降に溜まった修正を一度に — 追加だけのマイグレーション(cart · catalog · coupon · order · user)、`ops/28` が保証しない順序(coupon · cart → order)をデプロイ直後の確認で代える(アウトボックスの FAILED 行 · DLT の送り直し)、事前点検 · リハーサル · バックアップ · トピック · 身元、画面の一周、数日後の observe → enforce | 🟡 |
| 61 | [新サービス追加チェックリスト](61-adding-a-service.md) | 登録が必要な箇所すべてと、漏れると静かにおかしくなるもの | ✅ |
| 31 | [環境分離 · OCI Ampere · Cloudflare Tunnel](31-kustomize-oci-tunnel.md) | kustomize オーバーレイ、arm64、インバウンドポート 0 | 🟡 |
| 32 | [インフラの外部化](32-external-managed-infra.md) | マネージドの無料枠に出せるものと出せないもの | ✅ |
| 23 | [本番インフラの選定](23-infrastructure-decision.md) | 依存関係が実際に使う機能を根拠にした比較 | 🕰️ |
| 13 | [シークレット · DB HA · ES 検索への切り替え](13-secrets-dbha-es-search.md) | ローテーション Runbook、PostgreSQL レプリケーションの実検証 | 🟡 |
| 08 | [k3s マニフェスト](08-k3s-deployment.md) | 最初のマニフェストの記録 | 🕰️ |

## 7. 可観測性 · パフォーマンス

| # | ドキュメント | 内容 | 状態 |
|---|---|---|---|
| 63 | [OpenTelemetry](63-opentelemetry.md) | OTel デモの構成 — OTLP · Collector · Jaeger · span_metrics · exemplar、トレースの資格情報マスク | ✅ |
| 65 | [サイズ単位の SKU](65-size-level-sku.md) | SKU を色からサイズの 1 行へ、ルールは DB 関数の 1 か所、カート → 注文 → 梱包リストへ記録、管理者登録サイズの親欠落バグ | ✅ |
| 66 | [gRPC の試行適用 — 在庫予約](66-grpc-stock-reservation.md) | order → catalog の在庫予約を gRPC で(HTTP と並べ、設定で切り替え)、mTLS 判定の再利用、期限の伝播、同等性テスト、B-73 | ✅ |
| 51 | [ロギング](51-logging.md) | 認証番号のログ漏洩、出力段階でのマスキング、JSON ログ · Loki、トレースの往復 | 🟡 |
| 16 | [Saga E2E · 可観測性の実地検証](16-saga-e2e-and-observability-verification.md) | 実際のインフラで検証 — チャットの保存と ES 検索は一度も動作したことがなかった | 🕰️ |
| 19 | [ローカルでのパフォーマンスチューニング](19-local-performance-tuning.md) | N+1 · コネクションプール総和の超過を実測、構造的な事実はビルドで固定 | 🟡 |

## 8. 機能設計

| # | ドキュメント | 内容 | 状態 |
|---|---|---|---|
| 25 | [配送サービスの設計](25-delivery-service-design.md) | 分割 · 国際配送、配送業者アダプター、状態別のポーリング | 🟡 |
| 58 | [カスタマーサポートチャット](58-customer-support-chat.md) | ロール 1 つで相談員に、相談スレッド、サーバーが強制する権限、1対1画面 · 問い合わせ種別 · 満足度 · 内部メモ · 返却 | ✅ |
| 29 | [トランザクションメール](29-transactional-email.md) | 送信パイプラインを notification-service へ、確認リンクは資格情報 | 🟡 |
| 24 | [SMS 通知 · 2 段階認証](24-sms-notifications.md) | 韓国 · 日本の番号、重複送信の三重防御、認証番号の濫用防止 | 🟡 |
| 44 | [お知らせ · 通知 · イベント](44-notice-notification-event-gap.md) | すでにあるものと実際に欠けているもの | 🟡 |
| 39 | [商品登録の手順](39-product-registration.md) | 最初の画面に商品が表示されるまで | ✅ |
| 45 | [多言語対応（日本語）](45-internationalization.md) | 依存関係のない i18n、第 1 次の範囲 | 🟡 |
| 60 | [多言語対応の全数調査](60-i18n-audit.md) | 画面の文言 1,091 箇所 · サーバーの文言 約 290 個、ガードテスト | ✅ |

## 9. 検索

| # | ドキュメント | 内容 | 状態 |
|---|---|---|---|
| 50 | [`/browse` の精密分析](50-browse-analysis.md) | 「該当なし」が「制限なし」にひっくり返っていたフィルター、レコメンド · セマンティック検索の順序 | ✅ |
| 06 | [catalog 検索の問題分析](06-catalog-search-issues.md) | ES を使っていなかった検索、キャッシュ無効化 | 🕰️ |
| 07 | [catalog 検索の改善](07-catalog-search-improvements.md) | 06 に対する修正と ES への切り替え設計 | 🕰️ |

## 10. フロントエンド

| # | ドキュメント | 内容 | 状態 |
|---|---|---|---|
| 40 | [フロントエンドの欠陥リスト](40-frontend-bug-list.md) | ユーザーへの影響基準で P0〜P2 の 20 件と修正記録 | 🕰️ |
| 26 | [フロントエンドの全面レビュー](26-frontend-review-and-refactor.md) | 欠陥 24 件 — PayPal の二重キャプチャ、`querykey` のタイプミス | 🕰️ |
| 43 | [データロード戦略](43-loading-strategy-review.md) | loader · React Query · useEffect を 1 つの基準で | 🕰️ |
| 28 | [フロントエンドテストの導入](28-frontend-test-suite.md) | 導入直後に捕まったバグ 2 件 | 🕰️ |
| 04 | [フロントエンドの問題分析](04-frontend-issues.md) | トークンの保存 · リフレッシュキュー · ログ漏洩 | 🕰️ |
| 05 | [フロントエンドの改善](05-frontend-improvements.md) | 04 に対する修正 | 🕰️ |

## 11. テスト

| # | ドキュメント | 内容 | 状態 |
|---|---|---|---|
| 17 | [テスト戦略](17-testing.md) | 単体と Testcontainers の分離、設定のガードレール | 🟡 |
| 14 | [実際の起動テスト](14-boot-test-findings.md) | 新しいクローンで 0/8 起動 — 起動不能な欠陥の発見と修正 | 🕰️ |
| 10 | [品質のハードニング](10-quality-hardening.md) | 主要経路のテスト · OpenAPI · HPA/PDB | 🕰️ |

---

その他のドキュメント: [運用スクリプト](../ops/README.md) · [k8s マニフェスト](../k8s/README.md) · [フロントエンド](../frontend/README.md)
