# 48. ユーザーのレプリケーション — 1 つのイベント、3 つのコンシューマー、3 種類の失敗

**日本語** | [한국어](48-user-replication-debt.ko.md)

> 🕰️ **状態（2026-09-27）— 一部古い。**「改善予定」として書いたドキュメントですが、①〜⑥ は適用しました（§6）。残っているのは Avro の履歴整理（C3）です。

> 📌 docs/47 の注文 400 を追っているうちに明らかになった構造上の問題をまとめたドキュメントである。
> **①（order はレプリケーションしない）・②（Outbox）・③（突合 — docs/57 §14）・④（発行タイミング）・⑤（変更・退会の伝播）は適用した。**
> ⑥（chat のアイデンティティを uid に、イベントからメールアドレスを削除）も適用した。

---

## 1. 今の形

`user-created` イベント 1 つを 3 つのサービスが受け取る。**3 つとも用途がまったく異なる。**

```
user-service ──user-created──┬──▶ order-service   orders.customer   (行を作る)
 (メール認証の時点)            ├──▶ chat-service    chat_member       (行を作る)
                             └──▶ coupon-service  ✗ 行なし           (登録クーポンを発行する)
```

### 🔴 ところが発行が一度も起きていなかった

発行は **メール認証リンクを押した瞬間** に紐づいていた。ところが
`app.verification.require-email-verification` の **デフォルト値が false** だ:

```java
user.setEnabled(!requireEmailVerification);   // false → 登録と同時に有効化
```

アカウントがすぐに `enabled` で作られるので、**確認リンクを押す機会がなく、その経路を誰も
通らない。** `user-created` トピックが空だった本当の理由はこれだ — 発行が失敗したの
ではなく、**一度も呼ばれていなかった。**

症状は 3 つに分かれて現れた。

| | どう見えたか |
|---|---|
| order | 注文ボタン → 400（docs/47）— **目に付く** |
| chat | チャットへの入室失敗 — 目に付く |
| coupon | **登録お祝いクーポンが届かない** — 届いていないという事実すら気づきにくい |

3 つ目が、このドキュメントの §3 の要点をそのまま示している。ユーザーが「クーポンが付与されていないみたいだけど」
と尋ねるまで、誰も気づかなかった。

## 2. 何が壊れたか — dual-write  ✅ 修正済み（§6-②）

```java
@Transactional
public void publish(UserCreatedRequest req) {
    kafkaTemplate.send("user-created", String.valueOf(req.getUserId()), req);
}
```

**DB トランザクションと Kafka への発行は、同じアトミックな単位ではない。** アカウントはコミットされ、イベントは送られない
ことがありうる。しかも `send` は非同期で、future を誰も見ていなかったので、**失敗してもログ 1 行
残らなかった。**

実際の結果: アカウントは無事なのに、`user-created` トピックは **丸ごと空だった**（offset 0、lag 0）。

> このリポジトリにはすでに **transactional outbox** があった — `users.outbox_event`、
> `PaymentOutboxHelper`、CDC コネクター。決済には適用したのに `user-created` にだけ適用しておらず、
> `UserServiceImpl` に `createOutboxEvent` が **コメントアウトされたまま** 残っていた。

## 3. 取りこぼしたとき、3 つのサービスはそれぞれ違う壊れ方をする

| サービス | レプリケーションするもの | 実際に使うもの | イベントを取りこぼすと | 現在の状態 |
|---|---|---|---|---|
| **order** | `orders.customer`（id・email・username・nickname） | **メールアドレス → id の変換、それだけ** | 注文作成の失敗 | 注文時のバックフィルを追加（docs/47） |
| **chat** | `chat_member`（id・email・nickname・image） | メールアドレス → 会員、**ニックネームを画面に表示** | チャットへの入室失敗 | `createMissingMember` でその場で作成 — ~~ニックネームをメールアドレスの前半から推測~~ 仮のニックネーム、登録イベントが埋める（§6-⑤） |
| **coupon** | なし | — | **登録クーポンが永遠に出ない** | ❌ 対策なし |

**coupon が最も悪い。** order・chat は「行がないな」を読み取り時点で気づけるが、
クーポンは **受け取れなかったという事実そのものを後で知る方法がない。** 読み取り時のフォールバックでは直せない
類の欠落だ — 突合（reconciliation）バッチ以外に答えはない。

## 4. 急ぎのものだけ塞いだ

- order-service が注文時に行がなければ、user-service に問い合わせて作る。
- （その後 ② を適用し、発行そのものを Outbox に移した — 下の §6-②。）

## 5. 🔴 読み取り時のバックフィルは <b>復旧用のバルブ</b> であって設計ではない

3 つの理由から、正常経路になってはいけない。

