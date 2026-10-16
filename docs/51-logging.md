# 51. ロギング — 認証番号の漏えいを防ぎ、出力段階でマスキングし、JSON で集めてトレースとつなぐ

**日本語** | [한국어](51-logging.ko.md)

> 🕰️ **状態（2026-09-27）— 一部古い。** §5 の最初の項目（バッチリスナーのトレース）は §4h で解消され、Loki ベースのアラートの一部が入りました。

背景: このプロジェクトのロギング機能が ruke79/facebook リポジトリのロギング機能より劣っているかを評価し、
評価から出た改善 1〜4 番を順番に適用する。

facebook リポジトリの `backend-spring` は**ロギング実習専用**の単一サービスだ。JSON 構造化ログ、出力
段階のマスキング、MDC（traceId・userId）、非同期スレッドへの伝播、アクセスログ、ランタイムのレベル変更、ログ氾濫の
防御、Loki による収集、ログキャプチャテスト 53 件を備えている。このプロジェクトはサービス間の**分散トレース**（Micrometer
Tracing + Zipkin）と**メトリクス通知**（Prometheus + Alertmanager）では先行しているが、ログそのものは平文のパターンで
マスキングは呼ぶ側の裁量であり、収集器もなかった。評価結果に従い、順番に 4 つを適用した。

| 段階 | やったこと | 主要ファイル |
|---|---|---|
| 1 | SMS ログ送信器から**認証番号を含む本文**を外した | `notification-service/.../sms/LoggingSmsSender.java` |
| 2 | **出力段階のマスキング**を共通モジュールで全サービスに | `common/.../logging/*`, `common/src/main/resources/logback-spring.xml` |
| 3 | 共通設定に **JSON ファイル・ローリング・非同期**、MDC `userId`、非同期スレッドへの伝播 | 同じファイル、`UserMdcFilter`, `MdcTaskDecorator` |
| 4 | **Loki + Alloy** による収集、Grafana で**ログ ↔ Zipkin トレース**を往復 | `docker-compose-observability.yml`, `observability/*` |

---

## 1. 🔒 携帯電話の認証番号が本番ログに平文で残っていた

`LoggingSmsSender` は実際には送らずログだけを残す送信器で、**デフォルト**だ
（`notification.sms.provider` のデフォルトは `logging`、k8s も変えていない）。ところが SMS の本文全体を
`body={}` で INFO に出力していた。認証 SMS の本文には認証番号がある。

```
[SMS:dry-run] to=+8210****5678 bytes=24 single=true body=[쇼핑몰] 인증번호 482913 ...
```

ログを読める人なら、他人の番号で認証を完了できる。`VerificationService` は
「コードは絶対にログに残さない」と書いて**自分のログからは**外していたが、そのコードを受け取った
送信器がそのまま出力していた。docs/38 §1.7 が指摘していたのに残っていた。

**本文を外し、マスキングした番号・文字数・1 通かどうかだけを残す。** 2 段階目のグローバルマスキングでは防げない —
6 桁の数字は注文番号・金額・数量と区別がつかない。認証番号は**そもそもログに渡さない
こと**がルールだ。

**ガード**: `LoggingSmsSenderTest` — 実際のログを `ListAppender` でキャプチャし、韓国語・日本語の認証文言の
コードがどの行にも、**フォーマット前の引数にも**ないことを確認する（JSON エンコーダは引数を別途シリアライズし得る）。
古いコードに戻すと失敗することを確認した。

---

## 2. 出力段階のマスキング —「覚えていて呼ぶ」方式は必ずどこか 1 か所で漏れる

以前は呼ぶ側がマスキングしていた（`EmailAddresses.mask`、`PhoneNumbers.mask`、`DeviceIdentity.maskIp`）。
通知サービスはよく守っていたが、cart・order・user サービスにはメールアドレスをそのまま出力するログが 20 か所近く
あった（`CartService` の `Cache miss for carts::{email}` など）。

今は logback が**行を書く直前に**強制的にフィルタする。

- テキスト（コンソール）: `%mask(%msg)` · `%mask(%wEx)` — `MaskingPatternConverter`
- JSON: `MaskingJsonGeneratorDecorator` + `SensitiveValueMasker` — メッセージ・MDC・引数・スタックトレースの
  **すべての文字列値**
- ルールは 1 つ: `SensitiveDataMasker`。コンソールと JSON が別々にマスキングすると、片方から漏れる。

| マスキングするもの | 例 | 結果 |
|---|---|---|
| Bearer/Basic トークン | `Bearer abcdefgh…` | `Bearer ****` |
| JWT | `eyJhbGci….eyJzdWIi….c2ln…` | `eyJ****.****.****` |
| シークレット値 key=value・JSON | `"password":"hunter22"` | `"password":"****"` |
| 住民登録番号 | `900101-1234567` | `900101-1******` |
| カード番号 16 桁 | `4111-1111-1111-1111` | `4111-1111-11**-****` |
| 国際表記の携帯電話（E.164） | `+821012345678` | `+82****5678` |
| 韓国・日本の携帯電話 | `010-1234-5678` · `090-1234-5678` | `010-****-5678` |
| メールアドレス | `hong.gildong@example.com` | `h****@example.com` |

