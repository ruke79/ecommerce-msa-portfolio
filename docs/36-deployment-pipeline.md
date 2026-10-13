# 36. デプロイパイプライン — テストしたものとデプロイするものを同じ物に

**日本語** | [한국어](36-deployment-pipeline.ko.md)

> 🕰️ **状態（2026-09-27）— 一部古い。** B-54 ガード（ROLLBACK-UNSAFE）と `deploy-oci.yml` が入り、§5.2 と「デプロイステージがない」は解消されました。2026-10-05 からデプロイは手動実行だけで始まります（実行 = 承認、§6・B-75）。

> 根拠文献: Jez Humble · David Farley, *Continuous Delivery: Reliable Software Releases
> through Build, Test, and Deployment Automation* (Addison-Wesley, 2010).
> 引用は原文のまま載せ、出典の章を明記した。

このドキュメントは **何がどう壊れていて、何を直し、何がまだできていないのか** を書く。
「CI/CD を導入した」のような文は書かない — このリポジトリにはすでに CI があり、
問題は CI がないことではなく、**CI が検証した物がデプロイされる物ではない** ことだった。

---

## 1. 何が壊れていたのか

### 1.1 バイナリを 2 回作っていた

本が第 5 章で最も強く念を押しているルール:

> "you should only build your binaries once, during the commit stage of the build"

改修前のフローは、まさにその正反対だった。

```
GitHub Actions             開発者のノートパソコン
────────────              ──────────────
./gradlew build     →     (数時間後)
  jar 生成                ops/19-build-images.sh
  テスト通過 ✅            → ./gradlew bootJar   ← **jar をもう一度作る**
  jar を捨てる             → docker build
                          → ghcr.io/.../user-service:1.0.0
                                    ↓
                              これがデプロイされる
```

CI が通した jar は、ランナーと一緒に消えた。実際に本番に載った jar は、**誰も
テストしていない 2 回目のビルドの成果物** だった。同じソースでも、2 つのビルドが同じバイトを
出す保証はない — ベースイメージがその間に更新され、依存関係の解決時点が異なり、
ビルド環境が異なる。そのため「受け入れテスト通過」が、デプロイ物について保証するものは何もなかった。

### 1.2 タグが人の選んだ数字だった

`TAG="${TAG:-1.0.0}"`。ここで 2 つのことが同時に壊れる。

**追跡不能。** クラスターで動いている `:1.0.0` がどのコミットなのかを知る方法がなかった。git のタグは
0 個で、`build.gradle` のバージョンはすべてのサービスで `0.0.1-SNAPSHOT` に固定されていた。
「このバグは今のデプロイ物に入っているか？」に答えられないということだ。

**タグの再利用。** 直して同じタグで再び push すると、ノードは **キャッシュされた古いイメージを使い続ける。**
マニフェストに `imagePullPolicy` がなく、タグが `latest` ではないので、デフォルト値が `IfNotPresent`
になるからだ。kubelet はこう出力するだけだ:

```
Container image "...:1.0.0" already present on machine
```

今回の作業の実デプロイで、まさにこれを経験した — 「イメージを直して push したのに、なぜそのままなんだ」。

### 1.3 デプロイ手順が人の記憶だった

デプロイはこんな案内文だった:

```
kustomize edit set image REGISTRY_PLACEHOLDER/... = ghcr.io/...:1.2.3
kubectl apply -k k8s-overlays/prod
kubectl apply -f k8s/70-autoscaling.yaml
⚠️ user-service を gateway-server より先に上げること
```

問題がいくつもある。

- `kustomize edit` は **コミットされたファイルを書き換える。** デプロイのたびに作業ツリーが汚れ、
  「今デプロイされているのは何か」がコミットされていないローカルの変更の中に隠れる。
- 順序の注意事項がコメントになっている。コメントは午前 3 時には守られない。
- デプロイ後の検証がない。Pod は Running なのにサイトが 522 を返す状態を実際に経験した
  （docs/33 §2）。
