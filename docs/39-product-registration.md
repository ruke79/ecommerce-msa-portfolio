# 39. 商品登録の手順 — トップ画面に商品が出るまで

**日本語** | [한국어](39-product-registration.ko.md)

> 背景: フロントエンドのホーム画面に商品が表示されていなかった。商品をプロダクションレベルで登録する
> 手順も必要だった。
>
> このドキュメントは **手順書であり、修正記録でもある。** §1 が「なぜ見えなかったか」、§2〜§4 が手順、
> §5 が今回直したものだ。

---

## 0. 一行要約

商品が見えない原因は 3 つの層に散らばっていて、3 つとも **静かに** 失敗していた —
画像のアップロードが失敗してもインポートジョブは成功で終わり、画像のない商品が一つでも
あるとホームが丸ごと白紙になり、その白紙と「商品がない」状態が画面上で区別できなかった。

---

## 1. なぜトップ画面に商品が出なかったか

### 1.1 画像アップロードの失敗が握りつぶされていた

`ProductSkuItemProcessor`・`ProductColorItemProcessor` は、CSV が指す画像を
S3 にアップロードする。ところが失敗すると、こう処理していた。

```java
} catch (Exception e) {
    System.err.println("Failed to upload image from path: " + path);
    e.printStackTrace();
    return null;          // ← そして filter(Objects::nonNull)
}
```

結果: **資格情報が破棄された状態でも、インポートジョブは COMPLETED で終わる。** 代わりに
`images` が空の SKU が DB に残る。ログを見ない限り誰も気づかない。

このリポジトリには実際に AWS キーを破棄した履歴がある（docs/30）。その後でインポートを回していれば、
まさにこの状態になる。

### 1.2 画像のない商品一つがホーム全体を白紙にしていた

フロントのあちこちが `product.skus[0].images[0]` をそのまま読んでいた。`skus` が空か
`images` が空だとその場で TypeError が起き、React は **レンダーツリーを丸ごと捨てる。**
一つの商品ではなく、トップ画面全体が消える。

`ProductCard` も同じ形だった — `product.skus[active].sizes.map(...)` と
`style.color` を前提にしていた。色の一つにサイズがなければ同じ症状になる。

### 1.3 「障害」と「データなし」が区別できなかった

ホームのローダーは失敗を `console.error` で握りつぶして null を返し、画面は `products &&` で
全体を隠していた。そのため API が落ちているときと商品が 0 件のときの画面が **まったく同じだった。**

### 1.4 （該当する場合）セクションが指すカテゴリがない

ホームの構成は今は `content.home_section` テーブルが決める（ドキュメントはなし、V10 マイグレーションを参照）。
セクションが指すカテゴリに商品がなければ、そのセクションは静かに外される — タイトルだけあって下が
空の行は、故障しているように見えるからだ。すべてがそうやって外れると、トップ画面が空に見える。

管理画面 `/admin/home` の一覧の **公開**・**カテゴリ** の 2 列がその答えをくれる。

---

## 2. 現在の状態を確認する — 順番に

```bash
# ① 商品が DB にあるか
kubectl -n postgres exec deploy/postgres -- \
  psql -U product -d productdb -c "select count(*) from product.product;"

# ② 画像が付いているか（0 でなければならない）
kubectl -n postgres exec deploy/postgres -- psql -U product -d productdb -c "
  select count(*) filter (where images is null or cardinality(images) = 0) as 이미지없음,
         count(*) as 전체
  from product.product_sku;"

# ③ ホームのセクションが何を指しているか
kubectl -n postgres exec deploy/postgres -- psql -U product -d productdb -c "
  select section_id, title, category_name, layout, published, starts_at, ends_at
  from content.home_section order by sort_order;"

# ④ サーバーが実際に何を返しているか
curl -s https://www.ysyproject.com/catalog-service/api/content/home | head -c 400
```

読み方:

| 結果 | 原因 | 次 |
|---|---|---|
| ① が 0 | 商品がまったくない | §3 または §4 |
| ② の `이미지없음`（画像なし）が 0 ではない | アップロードが失敗したまま登録された | §3.1 でキーから直して再登録 |
| ③ に published=false や期間外 | セクションがオフになっている | `/admin/home` でオンにする |
| ④ の `sections` が空 | 上の 3 つのどれか | 上へ |
| ④ は正常なのに画面が空 | フロントのキャッシュ／ビルド | フロントのイメージタグを確認 |

---

## 3. 手順 A — 商品を 1 つずつ登録（常時の運用経路）

### 3.1 まず画像ストレージを確認する

