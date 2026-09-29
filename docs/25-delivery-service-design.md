# 25. 配送サービス — 要件とスキーマ設計（提案）

**日本語** | [한국어](25-delivery-service-design.ko.md)

> 🕰️ **状態（2026-09-27）— 一部古い。** タイトルの「（提案）」は作成当時のものです。delivery-service は実装 · デプロイされ、§10 の「しない」（分割配送 · 住所変更）はその後実装されました。

> 決済が終わると注文は `PAID` になり、**そこで止まる。** その先がない。
> この文書は配送サービスの要件・ドメインモデル・スキーマ・連携構造を提案する。

## 0. 今何が空いているのか

まず事実確認から。配送は「まだ作っていない機能」ではなく、**殻だけある機能**だ。

| すでにあるもの | 実際の状態 |
|---|---|
| `OrderStatus.PROCESSING / DISPATCHED / DELIVERED` | **誰もこの値を設定しない。** Saga は `PAID` で終わる |
| `Order.deliveredCreatedAt` | 常に null |
| `Order.trackingId` | Saga 追跡用の UUID だ。**配送業者の送り状番号ではない** — 名前が紛らわしくなっている |
| `OrderAddress` | 住所は受け取っておくが、配送には使われない |
| フロントのプロフィールの `DISPATCHED` フィルタ | 条件に合う注文が生まれ得ないので、**永遠に空の一覧** |

`Alarm` エンティティが保存されていなかったこと、チャットが配信されるだけで保存が 0 件だったこと（B-24）と同じ形だ。
**型はあるのに、その型を埋めるコードがない。**

> **2026-09 更新。** 上の表はもう過去のものだ。配送が実際に作られ（§11.1）、`delivery-status`
> を order-service が消費して PROCESSING・DISPATCHED・DELIVERED と `deliveredCreatedAt` が
> 埋まり（§11.2）、管理者が画面で送り状を登録する（§11.3）。実際の配送業者なしで最後まで回して
> みる方法は §11.6b。デプロイ後に遭遇した 2 つ目の詰まりとその復旧経路は §11.5。

## 1. 要件

### 1.1 機能要件

| # | 要件 | 備考 |
|---|---|---|
| F-1 | 決済完了時に配送が自動作成される | Saga の延長。注文状態が `PAID` → `PROCESSING` へ進む |
| F-2 | 販売者/物流が**送り状番号と配送業者**を登録する | この時点で `DISPATCHED` |
| F-3 | 配送業者から配送の進捗を**定期的に取得する** | ほとんどの韓国の配送業者は Webhook を提供しない — ポーリングが現実 |
| F-4 | 顧客が配送状態と**履歴タイムライン**を照会する | Amazon 風の進捗バー + 段階ごとの時刻 |
| F-5 | 状態が変わると**通知**が出る | [notification-service](24-sms-notifications.md) へイベント発行 |
| F-6 | 配送完了時に注文が `DELIVERED` で締めくくられる | `deliveredCreatedAt` がついに埋まる |
| F-7 | **配送業者を後から柔軟に追加**できる | §4 — この要件が設計を最も大きく左右する |
| F-8 | 配送失敗・返送・紛失を表現できる | ハッピーパスだけ作ると、実際の運用ですぐ詰まる |

### 1.2 非機能要件

| # | 要件 | 設計への影響 |
|---|---|---|
| N-1 | **外部 API の障害が注文に波及しないこと** | 別サービスに分離する最大の理由。配送業者の API はよく遅く、時々落ちる |
| N-2 | API 呼び出しのクォータを節約すること | 公共 API には 1 日の呼び出し上限がある。状態ごとにポーリング周期を変える（§5） |
| N-3 | 同じ追跡イベントを重複保存しないこと | ポーリングは毎回**全履歴**を返す。重複除去が必須 |
| N-4 | レプリカ 2 つで安全であること | ポーリングの先取りに `FOR UPDATE SKIP LOCKED`（[docs/21](21-horizontal-scaling.md)） |
| N-5 | 配送業者の原文を保存すること | マッピングが間違っていたとき、原文がなければデバッグ不可能だ |

### 1.3 範囲

| 項目 | 状態 |
|---|---|
| **分割配送**（1 注文に複数の箱） | ✅ 実装 — `Shipment` 単位で追跡。§3 |
| **国際配送**（通関段階） | ✅ 実装 — `CUSTOMS_HOLD/CLEARED` + 通関番号 |
| **配送前の住所変更** | ✅ 実装 — 履歴を保存、集荷後はブロック |
| **キャンセル後の再注文** | ✅ 実装 — 発送前キャンセル、新しい配送に `reorderedFromOrderId` を紐付け |
| リアルタイムのドライバー位置（地図上の移動） | ❌ 韓国の配送業者は**このデータを提供しない**。提供するのはスキャン拠点単位の履歴だ |
| 送料のリアルタイム見積もり | ❌ 契約単価が必要だ |
| 返品集荷（回収）受付 | ❌ 別ドメイン。状態モデルに枠だけ残しておく |
| 複数配送業者の自動選択 | ❌ 契約・単価情報が先に必要だ |

> **「位置追跡」の実際の意味。** 要求に「位置追跡」があったが、国内の配送 API が返すのは
> GPS 座標ではなく**スキャンイベント**だ: `집화완료(서울강남터미널) → 간선상차 → 간선하차(대전허브)
> → 배달출발(담당 배송기사) → 배달완료`。拠点名は出るので「どの都市まで来たか」は見せられる
> が、地図上のリアルタイム移動は不可能だ。フロントを Amazon 風の**進捗段階 + 履歴一覧**で
> 設計した理由がこれだ — Amazon も同じ理由でそうしている。

## 2. なぜ別のマイクロサービスなのか

order-service の中に入れない理由:

1. **障害の隔離（N-1）。** 配送業者の API は私たちが制御できない。ポーリングが遅くなったり失敗したりしても、注文
   作成・決済は影響を受けてはならない。
2. **別のリズムで回る。** 注文はリクエストベース、配送は**時間ベースのポーリング**だ。スケジューラと外部
   HTTP 呼び出しが注文サービスのコネクションプールやスレッドを食いつぶす理由はない。
3. **別のスケール軸。** 配送件数は注文件数に追随するが、ポーリング負荷は**未完了の配送数**に
   比例する。連休のように配送が滞るときだけ大きくなる。
4. **外部の資格情報の隔離。** 配送業者の API キーを注文サービスが持っている理由はない。

## 3. ドメインモデル

**分割配送のために階層が 3 つある。** 追跡情報を注文に直接付けると、1 つの注文が複数の箱に
分かれるケースを表現できない — 送り状番号も配送業者も到着日も箱ごとに異なるからだ。

