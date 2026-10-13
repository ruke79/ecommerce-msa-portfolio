# 77. イベントソーシング — 適用しなかった理由と、適用するならの設計

**日本語** | [한국어](77-event-sourcing-decision.ko.md)

> **状態(2026-10-07)** — 決定の記録。コード変更なし。[docs/76](76-order-status-read-model.md) が「真実は order の DB、イベントは通知」と定めた根拠と、その決定を覆すときに何をどの順でやるかを、参考書を根拠に書く。
> 数字は 2026-10-07 の `order-service` ソースから数えたもの(§3-2)。

---

## 0. 概要

- **今はやらない。** イベントソーシングが約束するもののうち我々が欲しかった二つ — *履歴* と *信頼できるイベント発行* — は、トランザクショナルアウトボックス + 状態変更イベント(docs/76)ですでに得た。残りの約束(過去時点の再構成、O/R 不一致の解消)には今のところ需要がなく、代価(書き込みモデルの全面書き換え・すべての照会のプロジェクション化・不変ログからの個人情報削除・恒久的なスキーマ進化)は、シングルノード・ブローカー 1 台・一人運用という体格に比べて大きい。
- **まず用語を分ける。** Fowler は「イベント駆動」という言葉が四つの異なるパターンを一括りにしていると述べた — イベント通知(Event Notification)、状態運搬イベント(Event-Carried State Transfer)、イベントソーシング、CQRS[^fowler-ed]。このリポジトリは**前の二つと CQRS(部分的)**を使う。イベントソーシングは使わない。
- **Kleppmann の区分では**「変更データキャプチャ(CDC)側」である — 可変の DB が真実で、ログは派生。イベントソーシングはその逆(ログが真実、DB は派生)だ[^ddia-cdc-es]。どちらが真実かが、この決定のすべてである。
- **やるなら Order 集約ひとつから**、PostgreSQL のイベント表 + 今のリレー、既存の `orders` 表はプロジェクションに降格、四リリース(§4)。決定を見直すシグナルは §5。

---

## 1. 我々は今どこにいるか — 本の用語で

| このリポジトリ | パターン | 根拠 |
|---|---|---|
| `payment_outbox_event` + `PaymentOutboxScheduler`(FOR UPDATE SKIP LOCKED で先取り、同期発行) | **トランザクショナルアウトボックス + ポーリング発行器** | Richardson 3 章 "Transactional messaging" — アウトボックス表、ポーリング発行器、トランザクションログテーリング(CDC)のうち我々はポーリング[^rich-outbox] |
| `OrderStatusChanged`(docs/76) — 状態遷移 + 出所 | **イベント通知**(状態の一部を載せる) | Fowler の四パターンのうち一つ目と二つ目の間[^fowler-ed]。Newman の「何を載せるか — id だけ vs 全部」の中間[^newman-what] |
| saga-projection — `order` ∪ `payment` ∪ `order-status` を畳み込んで二つのストア | **派生データ・同じログから複数のビュー** | Kleppmann 11 章 "Deriving several views from the same event log" — ここで CQRS に触れる[^ddia-views] |
| `order-saga-state`・`order-timeline`(読み取り) vs `orders` 表(書き込み)、ES 検索 vs PostgreSQL | **CQRS — 部分的** | Richardson 7 章 — 照会を API 合成ではなくビューで[^rich-cqrs] |
| `OrderSaga`(決済 → 在庫 → 配送 → 確定、補償) | **サーガ — オーケストレーション** | Newman 6 章 "Workflow"[^newman-saga]、Richardson 4 章 |
| `Order` エンティティ — 37 フィールド、時刻カラム 9 個、状態は上書き | **状態保存(伝統的な永続化)** | Richardson 6 章 "The trouble with traditional persistence" が述べるまさにその形 — 履歴がなく、監査は後付け[^rich-trouble] |