**マスキングしないもの — 意図的に。** 認証番号（上の 1 段階目）、IP（Kafka・DB のアドレスと区別がつかない — ユーザーの IP は
保存時にすでにマスキングしている）、そして**注文番号（TSID 18〜19 桁）・UUID・金額・時刻・traceId**。誤検知すると障害時に
ログで注文を探せなくなる。`SensitiveDataMaskerTest` の「触らない」入力は、このリポジトリの実際の
ログの形だ。

facebook から移す際に変えたもの:

- **E.164 と日本の番号**を加えた。このプロジェクトは番号を `+8210…`・`+8190…` で保存する。
- **検査長の上限 16KB → 64KB。** コンソールはスタックトレースもマスキングを通るが、Spring の深いスタックは
  16KB を簡単に超え、**肝心の `Caused by` が切り落とされていた。**
- `Authorization: Bearer x` は key=value ルールが `Bearer` までマスキングする（`Authorization: **** ****`）。
  多めに隠れるだけで漏れはしない — テストがその動作を固定している。

### すべてのサービスに適用される仕組み

`common` jar のルートに `logback-spring.xml` を置いた。common を使うすべてのサービスがこのファイルで
ログを書く。レベル（`logging.level`）とファイル名（`logging.file.name`）は今までどおりサービスが決める。

- サービスの yml の `logging.pattern.*` 11 か所を消した — 共通設定がパターンを決めるので、置いても使われない。
- 🐛 **eureka-server** は 3 重にずれていた。
  1. yml の `logging:` が 2 スペースインデントされて `eureka.logging.*` に入っており、Spring が読んでいなかった。
  2. 代わりに自前の `logback-spring.xml` が Netflix のロガーを **DEBUG に固定**していた（B-46 は yml しか見ていなかったので捕まえられなかった）。
  3. 本番用だという `logback-psring-prd.xml` は名前の誤字で誰にも読まれていなかった
     （出力先も `logstash.yourcompany.com` というプレースホルダだった）。

  yml ブロックをトップレベルに移し、2 つの XML を消した。
- **config-server は除外だ。** common に依存していない（設定サーバーに Kafka・Avro を引き込む理由が
  ない）。ユーザーデータを扱わず、Spring のデフォルト設定と自前の yml パターンのまま残る。

**ガード**

- `CommonLogbackConfigTest` — 共通設定を**Spring がサービス起動時に開く方式そのままで**
  （`LogbackLoggingSystem.initialize`）開いてみる。クラス名の誤字 1 つで Spring が
  "Logback configuration error detected" で **12 サービスの起動を一斉に止める** — サービスを
  立ち上げる前にここで捕まえる。そして実際に出力されたコンソール・ファイルの行を読み、マスキングされたかを見る。
- **B-57** — common を使うモジュールが自前の `logback*.xml` や `logging.pattern` を置くと失敗する
  （置くと共通設定のマスキングが抜ける）。パターンと logback ファイルを復活させて失敗することを確認した。
- **B-57b** — 共通設定のすべてのテキストパターンが `%mask(%msg)`・`%mask(%wEx)` を使い、すべての
  `LogstashEncoder` に `SensitiveValueMasker` が付いていなければならない。新しい appender を加えるときに
  `%msg` をそのまま使うと、その出力からだけ平文が漏れる。

---

## 3. JSON ファイル・ローリング・非同期、MDC `userId`、非同期スレッドへの伝播

### 共通設定が出力するもの

| 出力 | 形式 | 有効にする方法 | デフォルト |
|---|---|---|---|
| コンソール | テキスト 1 行 | `LOG_CONSOLE_FORMAT=text` | ✅ |
| コンソール | JSON 1 行 | `LOG_CONSOLE_FORMAT=json` | |
| ファイル `logging.file.name`（`<モジュール>/logs/<サービス>.json`） | JSON 1 行、非同期書き込み | `LOG_JSON_FILE=true` | ✅（k8s は `false`） |

- テキスト行: `2026-09-24 10:00:00.123 [http-nio-8080-exec-1] [traceId,spanId,userId] WARN  c.p.c.s.CartService - …`
- JSON 行: `@timestamp, message, logger_name, thread_name, level, service, traceId, spanId, userId, stack_trace`
- ファイルのローリング: 100MB · 14 日 · **合計 1GB**。以前は Spring のデフォルト値（総量の上限なし）だった。
- 非同期: キュー 8192、`discardingThreshold=0`（キューが埋まっても INFO を黙って捨てない）、`neverBlock=false`
  （満杯になったら少し待つ — 欠落より遅延）。コンソールは同期のままにする — Pod が死ぬ直前の最後の行が
  `kubectl logs` に残らなければならない。
