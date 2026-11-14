# 31. 環境分離 · OCI Ampere · Cloudflare Tunnel

**日本語** | [한국어](31-kustomize-oci-tunnel.ko.md)

> 🕰️ **状態(2026-11-14) — 本文は 2026-07 〜 09 の設計記録です。OCI の本番は Cloudflare Tunnel の背後で動いており(§5 の「未検証」二つはその後実際に動いた)、デプロイは `ops/28-deploy.sh local <バージョン>` 一つです([docs/36](36-deployment-pipeline.md))。** 変わったこと: オーバーレイの連鎖の末尾にコミットされない `local`(← `external` ← `prod`、見本 `k8s-overlays/local.example`)が付き、実習用の上書きもできた(§2)。初回デプロイ後はオーバーレイに `kubectl apply -k` を直接しない — 手書きの `newTag` で全サービスが巻き戻る(§6)。HPA は `ops/28` が `prod` か `APPLY_HPA=1` のときだけ適用し、chat-service にも 2026-11-01 から HPA(1 〜 3)がある(§3)。

三つを一つのまとまりとして作った。三つとも「ノートパソコンでしか動いていなかったものを実際にどこかで立ち上げる」という
同じ目標に向かっている。

1. **kustomize オーバーレイ** — dev/prod の差分をマニフェストの複製なしに
2. **OCI Ampere k3s ブートストラップ** — arm64 の無料インスタンス 1 台に
   (⚠️ **2026-06-15 付で無料枠が 2 OCPU/12GB に半減した。** prod オーバーレイは
    もちろん external も収まらない — `k8s-overlays/oci` を使う。計算は docs/32 §2)
3. **Cloudflare Tunnel** — インバウンドポートを一つも開けずに外部公開

---

## 1. なぜ ALB + ACM ではなく Cloudflare Tunnel なのか

要望は「Cloudflare Tunnel **または** ALB → Ingress + ACM」だった。デプロイ先が OCI Ampere で
ある以上、選択肢にならない。

| | ALB + ACM | OCI LB | Cloudflare Tunnel |
|---|---|---|---|
| OCI インスタンスに付けられるか | ❌ AWS 専用 | ✅ | ✅ |
| インバウンドポート | 80/443 を開ける必要あり | 開ける必要あり | **0 個** |
| TLS | ACM (AWS 内でのみ) | 自前で発行・更新 | **Cloudflare が終端** |
| 無料枠 | ALB は有料 | 10Mbps 制限 | 無料 |
| グローバル IP の変更 | 気にする必要あり | 気にする必要あり | 無関係 |

ALB + ACM は **EKS に移行するとき**に意味を持つ。そのときは AWS Load Balancer Controller と
`ingressClassName: alb`、ACM ARN アノテーションが必要で、今作ったものとは重ならない。

トンネルの代償も明確だ:

- **Cloudflare 依存。** エッジ障害がそのままサービス障害になる
- **無料プランのアップロード 100MB 制限。** 商品画像がそれより大きいと 413 が返るが、
  **アプリケーションログには何も残らない** — エッジで切られるからだ
- クラスタ内には平文 HTTP で入ってくる(トンネル区間自体は暗号化されている)

> 🕰️ 現在(2026-11-14): 平文 HTTP はフロントエンド側(→ traefik:80)だけ。API の経路(`/<サービス>-service/…` · `/saga-projection/…`)は cloudflared がゲートウェイへ直接 `https://gateway-server…:8000` で送る(ゲートウェイ自身が TLS 終端、`noTLSVerify`)— `k8s-overlays/prod/cloudflared.yaml`、経路の照合は B-82。

---

## 2. オーバーレイ構造 — そしてなぜこの形なのか

```
k8s/                        マニフェスト原本 + kustomization.yaml (ベース)
k8s-overlays/dev/           レプリカ 1、リクエスト縮小、ローカルイメージ
k8s-overlays/prod/          replicas 削除(HPA が所有)、レジストリイメージ、cloudflared
k8s-overlays/external/      prod 継承 + マネージド PG/Mongo エンドポイント (docs/32)
k8s-overlays/oci/           external 継承 + 2 OCPU/12GB に絞り込み (docs/33 §4)
```

`k8s/base/` + `k8s/overlays/` という標準的な配置では**ない。** 二度つまずいた。

**(1) kustomize は kustomization ディレクトリ外の個別ファイルを参照できない。**

```
security; file '.../k8s/40-user-service.yaml' is not in or below '.../k8s/base'
```

そのためベースを `k8s/base/` に置くにはマニフェストをすべて移動しなければならない。ところがこのリポジトリは
`ops/13-k3s-deploy.sh`・`k8s/README.md`・docs など **50 箇所近くが `k8s/40-user-service.yaml`
のようなパスを直接参照**している。すべて直すコストはオーバーレイを移すコストより大きい。

