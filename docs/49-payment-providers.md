# 49. 決済手段を増やせるように — ポート、そして Stripe · PortOne

**日本語** | [한국어](49-payment-providers.ko.md)

韓国では PayPal のライブ決済がブロックされていて、決済以降の過程を確認できないというところから始まった。
決済手段をもう1つ付けようとしたところ、**付ける場所がなかった。**

---

## 1. なぜリファクタリングが先だったのか

決済確認は `OrderDataService.persisitPaypalPayment` という1つのメソッドで、そこに **2種類が
混ざって** いた。

| | |
|---|---|
| **PayPal だけに該当** | PayPal REST で注文を照会し、status が `COMPLETED` かを見る |
| **すべての決済に該当** | 確認した金額を **DB の注文総額** と照合 · 通貨の照合 · その決済参照がすでに使われていないかの遮断 · 決済 Outbox への記録（docs/35 の4つのルール） |

混ざっていると、**決済手段を追加するたびに下のルールがコピーされる。** そしてコピーの1つで
金額照合が抜けると、**その手段でだけ** 決済なしに注文が完了する — docs/35 の元の欠陥が
まさにその形だった。

## 2. ポートを1つだけ切り出した

```java
public interface PaymentGateway {
    PaymentProvider provider();
    VerifiedPayment verify(String providerPaymentId);   // プロバイダーに直接問い合わせる
}
```

下のルールは `recordPayment` **1つだけが** 持つ。**新しいプロバイダーはそれを自動的に継承する。**

決済手段を追加する作業は、今やこれだけだ:

1. `PaymentProvider` に値を1つ
2. `PaymentGateway` の実装ビーンを1つ

レジストリ（`PaymentGateways`）は Spring が見つけたビーンを集めてインデックス化するので、**直す必要がない。**
ガードは `PaymentGatewaysTest` — 「ゲートウェイを差し込むだけで登録される」を固定する。

### ポートが2つある理由

プロバイダーごとに **開始方式が違う。**

| | 決済画面を誰が開くか | ポート |
|---|---|---|
| PayPal | ブラウザ SDK | `PaymentGateway` |
| PortOne | ブラウザ SDK（`IMP.request_pay`） | `PaymentGateway` |
| Stripe Checkout | **サーバーがセッションを作り**、ブラウザが遷移 | `HostedCheckoutGateway` |

開始段階のないプロバイダーに空のメソッドを強要しないために分けた。**確認ルールは変わらず
1つだ** — 違うのは開始だけである。

> ⚠️ order-service 全体を ports/adapters に再配置することは **しなかった。** 決済の境界1つが
> レバレッジの効くポイントであり、残りは回帰リスクが大きいだけだ。PayPal の実装は <b>アダプターで包んで</b>
> 手を付けなかった（`PaypalPaymentGateway`）— うまく動いていた決済経路に触れないためだ。

## 3. スキーマは expand-contract

`paypal_order_id` を rename すると元に戻せなくなる（B-54）。ユニークインデックスがかかったカラムなので
なおさらだ。そこで広げるだけにした — `payment_provider` · `provider_payment_id` を追加して
バックフィルし、**古いカラムは次のリリースまで残す**（V4）。

### 🔴 テストがローリングデプロイのリスクを1つ捕まえた

古いイメージが反映した決済は `paypal_order_id` にだけ値がある。新しいコードが新しいカラムだけを見ると、
その決済を「まだ紐付いていない」と判断して **決済イベントをもう一度発行する** — 同じ行なので
ユニーク制約でも防げない。冪等判定が **古いカラムも見る** ようにした。

ローリングデプロイ中の短いウィンドウでしか起きない種類なので、テストがなければ再現すら難しい。

### 古いカラムの整理（contract）— 2つのリリースに分ける

> この節の移行を、デプロイトレイン（どのコミットで区切って上げるか）と教訓まで含めてまとめたのが [docs/55](55-zero-downtime-migrations.md) である。

古いカラムの「次のリリースで消す」を一度にやると、**同じウィンドウが逆方向に開く。** ローリング中は古い Pod（エンティティが
`paypal_order_id` をマッピングしている）がしばらく一緒に動いており、新しい Pod のマイグレーションがカラムを消した瞬間、その Pod の **注文照会が
すべて失敗する** — Hibernate はマッピングしたカラムを SELECT リストに入れる。だから2段階である。

| 段階 | リリース | やること | 元に戻すこと |
|---|---|---|---|
| 1 ✅ | コミット `8f4a131` | エンティティが古いカラムをマッピングしない。冪等チェック・決済済み注文のキャンセル拒否・返金通知が新しいカラムだけを見る。**V17** が古いカラムにだけ残っている値を新しいカラムに移す（冪等） | 安全 — 値をコピーするだけで、古いイメージは両方のカラムを見る |
| 2 ✅ | 1段階目が安定した **次の** リリース | **V18** がカラムとインデックス（`uk_order_paypal_order_id`）を消す。1段階目を飛ばしたデプロイなら自ら止まる（下記「飛ばし防止」） | `ROLLBACK-UNSAFE` — 1段階目より前のイメージは起動できない |

再利用の遮断は最初から最後まで変わらない — `(payment_provider, provider_payment_id)` の部分ユニーク（V4）が防ぐ。

### pay-service もプロバイダー中立に

`pay.payment` は PayPal の参照だけを `paypal_order_id` に記録していた — Stripe・PortOne・銀行振込の決済は「どの決済か」が残って
いなかった。🐛 そして **伝わってもいなかった。** 決済リクエストの DTO には `paymentProvider`・`providerPaymentId` があったが、Avro
スキーマ（v2）にそのフィールドがなく、リレー経路ではマッピングが静かに捨てていた（CDC の JSON 経路にだけ載っていた）。再発防止として、すべての
マッピングの DTO ↔ スキーマのフィールドを名前と往復で照合するテストを置いた（`AvroMappingContract`、docs/55 §8）。

| 段階 | リリース | order | pay | 元に戻すこと |
|---|---|---|---|---|
| A ✅ | コミット `6b8cf08` | 決済リクエストを **v2 の書き込みスキーマで** 書く | すべてのコンシューマー（pay・saga-projection・文字列コンシューマー）が v3 を読む。**V5** — 新しいカラム2つ + `payment_type` からプロバイダーを、`paypal_order_id` から参照を移す。エンティティは新しいカラムだけをマッピングする。v2 レコードなら決済手段・PayPal 参照から決める | 安全 — カラムの追加・コピーだけで、古いイメージは `paypal_order_id` で判断しない（保存のみ） |
| B ✅ | A が安定した次（order の2段階目と同じリリース） | **v3 で書く** → Stripe・PortOne・銀行振込の参照も pay に届く。メッセージの `paypalOrderId` はもう埋めない | **V6** — A のローリング中に古い Pod が書いた行をもう一度移し、カラムを消した後 `idx_payment` を古いカラムなしで作り直す。A を飛ばしたデプロイなら自ら止まる | `ROLLBACK-UNSAFE` |
| C1 ✅ | B の次（コミット `0836bb9`） | v3 で書き続ける。**次のスキーマ v4**（`paypalOrderId` を削除）を `avro-next/` に置き、すべてのコンシューマーのデコーダーが事前に知っておく。**V19** はリリースマーカー（下記） | 変化なし（デコーダーのみ） | 安全 |
| C2 ✅ | C1 が行き渡った後 | `PaymentRequest.avsc` を v4 に変え（v3 は `avro-history/` へ、`avro-next/` のファイルは削除）v4 で書く。DTO の `paypalOrderId` を削除。**V20**（ガード専用）が V19 を見て、C1 を飛ばしたデプロイを止める（V18 と同じ方式） | 参照は `providerPaymentId` 1つだけを読む。v2 レコード（A 以前・A の order）の PayPal 参照は v4 で抜けたフィールドにあるので **空になる** — DLT の再処理でしか来ることはなく、元の参照は order の `provider_payment_id` にある | 安全（古いコンシューマーは C1 なので v4 を知っている） |
| C3 | B のデプロイから **14日** 後（DLT の保持期間） | — | v2 の代替経路（`PaymentService.providerOf` の `paymentType` → プロバイダー）と `avro-history/PaymentRequest-v1·v2` を消す。pay のマイグレーションが V6 の適用時刻を見て、14日前なら止める。v3 の履歴は C2 のデプロイから14日後に同じ方式で | — |

**C はなぜ方向が逆なのか。** フィールドを **足す** とき（A・B）は「新しい生成クラスのレコードを古い書き込みスキーマで書く」で1つのリリースを
しのいだ — 新しいフィールドを末尾に付けたので、古いフィールドの位置は同じだった。フィールドを **抜く** ときはそれができない。`paypalOrderId` は真ん中（4番目の）
フィールドなので、抜くと後ろのフィールドの位置がすべてずれ、v4 クラスを v3 スキーマで書くと値が見当違いの欄に入る。だから逆に進む —
C1 は **生成クラスを v3 のまま** にして、次のスキーマ v4 をデコーダーのストアに事前に入れる。ストアはフィンガープリントで書き込みスキーマを探す
だけなので「古い」と「次」を区別せず、読むときは Avro のスキーマ解決（v4 → v3）が抜けたフィールドをデフォルト値（null）で埋める。
C2 で v4 で書き始めても、ローリング中に残っている C1 の Pod が読める。v4 のファイルは手で置くので、現在のスキーマとずれないようにテストが
照合する（正規形の比較）。

**C をなぜ3つに分けるのか。** C2（書き込みの切り替え）は **リリースの順序** が条件で、C3（v1・v2 の読み取り経路の削除）は **時間** が条件である —
前のリリースの order が書いた v2 レコードが、トピック（7日）と DLT（14日、`ops/13`）から消えていなければならない。1つのコミットにまとめると、時間条件の
ガードが14日間デプロイ全体を止めてしまう。異なる条件は異なるリリースに置く。