- スタックトレース（JSON）: 深さ 30 · 8KB · 原因を先に。

**なぜプロファイルではなく環境変数なのか。** このリポジトリはすべての環境が `local` プロファイルで動く（k8s を含む、
`SPRING_PROFILES_ACTIVE: "local"`）。`<springProfile>` で分けると k8s と開発 PC を区別できない。
そこで `FormatSwitchFilter` が `app.logging.*`（環境変数 `LOG_CONSOLE_FORMAT`・`LOG_JSON_FILE`）で appender を
オン・オフする。logback の `<if>` は Janino をさらに導入する必要があるので使わなかった。

**k8s はテキストコンソール + ファイルオフ**（`k8s/10-configmap.yaml`）。コンテナ内のファイルは誰も読まず、Pod が
消えればなくなり、ノードのディスクを使うだけだ。クラスタに Loki を付けて stdout を集めるときに `LOG_CONSOLE_FORMAT=json`
に変える。

### `userId` —「誰のリクエストか」

traceId・spanId は Micrometer Tracing がすでに MDC に入れている。抜けていたのはユーザーだった。「決済ができない」
という問い合わせが来ると、メールアドレスでログを grep するしかなかったが、そのメールアドレスは 2 段階目からマスキングされる。

`UserMdcFilter` が、ゲートウェイが入れた `User-Id` ヘッダを MDC `userId` に入れる。

- ゲートウェイはクライアントが送った身元ヘッダを消し、トークンから入れ直す。ゲートウェイを経由しない
  リクエストは偽装できるが、ここでは**ログ表示にだけ**使い、どんな判断にも使わない。
- ログインジェクション（改行で偽の行を作ること）は防ぐ — 英数字と `. _ : -` だけを残し、64 文字で切る。
- 終わったら `userId` **だけ**を消す。`MDC.clear()` はトレースが入れた traceId まで消してしまう。
- サーブレットのサービスにだけ付く。ゲートウェイ（リアクティブ）はフィルタチェーンが異なるので付かない — ゲートウェイのログは
  traceId でつなぐ。

### 非同期スレッドへ渡す

MDC はスレッドごとに別なので、`@Async` のメール・SSE・商品取り込み・ES 再索引のログで traceId・userId が空だった。
`MdcTaskDecorator` が渡す瞬間の MDC をワーカースレッドに付け、終わったらワーカースレッドの元の MDC に
戻す（戻さないと、再利用されるプールスレッドが他人の traceId を付けたまま動き回る）。

| 付けた場所 | 方式 |
|---|---|
| user-service `mailExecutor` · `sseExecutor` | `setTaskDecorator` |
| catalog-service バッチ `taskExecutor` | `setTaskDecorator` |
| catalog-service 商品取り込み · ES 再索引スレッド | `MdcTaskDecorator.wrap(...)` |
| Spring Boot デフォルトの `@Async` 実行器 | 自動構成の `TaskDecorator` Bean |

### サービスに付く仕組み — 自動構成

ほとんどのサービスは `com.project.common` をコンポーネントスキャンしない（order・cart・chat だけがする）。スキャンに
頼ると、サービスによって付いたり付かなかったりする。`CommonLoggingAutoConfiguration` を
`META-INF/spring/…AutoConfiguration.imports` に登録した。

**ガード**: `CommonLogbackConfigTest`（+3: JSON ファイルをパースしてマスキング・service・traceId・userId・stack_trace を確認、
json コンソールモード、ファイルオフ）、`UserMdcFilterTest`、`MdcTaskDecoratorTest`（プールスレッド再利用時に混ざらない）、
B-57 に `logging.file.name` は `.json`。eureka の起動テストが実際の Spring の経路で JSON ファイルを書くことも
確認した。

---

## 4. Loki + Alloy — ログを集めて Zipkin トレースとつなぐ

> 🔄 トレースの保存先は現在 Jaeger である（[docs/63](63-opentelemetry.md)）。以下の「Zipkin」は Jaeger と読み替える — データソースの uid は `jaeger`。

`docker-compose-observability.yml` に 2 つを加えた（既存の Prometheus・Grafana・Alertmanager・Zipkin の隣）。

```
<モジュール>/logs/*.json ──(Alloy, 読み取り専用マウント)──▶ Loki ──▶ Grafana Explore
                                                                    │  行の traceId をクリック ▼
                                             Zipkin ◀───────────────┘
                                               │  スパンの "Logs for this span" ▼
                                               └──────────────▶ Loki {job="ecommerce"} | json | traceId="…"
```

