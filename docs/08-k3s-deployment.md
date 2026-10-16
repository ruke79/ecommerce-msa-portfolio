# k3s デプロイマニフェストの追加

**日本語** | [한국어](08-k3s-deployment.ko.md)

> 🕰️ **状態（2026-09-27）— 過去の記録。** 最初のマニフェストを作ったときの記録です。現在のデプロイは kustomize オーバーレイ · Cloudflare Tunnel · `ops/28` です（[31](31-kustomize-oci-tunnel.md) · [33](33-deploy-runbook.md) · [36](36-deployment-pipeline.md)）。

> 作成日: 2026-07-24 · 関連成果物: [`k8s/`](../k8s/README.md)

既存の `docker-compose-core.yml` / `docker-compose-services-1.yml` / `docker-compose-services-2.yml` は
k3s（Kubernetes）で直接実行できません。このドキュメントはそのギャップを埋めるために追加した
純粋な Kubernetes YAML マニフェスト（`k8s/`）の要約であり、詳しい使い方は [`k8s/README.md`](../k8s/README.md) にあります。

## スコープの決定

ユーザーと協議し、スコープを次のように決めました:
- **形式**: Helm/Kustomize ではなく **純粋な K8s YAML マニフェスト**（すぐに `kubectl apply -f` でデプロイ可能、別途ツール不要）。
- **対象**: アプリケーション層のみ（eureka/gateway/7つのバックエンドサービス/frontend）。Kafka/PostgreSQL/Redis/MongoDB/
  Elasticsearch のようなステートフルなインフラはすでにクラスター内または外部に存在すると仮定し、接続情報のみを
  ConfigMap/Secret で注入します。

## 中核となる設計

- すべてのサービスが `application-local.yml` をそのままにして **OS 環境変数（Spring relaxed binding）だけで**
  設定を注入されるようにすでに構成されている点を活用し、**アプリケーションのコード/イメージを一切変更せずに**
  デプロイできるようにしました。（既存の docker-compose の `environment:` ブロックと同じメカニズムを k8s
  ConfigMap/Secret に移したもの）
- 先の作業でバックエンドに追加した liveness/readiness actuator プローブ（[`docs/02`](02-improvements.md)）を
  そのまま k8s の `livenessProbe`/`readinessProbe` に接続し、ドキュメント化した改善とデプロイマニフェストが実際に
  噛み合うようにしました。
- ゲートウェイは自前で mTLS を終端するため、標準の Ingress（HTTP プロキシがデフォルト動作）の代わりに
  `Service type: LoadBalancer`（k3s 内蔵の ServiceLB）で直接公開。frontend だけを Traefik Ingress で公開。
  > **2026-10-14 変更:** ゲートウェイの Service は `ClusterIP` です。運用は Cloudflare トンネルから入り（`--disable servicelb`）、
  > LoadBalancer は外部 IP がなくても nodePort を受け、全ノードのそのポートを開けていました（B-85）。直接つなぐときは
  > `kubectl -n ecommerce port-forward svc/gateway-server 8000:8000`。

## 調査中に発見して反映した既存の問題

マニフェストを書きながらアプリケーション設定をレビューしていたところ、今回のデプロイ作業のスコープを超える（コード修正が
必要な）既存の欠陥をいくつか追加で発見しました。コードには手を付けず k8s の環境変数で回避し、
根本的な修正が必要な項目は `k8s/README.md` の「既知の問題」表にまとめました:

- `feign.catalog-url` / `feign.cart-url` / `feign.user-url` がどの yml/compose にも定義されておらず、
  cart/order/coupon/chat/user-service が現状では起動時にプレースホルダーの解決に失敗して落ちる
  潜在的な問題 — ConfigMap にクラスター DNS アドレスを埋めて解決。
- chat-service の datasource が `paydb` を指し、hibernate dialect も MySQL になっているコピペミス —
  環境変数で `chatdb` + PostgreSQL dialect に修正。
- catalog-service の ES TLS トラストストアのパスに空白が混ざっていて classpath リソースとして解決されない
  問題（`docs/06-catalog-search-issues.md` と関連）— 初回デプロイは ES に http で迂回接続。

## 次のステップ

`k8s/README.md` の「今後の改善の方向性」（HPA/PDB/NetworkPolicy/インフラのクラスター内への取り込み/Ingress TLS
passthrough/Secret 管理のツール化）を参照。