Kleppmann の言葉で要約すると: CDC は「アプリケーションは DB を可変に使い、変更ログは低いレベルで抽出する」、イベントソーシングは「アプリケーションロジックが不変イベントの上に明示的に築かれ、イベントは状態変更の機械的な記録ではなく**ユーザー行動の意図**を表す」[^ddia-cdc-es]。我々の `OrderStatusChanged` は `source`(payment-saga・delivery・customer …)で意図を少し含むが、本質は状態遷移の通知である。だから docs/76 は最初から「真実ではない」と書いた。

---

## 2. イベントソーシングが約束するものと要求するもの

### 2-1. 約束 — Richardson の四つ[^rich-benefits]

1. **イベントを信頼性高く発行する** — イベントストアがそのままメッセージログなので、アウトボックスが別に要らない。
2. **集約の履歴が残る** — 監査・デバッグ・「あのときなぜこうなったか」。
3. **O/R 不一致をほぼ避けられる** — イベントは単純な直列化対象である。
4. **タイムマシン** — 過去時点の状態を再構成し、後から生まれた要件を過去のイベントに遡って適用する。

Kleppmann は不変イベントの利点を会計の帳簿に喩える — 誤った取引は消さず、訂正取引を追加する。バグで誤って書いた状態もイベントを畳み直して復旧でき、同じログから異なる読み取りビューをいくつも作れる[^ddia-immut]。

### 2-2. 要求 — 同じ本が記した代価

| 要求 | 内容 | 根拠 |
|---|---|---|
| **なじみのないプログラミングモデル** | 集約を「コマンド → イベント、イベント → 状態適用」に書き直す。コマンドの検証はイベントになる**前に**同期で終えなければならない — 消費者はイベントを拒否できない | Richardson "Drawbacks"[^rich-drawbacks]、Kleppmann "Commands and events"[^ddia-commands] |
| **照会が難しい → CQRS が必須** | イベントストアは主キー検索しかできない。一覧・集計・検索はすべて別のビュー(プロジェクション) | Richardson 6 章 "Querying the event store is challenging"・7 章[^rich-drawbacks][^rich-cqrs] |
| **全履歴が必要 — コンパクション不可** | CDC は最新状態だけ残せばよい(ログコンパクション)が、イベントソーシングは後のイベントが前のイベントを上書きしないので全履歴を保持しなければならない。そのため**スナップショット**が付いてくる | Kleppmann "Deriving current state from the event log"[^ddia-derive]、Richardson "Using snapshots"、Vernon 付録 A[^vernon-aes] |
| **イベントスキーマの進化が恒久的な仕事になる** | 保存されたすべての過去イベントを永遠に読めなければならない。フィールド追加はデフォルト値で、構造変更はアップキャスター、それ以上はストリームをコピー・変換(copy-transform)して新しいストリームへ — 過去は直さない | Richardson "Evolving domain events"[^rich-evolve]、Young *Versioning*[^young] |
| **削除が難しい** | 不変ログから個人情報を消すには「切除(excision)」のような例外経路が要り、実務では**個人ごとの鍵で暗号化して鍵を破棄**する(暗号シュレッディング・仮名化) | Kleppmann "Limitations of immutability"[^ddia-immut-limits]、Richardson "Deleting data is tricky"[^rich-drawbacks] |
| **読み取りビューの遅延** | イベントを書いた直後の照会が古いビューを見ることがある。同じトランザクションでビューを更新するには、イベントログとビューが**同じストレージ**になければならない | Kleppmann "Concurrency control"[^ddia-concurrency] |
| **冪等な消費** | メッセージ基盤なので重複配信を消費者が弾かなければならない(メッセージ id の追跡) | Richardson "Idempotent message processing"[^rich-idem] |

---

## 3. 適用しなかった理由 — このリポジトリの事実で

### 3-1. 欲しかったものはすでに得た

イベントソーシングで得たかったものは二つだった:「注文がいつなぜ変わったか」と「状態変更が漏れなくイベントとして出ていく」。どちらも docs/76 が解決した — `OrderStatusChanger` が状態とアウトボックス行を**同じトランザクション**に書き(Richardson のアウトボックスパターン[^rich-outbox])、saga-projection がそれをタイムラインに畳み込む。Richardson の四つの利点のうち①と②はこれで満たされる。③(O/R 不一致)は我々の問題ではなく、④(過去時点の再構成)はまだ誰も求めていない。