**リリースマーカー（V19）。** C1 は DB を変えないが、C2 のガードは「C1 がすでにデプロイされたか」を知る必要がある。そこで何もし
ないマイグレーション（`SELECT 1`）を置く — Flyway 履歴の `installed_on` がそのまま C1 のデプロイ時刻になる。消すと C2 のガードが C1 を
飛ばしたデプロイとみなしかねないので、消してはならない。

**なぜ order は1リリース遅れて v3 で書いたのか。** シングルオブジェクトエンコーディングは書き込みスキーマのフィンガープリントを載せ、読む側は知っているフィンガープリントしか読めない。
ops/28 はサービスをほぼ同時に入れ替えるので、「読む側を先に」をデプロイ順序で守ることはできない — 新しい order がすぐ v3 で書いていたら、
ローリング中の古い pay は決済リクエストを DLT に送り（決済が反映されない）、古い saga-projection はデシリアライズ失敗でストリームが止まっていた。
そこで A は **読み取りは v3、書き込みは v2** だった。新しいフィールドをスキーマの末尾に付けたので v2 のフィールドの位置は同じで、v3 レコードを v2
スキーマでそのまま書ける（新しいフィールドだけが抜ける）。B のローリング中の古い Pod は A なので、すでに v3 を知っている。レジストリがあれば、この作業は
互換性ルールが代わりに担う。

### 🔴 飛ばし防止 — 2段階目のマイグレーションが1段階目のデプロイを確認する

2段階目（order V18・pay V6）は **1段階目が本番で行き渡った後** でしか安全ではない。2つのリリースを一度に上げると、ローリング中に1段階目より前の
Pod が消されたカラムを SELECT し（注文・決済の照会がすべて失敗）、order の v3 レコードを v2 しか知らない pay・saga-projection が読め
ない。人が順序を覚えておく代わりに、マイグレーションが確認する:

- Flyway 履歴で1段階目のマイグレーション（order V17 · pay V5）の `installed_on` を見る。**30分以内に** 適用されていて、DB の最初の
  マイグレーションより10分以上後なら = すでに本番稼働中だった DB に1段階目と2段階目が **1回のデプロイで** 上がってきた → 例外で失敗する。
- PostgreSQL は DDL もトランザクションなので、2段階目のファイルは何も変えない（1段階目はすでにコミットされている — それは安全だ）。新しい order の
  Pod が準備完了にならないので **v3 レコードも出ていかない** — スキーマ側のリスクまで同じ仕組みが防ぐ。30分後に再デプロイすれば通る。
- 新しい DB（すべてのマイグレーションが一度に走る場合）には古い Pod がないので通る。履歴テーブルがなくても通る（fail-open）。
- PG16 で確認: 飛ばし → 拒否・カラムが残る / 1段階目の2時間後 → 削除・再移行・インデックス再作成 / 新しい DB → 通過 / 履歴なし → 通過。

開発 DB で2つのコミットを一度に取り込んで起動すると、このガードに引っかかる — 30分後に再起動すればよい。

ガード（テスト）: `PaymentRequestSchemaEvolutionTest`（order は v4 で書く、🔴 C1 のコンシューマー（v3 + 次のスキーマ v4）が C2 の
レコードを読める、v3 しか知らないコンシューマーは v4 を読めない、v3・v2 のレコードを読める、v3 は末尾にだけ足した、v4 は v3 から
`paypalOrderId` だけを抜いたものである）· `PaymentProviderRecordTest`（v3 はそのまま、v2 は PayPal 参照・決済手段から、
CASH → BANK_TRANSFER、エンティティが古いカラムをマッピングしない）· `PaypalPaymentVerificationTest`（新しいカラムに記録、メッセージはプロバイダー中立の
フィールドで、`paypalOrderId` は空、すでに反映された決済は再発行なし、エンティティが古いカラムをマッピングしない）。

## 4. Stripe — Checkout リダイレクト + Webhook

**カード情報がこちらのページを一切経由せず、フロントに Stripe のキーが要らない。** サーバーが
セッションを作るからだ — PayPal で経験した「ビルド時に埋め込まれる client id」の問題が構造的に
起きない。

確定経路が **2つあり、どちらも必要である。**

| | 何のためのものか | なければ |
|---|---|---|
| **復帰時の検証** | ユーザーにすぐ結果を見せる | 決済直後の画面が「確認中」のまま止まる |
| **Webhook** | 権威ある経路 | 決済直後にタブを閉じると **お金は引き落とされたのに注文は未決済** |

2つが重なるのは正常で、よく起きる。`recordPayment` が冪等なので、どちらが先に
来ても結果は同じだ。

### Webhook の署名 — ここだけは自前で実装した

Webhook のエンドポイントは **認証なしで公開** されなければならない（Stripe がこちらのトークンを持てるはずがない）。
だから「本当に Stripe が送ったのか」には **署名だけが答える。** 間違っていれば、その URL に JSON を
POST するだけで決済が改ざんされる。

SDK なしで実装した代償として、境界条件をすべて固定した — 別のシークレットキー · 本文の改ざん · 時刻の期限切れ ·
未来の時刻 · ヘッダーなし · 形式の破損 · `v1` の複数 · `v0` のみ · 16進数ではない（13件）。

> リプレイ防御が核心だ。**署名そのものは永遠に有効である** — 時刻を見なければ、一度
> 傍受したリクエストを数か月後でもそのまま再送できる。

**ゲートウェイのルートを order-service のルートより上に置いた。** 順序が入れ替わると Webhook も
`JwtAuthorization` を通って 401 になり、Stripe はリトライした末に諦める。

## 5. PortOne（旧 I'mport）— 韓国国内の決済

ブラウザが決済画面を開く（`IMP.request_pay`）。サーバーは `imp_uid` を **PortOne に改めて問い合わせて**
確認する。

### 🔒 PortOne V1 の Webhook には署名がない — それでも安全な理由

Stripe と違い、送信者を証明する署名がない。誰でも呼び出せる。それでも決済を
改ざんできないのは、**本文を手がかりとしてしか使わない** からだ:

1. 本文から取るのは `imp_uid` **1つだけ** — 金額・状態・注文参照は **すべて捨てる**
2. その `imp_uid` を **PortOne の API に再照会** — 状態・金額・`merchant_uid` はそのレスポンスからだけ
3. `merchant_uid` は **こちらが作った trackingId** — 他人の決済を送っても、こちらの注文を指していなければそれで終わり
4. 金額を DB の注文総額と照合
5. 同じ決済を2つの注文に付けることはユニーク制約が防ぐ

つまり攻撃者にできる最大限は **すでに存在する正常な決済をもう一度確認させること** であり、
反映が冪等なので何も起きない。**本文をそのまま信じた瞬間、この論理は
すべて崩れる** — だから1番が核心である。

署名が必要なら PortOne V2 に上げなければならない（V2 は Standard Webhooks の署名を使う）。

### ブラウザが金額を決めてもよい理由

`IMP.request_pay` には金額が入る。つまり **ブラウザが決済金額を決める。** それでもよい
のは、確認段階で **PortOne が知らせてきた実際の決済額を DB の注文総額と照合** する
からだ。1ウォンに変えて決済すると、決済は成功するが **注文には反映されない。**

> ⚠️ この照合を抜くと、即座に無料注文が可能になる。ポートを作った理由は、このルールをプロバイダーごとに
> 書き直さないためだ。

## 6. 設定

| | Stripe | PortOne |
|---|---|---|
| 有効化 | `STRIPE_ENABLED` | `PORTONE_ENABLED` |
| サーバーの資格情報 | `STRIPE_SECRET_KEY` · `STRIPE_WEBHOOK_SECRET` | V1 `PORTONE_API_KEY/SECRET` · V2 `PORTONE_V2_API_SECRET`+`_WEBHOOK_SECRET` |
| フロントのビルド引数 | **なし** | V1 `REACT_APP_PORTONE_IMP_CODE` · V2 `REACT_APP_PORTONE_STORE_ID`+`_CHANNEL_KEY` |
| test/live の区別 | **キーが教えてくれる**（`sk_test_`/`sk_live_`） | **キーではわからない** — ストアの設定が決める |

### Stripe を接続する手順（テストモード）

合わせるべき値は **3つ** で、それぞれ出てくる場所が違う。

| 値 | どこで出てくるか | どこに入れるか |
|---|---|---|
| `STRIPE_SECRET_KEY` (`sk_test_…`) | ダッシュボード → Developers → **API keys** → Secret key | `k8s/20-secret.yaml` |
| `STRIPE_WEBHOOK_SECRET` (`whsec_…`) | ダッシュボード → Developers → **Webhooks** → エンドポイントを **作るとき** に一度だけ表示される | `k8s/20-secret.yaml` |
| `STRIPE_ENABLED` | — | `k8s/10-configmap.yaml`（`"true"`） |

1. **ダッシュボード右上の「Test mode」を有効にした状態** でキーを受け取る。キーの接頭辞が
   `sk_test_` かを確認する — order-service の起動ログが `Stripe 테스트 모드 (sk_test_…)`
   と改めて教えてくれる。`sk_live_` なら警告が出力され、**実際のお金が動く。**
2. Webhook のエンドポイントを作る:
   - URL: `https://<サイト>/order-service/api/order/payment/stripe/webhook`
   - イベント: **`checkout.session.completed`** と **`checkout.session.async_payment_succeeded`**
     — コードが見るのはこの2つだけで、残りは受け取っても無視する
   - 作成直後に出る `whsec_…` をその場でコピーする。**後から見直すことはできない**
     （Reveal で見ることはできるが、別の値が発行されてしまう場合がある — そうなるとシークレットも
     差し替えなければならない）