1. **同期依存を復活させる。** 注文作成が user-service の可用性に縛られる — 非同期レプリケーションが
   そもそも避けようとしていたまさにそれだ。user-service が遅ければ注文が遅くなり、落ちれば新規
   顧客は注文できない。
2. **ドリフトを覆い隠す。** バックフィルが毎回成功すると、Kafka への発行が壊れたままでも誰も
   気づかない。`warn` で残してはいるが、**ログはアラームではない。**
3. **コンシューマーごとに作り直す必要がある。** chat はすでに別に作ってあり（ニックネームは推測する）、
   coupon はそもそも不可能だ。同じ問題を 3 回解いている。

## 6. 改善の経路（推奨順）

### ✅ ① そもそもレプリケーションしない — 検証済みのトークンがすでに運んでいる ⭐ — **適用済み（2026-09）**

**order-service で `orders.customer` がやっていることはたった 1 つだ:**

```java
customerRepository.findByEmail(email).map(Customer::getId)   // メールアドレス → id
```

`username`・`nickname` カラムは **どこからも読まれていない。** ところがその id はすでに JWT の中にあり
（`uid` クレーム）、ゲートウェイが **`User-Id` ヘッダー** で渡してくれる。`orders.orders.customer_id` には
**FK もない**（ただの `bigint`）。

つまりこのテーブルは、**order-service がすでに知っている答えを得るために** 存在している。Kafka の
イベントも、バックフィルも、`customers` キャッシュも、すべてそのためにある。

`User-Id` ヘッダーを使えば、注文経路からこのテーブルが **丸ごと抜ける。** その後に残る問いは
「管理画面・レポートにこのテーブルが必要か」の 1 つだけで、不要なら削除する。

chat は事情が違う — **ニックネームを画面に使う。** それは本物のレプリケーションなので、②・③ が必要だ。

**適用したもの**

| | 前 | 後 |
|---|---|---|
| リクエスト元のアイデンティティ | コントローラーが `User-Email` → サービスが `orders.customer` から id を照会 | コントローラーが **`User-Id`**（トークンの uid、ゲートウェイは uid のないトークンを 401）→ サービスが `Long requesterId` を受け取る。所有権チェック 13 箇所 |
| 注文作成 | メールアドレスでコピーを照会、なければ user-service に問い合わせて **バックフィル** | 注文者の id はヘッダー。メールアドレスはカート・クーポンサービスの呼び出しにだけ使う（向こうの API がメールアドレスをキーに受け取る） |
| Webhook（Stripe・PortOne） | 注文の顧客 id → コピーからメールアドレス → 受け取る側が再びメールアドレス → id | 注文の顧客 id をそのまま |
| 管理者の注文一覧のメールアドレス | コピーから `findAllById` | トランザクションの **外**（`OrderService`）で user-service の `GET /api/auth/user/emails?ids=` を 1 回。失敗したらメールアドレスなしで描画する。そのエンドポイントは内部専用（docs/27 §9b のガード） |
| キャッシュ | `customers`（メールアドレス → id）、`order`・`orderList` のキーにメールアドレス | `customers` なし、キーはリクエスト元の id |
| イベント | `user-created` を購読（グループ `user-1`） | 購読しない — リスナー・コンシューマーファクトリー・`Customer` エンティティ・リポジトリを削除 |

- **テーブル `orders.customer` は残しておいた** → ✅ 移行の後片付けリリースの V16 が削除した（下の「移行の後片付け」）。今回のデプロイを戻すと古いコードが読む（expand-contract）。1 回の
  リリースが安定したら、`-- ROLLBACK-UNSAFE` 宣言を付けたマイグレーションで削除する（B-54）。
- トランザクション境界のガード（`OrderTransactionBoundaryTest`）が、最初の実装で `OrderDataService` に user-service のクライアントを
  入れたのを捕まえた — メールアドレスの付与をトランザクションの外に移した理由だ。
- **残っているもの**: ~~cart・chat・coupon は依然としてメールアドレス → id の照会を使っている~~ → 3 つとも `User-Id` に移した（§6-⑥）。リクエスト経路で
  `/api/auth/user/id` を呼ぶ箇所はない。残っている呼び出しは、coupon の管理者によるメールアドレス指定の発行と、移行リリース用の見積もりの代替経路だけだ。


### ✅ ② レプリケーションすべきものは Outbox で発行する — **適用済み**

`users.outbox_event` テーブルとエンティティは **最初からあった。** ところが使う場所がなかった —
`UserServiceImpl` に、こう **コメントアウトされたまま** 残っていた:

```java
//OutboxEvent event = createOutboxEvent(in);
//outboxEventRepository.save(event);
userCreatedProducer.publish(dto);     // ← これが動いていた
```

つまり Outbox を作っておきながら、**コメント 1 行の差で** dual-write を使っていた。

**変わったこと**