- **元に戻す方法がまったく書かれていなかった。**

### 1.4 元に戻すことに手順がなかった

本の第 10 章:

> "It is essential to be able to roll back a deployment in case it goes wrong.
> Debugging problems in a running production environment is almost certain to result
> in late nights, mistakes with unfortunate consequences, and angry users."

そして:

> "On no account should you have a different process for backing out than you do
> for deploying."

別立てのロールバック手順は、定義上ほとんど実行されない。そのため、本当に必要な日 — 障害の真っ只中、
明け方 — に初めて実行され、その日に初めてバグが露見する。

---

## 2. パイプラインの形

`.github/workflows/build.yml`

```
       ┌─ 統合 (Testcontainers) ─┐
コミット ─┤                          ├── リリース候補の昇格 ──▶ ops/28-deploy.sh
       └─ 受け入れ (Saga E2E) ─────┘        (記録)              (人が実行)
```

| ステージ | やること | 失敗すると |
| --- | --- | --- |
| **コミット** | コンパイル · 単体テスト（Java + フロント）· **バイナリを 1 回だけ生成** · イメージを `:<コミットSHA>` で GHCR に発行 | 何も発行されない。そのコミットはデプロイ候補ではない |
| **統合** | 実際の PostgreSQL/Redis/Kafka/Mongo/ES を立ち上げ、8 つのサービスの起動・Saga 再配信の冪等性を検証 | 候補の昇格が止まる |
| **受け入れ** | 注文→決済→在庫の Saga 全区間を **発行されたそのイメージで** 実行 | 候補の昇格が止まる |
| **昇格** | リビジョン・イメージ一覧・通過したステージをマニフェストとして残し、デプロイコマンドをサマリーに出力 | — |

本の基準（第 5 章）では、コミットステージは **理想的には 5 分、超えても 10 分** である。ここでは
Java のビルド + フロントエンドのテスト + 13 個のイメージのマルチアーキテクチャビルドが入るので、それより長い。
短縮の余地は §5 に書いた。

### なぜデプロイステージがパイプラインにないのか

デプロイ先（OCI Ampere）は **インバウンドが制限された管理経路（SSH）1 つだけ** 開いている
（docs/31）。GitHub ホステッドランナーから kube-apiserver に到達する方法がまったくない。

これは本の処方と食い違わない。本が求めているのは「すべての環境に **同じ手順** で
デプロイせよ」であって、「CI サーバーがボタンを押せ」ではない。変えるには §6 を参照。

---

## 3. 直したもの

### 3.1 バイナリは 1 回だけ — そしてその事実をツールが強制する

コミットステージが jar とフロントエンドのバンドルを作り、**同じジョブの中で** それをイメージに
包んでレジストリに上げる。レジストリが、本の言う「成果物リポジトリ」だ。

受け入れステージはソースから再ビルドせず、**そのイメージを受け取って** 立ち上げる。

```java
// e2e-tests/.../SagaEndToEndTest.java
private static GenericContainer<?> newContainer(String module) {
    if (!IMAGE_PREFIX.isBlank()) {
        return new GenericContainer<>(
                DockerImageName.parse(IMAGE_PREFIX + module + ":" + IMAGE_TAG));
    }
    return new GenericContainer<>(serviceImage(module));   // ローカル開発用のフォールバック
}
```

口だけでそうなっているのではなく、**ビルドグラフから消える。** `E2E_IMAGE_PREFIX` があれば、
`e2e-tests/build.gradle` は `bootJar` への依存を張らない。実際に確認した:

```
$ ./gradlew :e2e-tests:test --dry-run | grep -c bootJar
3
$ E2E_IMAGE_PREFIX=ghcr.io/x/y/ ./gradlew :e2e-tests:test --dry-run | grep -c bootJar
0
```

### 3.2 タグはリビジョンである