3. 2つの値を `k8s/20-secret.yaml` に入れ、configmap の `STRIPE_ENABLED` を `"true"` に:
   ```bash
   kubectl apply -f k8s/20-secret.yaml
   # 🔴 `kubectl apply -k k8s-overlays/local` を直接打たないこと — そのオーバーレイの newTag が
   #    全サービスを古いバージョンに戻してしまう。--config-only は今動いているイメージを維持する。
   ./ops/28-deploy.sh local --config-only --restart order-service
   kubectl -n ecommerce logs deploy/order-service | grep -i stripe
   #   "Stripe 테스트 모드 (sk_test_…)"  +  "Stripe 결제 활성화 (endpoint=https://api.stripe.com)"
   ```
   **有効にしたのにキーが空なら起動しない。** どの値が空かがログに出力される。
4. 決済画面で **Stripe** を選び、テストカードで決済する: 番号 `4242 4242 4242 4242`、
   有効期限は未来の任意の日付、CVC は任意の3桁。失敗を見るには `4000 0000 0000 0002`（拒否）。
5. 確認は2か所で:
   - ブラウザが `/order/<id>?stripe=success&session_id=…` に戻り、「決済完了」を描画する
   - ダッシュボード → Webhooks → そのエンドポイント → 最近の送信が **200** か。4xx なら署名の
     不一致（シークレットが間違っている）かゲートウェイの 401（ルートの順序、§4）である

> 🔴 復帰先アドレスは `APP_PUBLIC_URL` から作る（`frontend.url`）。以前は `FRONTEND_URL`
> だけを見ていたが、**どのマニフェストもその変数を設定していなかったため**、クラスターでは静かに
> `https://localhost:3000` になっていた — 決済後、ブラウザはユーザー自身の PC の 3000 番ポートに
> 行っていたはずだ。今は configmap の `APP_PUBLIC_URL` がサイトのアドレスであればよい。

> 通貨は `STRIPE_CURRENCY` が決める（下記「通貨」の節）。日本の決済を試すには `JPY` にして
> `FX_RATE_JPY` を埋める — Stripe ダッシュボードのアカウント通貨の設定とは関係なく、Checkout は JPY の
> セッションを作れる。最小単位（JPY は ×1）はコードが通貨から導く。

**ローカルで Webhook を受けるには**、公開 URL がないので Stripe CLI が代わりに転送する:
```bash
stripe listen --forward-to localhost:8084/api/order/payment/stripe/webhook
#   ▶ Ready! Your webhook signing secret is whsec_…   ← この値を STRIPE_WEBHOOK_SECRET に
stripe trigger checkout.session.completed
```
CLI が出す `whsec_` はダッシュボードのものと **異なる** — ローカルは CLI の値、クラスターはダッシュボードの値である。

### 🔴 まず — 自分の PortOne アカウントは V1 か V2 か

コンソール → 決済連携の画面を見る。

| 見えるもの | アカウント | サーバー | フロントのビルド引数 |
|---|---|---|---|
| **加盟店識別コード `imp…`** | V1（I'mport） | `PORTONE_VERSION=v1` · `PORTONE_API_KEY/SECRET` | `REACT_APP_PORTONE_IMP_CODE` · `_PG` |
| **Store ID `store-…`** のみ | V2 | `PORTONE_VERSION=v2` · `PORTONE_V2_API_SECRET` · `PORTONE_V2_WEBHOOK_SECRET` | `REACT_APP_PORTONE_STORE_ID` · `_CHANNEL_KEY` |

**2024年以降に新しく作ったアカウントは V2 だけである。** `imp…` がなければ下の「V1」の節は飛ばして
「V2」の節へ進む。2つのバージョンは API・認証・Webhook がすべて違い、アダプターもそれぞれある
（`PortOnePaymentGateway` · `PortOneV2PaymentGateway`）。同じプロバイダーにビーンが2つあると起動を
止めるので、1つだけが有効になる。

### 🔴 通貨 — 決済手段ごとに異なる

注文には金額が **2つ** ある。

| | 通貨 | どこに | 何の基準か |
|---|---|---|---|
| `Order.total` | **ストア通貨**（`STORE_CURRENCY`、デフォルト USD） | カタログ価格 · 売上集計 · pay-service · 購入履歴 | 会計 |
| `Order.chargedAmount` + `currency` | **請求通貨**（`<PROVIDER>_CURRENCY`） | そのプロバイダーに実際にリクエストした決済 | 決済確認 · 返金 |

国内 PG（PortOne）は KRW でしか決済できず、日本の決済を試すには Stripe・PayPal を JPY で
回さなければならない。そのため **請求通貨は決済手段ごとに決める:**

```yaml
# k8s/10-configmap.yaml
STORE_CURRENCY:   "USD"     # ストア通貨 — 変えない
PAYPAL_CURRENCY:  "USD"
STRIPE_CURRENCY:  "JPY"     # 日本向けテスト
PORTONE_CURRENCY: "KRW"
FX_RATE_KRW: "1350"         # ストア通貨 1 単位 = 請求通貨でいくら
FX_RATE_JPY: "150"
```

**注文を作るとき**、その手段の通貨がストア通貨と異なれば、為替レートで請求額を決めて注文に
保存する（V6: `currency · charged_amount · fx_rate`）。決済確認と返金は **その保存値** と
照合する — その間に為替レートが変わっても「そのとき請求した金額」が基準だ。再計算すると、為替レートが
変わった後の確認・返金がすべて拒否される。

3つのものが通貨に従う:

- **小数の桁数** — KRW・JPY は 0、USD は 2。`java.util.Currency` が知っている。ここを間違えると
  Stripe に 100倍（JPY に ×100）を送ったり、PayPal が `DECIMALS_NOT_SUPPORTED`（"1500.00"）
  で拒否したりする。`PaymentCurrencyPolicy.toMinorUnits / plain` が唯一の場所である。
- **フロント** — 決済画面は `order.currency · chargedAmount` をプロバイダーに渡す
  （`util/money.js`）。PayPal は SDK に通貨が埋め込まれるので、請求通貨が違えば SDK をその
  通貨で **再ロード** する（`resetOptions`）。請求通貨がストア通貨と異なれば、画面に
  「決済金額 ¥8,880（注文総額 $59 基準）」を表示する。ストア通貨はビルド引数
  `REACT_APP_STORE_CURRENCY`（ops/19 のデフォルト USD）でバンドルに埋め込まれる（B-51）。

  🐛 **その1行が本番のトップ画面を殺した。** webpack の設定が `EnvironmentPlugin([名前4つ])`
  だったので、リストにない `process.env.REACT_APP_STORE_CURRENCY` は置換されないままバンドルに
  残り、ブラウザには `process` がないため `Uncaught ReferenceError: process is not defined`
  でアプリ全体が起動しなかった（`util/money.js` はモジュールの最上部で読む）。PortOne のキー
  4つも同じ状況だった — 決済ボタンを押した瞬間に同じエラーが出ていたはずだ。テストは Node
  なので `process` があり、何も捕まえられなかった。今は `webpack.env.js` がビルド環境の
  `REACT_APP_*` を **すべて** `process.env` オブジェクトとして定義し（CRA 方式 — リストを手で
  管理しない）、`util/envRefs.test.js` がソースの `process` 参照を
  `process.env.<REACT_APP_*|NODE_ENV|PUBLIC_URL>` の形だけに許可する（素の `process.env`、
  デフォルト引数、他の名前は失敗）。
- **下流** — Outbox の `amounts` は **ストア通貨の total** である。請求額を送ると
  KRW 80,000 と USD 59 が pay-service の1つのカラムに混ざる。

#### 為替レート — 固定値がデフォルト、リアルタイムは任意

`FX_RATE_<通貨>` は **固定レート** である。請求通貨のレートがなければ、その手段では **注文
作成が失敗** する。設定漏れの結果が「レート 1 で静かに請求」（59 USD → 59 JPY）であっては
ならない。

`FX_LIVE_ENABLED=true` なら、**Frankfurter**（ECB 参照レート、キー不要の公開 API）から受け取った値を
先に使う（`FrankfurterFxRateSource`）。それでも固定値は消さない — 2つの役割が残る:

| | |
|---|---|
| **フォールバック** | API が落ちているか、その通貨がなければ固定値。為替 API の障害が注文作成の障害にならない（`FxRateSource` の契約: 失敗 = 空、例外ではない） |
| **上限** | リアルタイムの値が固定値と `FX_LIVE_MAX_DEVIATION_PERCENT`（デフォルト 30）を超えて異なれば、固定値を使って警告する。通貨コードの取り違え・データ事故で請求額が10倍になるのを防ぐ最後の線 |

どちらを使ったにせよ、レートは **注文に固定** される（`fx_rate`）。「リアルタイム」とは注文作成時点の値のことであって、
決済のたびに再計算するという意味ではない — 再計算すると、上で述べたとおり確認・返金が
すべて拒否される。

- キャッシュ: ストア通貨基準のレート表1つを `FX_LIVE_TTL_MINUTES`（60）の間保持する。注文ごとに
  API を呼ばない。ECB は **平日に1日1回** 更新するので、TTL を縮めても値がより頻繁に
  変わることはない。更新に失敗したら古い表を使い続け、失敗後の1分間は再度呼ばない。
- ⚠️ order-service の Pod から `api.frankfurter.app` への HTTPS 送信が開いていなければならない。
  ブロックされていても起動はするが、毎回の更新が警告ログとともに失敗し、固定値だけを使う — 「有効にしたのに
  値が変わらない」ならまずそのログを見る。
- ECB の参照レートは **銀行の売買レートではない。** 実決済の請求額を決めるには粗い値だが、
  整合性（確認・返金）は注文に固定された値で保証されるので、精度は精算損益の問題である。
  実決済に移行するなら、PG の精算レートに合わせた出どころで `FxRateSource` の実装を差し替えるのが正しい。