Kleppmann 流に言えば、我々は「ログを真実とする」代わりに「DB を真実に置き、派生ログを作る」側を選び、彼は二つのアプローチを**同じ考えの異なるレベル**だと書いている[^ddia-cdc-es]。派生側は既存アプリケーションをそのままにして始められる — それがこのリポジトリでは決定的だった。

### 3-2. 書き込みモデルを丸ごとひっくり返さなければならない — 数字

`order-service/src/main` で数えたもの:

| 項目 | 数 | 意味 |
|---|---|---|
| `Order` のフィールド | **37**(うち `LocalDateTime` 9 — paid・delivered・completed・depositDeadline・stockHoldUntil・refunded・returnReceived・shortageResolved・stockRestored) | 状態表が履歴の役を果たそうと時刻カラムを増やしてきた形。これはイベントソーシングの**シグナル**でもある(§5) |
| `order.set*(...)` の呼び出し | **28 回・22 種・5 ファイル** | すべて「コマンド → イベント → apply」に書き直す必要がある |
| `Order` を扱うクラス | **23** | サーガ・決済確認・返金・返品・銀行振込・仮想口座・配送・確定・在庫 … |
| `OrderRepository` の照会メソッド | **21**(`@Query` 8) | すべてプロジェクションの上に移さなければならない — イベントストアは主キー検索のみ[^rich-drawbacks] |
| 注文を読む GET | **8**(内部 3 — 購入統計・配送先・購入有無) | 同じ理由 |
| `@Version` | **0** | 今は楽観的ロックがない。イベントソーシングは(集約, バージョン)のユニークでこれを強制する[^rich-optimistic] — 取り入れたい利点だが、単独でもできる |

一方 `pay-service` の `Payment` はフィールド 11 個で `payment.set*` の呼び出しは **0** — すでに事実上不変である。イベントソーシングが必要なのは Order だけで、Order が最も高くつく。

### 3-3. 運用の体格 — シングルノード、ブローカー 1 台、一人

- 本番は OCI Ampere の**シングルノード**(docs/32)、Kafka は**クラスター内のブローカー 1 台**(レプリケーションなし、マネージドなし — `k8s-overlays/external` のコメント)。イベントソーシングではイベントストアが**唯一の真実**なので、そのストアの耐久性・バックアップ・復元がそのまま注文データのすべてになる。
- そのストアを Kafka にする選択はこの体格に合わない — コンパクションが使えず(全履歴)[^ddia-derive]、集約ひとつを読むにはパーティションを走査しなければならず、ブローカーが 1 台だ。結局 PostgreSQL にイベント表を置き(PITR バックアップがすでにある、docs/15)、Kafka は発行チャネルになる — Richardson の Eventuate Local も RDB の `events` 表 + CDC である[^rich-store]。すると**今の構成(表 + アウトボックス + リレー)とストレージ層はほぼ同じで、変わるのは「どの表が真実か」だけ**になる。
- 一人で運用している。Richardson が最初の欠点に「異なる、なじみのないプログラミングモデル・学習曲線」[^rich-drawbacks]を挙げたのは、チームがひとつの考え方を共有しなければならないという意味である。

### 3-4. 個人情報 — 不変ログから消す

注文イベントには配送先(`OrderAddress` — 氏名・住所 2 行・市・州・郵便番号・国・電話、9 フィールド)が載る。今は表なので UPDATE で消せる(現在の退会処理 `WithdrawalProcessor` は注文を扱っていない — それはそれで未解決事項である)。不変ログなら Kleppmann の言う「切除(excision)」[^ddia-immut-limits]や Richardson の「個人ごとの鍵で暗号化 → 鍵を破棄」[^rich-drawbacks]が**先に**設計されていなければならない。このリポジトリの規則(メールアドレス・住所をログに残さない)をイベントにも守るなら、§4-3 のように配送先をイベントの外に置く必要がある。

### 3-5. スキーマ進化 — 今でも二リリース、イベントソーシングなら恒久

