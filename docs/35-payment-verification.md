# 35. 決済をサーバーが確認していなかった

**日本語** | [한국어](35-payment-verification.ko.md)

> 🕰️ **状態（2026-09-27）— 一部古い。** `paypal_order_id` の代わりに `(payment_provider, provider_payment_id)` のユニーク制約（V18）が再利用を防ぎ、請求通貨は注文に保存します。決済手段の拡張は [docs/49](49-payment-providers.md)。

## どうやって見つけたか

PayPal アカウントがロックされてシークレットを破棄できない、という話から始まった。「では、そのシークレットは
正確にどこで使われているのか」を確かめようとコードを漁ったところ、**どこにも使われていなかった。**

それは良い知らせではなかった。

---

## 1. 何が問題だったか

```java
// order-service/controller/OrderController.java
@PostMapping("/payment/paypal")
public ResponseEntity<?> persistPaypalPayment(@RequestBody PaypalPaymentRequest request, ...) {
    orderService.persisitPaypalPayment(request, token);   // そのまま保存
```

```java
// order-service/service/OrderDataService.java  (修正前)
Order order = getOrderByTrackingId(request.getTrackingId());
if (order != null) {
    PaymentRequest payload = PaymentRequest.builder()
            .amounts(request.getAmounts())                              // ← クライアントが決めた金額
            .paypalOrderId(request.getPaypalOrderId())                  // ← 検証なし
            .paymentStatus(PaymentStatus.valueOf(request.getPaymentStatus()))  // ← クライアントが決めた状態
            .build();
    paymentOutboxHelper.savePaymentOutbox(payload, OutboxStatus.STARTED);
}
```

サーバーはこのうち **何一つ確認していなかった。** 金額も、決済状態も、PayPal 注文 ID が存在するかどうかも。
つまり、決済せずにこの 1 行で注文を完了させることができた:

```bash
curl -X POST https://shop.example.com/api/order/payment/paypal \
  -H 'Authorization: Bearer <자기 토큰>' -H 'Content-Type: application/json' \
  -d '{"trackingId":"<자기 주문>","paypalOrderId":"아무거나","amounts":0.01,
       "paymentStatus":"COMPLETED","orderStatus":"NOT_PROCESSED"}'
```

ログインさえしていればよい。自分の注文に向けて撃つのだから IDOR でもなく、所有権チェックを
どれだけしっかりやっても防げない。

### なぜ誰も気づかなかったか

**ブラウザでは本当に決済が行われるからだ。** PayPal JS SDK が決済ウィンドウを開き、
ユーザーが承認し、`actions.order.capture()` が成功し、その結果をサーバーに送る。
画面上は完璧に動作する。docs/26 §1 がこの経路のバグ（capture の 2 回呼び出し）を捕まえたときも、
**フロー自体は疑わなかった。**

見落としていたのはこれだ:

> ブラウザで決済が行われたという事実は、**サーバーにとって証拠ではない。**

ブラウザは信頼境界の外にある。SDK が返した `details.id` はサーバーから見ればただの文字列で、
その文字列がどうやって作られたのかサーバーには知りようがない。正直なユーザーからは本物の PayPal 注文
ID が来るし、そうでないユーザーからは何でも来る。

### シークレットがなかった理由

このリポジトリに PayPal のシークレットがなかったのは、セキュリティが優れていたからではなく、**サーバーが PayPal に何も
問い合わせていなかった** からだ。問い合わせるにはシークレットが必要だが、問い合わせないので必要がなかった。

（git 履歴にあった `REACT_APP_PAYPAL_CLIENT_SECRET` は、値が `REACT_APP_PAYPAL_CLIENT_ID` と
**完全に同じだった** — どちらも `A` で始まる 80 文字だ。PayPal のクライアント ID は `A`、シークレットは `E`
で始まる。シークレット変数にクライアント ID を貼り付けたもので、クライアント ID は公開値だ。
docs/30 がこれを「PayPal シークレット 🔴」に分類したのは、**変数名だけを見て判断した誤り** だ。）

---

## 2. 何を直したか

サーバーが PayPal に直接問い合わせる。4 つを確認する。

| | 確認 | 防ぐ攻撃 |
|---|---|---|
| ① | PayPal Orders API で照会 → `status == COMPLETED` | でっち上げた注文 ID · 承認だけされてキャプチャされていない決済 |
| ② | 金額を **DB の注文合計額** と照合 | 0.01 を決済して 59.98 の注文に付ける |
| ③ | 通貨の照合 | 59.98 USD を 59.98 KRW にすり替える |
| ④ | その PayPal 注文がすでに使われたかを **DB のユニーク制約** で | 1 回決済して 10 件の注文に再利用 |