`ops/19-build-images.sh` のデフォルトのタグが **現在のコミットの SHA 12 文字** になった。人が選んだ
数字ではない。決して再利用されないので §1.2 のキャッシュ事故が根本からなくなり、
イメージからソースへさかのぼれる。

作業ツリーが汚れていれば警告する — その SHA をチェックアウトしても、そのイメージを再現できない
からだ。リビジョンでさかのぼれない成果物は、リリース候補ではない。

### 3.3 デプロイはスクリプト 1 つ、環境は引数

`ops/28-deploy.sh` — 本の第 10 章:

> "It is essential to use the same process to deploy to every environment"

```bash
./ops/28-deploy.sh dev  a1b2c3d4e5f6
./ops/28-deploy.sh prod a1b2c3d4e5f6      # 同じコード経路。オーバーレイだけが違う
./ops/28-deploy.sh prod --rollback        # 元に戻すのも同じコード経路
./ops/28-deploy.sh prod <バージョン> --dry-run
./ops/28-deploy.sh --report               # デプロイ履歴 · サイクルタイム
```

やることを順番に:

**① オーバーレイをレンダリングしてイメージ一覧を読む。** サービスの一覧をスクリプトに埋め込まない。
そうすれば「オーバーレイにはあるのにデプロイスクリプトは知らないサービス」が生まれない。

この部分で **実際にバグを 1 つ捕まえた。** 最初は `^\s+image:` だけで探したところ、12 個であるべき
一覧が 11 個だった — `frontend` だけはコンテナのマップの最初のキーが `image` なので、
`      - image: ...` の形でシリアライズされ、漏れていた。そのままにしていたら、フロントエンドだけが古いバージョンのまま
残り、「デプロイしたのに画面がそのまま」になっていただろう。4 つのオーバーレイ（dev・prod・external・oci）
すべてで 12 個が出ることを、実際の kustomize で確認した。

**② レジストリにそのバージョンが実際にあるか確認する。** クラスターに触れる **前に**。
存在しないタグで apply すると ImagePullBackOff で止まるが、そのときにはすでに古い Pod が終了し
始めた後だ — つまり障害が起きてから知ることになる。docker・skopeo・crane のうち、あるものを使う。

**③ タグを一時的なオーバーレイに注入する。** コミットされたファイルには触れない。`.deploy/<環境>/`
（gitignore）に、`resources: [../../k8s-overlays/<環境>]` + `images:` だけがある薄い
オーバーレイを毎回新しく書く。

**④ 2 段階で適用する。** user-service を gateway-server より先に上げる — ゲートウェイは
uid クレームのないトークンを拒否するので、ゲートウェイが先に新しいバージョンになると、まだ古い user-service
が発行したトークンがすべて 401 になる（docs/27）。第 1 段階ではゲートウェイだけを **現在のバージョンに固定** して
おいて残りを上げ、第 2 段階でゲートウェイを上げる。実際の kustomize の出力で確認:

```
gateway-server:1.0.0            ← 第 1 段階で固定
user-service:abc123def456       ← 残りは新しいバージョン
catalog-service:abc123def456
...
```

**⑤ スモークがゲートだ。** `ops/06-smoke-test.sh` を回す。デプロイは「Pod が Running」ではなく、
「リクエストが正常に応答する」で終わる。対象アドレスは `GATEWAY` → Ingress ホスト →
port-forward の順に決める。**対象が見つからなかったからといって、ゲートを黙って飛ばさない。**

**⑥ 失敗したら直前のバージョンに自動で戻る。** — 次の節。

### 3.4 元に戻すための別経路はない

失敗時にコードがやることは、これですべてだ:

```bash
CURRENT_VERSION=""              # 段階に分けず一度に
deploy_version "$ROLLBACK_TO"   # ← 普段使うその関数を、直前のバージョンで
```

本の処方（第 10 章、"Rolling Back by Redeploying the Previous Good Version"）:

> "If you have an automated process for deploying your application, the simplest way
> to get back to a good state is to redeploy the previous good version from scratch."
>
> "It is the same process you have tested (hopefully) hundreds of times before.
> Rollbacks are performed much less frequently, and therefore are more likely to
> contain bugs."