```
Delivery (注文1件の配送全体 — コンテナ)
├── order_id (UNIQUE)         分割しても注文ごとに1つ
├── 受取人スナップショット    住所はここに1つだけ (箱が分かれても目的地は1つ)
├── reordered_from_order_id   キャンセル後の再注文の紐付け
├── 派生状態                  最も遅れている箱に従う
└── Shipment[] (箱 = 追跡単位)
     ├── carrier_code + tracking_number   箱ごとに異なり得る
     ├── status / next_poll_at            箱ごとに独立して進行
     ├── international + 通関番号         国際配送
     ├── ShipmentItem[]                   この箱に何が入っているか
     └── DeliveryEvent[]                  配送業者のスキャン履歴
```

**全体の進捗は最も遅れている箱に従う。** 3 つ中 2 つが届いても注文は完了ではない —
逆にすると、まだ届いていない品物があるのに「配達完了」になり、ユーザーが申告できなくなる。
ただし、例外状態の箱があればそれを優先する（問題は埋もれてはならない）。

### 3.1 なぜ住所を**コピー**するのか

`OrderAddress` を参照せず、配送時点の住所を**スナップショットとしてコピー**する。

ユーザーが配送中にプロフィールの住所を変えても、すでに出発した荷物の目的地は変わらない。参照に
しておくと、後で「この品物がどこへ行ったか」を照会したときに**現在の住所**が出てきて事実と異なってしまう。
会計・紛争資料としての価値が失われる。注文金額を注文時点の値で固定しておくのと同じ理由だ。

### 3.2 状態モデル — 配送業者の文字列をそのまま使わない

配送業者ごとに状態の表現が異なる。韓国郵便は `접수/발송/도착/배달준비/배달완료`、CJ は
`집화처리/간선상차/간선하차/배달출발/배달완료` といった具合だ。これをそのまま保存すると:

- 画面が配送業者ごとに変わる
- 状態に基づくロジック（通知送信、注文の締め）を配送業者の数だけ書かなければならない
- 配送業者を追加するたびに画面とロジックを直さなければならない → **F-7 違反**

そこで**内部の標準状態**にマッピングし、配送業者の原文はイベントに一緒に保存する（N-5）。

**宣言順がそのまま進捗度だ。** `DeliveryStatus` の ordinal で「前に進んだか」を判定するので
（`isProgressFrom`）、値を途中に差し込むときは順序を守らなければならない。下の表は実際の宣言順だ。

| 内部状態 | 意味 | ポーリング周期 | 注文状態との連動 |
|---|---|---|---|
| `PENDING` | 配送作成済み、送り状未登録 | （ポーリングしない） | `PROCESSING` |
| `INFO_RECEIVED` | 配送業者が受付情報だけ受け取った（品物はまだない） | 6h | `DISPATCHED`（送り状登録 = 発送処理、F-2） |
| `PICKED_UP` | 集荷完了 — 現物が配送業者の手に | 3h | `DISPATCHED` |
| `IN_TRANSIT` | 幹線輸送中 | 3h | `DISPATCHED` |
| `CUSTOMS_HOLD` | 通関待ち・保留 — 書類や関税が引っかかっている | 6h | `DISPATCHED` |
| `CUSTOMS_CLEARED` | 通関完了 — 国内の配送網に引き渡される | 3h | `DISPATCHED` |
| `OUT_FOR_DELIVERY` | 配達出発 — **今日届く** | 30m | `DISPATCHED` |
| `ATTEMPT_FAILED` | 配達試行の失敗（不在など）— 再試行される | 3h | `DISPATCHED` |
| `DELIVERED` | 配達完了（終了） | （中断） | `DELIVERED` |
| `EXCEPTION` | 破損・紛失・住所不明など、人が見るべき状態 | 12h | （維持） |
| `RETURNED` | 返送完了（終了） | （中断） | （維持） |
| `CANCELLED` | 発送前キャンセル（終了） | （中断） | `PAID` に戻す — 品物が出ていないので返金が再び可能になる（§11.9）。すでに返金・キャンセル済みの注文ならそのまま |

`OUT_FOR_DELIVERY` を別の状態にしたのは、**通知の価値が最も高い瞬間**だからだ。
Amazon が "Out for delivery" を強調する理由と同じだ。

通関の 2 状態を分けた理由は、**ユーザーに伝えることが異なる**からだ。`CUSTOMS_HOLD` は
ユーザーが行動しなければならないかもしれない状態（関税の納付、個人通関固有符号の提出）なので画面で警告として
表示し、`CUSTOMS_CLEARED` は単に進行中だ。1 つにまとめるとこの区別が消える。

## 4. 配送業者連携 — 拡張可能な構造（F-7）

要件の核心だ。**配送業者を追加するときにドメインコードが変わってはならない。**

```
                          ┌─────────────────────────┐
  DeliveryTracker ───────▶│   CarrierAdapter (I/F)  │
  (ポーリングスケジューラ)└───────────┬─────────────┘
                                      │ 実装クラス
                  ┌───────────────────┼────────────────────┐
                  ▼                   ▼                    ▼
          KoreaPostAdapter      SimulatedAdapter    (今後追加)
          (韓国郵便 公共 API)   (開発・テスト用)    CJ / 韓進 / ロッテ …
```

**アダプタの責務は 1 つだけだ**: `추적번호 → 표준 이벤트 목록`。
状態判定・重複除去・注文連携・通知はすべてドメイン側にある。だから新しい配送業者はクラスを 1 つ
追加するだけでよく、そのクラスは **HTTP 呼び出しとレスポンスのマッピングだけ**を知っている。

### 4.1 最初の連携先に韓国郵便（Korea Post）を選んだ理由

- **公開された公共 API がある**（公共データポータル）。民間配送業者の API はほとんど契約が先に必要だ。
- 仕様書が公開されているのでアダプタを検証できる。
- 国際郵便（EMS）まで同じ系列なので、後で海外配送に広げやすい。

⚠️ **正直に言うと**: このアダプタは API キーなしでは実際の呼び出しを検証できない。そのため
**レスポンスのフィールドマッピングを設定に切り出し**、マッピングロジックだけを記録済みのサンプルレスポンスでテストする。
実際のキーを受け取ったらフィールド名を合わせるだけでよく、そのとき `ops/` スクリプトで実呼び出しを確認する。

### 4.2 新しい配送業者を追加する手順

1. `CarrierAdapter` を実装したクラスを 1 つ作る（HTTP 呼び出し + レスポンス → 標準イベント）。
2. `carrier` マスタテーブルに行を入れる（コード・名前・追跡 URL テンプレート）。
3. 終わり。スケジューラ・状態判定・通知・画面には手を付けない。

## 5. ポーリング戦略 — クォータを燃やさない（N-2）

全件を同じ周期で照会すると、公共 API の 1 日の上限をすぐに超える。**状態に応じて周期を変える。**

