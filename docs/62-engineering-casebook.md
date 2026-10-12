# 62. 技術事例集 — 問題 · 対応 · 限界、そして参考にした本

**日本語** | [한국어](62-engineering-casebook.ko.md)

> 作成日: 2026-09-27
>
> このリポジトリで直した問題のうち **技術的に重要なもの** を選び、事例ごとに次のことを書きました。
> - 基準にできる **オライリー（O'Reilly）の本1冊**
> - **問題**、**対応した内容**、**限界と改善点**
> - 詳しい根拠ドキュメント
>
> 📖 **本について。** 本は「こうしろと書いてあったからそうした」という意味ではありません。**同じ問題を一般化して説明した参考書** という意味です。
> 章番号は版によって異なるため、番号に確信が持てる本だけ番号を書き、残りはテーマで書きました。
>
> ⚠️ **限界** は、このドキュメントを書いた時点でコードと設定から確認したものです。リポジトリ全体の未解決項目のリストは [docs/54 §10](54-improvements-vs-practice.md) です。

## 目次

| # | 領域 | 事例 | 参考書 |
|---|---|---|---|
| 1 | 決済 | サーバーがブラウザの「決済した」を信じた | *Web Application Security* |
| 2 | 並行性 | 最後の1個を2人が決済した | *Designing Data-Intensive Applications* |
| 3 | メッセージング | レプリカを増やしたらイベントが2回ずつ出た | *Designing Data-Intensive Applications* |
| 4 | メッセージング | 1件の失敗がバッチを止め、再送が在庫を2回減らした | *Kafka: The Definitive Guide* |
| 5 | 分散トランザクション | 決済の後が空っぽだった Saga、そして12秒の遅延 | *Building Microservices* |
| 6 | レジリエンス | 設定したと思っていたタイムアウトが適用されていなかった | *Building Microservices* |
| 7 | 設定 | コンパイルは通るのに起動すると落ちる設定の欠陥29件 | *The Site Reliability Workbook* |
| 8 | 認可 | 身元ヘッダーの偽造と IDOR — クライアントが選んだ値を信じた | *Building Microservices* |
| 9 | ネットワークの信頼 | クラスター内なら誰でも内部 API を呼べた | *Zero Trust Networks* |
| 10 | シークレット | git 履歴に2年間残っていた資格情報 | *Building Secure and Reliable Systems* |
| 11 | 認証 | ソーシャルログイン — 有効にした瞬間に失敗していたはずの Google、そして Kakao・LINE | *Identity and Data Security for Web Development* |
| 12 | スキーマ進化 | ローリングデプロイ中に古いコードと新しいコードが同じ DB・トピックを使う | *Designing Data-Intensive Applications* |
| 13 | デプロイ | テストしたものとデプロイしたものが別のバイナリだった | *Building Microservices* |
| 14 | データ保護 | バックアップはあると信じていたが、復元してみたことがなかった | *Site Reliability Engineering* |
| 15 | 可観測性 | アラートがどこにも届かず、すべてのサービスが自分を `common` と名乗っていた | *Observability Engineering* |
| 16 | テスト | モック（mock）テストは通ったのに、本番で保存・参照が壊れた | *Software Engineering at Google* |
| 17 | フロントエンド | いいねが1から0に — 19桁の id を JS が丸めた | *JavaScript: The Definitive Guide* |
| 18 | データモデル | コンパイルも通りテストも通るのに、データが間違っていた — モデル層の点検 | *Designing Data-Intensive Applications* |

---

## 1. サーバーがブラウザの「決済した」を信じた