`/admin/product` を開くと、上部に **画像ストレージのバナー** が出る。正常なら何も
描画せず、書き込めなければバケット名と SDK のエラーメッセージをそのまま表示する。

このチェックは読み取りではなく、**小さなオブジェクトを実際に書いて消す**（`ImageService.probe`）。
読み取りだけを確認しても意味がない — 登録に必要なのは put 権限で、キーを破棄したり
バケットポリシーが変わったりすると、まさにその権限だけが消えるからだ。

バナーが出たらここで止める。`AWS_ACCESS_KEY`/`AWS_SECRET_KEY` を差し替えて
catalog-service を再起動してから進める。

### 3.2 登録

1. `/admin/category` でカテゴリ・サブカテゴリを先に作る。
   （商品登録フォームがその一覧から選ぶ形になっている。）
2. `/admin/product` で商品を登録する。画像 3 枚と色見本の画像が **必須** だ —
   サーバーはどちらかがなければ 500 を返す（`ProductController.addProduct` のコメントを参照）。
3. 同じ商品の別の色は、登録直後に表示されるバナーの「バリエーション追加」から続ける。

### 3.3 トップ画面に載せる

`/admin/home` でセクションを作る。

| 列 | 意味 |
|---|---|
| 基準 | カテゴリ全体か、特定のサブカテゴリか |
| 配置 | GRID = カルーセルの上に重なる上部グリッド、SWIPER = その下の横スクロール |
| 商品数 | 1〜50。トップ画面なので上限を設ける — うっかり大きな値を入れると、最初の読み込みが丸ごと遅くなる |
| 並び順 | 小さいほど上 |
| 開始／終了 | 空なら無制限。特集の期間 |
| 公開 | オフにしておけば、作業中のセクションは表示されない |

存在しないカテゴリを指すと **保存時点で 400 で弾く。** 保存を許すと症状が
「ホームにそのセクションだけ出ない」として現れ、どこが間違っているかは画面からはわからない。

---

## 4. 手順 B — CSV 一括登録（初期データの投入）

`/admin/import`。同梱の CSV 9 個を順番に入れ、画像 60 枚を一緒にアップロードする。

> **冪等ではない。** 2 回回すと商品が 2 セットできる。空の DB に一度だけ入れる用途だ。

動作:

1. 開始前にストレージへの書き込みを確認する。できなければ **開始せずに** 503 を返す —
   アップロード失敗は今はステップを失敗させるので、そのまま開始するとカテゴリ・商品まで入った後に
   SKU ステップで落ち、半分だけ埋まった DB が残る。
2. すぐに 202 で返る。ジョブはバックグラウンドで回る。
3. 進行中は 2 回目の開始を 409 で弾く。
4. 画面が 3 秒ごとに状態を問い合わせ、ステップごとの読み込み／書き込み／スキップ件数と失敗の原因を表示する。

コマンドラインから:

```bash
# 開始（管理者の Cookie が必要）
curl -X POST https://www.ysyproject.com/catalog-service/api/content/admin/product-import \
     -b "ACCESS=<관리자 토큰>"

# 進捗状況
curl -s https://www.ysyproject.com/catalog-service/api/content/admin/product-import \
     -b "ACCESS=<관리자 토큰>"
```

以前の経路 `POST /catalog-service/api/products/load` も同じ動作をする（既存の呼び出し元向け）。

---

## 5. 今回直したもの

| # | 直したもの | なぜ |
|---|---|---|
| 1 | インポートのプロセッサーがアップロード失敗を **投げる** | 握りつぶすとジョブは成功で終わり、画像のない商品が残る。「半分だけ入ったデータ」より「失敗したと言うジョブ」のほうがよい |
| 2 | `ImageService.probe()` — 書き込みチェック | 登録画面とインポートが開始前に呼ぶ。長いフォームを全部埋めた後で 500 を見ることを防ぐ |
| 3 | インポートを **非同期** に | 同期実行は画像 60 枚をアップロードする間レスポンスを塞ぎ、ゲートウェイのタイムアウトに先に引っかかっていた。504 を見た管理者がもう一度押して、商品を 2 セット作っていた |
| 4 | インポートの **重複実行をブロック**（409） | このジョブは冪等ではない |
| 5 | インポートの **進捗画面**（`/admin/import`） | 以前は画面がなく curl で叩く必要があり、結果を知る方法がなかった |
| 6 | `firstImageOf` / `isRenderable` で画像へのアクセスをラップした | 画像のない商品一つがホーム全体を白紙にしていたのを防ぐ |
| 7 | `ProductCard` の価格・色へのアクセスに null ガード | 色の一つにサイズがなくても同じ症状だった |
| 8 | ホームのローダーが **throw しない** | ローダーの例外はページ全体をエラー画面に変える。カタログが揺らいだからといってヘッダーまで消える理由はない |
| 9 | 「読み込めなかった」と「まだない」を **区別して** 文言に | 以前はどちらも同じく空の画面だった |