- **Promtail ではなく Alloy。** 評価のときは Promtail と書いたが、Grafana が Promtail のサポートを終了し、
  Alloy を後継として出した。新しく付ける収集器を、サポートが終わったものから選ぶ理由はない。
- **ラベルは `service`・`level` の 2 つだけ。** traceId・userId をラベルにするとリクエストごとにストリームができて Loki が
  遅くなる。行内のフィールドにして `| json | traceId="…"` でフィルタする。
- 🔒 **リポジトリのルートを丸ごとマウントしない。** モジュールごとに `logs` ディレクトリだけを読み取り専用で付ける。ルートを
  付けると、gitignore されたシークレットファイル（`k8s/20-secret.yaml`、`kafka-secrets.properties`）までコンテナから見える。
- Loki の保持は 7 日（サービス側のファイルは 14 日・1GB）。
- Grafana のデータソースは `uid` を固定した（`zipkin`・`loki`）— 互いを uid で指す。derivedFields の
  traceId の正規表現は Brave の形式（16・32 桁の小文字 16 進数）に合わせた。

### 使ってみる

```bash
docker compose -f docker-compose-observability.yml up -d
./gradlew :order-service:bootRun            # order-service/logs/order_service.json に書く
./ops/05-check-observability.sh             # Loki・Alloy のヘルスと、入ってきた service ラベルまで見る
```

Grafana（http://localhost:3001）→ Explore → Loki:

```logql
{service="order-service", level=~"WARN|ERROR"}
{job="ecommerce"} | json | userId="736459283746592837"
{job="ecommerce"} | json | traceId="4bf92f3577b34da6a3ce929d0e0e4736"
```

**ガード**: B-58 — common を使うすべてのモジュールの `logs` ディレクトリが Alloy に読み取り専用で付いていなければならず、
ルートを丸ごと付けると失敗する。サービスを加えて compose を忘れると、そのサービスのログだけが Grafana に**静かに**
ない。マウントの 1 行を消して失敗することを確認した。

⚠️ **当時の検証環境で確認できなかったこと。** Docker がないので Loki・Alloy・Grafana を実際に立ち上げてみられなかった。設定ファイルは
YAML のパースとガードでだけ確認した。イメージのバージョン（`grafana/loki:3.4.2`、`grafana/alloy:v1.9.1`）は、検証環境の
ネットワーク制限で GitHub を照会できなかったため最新かどうかを確認できなかった。最初に立ち上げるときに `docker compose logs loki alloy` を
一度見ること。

---

## 4c. k3s でデプロイしたサービスのログ — Pod の標準出力を読む

最初の構成は各モジュールの `logs/*.json` だけを読んでいた。そのファイルは、サービスをローカルで `bootRun` で立ち上げたときにしかできない。
k3s ではログは Pod の**標準出力**で（k8s の configmap は `LOG_JSON_FILE=false`）、k3s はそれをホストの
`/var/log/pods/<ネームスペース>_<Pod>_<uid>/<コンテナ>/<n>.log` に CRI 形式で溜める。そのままでは **k3s で
デプロイしたサービスのログは Loki に 1 つも来ない。**

同じホストで compose で立ち上げる Alloy が `/var/log/pods` を読み取り専用で付け、`ecommerce_*` Pod のログも
読む（`job="ecommerce-k3s"`）。

- `service` ラベル = パスのコンテナ名（= サービス名、例: `order-service`）
- `stage.cri` で CRI のヘッダ（時刻・stdout・F）をほどき、その時刻をログの時刻として使う
- 私たちの Spring サービスにだけ、複数行のまとめ（スタックトレースを 1 件に）とテキスト行のレベル抽出をかける。
  frontend（nginx）・cloudflared の行は日付で始まらないので、全部にかけると前の行にくっつき続ける。
- Alloy は root で動く — Pod のログファイルが root 所有（0640）だ。マウントはすべて読み取り専用だ。
- Zipkin → ログの問い合わせは行フィルタ（`|= "<traceId>"`）なので、JSON 行とテキスト行の両方で見つかる。Loki → Zipkin のリンクは
  JSON 用とテキスト用（`[traceId,spanId,userId]`）の 2 つだ。

```bash
docker compose -f docker-compose-observability.yml up -d
docker compose -f docker-compose-observability.yml logs alloy | tail -20
./ops/05-check-observability.sh        # "Loki 에 들어온 서비스 로그" にサービス名が出なければならない
```

```logql
{job="ecommerce-k3s", service="order-service"}
{job="ecommerce-k3s", level="ERROR"}
{job=~"ecommerce.*"} |= "736459283746592837"      # userId
```

⚠️ k3s のサービスは `ZIPKIN_ENDPOINT` を注入されていないので（デフォルト `localhost:9411` = Pod 自身）、**トレースが Zipkin に
行かない。** ログの traceId で同じリクエストの行同士はつなげられるが、Zipkin のリンクを押すと空の画面だ。
k3s でトレースまでつなぐ作業は、§5 の「k8s 可観測性スタック」と一緒に行う。