| 状態 | ポーリング周期 | 根拠 |
|---|---|---|
| `PENDING` | 照会しない | 送り状番号がなければ照会する対象がない |
| `INFO_RECEIVED` | 6 時間 | 現物がまだ動かない |
| `PICKED_UP` · `IN_TRANSIT` | 3 時間 | 1 日に数回スキャンされる |
| `OUT_FOR_DELIVERY` | 30 分 | 今日届く — 最も頻繁に |
| `DELIVERED` · `RETURNED` | **中断** | 終わった配送を照会し続けることがクォータ浪費の主犯だ |
| `EXCEPTION` | 12 時間 | 人の介入を待っている |

ここに**連続失敗時のバックオフ**を加える。配送業者の API が落ちているときに同じ周期で叩き続けると、
復旧を妨げ、こちらのクォータだけを燃やす。

## 6. スキーマ

```sql
-- 配送業者マスタ。コードを追加するだけで新しい配送業者を認識させることが目的だ。
CREATE TABLE delivery.carrier (
    carrier_code      varchar(30)  PRIMARY KEY,   -- 'KOREA_POST', 'CJ', ...
    name_ko           varchar(60)  NOT NULL,
    name_en           varchar(60),
    tracking_url      varchar(300),               -- 顧客が直接見られる照会ページ
    active            boolean      NOT NULL DEFAULT true,
    supports_tracking boolean      NOT NULL DEFAULT true
);

-- 注文1件の配送全体。**追跡情報はここにはない** — 箱ごとに異なるからだ(§3)。
-- ここに残るのは、箱がいくつに分かれても1つだけのもの: 注文、顧客、目的地。
CREATE TABLE delivery.delivery (
    delivery_id       bigserial    PRIMARY KEY,
    -- 分割配送をしても注文ごとの配送コンテナは1つだ。この UNIQUE は維持される。
    order_id          bigint       NOT NULL UNIQUE,
    customer_id       bigint       NOT NULL,

    -- キャンセル後の再注文の紐付け。新しい注文がどのキャンセル分から出たかを残す。
    reordered_from_order_id bigint,

    -- 受取人スナップショット (§3.1 — 参照ではなくコピー)。箱が分かれても目的地は1つだ。
    recipient_name    varchar(80)  NOT NULL,
    recipient_phone   varchar(20),
    postal_code       varchar(12),
    address_line1     varchar(200) NOT NULL,
    address_line2     varchar(200),
    city              varchar(80),
    state             varchar(80),
    country           varchar(2)   NOT NULL DEFAULT 'KR',
    delivery_memo     varchar(200),                -- 「不在時は管理人室へ」

    created_at        timestamp    NOT NULL,
    updated_at        timestamp    NOT NULL,
    version           bigint
);

CREATE INDEX idx_delivery_customer ON delivery.delivery (customer_id, created_at);
CREATE INDEX idx_delivery_order    ON delivery.delivery (order_id);

-- 箱 = **追跡単位**。送り状番号・配送業者・状態・到着予定日がすべてここにある。
CREATE TABLE delivery.shipment (
    shipment_id       bigserial    PRIMARY KEY,
    delivery_id       bigint       NOT NULL REFERENCES delivery.delivery(delivery_id),
    sequence_no       int          NOT NULL,       -- 画面の「パッケージ 1/3」

    carrier_code      varchar(30),                 -- 箱ごとに異なり得る
    tracking_number   varchar(60),
    status            varchar(24)  NOT NULL,
    -- 配送業者が知らせた到着予定日。ないこともある。
    estimated_at      timestamp,
    dispatched_at     timestamp,
    delivered_at      timestamp,

    -- 国際配送。通関段階が付き、ポーリング周期も変わる。
    international            boolean NOT NULL DEFAULT false,
    customs_declaration_no   varchar(40),
    customs_clearance_no     varchar(40),

    -- ポーリング制御 (§5) — 箱ごとに独立して進行するので箱に付く。
    next_poll_at      timestamp,
    last_synced_at    timestamp,
    poll_failures     int          NOT NULL DEFAULT 0,

    created_at        timestamp    NOT NULL,
    updated_at        timestamp    NOT NULL,
    version           bigint,

    -- 同じ配送業者の中で送り状番号は一意だ。誤入力で他人の配送を上書きするのを防ぐ。
    CONSTRAINT uk_shipment_tracking UNIQUE (carrier_code, tracking_number)
);

-- ポーリング対象の照会用。これがないと、箱が溜まるほどスケジューラがフルスキャンをする。
CREATE INDEX idx_shipment_poll     ON delivery.shipment (next_poll_at);
CREATE INDEX idx_shipment_delivery ON delivery.shipment (delivery_id);

-- この箱に何が入っているか。分割配送で「どの箱に何が来たか」に答える。
CREATE TABLE delivery.shipment_item (
    shipment_item_id bigserial   PRIMARY KEY,
    shipment_id      bigint      NOT NULL REFERENCES delivery.shipment(shipment_id),
    order_product_id bigint,
    product_id       bigint,
    product_name     varchar(300),
    image_url        varchar(500),
    quantity         int         NOT NULL
);

CREATE INDEX idx_shipment_item_shipment ON delivery.shipment_item (shipment_id);

-- 発送前の住所変更履歴。**変更そのものより履歴が重要だ** — 品物が見当違いの場所へ行ったとき、
-- 誰がいつどこへ変えたかがなければ責任を明らかにできない。
CREATE TABLE delivery.address_change (
    address_change_id bigserial    PRIMARY KEY,
    delivery_id       bigint       NOT NULL REFERENCES delivery.delivery(delivery_id),
    previous_address  varchar(600) NOT NULL,       -- 人が読む1行のスナップショット
    new_address       varchar(600) NOT NULL,
    changed_by        bigint       NOT NULL,
    reason            varchar(200),
    changed_at        timestamp    NOT NULL
);

CREATE INDEX idx_address_change_delivery ON delivery.address_change (delivery_id, changed_at);

-- 配送業者のスキャン履歴。**箱に付く** — 分割配送なら箱ごとに別々のタイムラインだ。
CREATE TABLE delivery.delivery_event (
    event_id        bigserial    PRIMARY KEY,
    shipment_id     bigint       NOT NULL REFERENCES delivery.shipment(shipment_id),
    status          varchar(24)  NOT NULL,       -- 標準状態
    occurred_at     timestamp    NOT NULL,       -- 配送業者が知らせたスキャン時刻
    location        varchar(120),                -- '대전허브', '서울강남'
    description     varchar(300),                -- 画面に表示する文言

    -- ⚠️ 配送業者の原文をそのまま保存する(N-5)。マッピングが間違っていたとき、これがないと
    --    「なぜこの状態と判定されたのか」を再構成できない。
    raw_status      varchar(120),
    raw_payload     jsonb,

    -- 重複除去キー(N-3)。ポーリングは毎回全履歴を返すので、これがないと
    -- 同じイベントが照会回数分溜まる。
    dedup_key       varchar(120) NOT NULL,
    created_at      timestamp    NOT NULL
);

CREATE INDEX idx_delivery_event_timeline ON delivery.delivery_event (shipment_id, occurred_at);
```