### 回帰テスト

- `ProductImportServiceTest` — ストレージが落ちていればジョブを起動しない（そしてロックが
  解放される）、進行中の 2 回目の開始は拒否、失敗の原因が状態に残る。
- `ProductSkuItemProcessorTest` — アップロード失敗が **伝播する**（以前は握りつぶしていた）。

どちらも、直したコードを元に戻すと失敗することを確認した。

---

## 6. 残っているもの

- **AWS キーの差し替えが前提条件だ。** docs/30 の破棄項目のうち、AWS だけ新しいキーの発行が残っている。
  それまでは §3・§4 のどちらも最後まで進めない — ただし今は、開始する前にその事実を
  教えてくれる。
- インポートのロックは **1 インスタンス基準** だ（メモリ）。単一ノードなのでここまでにしておく。レプリカを
  増やすなら Postgres のアドバイザリーロックに引き上げる必要がある。
- 商品の **編集・削除** 画面は、依然として一覧（`/admin/allproducts`）だけだ。

---

## 7. デプロイ後の確認で明らかになったこと — ゲートウェイの 1 秒タイムアウト

最初のデプロイの後、`GET /api/content/home` が 503「内部サーバーエラー」を返し続けた。確認してみると
**サービスはまったく問題なかった。**

```
# Pod を直接呼び出し
$ curl -sk https://localhost:8081/api/content/home
{"sections":[]}
200 0.317647s

# ゲートウェイ経由
503 1.358464s
```

ゲートウェイのログに原因があった。

```
Fallback triggered. Exception: Did not observe any item or terminal signal
within 1000ms in 'circuitBreaker'
```

**2 つのことが重なっていた。**

### 7.1 TimeLimiter が 1 秒だった

`resilience4j.timelimiter.configs.default` に `timeout-duration` がなかった。書かなければ
Resilience4j のデフォルト値 **1 秒** だ。JVM サービスの呼び出しに 1 秒はあまりにも短い — Eureka で
インスタンスを選び、TLS を張り、JPA のクエリまで回る時間だ。単一ノードで Pod が複数
競合すると、0.3 秒の呼び出しでも簡単に 1 秒を超える。

`timeout-duration: ${GATEWAY_TIMEOUT:10s}` と明示した。10 秒にした理由は、同じファイルの
`slow-call-duration-threshold: 10s` に合わせるためだ — それより短く切ると「遅い呼び出し」が
集計される前に切られてしまい、サーキットが遅延を永遠に学習できない。フロントの axios のデフォルト
タイムアウト（15 秒）よりは短くないと、ユーザーがこちら側のレスポンスを受け取れない。

**B-45** がこの値が消えることを防ぐ。消すと何のエラーもなく 1 秒に戻ってしまうからだ。

### 7.2 そしてその事実がレスポンスに現れなかった

`FallbackController` は `org.springframework.cloud.gateway.support.TimeoutException` だけを
見ていたが、実際に投げられるのは `java.util.concurrent.TimeoutException` だ。
**別々のクラスなので `instanceof` が一致せず**、すべてのタイムアウトがデフォルト分岐に落ちて
503「内部サーバーエラー」になっていた。504「タイムアウトしました」だったなら、最初の画面で原因が
見えていたはずだ。

ゲートウェイ設定の `record-exceptions` には `java.util.concurrent.TimeoutException` が
すでに書かれていた — 設定は知っていて、このコードだけが知らなかった。

今は両方を見る。`FallbackControllerTest` が 4 つの分岐（2 種類のタイムアウト · サーキット開放 · その他）を
固定する。

### 7.3 残った運用上の問題

デプロイ当時、catalog-service の Pod が **3 つ** あり、そのうち 2 つは再起動を繰り返していた
（`rollout status` が "1 out of 2 new replicas" で停止）。単一ノードで JVM が 3 つ
同時に起動すると CPU が枯渇し、それが 1 秒を超えた直接の原因だ。
`k8s-overlays/local/kustomization.yaml` は `catalog-service: 1` と書いているので、
2 で動いているなら、そのオーバーレイが適用されていないか、HPA が復活させたのだ。

### 7.4 そしてその下 — 本番プロファイルが丸ごと DEBUG だった