## 4d. k3s でもトレースが Zipkin に行く — そして Kafka を渡ってもつながる

> 🔄 **2026-09 以降: Zipkin → OpenTelemetry（[docs/63](63-opentelemetry.md)）。** 下の `ZIPKIN_ENDPOINT` · `:9411` は
> `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` · `http://$(HOST_IP):4318/v1/traces`（ノードの OTel Collector）に、Zipkin の画面は Jaeger に
> 変わった。ノード IP を downward API で受け取る仕組み · B-59 · ファイアウォールの注意はそのまま（ポートだけ 4318）。

### Pod → ノードの Zipkin

`management.zipkin.tracing.endpoint` のデフォルト値は `http://localhost:9411/api/v2/spans` だ。Pod の中で
`localhost` は **Pod 自身**なので、k3s でデプロイしたサービスのトレースは**エラーなしに静かに捨てられて**いた。

クラスタに Zipkin をもう 1 つ立ち上げず（単一ノードはすでにリソースが厳しく、Pod が Pending で列をなす）、
同じノードで compose で動く可観測性スタックの Zipkin（:9411）に送る。ノードの IP はマニフェストに書かず、
downward API で受け取る。

```yaml
- name: HOST_IP
  valueFrom:
    fieldRef:
      fieldPath: status.hostIP
- name: ZIPKIN_ENDPOINT
  value: "http://$(HOST_IP):9411/api/v2/spans"
```

- 共通設定を使う 11 個の Deployment（ゲートウェイを含む）に入れた。eureka・config-server は共通設定を使わ
  ず、送ってもハートビートの雑音だけなので外した。
- `$(HOST_IP)` は**前に定義された**変数しか展開されない — `HOST_IP` が先だ。
- **ガード B-59** が 2 項目があるか、順序が正しいか、ノードの :9411 を指しているかを見る。`localhost` に戻して
  失敗することを確認した。

⚠️ **ホストのファイアウォール。** Pod → ノード IP:9411 が塞がれると、トレースは依然として静かに捨てられる。`ops/05` が busybox Pod を
一時的に立ち上げて直接叩いてみる。塞がれていたら、ホストで Pod の帯域（k3s のデフォルト `10.42.0.0/16`）からの 9411 へのアクセスを許可する。
OCI の Ubuntu イメージは iptables のデフォルトルールが厳しく、塞がれ得る — 当時の検証環境では確認できなかった。

### Kafka を渡ってもトレースがつながる — プロデューサ側のトレース

コンシューマ側のトレース（`ContainerProperties.setObservationEnabled(true)`）はサービスごとに有効だったが、**送る側は 1
か所も有効になっていなかった。** メッセージにトレースヘッダ（`traceparent`）が載らず、受け取る側が新しいトレースを始めていた —
「カートを空にする」・「配送状態」・「購入確定の通知」・「登録確認メール」が、原因となったリクエストと切り離されて見えていた。

テンプレートを作る場所がサービスのあちこちに 35 か所あるので、1 つずつ直さず、共通の自動構成の
`KafkaTemplateObservationPostProcessor` がすべての `KafkaTemplate` **Bean** で有効にする（無効にするには
`app.tracing.kafka-producer=false`）。Bean ではないテンプレート — user-service の `SecurityAlertProducer`・
`OutgoingMailProducer` はコンストラクタで `new` で作る — にはレジストリを直接入れた。

⚠️ **Outbox では依然として途切れる。** 決済リクエストのように Outbox リレー（スケジューラ）が送るメッセージは、送る瞬間がリクエストの
中ではなくスケジューラの中なので、トレースはリレーで新しく始まる。注文リクエスト → 決済 → 注文確定を 1 つのトレースで
見るには、トレースコンテキストを Outbox の行に保存しておき、リレーが復元しなければならない — 今回の範囲外だ。

**ガード**: `KafkaTemplateObservationPostProcessorTest` — 自動構成が付いたコンテキストのテンプレート Bean は有効になり、無効にする
設定が効く。

## 4e. Outbox を渡ってもトレースがつながる

§4d で Kafka を渡るトレースはつながったが、**Outbox で途切れていた。** Outbox は「行を書く瞬間」と「送る
瞬間」が別のスレッドだ。行はリクエスト（またはリスナー）の中で書かれ、送るのは数秒後のスケジューラだ。トレースコンテキストは
スレッドに付いているのでスケジューラにはなく、Kafka ヘッダに載るトレースは**スケジューラが新しく始めたもの**だった。
Zipkin で「PayPal 決済確認」リクエストは DB に行を書いて終わり、その後の Saga は切り離された別のトレースだった。

