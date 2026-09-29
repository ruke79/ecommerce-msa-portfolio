# k3s デプロイマニフェスト

**日本語** | [한국어](README.ko.md)


> ## ⚠️ デプロイはオーバーレイで
>
> `k8s/kustomization.yaml` ができたことで、**`kubectl apply -f k8s/` は使えません**
> （kubectl が kustomization.yaml をリソースとして読もうとして失敗します）。代わりに:
>
> ```bash
> kubectl apply -f k8s/90-dev-infra.yaml     # dev インフラ (dev のみ)
> kubectl apply -k k8s-overlays/dev          # 開発 — レプリカ 1、リクエスト縮小
>
> kubectl apply -k k8s-overlays/prod         # 本番 — HPA 所有、Cloudflare Tunnel
> kubectl apply -f k8s/70-autoscaling.yaml   # HPA・PDB (ノードが複数あるときのみ)
> kubectl apply -f k8s/85-backup-cronjobs.yaml
>
> kubectl apply -k k8s-overlays/external     # 本番 + マネージド PG/Redis/Mongo (docs/32)
> ```
>
> 🔴 **上の `apply -k` は初回デプロイでのみ使います。** オーバーレイの `images:` には手書きした
> `newTag` が埋め込まれているため、デプロイした後にそのファイルを再び apply すると、全サービスが**その古いタグに
> 戻ってしまいます。** 2 回目以降は `./ops/28-deploy.sh <オーバーレイ> <バージョン>` を使い、設定だけを
> 変えるときは `./ops/28-deploy.sh <オーバーレイ> --config-only [--restart <deploy>]` を使います。
>
> 以下の文書の**個別ファイルの適用はそのまま動作します。** 環境ごとの差分（レプリカ・リソース・イメージ）を
> オーバーレイが担当するだけです。詳しくは [docs/31](../docs/31-kustomize-oci-tunnel.md)、インフラをクラスターの外に出す `external` オーバーレイは [docs/32](../docs/32-external-managed-infra.md)。

このディレクトリは `ecommerce-msa` を k3s（軽量 Kubernetes）にデプロイするための**純粋な Kubernetes YAML マニフェスト**です。
k3s は Kubernetes ディストリビューションなので `docker-compose.yml` を直接実行できないため、既存のルートの
`docker-compose-core.yml` / `docker-compose-services-1.yml` / `docker-compose-services-2.yml` が
定義していたものと同等のデプロイを Deployment/Service/ConfigMap/Secret/Ingress で表現し直しました。

## 範囲（参考: [`docs/`](../docs/README.md)）

**含む**: eureka-server、gateway-server、7 つのバックエンドサービス（user/catalog/cart/order/pay/coupon/chat）、frontend。

**除外（前提）**: Kafka、PostgreSQL、Redis、MongoDB、Elasticsearch。すでに稼働中か、クラスターに
別途構築した**外部/既存のインフラ**と仮定し、接続情報だけを ConfigMap/Secret で注入します。
（本番環境ではステートフルなストアは通常、別の Operator/StatefulSet やマネージドサービスで扱うのが適切であり、
このマニフェストセットの関心事と分離したほうが保守に有利だと判断しました。）

**config-server は `32-config-server.yaml` で一緒にデプロイしますが、設定リポジトリ(Git)は接続していません** — 接続するなら
先に認証を付ける必要があります。すべてのサービスが `spring.config.import` に `optional:` 接頭辞を使っているので、config-server がなくても正常に起動します。

## なぜアプリケーションコードに手を入れずにデプロイできるのか

各サービスは `application-local.yml` の値の大部分を `${ENV_VAR}` プレースホルダーにしているか、Spring Boot の
relaxed binding（OS の環境変数が yml の値より常に優先）のおかげで、**OS の環境変数だけで再定義可能**です。
このマニフェストはその事実を利用して、イメージ/コードはそのままに、`ConfigMap` + `Secret` + Deployment の
`env` だけでデプロイ環境を構成します。（既存の docker-compose の `environment:` ブロックとまったく同じメカニズム）

## 事前準備