② が核心だ。リクエスト本文の `amounts` は **もう使わない。** 下流に渡すペイロードも
サーバーが確認した値で作り直す — `paymentStatus` も `orderStatus` もクライアントが決めることは
ない。

### ④ を DB 制約にした理由

アプリケーションで「すでに使われたか」を照会してから保存すると、同時に来たリクエスト 2 つが **一緒に通過する。**
照会の時点ではどちらも空だからだ。部分ユニークインデックスはその競合自体をなくす。

```sql
-- V3__order_paypal_order_id.sql
ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS paypal_order_id varchar(255);

CREATE UNIQUE INDEX IF NOT EXISTS uk_order_paypal_order_id
    ON orders.orders (paypal_order_id)
    WHERE paypal_order_id IS NOT NULL;   -- 未決済の注文（NULL）はインデックスに入らない
```

保存時に `saveAndFlush` で **即座に** 制約を確認する。コミットまで遅らせると、Outbox に決済が
すでに入った後で失敗する。

### ①〜③ がなければ ④ だけでは足りず、その逆も同じだ

- ④ だけの場合: でっち上げた ID も「初めて使う値」なので通過する
- ①〜③ だけの場合: 本物の決済 1 件を、同じ金額の複数の注文に再利用できる

4 つが揃ってはじめて意味がある。

---

## 3. デフォルト値をどちらに置いたか

```yaml
paypal:
  verification:
    mode: ${PAYPAL_VERIFICATION_MODE:http}   # デフォルトが「オン」
```

資格情報がなければ **order-service は起動しない。**

```
IllegalStateException: PayPal 결제 검증에 자격증명이 필요합니다.
  PAYPAL_CLIENT_ID / PAYPAL_CLIENT_SECRET 를 채우거나,
  검증 없이 돌리려면 PAYPAL_VERIFICATION_MODE=disabled 를 명시하세요.
```

これが意図だ。決済検証は **「設定をうっかり忘れると静かにオフになる」** 種類のものであってはならない。
検証なしで動かすには `disabled` を明示的に書く必要があり、そうすると起動のたびに警告が出る。

### メール・SMS と方向が逆である理由

docs/29 ではメール・SMS は `logging` がデフォルトだ。ここでは逆だ。矛盾ではなく、
**安全な方向がそれぞれ違う** からだ:

| | うっかりやると危険なこと | だからデフォルトは |
|---|---|---|
| メール・SMS | **送ること**（取り消せない） | 送らない（`logging`） |
| 決済検証 | **通すこと**（お金が出ていく） | 検証する（`http`） |

どちらも「ミスしたときに悪さが少ない方」という同じルールから来ている。

### 確認できなければ拒否する（fail closed）

PayPal に届かなかったとき（ネットワーク・トークン発行の失敗）、「ひとまず通す」誘惑がある。
そうすると **PayPal を少し塞ぐだけで検証全体が無力化される。** 確認できなければ拒否する。

読み取りタイムアウトを 5 秒と短く取ったのも同じ判断だ。拒否されてもお金は PayPal にそのまま残っていて、
ユーザーは再試行できる — ユーザーが空の画面を 30 秒見つめるよりはましだ。

---

## 4. 確認したこと

**単体（10 件、`PaypalPaymentVerificationTest`）** — 各攻撃が実際に防がれるか、そして
**防がれたときに Outbox に何も入らないか** を見る。例外を投げたとしても、その前に Outbox に
入れていれば、決済はすでに下流へ伝播した後だ。

| | |
|---|---|
| ✅ | でっち上げた注文 ID → 拒否、Outbox の発行なし |
| ✅ | `APPROVED`（キャプチャ前） → 拒否 |
| ✅ | 実際の 1 ウォン決済を 5998 の注文に → 拒否 |
| ✅ | 通貨の不一致（KRW） → 拒否 |
| ✅ | 別の注文に使った決済の再利用 → 拒否 |
| ✅ | 存在しない注文 → 拒否（以前は **静かに 200** だった） |
| ✅ | ペイロードの金額がリクエスト本文ではなく PayPal の確認値 |
| ✅ | 同じ注文 · 同じ決済の再送信（リロード） → 静かに無視、二重発行なし |

**統合** — `order-service:test -PwithContainers` の 19 件が通過。Flyway V3 の適用と Hibernate
`validate` が新しいカラムを受け入れることまで、実際の PostgreSQL で確認した。

**全体** — `test -PwithContainers` **268 tests、失敗 0**（e2e Saga の全区間を含む）。

### 確認できなかったこと

- **PayPal に実際に繋いでみていない。** アカウントがロックされている。`HttpPaypalOrderVerifier` の
  OAuth2 トークン発行と Orders API の照会は **ドキュメントに基づいて書いたもので、実際の呼び出しは未検証** だ。
  レスポンス JSON のパス（`purchase_units[0].amount.value`）がずれている可能性が残っている