この経路を別にテストする必要はない。**毎回のデプロイそのものが、この経路のテストだ。**

復帰は段階に分けず、全体を一度に戻す。2 段階の順序は前に進むときにだけ
成立するルールなので、後ろに戻るときに同じ順序を使うと、かえって「古い user-service + 新しい gateway」の
組み合わせを作ってしまう。逆の順序を新しく作れば、それこそが別の手順になる。直前にうまく動いていた
構成全体をそのまま再デプロイすれば、組み合わせの数は増えない。

戻す対象は、デプロイ履歴（`~/.ecommerce-msa/deploy-journal.tsv`）の **最後にスモークまで
通過したバージョン** だ。デプロイすると直前のバージョンはクラスターのどこにも残らないので、ファイルが
必要になる。`kubectl rollout undo` を使わない理由: undo は Pod の spec しか戻さないので、
オーバーレイがその間に変わっていたら、次の `apply` で黙ってまた前に進んでしまう。

`NO_ROLLBACK=1` で自動復帰を切れる — 原因を生きた状態で見る必要があるときに。

### 3.5 サイクルタイムを測る

本の第 1 章が唯一のグローバルな指標として挙げているもの:

> the time from deciding a change should be made to having it available to users

ジャーナルにデプロイ時刻と **コミット時刻** を一緒に書いておき、差を取る。

```
$ ./ops/28-deploy.sh --report
  배포시각             환경      버전           결과      사이클 타임(커밋→배포)
  2026-08-18T09:12:00Z  prod      a1b2c3d4e5f6   ok        31분
  2026-08-19T02:30:00Z  prod      b2c3d4e5f6a7   fail      25분
  2026-08-19T02:41:00Z  prod      a1b2c3d4e5f6   rollback  18시간 0분
  2026-08-19T11:00:00Z  prod      c3d4e5f6a7b8   ok        2일 2시간
```

測定しないものは改善されない。そしてこの数字が長いとき、ボトルネックはたいていパイプラインではなく
**「デプロイを押すまで」** にある — それを見えるようにすることが目的だ。

### 3.6 初回実行で明らかになったこと — GHCR のパッケージがリポジトリに紐づいていない

パイプラインを初めて回したとき、単体テストはすべて通過し、QEMU・buildx・GHCR へのログインまで
成功したのに、push で落ちた:

```
#17 [auth] ruke79/ecommerce-msa/eureka-server:pull,push token for ghcr.io
#17 DONE 0.0s
#16 ERROR: failed to push ghcr.io/.../eureka-server:09094ec7fc5e:
    denied: permission_denied: read_package
```

**ログインは成功し、トークンも発行された。** だから資格情報の問題ではない。GHCR のパッケージは
作った主体の所有であり、人が PAT で先に push しておいたパッケージはリポジトリに **紐づいて
いない。** そのため Actions の `GITHUB_TOKEN` は `packages: write` を持っていても、そのパッケージに
書き込めない。ワークフローが作ったパッケージは自動で紐づくが、このリポジトリのイメージは
docs/33 の手順どおり、手作業で先に上げられていた。

この失敗には興味深い点がある。`ops/19` の失敗分類器がこれを捕まえたのだが — **誤った
分類を出した。**「PAT は classic である必要があり、write:packages スコープが必要です」と
案内したが、それはここでは何の役にも立たない。スクリプトが避けようとしていたまさにそのこと（無駄骨を
折らせる案内）を、自ら犯したのである。`permission_denied` を別の分岐に切り出し、
紐づけの問題だと伝えるように直した。

**解決 ①（推奨、1 回だけ）** — パッケージごとにリポジトリを紐づける:

```
github.com/users/<OWNER>/packages/container/ecommerce-msa%2F<サービス>/settings
  → Manage Actions access → Add Repository → ecommerce-msa → Role: Write
```