| サービス | Outbox | 行を書く場所 | リレー |
|---|---|---|---|
| order-service | `orders.payment_outbox_event` | 決済・返金・入金確認の HTTP リクエスト | `PaymentOutboxScheduler` |
| pay-service | `pay.order_outbox_event` | 決済リクエストの Kafka リスナー | `OrderOutboxScheduler` |
| user-service | `users.outbox_event` | 登録確認の HTTP リクエスト | `UserOutboxScheduler` |

**つなぐ方法 — `common` の `OutboxTraceContext`**

1. 行を書くとき、`capture()` が現在のトレースコンテキストを伝播ヘッダの形のまま（W3C `traceparent=00-…`）新しいカラム
   `trace_context varchar(512)` に残す（order V14 · pay V4 · user V12）。
2. リレーは送る直前に `resume(stored, "outbox relay <種類>", send)` でそのコンテキストからスパンを復元し、
   そのスパンを現在のスパンにしたまま送る。§4d のプロデューサのトレースがその下の子になり、ヘッダに**元のリクエストの
   traceId** が載る。

結果: **決済確認リクエスト → 決済リクエスト（Outbox）→ pay-service リスナー → 決済レスポンス（Outbox）→ order-service
リスナー（注文 PAID）**が 1 つのトレースだ。pay-service はリスナーの中で行を書くので、そのリスナーのトレース — つまりすでに
元のリクエストのトレース — を残す。

- **新しい行のときだけ残す。** order・pay のヘルパーは同じ `save()` でリレーの状態更新もする。そのときに残すと
  スケジューラのコンテキストで上書きしてしまう（`isNew()` で分ける）。
- **トレースは発行を妨げない。** トレースの Bean がない、保存された値がない（古い行）、または壊れている場合は、以前のようにそのまま送る。
- トレースの Bean は使う瞬間に探す — 自動構成の順序上、この Bean が先に作られることがあり得る。

**ガード**

- `OutboxTraceContextTest` — **実際の Brave トレーサーと実際の KafkaTemplate**（MockProducer）で、リクエストスレッドで
  残したコンテキストを別のスレッドで復元して送ると、レコードヘッダに元の traceId が載るかを見る。対照群として、コンテキスト
  なしで送ると別のトレースになることも見る。
- `PaymentOutboxTraceTest` — 新しい行には残し、状態更新では上書きしないか、リレーが行のコンテキストで `resume`
  するか。
- **B-60** — 3 つの Outbox すべてに 4 つの端（マイグレーション・エンティティのフィールド・`capture()`・`resume(`）がなければならない。リレー
  1 つから `resume` を消して失敗することを確認した。
- 3 つのマイグレーションを実際の PostgreSQL 16 で、各サービスのパーティション作成（V1）の後に 2 回ずつ適用した — 親とすべての
  月別パーティションにカラムが付く。

⚠️ pay-service がすでに処理した決済を**リスナーからすぐに再送する**経路
（`publishIfOutboxEventProcessedForPayment`）は Outbox を経由しない — リスナーのトレースのまま出ていくので、
別途やることはない。

## 4f. 🐛 決済レスポンスから途切れていた — バッチリスナーにはコンシューマのトレースがない

§4e をデプロイしてみると「決済確認リクエスト → outbox relay payment-request」までしかつながらず、**`outbox relay
payment-response` と order-service 側が見えなかった。**

Spring Kafka のコンシューマのトレース（`ContainerProperties.setObservationEnabled(true)`）は**レコード単位のリスナーにだけ**
かかる。このリポジトリのコンテナファクトリ 13 個は**すべてバッチ**（`setBatchListener(true)`）なので、有効にしておいた設定がどこでも
動作していなかった。そのため pay-service が決済リクエストを受け取って決済レスポンスの Outbox 行を書くとき、現在のトレースがなく
（`capture()` → null）、決済レスポンスのリレーは新しいトレースで始まっていた。

**直したもの** — Saga 経路の 2 つのバッチリスナーが、レコードごとに**自分のヘッダ**でトレースを引き継いだ中で処理する。

- pay-service `PaymentRequestKafkaListener`（決済リクエスト）— この中で書く決済レスポンスの行が元のトレースを残す
- order-service `PaymentResponseKafkaListener`（決済レスポンス）— 注文確定が同じトレースに付く

レコードごとのヘッダは、Spring のデフォルトのバッチ変換器が `KafkaHeaders.BATCH_CONVERTED_HEADERS`（レコードごとに `Map`、値は
`byte[]`）で渡す。リスナーで任意のパラメータとして受け取り、`OutboxTraceContext.continueFrom(headers, name, work)` で
包む。ヘッダがなければそのまま処理する — トレースは付加情報だ。

**ガード**