メッセージスキーマにフィールドを足したり抜いたりするのに、このリポジトリはすでに二〜三リリースを使う(docs/55 §4・§5 — 決済リクエスト v1 → v4、`AvroSchemaHistory` が旧フィンガープリントを記憶する)。それは**トピックの保持期間中の**互換性である。イベントソーシングは**ストアに永遠に残るすべての過去イベント**を読まなければならないので、アップキャスター(旧イベント → 新しい形)と copy-transform(ストリームをコピーしながら変換して新しいストリームへ)が常時の仕事になる[^rich-evolve][^young]。Young の最初の問い —「なぜイベントを更新できないのか」— の答え(すでに読んだ消費者がいる、ログは訂正でしか変えない)は、このリポジトリの Flyway の規則(「適用済みのマイグレーションファイルは直さない」)と同じ精神だが、対象がすべてのイベントタイプに広がる。

### 3-6. だから

Fowler はイベントソーシングを「強力だが … なじみがなく、ツールと経験が少ない」とし、**必要な箇所に限って**使うよう述べ[^fowler-es]、Vernon もイベントソーシングを集約単位の選択(A+ES)として扱う[^vernon-aes]。Richardson は「複雑なビジネスロジック・監査要件のある集約」を対象に挙げる。今このリポジトリの Order はその閾値に**近づいてはいる**(§3-2 の時刻カラム 9 個)がまだ越えておらず、越える前にできるより安いこと(docs/76)を先にやった。

---

## 4. 適用するなら — Order 集約から、四リリース

### 4-1. 範囲

**Order だけ。** 理由: 状態遷移が最も多く(§3-2)、紛争(返金・返品・入金期限)が起きる場所である。`Payment` はすでに不変に近く、カタログ・会員・カートは CRUD に検索・個人情報が絡んでいて対象ではない。Vernon の A+ES のように**集約単位**で取り入れる[^vernon-aes] — サービス全体を変えない。

### 4-2. イベントストア — PostgreSQL、Kafka は発行チャネル

```sql
CREATE TABLE orders.order_event (
    aggregate_id   bigint      NOT NULL,          -- orderId
    version        integer     NOT NULL,          -- 集約内の連番、1 から
    event_id       uuid        NOT NULL,
    event_type     varchar(64) NOT NULL,          -- OrderPlaced · PaymentConfirmed · …
    payload        bytea       NOT NULL,          -- Avro 単一オブジェクト符号化(フィンガープリント付き) — 今のトピックと同じ道具
    occurred_at    timestamptz NOT NULL,
    trace_context  varchar(512),                  -- 今のアウトボックスと同じトレース伝播(docs/51 §4e)
    outbox_status  varchar(16) NOT NULL DEFAULT 'STARTED',
    PRIMARY KEY (aggregate_id, version)           -- 🔴 楽観的ロック: 同じバージョンを二度書くと失敗
);
```

- `(aggregate_id, version)` の主キーが**楽観的ロック**である — Richardson の "Handling concurrent updates using optimistic locking"[^rich-optimistic]。今の Order にない `@Version` をこれで得る。
- この表が**そのままアウトボックス**である。今の `PaymentOutboxScheduler` が `outbox_status=STARTED` の行を先取りして Kafka に出し COMPLETED を付ける — Richardson「イベントストアはメッセージログの役も果たす」[^rich-benefits]、Eventuate Local の `events` 表を CDC でテーリングするのと同じ構造[^rich-store]。ポーリングの代わりに Debezium にするのは選択(docs/76 §4 のハイブリッド逆直列化器が必要になる)。
- **Kafka をストアにしない理由**: 全履歴なのでコンパクション不可[^ddia-derive]、集約単位の読み取り不可、ブローカー 1 台(§3-3)。PITR バックアップ(docs/15)がすでに PostgreSQL にある。
- スナップショット表 `order_snapshot(aggregate_id, version, state bytea)` は**最初は作らない。** 注文ひとつのイベントは 10〜15 個。集約あたり 50 を超え始めたら Richardson・Vernon のスナップショット[^rich-snapshot][^vernon-aes]を入れる — 再構成コストはそのとき実測する。