13 個（サービス 12 + frontend）について 1 回ずつやれば終わりで、以後は寿命の長いシークレットが
不要になる。

**解決 ②（一時的）** — リポジトリのシークレット `GHCR_PAT`（classic、`write:packages`）を入れる。
ワークフローが `secrets.GHCR_PAT || secrets.GITHUB_TOKEN` になっているので、あればそれを
使い、なければ `GITHUB_TOKEN` に落ちる。ただしこの方式は **失効・再発行の対象となるシークレットが
1 つ増えること** なので（docs/30 の教訓）、① に移るほうがよい。

### 3.7 副次的に直したもの

`gradlew` の実行権限が git のインデックスになかった（`100644`）。ワークフローが `./gradlew` を直接
呼ぶので、このままではコミットステージが `Permission denied` で落ちる。`100755` に直した。
（`ops/19` が `sh gradlew` を先に試すフォールバックを持っているのが、この問題の痕跡だ。）

---

## 4. 検証したものとしなかったもの

嘘をつかないために、分けて書く。

**実際に確認した**

| 対象 | 方法 | 結果 |
| --- | --- | --- |
| イメージ一覧の抽出 | 実際の `kustomize build`（v5.4.3）、4 つのオーバーレイ | dev・prod・external・oci すべて 12 個 |
| タグの注入 + ゲートウェイの固定 | 生成した一時オーバーレイを実際にレンダリング | gateway だけ古いタグ、残りは新しいタグ |
| 受け入れステージの「再ビルドしない」 | `--dry-run` のタスクグラフを比較 | bootJar 3 個 → 0 個 |
| e2e テストの変更 | `./gradlew :e2e-tests:compileTestJava` | 通過 |
| ワークフローの YAML | 重複キーを拒否する厳格なローダーでパース + ジョブグラフを確認 | 5 つのジョブ、依存関係は正常 |
| `--report` · 引数エラーの経路 | 偽のジャーナルで実行 | サイクルタイムの計算・エラーメッセージは正常 |

**実際の実行で確認されたもの**（run 32284611563 / 32286228445）

| 段階 | 結果 | コールドキャッシュ | ウォームキャッシュ |
| --- | --- | --- | --- |
| Java 単体テスト · bootJar | ✅ | 6 分 02 秒 | **2 分 23 秒** |
| フロントエンド単体テスト | ✅ | 1 分 21 秒 | 1 分 02 秒 |
| QEMU · buildx · GHCR ログイン | ✅ | 13 秒 | 22 秒 |
| リリース候補イメージの発行 | ❌ | 15 秒 | 21 秒 — §3.6 |
| **発行直前までの累積** | | 7 分 36 秒 | **4 分 30 秒** |

マルチアーキテクチャのイメージビルド自体は正常に動作した — amd64/arm64 のマニフェストを作り、
マニフェストリストまで出力した後で **push 権限** で拒否された。つまりビルドパイプラインではなく、
レジストリのアクセス設定の問題だ。

2 回目の実行で、失敗メッセージが §3.6 の直した分類器のものに変わったことも確認した — 今は
「パッケージがリポジトリに紐づいていない」と設定のパスを案内する。そして 2 回目の実行が
同じ地点で落ちたという事実そのものが、**`GHCR_PAT` シークレットが設定されていない** ことを意味する
（あればそれを使っていたはずだ）。つまり §3.6 の解決 ① か ② を人がやる必要がある。

**まだ確認できていない**

- **受け入れステージが、発行されたイメージで Saga を完走するか。** イメージがまだレジストリに
  上がっていないので、その後のステージはすべて skip された。§3.6 を解決した後に確認する必要がある。
- **実際のクラスターへのデプロイ。** 当時の検証環境には kubectl もクラスターもなかった。`kubectl apply -k`、
  `rollout status`、port-forward のフォールバック、自動復帰は **コード経路でしか** 検証されていない。
- 最初のデプロイは、必ず `--dry-run` で何が変わるかを先に見ること。