1. **k3s クラスター**と `kubectl` のアクセス権限。
2. **コンテナレジストリ**（Docker Hub、GHCR、プライベートレジストリなど）— イメージをプッシュする先。
3. **外部インフラ**がすでに起動しており、クラスターからネットワークで到達可能である必要があります:
   - PostgreSQL 15+ — 以下の 8 つのデータベースが必要です（単一インスタンス、アカウント `product` を共用と仮定）:
     `userdb`, `cartdb`, `productdb`, `metadb`, `coupondb`, `orderdb`, `paydb`, `chatdb`
     （参考: `chat-service` の `application-local.yml` はもともと `paydb` を指しており、hibernate dialect も
     MySQL になっているという既存のバグがあったため、`46-chat-service.yaml` で `chatdb` + PostgreSQL の方言に
     環境変数で修正しました。）
   - Apache Kafka (SSL)
   - Redis
   - MongoDB (chat-service)
   - Elasticsearch (catalog-service) — 初回デプロイでは下記の「既知の問題」に従い http で接続します。
4. **mTLS キーストア（PKCS12）** — このプロジェクトはサービス間 mTLS を使用します。以前ローカル/Docker 用に
   作成しておいた `keystore.p12`、`truststore.p12`（およびゲートウェイ用の別のトラストストア）、Kafka 用の
   `kafka-truststore.jks` / `kafka-keystore.p12` ファイルが必要です。

## 1. イメージのビルド & プッシュ

既存の各サービスの `Dockerfile` をそのまま使用します（リポジトリのルートでビルドコンテキストを取ることで、`common` モジュールと
ルートの Gradle ファイルを一緒に COPY できます）:

```bash
REGISTRY=<your-registry>/ecommerce-msa
for svc in eureka-server gateway-server user-service catalog-service cart-service \
           order-service pay-service coupon-service chat-service; do
  docker build \
    --build-arg SERVICE_DIR=$svc \
    --build-arg SPRING_PROFILES_ACTIVE=local \
    -t $REGISTRY/$svc:1.0.0 \
    -f $svc/Dockerfile .
  docker push $REGISTRY/$svc:1.0.0
done

# frontend は今回新たに追加した Dockerfile を使用し、ビルドコンテキストは frontend/ 自体です。
# REACT_APP_API_URL/REACT_APP_PAYPAL_CLIENT_ID はランタイムではなくビルド時にバンドルへ埋め込まれるので、
# ここで実際の値を入れる必要があります (frontend/Dockerfile 冒頭のコメントを参照)。
docker build \
  --build-arg REACT_APP_API_URL=https://<gateway-server の外部アドレス>:8000 \
  --build-arg REACT_APP_PAYPAL_CLIENT_ID=<paypal client id> \
  -t $REGISTRY/frontend:1.0.0 -f frontend/Dockerfile frontend
docker push $REGISTRY/frontend:1.0.0
```

その後、このディレクトリの各 YAML で `REGISTRY_PLACEHOLDER/ecommerce-msa/<service>:1.0.0` という文字列を
実際のレジストリのパスに置換してください:

```bash
find k8s -name '*.yaml' -exec sed -i "s#REGISTRY_PLACEHOLDER/ecommerce-msa#$REGISTRY#g" {} +
```

## 2. 設定値を埋める

```bash
cp k8s/20-secret.example.yaml k8s/20-secret.yaml
# k8s/20-secret.yaml を開いて REPLACE_ME の値をすべて埋めてください。(.gitignore にすでに追加されています)
```

`k8s/10-configmap.yaml` も、皆さんの実際のインフラのホスト名（Kafka broker、Redis、MongoDB、
PostgreSQL、Elasticsearch）に合わせて修正してください。

mTLS キーストアはファイルベースの Secret として別途作成します（先に名前空間が必要です）:

```bash
kubectl apply -f k8s/00-namespace.yaml

kubectl create secret generic tls-keystore -n ecommerce \
  --from-file=keystore.p12=/path/to/keystore.p12 \
  --from-file=truststore.p12=/path/to/truststore.p12 \
  --from-file=kafka-truststore.jks=/path/to/kafka-truststore.jks \
  --from-file=kafka-keystore.p12=/path/to/kafka-keystore.p12
```

それらのファイルがなければ `ops/07-issue-certificates.sh` が作ってくれます（[docs/18](../docs/18-certificates.md)）。
Secret YAML まで直接出力してくれるので、上のコマンドを自分で打つ必要はありません:

```bash
./ops/07-issue-certificates.sh                          # ローカル CA (開発/社内)
# または  MODE=letsencrypt LE_DOMAIN=api.example.com LE_EMAIL=ops@example.com ./ops/07-...

kubectl apply -f certs/k8s-tls-keystore.secret.yaml     # tls-keystore Secret

# ストアのパスワードも ecommerce-secrets に反映 (値は certs/certs.env の中にあります)
kubectl -n ecommerce patch secret ecommerce-secrets --type merge -p \
  '{"stringData":{"LOCAL_KEY_STORE_PASSWORD":"...","LOCAL_KEY_PASSWORD":"...","KAFKA_SSL_KEY_STORE_PASSWORD":"...","KAFKA_SSL_TRUST_STORE_PASSWORD":"..."}}'
```

## 3. デプロイ

> ### ⛔ `kubectl apply -f k8s/` は使わないでください
>
> ファイル名がソートされるので一度に適用してもよいと、**以前この文書は勧めていました。** 今は
> そうしてはいけません。このディレクトリにはオーバーレイがわざと除外したファイルが一緒に置かれているため、
> ディレクトリを丸ごと適用すると、それらまでクラスターに押し込んでしまいます:
>
> | ファイル | ディレクトリ適用の結果 |
> |---|---|
> | `70-autoscaling.yaml` | HPA（minReplicas=2）が復活し、オーバーレイの `replicas: 1` を 15 秒で戻してしまう |
> | `90-dev-infra.yaml` | Postgres・Kafka・Redis・Mongo・Elasticsearch が**クラスター内に**起動する。マネージドを使うデプロイでは純粋な無駄であり、シングルノードではそれだけでノードのリソースが枯渇する |
> | `85-backup-cronjobs.yaml` | バックアップの CronJob と PVC が追加される |
> | 残りの base マニフェスト | イメージが**オーバーレイ以前の値**（`REGISTRY_PLACEHOLDER/...:1.0.0`）に戻る — 直前のデプロイが丸ごと無効になる |
>
> さらに `k8s/kustomization.yaml` をリソースとして読もうとして、このようなエラーを出します —
> しかし**そこで止まらずに残りをすべて適用します。**
>
> ```
> error: resource mapping not found ... no matches for kind "Kustomization"
> ```
>
> 実際にこのリポジトリでデプロイが 5 回連続で失敗した原因がこれでした（docs/39 §7.3）。
> **オーバーレイでデプロイしてください**: `./ops/28-deploy.sh <環境> <バージョン>`。
>
> 以下のリストは、オーバーレイなしで個別に適用する場合の順序です。

```bash
kubectl apply -f k8s/00-namespace.yaml
kubectl apply -f k8s/10-configmap.yaml
kubectl apply -f k8s/20-secret.yaml
kubectl apply -f k8s/30-eureka-server.yaml
kubectl apply -f k8s/31-gateway-server.yaml
kubectl apply -f k8s/40-user-service.yaml
kubectl apply -f k8s/41-catalog-service.yaml
kubectl apply -f k8s/42-cart-service.yaml
kubectl apply -f k8s/43-order-service.yaml
kubectl apply -f k8s/44-pay-service.yaml
kubectl apply -f k8s/45-coupon-service.yaml
kubectl apply -f k8s/46-chat-service.yaml
kubectl apply -f k8s/50-frontend.yaml
kubectl apply -f k8s/60-ingress.yaml
kubectl apply -f k8s/70-autoscaling.yaml   # HPA + PDB — ⚠️ **ノードが複数あるときのみ**
#   minReplicas がすべて 2 である。シングルノードではオーバーレイが決めた replicas=1 を 15 秒で
#   戻してしまい、その競合が startupProbe の猶予を超えてロールアウトを失敗させる(docs/39 §7.3)。
```

⛔ `kubectl apply -f k8s/` で一度に適用しないでください — 理由は上のボックスを参照してください。


## 4. 確認

```bash
kubectl get pods -n ecommerce -w
kubectl logs -n ecommerce deploy/gateway-server
kubectl get svc -n ecommerce gateway-server   # LoadBalancer の外部 IP/ポートを確認 (k3s 内蔵の ServiceLB)
```

## ゲートウェイの公開方式