> **上の SQL は論理スキーマだ。** 実際にデプロイされる DDL は
> `delivery-service/src/main/resources/db/migration/V2__baseline_schema.sql` で、それは手で
> 書いたものではなく、Hibernate がエンティティから作った DDL を `ops/tools/hbm2flyway.py` で冪等に変換した
> ものだ（docs/20）。両者がずれていたら **V2 が正しい** — そちらは `ddl-auto: validate` が起動のたびに
> エンティティと照合するので嘘をつけない。
>
> 2 か所は意図的に異なる:
> - `shipment.carrier_code` に `carrier` への FK がない。配送業者マスタはシードデータで、
>   FK を張ると、まだマスタにない配送業者の送り状が入ってきたときに配送登録自体が失敗する。
>   追跡できないことより、配送を記録すらできないほうが悪い。
> - `delivery_event` の `(shipment_id, dedup_key)` UNIQUE は論理的には正しいが張らなかった。
>   重複除去は保存前に `findDedupKeys` で除外する（§6.1）。制約でも防ぎたいなら別の
>   マイグレーションで追加すればよく、そのときはポーリングが `DuplicateKeyException` を飲み込まなければならない。

### 6.1 `dedup_key` をどう作るか

配送業者はイベントに安定した ID を付けないことが多い。そのため
`(スキャン時刻 + 原文の状態 + 拠点名)` のハッシュを使う。同じスキャンが繰り返し照会されても 1 回だけ保存される。

> 完璧ではない — 同じ拠点で同じ時刻に 2 回スキャンされると 1 つにまとまる。しかしその
> 損失より、**重複イベントがタイムラインを埋め尽くすほうがはるかに悪い。** 実用的な妥協だ。

## 7. イベントフロー

```
決済完了 (payment トピック)
   └─▶ delivery-service: Delivery 作成 (PENDING)
        └─▶ order-service: PAID → PROCESSING

送り状登録 (販売者 API)
   └─▶ status = INFO_RECEIVED, next_poll_at 設定
        └─▶ delivery-status トピック ─▶ notification-service (アプリ通知)

ポーリング (3時間ごと)
   └─▶ CarrierAdapter → 標準イベント一覧
        └─▶ 新しいイベントだけ保存 → 状態遷移の判定
             └─▶ 状態が変わったときだけ delivery-status を発行
                  ├─▶ notification-service: OUT_FOR_DELIVERY / DELIVERED は SMS
                  └─▶ order-service: DISPATCHED / DELIVERED を反映
```

**状態が変わったときだけイベントを発行すること**が重要だ。ポーリングのたびに発行すると 3 時間ごとに通知が行く。

## 8. フロントエンド — Amazon 風

Amazon の注文詳細の配送エリアをそのまま参考にする。

```
┌──────────────────────────────────────────────────┐
│  火曜日到着予定                                  │
│  ●━━━━━━━●━━━━━━━●───────○                       │
│  注文完了   発送     配送中   配達完了           │
│                                                  │
│  ▸ 配送詳細を見る                                │
│    12/03 14:20  大田ハブ   幹線荷降ろし          │
│    12/03 09:15  ソウル江南 集荷完了              │
│                                                  │
│  [ 配送業者の照会ページで見る ]                  │
└──────────────────────────────────────────────────┘
```

設計のポイント:
- **4 段階の進捗バー。** 9 つの内部状態をそのまま見せてもユーザーは理解できない。4 段階に畳み、
  詳しい履歴は折りたたみ領域に置く — Amazon がやっている方式だ。
- **到着予定日を一番上に大きく。** ユーザーが最も知りたいことの 1 つだ。
- **`OUT_FOR_DELIVERY` は強調。** 「今日届きます」が最も価値のある情報だ。
- **`EXCEPTION` は進捗バーを止めて案内を出す。** 進捗バーだけだと問題の状況が埋もれる。
- 配送業者の照会ページへのリンクを提供する — こちらが見せられない情報をユーザーが直接見られるように。

## 9. 実装順序の提案

| 段階 | 内容 | 備考 |
|---|---|---|
| 1 | delivery-service の骨格 + スキーマ + 決済イベントの購読 | 配送が作られ始める |
| 2 | `CarrierAdapter` + Simulated アダプタ | 実 API キーなしで全経路を検証 |
| 3 | ポーリングスケジューラ + 状態遷移 + イベント発行 | 核心 |
| 4 | 照会 API + フロントのタイムライン | ユーザーに見える部分 |
| 5 | KoreaPost アダプタ | キーを受け取ったらフィールドマッピングを合わせるだけでよい |
| 6 | order-service 連携（`DISPATCHED`/`DELIVERED` の反映） | 殻として残っていた状態がついに埋まる |

## 10. 未決の質問

| 質問 | 現時点の仮定 |
|---|---|
| 分割配送（1 注文に複数の箱）をサポートするか | **しない。** `order_id` UNIQUE。必要になったら shipment を入れる |
| 送り状番号は誰が登録するのか | 販売者/管理者 API。物流システム連携は後で |
| 国際配送 | スキーマに `country` はあるが、アダプタは国内のみ |
| 配送前の住所変更 | 未サポート。キャンセル後に再注文 |

## 11. 実装 — 途切れていた 2 つの節をつなぐ（2026-09）

§9 の 6 段階のうち 1〜5 はできていたが、**実際には配送が 1 件も作られておらず**、6 段階目は
そもそもなかった。どちらも「コードはあるのに接続がない」種類の欠陥なので、機能テストでは見えなかった。

### 11.1 🐛 配送が 1 件も作られなかった

`PaymentCompletedListener` は `payment` トピックのイベントの中から `shippingAddress` を探して
配送を作るようになっていた。ところがそのイベント（`PaymentResponse.avsc`）には**住所も品目も
ない** — `paymentId, orderId, customerId, couponName, amounts, paymentStatus` だけだ。
住所が見つからなければ例外を投げるようになっていたので、**すべての決済完了が `payment.DLT` へ行った。**
注文詳細の `GET /api/deliveries/order/{id}` 404 は「まだ」ではなく「永遠に」だった。

直し方 — **注文の持ち主に聞く。**

```
payment トピック (COMPLETED)
   └─▶ delivery-service: イベントに住所があればそれを、なければ
        GET order-service /api/order/internal/orders/{orderId}/shipping   (Feign、内部呼び出し)
        └─▶ CreateDeliveryCommand → Delivery(PENDING) → delivery-status 発行
```

- order-service `OrderShippingInfoService` + `OrderController.shippingInfo` — 受取人（名前
  2 つを結合）・住所・品目だけを載せる。**金額はない。** 保護ルールは購入集計と同じ
  （`InternalCallGuard.allowed` — ゲートウェイの身元ヘッダがあれば管理者のみ）。