マニフェストは実際に使うツールで検証すべきだということは、このリポジトリですでに高くついて学んだ
（docs/33 §13 — PyYAML は重複キーを許すが、kustomize は拒否する）。そのため上の
表の検証は、すべて実際のツールで行った。

---

## 5. 残っているもの — 本が処方しているがまだやっていないこと

### 5.1 無停止デプロイとカナリア（本の第 10 章）

今はローリングアップデートだ。ブルーグリーンもカナリアもない。ロールバックにはダウンタイムがある。

本が指摘するとおりの制約が、このプロジェクトにもある:

> "It is usually not possible to [use blue-green with databases]"

マネージド PostgreSQL 1 台（Neon 無料枠）を共有しているので、DB を 2 系統に分けられない。
カナリアは、サービス数（12 個）とノード数（1 台）を考えると今の規模では過剰だ。
**規模が大きくなる前に導入する理由はないが、必要になる時点を逃すと手遅れになる。**

### 5.2 DB マイグレーションとアプリケーションのデプロイの分離（本の第 12 章）

今は Flyway がアプリケーションの起動時にマイグレーションを回す。つまりデプロイとスキーマ変更が
一体なので、**アプリケーションだけを戻すことはできない。**

本の処方:

> "you must ensure it works with both the new version and the current version"
>
> "most changes should be additive, adding new tables or columns to our database,
> but not changing existing structures, where possible"

このリポジトリのマイグレーションは、おおむね additive だ（V6 unique slug、V7 unique sku、V8 status）。
しかしそれを **強制する仕組みがない。** 破壊的なマイグレーションが 1 つ入った瞬間、
`--rollback` が黙って危険になる。次の作業として適切なもの:

- マイグレーションのレビュールール（DROP/RENAME/NOT NULL の追加を禁止）を CI のチェックに
- 元に戻せないマイグレーションを含むバージョンはジャーナルに印を付け、`--rollback` が警告

### 5.3 ゲートウェイのトークン互換性

`ops/28-deploy.sh` が 2 段階でデプロイする理由（§3.3 ④）は、ゲートウェイが古いトークンを拒否する
からだ。順序で解決するのは **対症療法** である。根本的な解決策は互換性だ — ゲートウェイが
2 つの形のトークンをどちらも受け付ければ順序そのものがなくなり、ロールバックの組み合わせ問題もなくなる。
これは §5.2 と同じ原理（前方・後方互換）を API に適用したものだ。

### 5.4 コミットステージの時間 — 心配していたよりもよい

パイプラインを組むときは、「13 個のイメージ × 2 アーキテクチャなら本の基準（理想 5 分、最大 10 分）を
超えるだろう」と見ていた。実測は違った（§4）: **ウォームキャッシュで発行直前まで 4 分 30 秒**、
Java のビルドが 2 分 23 秒だ。コールドキャッシュの 6 分は最初の 1 回の実行だけだった。

つまり今急いで手を入れる場所ではない。発行段階まで含めた本当の合計時間は §3.6 が解決して
初めて分かり、そのとき 10 分を超えていたら、順番に:

1. **`linux/amd64` を外す。** デプロイ先（OCI Ampere）も開発機（Apple Silicon）も arm64 だ。
   amd64 のイメージは今誰も使っていない。ビルドするイメージが半分になる。
2. イメージの発行を別のジョブに切り出し、jar をアーティファクトとして渡す。単体テストのフィードバックは
   速くなるが、1GB 近い転送コストが生じる — だから 1 番を先にやる。

**まだやっていない。** 測定なしに直すと見当違いの場所を直すことになり、今の測定値は
「直す必要はない」と言っている。

### 5.5 自動デプロイ（continuous deployment）

本は、受け入れテストが十分なときに最終段階まで自動化する選択肢を示しつつ、条件を
はっきりさせている:

> "In order for this not to cause breakages, your automated tests have to be
> fantastic—there should be automated unit tests, component tests, and acceptance
> tests (functional and nonfunctional) covering your entire application."