### 4-3. イベント — 意図を込め、個人情報は外に

Kleppmann: イベントソーシングのイベントは「状態更新の機械的な記録ではなく**ユーザー行動の意図**」[^ddia-cdc-es]。今の `OrderStatusChanged(PAID → PROCESSING, source=delivery)` をそのままイベントソーシングのイベントにすると「なぜ」が抜ける。名前を意図に変える:

| 今の遷移 | イベントソーシングのイベント(例) | 含むもの |
|---|---|---|
| 作成 | `OrderPlaced` | 商品・数量・金額・クーポン・決済手段、**配送先 id**(本文ではない) |
| → UNPAID | `DepositRequested` / `VirtualAccountIssued` | 期限・振込人名 / 銀行・口座(仮想口座は個人情報ではない) |
| → PAID | `PaymentConfirmed` | プロバイダー・決済参照・請求通貨・金額・時刻 |
| → PROCESSING / DISPATCHED / DELIVERED | `ShipmentStarted`・`ShipmentDispatched`・`Delivered` | 配送 id・送り状(配送サービスが送った値) |
| → COMPLETED | `PurchaseConfirmed` | 顧客 / 自動、時刻 |
| → CANCELED | `OrderCanceled` | 理由(顧客・管理者・入金期限) |
| → REFUNDED(部分を含む) | `RefundIssued` | 金額・項目・返金参照 — 部分返金は状態が変わらなくてもイベントである |
| 返品・在庫 | `ReturnReceived`・`StockShortageResolved`・`StockRestored` | 今の時刻カラム三つが担っていること |

- **配送先はイベントに載せない。** `OrderPlaced` は `order_address` 行の id だけを指し、その表は可変のままにして UPDATE で消せるようにする。イベントの中にどうしても入る個人情報が生じたら、注文ごとのデータ鍵で暗号化し、削除時に鍵を破棄する(暗号シュレッディング — Richardson・Kleppmann[^rich-drawbacks][^ddia-immut-limits])。
- 符号化は **Avro 単一オブジェクト**(フィンガープリント付き) — 今のトピックと同じ道具なので `AvroSchemaHistory`・ハイブリッド逆直列化器・ガードがそのまま使える。互換規則は Kleppmann 4 章の書き込み側・読み取り側スキーマ[^ddia-avro]: フィールド追加はデフォルト値付きで(旧イベントも読める)、削除はデフォルト値のあったフィールドだけ。

### 4-4. 集約 — コマンド → イベント、イベント → 状態

```java
// スケッチ。状態はイベントを畳み込んだ結果で、コマンドは検証の後にイベントを返す。
final class Order {
    private OrderStatus status; private BigDecimal refunded; /* … */ private int version;

    static Order rehydrate(List<OrderEvent> history) { Order o = new Order(); history.forEach(o::apply); return o; }

    List<OrderEvent> confirmPayment(PaymentConfirmed cmd) {             // コマンド
        if (!AWAITING_PAYMENT.contains(status)) return List.of();      // 再配信 — イベントなし(冪等)
        return List.of(new PaymentConfirmed(id, version + 1, cmd.provider(), cmd.ref(), cmd.at()));
    }
    private void apply(PaymentConfirmed e) { status = PAID; paidAt = e.at(); version = e.version(); }
    /* apply(...) は検証しない — すでに起きたことである */
}
```

- 検証はコマンド段階で**同期的に**終える。イベントになれば消費者は拒否できない[^ddia-commands]。今 `OrderDeliveryStatusService` が「前にしか進まない」を検査している場所が、まさにコマンド検証である。
- 保存: `load = (スナップショット) + 以降のイベント`、`save = INSERT … (aggregate_id, version)` — 衝突なら読み直して再試行。
- テスト: **Given(イベント群)・When(コマンド)・Then(イベント群)** — Young が CQRS 文書で提案した形式[^young-cqrs]。リデューサーが純粋関数なので TopologyTestDriver なしで検証する今の saga-projection のやり方と同じ。