- delivery-service `client/OrderServiceClient` — user-service の等級バッチと同じ形。
  **リクエストヘッダ転送のインターセプタがない。** リスナースレッドなので転送するリクエストがなく、身元ヘッダを
  載せると「外部リクエスト」になって 403 だ。プライベート CA は JVM のデフォルトトラストストア
  （`JAVA_TOOL_OPTIONS`）で信頼する。
- 決済が `CANCELED`（返金・キャンセル）で来たら `DeliveryService.cancelByOrder` — 発送前なら畳んで
  `CANCELLED` を発行する。集荷された後なら手を付けない（返品手続き）。
- 共通 DTO `common/dto/order/OrderShippingDto`。

### 11.2 order-service が `delivery-status` を聞く

`DeliveryStatusPublisher` は最初から「order-service が DISPATCHED/DELIVERED を反映する」
と書いて発行していたのに、聞く側がいなかった。`DeliveryStatusKafkaListener`（グループ
`order-delivery-status`、JSON 文字列、`delivery-status.DLT`）→ `OrderDeliveryStatusService`:

| 配送状態 | 注文状態 |
|---|---|
| `PENDING`（配送の作成） | `PROCESSING` |
| `INFO_RECEIVED` … `ATTEMPT_FAILED`、`EXCEPTION`（送り状登録以降すべて） | `DISPATCHED` |
| `DELIVERED` | `DELIVERED` + `deliveredCreatedAt` |
| `CANCELLED` | **`PAID` に戻る**（PROCESSING・DISPATCHED だった場合）。発送前キャンセルなので品物は出ていない — 返金が再び可能になる。§11.9 |
| `RETURNED` | 手を付けない — 返品入庫が処理する |
| 知らない状態 | 一緒に来る `stage` で判定 |

ルールは 2 つだけだ。**前にしか進まない**（PAID → PROCESSING → DISPATCHED → DELIVERED、再配信・
順序の入れ替わりに安全）そして**決済済みの注文だけを動かす** — `REFUNDED`・`CANCELED`・`COMPLETED`
はお金の状態が優先なので、配送イベントが遅れて来ても上書きしない。変わったら
`OrderCacheEvictor` でキャッシュを空にする（docs/49 §10 の Saga と同じ理由）。

### 11.3 管理者による送り状登録

- `GET /api/deliveries/admin?awaiting=true|false&size=` — 管理者のみ（`User-Role`）。デフォルトは
  送り状を待っている配送（箱のうち 1 つでも送り状なしで `PENDING`）、古い順。
- フロント `/admin/delivery`（サイドバー Orders → Deliveries）: 配送業者の選択（`/carriers`）+ 送り状番号
  → 既存の `POST /order/{orderId}/tracking`。登録後に一覧をサーバーから読み直す。

### 11.4 実際の配送業者なしで最後まで回してみる

シミュレータのスイッチは今や **configmap のキー**だ（`DELIVERY_SIMULATED_ENABLED`、
`DELIVERY_SIMULATED_STEP_MS`、`DELIVERY_POLL_INTERVAL_MS`）。以前は `48-delivery-service.yaml`
の `env` に `"false"` で埋め込まれていて envFrom より優先され、オーバーレイのパッチでは有効にできなかった。

```yaml
# k8s-overlays/local/patch-local-endpoints.yaml  (gitignore) の data:
DELIVERY_SIMULATED_ENABLED: "true"
DELIVERY_SIMULATED_STEP_MS: "60000"    # 1分ごとに1段階
DELIVERY_POLL_INTERVAL_MS: "20000"
```

反映は **ops/28 で**行う。`kubectl apply -k k8s-overlays/local` を直接叩くと、そのオーバーレイの
`newTag` が全サービスを古いバージョンに戻してしまう（docs/33 §オーバーレイの直接適用禁止）:

```bash
./ops/28-deploy.sh local --config-only --restart delivery-service
```

### 11.5 🐛 2 つ目の詰まり — 国名の 1 欄が決済を DLT に送った

§11.1 を直してデプロイしたが、配送は依然として作られなかった。ログにはこれだけがあった:

```
ERROR: value too long for type character varying(2)
```

**住所フォームは国を名前で保存する。** `SingularSelect` が `option.name` を値として使うので
`OrderAddress.country` には `"Korea, Republic of"` が入る。一方 `delivery.country` は
ISO コードなので `varchar(2)` だ。名前が同じ 2 つの値が、実は別物だった — 一方は**人が読む
表記**、もう一方は**コード**。

そしてその例外は配送 1 件ではなく、**決済レコード**をリトライの末に DLT へ送った。注文は
PAID なのに配送は永遠に作られず、画面には "배송 준비 중" だけが残った。§11.1 と症状がまったく同じなので、
同じバグが直っていないように見えた。

| 直したもの | 内容 |
|---|---|
| `common/i18n/CountryCodes` | 名前・alpha-2・alpha-3・韓国語・日本語 → alpha-2。JDK の CLDR 表記（`South Korea`）とフォームの ISO 公式表記（`Korea, Republic of`）が異なるので、**フォームの一覧 245 個をすべて回して**解決できないものだけを別名として入れた。`CountryCodesTest` がその一覧をファイルから読み、全件照合する |
| `Delivery` コンストラクタ | 国をコードに変える。識別できなければ発送国とみなす — **例外を投げない** |
| `Delivery.fit()` | すべての文字列の欄をカラム幅に合わせる。フォームは郵便番号を 30 文字まで受け付けるのに、カラムは 12 文字だった — **同じ種類がもう 1 つ残っていた** |
| リスナーのログ | どの分岐に進んでも 1 行残す。以前は「完了ではない決済」を黙ってスキップしていてログに何もなく、そのため「イベントを受け取れなかったのか、受け取ってスキップしたのか」を区別できなかった |
| `POST /api/deliveries/admin/order/{orderId}` | すでに DLT へ行った注文の復旧。バグを直してもそのレコードは再び来ない |

**なぜ切り詰めて先に進むのか。** これらの値は決済イベントを処理する途中で保存される。1 欄が長くて
例外が出ると、そのレコードは DLT へ行って配送は永遠に作られないのに、画面には何の手がかりも
ない。切り詰められた住所は管理者画面に見え、発送前に直せる — **見える誤りは見えない
欠落より良い。**

> 残るずれ: `OrderAddress.country` は依然として名前を保存している。フォームがコードを送るように変えれば
> 根本が整理されるが、すでに保存された住所が名前なので、サーバー側の変換はどのみち必要だ。

### 11.6 🐛 3 つ目の詰まり — 18 桁の注文番号がブラウザで変わった

配送は作られたのに、送り状登録がいつもこうだった:

```json
{"registered":false,"message":"주문 또는 택배사를 찾을 수 없습니다"}
```

送り状番号はどんな値でも受け付けるので原因ではなかった。注文番号が **TSID で 18 桁**であることが
原因だ。Java では `long` なので問題ないが、JSON の**数値**として返すとブラウザの
`JSON.parse` が倍精度浮動小数点数として読む。安全な整数の限界は `2^53-1`（約 9.0e15）なのに、TSID は
8.9e17 で 2 桁大きい:

```
889196209423729897  →  889196209423729920
```

桁数が同じなので、画面では正しく見える。その番号でリクエストすると、サーバーはそんな注文を知らない。

`OrderDto.id`・`OrderedProductDto.id` がすでに `String` である理由がこれなのに、新しく作った配送
DTO がその慣例に従っていなかった。同じ漏れが管理者の注文画面にもあった —
`AdminOrderSummaryDto.orderId` は画面に誤った番号を表示し（その番号を §11.5 の "배송이 없는
주문 잇기" に書き写すと失敗する）、`AdminOrderItemDto.orderProductId` は**部分返金が
品目を見つけられない**潜在バグだった。

すべて `String` に変えた。`DeliveryResponseIdTest` が実際の TSID で JSON を作り、
`"orderId":"889196209423729897"` であること、そしてその値が `double` で表現できないという前提まで
あわせて固定する。

### 11.8 🐛 画面の時刻が 9 時間ずれていた

サーバーは時刻を `LocalDateTime` で保存し、**タイムゾーン表示なしで**返す:

```
"2026-09-20T00:27:39.97"
```

値は UTC だ（サービスは `LocalDateTime.now(ZoneOffset.UTC)` で作り、コンテナのデフォルト
タイムゾーンも UTC だ）。ところが JavaScript は**タイムゾーンのない日付時刻文字列を現地時刻として
読む** — 仕様がそうなっている。ソウルから見ると、すべての時刻が 9 時間早く表示された。

ルール自体は `Z` を 1 文字付ければよいだけなのに、その 1 行が画面ごとに複製されていた。
**20 か所中 5 か所だけが付けていて、残りの 15 か所はすべて間違っていた** — 注文時刻、
配送作成時刻、銀行振込の期限、お知らせの掲載日、イベント期間まで。

`frontend/src/util/datetime.js` 1 か所に集め、すべてそれを使うようにした。`datetime.test.js`
が `util/datetime.js` の外での `new Date(値)` を禁止する — `envRefs.test.js` が
`process.env` の直接参照を防ぐのと同じ方式だ。新しい画面がまた複製すれば、ビルドが先に止める。

> **日付だけの値は別の読み方をする。** `"2026-09-20"` に `Z` を付けると UTC の深夜 0 時になり、
> タイムゾーンによってはカレンダーの日付が 1 日ずれる。日付だけの値は現地の深夜 0 時として読む。

> ⚠️ これらのファイルには**テストがカバーしていない画面が多かった。** 直す途中で import を誤って入れて
> 3 ファイルが壊れたが、Jenkins 相当の `npx jest` はすべて通った — その画面を呼ぶテストが
> なかったからだ。本番ビルド（`npx webpack --config webpack.config.prod.js`）を回さないと捕まらない。

### 11.9 🐛 デプロイ後の試験で明らかになった 3 つ

**① 送り状を登録したのに進捗バーが "주문 완료" に止まっていた。** 注文は DISPATCHED になり
"발송되었습니다" の通知まで出たのに、配送追跡の進捗バーはそのままだった。`INFO_RECEIVED` の
段階が `ORDERED` だったからだ。送り状登録がすなわち発送処理というのがこのシステムの定義（F-2）なので、
`SHIPPED` に合わせた。今や `ORDERED` 段階は `PENDING` だけだ。

**② 配送をキャンセルしても注文は DISPATCHED のままだった。** そのため "배송이 시작된 주문은 여기서
환불할 수 없습니다" に閉じ込められた — お客様が配送をキャンセルした理由は返金なのに、その道が塞がれた。
`OrderDeliveryStatusService` が `CANCELLED` を受け取ると注文を `PAID` に戻す。「前にしか
進まない」の唯一の例外であり、配送ドメインが発送後のキャンセルを防ぐので、品物が出た注文が戻る
ことはない。画面はキャンセルが受け付けられると（`DeliveryTracker.onCancelled`）、注文が DISPATCHED を
抜けるまで読み直す — 決済確定を待っていた `useOrderSettling` に `until` 条件を与えたもの。

**③ 木浦の住所の配送が `KP` と記録された。** 変換器（§11.5）は正しかった。保存された住所の国が
"Korea, Democratic People'S Republic of" だった — 一覧で "Korea, Republic of" の**すぐ上**なので
誤って選びやすい。すると配送が国際配送として作られて通関段階が挟まり、シミュレータも
通関のシナリオに乗る。住所フォームの国のデフォルトを発送国（"Korea, Republic of"）にし、変換器に
人が手で書く表記（"Republic of Korea"、"Korea, South" など）を加えた。2 つの韓国が混ざら
ないことは `CountryCodesTest` が固定する。**すでに保存された住所はお客様自身が直さなければならない。**

### 11.10 🐛 シミュレータでは配送完了を見られなかった — ポーリング周期

状態ごとのデフォルトの照会周期は実際の配送業者のリズムだ（受付後 6 時間、移動中 3 時間、配達出発
30 分）。シミュレータは 1 分ごとに段階を進めるのに、ポーラーは受付後 **6 時間**経ってからやっと再び問い合わせ、
そのとき履歴全体を受け取って**いきなり配達完了に飛んだ。** 集荷・移動・配達出発とその通知はすべて
消えた。`DELIVERY_SIMULATED_STEP_MS` をいくら縮めても無駄だった。

配送業者は自分の更新周期を知っている — `CarrierAdapter.pollIntervalCap()`。シミュレータは段階の間隔を
返し、箱はデフォルト周期とその値のうち**短いほう**で次の照会を設定する。伸ばすことには使わ
ないので、実際の配送業者（韓国郵便は空）は以前のままだ。`TrackingPollerSimulatedTest` が
受付 → 集荷 → 移動 → 配達出発 → 配達完了を 1 段階ずつ進むことを固定する。

### 11.6b シミュレータで最後まで回してみる

手順: 決済 → 注文詳細に "배송 준비 중"（PROCESSING）→ `/admin/delivery` で配送業者
**테스트배송**（コード `SIMULATED`）、送り状 `SIM-1` を登録 → 注文 DISPATCHED、"상품이 발송되었습니다"
通知 → ポーラーが `STEP_MS` ごとに 1 段階ずつ: 集荷 → 移動 → "오늘 배송될 예정입니다" 通知 →
**配達完了** + "배송이 완료되었습니다" 通知、注文 DELIVERED、`deliveredCreatedAt` が埋まる。

`STEP_MS` が 1 分なら、送り状登録後**4 分ほど**で配達完了だ（段階 5 つ、間隔 4 つ）。ポーラーの
`POLL_INTERVAL_MS` は `STEP_MS` より短くなければ 1 段階も逃さないとは言えない — 上の例（20 秒）なら大丈夫だ。
注文詳細は 20 秒ごとに配送を読み直すので、リロードしなくても進捗が見える。

