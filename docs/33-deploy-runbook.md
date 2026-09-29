# 33. デプロイ Runbook — Mac · Linux · OCI Ampere 1 台

**日本語** | [한국어](33-deploy-runbook.ko.md)

> 🕰️ **状態（2026-09-27）— 一部古い。** フロントエンドは CRA ではなく webpack 5 です（`REACT_APP_*` という名前はそのまま）。通常のデプロイは CI → 承認 → `ops/28` です（[docs/36 §6](36-deployment-pipeline.md)）。

docs/31（オーバーレイ・ブートストラップ・トンネル）と docs/32（インフラの外部化）が**何を、なぜ作ったのか**を
説明するものだとすれば、この文書は**それらをどの順序で実行するか**だけを扱う。

マシンが 3 台という前提で書く。各ステップの前に、どこで実行するかを記した。

---

## 0. マシンごとの役割

| | 役割 | なぜそのマシンか |
|---|---|---|
| **Mac** | 操縦席 — コード、シークレットの保管、`kubectl`、Cloudflare トンネルの作成 | トンネルの資格情報が `~/.cloudflared` にできる。シークレットはサーバーに残らないほうがよい |
| **Linux** | デプロイ前の検証 — `ops/13`（k3s 実クラスター）、`ops/12`（フルスタック）、`ops/14`（負荷） | **`ops/13` は Linux 専用である**（privileged コンテナを使う）。Mac では動かない |
| **Ampere 1 台** | 本番 + **arm64 イメージのビルド** | Ampere は arm64 なので、その上でビルドすればエミュレーションがゼロになる |

Mac が Apple Silicon なら arm64 イメージを Mac で作ることもできる。それでもインスタンス上で
ビルドするほうが単純である — レジストリを経由しなくてよく、ビルドキャッシュがデプロイ先に残る。

---

## 1. 始める前に決めること — 順序の制約 2 つ

### ① ドメインを先に決める

フロントエンドは Create React App なので、`REACT_APP_*` が**ビルド時点でバンドルに埋め込まれる。**
ランタイムの環境変数では変えられない。ドメインを決めないままイメージを作ると、**すべてビルドし直す**必要がある。

### ② インスタンスは 1 台で、受け取れる分をすべて

2 台に分けても同じ上限を分け合うだけで、2 ノードの k3s は etcd のクォーラムの観点から 1 ノードより悪い。

**上限はテナンシーごとに異なる。** 2026-06-15 付で Always Free Ampere の上限が 4 OCPU/24GB →
2 OCPU/12GB に縮小されたと、告知なしにドキュメントだけが変わったが、**実際には 4 OCPU/24GB がそのまま
確保できるアカウントもある。** ドキュメントを信じず、インスタンス上で確認する:

```bash
nproc && free -g
```

受け取ったサイズによって**使うオーバーレイが分かれる。** これがこの文書全体で最も重要な分岐である。

| 受け取ったサイズ | オーバーレイ | リクエスト合計 | 備考 |
|---|---|---:|---|
| **4 OCPU / 24GB** | `k8s-overlays/external` | 約 **1,950m (49%)** | CPU リクエストを 100m に下げた後の値。下げる前は実測 3,980m（99%）だった — docs/32 §2 |
| **2 OCPU / 12GB** | `k8s-overlays/oci` | 1,000m / 3,520Mi | ES を除き、リクエスト・ヒープを絞った値。すべて 1 レプリカ |

⚠️ CPU リクエストは `k8s-overlays/external/patch-java-requests.yaml` が決める。**`kubectl patch`
で一時的に変えないこと** — 次の `kubectl apply -k` で上書きされて 99% に戻り、そうなるとポッドが
`Insufficient cpu` でスケジュールされなくなる（実際に経験した）。変える必要があれば、そのファイルで変える。

どちらも **`k8s/70-autoscaling.yaml` は適用しない。** prod の HPA は min=2 なので、
どちらでも CPU を超える（5,300m）。計算は docs/32 §2。

⚠️ 4 OCPU を受け取った場合でも `oci` オーバーレイを使ってよい — より絞った値なので、単に余裕が残るだけである。
逆は駄目である。

### アカウントの準備（Mac、ブラウザ）

| | 備考 |
|---|---|
| Neon（または Aiven） | ⚠️ **OCI インスタンスと同じリージョン。** 遅延が 30ms になると、クエリ 20 個のリクエストが 600ms になる |
| MongoDB Atlas M0 | chat-service 用 |
| ~~Upstash Redis~~ | **作らない。** Redis はクラスター内に残すことにした — docs/32 §6 |
| Cloudflare | ドメインのネームサーバーを Cloudflare に移しておくこと |
| GHCR | `write:packages` 権限の PAT |

---

## 2. 【Mac】 マネージド PostgreSQL の確保

```bash
brew install postgresql@16 kubectl kustomize cloudflared
```

Neon コンソール → **Connection Details** から接続文字列を取得する。3 つを必ず確認する。

| 確認 | 理由 |
|---|---|
| Role が **`neondb_owner`** | コンソールで作ったロールだけが `neon_superuser` のメンバーなので `CREATE DATABASE` ができる。SQL で作ったロール（`product`）は普通のロールなので、3 段階目で止まる |
| **Connection pooling トグル OFF** | プーラー（`-pooler`）は PgBouncer の transaction モードなので `CREATE DATABASE` をトランザクションで包む — 実行不可である |
| `channel_binding` の削除 | libpq 専用のオプションである。後で JDBC URL に入ってはならない |

```bash
# ⚠️ 引用符は**手で入力**すること。Web からコピーすると ' が丸引用符（‘）に変わって値に混ざる。
#    すると libpq が URI ではなく キー=値 文字列として読み、見当違いのエラーを出す。
export ADMIN_URL="postgresql://neondb_owner@ep-xxxx.<region>.aws.neon.tech/neondb?sslmode=require"

printf '관리자 비밀번호: '; read -rs PGPASSWORD; echo; export PGPASSWORD

psql -X "$ADMIN_URL" -c 'select current_user'      # neondb_owner でなければならない
```

```bash
ops/25-provision-external-postgres.sh --check      # ← ここで止まるなら、その DB ではデプロイできない
ops/25-provision-external-postgres.sh              # ロール 1 + データベース 10 個
```

`--check` は SKIP LOCKED · jsonb · IDENTITY · 宣言的パーティショニング · スキーマ作成 · `CREATE DATABASE`
の 6 つを**実際に実行してみる**。カタログを読んで推測はしない（docs/32 §9）。

### ⚠️ パスワードは 2 つある

| | 誰のものか | どこで使われるか |
|---|---|---|
| `PGPASSWORD` | **neondb_owner** | プロビジョニングの間だけ。**クラスターには入れない** |
| スクリプトが尋ねる値 | **product** | `k8s/20-secret.yaml` の `LOCAL_DB_PASSWORD` になる |

アプリケーションは `product` でのみ接続する。

---

## 2b. 【Mac】 MongoDB Atlas クラスターの作成

**chat-service だけが使う。** コレクションは `chat`（メッセージ）と `auto_sequence`（チャットルーム番号の
シーケンス）の 2 つで、**事前に作る必要はない** — 最初の書き込みでできる。インデックスもコードで宣言された
ものはない。だからやることはクラスター・ユーザー・ネットワークの 3 つだけである。

### ① クラスター（M0 Free）

1. <https://cloud.mongodb.com> に登録 → **Create a cluster** → **M0 Free**
2. Provider・Region: ⚠️ **OCI インスタンスと同じ地域**を選ぶ。東京なら AWS `ap-northeast-1`。
   遅延が 30ms を超えると、チャットメッセージの保存が目に見えて遅くなる
3. 名前はデフォルト（`Cluster0`）のままにする — 接続文字列にしか使われない

M0 の壁: **ストレージ容量 512MB**、バックアップなし、アイドル時に自動一時停止。個人プロジェクトには
十分だが、**バックアップがない**という点は知っておく必要がある（`ops/10-backup-mongo.sh` で自分で取る）。

### ② データベースユーザー

Database Access → **Add New Database User** → Password 認証。

| | 値 |
|---|---|
| ユーザー名 | 例: `chat` |
| 権限 | **Specific Privileges** → `readWrite` on `chat` |

⚠️「Read and write to any database」は選ばない。このプロジェクトが使う DB は `chat`
1 つだけなので、広げる理由がない。

⚠️ **パスワードに特殊文字を使うと、接続文字列でパーセントエンコードする必要がある**（`@` → `%40`、
`/` → `%2F`、`:` → `%3A`）。エンコードを忘れると URI のパースが見当違いの場所で切れて「ホストが
見つからない」というエラーが出るが、原因がパスワードであることはメッセージに現れない。**英数字だけを使う
ほうが簡単である。**

### ③ ネットワークアクセス

Network Access → **Add IP Address** → **Ampere のパブリック IP/32**。

⚠️ `0.0.0.0/0`（Allow Access from Anywhere）は選ばない。そうするとパスワードが唯一の
防御線になる。OCI インスタンスのパブリック IP は再起動しても維持されるので、一度入れればよい。

### ④ 接続文字列

Connect → **Drivers** → Java → 文字列をコピー:

```
mongodb+srv://chat:<PASSWORD>@cluster0.xxxxx.mongodb.net/?retryWrites=true&w=majority
```

この値が `k8s/20-secret.yaml` の `SPRING_DATA_MONGODB_CLIENT` になる（§7）。

> ⚠️ **データベース名はこの URI では決まらない。** chat-service は
> `MongoTemplate(mongoClient(), mongoProperties.getName())` で作られ、その
> `name` は `spring.data.mongodb.name` である（値: `chat`）。URI の末尾に `/chat` を付けても
> **無視される** — 付けても害はないが、付け忘れたと心配する必要もない。
>
> 逆に Atlas 側の DB 名を `chat` 以外で作った場合は、URI ではなく
> `SPRING_DATA_MONGODB_NAME` を変える必要がある。URI だけ直すと、静かに見当違いの DB を使う。

> ⚠️ **証明書のパスは埋めない。** `mongodb+srv` は TLS がデフォルトで、Atlas の証明書は
> 公開 CA で発行される。`external` オーバーレイが `SPRING_DATA_MONGODB_CACERT` ·
> `SPRING_DATA_MONGODB_KEYSTORE` を**空の値で上書きする理由がこれ**である — 空なら
> `MongoSslConfig` が JVM デフォルトのトラストストアを使う。プライベート CA のパスが残っていると、その
> トラストストアで検証しようとしてハンドシェイクが失敗する（docs/32 §4）。

### ⑤ 接続できるか確認

Secret に入れる前に、まず Mac で確認する。ここで止まるなら、クラスターでも止まる:

```bash
brew install mongosh
mongosh "mongodb+srv://chat@cluster0.xxxxx.mongodb.net/" --apiVersion 1
# パスワードはプロンプトで入力される — コマンドラインに入れるとシェル履歴に残る
```

```javascript
use chat
db.smoke.insertOne({ ok: 1 })
db.smoke.find()
db.smoke.drop()
```

| 症状 | 原因 |
|---|---|
| `getaddrinfo ENOTFOUND` / SRV 照会失敗 | クラスター名のタイプミスか、DNS が SRV を問い合わせられない |
| `bad auth : authentication failed` | ユーザー名・パスワード。特殊文字をエンコードしたか確認 |
| 接続がただ止まる | **Network Access に IP がない。** Mac でテストするなら Mac の IP も一時的に入れる必要がある |
| `not authorized on chat` | ユーザー権限が別の DB に付いている |

⚠️ Mac の IP を入れてテストしたなら、**終わったら消す。** 残しておけば、その分だけ開いていることになる。

（Upstash は作らない — Redis はクラスターに残す、docs/32 §6）

---

## 3. 【Linux】 デプロイ前の検証

飛ばすと Ampere 上でデバッグすることになる。ここで捕まるものは、あちらではずっと高くつく。

```bash
git clone <repo> && cd ecommerce-msa
./gradlew test -PwithContainers        # 268 tests — 12 サービスが実際のインフラで起動するところまで
./ops/13-k3s-deploy.sh                 # k3s 実クラスター + マニフェスト全体 + スモーク
```

`ops/13` はクラスター**内**のインフラで動く。そしてそれは本番の構成とも同じである —
**Redis はクラスターに残すことにした**（docs/32 §6）。マネージドに移したくなったら、そのとき
スタックを残しておいて cart-service だけをつないでみればよい:

```bash
KEEP=1 ./ops/13-k3s-deploy.sh
# カートに追加（Redisson ロック経路）を押してみる。ERR unknown command や pub/sub
# タイムアウトが出たら、その provider は使えない。
```

---

## 4. 【OCI】 インスタンス作成 — ここで最も長く足止めされる

- Ampere A1 · **受け取れる最大**（aarch64）。テナンシーによって 4 OCPU/24GB または 2 OCPU/12GB — §1 ②
- イメージ: **Oracle Linux 9 (aarch64)** を推奨。Ubuntu もそのまま動く — 下記参照
- ブートボリュームは **100GB 以上**。デフォルトの 47GB ではイメージが溜まると kubelet がポッドを evict し始める
- Security List のイングレス: **22 番だけ。** できれば自分の IP に制限

80/443 は開けない。Cloudflare Tunnel を使う理由がそれである。

### 接続経路 — セキュリティリストに何を開ける必要があるか

**たいてい何も開ける必要はない。** OCI は VCN を作るときにデフォルトのセキュリティリストに
`0.0.0.0/0 → TCP 22` のイングレスを入れてくれる。ウィザードで作ったなら SSH はそのまま使える。

| ポート | 開けるか | 理由 |
|---|---|---|
| 22 | **すでに開いている** | デフォルトのセキュリティリストにある。自分の IP に絞るだけでよい |
| 80 / 443 | **開けない** | Cloudflare Tunnel が**外へ**出て接続を張る。インバウンドは不要 |
| 6443 | **開けない** | `kubectl` は SSH トンネルでつなぐ（§8）。開けると k8s API がインターネットに露出する |

だから SSH がつながらないとき、犯人はたいていセキュリティリストではない。3 つのうちのどれかである。

| | 何 | 症状 |
|---|---|---|
| ① | **プライベートサブネット**を選んだ | パブリック IP 自体がない。VCN ウィザードはパブリック・プライベートを**両方**作る |
| ② | ルートテーブルに **IGW がない**（NAT だけがある） | パブリック IP は付くが、外から入ってくる道がない → **timeout** |
| ③ | インスタンス**内**のファイアウォール | OCI の外の問題。`ops/23` が扱う |

症状で OCI 側とホスト側を切り分けられる。これが最も速い判定である:

- **timeout** → パケットが静かに捨てられた。①②・セキュリティリスト
- **Connection refused** → パケットは届いた。③ か、`sshd` が起動していない

①②とセキュリティリストは**インスタンスを作る前に**確認できる:

```bash
ops/26-oci-launch-retry.sh --list-subnets      # public=true のものを選ぶ
export OCI_SUBNET_OCID=ocid1.subnet.oc1...
ops/26-oci-launch-retry.sh --check-network
```

パブリックかどうか · IGW ルート · 22 番の許可を見て、**おまけに 22 番以外でインターネットに開いたルールを
探して警告する**（6443 が開いていれば k8s API の露出である）。ICMP type 3 code 4 は Path MTU
Discovery 用なので警告しない — それはあるべきルールである。

> ⚠️ NSG はサブネットではなく VNIC に付くので、この点検には見えない。OCI はセキュリティリストと
> NSG を**和集合**で適用するので、セキュリティリストに 22 があれば通過である。NSG だけで許可している
> 構成なら「22 なし」の警告は誤検知である。

③ はインスタンス上で見る — `sudo bash ops/23-oci-ampere-bootstrap.sh --check`（§5）。

### どの Linux を選ぶか

東京リージョンでは A1 のイメージ一覧に Ubuntu が出てこないことがある。RHEL 系の 3 つのうち
**Oracle Linux 9** を勧める。中で動くものは 3 つとも事実上同じで、**OCI の中でだけ**
違いが出る。

| | Oracle Linux 9 | Rocky Linux 9 | CentOS |
|---|---|---|---|
| OCI イメージ | **プラットフォームイメージ** — コンソールの一覧に常にある | マーケットプレイスイメージ — OCID を別途探して `ops/26` に入れる必要がある | プラットフォームイメージ**なし** |
| aarch64 A1 | 公式サポート（OL7/8/9） | 動くが OCI は検証していない | — |
| 寿命 | 2032、無償アップデート | 2032 | **CentOS Linux 7 は 2024-06 EOL**。残る Stream は RHEL の*上流*なのでローリング |
| cloud-init・oci-utils | 内蔵 | 自前で | — |

- **CentOS は今は選択肢ではない。** CentOS Linux は終わっており、残る Stream はローリングである。
- **Rocky は技術的には同等である。** 分かれるのは OCI でプラットフォームイメージではないという一点だけだが、
  今はインスタンス作成自体が容量で止まる状況なので、手順を 1 つ増やす理由がない。
- ⚠️ **OL8 ではなく OL9。** OL8 は cgroup v1 がデフォルトなので、k3s のリソース制限が緩くなる。
- ⚠️ イメージを変えても `Out of host capacity` は**解決しない。** 容量はイメージと無関係である。

Ubuntu から移ってくると実際に変わるのは 4 つである。`ops/23` がすべて処理する:

| | Ubuntu | Oracle Linux 9 |
|---|---|---|
| デフォルトの SSH ユーザー | `ubuntu` | **`opc`** |
| パッケージ | `apt` | `dnf` |
| ファイアウォール | iptables + netfilter-persistent | **firewalld**（ポッド・サービスの CIDR を trusted ゾーンに） |
| SELinux | なし | **enforcing** — `container-selinux` + `k3s-selinux` が必要 |

最後の行が最も静かに噛みつく項目である。ポリシーなしで k3s を載せると**インストールは成功するのに**
コンテナ作成が拒否され、ログには `permission denied` しか残らず、ファイアウォールの問題のように見える。
`ops/23` は無効にする代わりにポリシーを入れ、インストール後に `rpm -q k3s-selinux` で実際に入ったかを確認する。

### ⚠️ `Out of host capacity`

東京・ソウルのような人気リージョンでは、コンソールで作ろうとするとほぼ必ずこれに遭遇する。設定ミスでは
なく、**その瞬間ホストに A1 のコアが空いていない**という意味である。だから次はすべて無駄である:

| よくある誤解 | 実際 |
|---|---|
| イメージを変える（Ubuntu ↔ Oracle Linux ↔ Rocky） | 容量は**イメージと無関係**である。イメージ選択は上の節の理由でするものであって、容量のためではない |
| 別のリージョンを選ぶ | ⚠️ **Always Free はホームリージョンでしか**使えない。ホームリージョンは後から変えられない |
| 別の AD/FD を選ぶ | 東京・大阪・ソウルは **AD が 1 つしかない**。FD の指定は範囲を狭めるだけ |
| shape を変える | A1 でなければ Always Free ではない |

通用するのは 2 つだけである。

**① 確保できるまで再試行する。** 容量は随時、短時間ずつ空く。

```bash
ops/26-oci-launch-retry.sh --list-subnets    # 必要な OCID を探す
ops/26-oci-launch-retry.sh --list-images
export OCI_COMPARTMENT_OCID=... OCI_SUBNET_OCID=... OCI_IMAGE_OCID=...
ops/26-oci-launch-retry.sh --check
tmux new -s oci 'ops/26-oci-launch-retry.sh'   # 何日放置してもよい
```

このスクリプトは**再試行すべき失敗とすべきでない失敗を分ける。** `Out of host capacity` は
叩き続け、`LimitExceeded`（すでに A1 を使っている）や認証エラーでは即座に止まる —
永遠に通らないリクエストを何日も叩き続けないためである。

> **AD を変えればいいのでは？** 作成**リクエスト**で AD を選ぶことはできる（すでに作った
> インスタンスを移すことはできない — AD は作成時に確定する）。スクリプトも AD が複数あれば
> 交互に試行する。
>
> ⚠️ ところが**東京・大阪・ソウルは AD が 1 つしかない。** 3 つあるのはアッシュバーン・フェニックス・
> フランクフルト・ロンドンくらいである。単一 AD のリージョンではこの巡回は何の効果もなく、スクリプトが
> 実行時にそう警告する。フォールトドメイン（FD）も指定しない — 指定すると OCI が探す
> 範囲が狭まるだけで、むしろ不利になる。

**② Pay As You Go にアップグレードする。** Always Free リソースはそのまま $0 で、A1 の割り当て
成功率が大きく上がるという報告が多い。カードが必要で、無料枠の上限（**2 OCPU/12GB**、
ブートボリューム合計 200GB）を超えると課金されるので、その線は守る必要がある。

⚠️ 上限縮小以降、PAYG アカウントも影響を受けるかについて Oracle サポートの回答が食い違うという
報告がある。ドキュメントには「すべてのテナンシー」と書かれている。カードを登録する前に請求アラートを
必ず設定しておくこと。

### それでも確保できなければ

上限が半減した今でも 2 OCPU/12GB を無料でくれるところは事実上 OCI だけである。
`k8s-overlays/oci` 基準の要件は **約 1,000m CPU / 3.5Gi リクエスト**（実使用 7GB 前後）なので、
Hetzner ARM `CAX21`（4 vCPU / 8GB、月 €4 程度）なら収まる — 無料枠が減ったことで、
有料の代替案との差がむしろ縮まった。

---

## 5. 【Ampere】 k3s ブートストラップ

```bash
# デフォルトユーザーはイメージごとに異なる: Oracle Linux は opc、Ubuntu は ubuntu
scp ops/23-oci-ampere-bootstrap.sh opc@<IP>:~/
ssh opc@<IP>
sudo bash 23-oci-ampere-bootstrap.sh --check     # まず診断
sudo bash 23-oci-ampere-bootstrap.sh
```

OCI イメージは**インスタンス内に入力遮断ルールをあらかじめ入れてある**（Ubuntu は iptables、
Oracle Linux は firewalld）。それがポッドネットワーク（CNI）まで塞ぐが、症状が「ポッドは Running
なのに通信不可 / CoreDNS CrashLoopBackOff / `kubectl exec` タイムアウト」なので、ファイアウォールの問題には
見えない。スクリプトはルールを**消さずに**、ポッド・サービスの CIDR だけを許可する。

Oracle Linux では **SELinux もここで処理する** — enforcing を無効にせず、
`container-selinux`・`k3s-selinux` ポリシーを入れる（§4 参照）。

Elasticsearch がクラスターに残るので、`vm.max_map_count=262144` もここで上げる。

> ⚠️ `iptables` コマンドがない最小インストールイメージでは、この検査は以前**「遮断ルールなし」として
> 誤って通過**していた。今は未確認だと言う — ないことと、見られなかったことは違う。

---

## 6. 【Ampere】 arm64 イメージのビルド

### GHCR ログイン

`GHCR_PAT` は**自分で発行して入れなければならない値**である。どこからも自動では来ない。

GitHub → Settings → Developer settings → Personal access tokens → **Tokens (classic)**
→ Generate new token (classic) → スコープ **`write:packages`**

| | |
|---|---|
| ⚠️ Fine-grained token | **使えない。** GHCR をきちんとサポートしておらず、push で止まる |
| ⚠️ 値が見えるタイミング | 作成直後の**一度だけ**である。逃したら再発行 |
| `-u` の位置 | GitHub のユーザー名。メールアドレスではない。下の `<USER>` と同じ値 |

```bash
git clone <repo> && cd ecommerce-msa

# 1 行で export するとシェル履歴に平文で残る。エコーなしで受け取る。
printf 'GHCR PAT: '; read -rs GHCR_PAT; echo; export GHCR_PAT
echo "${#GHCR_PAT}"        # 長さだけ確認 — 値は絶対に出力しない

echo "$GHCR_PAT" | docker login ghcr.io -u <USER> --password-stdin
```

> `password is empty` が出たら原因は 2 つのうちのどちらかである: 変数が空か、パイプ左側のコマンドが
> 失敗して stdin が空になったか（`echo` のタイプミスなど）。**どちらも同じメッセージ**なので、変数から確認する必要が
> ある — 上の `${#GHCR_PAT}` がその確認である。

#### `Login Succeeded` なのに push が拒否されるとき

```
denied: permission_denied: The token provided does not match expected scopes.
```

**ログイン失敗ではない。** ビルドログに `[auth] ... pull,push token for ghcr.io  DONE` が
出ていれば認証は通っており、拒否されたのは**トークンのスコープ**である。ログインし直しても直らない。

トークンの値を露出させずに、スコープだけを確認する:

```bash
curl -sI -H "Authorization: Bearer $GHCR_PAT" https://api.github.com/user | grep -i x-oauth-scopes
```

| 結果 | 意味 | やること |
|---|---|---|
| `x-oauth-scopes: ... write:packages ...` | スコープは正しい | 組織の SSO やパッケージ所有者の不一致を見る |
| 一覧に `write:packages` がない | classic トークンだがスコープ未チェック | トークン設定でチェックして保存 |
| **ヘッダーがまったく出ない** | **fine-grained トークン**である | classic で新たに発行 |

トークンを直した後は**必ずログインし直す。** Docker が資格情報をキャッシュするので、そのままにすると
古いトークンを使い続ける:

```bash
docker logout ghcr.io
echo "$GHCR_PAT" | docker login ghcr.io -u <USER> --password-stdin
```

### ビルド

```bash
REACT_APP_API_URL=https://shop.example.com \
REACT_APP_PAYPAL_CLIENT_ID=<paypal-client-id> \
  ./ops/19-build-images.sh --registry ghcr.io/<USER> --push
```

**推奨 — ファイルに一度だけ書いておく。** `ops/19`（そしてそれを呼ぶ `ops/33`）は `ops/frontend.env` を自動的に読む
（gitignore — 環境ごとに異なる）。環境変数で与えた値が優先される。

```bash
cp ops/frontend.env.example ops/frontend.env   # REACT_APP_API_URL・REACT_APP_PAYPAL_CLIENT_ID・（PortOne）を埋める
```

> ⚠️ シェルで `REACT_APP_API_URL=…` を**1 行で単独で**打つと、それはそのシェルの変数にすぎずスクリプトには渡らない（export がないと
> 子プロセスからは見えない）。`echo` では見えるので「設定したのに空だと言われる」ことになる。ファイルならそういうことは起きない。
> ビルドログの `✓ 프론트 설정 파일에서 읽음 … REACT_APP_API_URL REACT_APP_PAYPAL_CLIENT_ID` で確認する。
> ここには**公開値だけ**を置く — PayPal Secret・Stripe・PortOne のシークレットは `k8s/20-secret.yaml` にだけ（B-51）。
>
> 空の値で frontend をすでにデプロイしてしまったなら、同じタグでは上げ直せない（ノードがキャッシュされたイメージを使う）— 新しいコミットの後に
> `./ops/33-deploy-some.sh frontend`。

> **`REACT_APP_API_URL` に何を入れるか — フロントエンドと同じアドレスである。** パスもポートも
> 付けない。フロントエンドが `${REACT_APP_API_URL}/user-service/api/...` のように
> **サービスプレフィックスを自分で付け**、トンネルがそのパスをゲートウェイに送る
> （`k8s-overlays/prod/cloudflared.yaml` の最初の ingress ルール）。
>
> ホストを 1 つにしているのは意図的である — フロントエンドと API が**同一オリジン**なので CORS 設定が
> 不要で、無料の Universal SSL の「サブドメイン 1 階層」制約にも引っかからない。
>
> ⚠️ この値は**バンドルに埋め込まれる。** ドメインが決まる前にビルドすると、フロントエンドを丸ごと
> 作り直す必要がある（§1 ①）。
>
> ⚠️ **サイトはこのアドレス 1 つだけで開かれなければならない。** トンネルはホスト名を区別しないので `https://<도메인>`
> （www なし）で入ってきても画面が表示されるが、そうするとすべての API 呼び出しが www へのクロスオリジンになり、
> ゲートウェイが 403 で拒否する（Firefox: `Cross-Origin Request Blocked … token/refresh`）。
> 本番バンドルはその場合、同じパスでこのアドレスを開き直す（`frontend/src/util/canonicalOrigin.js`）。より
> すっきりするのは、描画する前に Cloudflare で移すことである — ダッシュボード → Rules → Redirect Rules →
> 「Redirect from root to WWW」テンプレート（301、パス・クエリを維持）。

⚠️ GHCR のパスは**小文字しか**受け付けない。ユーザー名に大文字があれば、`--registry` には小文字で入れる。

### ⚠️ 同じタグを再利用するとノードが新しいイメージを取得しない

マニフェストに `imagePullPolicy` がなく、タグが `latest` ではないので、デフォルト値は
**`IfNotPresent`** である。つまり**同じタグを上書き push してもノードはキャッシュされた古いイメージを使い
続ける。** kubelet はこう出力するだけである:

```
Normal  Pulled  Container image "ghcr.io/USER/ecommerce-msa/frontend:1.0.0" already present on machine
```

「直して上げたのになぜそのままなのか」はここから生じる。実際にこのデプロイで経験した。

**この罠は今ではデフォルト値が防ぐ。** `ops/19` のデフォルトタグが**現在のコミットの SHA 12 文字**なので、
同じタグが 2 回出ることはありえない（docs/36 §3.2）。手で `--tag` を与えない限り安全である。
`--tag` で人が選んだ値を使うと、古い罠がそのまま戻ってくる。

2 回目のデプロイからは、イメージを作った後 **`ops/28-deploy.sh`** で上げる — タグの注入、
デプロイ順序、スモークゲート、失敗時の自動復帰がすべてその中にある（docs/36 §7）:

```bash
./ops/19-build-images.sh --registry ghcr.io/<USER> --push       # タグ = コミット SHA
./ops/28-deploy.sh prod $(git rev-parse --short=12 HEAD)
```

`--only` には**引用符で囲んで複数**を与えることができ、frontend も混ぜられる —
`--only "delivery-service frontend"`。`common` はサービスではなくライブラリなので書かない。
Gradle が依存モジュールを自動で一緒にビルドする。

フロントエンドだけを直した場合でも、`--only frontend` でそのイメージだけを作り、残りは**同じコミット
SHA ですでにレジストリにある必要がある。** なければ `ops/28` がクラスターに触れる前に
「イメージ N 個が見つかりません」で止まる — 半分だけ新バージョンという状態が生じないようにするための
ものである。

同じタグにこだわるなら、ノードのキャッシュを直接消す必要がある — ロールバックができず、何が動いているのか
不明確になるので勧めない:

```bash
sudo k3s crictl rmi ghcr.io/<USER>/ecommerce-msa/frontend:1.0.0
kubectl -n ecommerce rollout restart deploy/frontend
```

`ops/19` は push の前に、そのタグがすでにレジストリにあれば警告する。

⚠️ 最初に push したパッケージは **private で作られる。** そのままにすると k3s がイメージを pull できず
`ImagePullBackOff` になる。2 つのうちどちらかを選ぶ。

**① パッケージを public に変える（簡単）。** GitHub → プロフィール → Packages → 各パッケージ →
Package settings → Change visibility。サービスが 12 個なので 12 回やる必要がある。

**② private を維持して pull secret を入れる。** このリポジトリのマニフェストには
`imagePullSecrets` が**ないので**、Secret を作るだけでは付かない。Deployment 12 個を
直す代わりに、ネームスペースのデフォルト ServiceAccount に付ければ 1 回で済む:

```bash
kubectl -n ecommerce create secret docker-registry ghcr \
  --docker-server=ghcr.io --docker-username=<USER> --docker-password="$GHCR_PAT"

kubectl -n ecommerce patch serviceaccount default \
  -p '{"imagePullSecrets":[{"name":"ghcr"}]}'

# すでに失敗中のポッドは自力では回復しない。回してやる。
kubectl -n ecommerce rollout restart deploy
```

⚠️ ②を選ぶと **PAT の期限切れがデプロイ障害になる。** classic PAT に有効期限を設定していたら、
その日にイメージの pull が静かに失敗する。個人プロジェクトなら①のほうがよい。

`--platform` を与えないことが肝心である。ホストが arm64 なので、そのままネイティブである。

> ⚠️ `REACT_APP_*` は**公開バンドルに埋め込まれる。** シークレットを入れてはならない。PayPal の *client id* は
> 公開値だが、secret はそうではない。

---

## 7. 【Ampere】 証明書 + シークレット

```bash
./ops/07-issue-certificates.sh                       # クラスター内部用のプライベート CA セット
kubectl apply -f k8s/00-namespace.yaml
kubectl apply -f certs/k8s-tls-keystore.secret.yaml  # tls-keystore Secret

cp k8s/20-secret.example.yaml k8s/20-secret.yaml     # .gitignore の対象 — コミットされない
```

`k8s/20-secret.yaml` に埋める値:

| キー | 値 |
|---|---|
| `LOCAL_DB_PASSWORD` | §2 でスクリプトに入力した **product** のパスワード。**入れる前に psql で確認する** — 下記 |
| `SPRING_DATA_REDIS_PASSWORD` | マネージド Redis を使うときだけ。クラスター内の Redis なら空にしておく（LOCAL_DB_PASSWORD にフォールバック） |
| `SPRING_DATA_MONGODB_CLIENT` | §2b でコピーした Atlas の文字列。`mongodb+srv://chat:PW@cluster0.xxx.mongodb.net/?retryWrites=true&w=majority` — パスワードに特殊文字があれば**パーセントエンコード**。DB 名はこの URI ではなく `spring.data.mongodb.name` が決める（§2b） |
| `LOCAL_KEY_STORE_PASSWORD` · `LOCAL_KEY_PASSWORD` · `KAFKA_SSL_*_PASSWORD` | **手で写さないこと。** `certs/certs.env` から直接入れる — 下記 |
| `JWT_SECRET_KEY` · `SMS_CODE_PEPPER` | `openssl rand -hex 32` で**それぞれ別に** — 下記参照 |
| AWS · PayPal · GitHub OAuth | **新たに発行したもの。** git 履歴に流出していた値ではなく（docs/30） |

> **Cloudinary は不要である。** フロントエンドから外部ストレージへ直接アップロードしていたコードは削除した
> — 画像は catalog-service が `MultipartFile` で受け取って **S3** にアップロードする（`ImageService` ·
> `AmazonS3Config`）。だからアカウントも、キーも、`REACT_APP_CLOUDINARY_*` も不要である。
>
> ⚠️ ただし**履歴に流出した古いキーはそのまま有効である。** コードを消したこととキーを失効させたことは
> 違う — 失効は依然として必要である（docs/30）。

### `LOCAL_DB_PASSWORD` は入れる前に確認する

この値だけはファイルから持ってこられない — `ops/25` がプロンプトで受け取り、**どこにも保存
しないから**である（保存すればそれが流出経路になる）。そのため手で写さなければならない唯一の
パスワードであり、間違えると **10 サービスがすべて** Flyway の段階で死ぬ。

クラスターに入れる前に、まず psql で確認する。ここで通ってこそ、あちらでも通る。

⚠️ **この節は【Ampere】なのに、psql は §2【Mac】でインストールした。** インスタンスにはない。どちらか一方:

```bash
# ① Ampere にインストール — Ubuntu 24.04 では postgresql-client → 16 になる
sudo apt-get update && sudo apt-get install -y postgresql-client
#   Oracle Linux 9:  sudo dnf install -y postgresql

# ② または Mac で確認する。Neon はどこからでも接続できるので結果は同じ。
#    ⚠️ Neon に IP 制限をかけているなら Mac の IP を一時的に追加し、終わったら消す。
```

`postgresql-client-common` だけがあって実際のクライアントがないと、こう出る — インストールされていない
のであって、接続に失敗したのではない:

```
Warning: No existing cluster is suitable as a default target.
Error: You must install at least one postgresql-client-<version> package
```

ホストは**すでに適用した ConfigMap から読む。** `$ADMIN_URL` は §2【Mac】のシェル変数なので
Ampere にはなく、何よりこちらが**アプリが実際に使う値**なので、確認としてもより正確である:

```bash
HOST=$(kubectl -n ecommerce get cm ecommerce-config -o jsonpath='{.data.POSTGRES_HOST}')
echo "HOST=[$HOST]"          # 空なら [] と表示される — そうなったらここで止まる

printf 'product 비밀번호: '; read -rs APP_DB_PASSWORD; echo

PGPASSWORD="$APP_DB_PASSWORD" psql -X \
  "postgresql://product@$HOST/userdb?sslmode=require" -c 'select current_user'
```

> ⚠️ **`$HOST` が空だと、接続失敗ではなく見当違いの場所へ行く。** ホストのない URI を
> libpq は**ローカルの Unix ソケット**と解釈する:
>
> ```
> psql: error: connection to server on socket "/var/run/postgresql/.s.PGSQL.5432" failed:
>       No such file or directory
> ```
>
> Neon が拒否したように見えるが、Neon までたどり着いてすらいない。まず変数が空かどうかを見る —
> 上で値を角括弧で囲んで出力する理由がこれである。