### 4-5. 読み取りモデル — `orders` 表をプロジェクションに降格

- 今の `orders.orders` 表と 21 の照会・8 の GET は**そのまま残す。** ただしその表を書く主体が変わる — コマンド処理器がイベントを `order_event` に書き、**同じトランザクション**で `orders` 行を更新する(同期プロジェクション)。Kleppmann の言う「読み取りビューをイベント追加と同じトランザクションで更新するには同じストレージでなければならない」の条件が PostgreSQL の中で成り立つ[^ddia-concurrency]。そのため**読み取り遅延がなく**、画面・管理者・内部 API はひとつも変わらない。
- サービス外のプロジェクション(saga-projection のタイムライン、通知)は今のように Kafka で — 非同期、eventId で冪等[^rich-idem](すでに `OrderTimelineReducer` がやっている)。
- Richardson 7 章の CQRS は通常「別の DB・別のサービスのビュー」を指すが、同じ DB 内の二つの表から始めても「コマンドと照会が異なるモデルを見る」という本質は同じである[^rich-cqrs]。マルチノードになったらそのとき分離する。

### 4-6. 四リリース — docs/55 の expand → contract の精神

| リリース | やること | 真実 | ロールバック |
|---|---|---|---|
| **R0**(今、docs/76) | 状態変更をアウトボックスイベントに、タイムラインの読み取りモデル | `orders` 表 | イメージだけ |
| **R1 ジャーナル** | `order_event` 表を作成。`OrderStatusChanger` が状態変更のたびに**同じトランザクション**でイベントも書く(意図の名前で)。既存注文は `OrderSnapshotImported`(現在行の全体)一件でシード。**検証ジョブ**: イベントを再生した状態 == `orders` 行 — ずれたらアラート(これが「戻すと失敗するテスト」の運用版) | `orders` 表 | イメージだけ(表はおまけ) |
| **R2 切り替え** | 集約をイベントからロード、`orders` は同期プロジェクション。直接 UPDATE をガード(B-7x — B-78 の拡張)で止める。再生ジョブが一週間ゼロ件なら進む | `order_event` | R1 のイメージへ — 二つの表は R1 でも一緒に書かれていたので食い違いなし |
| **R3 整理** | `Order` のセッター・時刻カラムの整理(列削除は ROLLBACK-UNSAFE、docs/55)、ポーリング → CDC は選択 | `order_event` | 不可(contract) — 安定後にのみ |

各段階は今のリポジトリがすでに使う道具(Flyway ガード・リリースマーカー・Testcontainers の実 PostgreSQL マイグレーションテスト)で検証する。R1 の「再生 == 行」の検証が核心である — Kleppmann の言う「同じログから派生したビュー」が本当に同じかを**データで**確かめてからのみ真実を移す[^ddia-views]。

### 4-7. 払う代価 — おおよそ

§3-2 の数字がそのまま作業量である: 23 クラス・28 呼び出しをコマンド/イベントに、イベントタイプ 10 余りのスキーマとアップキャスターの置き場、R1 のシード・検証ジョブ、ガード、文書。運用の変化は表ひとつ(バックアップ・PITR の範囲内)とリレーのスループット(注文あたりイベント 10〜15 個 — 今の決済リクエスト 1〜2 個に比べ十倍)。この体格では数週間単位の仕事で、得るものは §2-1 の④(タイムマシン)と①の「アウトボックスなしでも発行」だけ — 残りはすでにある。

---

## 5. 決定を見直す条件

次のいずれかが生じたら §4 を取り出す:

- **監査・規制** — 決済・返金の履歴を証憑として出さなければならないとき。タイムライン(docs/76)は読み取りモデルなので証憑ではない(再生で作り直せるが、原本は `orders` 表の現在状態だけ)。
- **過去時点の再構成**の要求 —「返金当時、注文はどんな状態だったか」を紛争で問われ始めたとき(§2-1 ④)。
- **時刻カラムが増え続ける** — 今 9 個。状態表が履歴の役を果たそうとする圧力がさらに強まれば、それは Richardson の「伝統的な永続化の問題」[^rich-trouble]が臨界に来たということである。
- **同じイベントの消費者が三つ以上** — 通知・分析・精算がそれぞれ注文履歴を欲しがるとき。今は saga-projection ひとつである。
- **運用の体格** — マルチノード(docs/71)とレプリケートされた Kafka ができ、イベントストア・発行チャネルの耐久性を別々に語れるようになったとき。