| | 前 | 後 |
|---|---|---|
| イベントの記録 | `registerConfirmed` から Kafka へ直接 — 認証確定とは **別のトランザクション** | `validateAndGetUser` が `user.setEnabled(true)` と **同じトランザクション** で Outbox の行を書く |
| 発行 | `kafkaTemplate.send` の非同期、結果を見ない | `UserOutboxScheduler` のポーラーが読んで **同期で** 送り、状態を確定 |
| 失敗 | 痕跡なし | 行が `FAILED` で残る — **何が送られなかったかを数えられる** |

**設計は order-service の `PaymentOutboxScheduler` と同じだ。** 同じ問題を 2 回違う方法で
解く理由はない。要点は 2 つ:

- **`FOR UPDATE SKIP LOCKED` で先取りする。** user-service は `replicas: 2` だ。ロックなしで
  読むと、2 つのレプリカが同じ行を発行する（docs/21 で決済の Outbox がそうだった）。
- **発行をそのトランザクションの中で同期的に待つ。** 非同期だと状態を変えるコールバックがコミットの
  後に回り、その間は行が STARTED なのにロックは外れている — その隙に別のレプリカが
  拾っていく。

**`UserOutboxHelper.recordUserCreated` は `MANDATORY` だ。** トランザクションなしで呼ばれると即座に
例外になる — `REQUIRED` だと黙って新しいトランザクションを開いて成立してしまい、それこそが舞い戻ってきた
dual-write だ。

**冪等性** は、`validateAndGetUser` の「すでに有効化済み」の早期リターンが担う。確認リンクを 2
回押しても行は 1 つだ。Outbox の記録がその早期リターンの **上に** 移ると壊れる。

> ⚠️ `FAILED` は **自動では再試行しない。** ブローカーが落ちていると 1 周期でバッチ全体が
> FAILED になるが、戻し続けてもロックを長く握るだけだ。復旧は ③ の突合バッチの役目だ。
> 今の目標は **見えるようにすること** までだ。

**CDC に移す余地は残っている。** `event_type` をトピック名（`user-created`）で書いて
あるので、後で Debezium の `EventRouter` を付ければ `route.by.field: event_type` が
そのまま動く。ポーラーを先に選んだ理由は、**イメージのデプロイだけで済むから** だ — コネクターは
Kafka Connect の設定・レプリケーションスロット・シークレットが一緒に動く必要がある。

ガード: `UserCreatedOutboxTest`（6 件）。Outbox の記録を消すと失敗することを確認した。

### ✅ ③ 突合（reconciliation）バッチ — **適用済み（2026-09、docs/57 §14）**

`UserReplicationReconciler` — 毎日、直近 7 日間に登録・変更・退会したユーザーのイベントを Outbox で **再送する。** 受け取る側が
すべて冪等なので（coupon は同じクーポンがあれば作らず、chat はないときだけ作り、より新しい値だけを適用する）、「欠けているものを探す」代わりに
「最近のものを再送する」で同じ結果になる。古い欠落は `user.replication.reconcile.window-days` を一度広げて回す。

「user-service にはいるのに order/chat にいないユーザー」、「登録クーポンを受け取れなかったユーザー」を
定期的に探して埋める。**ドリフトはリクエストごとに直すのではなく、定期的に洗い出して
直す。** coupon の欠落は、この方法でしか復旧しない。

### ✅ ④ 発行タイミングを登録時点に — **適用済み**

② を適用したとき、Outbox の記録を `validateAndGetUser`（認証リンク）の中に置いたが、**それだけでは
現在の設定では何も起きない。** 上の §1 の理由で、その経路を誰も通らない
からだ。そこで `registerNewUserAccount` に移した — アカウントの保存と同じトランザクションだ。

これで **認証の設定に関係なく常に発行される。** 認証を有効にした環境では未認証のアカウントにも下流の
サービスの行ができるが、そのアカウントはログインそのものができないので害はない。逆の状態 —「アカウントは
あるのに下流のサービスにはない」— のほうがはるかに悪いことを docs/47 で確認した。

> ⚠️ 下流のサービスが認証済みかどうかを知る必要があるなら、`UserCreatedRequest` に `emailVerified` を
> 追加する。イベントのスキーマ（Avro マッピング）+ コンシューマー 3 つを一緒に直す必要があり、今は誰もその
> 値を必要としていないので行わなかった。

**すでに登録済みのアカウントは復活しない。** それは ③ の役目だ。

### ✅ ⑤ プロフィールの変更・退会も伝播する — `user-updated`・`user-deleted` — **適用済み（2026-09）**

`user-created` しかなかった間、chat のレプリカは **登録した瞬間の値で止まっていた。**