| 送り状番号 | シナリオ | 配達完了まで（1 分段階） |
|---|---|---|
| `SIM-1` | 受付 → 集荷 → 移動 → 配達出発 → 完了 | 4 分 |
| `SIMFAIL-1` | 配達出発 → **配達失敗**（不在）→ 再出発 → 完了 | 6 分 |
| `SIMINTL-1` | 移動 → **通関保留**（通知）→ 通関完了 → 配達出発 → 完了 | 6 分 |
| `SIMEXC-1` | 移動 → **事故**（通知）で停止 — 完了しない | — |
| `SIMNONE-1` | 配送業者が送り状を知らない — 失敗カウンタが上がる | — |

> ⚠️ **送り状を登録しても "발송되었습니다" 通知は出ない。** 下記 §11.7 参照 —
> 通知は 6 つの状態でしか出ない。

### 11.7 どの配送状態で通知が出るか

2 つの関門を通って初めて通知が作られる。

1. **発行者側** — `DeliveryStatus.isWorthNotifying()` が真の状態だけが `worthNotifying: true`
   で載る。偽なら notification-service がそのイベントを捨てる（アプリ通知も残らない）。
2. **種類側** — `NotificationType.SHIPPING_UPDATED` は `smsWorthy = false` だ。
   **配送通知は SMS では出ない。** アプリ通知とメールだけだ。

| 配送状態 | 画面に表示される言葉 | 通知 | 文言 |
|---|---|---|---|
| `PENDING` | 配送準備中（注文 PROCESSING） | ❌ | |
| `INFO_RECEIVED` | 送り状登録 = 発送（注文 DISPATCHED） | ✅ | "상품이 발송되었습니다" |
| `PICKED_UP` | 集荷完了 | ❌ | |
| `IN_TRANSIT` | 配送中 | ❌ | |
| `CUSTOMS_CLEARED` | 通関完了 | ❌ | |
| `ATTEMPT_FAILED` | 配達失敗（再試行） | ❌ | |
| `OUT_FOR_DELIVERY` | 配達出発 | ✅ | "오늘 배송될 예정입니다" |
| `DELIVERED` | 配達完了 | ✅ | "배송이 완료되었습니다" |
| `CUSTOMS_HOLD` | 通関保留 | ✅ | 個人通関固有符号・関税の案内 |
| `EXCEPTION` | 配送事故 | ✅ | "배송에 문제가 발생했습니다" |
| `RETURNED` | 返送完了 | ✅ | "상품이 반송되었습니다" |
| `CANCELLED` | 発送前キャンセル | ✅ | "배송이 취소되었습니다" |

**選んだ基準は「受け取る人の行動が変わるか」だ。** 集荷・幹線移動は知ってもすることがなく、
その履歴は注文詳細の配送追跡がすでに見せている。

> **発送通知は後から有効にした。** 最初は外しておいたが、その理由は SMS 料金だった。ところが
> 配送通知（`SHIPPING_UPDATED`）はそもそも `smsWorthy = false` なので SMS では出ない —
> 根拠が成り立っていなかった。"상품이 발송되었습니다" は受け取る人が真っ先に待っている
> 知らせであり、ほとんどのショッピングモールが送っている。

**何が知らせるに値するかは配送ドメインが決める。** notification-service はイベントに載ってきた
`worthNotifying` フラグだけを見る。そのため発送を対象に加えるとき、通知側は文言を追加するだけで
済み、判定ロジックは `DeliveryStatus.isWorthNotifying()` 1 か所にしかない。

### 11.11 🐛 送り状を登録すると配送追跡だけが変わり、進捗帯・返金案内はリロードが必要だった

配送追跡（`DeliveryTracker`）は 20 秒ごとに自ら読み込んで "발송되었습니다" に変わった。ところが
すぐ上の注文の進捗帯は "상품 준비"、隣の返金案内は "주문 취소 · 환불 요청" ボタンの
ままだった — 注文は react-query が持っていて、**誰も読み直せと言わなかった。** 同じ
画面が互いに違うことを言っていた。

注文状態は同じ配送イベントを Kafka で受け取って変わるので（§11.2）、配送より数秒遅れる。だから
「配送が変わったので注文を 1 回読み直す」では足りない — その 1 回が早すぎると古い
値を受け取る。配送追跡が**状態が変わったのを見た瞬間** `onStatusChange(delivery)` で知らせ
（最初の読み込みと同じ状態は除く）、注文画面は `useOrderSettling` で注文が**その段階まで
追いつくまで** 2 秒ごとに読み直す（30 秒の期限）。「追いついた」の判定は `orderRank` /
`rankForDeliveryStage` — サーバーの `OrderDeliveryStatusService.targetOf`・`rank()` と同じ
対応だ。注文がすでにもっと先にあれば（購入確定の後に遅れて来た配送イベント）すぐに終わる。

**ガード**: `DeliveryTracker.test.jsx`（+2: 最初の読み込み・同じ状態では呼ばず、変われば呼ぶ、404 →
PENDING も変化だ）、`orderStatus.test.js`（+3）。

### 11.12 🐛 決済済みなのに詳細が NOT_PROCESSED — 決済ボタンが再び表示された（時々、特にデプロイ直後に）

DB は PAID なのに、注文詳細は "お支払いをお待ちしています" と決済ボタンを表示した。もう一度
試すと問題なかった — **競合状態**だ。

Saga（`OrderSaga.process`）は状態を PAID に変えた**トランザクションの中で** `OrderCacheEvictor` で
キャッシュを空にしていた。コミットはその後だ — 在庫・カートの発行と Kafka トランザクションのコミットまで終わった後。
その隙間に、確定を待つ画面（2 秒ごとに詳細を読む）の照会が入ってくると:

1. キャッシュが空なので DB を読む → まだコミット前なので **NOT_PROCESSED**
2. その値を `order` キャッシュに入れる → 空にした直後なので**そのまま 30 分**残る

デプロイ直後の最初の注文は、トランザクションプロデューサの初期化（`initTransactions`）でその隙間が数秒あるため、ほぼ毎回
引っかかった。`OrderCacheService` の `@Transactional` + `@CacheEvict` メソッドも同じ形だ。

**修正**: `RedisCacheManager` を `transactionAware()` で作る。トランザクション内の削除・格納は
**コミットの後に**起き、トランザクション外は以前のように即時だ。残る隙間は「照会がコミット前に DB を
読み、コミット・削除が終わった後に格納する」場合だけで、それは照会 1 回の読み込み→格納の間（ミリ秒）だ。

**ガード**: `RedisCacheTransactionAwareTest` — 接続ファクトリを mock にし、トランザクション同期の
中で `clear/evict/put` が Redis に触れず、コミット後の処理として登録されるかを見る。
`transactionAware()` を外すと失敗することを確認した。

> すでに引っかかった注文を今すぐ解くには、`order` キャッシュのキーを消すか 30 分待つ。注文
> 状態そのもの（DB）は PAID なので、一覧（キャッシュなし）では正しく見える。