- `OutboxTraceContextTest` — トレースが載ったレコードを**Spring の実際の `BatchMessagingMessageConverter`** に
  通してヘッダを受け取り、それで引き継いだ中で書いた次の Outbox 行に元の traceId が残るかを見る。
  pay-service で起きる順序そのままだ。
- `PaymentRequestTraceContinuationTest` — レコードごとにそのレコードのヘッダで引き継ぐか、ヘッダの一覧がなくても決済が
  処理されるか。
- B-60 に 2 つのリスナーの `continueFrom(` を加えた。

残りのリスナーは §4h で付けた。

## 4g. 🐛 §4f をデプロイしても変わらなかった — 本番のファクトリはレコードアダプタだった

§4f の後も、Zipkin の Webhook のトレースは `outbox relay payment-request → order send` で終わっていた。pay-service も、
`outbox relay payment-response` もなかった。後者が**まったく**なかったのは、決済レスポンスの行の `trace_context` が
空だったということだ（`resume(null, …)` はスパンなしでそのまま送る）— pay-service で引き継ぎが起きていなかった。

原因: 2 つのリスナーのコンテナファクトリに `setBatchToRecordAdapter(new DefaultBatchToRecordAdapter<>(…))` がある。
リスナーメソッドが `List<…>` を受け取っても、Spring はバッチを**レコード 1 つずつ**渡し、そのときメッセージヘッダはそのレコードの
ものだ — `BATCH_CONVERTED_HEADERS` はない。§4f はそのバッチヘッダだけを受け取っていたので、本番では常に null だった。
§4f のテストは変換器を直接呼んだりリスナーメソッドを直接呼んだりしていたので、この違いを見られなかった。

**直したもの** — リスナーは `@Headers Map` 全体を受け取り、`OutboxTraceContext.recordHeaders(headers, i)` で i 番目のレコードの
ヘッダを取り出す。バッチ変換ヘッダがあればその一覧の i 番目、なければメッセージヘッダそのもの（レコードアダプタ）だ。

**ガード** — 今回は**Spring が作ったリスナーアダプタ**を通す。

- `PaymentRequestListenerAdapterTraceTest`（pay）· `PaymentResponseListenerAdapterTraceTest`（order）— 本番の
  `KafkaConsumerConfig` のまま `@KafkaListener` を登録し（ブローカーなしで、自動起動だけオフ）、レジストリから取り出した
  アダプタに order-service/pay-service が送る形のレコード（Authorization は Spring のヘッダマッパー、traceparent は
  バイト）を入れる。処理する瞬間の現在のトレースが送った側の traceId かを見る。トレーサーは本番と同じ **W3C** 伝播だ
  （Brave のデフォルト B3 では traceparent を読めない）。修正前のコードで `(no trace)` で失敗することを確認した。
- B-60 — 2 つのリスナーが `recordHeaders(` を使っているか。

## 4h. 残りのリスナーも — すべての Kafka の消費が送った側のトレースに付く

Saga の 2 つのリスナー以外の消費は、すべて切り離された別のトレースだった。決済後の配送作成・通知、注文が送った在庫の差し引き・
カートを空にする処理の**受け取る側**、配送状態 → 注文状態、登録 → クーポン・チャット会員・注文顧客など。

**バッチリスナー 21 個** — cart（カートを空にする・ロールバック、クーポン使用）、catalog（在庫）、chat（登録）、coupon（決済レスポンス、
クーポンのロールバック）、delivery（決済 → 配送）、notification（通知 8 個・お知らせ・特集・メールコマンド）、order（登録、配送状態）。
ループ本体はそのままにして、`try {` の 1 行だけをスコープに変えた:

```java
for (int i = 0; i < messages.size(); i++) {
    try (OutboxTraceContext.TraceScope trace = outboxTraceContext.openFrom(
            OutboxTraceContext.recordHeaders(kafkaHeaders, i), "receive cart-empty")) {
        ... 以前の本体 — continue・チェック例外はそのまま ...
    } catch (Exception e) {
        throw new BatchListenerFailedException("…", e, i);
    }
}
```

`openFrom` は `continueFrom` のスコープ版だ（ラムダで包むと、`continue`・チェック例外がある本体が壊れる）。
リスナーは `@Headers Map<String, Object> kafkaHeaders` を受け取る — 普通のバッチファクトリなら Spring が
`BATCH_CONVERTED_HEADERS` でレコードごとのヘッダを渡し、`recordHeaders` が i 番目を取り出す。1 つのバッチに異なる
トレースのレコードが混ざっていても、レコードごとにそれぞれのトレースに付く。

⚠️ スパンは `try` が終わるときに閉じる。`catch` で出力するログ（例: 楽観ロック競合の警告）には traceId がない。

**レコード単位のリスナー 2 個** — chat のチャットメッセージ（`MessageConsumer`）はファクトリのオブザベーションがすでに有効なので
Spring が引き継ぐ。coupon の登録クーポン（`UserCreatedConsumer`、`setBatchListener(false)`）はそのファクトリだけ
オブザベーションが抜けていたので有効にした。