| 原本で起きたこと | 以前の chat のレプリカ | 問題 |
|---|---|---|
| ニックネーム・プロフィール写真の変更 | 古い値のまま | 相手の画面に古い名前 |
| 退会の最終処理（原本の匿名化） | **メールアドレス・名前・写真が残る** | 🔴 個人情報の削除要求を原本でだけ履行 — レプリカが漏洩元になる |

**調べているうちにもっと大きなものが出てきた — chat は登録イベントを一度も受け取れていなかった。**
user-service は `user-created` を Avro で書いているのに（`459126e`、トピックをすべて Avro に切り替え）、chat の
コンシューマーだけが `JsonDeserializer` のまま残っていた。すべての登録イベントがデシリアライズで失敗して DLT に行き、
チャット会員は **接続した瞬間に `createMissingMember` でのみ** 作られていた — ニックネームは **メールアドレスの前半** だった。
ニックネームは相手の画面に表示されるので、事実上すべてのチャット会員が **メールアドレスの ID 部分を相手にさらして** いた。
ガード（`AvroWiringGuardrailTest`）のリストに chat の user-created が抜けていたため、捕まえられなかった。

適用したもの:

| 場所 | 変更 |
|---|---|
| user-service `UserOutboxHelper` | `recordUserUpdated`（ニックネーム・画像・`updatedAt`）· `recordUserDeleted`（userId）— **リクエストと同じトランザクションの Outbox**（② と同じ方式、`MANDATORY`） |
| user-service `UserServiceImpl` · `WithdrawalService` | プロフィール・写真の変更後、退会の最終処理後に記録 |
| user-service `UserLifecycleProducer` · `UserOutboxScheduler` | リレーが JSON 文字列として同期発行、キー = userId（同じユーザーは 1 つのパーティション） |
| chat `UserLifecycleConsumer` | 2 つのトピックをバッチで受け取り、レコードごとにトレースを引き継ぐ（docs/51 §4h）。失敗はそのインデックスだけ DLT |
| chat `ChatMember` | `applyProfile(…, updatedAt)` — **古いイベントは捨てる** · `anonymize()` — 原本と同じルールの匿名メールアドレス、"탈퇴한 사용자"（退会したユーザー） |
| chat `V4__member_profile_sync.sql` | ニックネームの UNIQUE を削除（§7）+ `profile_updated_at` |
| chat `KafkaConsumerConfig` | 🐛 user-created を `AvroDtoDeserializer` で（coupon と同じ） |
| chat `UserMessageListener.userCreated` | その場で作られた行が先にあれば **原本のプロフィールで埋める** — 以前は id の衝突を飲み込んで終わっていた |
| chat `ChatService.createMissingMember` | 仮のニックネームにメールアドレスを使わない（`회원 <id36>`） |
| common `AvroWiringGuardrailTest` | chat の user-created の Avro デシリアライザーをリストに追加した。order は ① でもう受け取らないので外した |
| `ops/02` TOPICS | `user-updated user-deleted`（B-41） |

設計で選んだものとその理由:

- **イベントは「差分」ではなく「最新の状態全体」を載せ、`updatedAt` を付ける。** キーが userId なので
  1 つのパーティションで順番に読まれるが、Outbox のリレーが **失敗した行を後で再送すると**、新しい変更より
  遅れて届くことがある。受け取る側は `profile_updated_at` より古いものを捨てる。登録イベントは
  時刻 0 として扱う — どの変更よりも古い。
- **退会は行を削除せず匿名化する。** チャットルームへの参加・メッセージが member_id を指している。削除すると
  相手の会話記録が壊れる。識別情報（メールアドレス・名前・写真）だけを消すのが、原本（`WithdrawalService`）と
  同じポリシーだ。匿名化後に `profile_updated_at = MAX` で固定し、**遅れて届いた変更・登録イベントが復活させられないようにする。**
- **`user-updated` にはメールアドレスを載せない。** 受け取る側が使わない（データ最小化）。そのためレプリカが
  ないときに作ることもできない — スキップする。ない理由はたいてい登録イベントが DLT にあることで、再処理すればできる。
- **コンシューマーは `earliest`。** 新しいグループが初めて立ち上がる前に積もった退会を取りこぼさない。処理は冪等だ。
- **coupon は受け取らない。** userId しか使わず、表示用のフィールドがない。

実務と比べて残っているもの: **すでに DLT に積もった古い `user-created` は、この修正では復活しない。**
デプロイ後に `chat` の user-created の DLT を再処理すれば（docs/11 §3）、その場で作られた会員が原本のニックネームで埋められる。
その間に登録イベントがなくても、**プロフィールを一度変えれば** `user-updated` が埋める。

ガード: `UserLifecycleOutboxTest`（5）· `WithdrawalServiceTest.finalizeTellsReplicas` ·
chat `UserLifecycleConsumerTest`（12）· `AvroWiringGuardrailTest` · B-41 · B-60b。