思い出せなければ当てずっぽうをせずリセットする。このときだけ**管理者の資格情報が必要である**（§2）。
Ampere にはないので入れ直すか Mac で行う — 引用符は**手で入力すること**（§2 の
丸引用符の罠）:

```bash
export ADMIN_URL="postgresql://neondb_owner@$HOST/neondb?sslmode=require"
printf '관리자(neondb_owner) 비밀번호: '; read -rs PGPASSWORD; echo; export PGPASSWORD

psql -X "$ADMIN_URL" -c "ALTER ROLE product WITH PASSWORD '$APP_DB_PASSWORD'"
```

確認できたら Secret に入れる:

```bash
kubectl -n ecommerce patch secret ecommerce-secrets --type merge \
  -p "{\"stringData\":{\"LOCAL_DB_PASSWORD\":\"$APP_DB_PASSWORD\"}}"
unset APP_DB_PASSWORD PGPASSWORD
```

> ⚠️ **コンソールの URI のパスワードはパーセントエンコードされている。** `%40` は `@` である。その値をそのまま
> 入れると認証に失敗する。**平文**が必要である — Neon は Roles → Reset password で平文を
> 一度だけ表示してくれる。
>
> 実際にこのデプロイで URI が `product:%E2%80%98…%E2%80%99@` と出た。デコードすると
> **`‘…’`** — コピーするときにエディターが入れた**丸引用符が値の一部**になっていた。
> `read -rs` は画面に何も表示しないので目で捕まえられず、長さチェックも
> 通過する。今は `ops/25` がこれらの文字を拒否する。
>
> **間違えたときの症状:** `Unable to obtain connection from database:` +
> `password authentication failed for user 'product'`（SQL State **28P01**）。
>
> ⚠️ このエラーが出たなら、**ホスト・TLS・ロールはすべて合っている。** そこまで到達して初めて認証を
> 試みる。見当違いに `POSTGRES_HOST` や `sslmode` を探り直さないこと。

### ストアのパスワード 4 つは `certs.env` から入れる

`ops/07` は **1 つの `STORE_PASSWORD` で 5 つのストアをすべて**作る。だから下の 4 つの
キーは値が同じでなければならず、1 つでもずれるとそのストアを開くサービスが起動時に死ぬ。
手で写すといつか必ず間違えるので、ファイルから直接入れる:

```bash
set -a; . certs/certs.env; set +a
echo "길이: ${#LOCAL_KEY_STORE_PASSWORD}"     # 24 でなければならない（値は出力しない）

kubectl -n ecommerce patch secret ecommerce-secrets --type merge -p "{\"stringData\":{
  \"LOCAL_KEY_STORE_PASSWORD\":\"$LOCAL_KEY_STORE_PASSWORD\",
  \"LOCAL_KEY_PASSWORD\":\"$LOCAL_KEY_PASSWORD\",
  \"KAFKA_SSL_KEY_STORE_PASSWORD\":\"$KAFKA_SSL_KEY_STORE_PASSWORD\",
  \"KAFKA_SSL_TRUST_STORE_PASSWORD\":\"$KAFKA_SSL_TRUST_STORE_PASSWORD\"}}"

kubectl -n ecommerce rollout restart deploy
```

> **間違えたときの症状。** ポッドが `CrashLoopBackOff` で回り、ログの一番下がこうなる:
>
> ```
> Caused by: java.io.IOException: keystore password was incorrect
> Caused by: java.security.UnrecoverableKeyException: failed to decrypt safe contents entry:
>            javax.crypto.BadPaddingException: Given final block not properly padded
> ```
>
> **ファイルは正しくマウントされている** — 見つからなかったなら `KeyStore not exists` が出ていたはずである。
> 純粋にパスワードの不一致である。
>
> ⚠️ `ops/07` を**再実行した**なら、`certs/k8s-tls-keystore.secret.yaml` も一緒に再
> 適用する必要がある。ストアファイルとパスワードはワンセットなので、片方だけ更新すると同じエラーが出る:
>
> ```bash
> kubectl apply -f certs/k8s-tls-keystore.secret.yaml
> ```

### `JWT_SECRET_KEY` · `SMS_CODE_PEPPER` の作成

```bash
openssl rand -hex 32     # JWT_SECRET_KEY
openssl rand -hex 32     # SMS_CODE_PEPPER  ← もう一度実行する。同じ値は使わない
```

**なぜ hex で、なぜ 32 なのか。**

コードはこの文字列を**デコードせず UTF-8 バイトのまま**キーとして使う:

```java
new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), Jwts.SIG.HS256.key()...)
```

HS256 は **256 ビット（32 バイト）以上**を要求する。`openssl rand -hex 32` は 64 文字を出力し、
すべて ASCII なのでちょうど 64 バイトである — 十分である。

| | |
|---|---|
| ⚠️ **Base64 で作らないこと** | コードは Base64 デコードをしない。`openssl rand -base64 32` は 44 文字（44 バイト）なのでたまたま通るが、`+` `/` `=` が YAML・シェル・環境変数を経由する間に事故を起こす。hex にはそういう文字がない |
| ⚠️ **32 バイト未満なら起動失敗** | jjwt が `WeakKeyException` を投げる。静かに弱いキーで動くよりはましである |
| ⚠️ **2 つは別の値** | 署名キーとハッシュの pepper は用途が異なる。1 つが漏れたら両方漏れる状況を作らない |
| ⚠️ **gateway-server と user-service は同じ `JWT_SECRET_KEY`** | 片方だけ変えると、発行したトークンをもう片方が拒否する。Secret 1 つを共有するので自動的に一致する |
| ⚠️ **変えると既存のトークンがすべて無効** | ログイン中のユーザーが全員再ログインする必要がある（docs/30） |
| pepper を変えると | 進行中の認証番号が無効になる。コードは 5 分間有効なので実質的に無害である |

> **`k8s/20-secret.example.yaml` のプレースホルダーはわざと短い。** 以前の値
> （`REPLACE_ME_with_a_long_random_string`、36 文字）は 32 文字を超えていたので、**埋めなくてもそのまま
> 動作していた** — リポジトリで公開されたキーでトークンに署名する状態で起動する。今は埋めないと
> 起動自体が失敗する。

```bash
kubectl apply -f k8s/20-secret.yaml
```

### メール送信と認証ゲート — 順序が重要である

デフォルトの状態では、メールは**ログに出力されるだけで送られない**（`MAIL_PROVIDER=logging`）。
Secret に `MAIL_*` の 4 つの値を埋めるだけでは何も起きない — local
オーバーレイの `patch-mail.yaml` がスイッチである（Gmail のアプリパスワードなどの注意事項はそのファイルのコメント）。

そして**登録したアカウントはデフォルトで即座に有効化される。** メール認証がゲートになるには
`APP_REQUIRE_EMAIL_VERIFICATION=true` が必要だが、⚠️ **必ずこの順序で**:

1. `patch-mail.yaml` で SMTP を有効にする（`MAIL_FROM` = SMTP アカウントのアドレス）
2. 1 回登録してみて**メールが実際に届くか**を確認する
3. その後で初めて user-service に `APP_REQUIRE_EMAIL_VERIFICATION=true` を与える

順序を入れ替えると — メールが送られない状態でゲートだけを有効にすると — **登録はできるのに誰も
ログインできない。** ロックされたアカウントの非常脱出口:
`UPDATE users.users SET enabled = true WHERE email = '...';`

### ソーシャルログイン（任意）— Google · GitHub · Facebook · Kakao · LINE

資格情報を入れたプロバイダーだけが有効になる。1 つも入れなければ、ログイン画面にボタン自体が
ない（サーバーの `/api/auth/public/oauth2/providers` が空のリストを返す）。

**プロバイダーのコンソールでアプリを作るとき、redirect URI は正確にこの形である:**

```
https://<도메인>/user-service/login/oauth2/code/google      ← google | github | facebook | kakao | line
```

⚠️ 1 文字でも違うと `redirect_uri_mismatch` で同意画面から弾かれる。よくあるミス:
`www.` の有無の不一致、末尾のスラッシュ、http/https。

| プロバイダー | 発行元 | 注意 |
|---|---|---|
| Google | Cloud Console → API とサービス → 認証情報 → OAuth クライアント ID（ウェブ） | 同意画面の構成が必要。テストモードならテストユーザーだけがログインできる |
| GitHub | Settings → Developer settings → OAuth Apps | callback URL は 1 つしか登録できない |
| Facebook | Meta for Developers → アプリ → Facebook ログイン | 開発モードならアプリのロールを持つアカウントだけがログインできる |
| Kakao | Kakao Developers → マイアプリケーション → アプリ作成。**client-id = REST API キー**、カカオログインの有効化 + Redirect URI 登録、[セキュリティ] Client Secret を発行後 **「使用する」** | 同意項目で **カカオアカウント（メールアドレス）** を有効にする必要がある — メールの同意項目はビズアプリ（個人開発者も切り替え可能）でなければ使えない。メールがないとログインが `email_required` で拒否される |
| LINE | LINE Developers → Provider → **LINE Login チャネル**。client-id = チャネル ID、client-secret = チャネルシークレット。Callback URL を登録 | **OpenID Connect → メールアドレス取得権限（Email address permission）を申請して承認**を受ける必要がある — メールは ID トークンにしか載らない。チャネルが Developing 状態ならチャネル管理者・テスターだけがログインできる（Published に切り替え） |

受け取った値を Secret に入れ（`k8s/20-secret.yaml`、キー名は `20-secret.example.yaml` 参照）、
user-service を再起動する:

```bash
kubectl apply -f k8s/20-secret.yaml
kubectl -n ecommerce rollout restart deploy/user-service
```

動作の仕組み（コードを直すときに知っておくべきこと）:

- ボタン → `/user-service/oauth2/authorization/{provider}` **ページ全体の遷移** →
  同意画面 → コールバックで**通常ログインと同じ REFRESH クッキー**を設定してホームへリダイレクト →
  SPA が新たに起動し、`/api/token/refresh` でセッションを復元する。アクセストークンを URL で
  渡さない
- アカウントの連携は**検証済みのメールアドレス**基準である。同じメールアドレスの既存アカウントがあれば、そのアカウントで
  ログインされる（ロールを含む — 管理者がソーシャルで入ってきても管理者である）。未検証のメールアドレスは拒否する —
  信じてしまうと、他人のメールアドレスでソーシャルアカウントを作ってローカルアカウントを乗っ取れる
- **2FA を有効にしたアカウントはソーシャルログイン後も 2FA を通る** — 成功ハンドラーがセッションの代わりに 5 分間有効な保留トークン（httpOnly
  `PENDING_2FA`、別キーで署名）を設定して `/signin?oauth2fa=1` へ送る。コードが合って初めて REFRESH が出る（ソーシャルボタンが
  2FA の抜け道にならないように）
- GitHub はメールアドレス非公開のアカウントが多いので `/user/emails` フォールバックがある（`user:email` scope）
- **Google・LINE は OIDC 経路**（`openid` scope）で処理される — アカウント連携は `CustomOidcUserService` が OAuth2 経路と同じ
  `CustomOAuth2UserService.resolve` で行う。LINE の ID トークンは **ES256** なので、アルゴリズムを登録ごとに選ぶ
  （`SecurityConfig#idTokenAlgorithm`）。🐛 この連携がなかった頃は、Google ログインは有効にした瞬間から失敗していた（ID トークン検証
  ライブラリ `spring-security-oauth2-jose` も抜けていた）
- 失敗は理由コードを持ってログイン画面に戻ってくる（`?error=email_required|unverified_email|access_denied`、
  `OAuth2FailureHandler`）。それ以外の失敗は `oauth_failed` 1 つ — プロバイダーのエラー説明は URL に載せない
- Kakao は `is_email_valid`・`is_email_verified` が両方 true のときだけ既存アカウントに連携する（再利用・未認証のアドレスで
  他人のアカウントを奪えないように）

---

## 8. 【Ampere】 エンドポイント + デプロイ

### 残るインフラだけを起動する

Kafka と Elasticsearch はクラスターに残る（docs/32 §3）。残りの 3 つは消す。