今はその条件を満たしていない。Saga の 1 経路とサービスの起動はカバーしているが、決済・ソーシャル
ログイン・管理者のフローの受け入れテストがない（docs/34 は **人が** 回るチェックリストだ）。
自動デプロイはその次だ。

---

## 6. CD — 承認後の自動デプロイ（OCI Ampere、セルフホステッドランナー）

これまで阻んでいたのはツールではなく **ネットワークポリシー** だった。OCI インスタンスは、インバウンドが制限された管理経路（SSH）1 つだけだ。
選択肢は 3 つあり、3 つとも `ops/28-deploy.sh` をそのまま使う — 変わるのは「誰がそのスクリプトを実行するか」だけだ。

| 方法 | 何が変わるか | 代償 |
| --- | --- | --- |
| ✅ **セルフホステッドランナー** を OCI インスタンスにインストール | ランナーがアウトバウンドで GitHub に接続する。インバウンドポートの追加なし | ランナーがクラスターの資格情報を持つ。リポジトリが公開ならフォークの PR がランナー上でコードを実行できるため、**必ず非公開であるか承認ゲートが必要** |
| Tailscale/WireGuard でランナーをプライベートネットワークに参加させる | Actions のジョブが kube-apiserver に到達する | ネットワークの構成要素が 1 つ増え、その資格情報が新たな管理対象になる |
| GitOps（Argo CD/Flux）をクラスター内に | クラスターが git を **プル** する。インバウンド 0 を維持 | デプロイの主体がスクリプトからコントローラーに変わる。スモークゲートと自動復帰をコントローラー側に作り直す必要がある |

1 つ目を選んだ（リポジトリは非公開だ）。フロー:

```
push(デフォルトブランチ) ─> deployment pipeline(build.yml) グリーン(リリース候補)
運用者が Run workflow(バージョン) ─> deploy (OCI Ampere)(deploy-oci.yml)
                                    ├─ gate   : 対象コミット・そのパイプラインがグリーンか・フロントの設定 Variables
                                    └─ deploy : ランナーが ops/28-deploy.sh local <SHA12>
```

- イメージは作り直さない — コミットステージが発行した amd64・arm64 のイメージ（タグ = コミット SHA 12 桁）をそのまま使う。
- `ops/28` がやっていたことはすべてそのままだ — イメージの存在確認、元に戻せないマイグレーションのチェック、スモークゲート、失敗時の直前バージョンへの自動復帰。
- ランナーは運用者と **同じユーザー**（ubuntu）で動く — `~/.kube/config`・`~/.docker/config.json`・デプロイ履歴（`~/.ecommerce-msa/deploy-journal.tsv`）が
  手作業でデプロイするときと同じだ。そのため `--rollback`・`--report` も 1 つの履歴を見る。
- gitignore されたローカルオーバーレイ（`k8s-overlays/local`）は、運用者の作業コピー（`DEPLOY_REPO_DIR`）にあるものをリンクする。シークレットには触れない。
- 実行は **手動（Actions → deploy (OCI Ampere) → Run workflow）だけ** だ — デフォルトブランチでのみ、そして **そのコミットのパイプラインがグリーンのときだけ** 可能だ。
- 🔒 **実行 = 承認。** パイプラインがグリーンになっても自動ではデプロイしない（ガード B-75）。以前は自動実行（workflow_run）+ Environment
  `production` の Required reviewers を承認ゲートと書いていたが、非公開リポジトリでは Free・Pro・Team プランで Required reviewers が
  使えず、ゲートが無かった（2026-10-05、承認なしで待機していたデプロイを取り消し、手動実行だけを残した）。

### 6.1 セットアップ（1 回）

1. **GHCR パッケージの権限** — パッケージごとに Settings → Manage Actions access → このリポジトリに **Write**。（PAT で先に上げたパッケージはリポジトリに
   紐づいていないため、コミットステージのイメージ発行が `permission_denied: read_package` で失敗する。）