### ✅ ⑥ chat のアイデンティティを uid に移し、`user-created` からメールアドレスを外す — **適用済み（2026-09）**

データ最小化の原則からすれば、メールアドレスは外すべきだ — レプリカが **多いほど漏れる場所が増える** フィールドだ。コンシューマーごとに確認した。

| コンシューマー | メールアドレスを使うか（前） | 現在 |
|---|---|---|
| coupon | ❌ userId だけを使う | そのまま |
| order | — もう受け取らない（①） | — |
| **chat** | ✅ **アイデンティティのキーがメールアドレスだった** — STOMP セッション（トークンの subject）、会員照会 `findByEmail`、参加者の確認 `…Member_Email`、メッセージの送信者フィールド、フロントの「自分のメッセージ」判定（`chat.email === user.email`） | **uid（member_id）** |

そのため順序は **chat を uid に → イベントからメールアドレスを削除** だった。先にメールアドレスを外すと、新規登録者のチャット会員照会が失敗する。

#### なぜメールアドレスがアイデンティティではいけなかったか

- **ルームの相手に漏れていた。** 送信者をメールアドレスで表していたので、メッセージごとにメールアドレスが載ってルームのすべての参加者に届いていた
  （画面には描画しなかったが、開発者ツールで見える）。入室の案内は `"<メールアドレス> entered."` として画面に表示されていた。
- **変わる値だ。** メールアドレスを変えると、古いメッセージが他人のものになる。id は不変だ。
- **トークンがすでに運んでいる。** ゲートウェイ・order は `uid` に移した（①）。chat だけが subject（メールアドレス）を使っていた。

#### 変えたもの

| 層 | 前 | 後 |
|---|---|---|
| STOMP CONNECT | セッションに `email`（+ `userId`） | セッションに `userId` **1 つ**。uid のないトークンは拒否 |
| 送信・入室・退室 | 本文の email をセッションの email で上書き | セッションの uid で `senderId` を埋め、本文の email は消す |
| 会員照会・参加者の確認 | `findByEmail`、`…Member_Email` | `findById`、`…Member_Id` |
| REST（ルーム作成・履歴） | トークンの subject | トークンの uid（なければ 401） |
| メッセージ（Kafka・Mongo・画面） | `email` | `senderId`。画面へは **文字列** で（19 桁の TSID は JS の Number で潰れる）。DTO・Mongo のモデルに `email` フィールドが **ない** — 古いクライアントが送っても無視する |
| Avro `ChatMessage` | v1 | **v3**（v2 で `senderId` を追加、v3 で `email` を削除）。v1・v2 は `avro-history/` に置き、chat・notification のデコーダーが `SchemaStore` で読む |
| 古い会話記録（Mongo v1） | 送信者 = メールアドレス | 起動時に `LegacySenderBackfill` が id に変え、メールアドレスを消す。マッピングにない送信者（退会など）は **メールアドレスだけを消す**。すべて終わったらマッピングも消す |
| 退会（`user-deleted`） | 会員の行だけを匿名化 | 会話記録のニックネームのコピー・古いメールアドレスも消す（入室・退室の文言を含む）。内容は残す — 相手の会話でもある |
| `chat.member.email` | すべての会員 | **カラムなし**（V5）。古い記録のバックフィル用のマッピングは `chat.legacy_sender_email` に移し、バックフィルが終わったら空にする |
| `user-created` | email を含む | **ない** — DTO・Avro（v2）ともに。v1 のレコードは履歴として読む |
| フロント | `chat.email === user.email` | `sameUserId(chat.senderId, user.userId)`。`user.userId` はトークンの **原文** から文字として読む（`uidOf`） |

#### 設計で選んだものとその理由

- **uid の精度。** フロントの `user.userId` は `jwtDecode` → `JSON.parse` を経て `Number` になっていた。19 桁の TSID は
  2^53 を超えるので **末尾の桁が潰れる**（…789 → …800）。サーバーが送る `senderId`（正確な文字列）と比べると、「自分のメッセージ」が
  永遠に他人のメッセージになる — 黙って間違える。署名の検証はサーバーの役目なので、画面はトークン原文の JSON テキストから数値を
  文字として取り出す。注文番号ですでに一度経験した落とし穴だ（docs/25 §11.6）。
- **スキーマの進化。** レジストリがないので、レコード先頭の **フィンガープリント** で書き込み時のスキーマを探す。フィールドを 1 つ足すだけでもフィンガープリントが変わり、
  以前の方式ならデプロイ途中に古い Pod が書いたメッセージはすべてデシリアライズに失敗して DLT に行く（ルームに届かない）。古いスキーマを
  クラスパスに残してデコーダーに登録する `AvroSchemaHistory` を作った — 次のスキーマ変更も同じ方式で行う。