`gateway-server` は自前で HTTPS（mTLS）を終端するので、`Service type: LoadBalancer` で直接
公開します（k3s はデフォルトで Klipper ServiceLB を内蔵しているため、別途インストールしなくても外部 IP が割り当てられます）。
標準の `Ingress` はバックエンドへ平文 HTTP でプロキシするのがデフォルトの動作であり、ゲートウェイ自身の TLS 終端方式と
合わないため、意図的に除外しました。公開ドメイン + 公開証明書を前段に置きたい場合は、Traefik の
`IngressRoute` + `ServersTransport`（TLS passthrough）を別途構成してください。

## 既知の問題 / 意図的な簡略化（初回デプロイ基準）

今回の作業で発見/補正した事項です。根本的な修正にはアプリケーションコードの変更が必要で、
このマニフェストセットの範囲を超えるため、env で回避するか文書化するだけにしました。

| 問題 | 対処 |
|---|---|
| `feign.catalog-url` / `feign.cart-url` / `feign.user-url` がどの yml にも定義されていない（docker-compose も同様）→ ないと cart/order/coupon/chat/user の起動が失敗 | `10-configmap.yaml` でクラスター内部の DNS を設定 |
| catalog-service の ES PEM トラストストアのパスが `"classpath: ${LOCAL_CRT_PATH}"`（コロンの後に空白）になっており、classpath リソースとして解決されない（[`docs/06`](../docs/06-catalog-search-issues.md)） | 初回デプロイは ES に http（平文）で接続するよう回避（`41-catalog-service.yaml`）。TLS で接続するには、ソースのタイポを直したイメージが必要 |
| chat-service の datasource が `paydb` を指しており、dialect も MySQL になっている（コピペミスと思われる） | env で `chatdb` + PostgreSQL dialect に上書き（`46-chat-service.yaml`） |
| Postgres の `properties.ssl=true`（DefaultJavaSSLFactory、別途ルート証明書が必要） | 初回デプロイは `sslmode=require`（転送区間の暗号化）だけを使い、`SPRING_DATASOURCE(_*)_PROPERTIES_SSL=false` で無効化。サーバー証明書の検証まで行うには、ルート証明書を mTLS の Secret に追加してこの値を削除してください |
| ゲートウェイの CORS 許可オリジン（`https://localhost:3000`）は、マップのキーが特殊文字（`/**`）を含むため env var で安定して上書きできない | 実際のフロントのドメインを使うには、`gateway-server/src/main/resources/application-local.yml` を修正してイメージを再ビルドする必要があります |
| ゲートウェイの `useInsecureTrustManager: true`（mTLS の信頼体系を弱める、[`docs/03` R-5](../docs/03-architecture-and-reliability.md)） | 今回のマニフェストではそのまま維持。本番前に必ずソースを修正することを推奨 |

## リソースのサイジング / プローブ

- リクエスト/リミットは、既存の `docker-compose-*.yml` に追加していた値（`mem_limit: 768m`、`cpus: 1.0`）と同じ
  水準に従いつつ、k8s の `requests`（スケジューリング基準）/`limits`（上限）に分けました。
- `livenessProbe`/`readinessProbe` は、先のバックエンド改善（[`docs/02`](../docs/02-improvements.md)）で
  全サービスに有効化した `/actuator/health/liveness`、`/actuator/health/readiness` をそのまま使用します。
  ただし `eureka-server` は actuator の依存関係がないため、TCP ソケットプローブで代替しました。
- すべての Pod は初回起動が遅くなることがあるため（JVM+Hibernate ddl-auto+Eureka 登録など）、`startupProbe` で
  最大 ~150 秒の起動猶予を設け、その後は liveness/readiness が短い周期で動作します。

## 今後の改善方針（未対応）

- ~~HorizontalPodAutoscaler、PodDisruptionBudget~~ → `70-autoscaling.yaml` で追加済み。
  chat-service はインメモリの STOMP ブローカーなのでスケールアウトから除外（docs/03 F-7）、eureka は peer-awareness の設定が先に必要なため除外。
- ~~NetworkPolicy による名前空間間のトラフィック制限~~ → `95-network-policy.yaml`（受信接続のみ）。kustomization の外 — `ops/35-network-policy.sh apply` で確認しながら適用（docs/57 §11）。
- インフラ（Kafka/Postgres/Redis/Mongo/ES）まで StatefulSet/Operator でクラスター内に取り込む
- ゲートウェイ前段の TLS passthrough Ingress（公開証明書を使う場合）
- Secret を平文の代わりに Sealed Secrets / External Secrets Operator で管理