⚠️ **`90-dev-infra.yaml` は Secret を参照するだけで作らない。** ファイルヘッダーに前提として
書かれているが（`redis/redis-auth` など）、それを作るのは `ops/13`（開発用スクリプト）だけなので、
この Runbook どおりにだけ進めると **redis ポッドが `CreateContainerConfigError` で永遠に止まる。**

ここで残す 3 つのうち Secret が必要なのは **redis だけ**である（Kafka・ES はなくても起動する）。
パスワードはアプリが送る値と同じでなければならない — `spring.data.redis.password` が
`${LOCAL_DB_PASSWORD}` なので、**DB のパスワードと同一**である:

```bash
PW=$(kubectl -n ecommerce get secret ecommerce-secrets \
       -o jsonpath='{.data.LOCAL_DB_PASSWORD}' | base64 -d)
echo "길이=${#PW}"                        # 0 なら先に §7 を終わらせる

kubectl -n redis create secret generic redis-auth --from-literal=password="$PW" \
  --dry-run=client -o yaml | kubectl apply -f -
unset PW
```

```bash
kubectl apply -f k8s/90-dev-infra.yaml     # 単独ファイルなのでオーバーレイから参照できない
kubectl -n postgres delete deploy postgres
kubectl -n mongodb  delete deploy mongodb
# ⚠️ redis は消さない — クラスターに残す（docs/32 §6）

# 実際に起動したか確認する。Service だけあってポッドがないと、アプリが Connection refused で死ぬ
kubectl -n redis rollout status deploy/redis --timeout=120s
kubectl -n redis get endpoints redis       # <none> ならまだである

# Elasticsearch（200m/768Mi）は受け取ったサイズによって分かれる
kubectl -n elasticsearch delete deploy elasticsearch   # ← 2 OCPU なら消す
```

**4 OCPU なら Elasticsearch を残してもよい。** 韓国語の形態素検索（nori）を実際に確認
したいなら残すほうがよい — `SEARCH_ENGINE=postgres` のデフォルト値ではその経路を通れない。
2 OCPU なら消す（`oci` オーバーレイが `SEARCH_ENGINE: postgres` を固定している理由である）。

### 値を埋める

| ファイル | 変えるもの |
|---|---|
| `k8s-overlays/external/patch-external-endpoints.yaml` | `POSTGRES_HOST` → Neon のホスト名。**ホスト名だけ** — 下記参照 |
| `k8s-overlays/prod/kustomization.yaml` | `REPLACE_ME_OWNER` → GHCR のユーザー名 |
| `k8s/10-configmap.yaml` | `APP_PUBLIC_URL` → 実際のドメイン（会員登録確認メールのリンクに使われる） |
| `k8s/60-ingress.yaml` | `host: shop.example.com` → 実際のドメイン |

> ⚠️ **上の 4 つのファイルはすべて git が追跡している。** ところが埋める値（ドメイン・Neon ホスト・GHCR
> ユーザー名）はコミットしないほうがよい — シークレットではないが、攻撃対象領域を広げる。つまり**追跡される
> ファイルにコミットしてはならない値を入れた状態**で運用することになる。2 つのことがついてくる:
>
> - `git pull` が衝突したり、ローカルの変更を上書きしたりしうる → **`git pull --autostash`** で取得する
> - `git add -A` すると**そのままコミットされる** → インスタンス上ではコミットしないのが最も安全である。
>   どうしても必要なら `git add <파일>` で個別に指定し、先に `git diff --cached` を見る
>
> `git update-index --skip-worktree <파일>` で完全に隠すこともできるが、そうすると**上流で
> そのファイルが変わったときに pull が失敗する**（メッセージは原因をよく教えてくれない）。このリポジトリは
> まだそれらのファイルを直している最中なので、今は `--autostash` のほうを勧める。

#### `POSTGRES_HOST` はどう確認するか

**§2 で使った `ADMIN_URL` にすでに入っている。** コンソールから目で写し取らずに抜き出す:

```bash
echo "$ADMIN_URL" | sed -E 's|.*@([^/:?]+).*|\1|'
# → ep-<이름>-<ID>.<리전>.aws.neon.tech   ← 実際の値が出る。この例をコピーしないこと
```

（シェルを新しく開いたなら、Neon コンソール → プロジェクト → **Connection Details** から接続文字列を
もう一度取得して同じ方法で抜き出す。）

⚠️ **ホスト名だけを**入れる。次のものはすべて入ってはならない:

| 入れないもの | 理由 |
|---|---|
| `postgresql://` | ベースが `jdbc:postgresql://` をすでに付けている |
| `사용자:비밀번호@` | 資格情報は Secret に行く。ConfigMap はコミットされる |
| `:5432` | `POSTGRES_PORT` が別にある |
| `/neondb` | 各サービスが自分の DB 名を付ける（`userdb`・`productdb` …） |
| `?sslmode=require` | ベース URL にすでにある |
| `&channel_binding=require` | **libpq 専用のオプションである。** JDBC は未知のパラメータとみなして失敗する |

⚠️ **`-pooler` が付いたアドレスは使わない。** PgBouncer の transaction モードがサーバーサイドの
プリペアドステートメントと衝突し、`prepared statement "S_1" already exists` が散発的に
出る（docs/32 §9、未解決として残した項目）。確認:

```bash
case "$(echo "$ADMIN_URL" | sed -E 's|.*@([^/:?]+).*|\1|')" in
  *-pooler.*) echo "⚠️ 풀러 주소다 — 직결 주소를 쓸 것" ;;
  *) echo "직결 주소 ✓" ;;
esac
```

> **MongoDB はここで埋めるものがない。** Atlas への接続はもっぱら Secret の
> `SPRING_DATA_MONGODB_CLIENT`（§7）1 つで決まる。以前はこのファイルに
> `MONGODB_HOST: REPLACE_ME.mongodb.net` があったが、**そのキーを読む箇所がなかったので**削除した。

**埋めた値が実在するかを確認する — ただし DNS では駄目である。**

⚠️ **`getent hosts`・`dig` は Neon では何も検証できない。** ワイルドカード DNS なので
`*.ap-southeast-1.aws.neon.tech` の下の**どんな名前でも解決される**（実測: `ep-obviously-
not-real-zzz999.…` も解決された）。実際のルーティングは TLS の **SNI** で行うので、エンドポイントが
存在するかどうかは**接続してみないと**分からない。

```bash
H=$(grep -oP '^\s*POSTGRES_HOST:\s*"\K[^"]+' k8s-overlays/external/patch-external-endpoints.yaml)
echo "HOST=[$H]"
printf 'product 비밀번호: '; read -rs PW; echo

PGPASSWORD="$PW" psql -X "postgresql://product@$H/userdb?sslmode=require" -c 'select 1'
unset PW
```

| 結果 | 意味 |
|---|---|
| `1` が出る | ✅ ホスト・パスワード・DB すべて正しい |
| プロキシが接続を拒否/終了する | **エンドポイントがない** — タイプミスかプレースホルダーである |
| `password authentication failed` (28P01) | エンドポイントは正しい。パスワードだけが間違っている |
| `database "userdb" does not exist` | エンドポイントは生きているが、プロビジョニングされていない |

3 つのエラーが**それぞれ別の層**を指しているという点が重要である。28P01 が出たなら、ホストを
探り直す理由はない。

> ⚠️ **文書の例のホストをそのままコピーしないこと。** 実際にそういう事故が起きた — 例が
> 本物のような形をしていたので（`ep-<단어>-<단어>-<hex>.<리전>.aws.neon.tech`）目立たなかった。
> そこで例を `ep-<이름>-<ID>.<리전>` の形に変えた。本物の値は**必ずコンソールか
> `ADMIN_URL` から**取得する。
>
> ⚠️ `-pooler` を消すときに**ドットまで一緒に消さないよう**注意する。
> `ep-xxx-pooler.c-3.…` → `ep-xxx.c-3.…` である。`ep-xxxc3.…` になると DNS が失敗する。

**埋めたかどうかを apply の前に確認する。** 埋めずに apply するとポッドが `InvalidImageName` で
止まるが、そのメッセージは**どの値が空なのかを教えてくれない**:

```bash
kustomize build k8s-overlays/external | grep -n REPLACE_ME
```

何も出なければ合格である。`REPLACE_ME_TUNNEL_ID` 1 つだけが残るのは正常である —
それは `ops/24` がクラスター内で置換する（§9）。

> **なぜ `InvalidImageName` なのか。** `REPLACE_ME_OWNER` には**大文字がある。** コンテナ
> イメージのパスは小文字しか許さないので、kubelet は名前をパースすらできない。「イメージが
> 見つからない」（`ImagePullBackOff`）とは別の状態である — レジストリに行ってすらいないという意味であり、
> だからログインやパッケージの公開設定をいくら確認しても原因は出てこない。
>
> ```bash
> sed -i 's/REPLACE_ME_OWNER/<GHCR 사용자명 소문자>/g' k8s-overlays/prod/kustomization.yaml
> ```

### バックアップ CronJob — external では半分だけ使う

`k8s/85-backup-cronjobs.yaml` はオーバーレイになく、別途適用するファイルである。**external
構成では 2 つの立場が正反対**なので、そのまま適用してはならない:

| CronJob | external では | 理由 |
|---|---|---|
| `postgres-backup` | **使わない** | `pg_basebackup` は replication プロトコルで接続するが、Neon はその接続を開けてくれない。バックアップ・PITR は provider の責任になる（docs/32 §5） |
| `restore-drill` | **使わない** | 上が残したバックアップを戻してみる訓練である。復元するものがない |
| `mongo-backup` | **必要** | **Atlas M0 にはバックアップがない**（§2b）。ここで取らなければバックアップがまったくない状態である |

`postgres-backup` と `restore-drill` はマニフェストに **`suspend: true` で入っている** —
external ではそのままにしておけばよい。クラスター内の PostgreSQL を使うなら、`ACTION=check
./ops/08-backup-postgres.sh` を通してから自分で有効にする。

`mongo-backup` だけ資格情報を入れて生かす:

⚠️ **`read` は必ず単独の 1 行で実行する。** 複数行を一度に貼り付けると、シェルが
**次の行を値として読み込んで**しまい、画面に表示されないので気づけない。Secret は `created` で
問題なく作られ、バックアップだけが静かに失敗する — 実際のデプロイで経験した。

```bash
read -rs MONGO_URI          # ← この行だけ実行して Enter、プロンプトに URI を入力
```

値が入ったら残りを実行する:

```bash
kubectl -n ecommerce create secret generic backup-credentials \
  --from-literal=MONGO_URI="$MONGO_URI" \
  --dry-run=client -o yaml | kubectl apply -f -
unset MONGO_URI

# 値を見ずに確認 — スキームと長さだけを見る
kubectl -n ecommerce get secret backup-credentials \
  -o jsonpath='{.data.MONGO_URI}' | base64 -d | cut -c1-14; echo

# スケジュールを待たずに今一度実行してみる —「起動している」と「ダンプが出る」は違う
kubectl -n ecommerce create job --from=cronjob/mongo-backup mongo-backup-verify
kubectl -n ecommerce logs job/mongo-backup-verify --follow
```

⚠️ **`backup-credentials` はマニフェストが作らない。** なければポッドがコンテナを
作る前に `CreateContainerConfigError` で止まり、**ログがない** — `redis-auth` と
まったく同じ罠である。実際のデプロイで 2 つのジョブが 31 時間その状態で放置された。

すでに失敗したジョブが残っていれば消す:

```bash
kubectl -n ecommerce delete job -l job-name --field-selector status.successful=0 2>/dev/null
kubectl -n ecommerce get jobs
```

### あなたの値は `local` オーバーレイに置く — 追跡ファイルには書かない

ここまでのオーバーレイ（`prod` → `external`）は誰でもそのまま使う。最後の 1 層にだけ
**この環境にしかない値**が入る: GHCR の所有者、イメージタグ、Neon ホスト、公開ドメイン。

⚠️ それらの値を `prod/kustomization.yaml`・`patch-external-endpoints.yaml`・`10-configmap.yaml`
のような**追跡対象のファイルに直接書かないこと。** 実際のデプロイでこうなった:

- `git pull` が毎回衝突する — 値を埋めた行とリポジトリが直した行が同じ行である
- 衝突マーカーが残ると kustomize がファイル全体を拒否する
  （`trouble configuring builtin PatchTransformer`、`ScannerError`）
- 元に戻すと実際のホストが消えて `28P01`・`InvalidImageName` で死ぬ
- 何よりも**ホスト名とレジストリの所有者がコミットされかけた**