- **古いデータは 1 回のデプロイで片付ける。** メールアドレスのカラムを消す V5 は、起動時のバックフィルより **先に** 回る — カラムだけを消すと
  バックフィルが「メールアドレス → id」を失う。そこで V5 がマッピングを専用テーブルに移してからカラムを消し、バックフィルはそのテーブルに切り替えた
  後、Mongo にメールアドレスが 1 つも残っていなければテーブルを空にする。「デプロイしてバックフィルを確認した後、次のリリースで消す」は 2 回の
  デプロイと人の確認が必要だが、この方式は順序をコードが守る。バックフィル前のドキュメントは画面に出ない — readiness は
  ランナーが終わった後に準備完了になり、Mongo のモデルに `email` フィールドがないので読まれもしない。バックフィルが失敗しても起動は止めない
  （送信者が空に見えるだけで、次の起動で再び回る）。
- **送信者の分からない古いメールアドレスは消す。** すでに退会してマッピングのない送信者のメールアドレスを残しておく理由はない。そのメッセージは
  「他人のメッセージ」として描画される。
- **退会時の会話記録。** メッセージを消すと相手の会話が消える。送信者の表示（ニックネームのコピー・メールアドレス）だけを消す。

#### デプロイの順序

1. **notification-service** — v1・v2・v3 をすべて読む（先に上げておけば、chat が新しいスキーマを書き始めても通知が途切れない）。
2. **chat-service** — V4・V5 のマイグレーション、起動時のバックフィル。ログ `옛 발신자 백필: id 로 바꾼 문서 N건, 발신자를 모른 채
   이메일만 지운 문서 M건, 지운 매핑 K건`（古い送信者のバックフィル: id に置き換えたドキュメント N 件、送信者不明のままメールアドレスだけ消したドキュメント M 件、消したマッピング K 件）が 1 回出て、その後の起動からは静かになる（やることがない）。
   - ⚠️ **V5 は元に戻せない**（`ROLLBACK-UNSAFE`）— メールアドレスのカラムを消す。以前のイメージは Hibernate の validate で起動
     できない。chat は 1 レプリカなので、ローリング中に新しい Pod が準備完了になるまで古い Pod と少し重なるが、その間は古い Pod のチャット会員
     照会が失敗する。
   - ローリング中に古い Pod が書いた v1・v2 のメッセージは、新しい Pod が読む。v1 は送信者がメールアドレスなので、その数件は「送信者不明」として保存される。
3. **user-service + frontend** — 一緒に（2FA のレスポンス形式も変わった、docs/27 §12b）。frontend が chat より先に行くと、その間
   すべてのメッセージが「他人のメッセージ」に見える（機能は問題ない）。

#### cart もユーザー id に（同じデプロイ）

cart はカートを id で保存していたのに、アイデンティティ・キャッシュキーだけがメールアドレスだった。そのためキャッシュを消すすべての経路が user-service に
id → メールアドレスを問い合わせており、その呼び出しに使う **ユーザートークンを Kafka メッセージに載せて** 運んでいた。`/savecart` は本文の email を信じて
他人のカートを上書きできた。今はアイデンティティが `User-Id`、キャッシュキーが `carts::<id>`、user-service の呼び出しはない。
cart-empty・cart-rollback・coupon-use からトークンを外した（スキーマ v2、履歴として v1 も読む）。詳細は docs/54 §1.1・§1.7。

デプロイ中（ops/28 はサービスをほぼ同時に入れ替える）、古い Pod と新しい Pod が混在する数分間:

- **新しい order・coupon → 古い cart**: 移行用の呼び出しが `User-Id` と古い `User-Email` を **一緒に** 載せる — 通る。
- **古い order・coupon → 新しい cart**: `User-Id` がないので 401 — その間の注文作成・クーポンの見積もりが失敗しうる（再試行すればよい）。
- **新しい order・coupon の v2 メッセージ → 古い cart**: 古い cart は v2 のフィンガープリントを知らないので DLT に行く。デプロイ後に cart-empty・cart-rollback・
  coupon-use の DLT を再処理する（docs/11 §3 — 処理は冪等だ）。
- 新しい cart は古い v1 のメッセージ（トークンを含む）を読む — トークンは捨てる。

次のリリースで order・coupon の移行用の呼び出し（`getCartDuringRollout`）を消し、`User-Id` だけを載せる → ✅ 消した（下の「移行の後片付け」）。

#### coupon もユーザー id に

coupon はクーポンをユーザー id で保存していたのに、リクエストごとに `User-Email` を受け取って user-service にメールアドレス → id を改めて問い合わせていた
（適用・一覧・キャンペーンでの受け取り・注文の見積もり）。🔴 その Feign 呼び出し（`findUserByEmail`）は **アクセストークンをクエリパラメーター**
（`?Authorization=Bearer …`）で載せていた — URL はアクセスログ・プロキシ・例外メッセージに残る。受け取る側は読んですらいなかった。