止まった Pod のログを読んでいて、原因がもう一つ出てきた。起動ログがこう終わる。

```
Started CatalogServiceApplication in 194.864 seconds (process running for 208.811)
```

**195 秒。** startupProbe の予算が 300 秒（5 秒 × 60）なので、余裕がほとんどない。ノードが混めば
そのまま超える — 実際に超えて、Pod が落ちては再起動するのを繰り返した。

ログ本文が理由を語っている。`SharedEntityManagerCreator - Creating new EntityManager`
のような DEBUG 行が数百ある。確認してみると **12 モジュールすべて** がこうだった。

```yaml
logging:
  level:
    org.springframework.jdbc: DEBUG
    org.springframework.orm.jpa: DEBUG
    org.hibernate.SQL: DEBUG        # ← 本番ですべての SQL が 1 行ずつ
```

コストは 2 か所にある。起動と処理が遅くなり（単一ノードではそれがそのままロールアウトの失敗だ）、
肝心の障害時にはログが DEBUG で埋もれて原因の行を見つけにくい。今回の診断で
ゲートウェイのログ 1 行を見つけるのに時間がかかった理由でもある。

デフォルトを本番向けの値に変え、調査するときだけ上げる:

```
LOG_LEVEL_APP=debug     自分たちのパッケージ
LOG_LEVEL_SQL=debug     JDBC・JPA・Hibernate SQL（普段は warn）
LOG_LEVEL_BATCH=debug   インポートジョブ
LOG_LEVEL_EUREKA=debug  ディスカバリー
```

**B-46** が DEBUG が再び埋め込まれることを防ぐ。

### 7.5 そして真犯人 — `kubectl apply -f k8s/`

デプロイログのど真ん中に、このエラーが混ざっていた。

```
error: resource mapping not found for name: "" namespace: "" from
"k8s/kustomization.yaml": no matches for kind "Kustomization"
```

`kubectl apply -f k8s/` の署名だ。そして **kubectl はそのエラーで止まらず、
残りをすべて適用する。** 同じ出力にこれらが続いて出ていた。

```
horizontalpodautoscaler.autoscaling/... unchanged   ← 10 個 (70-autoscaling.yaml)
poddisruptionbudget.policy/... configured
cronjob.batch/postgres-backup unchanged             ← 85-backup-cronjobs.yaml
namespace/postgres · deployment.apps/postgres       ← 90-dev-infra.yaml
namespace/kafka   · deployment.apps/kafka
namespace/redis   · namespace/mongodb · namespace/elasticsearch
```

1 行が **4 つのことを同時に** 行う:

1. HPA 10 個を復活させる → `replicas: 1` が 15 秒で 2 に戻る。
2. Postgres・Kafka・Redis・Mongo・Elasticsearch をクラスター内に立ち上げる → 単一ノードが枯渇する。
3. バックアップの CronJob と PVC を付ける。
4. base のマニフェストをオーバーレイなしで適用する → イメージが `REGISTRY_PLACEHOLDER/...:1.0.0`
   に戻る。**直前のデプロイが丸ごと無効になる。**

§7.3 の「Pod が 3 つ」、§7.1 の「1 秒を超えるレスポンス」、ジャーナルの 5 回連続の失敗、
Deployment が `1.0.0` に固定されていたこと — すべてこの 1 行の結果だ。

**なぜそれを打ったのか。** リポジトリがそうしろと書いていたからだ。`k8s/README.md` §3 が
個別適用の一覧の最後で、こう勧めていた。

```
# ファイル名が順番に並ぶので、下のようにディレクトリ全体を一度に適用しても構いません。
kubectl apply -f k8s/
```

kustomization.yaml とオーバーレイが導入される前は正しい話だった。その後は間違った話に
なったのに、ドキュメントだけがそのままだった。`ops/23` のステップ 3 も同じ形だった —「適用しないでください」の
すぐ次の行にそのコマンドがあった。

直したもの: `k8s/README` §3 に禁止ボックスを置いてディレクトリ適用の推奨を削除、`ops/23` から
コマンド行を除去、そして `ops/28` が prod 以外の環境に HPA が残っていればデプロイ時に警告する。
警告は消さずに **見えるようにするだけ** だ — デプロイスクリプトが他人のリソースを静かに削除するのは、
それ自体が驚くべき動作だ。

> **教訓。** 今回の作業で最も時間を食ったのはコードの欠陥ではなく、**古い案内文** だった。
> リポジトリが「しないでください」と書いたすぐ次の行にそのコマンドを書いておくと、コピーされるのはコマンドのほうだ。