```bash
cp -r k8s-overlays/local.example k8s-overlays/local     # local/ は gitignore される
$EDITOR k8s-overlays/local/kustomization.yaml           # REPLACE_ME_OWNER、タグ
$EDITOR k8s-overlays/local/patch-local-endpoints.yaml   # POSTGRES_HOST, APP_PUBLIC_URL

# ⚠️ 適用前の確認。REPLACE_ME_TUNNEL_ID の**1 行だけ**が残っていなければならない —
#    それは ops/24 がクラスターで置換するので、ここでは埋めない
kubectl kustomize k8s-overlays/local | grep REPLACE_ME

kubectl apply -k k8s-overlays/local
```

2 OCPU のインスタンスなら、`local/kustomization.yaml` の `resources` を `../oci` に変える。

シークレットはここに置かない — パスワード・接続文字列は `k8s/20-secret.yaml`（同じく gitignore）が
担当する。このオーバーレイに入るのは**シークレットではない識別子**だけである。

### デプロイ — 受け取ったサイズによってオーバーレイが分かれる

```bash
nproc && free -g          # まず確認する。§1 ②

# 4 OCPU / 24GB
kubectl apply -k k8s-overlays/external

# 2 OCPU / 12GB
kubectl apply -k k8s-overlays/oci
```

`oci` は `external` を継承してリソースだけをさらに絞ったものなので、4 OCPU で `oci` を使ってもよい
（余裕が残るだけである）。逆は駄目である。

> **初回デプロイでだけこうする。** 2 回目からは `kubectl apply -k` を直接打たず、
> **`./ops/28-deploy.sh <오버레이> <버전>`** を使う。同じ apply を行うが、前後に
> ① レジストリにそのバージョンが実際にあるかの確認、② user-service→gateway の順序の遵守、
> ③ スモークゲート、④ 失敗時の直前バージョンへの自動復帰が付く（docs/36）。
> 上のコマンドを手で打つと、その 4 つを人が覚えておかなければならない。
>
> 🔴 そして 5 つ目がある。**オーバーレイの `images:` には手で書いた `newTag` が
> 埋め込まれている。** デプロイが終わった後にそのファイルをそのまま apply すると、全サービスがそのタグに
> **戻ってしまう** — 失敗でもロールバックでもなく、書かれたとおりに適用されたのである。実際にそうやって数週間
> 前のフロントエンドが戻ってきて、原因を見つけるのに長くかかった（症状が「デプロイの問題」に見える）。
> ops/28 はデプロイするバージョンでタグを上書きするので、この事故は構造的に起きない。
>
> **設定だけを変えるとき**（ConfigMap・Secret・Ingress）も同じである。イメージはそのままで設定だけを
> 反映する専用の経路がある:
>
> ```bash
> ./ops/28-deploy.sh local --config-only                          # 適用のみ
> ./ops/28-deploy.sh local --config-only --restart delivery-service   # 値を読ませるには再起動
> ```
>
> ConfigMap にはハッシュサフィックスがないので、変わってもすでに起動しているポッドは読まない。`envFrom` は
> **起動時点で**一度だけ読む — だから `--restart` が必要である。

### ポッドがイメージを取得できないとき

`ImagePullBackOff` は**名前は正しいのに取得できなかった**という意味である（名前が間違っている場合は上の
`InvalidImageName`）。原因が 2 つあるので**実際のメッセージを見る必要がある** — ポッドの状態だけでは区別できない:

```bash
kubectl -n ecommerce get events --sort-by=.lastTimestamp | grep -i "failed to pull" | tail -3
```

典型的なメッセージはこれである:

```
failed to fetch anonymous token: unexpected status from GET request to
https://ghcr.io/token?scope=repository%3A<user>%2F…%3Apull&service=ghcr.io: 403 Forbidden
```

⚠️ **この 403 は 2 つのことを意味しうる。** GHCR はパッケージの存在有無を露出しないように、
**private の場合とまったく存在しない場合に同じ 403** を返す。メッセージだけでは切り分けられない。

切り分ける方法は**認証された状態で問い合わせること**である:

```bash
echo "$GHCR_PAT" | docker login ghcr.io -u <USER> --password-stdin
docker manifest inspect ghcr.io/<USER>/ecommerce-msa/order-service:1.0.0 >/dev/null \
  && echo "존재한다 → private 이 원인" \
  || echo "없다 → 아직 push 되지 않았다"
```

| 結果 | やること |
|---|---|
| **ない** | §6 を**すべて**実行する。`--only <서비스>` で 1 つだけテストしたなら、残りは存在しない — 初回デプロイで最もよくある |
| **存在する（private）** | パッケージを public に変えるか、pull secret を入れる（§6） |

GitHub → プロフィール → Packages で目でも確認できる。`ops/19` はサービス 12 個 + frontend =
**13 個**を作る（`config-server` はデプロイマニフェストにないがイメージは作られる —
上がっていても正常である）。

デプロイ後に実際の余裕を確認する — **計算とノードの現実は実際に違った**（推定 3,350m vs
実測 3,980m）。マニフェストだけを足すと、k3s 内蔵のポッドと cloudflared のレプリカが抜け落ちる:

```bash
kubectl describe node | sed -n '/Allocated resources/,/Events/p'
```

読み方:

| 行 | 意味 | 心配すべき値 |
|---|---|---|
| `cpu Requests` | **スケジューラーの予約台帳。** 実際の使用量ではない | **95% を超えるとローリングアップデートが止まる** |
| `cpu Limits`（555% など） | 上限の合計。ノードの容量を超えても**正常**である | それ自体は問題ではない |
| `memory Requests` | 同じ意味のメモリ予約 | 90% を超えるとポッドがスケジュールされない |

**リクエストが 99% でも CPU が忙しいわけではない。** Spring Boot サービスはアイドル時に 10〜50m を使う。
実際の使用量は `kubectl top nodes` / `kubectl top pods -A` で見る（metrics-server が必要）。

> ⚠️ **起動できなかったポッドもリクエストを占有する。** `ImagePullBackOff` · `CrashLoopBackOff` は
> **すでにノードに割り当てられた**状態である — スケジューラーが場所を確保した後でコンテナが失敗したので、
> 予約はそのまま残る。（`Pending` は割り当て自体がされていないので占有しない。）
>
> ここで二重に漏れる。イメージを取得できないと新しいポッドが Ready になれず、そうなると
> **古い ReplicaSet が片付かない。** 1 つのサービスが 250m ではなく 500m を確保する:
>
> ```bash
> kubectl -n ecommerce get rs | awk '$2>0'      # DESIRED>0 の ReplicaSet が 2 つならロールアウト未完了
> ```
>
> つまり**リクエスト使用率が高いことは、原因ではなく結果かもしれない。** リソースを調整する前に、
> ポッドがすべて Running かどうかをまず見る — イメージが取得できた瞬間に古い ReplicaSet が消え、
> 自然に下がる。

⚠️ 4 OCPU + `external` の組み合わせの実測は **3,980m (99%)** である。ポッドはすべて起動するが余裕が 20m なので、
`kubectl rollout restart` が `Pending` で止まる。余裕を作る方法は docs/32 §2。

### ⚠️ `k8s/70-autoscaling.yaml` は適用しない

prod オーバーレイが Deployment から `replicas` を消したので（HPA の所有）、HPA を適用しなければ
Kubernetes のデフォルト値である **1 レプリカ**になる。それがどちらの構成でも望む値である。
適用すると `minReplicas=2` × 11 サービスで 5,300m になり、**4 OCPU でも**足りない。

### レプリカを 2 に上げたいなら

レプリカ 1 では、このリポジトリがレプリカ 2 で捕まえてきた並行性バグを再現できない
（docs/31 §3）。主要なものを 2〜3 個だけ上げてその経路を踏んでみたいなら、**順序がある。**

**① まず CPU リクエストを下げる。** 実測の余裕が 20m なので（上記参照）、そのまま上げると新しいポッドが
`Pending` で終わる。方法は docs/32 §2。

**② `kubectl scale` ではなく HPA を直す。** HPA を適用しているなら、`kubectl scale` で
上げた値は **HPA が再び 1 に下げる**（CPU が目標値 70% 未満なら、安定化ウィンドウ 5 分後に縮小）。
変えるべき場所は HPA の `minReplicas` である:

```bash
# 商品サービスの名前は catalog-service である — product-service というものはない
kubectl -n ecommerce patch hpa order-service   -p '{"spec":{"minReplicas":2}}'
kubectl -n ecommerce patch hpa catalog-service -p '{"spec":{"minReplicas":2}}'
```

HPA をまったく適用していないなら `kubectl scale deploy ...` で十分である。

> サービス名はディレクトリ名と同じである: `user` `catalog` `cart` `order` `pay` `coupon`
> `chat` `notification` `delivery` + `-service`、そして `eureka-server` `gateway-server`。

2 OCPU では余地がない。そちらは `ops/12-staging-rehearsal.sh` で Linux 上で確認する。

`oci` オーバーレイがさらに絞るもの（docs/32 §2）:

| | external | oci |
|---|---|---|
| CPU リクエスト | 250m | **60m** |
| メモリリクエスト | 384〜512Mi | **256Mi** |
| CPU 上限 | 1000m | **800m** |
| メモリ上限 | 768〜1024Mi | **512Mi** |
| JVM ヒープ | 上限の 1/4（暗黙） | **`-XX:MaxRAMPercentage=60` を明示** |
| Tomcat スレッド | 200 | **50** |
| cloudflared | 2 レプリカ | **1** |
| Elasticsearch | 起動する | **起動しない**（`SEARCH_ENGINE=postgres` がデフォルト） |

⚠️ **レプリカ 1 では、このリポジトリが経験した並行性バグは再現しない**（docs/31 §3）。
それは `ops/12-staging-rehearsal.sh` で別途確認する。

```bash
kubectl -n ecommerce get pods -w
```

### DLT トピック + コンパクションの状態トピック

すべて Ready になった後で作る。

`ops/02-create-dlt-topics.sh` は実行場所を自分で探す — kubectl → docker → ローカル PATH
の順に試し、コマンド名も `kafka-topics`（Confluent）と `kafka-topics.sh`（Apache）の両方を見る。

```bash
ops/02-create-dlt-topics.sh --check     # 何を使うかの診断のみ
ops/02-create-dlt-topics.sh             # DLT 9 種 + order-saga-state（コンパクション）
```

（⚠️ このスクリプトは以前、`kafka-topics.sh` を PATH・`KAFKA_BIN`・docker のいずれかでしか探さず、
**k8s では動作しなかった。** この Runbook を書いている最中に見つけて直した。）

**`order-saga-state` は順序が重要である。** saga-projection（Kafka Streams）の出力トピックで、
`cleanup.policy=compact` でなければならないが、Streams はシンクトピックを作らない — 内部トピック
（変更ログ・リパーティション）だけを自分で作る。**アプリが先に起動するとブローカーが `delete` ポリシーで自動
作成してしまい**、その後でこのスクリプトを実行しても `--if-not-exists` は「すでにある」として静かに
スキップする。症状は `retention.ms`（デフォルト 7 日）が過ぎてから初めて現れる — 過去の注文の Saga の状態が
消え、プロジェクションを再構築するとその区間が空く。

そのためこのスクリプトは存在有無だけを見るのではなく、**`cleanup.policy` を実際に読んで違っていれば直す**
（`kafka-configs --alter`）。すでに消えた過去の状態は戻らないので、可能なら**アプリを起動する
前に**実行するほうがよい。直すことを望まなければ `FIX_CONFIGS=0` で警告だけを受け取る。

```bash
ops/02-create-dlt-topics.sh --check          # 診断のみ
FIX_CONFIGS=0 ops/02-create-dlt-topics.sh    # 既存のトピックには触れず警告のみ
SKIP_STATE=1  ops/02-create-dlt-topics.sh    # DLT のみ
```

デプロイ後の確認は `ops/06-smoke-test.sh` の 6 番目の項目が行う — 存在ではなく**コンパクションかどうか**を見る。

---

## 9. 【Mac】 Cloudflare Tunnel

### 事前準備 — 登録しただけでは何もできない

**最も時間がかかる項目はコードではなく、ネームサーバーの伝播である。** レジストラでネームサーバーを
Cloudflare のものに変え、ゾーンが有効になるまで数分から 1 日かかる。それまでは
`cloudflared tunnel login` がドメインを**一覧に表示すらしない。** だからこれを最初にやる —
§1 でドメインを先に決めろと言った理由は、ここと、フロントエンドのビルドの 2 つである。