2. **リポジトリの Variables**（Settings → Secrets and variables → Actions → Variables）— `ops/frontend.env` の `REACT_APP_*` 7 個（公開値）。
   空だと gate がデプロイを止める（CI が作るフロントのイメージが壊れる）。必要に応じて `DEPLOY_REPO_DIR`（デフォルト `/home/ubuntu/ecommerce-msa`）・
   `DEPLOY_ENV`（デフォルト `local`）。
3. **Environment** — 別途作るものはない。`production` は初回の実行が作り、デプロイ履歴用である。非公開リポジトリではプランによって
   Settings に Environments メニューが表示されず、Required reviewers は Enterprise でしか使えない — 承認は手動実行で代える。
4. **ランナー** — インスタンス上で（手作業でデプロイしていたその作業コピーで）:
   ```bash
   ./ops/40-deploy-runner.sh check      # arm64・kubectl・docker・オーバーレイを点検
   ./ops/40-deploy-runner.sh install    # トークンは Settings → Actions → Runners → New self-hosted runner の値（入力で）
   ```
5. **最初のデプロイ** — Actions → deploy (OCI Ampere) → Run workflow（バージョンは空 = デフォルトブランチの最新）→ ランナーのログで ops/28 の出力を確認。

### 6.2 以前の Jenkins スクリプト（`CI-CD/`）

AWS インスタンス 2 台に docker compose で載せていた構成用のものだ（設定リポジトリのチェックアウト → 設定ファイルのコピー → プライベートレジストリ → SSH で compose）。
今の構成（OCI Ampere 1 台の k3s、GHCR、コミット SHA タグ、スモークゲート）と合わないので使わない — 参考用として残しておく。

---

## 7. 実践的な使い方

### 普段のデプロイ

```bash
# 1. パイプラインがグリーンか確認し、リリース候補のバージョンを得る
#    (Actions のサマリーにデプロイコマンドがそのまま出る)

# 2. 何が変わるかを先に見る
./ops/28-deploy.sh prod a1b2c3d4e5f6 --dry-run

# 3. デプロイする — スモークまで通過して初めて成功として記録される
./ops/28-deploy.sh prod a1b2c3d4e5f6
```

### 何かがおかしくなったとき

スモークが失敗したら、**何もしなくても直前のバージョンに戻る。** 手動で戻すには:

```bash
./ops/28-deploy.sh prod --rollback
```

原因を生きた状態で見たいなら、自動復帰を切ってデプロイする:

```bash
NO_ROLLBACK=1 ./ops/28-deploy.sh prod a1b2c3d4e5f6
# 調査の後
./ops/28-deploy.sh prod --rollback
```

### パイプラインなしで急いで作る必要があるとき

```bash
./ops/19-build-images.sh --registry ghcr.io/<owner> --push   # タグは自動でコミット SHA
./ops/28-deploy.sh prod $(git rev-parse --short=12 HEAD)
```

本の第 10 章の警告も一緒に心に刻むこと:

> "Never do them late at night, and always pair with somebody else."
>
> "Sometimes it's better to roll back to the previous version than to deploy a fix."

緊急の修正がロールバックよりよいケースは、思っているより稀だ。

---

## 8. 関連ドキュメント

- [33-deploy-runbook.md](33-deploy-runbook.md) — 最初から立ち上げる手順。このドキュメントは **その次**、
  2 回目以降のデプロイの話だ
- [34-acceptance-checklist.md](34-acceptance-checklist.md) — 人が回る受け入れの経路。
  §5.5 の「受け入れテストが足りない」は、このチェックリストが自動化されていないという意味だ
- [17-testing.md](17-testing.md) — パイプラインが回すテストが何を捕まえるか
- [31-kustomize-oci-tunnel.md](31-kustomize-oci-tunnel.md) — オーバーレイの構造とインバウンド 0 の構成
- [ops/README.md](../ops/README.md) — 運用スクリプト全体