| | 前 | 後 |
|---|---|---|
| 適用・一覧・キャンペーンでの受け取り | `User-Email` → user-service → id | `User-Id` で直接クーポンを照会（user-service の呼び出しなし） |
| 顧客セグメント（キャンペーン対象の判定） | `/user/segments?email=` | `/user/segments?userId=`（user-service は移行期間中は両方受け付ける） |
| 注文の見積もり `/internal/quote` | order が `email` を送る | order が `userId` を送る。coupon は `userId` がないときだけメールアドレスで探す |
| `findUserByEmail` | トークンを URL のクエリで | トークンのパラメーターを削除（インターセプターがヘッダーで渡す）。管理者によるメールアドレス指定の発行と移行用の見積もりでだけ使う |
| `/applycoupon` の本文 | `@Deprecated userEmail` を受け取って無視 | フィールドごと削除、古いクライアントが送っても `ignoreUnknown` で捨てる |
| 登録クーポン（user-created の消費） | ヘッダーのトークンを引数で渡す | 引数なし — イベントの `userId` だけを使う |

デプロイ中（user-service と他のサービスが同じ段階で入れ替わる）、混在する数分間:

- **新しい order → 古い coupon**: `email` を一緒に載せ、インターセプターが `Authorization` ヘッダーを渡す — 古い coupon が読むとおりに通る。
- **古い order → 新しい coupon**: `userId` なしで `email` だけが来る — 新しい coupon がそのメールアドレスで id を探す。見つからなければ「クーポンなし」と
  同じレスポンスだ（500 ではない）。
- **新しい coupon → 古い user-service の `/user/segments`**: 古い側は `email` が必須なので 400 — 顧客セグメントを読めなければ対象外と
  答える（黙って付与しない側に倒れる失敗、再試行すればよい）。全員対象のキャンペーンは顧客セグメントを問い合わせないので影響はない。
- 古い coupon がまだ動いている間も、フロントはすでに本文にメールアドレスを送らない — 古い coupon も本文の値は読んでいなかった。

次のリリースで: order の見積もり呼び出しから `email` を、coupon の見積もりのメールアドレスによる代替経路（`legacyUserIdOf`）を、
user-service の `/user/segments` の `email` パラメーターを消す → ✅ 消した（下の「移行の後片付け」）。

ガード: `CouponQuoteTest`（id で直接照会・user-service を呼ばない、見積もりは id 優先、メールアドレスだけ来たら代替、どちらもなければ拒否）·
`EventCouponClaimTest`（検証済みの id で発行、メールアドレスでの照会なし）· `CouponApplyOwnershipTest`（コントローラーが `User-Id` を読む、
本文の古い `userEmail` は無視）· order `FeignContractTest`（見積もりは `userId`、どのクライアントもトークンを URL に載せない）·
`UserLookupGuardTest`（顧客セグメントを id で、存在しないユーザーは空のリスト）。

#### ✅ 移行の後片付け — 上の移行リリースの後始末（expand-contract の contract）

上の移行コードは、**古い Pod と新しい Pod が混在する数分間** のためのものだった。移行リリース（コミット `1227834` まで）が本番に載って
安定した後でなければ意味のない後片付けだ。

| 消したもの | サービス | 代わりに |
|---|---|---|
| `getCartDuringRollout`（`User-Id` + 古い `User-Email`） | order · coupon | `getCart(User-Id)` 1 つ |
| 注文作成の `User-Email` ヘッダー・`OrderRequest.email`・使っていなかったトークン引数 | order · common · frontend | 注文者は `User-Id` 1 つ。古いクライアントが送る `email` は Jackson が捨てる |
| 見積もりの `email` パラメーターとメールアドレス → id の代替経路 | order · coupon | `userId` 必須。なければ「クーポンなし」のレスポンス |
| `/applycoupon` の `User-Email` ヘッダー | coupon | `User-Id` 1 つ |
| `/user/segments` の `email` パラメーター | user | `userId` 1 つ |
| `LegacyAuthorizationHeader`（`Authorization: Bearer none` の目印） | common · order · pay | Saga のメッセージに `Authorization` ヘッダーそのものがない |
| `LegacySenderBackfill`・`LegacySenderEmails`、退会処理での古いメールアドレスの照会 | chat | 退会は `senderId` だけで探す |
| `chat.legacy_sender_email` テーブル | chat **V6** | — |
| `orders.customer` テーブル | order **V16** | — |

🔴 **デプロイの順序がそのまま条件だ — 移行リリースを飛ばしてこれを直接上げてはいけない。**