```bash
ops/24-cloudflare-tunnel.sh --preflight --hostname shop.example.com
```

確認できるものだけを確認し、残りはリストで示す:

| 確認する | どうやって |
|---|---|
| `cloudflared` のインストール | `command -v` |
| ログインしているか | `~/.cloudflared/cert.pem` の存在 — **中身は出力しない** |
| **ネームサーバーが Cloudflare か** | `dig NS` でゾーンの頂点を探して確認。`co.kr` のような 2 段 TLD もラベルを剥がしながら処理する |
| ホスト名がすでに応答しているか | 既存のレコードがあると、トンネルの CNAME を作れない |

`dig` がなければ、**合格ではなく「未確認」と言う。**

#### ダッシュボードで人が見るべきもの

スクリプトが読めない領域なので、合格したとは言わない。

| 場所 | 何 | 間違っていると |
|---|---|---|
| SSL/TLS → Overview | 暗号化モードが **Off でないこと**（Full なら十分） | エッジが TLS を切る |
| SSL/TLS → Edge Certificates | Universal SSL 有効（ゾーン有効化後 15 分ほど）、Always Use HTTPS をオン | 証明書エラー |
| Network | **WebSockets をオン** | **チャットだけが**静かに動かない。残りは正常なので原因を探しにくい |
| Caching | デフォルト値を維持 | 「Cache Everything」ルールを作ると `/api/*` がキャッシュされ、**他人のカートが見える** |

⚠️ 無料の証明書は `example.com` と `*.example.com` の**1 階層まで**しかカバーしない。
`a.b.example.com` には有料の証明書が必要なので、ホスト名は 1 階層にしておく。

⚠️ 無料プランのアップロード本文 100MB 制限は設定では超えられない。超えるとエッジが 413 を返し、
**アプリケーションのログには何も残らない**（§11 の症状表を参照）。

### トンネルを作る

6443 を開けていないので、SSH トンネルで `kubectl` をつなぐ。

```bash
scp opc@<IP>:/etc/rancher/k3s/k3s.yaml ~/.kube/oci-config
ssh -N -L 6443:127.0.0.1:6443 opc@<IP> &     # このトンネルを生かしたまま
export KUBECONFIG=~/.kube/oci-config
kubectl get nodes                                # 確認
```

k3s の kubeconfig はサーバーアドレスが `127.0.0.1:6443` なので、SSH トンネルとそのまま合う。

```bash
cloudflared tunnel login                         # ブラウザでドメインを選択
ops/24-cloudflare-tunnel.sh --hostname shop.example.com
ops/24-cloudflare-tunnel.sh --status
```

> **順序に注意:** `ops/24` は §8 の**後に**実行しなければならない。`cloudflared-config` ConfigMap が先に
> ないとトンネル ID を置換できない。順序が逆になると警告を出し、再実行するよう知らせてくれる。

トンネルの資格情報の Secret は**絶対にコミットしない。**

---

## 10. 確認して締める

```bash
curl -I https://shop.example.com                          # 200 でなければならない
GATEWAY=https://shop.example.com ./ops/06-smoke-test.sh
./ops/27-check-secret-drift.sh                            # 資格情報がまだ通用するか
```

`ops/27` は `ops/06` が**構造的に見られないもの**を見る。スモークは今起動しているポッドに
リクエストを送るが、それらのポッドは**起動時に読んだ資格情報を持っている。** Secret や DB 側の
パスワードがその後で変わってもすべて青信号で、**次のロールアウトで一斉に死ぬ。**
今回のデプロイでは、5 時間経ったポッドは Running なのに、新しく起動するポッドだけが CrashLoopBackOff だった。

値を比較せず、**クラスター内で実際にログインしてみる** — パスワードがローカルシェルに
出てこず、サービスが受け取る値とまったく同じ値を試すことになる。

**トンネル経由のとき、`ops/06` が何を見られて何を見られないかを知った上で見る必要がある。** 公開ホスト名
1 つでフロントエンドと API を一緒に受けるので、**ゲートウェイにルーティングされない経路はすべて
フロントエンドへ流れ、SPA はどんな経路にも index.html を 200 で返す。**

| 項目 | トンネル経由では | なぜ |
|---|---|---|
| §1 個別サービスの readiness | ⏭ 自動スキップ | 個別のポートはクラスターの外に出ない。`$GATEWAY` がリモートならスクリプトが自動でオフにする（`SKIP_DIRECT`） |
| §2 `gateway /actuator/health` | ⏭ スキップ | actuator は**わざと公開していない。** クラスター内で見る（下記） |
| §2 `/fallback` | ⏭ スキップ | Resilience4j がゲートウェイの**内部で** `forward:/fallback` する。外部から届かないのが正常 |
| §3 レートリミッター 429 | ✅ 実測 | `/catalog-service/...` はトンネルのルールに合ってゲートウェイまで行く — **ゲートウェイが生きているという本当の証拠はこれである** |
| §7 カタログ照会 | ✅ 実測 | 同じ理由 |

⚠️ 以前は §2 がステータスコードだけを見ていたので、`✅ gateway health 200` で**合格**していた。ゲートウェイには
目もくれず、フロントエンドの HTML を受け取っていたのである。今は本文を見て誰が応答したかを
見分けた上で、スキップとして扱う。

ゲートウェイのヘルスはクラスター内で見る:

```bash
kubectl -n ecommerce exec deploy/gateway-server -- \
  curl -sk https://localhost:8000/actuator/health
```

スモークが通ったら、次は**人が押してみる番**である — `ops/06` はヘルス・レートリミッター・
Eureka・DLT・カタログ照会までしか見ず、**注文・決済は見ない。**
巡回経路は [docs/34 受け入れチェックリスト](34-acceptance-checklist.md)。

その後で必ず:

- **Neon · Atlas のコンソールでアクセス IP を Ampere のパブリック IP に制限。** 無料ティアなので制限が
  不可能なら、それはパスワードが唯一の防御線だという意味である
- OCI Security List の 22 番も自分の IP に制限
- `ops/20-scan-history-secrets.sh --remote` でもう一度確認

---

## 11. よく詰まるところ

### Linux が WSL なら — 最初に読むこと

**`/usr/bin/env: ‘bash\r’: No such file or directory`**

Windows の git は `core.autocrlf=true` がデフォルトなので、チェックアウト時に LF を CRLF に変える。
するとシバンが `#!/usr/bin/env bash\r` になり、カーネルが `bash\r` というプログラムを探して失敗する。
症状がスクリプトの内容と無関係なので原因を探しにくく、**1 つのファイルを直しても、それが呼び出す次の
スクリプトでまったく同じように止まる**（`ops/13` → `ops/07` で実測）。

`.gitattributes` が `*.sh` を `eol=lf` に固定しているが、そのルールができる**前に**クローンしたなら
すでに CRLF で展開されている。展開し直す:

```bash
git rm --cached -r . >/dev/null && git reset --hard        # .gitattributes を基準に再正規化
git ls-files -z | xargs -0 grep -lIU $'\r' || echo "CR 없음"
```

⚠️ **`/mnt/c`、`/mnt/d` ではなく WSL ネイティブのファイルシステムにクローンすること。**

```bash
cd ~ && git clone <repo> && cd ecommerce-msa
```

Windows のドライブ（DrvFs）では (1) Windows 側の git が CRLF で上書きし直しうる、
(2) 実行権限がきちんと維持されない、(3) Gradle のビルドが数倍遅い。
`ops/13` は docker を privileged で起動するので、ネイティブのファイルシステムが安全である。

ビルドがこのミスを捕まえる — ガードレール **B-36** が `*.sh` 内の CR を見つけると失敗する。

### 接続・プロビジョニング

| 症状 | 原因 |
|---|---|
| `invalid connection option "‘postgresql://…sslmode"` | 値の先頭に丸引用符が混ざった。URI で始まらないと libpq が キー=値 として読む。引用符を手で入力すること |
| `must be able to SET ROLE "product"` | 管理者アカウントがスーパーユーザーではない。`ops/25` がメンバーシップを受け取って処理する（最新版） |
| `permission denied to create database` | `ADMIN_URL` が `product` を指している。`neondb_owner` に変える |
| 3 段階目でだけ失敗 / `cannot run inside a transaction block` | プーラー（`-pooler`）のアドレスである。直結アドレスを使う |
| `must be owner of schema probe_ecommerce_msa` | 前回実行の残骸。所有者アカウントで `DROP SCHEMA … CASCADE` |
| `connection to server on socket "/var/run/postgresql/..." failed` | **リモートに行ったことがない。** URI のホストが空で、libpq がローカルソケットにフォールバックしたのである — シェル変数（`$HOST`・`$ADMIN_URL`）が空でないか見る |
| `You must install at least one postgresql-client-<version> package` | psql 未インストール。`apt-get install -y postgresql-client` / `dnf install -y postgresql` |

### デプロイ