**(2) ベースを `k8s/` に置くと、オーバーレイをその中に置けない。**

```
cycle detected: candidate root '.../k8s' contains visited root '.../k8s/overlays/dev'
```

ベースのルートがオーバーレイを含むと循環とみなされる。そこでオーバーレイを `k8s-overlays/` に出した。
名前を `k8s-` で始めることで、ソート順ですぐ隣に来るようにした。

### 代償一つ — `kubectl apply -f k8s/` ができない

`k8s/kustomization.yaml` ができたので、kubectl がそれをリソースとして読もうとして失敗する。
個別ファイルの適用はそのままできる。`ops/13-k3s-deploy.sh` は個別ファイルを適用するので影響はなく、
`ops/19` の案内文と `k8s/README.md` だけを直した。

### オーバーレイに収められなかったもの

`90-dev-infra.yaml`(dev インフラ)、`70-autoscaling.yaml`(HPA・PDB)、`85-backup-cronjobs.yaml`
は**個別ファイルなのでオーバーレイから参照できない**(上の (1) と同じ制約)。複製すれば必ず
原本とずれるので、別途適用する:

```bash
# 開発
kubectl apply -f k8s/90-dev-infra.yaml
kubectl apply -k k8s-overlays/dev

# 本番
kubectl apply -k k8s-overlays/prod
kubectl apply -f k8s/70-autoscaling.yaml
kubectl apply -f k8s/85-backup-cronjobs.yaml
```

---

## 3. dev と prod の実際の違い

| | dev | prod |
|---|---|---|
| レプリカ | すべて 1 | **フィールド削除** — HPA が所有 |
| CPU リクエスト | 50m | 250m (ベース) |
| メモリリクエスト | 256Mi | 384~512Mi (ベース) |
| limits | **触らない** | ベース |
| イメージ | `ecommerce-msa/*:local` | `ghcr.io/OWNER/ecommerce-msa/*:1.0.0` |
| imagePullPolicy | IfNotPresent | ベース |
| 外部公開 | port-forward | Cloudflare Tunnel |

**リクエスト合計: dev 600m / 3.0Gi。** 24GB のノートパソコンでインフラまで一緒に動く。

### requests だけ下げて limits はそのままにする理由

よくある間違いは両方を一緒に下げることだ。`requests` はスケジューラが**席を確保しておく**値なので、
下げるとより多くの Pod が一つのノードに入る。`limits` は**実際に使える上限**なので、
下げると JVM がヒープを確保する途中で OOMKilled になる。目的が「全部起動させること」なら requests だけを下げるのが
正しい。

### prod で replicas を削除する理由

HPA が管理する Deployment に `replicas` が残っていると、**`kubectl apply` のたびに
マニフェストの値に戻り、HPA が再び引き上げる。** トラフィックが集中して 6 個に増えた状態で
デプロイすると 2 に落ちてから再び上がり、その間が障害になる。

`eureka-server`(ピアレプリケーション未設定)と `chat-service`(WebSocket セッションが Pod に張り付いている、
docs/21)は HPA がないので replicas を残しておく。

> 🕰️ 2026-11-01 から chat-service は Redis ファンアウト(B-107)で増やせるようになり、`k8s/70-autoscaling.yaml` に HPA(最小 1 · 最大 3)· PDB がある([docs/21 §2-1](21-horizontal-scaling.md))。HPA なしの 1 個固定はもう eureka-server だけ。prod オーバーレイの replicas 削除対象にはまだ chat-service がない(マニフェストの `replicas: 1` が HPA の最小と同じ)。

### dev を 1 にすると捕まえられないバグがある

このリポジトリがレプリカ 2 で経験した問題 — Outbox ポーラーがレプリカごとに同じイベントを発行、
SMS・メールのディスパッチャーがロックなしに同じ行を二度拾う(docs/21, docs/29) — は
**dev では絶対に再現しない。** 並行性は prod オーバーレイか `ops/12-staging-rehearsal.sh`
で確認しなければならない。

---

## 4. OCI Ampere — 最もつまずくポイント

`ops/23-oci-ampere-bootstrap.sh`.

**OCI の Ubuntu・Oracle Linux イメージは、インスタンス内に iptables の REJECT ルールをあらかじめ入れてある。**
22 番以外のほぼすべての入力を遮断するが、これが Pod ネットワーク(CNI)まで遮断する。症状が見当違いに見える:

- Pod は `Running` なのに互いに通信できない
- CoreDNS が `CrashLoopBackOff`
- `kubectl exec` がタイムアウト

「k3s がおかしい」ように見えるが、原因はファイアウォールだ。スクリプトは REJECT を**取り除かず**、
Pod/サービスの CIDR(`10.42.0.0/16`, `10.43.0.0/16`)だけを明示的に許可する — 全部消すと
インスタンスがそのまま露出する。そして `netfilter-persistent` で保存する(しないと再起動後に
「昨日は動いたのに」になる)。