- 部分キャプチャ（partial capture）は扱わない。このリポジトリは全額キャプチャのみを使う
- 返金・紛争（refund/dispute）の Webhook は依然としてない。決済 **以後** の状態変化は反映されない

アカウントのロックが解けたら、sandbox で §4 の最初の項目から実際に確認すること。それまでは
`PAYPAL_VERIFICATION_MODE=disabled` にしてデプロイしてもよいが、**それはこのドキュメントが説明した
欠陥がそのまま生きているという意味** だ。

---

## 5. 残るリスク

| | |
|---|---|
| 返金・紛争 | PayPal で返金しても注文の状態はそのままだ。Webhook が必要だ |
| 部分キャプチャ | 全額キャプチャのみを前提とする |
| 通貨のハードコーディング | `paypal.currency` 一つだけだ。多通貨ストアになれば、注文ごとに通貨を保存しなければならない |
| pay-service | 依然として order-service が渡した値を信じる。検証は境界（order-service）で一度だけ行う — 内部サービス間ではそれが正しいが、その境界が破られれば下流は防げない |

## 6. サンドボックスでテストする（韓国で決済が通らないとき）

PayPal は韓国の個人アカウントの受け取りを制限している。そのため **ライブの資格情報では国内で決済
テストができない** — 決済画面までは進むが、そこで止まる。

**サンドボックスには国の制限がない。** 偽の購入者アカウントで承認→キャプチャをそのまま流せて、
お金は動かない。決済以降の過程（決済の Outbox → `payment` トピック → pay-service →
注文状態 → 配送）を実際の経路で確認するなら、こちらが正しい。

### 🔴 揃えるべき値は 3 つで、1 つ抜けただけで静かにずれる

| | どこ | いつ埋め込まれるか |
|---|---|---|
| `PAYPAL_ENV` | configmap | コンテナ起動時 |
| `PAYPAL_CLIENT_ID` / `SECRET` | Secret | コンテナ起動時 |
| **`REACT_APP_PAYPAL_CLIENT_ID`** | フロントの **ビルド引数** | **イメージのビルド時点**（B-51） |

3 つ目が罠だ。サーバーの設定だけ変えてフロントを再ビルドしないと、**ブラウザは依然として
以前の環境の PayPal** を表示する。起動は成功し、症状は決済画面になってはじめて現れる。

`paypal.env` スイッチはサーバー側の 2 つの値がずれることは防ぐが（起動失敗）、**ブラウザに埋め込まれた
値はサーバーには知りようがない。** そのため両側がそれぞれ client id の先頭 6 文字を出力する — それで照合する。

```bash
# サーバーは何で起動したか
kubectl -n ecommerce logs deploy/order-service | grep "PayPal 결제 검증 활성화"
#   → env=sandbox endpoint=https://api-m.sandbox.paypal.com clientId=AXxxxx…(80자)

# フロントは何でビルドされたか — ops/19 がビルド時に同じ形式で出力する
#   → PayPal client id: AXxxxx… (80자)
```

**2 行の先頭 6 文字が違えば、それが原因だ。**

### 手順

1. developer.paypal.com → Testing Tools → Sandbox Accounts で **business**（販売者）と
   **personal**（購入者）のアカウントを作る。国は US、通貨は USD — フロントが
   `currency: "USD"` に固定されている（App.jsx `initialOptions`）。
2. Apps & Credentials → **Sandbox** タブ → アプリ作成 → client id / secret。
3. Secret の `PAYPAL_CLIENT_ID` / `PAYPAL_CLIENT_SECRET` をその値に。
   `PAYPAL_ENV` は `sandbox` のまま（デフォルト値）。
4. **フロントの再ビルド** — このステップを飛ばすと何も変わらない:
   ```bash
   REACT_APP_PAYPAL_CLIENT_ID=<같은 sandbox client id> ops/19-build-images.sh ...
   ```
5. 決済画面で **sandbox の personal アカウント** でログインして決済。

### ⚠️ `PAYPAL_VERIFICATION_MODE=disabled` では決済ウィンドウを飛ばせない

それは **サーバーの検証だけ** をオフにする。フロントの `onApprove` は依然として `actions.order.capture()` が
成功しないと発火しないので、PayPal のウィンドウ自体はそのまま必要だ。決済ウィンドウなしで後段だけを確認するには
別の開発専用経路が必要で、今のリポジトリには **ない**（意図的に作らなかった —
決済を迂回するコードは、一度入ると本番までついてくる）。

### ライブに切り替えるとき

`PAYPAL_ENV=live` に変え、Secret とフロントのビルド引数を **すべて live の値に** 置き換える。
order-service の起動ログに警告が 1 行出る — その行が見えたら、実際のお金が動くという意味だ。