ガード: `PaymentCurrencyPolicyTest`（リアルタイム優先 · 乖離の上限 · 空なら固定 · 例外でも固定 ·
両方なければ依然として失敗）· `FrankfurterFxRateSourceTest`（レスポンスの形 · TTL · 失敗 = 空 ·
障害中のリトライ抑制 · 古い表の維持）。

#### 🐛 `Order.total` は今や `numeric(19,2)` である（V7）

以前は `int` だったのでストア通貨の小数が切り捨てられていた（$59.98 → 59、docs/35 · docs/40 #10）。フロントが
プロバイダーに送る金額も同じ 59 だったので検証は通ったが、**実際には98セント少なく受け取っており**、請求額は
その切り捨てられた値から換算されていたので同じ誤差を引き継いでいた — 通貨の問題ではなく、それ以前の問題だった。

V7 が `total · total_before_discount · shipping_price · tax_price` の4つを一緒に移す（1つの式で
結ばれているので、1つだけ移すと画面の3行が合わない）。`OrderPricing` は今や切り捨てではなく、
ストア通貨の桁数で **四捨五入** する — カタログ価格はすでに小数第2位までなので、四捨五入が実際に
起きるのはクーポンの割引率（59.98 × 10% = 5.998）だけであり、画面も同じ値をサーバーから受け取る。
`OrderDto`・`AdminOrderSummaryDto` の金額も `BigDecimal` で、フロントは `{total}$` の代わりに
`formatMoney(total, STORE_CURRENCY)`（`util/money`）で表示する。

> ⚠️ **元に戻せなくなるマイグレーションである**（`ROLLBACK-UNSAFE`）。integer → numeric に
> 変えた後、古いイメージ（int フィールド）は Hibernate validate で起動できない。B-54 ガードが
> `ALTER COLUMN … TYPE` も捕まえるように広げた。古い注文の切り捨てられたセントは復旧しない — いくら
> 切り捨てられたかがどこにも残っていない。
>
> 等級の集計（`aggregatePurchases`）は合計を `Long` にキャストして `PurchaseStatDto.recentAmount`
> をそのままにする — 等級の区間はドル単位なので、セントには意味がない。この JPQL は Testcontainers
> なしでは起動検証ができないので（§8）、デプロイ後に `order-service` の起動ログを見る。

> ⚠️ **同じキーを2回書くとデプロイが止まる。** `PORTONE_ENABLED: "true"` を既存の
> `PORTONE_ENABLED: "false"` の行を消さずに下に **追加すると** YAML が無効になり、
> `ops/28` が `mapping key "PORTONE_ENABLED" already defined` で落ちる。値を **変えるか**、
> ベースファイルはそのままにして `k8s-overlays/local/patch-local-endpoints.yaml` の `data` に
> そのキーを書く — パッチは同じキーを **上書きするので**、ベースと重なっても構わない。コミットされない
> ファイルなので `git pull` と衝突することもない。

### PortOne V2 を接続する手順（新規アカウント）

| 値 | どこで出てくるか | どこに入れるか |
|---|---|---|
| `PORTONE_V2_API_SECRET` | コンソール → 決済連携 → **API Keys** → V2 API Secret の発行（一度だけ表示される） | `k8s/20-secret.yaml` |
| `PORTONE_V2_WEBHOOK_SECRET` (`whsec_…`) | コンソール → 決済連携 → **Webhook** → エンドポイント追加時 | `k8s/20-secret.yaml` |
| `PORTONE_ENABLED` · `PORTONE_VERSION` | — | 既存の行の **値を変える**（`"true"` · `"v2"`）— 行を追加しないこと。ローカルの値は `k8s-overlays/local/patch-local-endpoints.yaml` に置くほうがよい（下記 ⚠️） |
| `REACT_APP_PORTONE_STORE_ID` (`store-…`) | 決済連携画面の一番上 — 公開値 | `ops/19` のビルド引数 |
| `REACT_APP_PORTONE_CHANNEL_KEY` (`channel-key-…`) | **決済チャネル** → チャネル詳細 → チャネルキー — 公開値 | `ops/19` のビルド引数 |

1. **決済連携 → 決済チャネル → チャネル追加**: **テスト決済**、PG は初めてなら KG Inicis。
   PG のストア情報欄（MID · サインキー · INIAPI key/iv）には Inicis の **テスト用公開値** を入れる —
   コンソールが埋めてくれるか、案内に表示される。⚠️ **「決済取消」欄（INIAPI key/iv）が空だと、決済は
   できるのに返金（§10）が `code != 0` で失敗する。** チャネルができたら **チャネルキー** をコピーする。
2. **API Keys** で V2 API Secret を発行する。一度だけ表示される。
3. **Webhook** にエンドポイントを追加する: `https://<サイト>/order-service/api/order/payment/portone/webhook`、
   バージョン **V2**。作成すると出てくる `whsec_…` をコピーする — Standard Webhooks の署名キーだ。
   コードは `Transaction.Paid` だけを処理し、残りの種類は 200 で無視する。
4. サーバー:
   ```bash
   # 20-secret.yaml: PORTONE_V2_API_SECRET, PORTONE_V2_WEBHOOK_SECRET
   # k8s-overlays/local/patch-local-endpoints.yaml の data に2行を入れる（ベースの値を上書きする）:
   #   PORTONE_ENABLED: "true"
   #   PORTONE_VERSION: "v2"          (PORTONE_CURRENCY はデフォルト KRW)
   kubectl apply -f k8s/20-secret.yaml
   ./ops/28-deploy.sh local --config-only --restart order-service   # apply -k を直接打つと古いタグに戻ってしまう
   kubectl -n ecommerce logs deploy/order-service | grep -i 포트원
   #   "포트원 V2 결제 활성화 (endpoint=https://api.portone.io apiSecret=NN자)"
   ```
   V2 なのに2つのシークレットのどちらか1つでもなければ **起動しない。**
5. フロント — **再ビルド**:
   ```bash
   REACT_APP_PORTONE_STORE_ID=store-… REACT_APP_PORTONE_CHANNEL_KEY=channel-key-… \
   REACT_APP_API_URL=… REACT_APP_PAYPAL_CLIENT_ID=… ops/19-build-images.sh …
   ```
   Store ID があれば、画面は V2 で動作する（V1 の識別コードが一緒にあっても V2 が優先）。