| 症状 | 原因 |
|---|---|
| ポッドが `Pending` · `Insufficient cpu` | ノードの CPU **リクエスト**が埋まった。`kubectl patch` で下げた値は `apply -k` で上書きされるので、`patch-java-requests.yaml` で変える。その他に場所を食うもの: cloudflared 2 レプリカ（§9 の前なら 0 に）、復活した postgres・mongodb、HPA が作った余分なレプリカ、ロールアウトが終わらずに残った古い ReplicaSet |
| イメージを直して push したのに**そのまま** · `already present on machine` | `imagePullPolicy` が `IfNotPresent` なので、同じタグは再取得しない。**タグを上げる** — §6 |
| **frontend** だけ `CreateContainerConfigError`（`non-numeric user`） | イメージの `USER` が名前だと、kubelet が `runAsNonRoot` を**判定できず**拒否する。`Dockerfile.frontend-runtime` を `USER 1000` に直した（Java イメージは元から数値だった）— frontend だけビルドし直して push すればよい |
| **backup CronJob** が `CreateContainerConfigError` | `backup-credentials` Secret がない。⚠️ **external 構成では postgres-backup はまったく使えない** — `pg_basebackup` は Neon に通じない（バックアップは provider の責任）。mongo-backup は逆に**必要である**（Atlas M0 にはバックアップがない）。§8 参照 |
| redis ポッドが **`CreateContainerConfigError`** | `redis-auth` Secret がない。`90-dev-infra.yaml` は参照するだけで作らない — §8 の作成コマンド。コンテナを作る**前に**失敗するのでログもない |
| `CrashLoopBackOff` + **`RedisConnectionException` · `Connection refused ...:6379`** | **redis Deployment がない。** PG・Mongo と一緒に消しがちである — §8 参照。Redis を使うサービスが **7 つ**（cart・catalog・chat・notification・order・pay・user）なので、半分以上が死ぬ。⚠️ Service は残っているので**名前解決と ClusterIP は正常**であり、DNS の問題には見えない: `kubectl -n redis get endpoints redis` が `<none>` なら確定 |
| 会員登録が **400 `Error: Role is not found.`** | `users.roles` テーブルが**空である。** ロールを投入していたコードは `SecurityConfig` でコメントアウトされていて、マイグレーションにも入れていなかったので、**まっさらな DB では誰も登録できなかった** — 最初のユーザーがいないので管理者も作れない。`V3__seed_roles.sql` が ROLE_USER・ROLE_ADMIN を投入する。すでに起動した DB なら user-service を再起動して Flyway を実行する: `kubectl -n ecommerce rollout restart deploy/user-service` |
| `CrashLoopBackOff` + **`password authentication failed for user 'product'`**（28P01） | `LOCAL_DB_PASSWORD` が `ops/25` に入力した **product** のパスワードと違う。⚠️ ここまで来たならホスト・TLS・ロールはすべて合っている — パスワードだけ直せばよい。§7 の psql 確認手順 |
| `CrashLoopBackOff` + **`keystore password was incorrect`** | `LOCAL_KEY_STORE_PASSWORD` が `certs/certs.env` の値と違う。ファイルのマウントは正常である（見つからなかったなら `KeyStore not exists` だった）。§7 の patch コマンドで 4 つを一度に合わせる |
| `CrashLoopBackOff` + **`KeyStore not exists`** | こちらはファイルの問題である。`tls-keystore` Secret がないか、ファイル 8 個が全部入っていない — `ops/07` の後に `kubectl apply -f certs/k8s-tls-keystore.secret.yaml` |
| ポッドが **`InvalidImageName`** | イメージ名が文法に合っていない — レジストリに行ってすらいない。たいてい `REPLACE_ME_OWNER` がそのまま残っている（**大文字**なのでパスに使えない）。ログイン・パッケージの公開設定をいくら見ても原因は出てこない。§8 の `grep REPLACE_ME` |
| ポッドが **`ImagePullBackOff`** + `anonymous token … 403` | 名前は正しいのに取得できなかった。⚠️ GHCR は **private の場合とまったく存在しない場合に同じ 403** を返す — `docker manifest inspect` で切り分ける必要がある（§8） |
| フロントが古い API アドレスへ行く | `REACT_APP_API_URL` は**ビルド時点**の値である。§6 からやり直し |
| コンソールに **`Cross-Origin Request Blocked`** + 403（`token/refresh` など） | ページが `REACT_APP_API_URL` と**違うアドレス**（www なし・http）で開かれた。同一オリジンならブラウザは CORS チェックをしない。アドレスバーを確認 → §6 のルート→www リダイレクト |
| cloudflared ポッドが起動せず `Rule #N is matching the hostname ''` | ingress ルールのうち **catch-all が最後にない。** そうなると cloudflared は起動を拒否する。ルールを直したら、まず `cloudflared --config <파일> tunnel ingress validate` で確認すること |
| `curl` が **522** — なのに cloudflared ポッドは `1/1 Running` | ⚠️ **ポッドが Ready であることは「エッジにつながった」という意味にすぎず、ユーザーが届くという意味ではない。** コネクターが正常なのに 522 なら、**DNS レコードがこのトンネルを指していない。** ドメインを Cloudflare に移すとき以前のネームサーバーの **A レコードが丸ごとインポート**され、それが残っていると `cloudflared tunnel route dns` が失敗して CNAME ができない。Cloudflare はその死んだ IP に接続しようとして 522 を出す。ダッシュボード → DNS → Records で `CNAME → <tunnel-id>.cfargotunnel.com (Proxied)` かどうかを確認し、違えば `cloudflared tunnel route dns --overwrite-dns <터널> <호스트>`。⚠️ プロキシされたレコードは `dig` で Cloudflare の IP しか見えないので、**外からは区別できない** — ダッシュボードを見る必要がある |
| `curl` が **530** | DNS はトンネルを指しているが、コネクターが 1 つもない。`kubectl -n ecommerce get deploy cloudflared` が `0/0` ならそれである |
| トンネルはつながったのに **404** | Ingress の `host` の不一致。デフォルト値が `shop.example.com` だった頃、これを置き換えないと**デプロイはすべて成功したように見え**、ブラウザでだけ 404 が出ていた。今は `k8s/60-ingress.yaml` に `host` をまったく置かない（すべてのホストにマッチ）。古い値がクラスターに残っていれば: `kubectl -n ecommerce patch ingress ecommerce-frontend --type=json -p='[{"op":"remove","path":"/spec/rules/0/host"}]'`。クラスター内ですぐ確認するには `kubectl -n ecommerce run curltest --rm -it --restart=Never --image=curlimages/curl -- curl -sS -m 10 -o /dev/null -w 'HTTP %{http_code}\n' -H 'Host: <도메인>' http://traefik.kube-system.svc.cluster.local:80/` |
| ポッドは Running なのに互いに通信できない | OCI の iptables REJECT。`ops/23` を実行していないか、再起動後にルールが飛んだ |
| Elasticsearch がすぐに死ぬ | `vm.max_map_count`。`ops/23` が上げる |
| 画像アップロードで 413 · **ログに何もない** | Cloudflare 無料プランのアップロード 100MB 制限。エッジで切られてアプリケーションまで来ない |
| 散発的な `prepared statement "S_1" already exists` | アプリケーションがプーラーのエンドポイントを使っている。JDBC URL に `&prepareThreshold=0` が必要である（docs/32 §9、未解決として残した項目） |
| ポッドが `Pending` から進まない | リソース超過。HPA の `minReplicas` を 1 に下げたか確認（§8） |

---

## 12. 検証状態 — 何を信じ、何を信じないか

**§1〜§8 は実際の OCI Ampere（4 OCPU/24GB、Ubuntu 24.04、arm64）で最後まで通過した。**
その過程でこの文書は何度も間違えており、間違えたものは下の §13 に残した。§9 以降はまだである。

| ステップ | 状態 |
|---|---|
| §2 `ops/25` | ✅ PostgreSQL 16 コンテナ + **実際の Neon**。管理者・非管理者・CREATEDB なし・残存スキーマの 4 つの状況を確認。マイグレーション 26 個を全量適用 |
| §2 Neon への実接続 | ✅ **実測。** ロール・DB 10 個を作成、アプリが接続して Flyway を通過 |
| §2b Atlas への実接続 | ✅ **実測。** クラスター作成 → 接続確認まで |
| §3 `gradlew test -PwithContainers` | ✅ 281 tests、失敗 0 |
| §3 `ops/13` | ✅ Linux で実行を確認（以前の作業） |
| §6 イメージビルド・GHCR push | ✅ **実測。** Ampere 上で arm64 ネイティブビルド、13 個 push |
| §7 証明書・シークレット | ✅ **実測。** `ops/07` の成果物で 12 サービスが起動 |
| §8 デプロイ | ✅ **実測。** external オーバーレイでサービスの起動を確認 |
| §8 `kustomize build` | ✅ 29 リソース、ConfigMap のマージを確認 |
| §4 `ops/26` | ⚠️ oci CLI をスタブにして**失敗分類 3 種**を確認。実際の OCI 呼び出しは未検証（インスタンスはコンソールで作った） |
| §5 `ops/23` | ⚠️ **部分的。** Ubuntu の経路のみが該当し、このデプロイでスクリプトをそのまま実行したかは確認されていない。RHEL 系の分岐は依然として未検証 |
| §9 `ops/24` の設定 | ✅ **実際の cloudflared 2026.7.3 で検証。** 3 つのオーバーレイの ingress ルールが `tunnel ingress validate` を通過、経路ルーティング（API→ゲートウェイ / 残り→traefik）も `tunnel ingress rule` で確認 |
| §9 `ops/24` の実行 | ⚠️ **部分的。** トンネル作成・DNS ルートまで進めた。エンドツーエンド（公開 URL の応答）は未完 |
| §10 `ops/06` スモーク | ❌ **未検証。** §9 の後のステップである |
| §8 `ops/02` DLT トピック | ✅ 実際の Confluent Kafka コンテナに 9 種の作成を確認。kube の分岐はコマンドの組み立てまで |

---

## 13. 実際のデプロイでこの文書が間違っていたこと

1 回の実デプロイで出てきたものである。同じ類のものがまだあるかもしれないので、パターンを書いておく。

| 何が間違っていたか | なぜ見えなかったか |
|---|---|
| `redis-auth` Secret の作成ステップがなかった | `90-dev-infra.yaml` のヘッダーに前提として書かれているだけで、作るのは `ops/13` だけだった。redis が `CreateContainerConfigError` で止まっても**ログがない** |
| Redis 削除の被害を「cart-service」と書いた | 実際には **7 サービス**が Redis を使う |
| 例のホストが本物のような形をしていた | `ep-solitary-mountain-a1b2c3.…` をそのままコピーして入れても目立たなかった |
| DNS でホストを検証しろと書いた | Neon は**ワイルドカード DNS** なので、どんな名前でも解決される。検証にならない検査だった |
| psql・`$ADMIN_URL` があると仮定した | それは §2【Mac】の状態である。§7 は【Ampere】である |
| パスワードの丸引用符を防いでいなかった | `read -rs` は画面に何も表示しない。長さチェックも通過する |
| `POSTGRES_HOST` を手で埋めさせた | 値を埋めたファイル 4 つがすべて git の追跡対象だったので `git pull` が衝突し、マーカーが残って kustomize が丸ごと拒否した。今は `k8s-overlays/local`（gitignore）が受け持つ |
| YAML を PyYAML で検証した | **PyYAML は重複キーを黙って許す** — 最後のものだけを残して先に進む。`paths:` が 2 回入った Ingress が通過してコミットされ、`kustomize build` を実行してようやく露見した（`mapping key "paths" already defined`）。マニフェストは**実際に使うツールで**検証しなければならない |
| Ingress の `host` のデフォルト値が `shop.example.com` だった | 置き換えを忘れても**デプロイはすべて成功したように見える。** ポッドは Running、Ingress も正常、トンネルもつながっていて、失敗はブラウザの 404 でしか現れない。今は `host` をまったく置かない |
| `ops/24` の DNS ルートが失敗を握りつぶした | `route dns … >/dev/null 2>&1` の後に *"이미 있거나 만들지 못했습니다"*（「すでにあるか、作成できませんでした」）という**区別のつかない**文を流し、成功したかのように終わった。ドメイン移管時にインポートされた古い A レコードが残っていると CNAME ができず、その結果が 522 である |
| 522 を「コネクターがない」とだけ書いた | コネクターが `1/1 Running` のままで 522 が出る。**ポッドの Ready は「エッジにつながった」であって「ユーザーが届く」ではない** |
| `ops/06` が `✅ gateway health 200` で合格した | ゲートウェイには目もくれていなかった。`/actuator/health` はトンネルのルールに合わずフロントエンドへ行き、**SPA はどんな経路にも index.html を 200 で返す。** ステータスコードだけを見る検査は、SPA の前ではすべて無意味である |
| §10 の `ops/06` コマンドに `SKIP_DIRECT` がなかった | `localhost:8080` を叩いて**偽の失敗が 7 個**出て、本当の問題（上の行）がその間に埋もれた。今は `$GATEWAY` がリモートなら自動でオフにする |
| `read -rs` を複数行の貼り付けブロックの中に入れた | シェルが**貼り付けた次の行を値として読み込んで**しまう。画面に表示されないので気づけず、Secret は `created` で問題なく作られる。この文書がそう書けと指示していた。`read` は必ず**単独の 1 行で**実行する |
| 空のバックアップが「完了」で終わった | `mongodump` は対象 DB が空でも成功し、`gzip -t` は空のアーカイブも通す。116 バイトのダンプが青信号だった。このプロジェクトは URI のパスではなく `spring.data.mongodb.name`（=`chat`）で DB を選ぶので、URI が別の DB を指していると**毎日成功する空のバックアップ**になる。サイズをメトリクスとして出力し、`MongoBackupEmpty` アラートをかけた |
| CronJob が出力するメトリクス名がアラートと違っていた | アラートは `ecommerce_mongo_backup_last_success_timestamp` を、CronJob は `mongo_backup_last_success_timestamp_seconds` を使っていた。**CronJob の経路ではバックアップのアラートが永遠に鳴らない。** 存在しないメトリクスに対する `time() - x` は静かに何もしない — アラートが鳴らないことと区別がつかない |
| バックアップジョブが `restartPolicy: OnFailure` だった | バックオフの上限に達すると job-controller が**ポッドを消す。** mongo-backup が 14 秒で失敗したのに、原因を見られなかった。`Never` なら失敗したポッドを残す。**バックアップがなぜ失敗したかは、バックアップが動くことと同じくらい重要である** |
| バックアップ CronJob を文書だけで停止させていた | `85-backup-cronjobs.yaml` を適用すると `postgres-backup` が**即座に有効な状態で**入ってくる。Neon では絶対に成功しえないのに。文書の停止コマンドを飛ばすと毎日失敗する。今はマニフェストが `suspend: true` で入ってきて、動くことを確認した人が有効にする |

**繰り返し現れる形が 1 つある。**「名前が解決される」は「そこに何かがある」を意味しないということ —
Neon のワイルドカード DNS、ポッドのない Service の ClusterIP、トンネルにつながっていない DNS レコード、
すべて同じ錯覚を生む。確認は**接続してみること**で行わなければならない。だから `ops/24` は今では
最後に公開 URL を実際に一度取得し、返ってきたコード（404/522/530/…）ごとに、どこが
切れているかを知らせてくれる。

**2 つ目の形もある。** 層が複数ある経路では、**各層がそれぞれ「正常」と報告する。**
Cloudflare（DNS）→ トンネル → traefik（Ingress）→ frontend の 4 つのどこが切れても、残りの 3 つは
青信号である。層を 1 つずつ飛ばしながら叩いてみるのが唯一の速い方法である:
クラスター内で traefik を直接 → トンネル経由で公開 URL → ブラウザ。