その他:

- `vm.max_map_count=262144` — ないと Elasticsearch がブートストラップチェックで即座に落ちる
- `--disable servicelb` — トンネル経由で入るので LoadBalancer は不要。有効にしておくと klipper-lb が
  80/443 を掴もうとして失敗する
- metrics-server は**無効にしない** — prod オーバーレイの HPA が使う
  (ただし 2 OCPU の環境では HPA を適用しない — docs/33 §4)
- イメージ GC の閾値を 70/50 に — 無料枠のブートボリュームが小さく、イメージが溜まると kubelet が
  Pod を evict し始める

**Security List はスクリプトからは触れない**(コンソールの領域)。ただしトンネルを使うので **22 番だけ
開いていればよい。** それがこの組み合わせの核心だ。

### arm64 イメージ

`ops/19-build-images.sh` がすでに処理している — 別のスクリプトは作らなかった。
**Ampere インスタンス上でそのまま実行すればネイティブ arm64 なのでエミュレーションがない:**

```bash
ssh ubuntu@<Ampere-IP>
./ops/19-build-images.sh --registry ghcr.io/USER --push    # --platform 不要
```

遅くなる組み合わせは **amd64 を arm 上で、またはその逆で**ビルドするときだけで、それすら jar を一度だけ
ビルドするようになっているので短い(`ops/Dockerfile.runtime` のコメント)。

---

## 5. 検証したこと・していないこと

**検証済み**

| | |
|---|---|
| `kustomize build k8s-overlays/dev` | 27 個のリソース、Deployment 12 個すべて replicas=1、リクエスト 600m/3.0Gi |
| `kustomize build k8s-overlays/prod` | 29 個のリソース、HPA 対象 10 個で `replicas` フィールドの削除を確認、cloudflared を含む |
| シェル構文 | `bash -n` — ops/19, 23, 24 |
| ネームスペース | 両オーバーレイとも `ecommerce` に統一 |

**未検証 (環境がない)**

- **OCI Ampere インスタンスで `ops/23` を実際に動かしていない。** iptables の処理と k3s の
  インストールフラグは構文とドキュメントを基に書いており、実機での動作は未確認だ
- **Cloudflare トンネルを実際に接続していない。** アカウント・ドメインが必要だ
- `kubectl apply` そのもの — クラスタがない。`kustomize build` の結果が有効なマニフェストである
  ことまでしか確認していない

特に `ops/23` の iptables ルールは **`--check` でまず診断だけ実行してから**適用することを勧める。

> 🕰️ その後: `ops/23` · k3s · Cloudflare トンネル · クラスタへのデプロイはすべて実際の OCI Ampere(本番 www.ysyproject.com)で動いている — 上の「未検証」は 2026-07 の記録。デプロイの記録は [docs/33](33-deploy-runbook.md) · [docs/85](85-release-deploy-runbook.md)。

---

## 6. 手順

```bash
# ── OCI インスタンスで ──
sudo bash ops/23-oci-ampere-bootstrap.sh --check    # まず診断
sudo bash ops/23-oci-ampere-bootstrap.sh

./ops/19-build-images.sh --registry ghcr.io/USER --push   # ネイティブ arm64

kubectl apply -f k8s/20-secret.yaml                 # docs/13
kubectl apply -k k8s-overlays/prod
kubectl apply -f k8s/70-autoscaling.yaml

# ── ローカルで (kubectl が OCI を指している状態) ──
ops/24-cloudflare-tunnel.sh --hostname shop.example.com
ops/24-cloudflare-tunnel.sh --status

# ── 最後: 開けておいたインバウンドポートを閉じる ──
# OCI コンソール → VCN → Security List → 80/443 を削除 (22 のみ、可能なら自分の IP に制限)
```

`ops/24` は **`kubectl apply -k k8s-overlays/prod` の後に**実行しなければならない。ConfigMap
(`cloudflared-config`)が先に存在しないとトンネル ID を置換できない。順序が逆になると警告を出し、
再実行するよう案内する。

> 🕰️ 上の `kubectl apply -k` · `apply -f k8s/70-autoscaling.yaml` は**まだ何もデプロイされていないクラスタでの最初の 1 回**だけに使う。2 回目からは `./ops/28-deploy.sh <環境> <コミットSHA12桁>`(設定だけなら `--config-only`)— オーバーレイの `newTag` は手書きの値なので、直接 apply すると全サービスがその古いタグに戻る(各オーバーレイの `kustomization.yaml` 冒頭コメント)。HPA · PDB は `ops/28` が `prod` のときだけ(それ以外は `APPLY_HPA=1`)、バックアップ CronJob は `ACTION=cronjobs ./ops/29-enable-pitr-in-cluster.sh` でだけ適用する(そのまま apply すると `suspend: true` で再び止まる)。