- **chat V6 は自ら止まる。** マッピングテーブルに行が残っていれば（= 古い送信者のバックフィルが終わっていない）、削除せずに
  例外で失敗する。PostgreSQL は DDL もトランザクションなので何も変わらず、新しい Pod が準備完了にならず古い Pod が回り続ける。
  そのときは移行リリースを先に上げ、ログ `옛 발신자 백필: … 지운 매핑 N건` を確認してから改めて上げる。テーブルのない
  新しい DB ではそのまま通過する（PG16 で 3 つのケースすべてを確認 — 最初に書いた `IF a AND EXISTS(...)` はテーブルがないと失敗したので、
  ネストした IF に直した）。
- **残りは自ら止まれない。** 移行リリースなしで古いバージョンから直接上げると、混在する数分間に古い cart・coupon が
  `User-Email` を受け取れず 401、古い pay・order が `Authorization` ヘッダーのない Saga メッセージを DLT に送る。データは失わ
  ない（DLT の再処理で復旧 — docs/11 §3）。それでも順序は守る。
- **元に戻す場合**: V6・V16 は `ROLLBACK-UNSAFE` だ。移行リリースのイメージに戻すと、chat の退会処理が存在しないテーブルを読んで
  DLT に行く（空のテーブルを作り直せばよい — バックフィルが終わっていたなら元々空だった）。`orders.customer` を読むイメージは、すでに
  2 リリース前だ。

残っているもの:

- `avro-history/` の古いスキーマ — そのスキーマで書いたレコードがトピックの保持期間（デフォルト 7 日）を過ぎて消えたら削除する。期間が
  基準なので、コードのデプロイ順序で前倒しすることはできない。
- この後片付けとは無関係の expand-contract が 1 つ: 決済の古い `paypal_order_id` カラム → order・pay ともに整理した（docs/49 §3）。メッセージの
  `paypalOrderId` もスキーマ v4 で消した（C1・C2）。C3（v1・v2 の読み取り経路の削除、B の 14 日後）だけが残った。

ガード: `StompHandlerSubscribeTest`（uid のない CONNECT を拒否、セッションにメールアドレスなし、メールアドレスだけのセッションを拒否）·
`ChatServiceSendMessageTest`（ブロードキャストの JSON に `email` なし・`senderId` は文字列、古い送信者の変換）·
`UserLifecycleConsumerTest`（退会が会話記録の送信者表示を `senderId` で消す、会員モデルにメールアドレスのフィールドなし）· `MessageMapperTest.noEmailFieldAnywhere` ·
`ChatMessageSchemaHistoryTest`（chat v1・v2、user-created v1 のレコードを新しいデコーダーが読む、email の混じった古い JSON）·
`UserLifecycleOutboxTest.createdCarriesNoEmail` · V5 は PG16 に直接適用して確認 ·
フロント `ChatContentSender.test`（19 桁の id、潰れた id は自分のものではない、メールアドレスでは判定しない）· `jwtUser.test`。

## 7. スキーマの地雷（一緒に整理すべきもの）

- **`uk_customer_nickname UNIQUE`、`uk_customer_username UNIQUE`**（`orders.customer`）。
  **同名の人が 2 人いると、2 人目のユーザーのレプリケーションが失敗する。** レプリケーションされた表示用のフィールドに一意
  制約をかけるのは、原本の制約をレプリカで真似ることだが、原本が変わればずれる。
- ✅ **chat のニックネームの推測** — 修正済み（§6-⑤）。`createMissingMember` がメールアドレスの前半をニックネームに使っており、
  後でイベントが届いても `persist` の衝突で **上書きしなかった。** 今は仮のニックネームはメールアドレスと無関係で、
  登録イベントが来たら原本の値で埋める。
- ✅ **`chat.member` の `uk_member_nickname UNIQUE`** — なくした（V4）。order のものと同じ地雷だ: 同名の人の
  登録・変更が制約違反で失敗し、退会の匿名化（"탈퇴한 사용자"（退会したユーザー））は複数人が同じ名前を持てる必要がある。
- **`user-created` のコンシューマーグループがサービスごとに異なる**（`user-1`、`user-2`、…）。意図したものだが、
  トピックの retention（デフォルト 7 日）を過ぎると、どのグループも過去を読み直せない。③ が必要な
  理由でもある。

## 8. やらないと決めたこと

- ~~**バックフィルは消さない。**~~ order のバックフィルは ① と一緒に消した — もうレプリケーションしないので、埋めるものもない。
  chat のその場での作成（`createMissingMember`）は残す: chat は依然としてレプリケーションしており、DLT に残った古い登録者がいる。
- **同期呼び出しに全面的に切り替えることはしない。**「レプリケーションせずにその都度問い合わせよう」は ① とは違う。
  ① は **問い合わせる必要すらなくすこと** であり、全面的な同期化はサービス間の結合を増やす。