**ガード**

- `PaymentCompletedListenerAdapterTraceTest`（delivery）— 本番の `KafkaConsumerConfig` で登録した実際のアダプタに
  **異なるトレースのレコード 2 件を 1 つのバッチで**入れ、それぞれが自分のトレースの中で処理されるかを見る（普通のバッチファクトリの
  代表）。修正前のコードでは両方とも `(no trace)`。
- B-60b — すべての `@KafkaListener` ファイルがリスナーの数だけ `recordHeaders(` を使っているか（レコード単位の 2 個は一覧で
  例外）、すべてのコンテナファクトリが `setObservationEnabled(true)` か。新しいリスナーが漏らすと失敗する。

## 4i. ログアラート — "수동 확인 필요" と品切れを人に知らせる

お金・在庫がすでに動いたのに後続の処理が失敗すると、コードは `… — 수동 확인 필요` を error で残す（在庫・クーポン復元の
発行失敗、返金額・通貨の不一致、キャンセル済みの注文に届いた決済 = "수동 환불 필요"）。自動リトライがないので、**誰かが
ログを見なければ誰も気づかない。** メトリクスがない事象なので、Prometheus のアラートでは捕まえられない。

**Loki ruler** を有効にした（`observability/loki-config.yml` の `ruler:`）。ルールは `observability/loki-rules/
ecommerce-log-alerts.yml` — 1 分ごとに評価して**既存の Alertmanager** に送る。受け取るチャネルは Prometheus のアラートと同じだ
（severity で critical/warning の Webhook が分かれる、`ops/17`）。

| アラート | 条件（直近 5 分） | severity |
|---|---|---|
| `ManualActionRequired` | `{job=~"ecommerce.*"} \|~ "수동 (확인\|환불)이? 필요"` が 1 行でもあれば | critical |
| `ProductSoldOut` | catalog-service の `품절 발생`（docs/53） | warning |

ルールファイルは compose が `/etc/loki/rules/fake` に付ける — `auth_enabled: false` の Loki のテナント名が
`fake` なので、その下になければ読まれない。適用: `docker compose -f docker-compose-observability.yml up -d loki`。
確認: `ops/05` の "Loki 로그 경보 규칙"（2 つのルールと状態）、または Grafana → Alerting → Alert rules（データソースのルール）。

⚠️ **文言が契約だ。** "수동 확인 필요" を別の言葉に変えると、アラートが静かに来なくなる。**B-61** がコードの
log.error/warn のうち "수동 … 필요" の文言がすべてルールの正規表現に引っかかるか、ルールのマウント・ruler 設定があるかを見る。

## 4b. 🐛 最初のデプロイで明らかになったこと — すべての行が 1 行にくっついた

デプロイすると、コンソールにこう出力された:

```
… 're-run your application with 'debug' enabled.%n2026-09-24 12:53:07.336 [main] [,,] ERROR …
```

改行の場所に **`%n` が文字として**出て、次の行がそのままつながった。logback は複合変換子
`%mask(...)` の**すぐ後ろに付いた** `%` を変換語として読まない — `%mask(%msg)%n` は `hi%n` になる。
空のオプション `{}` で複合変換子を閉じれば（`%mask(%msg){}%n`）正常だ。facebook 側のパターン
（`%mask(%msg)%n%mask(%ex)`）も同じ形だ。

`CommonLogbackConfigTest` はマスキングされたかどうか（`contains`）しか見ておらず、**行が分かれるかは見ていなかった。** 2 つのイベントが
それぞれ 1 行かを見るテストを加え、B-57b が `%mask(...)` のすぐ後ろの `%` を防ぐ。

## 5. 残っているもの

- **Saga 以外のバッチリスナー**はまだレコードヘッダでトレースを引き継いでいない（§4f）— 配送作成・通知・カートを
  空にする処理・クーポンなど。コンシューマファクトリ 13 個がすべてバッチなので、`setObservationEnabled(true)` はどこでも効果がない。
- **可観測性スタックはノードの compose だ。** k3s Pod のログ（§4c）とトレース（§4d）は同じノードの compose スタックに行く。
  ノードが複数になると Pod は自分のノードの Zipkin に送るので、そのときはクラスタ内に収集器を置かなければならない。
- **ランタイムのログレベル変更**（actuator `loggers`）は開放しなかった。認証なしで開くと、facebook 側のように誰でもレベルを
  変えられる — 管理ポートの分離や認証と一緒に行う必要がある。
- **ログベースのアラート**（ERROR の急増など）とログ氾濫の防御（毎秒の上限）は今回の範囲外だ。
- **config-server** は common を使わないので、マスキング・JSON が適用されない。ユーザーデータを扱わない。