それまでは docs/76 の決定 — **真実は order の DB、イベントは通知、タイムラインは派生** — を維持する。

---

## 6. 参考書

| 本・記事 | どこ | 何を持ってきたか |
|---|---|---|
| Martin Kleppmann, *Designing Data-Intensive Applications* (O'Reilly, 2017) | 11 章 "Stream Processing" — Change Data Capture・Event Sourcing・Commands and events・State, Streams, and Immutability(Advantages / Deriving several views / Concurrency control / Limitations) | CDC とイベントソーシングの区分、全履歴・コンパクション不可・スナップショット、コマンドは同期検証、読み取りビューの遅延と同一ストレージのトランザクション、不変の限界(削除) |
| 同書 | 4 章 "Encoding and Evolution" — Avro | 書き込み側・読み取り側スキーマ、前方・後方互換の規則 |
| Chris Richardson, *Microservices Patterns* (Manning, 2018) | 3 章 "Transactional messaging" | トランザクショナルアウトボックス・ポーリング発行器・トランザクションログテーリング |
| 同書 | 6 章 "Developing business logic with event sourcing" — The trouble with traditional persistence・optimistic locking・snapshots・idempotent message processing・evolving domain events・benefits・drawbacks・Implementing an event store | 利点四つ・欠点(なじみのないモデル・進化・削除・照会)、イベント表 + CDC のストア構造 |
| 同書 | 7 章 "Implementing queries in a microservice architecture" — CQRS | 照会をビューで、イベントソーシングが CQRS を要求する理由 |
| Vaughn Vernon, *Implementing Domain-Driven Design* (Addison-Wesley, 2013) | 4 章 "Architecture" — Event Sourcing・CQRS、付録 A "Aggregates and Event Sourcing: A+ES" | 集約単位の選択、イベントストア・スナップショット |
| Greg Young, *Versioning in an Event Sourced System* (Leanpub, 2017) | "Why can't I update an event?"・"Weak Schema"・"Copy and Replace" | 過去を直さない、アップキャスティング、copy-transform |
| Greg Young, *CQRS Documents* (2010, オンライン) | Testing | Given・When・Then のイベントテスト |
| Martin Fowler, "Event Sourcing" (2005)・"What do you mean by 'Event-Driven'?" (2017, martinfowler.com) | — | 四パターンの区分、「必要な箇所に限って」 |
| Sam Newman, *Building Microservices*, 2nd ed. (O'Reilly, 2021) | 4 章 "Pattern: Event-Driven Communication — What's in an Event?"、6 章 "Workflow" | イベントに何を載せるか、サーガ |

[^fowler-ed]: Fowler, "What do you mean by 'Event-Driven'?" — Event Notification・Event-Carried State Transfer・Event-Sourcing・CQRS の四つを区別し、一語で一括りにするところから誤解が生まれると書く。
[^ddia-cdc-es]: Kleppmann, 11 章 "Event Sourcing" — CDC は DB を可変に使い変更ログを低いレベルで抽出するのに対し、イベントソーシングは不変イベントの上にアプリケーションロジックを築き、イベントは状態更新の機械的記録ではなくユーザー行動の意図を表す、と区別する。
[^rich-outbox]: Richardson, 3 章 "Transactional messaging" — アウトボックス表を一時的なメッセージキューとして使う Transactional outbox、その表をポーリングして発行する Polling publisher、DB ログを読む Transaction log tailing。
[^newman-what]: Newman, 4 章 "What's in an Event?" — id だけを載せるイベントと全状態を載せるイベントの長短。
[^ddia-views]: Kleppmann, 11 章 "Deriving several views from the same event log" — 同じイベントログから異なる読み取り指向の表現をいくつも作ることができ、これが CQRS と呼ばれると触れる。
[^rich-cqrs]: Richardson, 7 章 — API 合成と CQRS。イベントソーシングはイベントストアが主キー検索しかサポートしないので照会をさらに難しくし、そのため CQRS ビューが必要になる。
[^newman-saga]: Newman, 6 章 "Workflow" — サーガ、オーケストレーションとコレオグラフィ、補償トランザクション。
[^rich-trouble]: Richardson, 6 章 "The trouble with traditional persistence" — 集約の履歴がなく、監査ロギングは後付けで退屈かつ誤りやすく、イベント発行がビジネスロジックに継ぎ足される。
[^rich-benefits]: Richardson, 6 章 "Benefits of event sourcing" — ドメインイベントの信頼性ある発行、集約履歴の保持、O/R インピーダンス不一致のほぼ回避、開発者へのタイムマシン。
[^ddia-immut]: Kleppmann, 11 章 "Advantages of immutable events" — 会計帳簿の喩え、誤った取引は訂正取引で、バグ復旧とデバッグの容易さ。
[^rich-drawbacks]: Richardson, 6 章 "Drawbacks of event sourcing" — 異なるなじみのないプログラミングモデル、メッセージ基盤アプリケーションの複雑さ(重複処理)、イベント進化の難しさ、データ削除の難しさ(GDPR — 個人ごとの鍵による暗号化と鍵の破棄)、イベントストア照会の難しさ。
[^ddia-commands]: Kleppmann, 11 章 "Commands and events" — リクエストはコマンドとして届き検証を経てはじめてイベントになり、イベントストリームの消費者はイベントを拒否できないので、検証はその前に同期で終えなければならない。
[^ddia-derive]: Kleppmann, 11 章 "Deriving current state from the event log" — イベントソーシングでは後のイベントが前のイベントを上書きしないので全履歴が必要で、CDC と違いログコンパクションを同じようには使えず、そのためスナップショットを置く。
[^vernon-aes]: Vernon, 付録 A "Aggregates and Event Sourcing: A+ES" — 集約ごとのイベントストリーム、イベントストア、スナップショット、性能の考慮。
[^rich-evolve]: Richardson, 6 章 "Evolving domain events" — スキーマ変更の種類と互換性、アップキャスティング。
[^young]: Young, *Versioning in an Event Sourced System* — イベントは直さない、弱いスキーマ(デフォルト値で読む)、アップキャスティング、Copy and Replace(ストリームをコピー・変換して新しいストリームへ)。
[^ddia-immut-limits]: Kleppmann, 11 章 "Limitations of immutability" — 保持・高い変更率・そして規制などでデータを本当に消さなければならないときの難しさ(Datomic の excision、Fossil の shunning)。
[^ddia-concurrency]: Kleppmann, 11 章 "Concurrency control" — イベントを書いた直後に読み取りビューがまだ更新されていないことがあり、ビュー更新をイベント追加と同期で束ねるには両者が同じストレージにあるか分散トランザクションが必要。
[^rich-idem]: Richardson, 6 章 "Idempotent message processing" — 処理したメッセージ id を追跡して重複を弾く。
[^rich-optimistic]: Richardson, 6 章 "Handling concurrent updates using optimistic locking" — 集約バージョンで同時更新を検出する。
[^rich-store]: Richardson, 6 章 "Implementing an event store" — Eventuate Local: RDB の events・entities・snapshots 表とトランザクションログテーリングで Kafka に発行。
[^rich-snapshot]: Richardson, 6 章 "Using snapshots to improve performance"。
[^ddia-avro]: Kleppmann, 4 章 "Avro" — 書き込み側スキーマと読み取り側スキーマ、スキーマ解決と進化の規則。
[^young-cqrs]: Young, *CQRS Documents* — 集約のテストを「Given イベント群、When コマンド、Then イベント群」で書く。
[^fowler-es]: Fowler, "Event Sourcing" — なじみのないモデルとツール・経験の不足を挙げ、どこにでも使うパターンではないと書く。