## 12. 購入確定 — `DELIVERED → COMPLETED`、注文の最後の遷移（2026-09）

§0 の表で PROCESSING・DISPATCHED・DELIVERED を埋めた後も、**もう 1 欄空いていた。**
`OrderStatus.COMPLETED`（購入確定）は宣言だけあって、その値を入れるコードがリポジトリのどこにも
なかった。配達完了が終わりで、プロフィールの "구매 확정" フィルタは永遠に空だった — いや、
そのフィルタは `COMPLETED` を絞り込んでおいて、名前は "배송 완료" だった（§12.4）。

### 12.1 2 つの経路

| きっかけ | 誰が | 条件 | 残るもの |
|---|---|---|---|
| お客様が注文詳細で**購入確定**を押す | `POST /api/order/{orderId}/confirm` | 自分の注文で `DELIVERED` | `completedAt`、`autoCompleted=false` |
| 配達完了後 `ORDER_AUTO_CONFIRM_DAYS`（デフォルト 7 日）が過ぎる | `OrderPurchaseConfirmationService.autoConfirmSweep`（1 時間ごと） | `DELIVERED` で `deliveredCreatedAt < now − days` | `completedAt`、`autoCompleted=true` |

お客様が何も押さない場合がほとんどなので、**自動確定が実際には多数派**だ。そのため
`autoCompleted` を別途残す — 画面が "배송 완료 후 확정 기한이 지나 자동으로 확정되었습니다"
と表示し、通知も文言が分かれる。

### 12.2 ルール

- **冪等。** すでに `COMPLETED` なら 200 でそのまま返す。2 回押しても、自動確定と
  クリックが重なっても、2 回目は何もしない。
- **配達完了のみ。** 配送中・決済のみ済み・返金・キャンセルは 400 だ。画面はその状態ではボタンを描画
  しない（`canConfirmPurchase` は DELIVERED のみ）。
- 🔒 **他人の注文は 404。** 403 は「その番号の注文が存在する」ことを知らせてしまい、番号の総当たりを
  防げない — `GET /{orderId}` と同じルール（`OrderNotFoundException`）。
- **確定が新たに閉じる扉はない。** お客様自身による返金は配達完了の時点ですでに閉じており
  （`SELF_SERVICE_REFUNDABLE` = PAID・PROCESSING）、管理者の返金・返品入庫は確定後も開いて
  いる（`SHIPPED_STATUSES` に COMPLETED を含む）。売上集計（`PAID_STATUSES`）には配達完了と
  まったく同じく計上される。つまり確定は**「受け取った、問題ない」というお客様の言葉**だ。
- **配送イベントは確定を元に戻せない。** `OrderDeliveryStatusService.MOVABLE` の外なので、
  遅れて来た配送イベントが COMPLETED を上書きしない（§11.2）。

### 12.3 通知 — `order-completed`

order-service がコミットの**後に** JSON で発行する（`OrderCompletedKafkaPublisher`、
`afterCommit` 同期 — コミットが失敗したのに「確定した」という通知だけ出てはならない）。

```json
{"orderId": 1234, "customerId": 7, "status": "COMPLETED", "auto": true,
 "completedAt": "2026-09-20T10:00:00", "eventId": "order-completed:1234"}
```

notification-service の `ORDER_COMPLETED` は `smsWorthy=false` — お金が動かないので SMS では
ない。アプリ通知とメールだけ。`eventId` が注文ごとに 1 つなので、再配信されても通知は 1 つだ。

> ⚠️ 新しいトピックだ。`ops/02-create-dlt-topics.sh` の `TOPICS` に `order-completed` を加えたので、
> デプロイ前に 1 回回して DLT を作ること（B-41）。notification-service は V9 で 2 つの CHECK
> （`notification.type`・`mail_message.kind`）に値を加える — ガード B-55 が捕まえた。

### 12.4 🐛 あわせて直したもの — "배송 완료" フィルタが `COMPLETED` を絞り込んでいた

プロフィールのサイドバーと注文一覧タブの "Delivered Orders / 배송 완료" が `filter: "COMPLETED"`
だった。届いた注文は `DELIVERED` なので、このタブはいつも空だった。`DELIVERED` に正し、
"구매 확정(COMPLETED)" タブを別に設けた。注文詳細の状態文字の色も `order.status == "COMPLETED"`
を見ていた — DTO のフィールドは `orderStatus` なので常に偽で、すべての注文が赤い文字だった。

### 12.4b 🐛 最初のデプロイで order-service が起動できなかった

```
APPLICATION FAILED TO START
Parameter 0 of constructor in TokenRequestKafkaPublisher required a single bean, but 2 were found
```

通知発行用に `KafkaTemplate<String, String>`（`orderCompletedKafkaTemplate`）をもう 1 つ作ったが、
既存の `TokenRequestKafkaPublisher` が同じ型を `@RequiredArgsConstructor` で**名前なしに**注入されて
いた。lombok は `@Qualifier` をコンストラクタに移してくれないので、コンストラクタを直接書いて
`tokenUpdateKafkaTemplate` を名前で受け取る。

発行者の単体テストはモックで作られていたので、**Bean の配線は一度も検査されていなかった。** `KafkaPublisherWiringTest` が
実際の `KafkaProducerConfig` と発行者 7 つを Spring コンテキストで立ち上げる（ブローカーには接続しない）。古いコードに
戻すと本番と同じ `NoUniqueBeanDefinitionException` で失敗することを確認した。

### 12.5 シミュレータで最後まで

§11.6b のとおりに `SIM-1` を配達完了まで送った後:

1. 注文詳細に**購入確定**ボタンが表示される。押すともう一度確認され、確定すると "구매가 확정된
   주문입니다 · 확정 시각" に変わり、通知 "구매가 확정되었습니다" が届く。
2. 自動確定を見るには、オーバーレイで `ORDER_AUTO_CONFIRM_DAYS: "0"` に上書きして
   order-service を再起動する（`ops/28-deploy.sh local --config-only --restart order-service`）。
   ジョブは起動 2 分後に初回実行、その後は 1 時間ごとだ。確定した注文には "자동으로 확정되었습니다"
   が付き、通知の文言も "확정 기한이 지나 자동으로" だ。
3. プロフィールの**購入確定**タブにその注文が表示される。

設定: `ORDER_AUTO_CONFIRM_ENABLED`（デフォルト true）· `ORDER_AUTO_CONFIRM_DAYS`（デフォルト 7）は
`k8s/10-configmap.yaml` のキーだ。レプリカが複数あるとジョブが Pod ごとに走る — 同じ注文を 2 つの
Pod が同じ瞬間に読むと、両方が確定してイベントを 2 回出し得るが、状態は同じで通知は
`eventId` で 1 つだ。今は単一ノードなので問題ではなく、増やすときに ShedLock を付ける。