### 7.6 訂正 — `kubectl apply -f k8s/` を打ったのは人ではなくスクリプトだった

§7.5 はそのコマンドを運用者が打ったと書いた。**間違いだった。** 運用者が実行したのは 4 行だけで
（`git pull` → `VER=` → `ops/19` → `ops/28`）、それなのに `ops/19` の「完了」の直後にそのエラーと
apply の出力が出ていた。決定的な手がかりは、出力が **案内文のど真ん中に挟まっていた** ことだ:

```
  ⚠️ k8s/ 에 kustomization.yaml 이 생겨서 namespace/ecommerce unchanged
configmap/ecommerce-config configured
...
service/elasticsearch unchanged 는 더 이상 쓸 수 없다.
```

`ops/19-build-images.sh` の案内文は、`$TAG` を置換するために **クォートなしで** `cat <<EOF` で
開かれていた。クォートなしの heredoc の中では、バッククォートはコマンド置換だ。その中のこの 1 行が

```
  ⚠️ k8s/ 에 kustomization.yaml 이 생겨서 `kubectl apply -f k8s/` 는 더 이상 쓸 수 없다.
```

**「使えない」と言いながら、ビルドのたびにまさにそのコマンドを実行していた。** stdout は
案内文の位置に置換されて入り（だから文が真っ二つになった）、stderr（`error: resource
mapping not found`）だけが先にターミナルに出力された。

つまり §7.5 の 4 つの結果 — HPA の復活、dev-infra の再適用、CronJob、そして base マニフェストによって
**イメージが 1.0.0 に戻ること** — はビルドのたびに起きていた。デプロイの直前に。
`ops/28` が毎回「현재 배포 버전: 1.0.0」（現在のデプロイバージョン）と読んでいた理由であり、ジャーナルの fail 5 回の根だ。

シェル履歴にそのコマンドが 1 行あったのは偶然で、それを根拠に人を疑ったのは
誤りだった。

直したもの: バッククォートを `\`` でエスケープし（ops/22 はすでにそうしていた）、**B-47** が
`ops/*.sh` のクォートなしの heredoc 内でエスケープされていないバッククォートを捕まえる。元に戻すと
失敗することを確認した。

> 教訓をもう一つ。§7.5 の教訓（「古い案内文が最も高くついた」）は正しかったが、半分だけ正しかった。
> 案内文は読まれるだけではない — シェルの中では **実行されることもある。**

### 7.7 スモークゲートは **最初から通過できない状態** だった

すべてのロールアウトが成功したデプロイが、スモークで失敗した。出力に原因がそのまま出ていた。

```
▶ 스모크 테스트  (http://localhost:18000)
  ❌ gateway health 가 UP 이 아니다: Bad Request
This combination of host and port requires TLS.
```

`ops/28` の `resolve_gateway` は、Ingress に host がなければ（このリポジトリのデフォルトだ）
ゲートウェイへ port-forward し、対象を `http://localhost:18000` にしていた。ところが
**ゲートウェイは自分で TLS を終端する**（`server.ssl`、ポート 8000）。Tomcat は平文のリクエストに
400 を返すので、スモークのすべての項目が失敗した。

項目 1 の `000` 7 件も根は同じだ。`ops/06` は `$GATEWAY` が localhost なら「ローカル
実行」とみなして個別サービスのポート（8080・8081…）を直接叩くが、ここで localhost なのは
**ポートフォワードのため** で、それらのポートはクラスターの外には出てこない。偽の失敗 7 件が
本当の問題を覆い隠した。

つまりスモークゲートは **何をデプロイしても失敗判定を下していた。** ジャーナルに `ok` が 1 行もなく
fail だけが積み上がったこと、そしてそのたびに自動復帰が `1.0.0` に戻したことは、すべてここから来ている。
§7.5 の heredoc バグがデプロイを **元に戻した** のだとすれば、こちらはデプロイが **成功として記録されるのを
阻んだ。** 2 つは別々のバグで、両方があってはじめてこの 7 時間が説明できる。

直したもの:

- port-forward の対象を `https://localhost:18000` に、自己署名なので `INSECURE=1`。
- `SKIP_DIRECT=1` を明示して個別サービスの直接チェックをオフにする — k8s のプローブがすでに同じことをしている。
- **B-48**: ゲートウェイが TLS を終端しているのにスモークの対象が `http://` なら失敗させる。
  スキームがずれていてもエラーメッセージは「ゲートウェイが UP ではない」と出るので、**症状が原因を
  隠す。** 元に戻すと失敗することを確認した。