**📖 参考書** — Andrew Hoffman, *Web Application Security*, 2nd ed. (O'Reilly, 2024). クライアントから来た値は信頼境界の外にあるという原則と、ビジネスロジックの欠陥を扱います。

**問題**
- 決済完了リクエストに含まれる決済 id と金額をサーバーがそのまま受け取り、注文を `PAID` に変えていました。
- ログインさえすれば、決済なしで注文を完了できました。
- 同じ決済 id を別の注文に使い回すことも防げていませんでした。

**対応した内容**
- サーバーが決済事業者の API を改めて照会します。状態、金額、通貨を **DB に保存された注文金額** と照合します。
- `(payment_provider, provider_payment_id)` のユニーク制約で決済 id の再利用を防ぎます。
- 設定が空なら決済を拒否します（fail closed）。
- 決済手段を1つのポートとして切り出し、共通ルールを1か所に集めました。手段は PayPal · Stripe · PortOne V1/V2 · 銀行振込 · バーチャル口座です。
- 部分返金は決済事業者側の残高照合（`checksum` / `currentCancellableAmount`）で、同じ返金が2回行われないようにしました。

**限界**
- 実際の API で確認したのは Stripe だけです（本番での決済と返金）。
- PayPal と PortOne はレスポンスフィールドのマッピングがドキュメント基準なので、形が違えば決済確認がすべて拒否されます。fail closed なのでお金が漏れることはありません。
- Stripe Checkout は決済手段が `card` に固定されています。

**改善点**
- 決済事業者のサンドボックスで回る契約テストを CI の受け入れステージに置きます。
- Webhook の再送と順序の入れ替わりを再現するテストを追加します。

→ [docs/35](35-payment-verification.md) · [docs/49](49-payment-providers.md)

---

## 2. 最後の1個を2人が決済した

**📖 参考書** — Martin Kleppmann, *Designing Data-Intensive Applications* (O'Reilly, 2017), 第7章 "Transactions"。書き込みスキュー（write skew）、ファントム、そして「競合の実体化（materializing conflicts）」によってロック対象を作る方法を扱います。

**問題**
- 決済から在庫減算までの間に在庫を押さえておくものがありませんでした。
- 2人が最後の1個を同時に決済すると両方とも成功し、遅いほうは後になって不足と判明しました。
- 減算が足りないと `null` で静かに終わり、注文は `PAID` のままでした。販売数は数量確認の前に増えていました。

**対応した内容**
- **予防:** 注文作成時に30分の **予約行** を作ります。本でいう「競合の実体化」です。
  - 残数は、実物から他の人の有効な予約を引いた値です。
  - すべて確保するか、1つも確保しないかです。SKU をソートしてロックし、デッドロックを避けます。
  - 決済による減算と同じトランザクションで予約を確定します。
  - 期限切れはジョブが解放します。銀行振込は入金期限まで延長します。
- **補償:** それでも足りなければ、SKU ロックのもとである分だけを割り当てます。不足分は同じトランザクションの Outbox で知らせ、order が自動で部分返金します。銀行振込は手動返金に回します。

**限界**
- catalog に届かなければ予約なしで進みます（fail-open）。そのときは補償側に頼ります。
- 予約が期限切れになった後に入ってきた決済とバーチャル口座の入金は、再び不足する可能性があります。

**改善点**
- 予約の期限切れ直前の決済を防ぐか延長するルールを設けます。
- fail-open の回数をメトリクスにしてアラートを出します。

→ [docs/59](59-stock-reservation.md) · [docs/53](53-restock-and-sold-out.md)

---

## 3. レプリカを増やしたらイベントが2回ずつ出た

**📖 参考書** — *Designing Data-Intensive Applications*, 第11章 "Stream Processing"。二重書き込み（dual writes）の危険と、DB の変更をログとして送り出す方式（CDC）を扱います。

**問題**
- Outbox ポーラーがレプリカごとに同じ行を読んで同じイベントを発行していました。並行トランザクションのテストで再現し、重複は 100% でした。
- 決済の後 `PAID` になるまで約12秒かかっていました。Saga が遅いのではなく、**10秒のポーラーを2回** またいでいました。
- Outbox 行の `created_at` が `Asia/Seoul` でハードコードされていました。この値がパーティションキーなので、月の境目で行が別のパーティションに入っていました。

**対応した内容**
- `FOR UPDATE SKIP LOCKED` で行を先取りします。
- コミット直後にリレーを1回回します。シングルスレッドで、実行中に来たリクエストはまとめます。ポーラーはセーフティネットとして残しました。
- 時刻は UTC に統一し、回帰テストを付けました。

**限界**
- ~~Outbox の月次パーティションは 2027-01 までしか事前に作られておらず、自動で作る仕組みがありません。~~ → ✅ 2026-09-27 にパーティションジョブを入れました（下記）。
- Debezium コネクターの設定はリポジトリにありますが、本番ではポーリングリレーを使っています。

**改善点**
- ✅ **パーティションジョブ**（common `OutboxPartitionAutoConfiguration`）：6 時間ごとに、今月から 3 か月先までの足りないパーティションを作ります。
  - DEFAULT にその月の行がすでに入っていても作れます。空のテーブルを作り、行を移してから `ATTACH` します。そのまま `PARTITION OF` で作ると `updated partition constraint for default partition would be violated` で失敗します。
  - レプリカが複数あっても `pg_advisory_xact_lock` で 1 回だけ作ります。
  - メトリクス `outbox_partition_months_ahead` · `outbox_partition_default_rows` とアラート 3 件を置きました。設定の書き忘れはガード B-70 が捕まえます。
  - ⚠️ 2026-10-12 の点検で **3 件のアラートが一度も鳴り得なかった**ことが分かりました。メトリクスが `@ConditionalOnBean(MeterRegistry)` で条件付けされていましたが、この自動設定がアクチュエータのメトリクス自動設定より先に(アルファベット順)評価され、常に外れていました。さらに `months_ahead` は点検に成功したときの値をそのまま出しており、成功すれば常に 3、失敗すれば最後の値で止まり、一度も成功しなければ NaN でした。今は `MeterBinder` で登録し、残り月数は現在時刻を基準に毎回計算します(ジョブが止まると月ごとに 1 ずつ減る)。`last_success_seconds`(初期値は起動時刻)と指標なし(`absent`)のアラートを加え、`Error` 一回でジョブが永久に止まっていた点も防ぎました(`OutboxPartitionMetricsTest` · `OutboxPartitionMaintainerTest`)。
  - 実際の PostgreSQL 16 で検証しました（`OutboxPartitionMaintainerPostgresTest`）。
- 残り：古いパーティションの廃棄（保持ポリシー）は人が行います。
- トラフィックが増えたら CDC への移行を検討します。

→ [docs/21](21-horizontal-scaling.md) · [docs/56](56-saga-latency.md) · [docs/09](09-postgresql-partitioning-sharding.md)

---

## 4. 1件の失敗がバッチを止め、再送が在庫を2回減らした

**📖 参考書** — Gwen Shapira ほか, *Kafka: The Definitive Guide*, 2nd ed. (O'Reilly, 2021). コンシューマーのオフセットコミット、再処理、厳密に1回（exactly-once）の範囲を扱います。

**問題**
- バッチリスナーで1つのレコードが失敗すると、バッチ全体がリトライされていました。毒入りのメッセージ1つがパーティションを止めていました。
- 再送された `product-update` は在庫をもう一度減らしていました。コンシューマーが冪等ではありませんでした。

**対応した内容**
- `BatchListenerFailedException` で失敗したレコードだけを DLT に隔離します。DLT の蓄積量と lag にアラートをかけました。
- イベント id に `processed_event` 台帳を設け、同じイベントを2回反映しないようにしました。
- トレースコンテキストが Kafka をまたいで引き継がれるようにしました。

**限界**
- 冪等台帳は **catalog と coupon にしか** ありません。他のコンシューマーは状態遷移の条件（すでに PAID なら無視など）に頼っています。

**改善点**
- 副作用のあるすべてのコンシューマーに台帳か条件付き更新を適用し、それをガードテストで強制します。

→ [docs/11](11-observability-and-kafka-reliability.md) · [docs/12](12-dlq-rollout-idempotency-alerts.md)

---

## 5. 決済の後が空っぽだった Saga、そして12秒の遅延

**📖 参考書** — Sam Newman, *Building Microservices*, 2nd ed. (O'Reilly, 2021), 第6章 "Workflow"。Saga、オーケストレーションとコレオグラフィ、補償トランザクションを扱います。

**問題**
- 注文は `PAID` から先に進めませんでした。配送、購入確定、返品がありませんでした。
- ユーザーのアクセストークンが Saga のメッセージと Outbox 行に載って運ばれていました。

**対応した内容**
- order をオーケストレーターとして Saga をつなぎました: 決済 → 在庫 → 配送（delivery-service）→ 購入確定。
- 補償はクーポン復元、カート復元、部分返金です。
- トークンはすべての Saga メッセージから外し、すでに溜まっていた Outbox 行からも消しました（V15）。
- saga-projection（Kafka Streams）で進行状況を照会できるようにしました。

**限界**
- delivery-service には Outbox がありません。
- `TrackingPoller.poll` は1つのトランザクションの中で外部の配送業者を呼び出します。

**改善点**
- delivery の発行を Outbox に移します。
- ポーリングを「照会（トランザクション外）→ 反映（短いトランザクション）」に分けます。

→ [docs/25](25-delivery-service-design.md) · [docs/37](37-transaction-boundary-audit.md) · [docs/56](56-saga-latency.md)

---

## 6. 設定したと思っていたタイムアウトが適用されていなかった

**📖 参考書** — *Building Microservices*, 2nd ed., 第12章 "Resiliency"。タイムアウト、リトライ、サーキットブレーカー、バルクヘッドを扱います。

**問題**
- Feign のタイムアウトを設定しましたが、Spring Cloud 4 でプロパティの接頭辞が変わっていたため **一度も適用されていませんでした。** 実際にはデフォルト値（接続10秒 · 読み取り60秒）で動いていました。
- 12個のモジュールのうち9個で、トランザクションの中で外部呼び出しをしていました。

**対応した内容**
- 新しい接頭辞に移し、プロパティが実際にバインドされるかを確認するテスト（`FeignTimeoutBindingTest`）を置きました。
- トランザクション境界を点検して推奨の順序を決め、優先度の高いものからトランザクションの外に移しました。
- 同種の落とし穴（Gateway 4.3 の `spring.cloud.gateway.server.webflux` への移動）もガードテストで防ぎました。

**限界**
- **Feign のサーキットブレーカーが適用されていません**（`spring.cloud.openfeign.circuitbreaker` なし）。ゲートウェイのルートにだけあります。
- `CartService.saveCart` は依然として `@Transactional` の中で Feign を呼んでいます。

**改善点**
- Feign に Resilience4j のサーキットブレーカーとフォールバックを付けます。
- 残っているトランザクション内の呼び出しを [docs/37](37-transaction-boundary-audit.md) の順序どおりに外します。

→ [docs/37](37-transaction-boundary-audit.md)

---

## 7. コンパイルは通るのに起動すると落ちる設定の欠陥29件

**📖 参考書** — Betsy Beyer ほか, *The Site Reliability Workbook* (O'Reilly, 2018), 第14章 "Configuration Design and Best Practices" と第15章 "Configuration Specifics"。設定もコードと同じように検証すべきだという観点です。

**問題**
- 新しくクローンすると、サービスが1つも起動しませんでした（0 / 8）。例:
  - プロファイルファイル内の `spring.profiles.active`
  - PostgreSQL に MySQL Dialect
  - 原作者の PC のパス（`file:E:/…`）
  - `.p12` を `JKS` と宣言
  - 共有設定の `spring.application.name` のせいで、すべてのサービスが自分を `common` と報告
- テストは設定を読まないので、すべて通っていました。

**対応した内容**
- **設定ガードレールテスト（B-1 ~ B-69）** を作りました。設定ファイル、マニフェスト、コードを読んで、ルール違反をビルドで捕まえます。例:
  - 元に戻せないマイグレーションなのに `ROLLBACK-UNSAFE` の宣言がないもの
  - Feign が呼ぶ内部パスが mTLS のルールにないもの
  - 証明書の更新後の再起動アノテーションがないもの
- ガードごとに **元に戻して失敗するか** を確認しました。

**限界**
- 静的検査なので、実際のクラスターの値（Secret、オーバーレイ）は見られません。
- ルールが1つのファイル（`ConfigurationGuardrailTest`）に集まって大きくなりました。

**改善点**
- デプロイ直前にレンダリングしたマニフェスト（`kustomize build`）を同じルールで検査します。
- ルールを領域ごとのファイルに分けます。

→ [docs/14](14-boot-test-findings.md) · [docs/17](17-testing.md)

---

## 8. 身元ヘッダーの偽造と IDOR — クライアントが選んだ値を信じた

**📖 参考書** — *Building Microservices*, 2nd ed., 第11章 "Security"。ゲートウェイの背後の暗黙の信頼と「混乱した代理人（confused deputy）」問題を扱います。

**問題**
- クライアントが `User-Email` のような身元ヘッダーを直接送ると、サービスがそのまま信じていました。
- 4つのサービスで、リクエスト本文の id と email を使って他人の注文、カート、クーポン、配送先にアクセスできました（IDOR）。
- レートリミットのキーも、クライアントが変えられる `X-Forwarded-For` でした。

**対応した内容**
- ゲートウェイがクライアントの身元ヘッダーを **消し**、検証したトークンから書き直します。
- 身元は `User-Id`（トークンの uid）からだけ得ます。本文の身元フィールドはなくしました。
- 所有権の判定を1か所に集め、「他人のものをリクエストしたら拒否」を回帰テストで固定しました。
- ユーザー列挙を防ぐため、ログイン失敗の文言を1つに統一しました。アカウントロック（5回 / 15分）と 2FA はサーバーが強制します。
- 認証クッキーを `SameSite=Lax` と明示しました。クッキーで身元を運ぶリクエスト（リフレッシュのローテーション、2FA 確認、ログアウト）はすべて POST なので、他サイトから始まったリクエストにはクッキーが載りません。アクセストークンは `Authorization` ヘッダーで運ぶので CSRF の対象ではありません。以前は属性を書かずブラウザの既定値に任せていました（Safari・Firefox は None 扱い）。`Set-Cookie` ヘッダーをテストで固定しました（`CookieSameSiteTest`）。

**限界**
- `SameSite` は **サイト** 単位なので、同じドメインのサブドメインから来るリクエストは防げません。今はサブドメインを別の用途に使っていないので問題ありません。

**改善点**
- サブドメインを増やすことになれば、クッキーを使う POST に `Origin` ヘッダーの検査を加えます。

→ [docs/27](27-security-review.md) · [docs/41](41-api-security-applied.md) · [docs/54 §1](54-improvements-vs-practice.md)

---

## 9. クラスター内なら誰でも内部 API を呼べた

**📖 参考書** — Razi Rais ほか, *Zero Trust Networks*, 2nd ed. (O'Reilly, 2024). ネットワーク上の位置ではなく身元で判定し、ワークロードごとに資格情報を持たせるという原則です。

**問題**
- 内部専用 API は「身元ヘッダーがなければ内部呼び出し」と判定していました。クラスター内の Pod を1つ乗っ取るだけで、すべて呼べました。
- NetworkPolicy は 0 個でした。
- ゲートウェイの自動ルート（`/{SERVICE-ID}/**`）には JWT フィルターがありませんでした。

**対応した内容**
- サービス間の **mTLS** を導入しました。サービスごとに証明書を別々に持たせます（CN = サービス名、cert-manager が90日の寿命で自動更新し、Reloader が再起動）。
- 受け取る側の `InternalCallerFilter` が証明書で呼び出し元を判定します。観察モードで回してみた後、強制モードに切り替えました。
- パスごとの許可リスト（`INTERNAL_AUTH_PATH_RULES`）で「どのサービスがどの内部パスを呼べるか」まで見ます。これも観察段階を経て強制モードに切り替え、Feign・gRPC の呼び出しとルールが合っているかはガード（B-67・B-73）がビルドで照合します。
- NetworkPolicy はデフォルト拒否です。自動ルートは無効にしました。外部公開は Cloudflare Tunnel で行い、インバウンドポートは 0 個です。

**限界**
- サーバー証明書（`tls-keystore`）は依然としてサービス間で共有しており、手で管理しています。
- NetworkPolicy は受信側（ingress）だけで、送信側（egress）のルールがありません。
- Prometheus の収集が TLS 検証を省略しています。

**改善点**
- サーバー証明書も cert-manager に移し、egress の許可リストと Prometheus の CA 信頼を追加します。

→ [docs/57 §16·§18](57-remaining-priorities.md) · [docs/18](18-certificates.md)

---

## 10. git 履歴に2年間残っていた資格情報

**📖 参考書** — Heather Adkins ほか, *Building Secure and Reliable Systems* (Google/O'Reilly, 2020). シークレット管理、鍵のローテーション、侵害を前提とした復旧設計を扱います。

**問題**
- 公開リポジトリの履歴に AWS キー、Gmail のアプリパスワード、OAuth シークレット、DB パスワード（コネクター JSON 4つ）が残っていました。
- 履歴を消しても、フォーク、PR の参照（`refs/pull/*`）、キャッシュに残ります。

**対応した内容**
- **失効と再発行** を先に行いました。履歴の整理はその後でした。
- 失効の確認は値を比較するのではなく、**実際にログインして通らないかどうか** で行いました（`ops/34`）。
- シークレットは gitignore された Secret でだけ扱います。CI でコミット範囲をシークレットスキャンし、スクリプトは値を出力しないようにしました（長さや接頭辞だけ）。

**限界**
- GitHub Push Protection はリポジトリの設定次第です（コードで強制できません）。
- シークレットが k8s Secret（base64）に平文と同じ形で置かれています。

**改善点**
- Sealed Secrets、SOPS、または外部 KMS に移します。
- 定期的なローテーションを自動化します。

→ [docs/30](30-git-history-secret-exposure.md)

---

## 11. ソーシャルログイン — 有効にした瞬間に失敗していたはずの Google、そして Kakao・LINE

**📖 参考書** — Jonathan LeBlanc · Tim Messerschmidt, *Identity and Data Security for Web Development* (O'Reilly, 2016). OAuth 2.0 と OpenID Connect のフローとトークン検証を扱います。

**問題**
- 最初のコードは、クライアント id とシークレットが `"아이디"` / `"비밀번호"` でハードコードされていて、コールバックアドレスは localhost でした。
- 成功ハンドラーは `"google 12345"` をメールアドレスとして照会していたため、すべてのログインが `user_not_found` で終わっていました。
- それを直した後も、Google は動作しようがありませんでした。
  - Google の登録は `openid` scope なので **OIDC 経路** に進みます。
  - ところが ID トークン検証ライブラリ（`spring-security-oauth2-jose`）がなかったため、OIDC の処理器が登録されていませんでした。
  - OIDC 用のユーザーサービスも設定されていなかったため、アカウント連携のコードが呼ばれませんでした。
  - 本番で Google を有効にしたことがなかったため、表面化しませんでした。

**対応した内容**
- 資格情報のあるプロバイダーだけを登録し、ログイン画面はサーバーが返したリストどおりにボタンを描きます。
- PKCE をすべての登録に適用し、認可リクエストは Redis に保管します（`code_verifier` がブラウザに出ないように）。
- アカウント連携は **検証済みのメールアドレス** でだけ行います。Google は `email_verified`、GitHub は `/user/emails`、Kakao は `is_email_valid` と `is_email_verified` を確認します。
- OIDC 経路（`CustomOidcUserService`）が Spring の ID トークン検証を経た後、OAuth2 経路と **同じ連携ルール** を使います。
- LINE は ID トークンが ES256 なので、登録ごとにアルゴリズムを選びます。
- 2FA のアカウントは、ソーシャルログインの後にも 2FA を経ます（別の鍵で署名した保留トークン）。
- 失敗したら、許可した理由コードだけを持ってログイン画面に戻ります。

**限界**
- Google、Kakao、LINE のいずれも **実際のプロバイダーで最後まで試したことがありません。** 本番の Secret にまだ資格情報がありません。
- LINE はチャネルのメール権限が承認されないとログインできません。

**改善点**
- プロバイダーごとに本番で一度ずつ最後までログインしてみて、受け入れチェックリスト（[docs/34](34-acceptance-checklist.md)）に入れます。
- ソーシャルログインの失敗コード別のメトリクスを置きます。

→ [docs/33 ソーシャルログイン](33-deploy-runbook.md) · [docs/27](27-security-review.md)

---

## 12. ローリングデプロイ中に古いコードと新しいコードが同じ DB・トピックを使う

**📖 参考書** — *Designing Data-Intensive Applications*, 第4章 "Encoding and Evolution"。前方・後方互換性とスキーマ進化（Avro）を扱います。

**問題**
- 身元をメールアドレスから uid に変え、古い決済カラムを消し、決済メッセージのスキーマでフィールドを追加したり削除したりする必要がありました。
- ローリングデプロイ中は、古い Pod と新しい Pod が **同じ DB、同じトピック、同じ API** を同時に使います。一度に変えると、どちらか一方が壊れます。

**対応した内容**
- expand-contract で複数のリリースに分けました（デプロイトレイン R1~R5）。
  - 追加するときは「読み取りは新しいスキーマ、書き込みは古いスキーマ」から始めます。
  - 削除するときは「次のスキーマを先に」デプロイします。
- マイグレーションが自ら止まるようにしました。データガード、Flyway 履歴の時刻ガード、リリースマーカーを置きます。
- 元に戻せないマイグレーションは `ROLLBACK-UNSAFE` と宣言させるようにしました（B-54）。`ops/28` はそれをまたぐロールバックを拒否します。

**限界**
- 決済スキーマ移行の最終段階（C3 / R5）が残っています。古い Avro v1・v2 の履歴があり、`providerOf` の互換コードがそのままです。

**改善点**
- スキーマレジストリの互換性モードを CI で検査します。
- 残りの段階を終えて互換コードを消します。

→ [docs/55](55-zero-downtime-migrations.md) · [docs/20](20-schema-ownership.md)

---

## 13. テストしたものとデプロイしたものが別のバイナリだった

**📖 参考書** — *Building Microservices*, 2nd ed., 第7章 "Build" と第8章 "Deployment"。成果物を一度だけ作り、環境ごとに同じものをデプロイする原則を扱います。

**問題**
- テスト用とデプロイ用のイメージを別々にビルドしていました。
- イメージタグは人が選んだ数字で、デプロイとロールバックは人の記憶に頼っていました。

**対応した内容**
- コミットステージでイメージを **一度だけ** ビルドします（amd64 · arm64、タグ = コミット SHA）。同じイメージで統合 · 受け入れテストを回します。
- デプロイはスクリプト1つ（`ops/28-deploy.sh <環境> <バージョン>`）で行います。
  - イメージの存在を確認し、元に戻せないマイグレーションを検査します。
  - スモークゲートが失敗したら直前のバージョンに自動で戻し、デプロイ履歴を残します。
- パイプラインが緑なら、**人が承認した後に** 本番ノードのセルフホストランナーが同じスクリプトを実行します。

**限界**
- シングルノードなので、カナリアやブルーグリーンがありません。本番オーバーレイは HPA を適用していません。
- セルフホストランナーは本番の資格情報を持ちます。そのためリポジトリは非公開でなければなりません。

**改善点**
- ノードを 2 台に増やし、トラフィックの一部を新バージョンに送るカナリアを置きます。
- デプロイ後のメトリクス（エラー率 · レイテンシ）で自動判定します。

→ [docs/36](36-deployment-pipeline.md)

---

## 14. バックアップはあると信じていたが、復元してみたことがなかった

**📖 参考書** — Betsy Beyer ほか, *Site Reliability Engineering* (O'Reilly, 2016), 第26章 "Data Integrity: What You Read Is What You Wrote"。「必要なのはバックアップではなく復元である」という観点です。

**問題**
- バックアップがなく、設計ドキュメントもありませんでした。
- バックアップスクリプトを作った後も cron が設定されていませんでした。

**対応した内容**
- データの等級ごとに RPO / RTO を決めました。
- PostgreSQL の PITR と MongoDB のバックアップを置き、**実際に `DROP TABLE` を実行した後、ポイントインタイムリカバリで戻して** 検証しました。
- バックアップの CronJob と週次の復元訓練ジョブを作りました。

**限界**
- バックアップがクラスター内の PVC にあります。クラスターが消えればバックアップも消えます。
- `postgres-backup` と `restore-drill` はデフォルト値が `suspend: true` なので、環境ごとに人が有効にしなければなりません。

**改善点**（このリポジトリで最も後回しにした項目です）
- オブジェクトストレージに送ります。
- 最後に成功したバックアップの時刻にアラートをかけます。

→ [docs/15](15-backup-and-dr.md) · [docs/22](22-alerting-and-backup-automation.md)

---

## 15. アラートがどこにも届かず、すべてのサービスが自分を `common` と名乗っていた

**📖 参考書** — Charity Majors ほか, *Observability Engineering* (O'Reilly, 2022). トレースとログの相関、そして「アラートが実際に届くか」を扱います。

**問題**
- Alertmanager の受信チャンネルがプレースホルダーだったため、アラートが **どこにも届いていませんでした。**
- 共有設定の `spring.application.name` のせいで、すべてのサービスが自分を `common` と報告し、分散トレーシングが無力でした。
- SMS のログ送信器が **携帯電話の認証番号を平文で** 本番ログに残していました。
- トレース（Zipkin）には、ゲートウェイ · Feign のスパンの **URL クエリ（OAuth の `code` · 認証リンクの `token`）がそのまま** 保存されていました。

**対応した内容**
- アラートは「受け付けられた」ではなく「届けられた」をメトリクスで確認します（`ops/17`）。
- サービス名を正しました。Kafka をまたいでもトレースが引き継がれるようにし、Loki に集めたログの traceId からトレースへ行き来できるようにしました。
- トレースを OpenTelemetry デモの構成（OTLP → Collector → Jaeger · span_metrics）に移しました。Collector が **保存前に** クエリ · JWT · メールをマスクし、トレースから RED メトリクスを作り、レイテンシグラフの点（exemplar）からそのトレースへ飛べます。サービスのコードはベンダー中立 API だけを使っていたので変わっていません（[docs/63](63-opentelemetry.md)）。
- サンプリングは Collector がトレースの終了 **後に** 行います（テールサンプリング）— エラー · 遅いリクエスト · 注文/決済/配送はすべて、残りは 10%。RED メトリクスはサンプリングの **前** で数えるので、比率に関係なく正確です。トレースはファイル（badger）に 7 日残ります。
- マスキングは呼び出し側の裁量ではなく **出力段階** で行います。認証番号はログに残しません。

**限界**
- サンプリング決定（30 秒）の後に起きた非同期エラーはトレースに残りません（ログ · メトリクスには残ります）。
- トレースの保存（badger）とテールサンプリングはノード1台を前提にしています。
- ログベースの ERROR 急増アラートがありません。
- 可観測性スタックがノードの compose で動いています。

**改善点**
- ノードが増えたら、トレース id ベースの loadbalancing エクスポーター + OpenSearch/ClickHouse 保存に移します。
- SLO ベースのアラート（エラーバジェットの消費率）に移行します。

→ [docs/11](11-observability-and-kafka-reliability.md) · [docs/22](22-alerting-and-backup-automation.md) · [docs/51](51-logging.md) · [docs/63](63-opentelemetry.md)

---

## 16. モック（mock）テストは通ったのに、本番で保存・参照が壊れた

**📖 参考書** — Titus Winters ほか, *Software Engineering at Google* (O'Reilly, 2020), 第13章 "Test Doubles"。「可能なら実際の実装を、次にフェイクを」という原則です。

**問題**
- **カートが保存されていませんでした。** 新しい行を `merge` で付けていましたが、PERSIST cascade は `merge` には適用されません。
- **レビューは保存されるのに読めませんでした。** エンティティにデフォルトコンストラクタがありませんでした。
- どちらもリポジトリをモックに置き換えたテストでは通っていました。

**対応した内容**
- リクエストごとに別のトランザクションを開き、次のリクエストで読み直す **実際の JPA テスト**（H2）を追加しました。
- デフォルトコンストラクタはガード（B-69）で強制します。
- 新サービスのチェックリストに「実際の DB で回る JPA テストを1つ以上」を入れました。

**限界**
- H2 は PostgreSQL ではありません。部分インデックス、`SKIP LOCKED`、jsonb のような方言の違いは、Testcontainers の統合テスト（CI）でしか捕まりません。

**改善点**
- リポジトリ層のテストを Testcontainers の PostgreSQL に移します。速度のために実行周期を分けます。

→ [docs/61 §1](61-adding-a-service.md) · [docs/17](17-testing.md)

---

## 17. いいねが1から0に — 19桁の id を JS が丸めた

**📖 参考書** — David Flanagan, *JavaScript: The Definitive Guide*, 7th ed. (O'Reilly, 2020), 第3章 "Types, Values, and Variables"。JS の数値は IEEE 754 倍精度なので、2^53 を超える整数は正確に表現されません。

**問題**
- レビューの id は TSID（18~19桁の `Long`）です。JSON の数値として送り出すと、ブラウザが末尾の桁を丸めていました。
- そのため、いいねのリクエストが **存在しない id** に送られ、画面の 1 が 0 に戻っていました。

**対応した内容**
- ブラウザに送る TSID は `ToStringSerializer` で文字列として送ります。
- 新サービスのチェックリストにルールとして入れました。

**限界**
- すべてのレスポンス DTO を全数調査したわけではありません。

**改善点**
- `Long` の id フィールドを持つレスポンス DTO を探し、文字列シリアライズを強制するガードを置きます（TSID の全数作業）。

→ [docs/61 §1-11](61-adding-a-service.md)

---

## 18. コンパイルも通りテストも通るのに、データが間違っていた — モデル層の点検

**📖 参考書** — Martin Kleppmann, *Designing Data-Intensive Applications* (O'Reilly, 2017), 第7章 "Transactions"。読んで・直して・書く 2 つのトランザクションの間で起きる **更新の消失（lost update）** と、それをアトミックな書き込み・条件付き更新で防ぐ話です。

**問題** — モデル（エンティティ）フォルダをサービスごとに読み直すと、エラーを出さずに **間違ったデータ** を作る欠陥が出てきました。
- **会員登録が DB で拒否されていました。** `User` の手書きコンストラクタが `@Builder.Default` の初期値（ランクなど）を飛ばし、NOT NULL に違反していました。Lombok は初期値をビルダーとデフォルトコンストラクタにしか移しません。
- **クーポンが「使用済み」にならず、再利用できました。** 決済応答のコンシューマーがヘッダー条件に引っかかり、状態変更まで届いていませんでした。
- **`BaseEntity` の `@Data`** が equals を監査フィールドで作り、同じ瞬間に作った詳細行が `Set` の中で 1 つにまとめられていました。
- **商品 Q&A が保存されず**（cascade が MERGE のみ）、**バリエーション登録は常に 500** でした（orphanRemoval のコレクションを丸ごと差し替え）。
- **既定の配送先の変更** が部分ユニークインデックスに引っかかり（Hibernate の flush 順序）、**期限切れトークンの掃除** は `Date` を `Instant` フィールドに渡して拒否され、**長いレビュー** は B-tree の行上限（2,704 バイト）を超えていました。
- **相談の担当割り当てが消えていました。** 顧客のメッセージがスレッドを読んだ後に担当者が条件付き UPDATE で引き受けると、メッセージ側のコミットが **全列** を読んだ時点の値（待機中・担当者なし）で書き戻していました。チャットの時刻は UTC の JVM で 9 時間早く、相談の本文がログに残っていました。
- 3 行目以降は **H2・モックのテストでは再現しませんでした。**

**対応した内容**
- コンストラクタは `this()` から始め、エンティティは `@Getter @Setter`（識別子による同値性）、コレクションは中身だけを入れ替え（`clear + addAll`）、cascade は `{PERSIST, MERGE}`。
- 割引価格は `common` の `Discount` 1 つで計算します。決済・返金の時刻はエンティティ自身が記録します。
- `SupportThread` に `@DynamicUpdate` — 変更した列だけを書きます。割り当て自体は元から条件付き UPDATE なので、同時割り当ては既に防げていました。
- **ガード** `EntityLombokGuardTest`（common）— 全サービスでエンティティの `@Data` を禁止し、`@Builder.Default` を持つエンティティのコンストラクタが `this()` から始まるかを確認します。
- 再現は **実際の PostgreSQL**（Testcontainers）で行いました。修正を元に戻すと失敗することを確認しています（`expected ACTIVE but was WAITING`、重複キー、`index row size 6024 exceeds … 2704` など）。

**2 次 — スキーマ変更・データ移行が必要で後回しにしたもの**(マイグレーションはすべて実際の PostgreSQL・MongoDB で確認)
- **カートがユーザーごとに二つ** — 初回の追加が同時に来ると両方が作り、以後そのユーザーのカート取得が常に例外でした。cart V5 が最新の一つにまとめ(無い行だけ移す)`user_id` をユニークに、作成は `INSERT … ON CONFLICT DO NOTHING`。行は SKU の**一覧上の位置**ではなく**色 id** でバリエーションを探します。
- **退会会員の名前が復活** — user-updated と user-deleted は別トピックで同時に処理されます。イベント処理は会員行を `SELECT … FOR UPDATE` で読みます。
- **チャット参加の重複** — chat V9 (ルーム, 会員)ユニーク + `ON CONFLICT DO NOTHING`。
- **会話のインデックス** — クエリと**同じ collation**(numericOrdering)の `{roomId, _id:-1}`。起動中の自動作成は大きなコレクションで起動を止めるので、バックグラウンドで作ります(explain で IXSCAN を確認)。
- **旧メッセージ時刻 +9 時間** — 修正版が最初に起動した時刻を基準として Mongo に一度記録し、それより前の文書を移しながら `tzFixed` を付けます(何度実行しても同じ)。ローリング中に旧 Pod が書いた文書は 15 分後の 2 回目の実行が拾います。
- **お知らせ本文がラージオブジェクト** — `@Lob` のせいで列には OID の番号だけ(実測 `16698`)、編集のたびにラージオブジェクトが捨てられていました。catalog V18 が本文へ移して解放します(**ROLLBACK-UNSAFE** — このデプロイでは自動ロールバックが無効になります)。
- **EAGER** — リポジトリ全体で最後の二つの to-one(商品 ↔ 下位カテゴリの連結)を LAZY + `@BatchSize` に。実測: 一覧の SQL は EAGER 6、LAZY のみ 11、LAZY + バッチ 7。
- **注文 ↔ 配送先の二重外部キー** — 注文 → 住所の一方向(order V25、expand)。**「税金 無料」表示** — 常に 0 の `taxPrice` を削除し「税込み」。**決済検索のインデックス** — pay V7 `tracking_id`。
- 整理: Redis のルームのコピー・Alarm エンティティ・`CouponStatus.EXPIRED`・全文コメントの `OrderService_RedisTemplate`。(`OrderCacheService` はデッドコードでは**ありませんでした** — 1 次点検の誤判断。)
- **ガード** `EntityMappingGuardTest`(common)— to-one は `fetch = LAZY`、`@Lob` 禁止。

**限界**
- **次のリリース(contract)**: `order_address.order_id` とその外部キー・インデックス、`orders.tax_price` 列を削除します — 今回のバージョンが安定してから(ロールバックした旧イメージはその列を使います)。
- Redis の旧ルームハッシュは残っています — `DEL CHAT_ROOM` で消して構いません。
- お知らせの V18 は元に戻せません — 旧イメージは本文を OID として読もうとして失敗します。

**改善点**
- 読んで・直して・書くエンティティに `@Version`（楽観ロック）を置きます。`@DynamicUpdate` が防ぐのは **別の列** 同士の上書きだけです。
- リポジトリ層のテストを Testcontainers の PostgreSQL に移します（§16 と同じ結論）。

→ [docs/17](17-testing.md) · §16

---

## 参考書一覧

| 本 | 出版 | 事例 |
|---|---|---|
| Martin Kleppmann, *Designing Data-Intensive Applications* | O'Reilly, 2017 | 2 · 3 · 12 · 18 |
| Sam Newman, *Building Microservices*, 2nd ed. | O'Reilly, 2021 | 5 · 6 · 8 · 13 |
| Gwen Shapira, Todd Palino, Rajini Sivaram, Krit Petty, *Kafka: The Definitive Guide*, 2nd ed. | O'Reilly, 2021 | 4 |
| Andrew Hoffman, *Web Application Security*, 2nd ed. | O'Reilly, 2024 | 1 |
| Razi Rais, Christina Morillo, Evan Gilman, Doug Barth, *Zero Trust Networks*, 2nd ed. | O'Reilly, 2024 | 9 |
| Heather Adkins ほか, *Building Secure and Reliable Systems* | O'Reilly, 2020 | 10 |
| Jonathan LeBlanc, Tim Messerschmidt, *Identity and Data Security for Web Development* | O'Reilly, 2016 | 11 |
| Betsy Beyer ほか, *The Site Reliability Workbook* | O'Reilly, 2018 | 7 |
| Betsy Beyer ほか, *Site Reliability Engineering* | O'Reilly, 2016 | 14 |
| Charity Majors, Liz Fong-Jones, George Miranda, *Observability Engineering* | O'Reilly, 2022 | 15 |
| Titus Winters, Tom Manshreck, Hyrum Wright, *Software Engineering at Google* | O'Reilly, 2020 | 16 |
| David Flanagan, *JavaScript: The Definitive Guide*, 7th ed. | O'Reilly, 2020 | 17 |