6. 決済手段で **PortOne** → 決済画面。V2 は決済 id（`payment-<uuid>`）を **こちらが** 作り、
   注文参照は `customData` に載せて送る — サーバーが PortOne に照会してそこから読む。
   確認: ブラウザが `/payment/portone/confirm` の後に「決済完了」、コンソール → 決済履歴に `PAID`、
   Webhook のログに 200。
   **リダイレクトからの戻り（2026-10-24）**: モバイル（一部の決済手段は PC も）は決済画面を iframe ではなくページ遷移で
   出す — そのとき `redirectUrl` がないと「リダイレクト方式で決済画面を呼び出すにはリダイレクト URL が必須です」で画面が
   開かない（本番の KG イニシスチャネルで判明）。画面は今の注文画面の URL（クエリ · ハッシュなし、`portOneRedirectUrl`）を
   載せ、戻ると `PortOneReturn` がクエリの `paymentId` 一つで同じ `/confirm` を呼ぶ（失敗なら `code` · `message` —
   確認せず理由だけ、クエリは消す）。`forceRedirect` は付けない — PC の iframe は従来どおり戻り値で終わる。KG イニシスの
   PC 決済は購入者の名前 · **電話番号** · メールが必須なので、電話番号はその注文の配送先のものを載せる（モバイルは任意）。
   **購入者名（2026-10-24）**: 以前は `user.username` を載せていたが、ログイン利用者オブジェクトは JWT の `{ email, userId, roles }`
   だけで名前は常に空だった（「イニシス日本決済の場合、購入者名は必須入力です」）。今はその注文の配送先の姓 · 名を `fullName`
   一つで（`portOneCustomer`）— このアプリの `firstname` は**姓**なので、PortOne の `firstName`（名）· `lastName`（姓）に分けると
   入れ替わる。配送先に名前がなければメールの前半。電話番号は数字のみ(2026-10-25 — PortOne の例 `01012341234`、配送先のハイフン · 空白 · `+` を除く)。
   ⚠️ **チャネルが「KG イニシス日本決済」なら**通貨は **JPY のみ**（`CURRENCY_JPY`）、決済手段はカード · コンビニ · 簡単決済
   （バーチャル口座なし — 画面は請求通貨が KRW でなければバーチャル口座を隠してカードで開く、`portOneSupportsVirtualAccount`。コンビニ決済は購入者のフリガナが必須だが配送先にその欄がないのでまだない）。国内 KG イニシス（KRW · カード · バーチャル口座）とは
   別のチャネル。日本決済を使うなら `PORTONE_CURRENCY: "JPY"` を `k8s-overlays/local/patch-local-endpoints.yaml` に置き
   `./ops/28-deploy.sh local --config-only --restart order-service` — 請求通貨は注文作成時に決まるので**新しい注文**から JPY。
   ⚠️ **テストサーバー `devcbt.inicis.com` は IP 許可リスト方式（2026-10-25）** — 登録されていない場所からは接続そのものがタイムアウトする（本番で PC · OCI とも `curl` 15 秒超過）。本番系 `cbt.inicis.com` にはこの制約はない。出典: イニシス直接連携プラグイン [gnuboard/g7-plugin-sirsoft-pay_kginicis](https://github.com/gnuboard/g7-plugin-sirsoft-pay_kginicis) の README と `AdminCbtConnectivityCheckController`（PortOne の文書にはない）。PortOne 経由では決済画面（ブラウザ）が devcbt へ行くので**試す PC のグローバル IP** を、承認は PortOne のサーバーが行うのでその登録は PortOne · イニシスに任せる — PortOne サポートにまとめて依頼する。
   🐛 **customData の二重エンコード（2026-10-25 — PortOne CLI `payment view` で判明）**: 画面が `JSON.stringify` した文字列を渡し SDK がもう一度包んだ — PortOne に `"{\"trackingId\"…}"` で保存され、サーバー（`trackingIdFromCustomData`）が注文参照を見つけられなかった（決済が成功しても確認 · Webhook が拒否）。画面はオブジェクトを渡し、サーバーは文字ノードならもう一段解く（作成済みの決済のため）。
   ⏸ **保留（2026-10-25）**: 決済画面がイニシスのテストサーバー（`devcbt.inicis.com/cbtauth`）で接続タイムアウト（韓国 PC · 日本 OCI とも `curl` 15 秒）— PortOne に問い合わせ中。チェックアウトで PortOne を選べないよう隠した（`checkout/Payment.jsx` の `HIDDEN_METHODS`）。決済コード · サーバー設定はそのままなので、再開時はそのリストから `"portone"` を外すだけ。
   **店舗情報（CBT 種別 JPPG、2026-10-24）**: このチャネルは決済画面に `storeDetails`（店舗名 · カナ · 英字 · 略称 · 担当 · メール ·
   連絡先 · 営業時間）がすべて必須。店舗名 · 連絡先は画面コード（`util/portone.js` の `PORTONE_JP_STORE` — 「アマス」 ·
   `Amasu` · `0810700000001`（数字のみ）· 00:00 〜 23:59）、メールはビルド変数 `REACT_APP_PORTONE_STORE_EMAIL`（公開値 — リポジトリ
   Variables · `ops/frontend.env`）。JPY 請求のときだけ載せる。決済手段の文言は日本決済基準（「クレジットカード（KG イニシス · PortOne）」）。

> V1 と違い、Webhook に **署名がある**（`PortOneV2WebhookVerifier`、Standard Webhooks —
> `webhook-id` · `webhook-timestamp` · `webhook-signature`、キーは `whsec_` の後ろの base64 を
> **デコードしたバイト列**）。署名が間違っていれば本文を読みもしない。それでも §5 の「本文は手がかりとしてだけ」
> というルールは変わらない — 署名は「PortOne が送った」ことを示すだけで、どの注文のいくらの決済なのかは
> 依然として再照会する。

### PortOne V1 を接続する手順（imp… 識別コードがあるアカウント）

Stripe と違って値が **4つ** あり、そのうち2つは **フロントのビルドに埋め込まれる**（B-51）。サーバーだけ変えて
フロントを再ビルドしないと決済画面が出ない。

| 値 | どこで出てくるか | どこに入れるか |
|---|---|---|
| `PORTONE_API_KEY` · `PORTONE_API_SECRET` | 管理者コンソール → 決済連携 → **識別コード・API Keys**（REST API キー / シークレット） | `k8s/20-secret.yaml` |
| `PORTONE_ENABLED` | — | 既存の行の値を `"true"` に **変える**（行を追加しないこと）、またはオーバーレイのパッチに置く |
| `REACT_APP_PORTONE_IMP_CODE` (`imp…`) | 同じ画面の **加盟店識別コード** — 公開値である | `ops/19` のビルド引数 |
| `REACT_APP_PORTONE_PG` | 下の2番で選んだ PG のコード | `ops/19` のビルド引数（デフォルト `html5_inicis`） |

1. **管理者コンソール → 決済連携 → 識別コード・API Keys** で3つを受け取る。⚠️ **API Secret は
   発行時に一度だけ表示される。** その場でコピーする。
2. **決済連携 → 決済チャネル管理 → チャネル追加** で **テスト決済** として PG を1つ選ぶ。
   初めてなら **KG Inicis** が無難だ（登録なしでテストチャネルが開く）。このとき選んだ PG の
   コードが `REACT_APP_PORTONE_PG` である — Inicis `html5_inicis`、Toss Payments `tosspayments`、
   NICE `nice`、KCP `kcp`。**チャネルの PG とこの値が違うと、決済画面が "등록되지 않은 PG"
   と表示される。** ブラウザの `IMP.request_pay({pg})` がその値をそのまま使う。
3. **決済連携 → Webhook 管理** に URL を入れる:
   `https://<サイト>/order-service/api/order/payment/portone/webhook`。署名はない —
   本文から `imp_uid` だけを取って API で再照会することが安全装置である（§5）。
4. サーバー:
   ```bash
   # k8s/20-secret.yaml に PORTONE_API_KEY / PORTONE_API_SECRET、configmap に PORTONE_ENABLED: "true"
   kubectl apply -f k8s/20-secret.yaml
   ./ops/28-deploy.sh local --config-only --restart order-service   # apply -k を直接打つと古いタグに戻ってしまう
   kubectl -n ecommerce logs deploy/order-service | grep -i 포트원
   #   "포트원 결제 활성화 (endpoint=https://api.iamport.kr apiKey=1234…)"
   ```
5. フロント — **再ビルドが必要である。** 識別コードがバンドルに埋め込まれるからだ:
   ```bash
   REACT_APP_PORTONE_IMP_CODE=imp12345678 REACT_APP_PORTONE_PG=html5_inicis \
   REACT_APP_API_URL=… REACT_APP_PAYPAL_CLIENT_ID=… ops/19-build-images.sh …
   ```
   `ops/19` が識別コードの先頭5文字と PG をログに出力する（値全体は出力しない）。空なら、
   決済手段で PortOne を押したときに「設定されていない」という案内が出て、残りはそのままだ。
6. 決済画面で **PortOne** を選ぶと決済画面が開く。テストチャネルは実際のカード番号を入れても
   **承認直後に自動キャンセル** される（PG がテスト取引を夜間に一括キャンセルする）。確認は2か所:
   ブラウザが `/payment/portone/confirm` の後に「決済完了」を描画するか、コンソール → 決済履歴に
   その取引が `paid` としてあるか。

> ⚠️ PortOne は **キーだけではテスト/実決済を区別できない** — チャネルの設定が決める。起動
> ログがそのことを警告として残す。実決済のチャネルに切り替えるときは、事業者登録・通信販売業の届出と
> PG 契約が先である（docs/49 の冒頭）。

**どちらもデフォルトは無効である。** PayPal の検証は逆にデフォルトが有効だが（`mode=http`）、方向が違う
理由がある: PayPal はすでに使っている手段なので「設定をうっかり忘れると検証なしで通過」になっては
ならず、Stripe・PortOne は新しい手段なので、設定していない環境で起動が失敗すると既存のデプロイが
止まってしまう。**有効にした後に資格情報がなければ起動を止める** — 半分だけ有効な状態（リストには出るのに
押すと 500）が一番悪い。

## 7. 決済手段の選択が実際に機能する

注文画面は **決済手段と関係なく PayPal ボタンだけを** 描画していた。分岐条件がコメントアウトされたままだった:

```jsx
{/* {order.paymentMethod === "paypal" && ( */}
<PayPalButtons ... />
{/* { order.paymentMethod === "credit_card" && } */}
```

「銀行振込」を選んでも PayPal ボタンが出ていた。今は分岐し、まだ使えない手段はボタンの代わりに
**なぜ使えないのか** を伝える。名前の `"Creadit cart"` のタイプミスも直した。

## 8. ⚠️ 検証できなかったもの

> ✅ **Stripe — 本番で決済・返金ともに成功**（2026-09-26、ユーザーが確認）。下記のフィールドマッピングが実際のレスポンスと合っているという意味だ。
> PayPal はまだ実際の決済で確認できていない。PortOne は依然として未検証。

**Stripe・PortOne の連携は実際の API で検証できなかった。** この作業環境からは2つの API に接続
できず、テスト用の資格情報もない。ドキュメントの仕様どおりに書き、レスポンスのフィールドマッピングを1か所に集めておいた:

- Stripe — `StripeCheckoutGateway.verify`（`payment_status` · `amount_total` · `currency`）
- PortOne — `PortOnePaymentGateway.toVerified`（`status` · `amount` · `currency`）

実際のレスポンスと違えば **そこだけ** 直せばよい。検証失敗のログが何を受け取ったかを残す。

署名検証（`StripeWebhookVerifier`）とポート/レジストリは <b>純粋なロジックなのでテストで検証された</b> —
外部呼び出しがないからだ。

### 🐛 無効にした環境で order-service が起動できなかった（修正済み）

`StripeConfig`・`PortOneConfig` は `*.enabled=true` のときだけゲートウェイのビーンを作る。ところが
そのゲートウェイをコンストラクタで受け取る `StripePaymentService`・`PortOnePaymentService` には **条件が
なかった。** クラスターのデフォルト値（どちらも `"false"`）では order-service は

```
No qualifying bean of type 'StripeCheckoutGateway' available
```

で起動すらできなかった。コントローラーは `@Autowired(required = false)` で「ビーンがなければ null」を
待っていたので、抜けていたのはサービスクラスの `@ConditionalOnProperty` の1行である。2つのサービスに
同じ条件を付けた。

**なぜ発見が遅れたのか。** 単体テスト189個はすべて通っていた — この種類のバグは **コンテキストを
起動して初めて** 表面化する。Testcontainers の起動テスト（`OrderServiceApplicationTests`）がその役目を
担うが、Docker がない環境では回らない。デプロイではロールアウトのタイムアウト → 自動復帰 →
古い Pod が Running なので、`kubectl logs deploy/order-service` には **正常な古い Pod のログ**
（Outbox ポーリングの DEBUG）しか見えなかった。`ops/28` は今、ロールアウト失敗の直後に **新しい Pod** の起動エラーを
抜き出して見せる。

**ガード。** `OptionalPaymentServicesWiringTest` が `ApplicationContextRunner` で2つのサービスだけを
載せて条件を固定する — 両方無効のデフォルト値でコンテキストが起動し、有効にすれば作られ、有効にしたのに
ゲートウェイがなければ止まる。デプロイ前の本当の確認は依然としてコンテナ起動テストである:

```bash
./gradlew :order-service:test -PwithContainers     # Docker が必要
```

今回はローカルの PostgreSQL 16 + Redis を直接起動して同じ検証をした: Flyway V1–V11 の適用、
Hibernate `validate` の通過、`결제 게이트웨이: [PAYPAL, STRIPE, PORTONE, BANK_TRANSFER]`
（ダミーの資格情報で有効にした場合）と `[PAYPAL, BANK_TRANSFER]`（デフォルト値）の両方で起動。

### 🐛 Stripe 決済が永遠に NOT_PROCESSED だった — CHECK 制約が enum より古かった（修正済み）

最初の実決済で表面化した。決済確認は 200、Outbox も COMPLETED、なのに注文は確定しなかった。
pay-service のログに原因があった:

```
ERROR: new row for relation "payment" violates check constraint "payment_payment_type_check"
  Detail: Failing row contains (..., COMPLETED, STRIPE, ...)
```

ベースライン（V2）は hbm2ddl の成果物なので、`@Enumerated(STRING)` カラムごとに **その時点の enum の値** で
`check (payment_type in ('PAYPAL','CREDIT_CARD','CASH'))` が埋め込まれている。その後 enum に
STRIPE・PORTONE を足したが、制約はそのままだった。Hibernate `validate` は CHECK を見ないので
起動は問題なく、コンテナ起動テストでも捕まらない — **その値が初めて保存される瞬間** にだけ
表面化する。レスポンスが order-service に戻らないので、Saga が注文を PAID に変えられなかった。

同じ欠陥がさらに2か所あった: `orders.order_status` に REFUNDED がなく（最初の全額返金で
爆発するところだった）、`notification.type` に後から足した3種類がなかった。

| サービス | マイグレーション | 広げたもの |
|---|---|---|
| pay-service | V3 | `payment_type` + STRIPE, PORTONE |
| order-service | V12 | `order_status` + REFUNDED |
| notification-service | V6 | `type` + NOTICE_PUBLISHED, ANNOUNCEMENT, EVENT_STARTED |

3つのマイグレーションはいずれも、そのカラムの CHECK を探して消し、同じ名前でかけ直す。元に戻すのは
安全である — 許容範囲を広げるだけだ。

**ガード B-55**（`ConfigurationGuardrailTest`）: エンティティの文字列 enum カラムごとに、マイグレーションが
最後に定義した CHECK のリストが Java の enum のすべての定数を含んでいるかをソースで照合する。
enum に値を足してマイグレーションを忘れると、ビルドが止める。意図的に狭くかけたもの（クーポン対象の
`ALL`）は理由とともに例外リストに書く。

**すでに決済された注文** は、マイグレーション後も自動的には確定しない — 失敗した決済レスポンスは
DLT に行ったか捨てられた。`pay.payment` に行がなく、`orders.orders.provider_payment_id` は
ある注文がそれである。数が少なければ、管理者が確認したうえで手動で状態を変えるのが最も安全だ。

## 10. 返金 — 決済確認の鏡像

返金を付けるときにやったことは決済確認のときと同じだ: **ポート1つ、ルールは1か所。**

```java
public interface RefundingGateway extends PaymentGateway {
    RefundResult refund(String providerPaymentId, BigDecimal amount, String currency, String reason);
}
```

`HostedCheckoutGateway` と同じ理由で別のインターフェースである — 返金 API のないプロバイダー
（銀行振込のようなステートマシン）に空のメソッドを強要しない。今は3つとも実装している。

| | 何を返金するか | 呼び出し |
|---|---|---|
| PayPal | 注文ではなく **キャプチャ** — 注文を照会してキャプチャ id をたどる | `POST /v2/payments/captures/{id}/refund` |
| Stripe | セッションではなく **PaymentIntent** — セッションを照会してたどる | `POST /v1/refunds` |
| PortOne | `imp_uid` をそのまま | `POST /payments/cancel` |

3か所とも **金額を明示する。** 空で送ると「残りの全額」になるが、そうすると返金額をプロバイダーが
決めることになる。返金額は DB の注文総額であり、レスポンスの金額をそれと再度照合する。

### ルール — `OrderDataService.refundOrder` の1つ

1. 注文が実在するか、**リクエストした人のものか**（管理者は例外 — `User-Role`）
2. すでに返金されているか → 冪等。**プロバイダーを再び呼ばない**（二重に返金されてはならない）
3. 決済済みの注文か — 決済前の注文はキャンセルの領域である
4. 配送前か — ユーザー自身では `PAID`・`PROCESSING` まで。発送された後は商品が戻ってこなければならない
   ので、管理者が判断する
5. プロバイダーが受け付けたか、返金額・通貨が注文総額と同じか — **部分返金はない**

**順序が重要だ。** プロバイダーを呼ぶ前に拒否できるものは、すべて先に拒否する。プロバイダーが
お金を返した後にこちらが拒否すると「お金は出ていったのに注文は決済状態」になる。だからプロバイダーの
呼び出し後に残る検査はレスポンスの照合だけであり、それが失敗したら **例外に返金参照を載せて**
運用者が手で合わせられるようにする。

### 状態と下流

- `OrderStatus.REFUNDED` ができた。`CANCELED`（決済前に取り下げ）とは違う。売上の集合
  `PAID_STATUSES` には **ない** — `PaidStatusesTest` が9番目の状態を人が分類するように強制し、
  フロントの `orderStatus.js` も同じ方向である。
- pay-service には決済確認と **同じ Outbox** で `paymentStatus=CANCELED` を送る。
  🐛 向こうの `persistPayment` は **既存の行を決して変えなかった** — 同じ trackingId で何が
  来ても、保存された状態を送り返していた。再配信には正しいが、返金には誤りだ。`COMPLETED →
  CANCELED` の一方向だけを開けた。
- クーポンは `OrderSaga.compensateRefund` が既存の `CouponRollbackRequest` で戻す。
- **在庫は商品がどこにあるかで決まる** — 下記「返品の入庫と在庫の復元」。

### 返品の入庫と在庫の復元

返金はそのまま返品ではない。発送前の返金は商品が倉庫にそのままあるので在庫が **すぐに** 戻るべき
であり、発送後の返金は商品が外にあるので **戻ってきたとき** に戻るべきだ。その間に在庫を
増やすと、ない商品を売ってしまう。

| 返金の時点 | `Order.awaitingReturn` | 在庫の復元 |
|---|---|---|
| 発送前（`PAID`・`PROCESSING`） | false | 返金直後 — `OrderSaga.compensateRefund` |
| 発送後（`DISPATCHED`・`DELIVERED`・`COMPLETED`、管理者のみ） | **true** | 管理者が **返品の入庫** を記録するとき — `OrderSaga.restock` |
| 商品が先に戻り、返金が後 | false（入庫がすでにある） | 入庫時にすでに戻っている |

**復元イベントは減算の逆である。** 新しいトピック・新しいリスナーは作らなかった — 決済時に送る
`ProductUpdateRequest`（トピック `product-update`）に `restock=true` を載せて送り、catalog の
`productUpdated` が同じ項目を **符号だけ変えて** 適用する（サイズの数量 +、販売数 −、0 より下には
いかない）。経路が2つあると、片方だけ直される日が来る（CAT-13 がそういう欠陥だった）。Avro はフィールドの
追加（デフォルト `false`）なので、古いコンシューマーは減算として読む。

**一度だけ。** 冪等キーは `restock-<orderId>` — 減算（`order-<orderId>`）とは別のキーなので、どちらも1
回ずつである。order 側は `stockRestoredAt` が一度だけ記録され（条件付き UPDATE の1文）、catalog 側は
`processed_event` が2回目を捨てる。順序は **送ってからマーク** である — 逆だと、発行が失敗したのに
DB は「返した」になってしまう。

**返品の入庫** — `POST /api/order/admin/return-received` `{trackingId, restock}`（管理者のみ、
在庫を増やす経路である）。数量は本文にない — DB の注文品目のとおりであり、部分返品は部分
返金がないのと同じ理由で存在しない。`restock=false`（破損・汚損）なら入庫だけが記録され、在庫は
そのままだ。ルール（`OrderDataService.receiveReturn`）: すでに入庫済みなら冪等、商品が出ていった
注文（返品待ち、またはまだ返金されていない発送・配送・確定）でなければならない。発送前の返金に入庫を
さらに記録すると在庫が二重に増えるので拒否する。

管理者画面（`/admin/refund`）に **返品待ち** フィルター（`status=AWAITING_RETURN`、状態ではなく
フラグである）と、行ごとの **返品入庫** ボタン（在庫復元のチェックボックス）が付いた。顧客の詳細画面は「返品受付
中 / 返品入庫完了」を表示する。

ガード: `OrderSagaRestockTest`（いつ · 何を · 一度だけ · 失敗時はマークなし）、
`OrderReturnTest`（入庫のルール）、`OrderRefundTest`（発送前/後の `awaitingReturn`）、
catalog `ProductServiceUnitTest`（符号の反転 · 販売数は 0 で止まる）、`AvroWireTest`（restock の往復）。

### 部分返金 · 部分返品（V9）

単位は **注文品目の数量** である。リクエスト本文は `items: [{orderProductId, qty}]` だけで、空にすると
「残りすべて」になる — だから古い呼び出し（全額）はそのまま動作する。金額はここにもない。

**金額は `RefundPricing` が決める** — サーバーが、3つのルールで:

1. **最後の返金は残りすべてである。** リクエストがまだ返金されていない数量をすべて覆うなら、金額は
   `total − これまでの返金額`。送料と丸めの端数がここで清算されるので、部分返金を何回
   しても **合計は正確に `total`** である（`RefundPricingTest.partsSumToTheTotal`）。
2. **それより前の返金は、割引を按分して差し引いた単価である。** クーポンが 10% なら品目も 10% 引いた値で —
   定価で返すとお客様が払った以上を受け取ることになる。割引は商品金額（送料を除いた値）に対する品目
   金額の比率で按分する。**送料は部分返金では返さない** — 残りの商品は依然として
   配送された。
3. どの場合も、残りの返金可能額を超えない。請求通貨の返金額は注文に固定された
   レートで換算するが、最後の返金は **請求残額のすべて** なので、レートの丸めの端数も清算される。

品目ごとに4つの数量を数える（`OrderProduct`）: `refundedQty` お金が戻った数 · `returnedQty` 商品が
戻ってきた数（**発送前に返金された数量も含む** — 倉庫を出ていない）· `restockedQty` 在庫に
戻した数 · `discardedQty` 破損などで在庫に入れなかった数。まだ在庫に回せる数は
`returned − restocked − discarded` であり、`OrderSaga.restock` は正確にその分だけを送る。
返品待ち（`awaitingReturn`）は今や `Σrefunded > Σreturned` である。

| | 部分返金 | 全額完了（残りの数量 0） |
|---|---|---|
| 注文状態 | そのまま（決済済み）— お金が一部残っている | `REFUNDED` |
| 売上集計 | `total − refundedAmount`（`aggregatePurchases`） | 集合から外れる |
| pay-service | 送らない — 決済は生きていて、向こうの状態は2つしかない | `CANCELED` |
| クーポン | 返さない — 依然としてそのクーポンで買った注文である | 復元 |
| 在庫 | 発送前なら返金数量の分すぐに、発送後なら入庫時に | 同じ |

返金の1件1件は `order_refund` に残る（金額2つ · 通貨 · 品目 JSON · プロバイダー参照）。
`Order.refundId` は「最後の返金参照」である。**プロバイダーの冪等キー** が重要になった — 同じキャプチャに
返金が複数あるので、`PayPal-Request-Id: refund-<captureId>` だけでは2回目が1回目の
レスポンスで置き換えられてしまう。`RefundingGateway.refund` に `refundKey`（「同じ状態で同じ品目・数量」
のハッシュ）が入り、PayPal はリクエスト id に、Stripe は `Idempotency-Key` に載せる。PortOne には
冪等ヘッダーがない。

在庫復元の冪等キーは `restock-<orderId>-u<累積復元数>` — 同じ状態で再び呼ばれれば同じ
キー（リトライがまとまる）、次の入庫の後は別のキー。⚠️ 発行はされたのに品目のマークが失敗し、**その
間に新しい入庫** が入ってくると次のキーが変わり、前の数量がもう一度載る — error ログとして
残し、運用者が合わせる（他の補償と同じ等級）。

画面: 顧客の詳細画面と管理者画面のどちらも、行ごとに `0…残りの数量` を選ぶ（`ItemQtyPicker`）。デフォルトは
残りすべてなので、触らなければ以前の動作（全額・全量）であり、そのときは `items` を送らない。部分
返金の予想金額は **描画しない** — 割引の按分を画面が真似るとサーバーの値とずれて
「なぜ少なく戻ってきたのか」になる。文言でだけ伝える。

古い注文（V9 以前、品目カウンターが 0）はバックフィルする: `REFUNDED` なら全数量を返金、`stock_restored_at`
があれば全数量を復元、`return_received_at` だけがあれば全数量を廃棄。そうしてこそ「残りの数量」が
0 になり、返金・入庫を再度できなくなる。

ガード: `RefundPricingTest` 9、`OrderRefundTest`（部分 · 最後が完了 · 超過の拒否 · キーが異なる ·
発送後の部分は返品待ち）、`OrderReturnTest`（部分入庫 · 廃棄 · 超過の拒否）、
`OrderSagaRestockTest`（pending のみ · 累積キー · 部分返金にはクーポンなし）、フロント
`ItemQtyPicker.test.jsx` · `Refunds.test.jsx` · `RefundButton.test.jsx`。

### 🐛 ついでに捕まったもの

- **一覧は PAID、詳細は NOT_PROCESSED。** 注文詳細は Redis キャッシュ（60分）から出てくるが、決済
  レスポンスで状態を PAID に変える Saga がキャッシュを消していなかった。確定を待つ画面が同じ
  詳細を14回読み直しても常に古い値なので、期限を過ぎて「確定が遅れている」と表示し、未決済に見える
  詳細画面で決済ボタンを押すと「すでに決済済みの注文です」だった。状態を変える場所は必ず
  `OrderCacheEvictor` を呼ぶ（Saga の PAID・CANCELED・在庫復元のマーク）。
- **返金したら、返金と無関係な商品まで消えた。** 犯人は返金ではなく、その前の決済の
  `cart-empty` だった — 「このユーザーのカートを丸ごと消せ」が決済レスポンスの後に非同期で
  届くので、確定が遅れている間に新しく入れた商品まで消していた。今は Saga が注文の品目
  （商品・サイズ・色・数量）を載せ（`CartEmptyRequest.items`、Avro のデフォルト値 `[]` で互換）、
  cart-service はその行だけを除く。品目が空で来たら（古い発行者）以前のようにすべて空にする
  （`CartEmptyLinesTest`）。
- 返金・キャンセルされた注文にも「配送準備中」と「決済が確認されました」が残っていた — リロードの
  代わりに注文だけを読み直すようになって表面化した。どちらも確定・キャンセル・返金の後は描画しない。

- **返金が pay-service に一度も反映されていなかった。** リクエストの重複判定が注文 id しか見ていなかった —
  「完了したレスポンスイベントがあれば処理済み」。返金リクエスト（CANCELED）は同じ注文で来るので、決済
  完了のレスポンスがある限り常に「重複」であり、古い COMPLETED のレスポンスが再発行されていた。決済の行は
  永遠に COMPLETED、order-service の Saga はそのレスポンスを無視（注文が決済待ちではない）、クーポン・在庫の
  復元と返金通知もすべてなかった。注文状態だけが REFUNDED なので（order-service が同期的に変える）、画面は
  返金されたように見えていた。重複判定に **リクエストされた状態** を入れた（`PaymentIdempotencyTest`）。通知は
  `PAYMENT_REFUNDED`（返金完了）に分かれる — 以前は CANCELED も「決済完了」で、重複キーが
  重なって何も作られなかった（notification-service V7、`PaymentNotificationTest`）。

`PaymentRequest.avsc` の `PaymentType` のシンボルが `["PAYPAL"]` だけだった。DTO には STRIPE・
PORTONE があるので、Stripe で決済すると Outbox → Avro 変換で `valueOf("STRIPE")` が
爆発し、**決済イベントが pay-service に届かなかったはずだ。** シンボルを埋めた（enum default
があるので古いコンシューマーも壊れない）。§8 の「実際の API で検証できなかった」が、こういうものを
捕まえられないという意味である — E2E を一度通すことが依然として必要だ。

### 画面

注文詳細では状態に応じて3つのうち1つを描画する: 発送前なら **ボタン**、発送後なら **カスタマーサポート
の案内**（サーバーがどのみち拒否するが、押してみて拒否されるままにはしない）、返金済みなら **完了 +
参照**。送るのは `trackingId` と理由だけだ — 金額も決済参照も本文にない。

**管理者画面** — `/admin/refund`。状態で絞り込むか追跡番号を1つ探し、決済済みの注文には
すべて返金ボタンがある（管理者は発送済みの注文も返金する — 返品の回収は上の「返品の入庫」）。同じ
`POST /api/order/refund` を使い、サーバーが `User-Role` で2つのルール（所有権・発送前）を解除する。
一覧は `GET /api/order/admin/orders` — 所有権チェックのない唯一の一覧なので、ロール判定が
そのままアクセス制御であり、**キャッシュしない**（返金直後に古い状態を見せると、同じ注文をまた
返金しようとする）。

### 🐛 返金しても在庫が戻らなかった — "No transaction is in process"

本番ログ: `재고 복원 발행 실패 — 수동 확인 필요 … IllegalStateException: No transaction is
in process`。在庫復元・クーポンのロールバック・カートのロールバック・カートを空にするテンプレートは `transactional.id`
を持つ **トランザクションプロデューサー** である（docs/38 §1.17）。決済レスポンスのリスナー内では DB トランザクションに
Kafka トランザクションが付いて送られるが、返金・返品の入庫・銀行振込のキャンセルは **HTTP リクエストで DB
トランザクションが終わった後に** 送る — トランザクションがないため毎回失敗していた。返金は終わったのに在庫も、全額
返金のクーポンも戻っていなかった。

`common/kafka/TransactionalSends.run(template, ops -> ops.send(...))` が振り分ける: トランザクション
プロデューサーなのに進行中のトランザクションがなければ、その1回の送信だけを包んだトランザクション
（`executeInTransaction`）で、あればそこに参加し、トランザクションプロデューサーでなければそのまま送る。
order-service の4つの発行者（`ProductUpdate`・`CouponRollback`・`CartRollback`・`CartEmpty`）が
これを経由する。**ガード**: `TransactionalSendsTest` — 対照群（そのまま送ると同じ例外）、1回きりの
トランザクションのコミット、外側のトランザクションへの参加、非トランザクションのテンプレート。

> すでに失敗した注文は自動では再送しない。ログの `orderId` で在庫を手で合わせる。

### 🐛 部分返金には通知がなかった

「返金完了」の通知は pay-service の決済レスポンス（CANCELED）から出る。ところが決済の取り消しは **全額が
戻ったときにだけ** 送る（部分返金では決済がまだ生きている）。品目の一部を返金すると、お金は戻るのにアプリ・メール
のどこにも通知がなかった。

order-service が返金 **1件ごとに** `order-refunded` を送る（`OrderRefundedKafkaPublisher`、コミットの後、
非トランザクションのテンプレート）。内容は最新の返金の **請求** 金額・通貨、`complete`（全額が戻ったか）、キー
`order-refunded:<orderId>:<n 番目の返金>` — 返金参照はプロバイダーによっては決済参照で代わりに埋められて重なりうるので、
連番を使う。notification-service は **部分返金だけ** を「部分返金完了」（`PAYMENT_REFUNDED`、金額を含む）として
作る — 全額は決済レスポンス側がすでに知らせている。トピックは ops/02 のリストに加えた（DLT）。

**ガード**: `OrderRefundedKafkaPublisherTest`（最新の返金・請求金額・連番キー・記録がなければ送らない）、
`OrderRefundedNotificationTest`（部分だけ通知・全額はスキップ・2回目の部分返金は別のキー・金額表記）、
`KafkaPublisherWiringTest`（専用テンプレート）。

## 11. 銀行振込 — 決済 API ではなく注文のステートマシン

```
NOT_PROCESSED ──(振込案内を受け取る: 顧客)──▶ UNPAID ──(入金確認: 管理者)──▶ PAID
                                                │
                                                └──(期限切れ · お客様が取り下げ)──▶ CANCELED
```

問い合わせるプロバイダーがいない。「確認してくれる相手」は銀行の明細を見た **管理者** である。そこで新しいゲートウェイを
作るが（`BankTransferGateway`、プロバイダー `BANK_TRANSFER`、決済手段 id は `cash`）、その `verify` は
**呼ばれてはならない** メソッドである — 呼ばれたということは、誰かが銀行振込を「ブラウザが決済したと
言う」経路に入れたという意味なので拒否する。

| 遷移 | 誰が | 何が変わるか |
|---|---|---|
| 振込案内を受け取る `POST /api/order/payment/bank-transfer/start` | 顧客（注文者のみ） | `UNPAID`、振込名義人、期限（`BANK_TRANSFER_DEADLINE_HOURS`、デフォルト 48h）。お金が来ていないので下流に知らせることはない |
| 入金確認 `POST /api/order/admin/deposit-confirmed` | 🔒 管理者のみ | 決済参照（`deposit-<orderId>[-参照]`）を付け、**カードと同じ Outbox** で `COMPLETED` を送る。その後は同じだ: pay-service → レスポンス → Saga が `PAID` に変えて在庫減算・カートを空に |
| 入金前のキャンセル `POST /api/order/payment/bank-transfer/cancel` | 顧客・管理者 | `CANCELED` + クーポン復元。在庫は減算されたことがない |
| 期限切れ（`BankTransferService.expire`、毎時） | ジョブ | 上と同じ。**確認が終わった注文（決済参照が付いたもの）には触れない** — Saga がまだ `PAID` に変えられていない瞬間かもしれない |

3つの点がカードと違う:

- **`UNPAID` が意味を得た。** この状態は enum にあったが、誰も入れていなかった。今は「振込
  案内を受け取り、期限内にお金が来なければならない」である。`NOT_PROCESSED`（決済手続きを開始していない）とは
  違い、売上ではない（`PAID_STATUSES` にない）。Saga の決済レスポンス処理は今や2つの状態で
  受け付ける（`OrderSaga.AWAITING_PAYMENT`）。顧客の「Unpaid Orders」フィルターがこの注文を表示する。
- **入金確認は金額を受け取らない。** 注文の請求額が届いたという管理者の確認である。「いくら
  届いた」を受け取り始めると、その値を信じることになる — 違う額が届いたなら確認せずに顧客に
  連絡するのが正しい。確認パネルが請求額を表示してそう尋ねる。
- **返金は管理者のみ。** ゲートウェイの `refund` は管理者が口座に送金した **記録** なので、常に
  「受け付けた」と答える。顧客が自分で呼ぶと、お金が送られていないのに注文だけ REFUNDED になるので、
  `refundOrder` がこのプロバイダーに限って顧客を拒否する（画面もカスタマーサポートの案内を描画する）。

口座は設定である（`BANK_TRANSFER_BANK · ACCOUNT · HOLDER`、configmap）。お客様に見せる
値なのでシークレットではないが、**空だと案内を開始できない** — 口座のない案内には振り込む
先がない（500 ではなく設定の問題だと伝える）。請求通貨は `BANK_TRANSFER_CURRENCY`（国内
口座なら KRW）— お客様が振り込む金額がその通貨で案内され、`OrderSaga` が送る Outbox の
金額は依然としてストア通貨である。

画面: 注文詳細の決済の位置に `BankTransfer` が描画される — 決済前なら振込名義人 +「振込
案内を受け取る」、入金待ちなら口座・金額・期限・振込名義人 +「入金前の注文キャンセル」。管理者画面には
「入金待ち（銀行振込）」フィルターと、行ごとの「入金確認」ボタン（銀行取引の参照を入力、任意）。

ガード: `BankTransferTest`（案内 · 冪等 · 所有権 · 確認は同じ Outbox · 確認後はキャンセル不可 ·
期限切れは未確認のみ · 返金は管理者のみ · `cash → BANK_TRANSFER`）、`OrderSagaProcessTest`（UNPAID
も PAID になる）、`PaymentGatewaysTest`（プロバイダー4つ）、フロント `BankTransfer.test.jsx` 5 ·
`Refunds.test.jsx` +1 · `paymentMethods.test.js`。

### PortOne のバーチャル口座 — 「振込案内」を PG が作り、「入金確認」を Webhook が行う

同じステートマシンで、2か所だけが変わる。

| 遷移 | 銀行振込 | PortOne のバーチャル口座 |
|---|---|---|
| 振込案内 | 顧客が振込名義人を書いてリクエスト → ストアの口座 | 決済画面が口座を **発行** → 発行 Webhook（`Transaction.VirtualAccountIssued`）またはブラウザの復帰が `registerVirtualAccount` で注文に付ける（UNPAID、期限は PG の期限） |
| 入金確認 | 管理者 | **PortOne の決済完了 Webhook** → `recordPayment` → Outbox → Saga → PAID。管理者は確認できない |
| 入金前のキャンセル | 取り下げてクーポン復元 | **PG の口座を先に閉じて** から取り下げる。閉じられなければ取り下げない |
| 期限切れ | ジョブが取り下げる | 同じ（期限 = PG の期限、過ぎれば PG も口座を閉じる） |

どの通知かは **本文ではなく PortOne への照会** が教える（`PortOnePaymentService`）。発行 Webhook と
完了 Webhook は決済参照だけを取り出し、`verify` が完了と言えば決済を付け、そうでなければ
`virtualAccountOf` が発行された口座を返せばそれを付け、どちらでもなければ（キャンセル・失敗）無視する。
本文が「発行された」と言っても PortOne が違うと言えば何も付かない — V1 の Webhook に署名が
なくても安全な理由と同じだ（§5）。

ブラウザの復帰（`/payment/portone/confirm`）も同じ分岐をたどる。以前は決済が完了していない
ものをすべて拒否していたが、バーチャル口座は発行そのものが正常な結果である — レスポンスの `orderStatus` が `UNPAID`
なら、画面がリロードして口座・金額・期限を表示する（`BankTransfer`、ストアの口座の代わりに PG の口座）。
バーチャル口座の金額・通貨は付けるときに請求額と照合する — 1ウォンの口座を付けておいても決済確認で
どのみち拒否されるが、お客様に誤った案内を見せないためだ。

🔴 **キャンセルされた注文に決済が届いたら、`recordPayment` が拒否して error として残す。** 期限が
過ぎてこちらが取り下げた後に入金されたか、キャンセルされた注文に遅れてキャプチャが付いた場合だ。静かに記録すると
Saga が状態を変えられず、「お金は届いたのに注文はキャンセル」がどこにも見えない。手動返金が必要な
状況であり、だからバーチャル口座のキャンセルは PG の口座を先に閉じる。

フロント: PortOne を選ぶと「カード・簡単決済 / バーチャル口座」を選ぶ。V1 は `pay_method: "vbank"`、
V2 は `payMethod: "VIRTUAL_ACCOUNT"` + `virtualAccount.accountExpiry.validHours`。発行された後、
カートは空にしない — 入金前に空にすると、期限が過ぎて取り下げられた注文のカートがなくなる。

レスポンスの形（V2 `method.type=PaymentMethodVirtualAccount` の `bank`・`accountNumber`・
`remitteeName`・`expiredAt`、V1 `vbank_num`・`vbank_name`・`vbank_holder`・`vbank_date`）は実際の API
で検証できなかった（§8）。違えば `virtualAccountOf` と `PortOneV2PaymentGatewayTest` の本文を
一緒に直す。V2 の `bank` は銀行の **コード** なので、画面にコードがそのまま表示される — 名前の表を付けるのは
残作業である。

ガード: `PortOnePaymentServiceTest`（発行 Webhook → 口座 · 完了 Webhook → 決済 · 本文は手がかり · 復帰の
2つの結果 · 他人の注文の拒否）、`PortOneV2PaymentGatewayTest`（発行レスポンスのパース · PAID なら発行ではない ·
発行 Webhook の参照）、`BankTransferTest`（UNPAID への遷移 · 冪等 · 金額の照合 · 所有権 · 管理者は確認不可 ·
キャンセルされた注文の決済の拒否）、フロント `BankTransfer.test.jsx` · `Refunds.test.jsx`。

## 12. 残っているもの

- **日本の決済手段** — Stripe はコンビニ決済·銀行振込 をサポートしている。新しい連携ではなく、
  Checkout セッションの `payment_method_types` を増やす作業である — 今はコードが `card` に
  **固定** している（ダッシュボードの設定がコードとずれないように）。増やすときは `async_payment_failed`
  Webhook の処理も一緒に付けなければならない。
- **部分返金の送料** — 今は最後の返金にだけ載る。「送料だけを返金」や「部分
  返金にも送料の一部」が必要なら、`RefundPricing` のルールを1つ変えればよい — テストが
  合計の不変性を守る。
- `PaypalPaymentRequest` という名前がプロバイダー中立ではない。次の整理のときに変える。
