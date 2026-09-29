# 66. gRPC の試行適用 — 在庫予約(order → catalog)

**日本語** | [한국어](66-grpc-stock-reservation.ko.md)

> 作成日: 2026-09-28 · 状態: ✅ 適用(デプロイ待ち · 伝送の既定値は http)
>
> 関連: [59 決済前の在庫予約](59-stock-reservation.md) · [57 §16 サービス間 mTLS](57-remaining-priorities.md) ·
> [63 OpenTelemetry](63-opentelemetry.md) · [55 無停止の移行](55-zero-downtime-migrations.md)

## 0. 要約

| | 前 | 後 |
|---|---|---|
| 在庫予約の呼び出し | Feign(HTTP/1.1 · JSON) `/api/internal/stock/reservations` | そのまま残し、**gRPC(HTTP/2 · protobuf)** を横に追加 |
| 選び方 | — | ConfigMap `STOCK_RPC_TRANSPORT` = `http`(既定) · `grpc` |
| 契約 | Java DTO(暗黙的) | `common/src/main/proto/stock_reservation.proto` — スキーマが真実 |
| 呼び出し元判定 | HTTP フィルター(mTLS の CN + パスルール) | gRPC インターセプターが **同じ判定オブジェクト · 同じルール** を使う |
| 時間制限 | クライアントの読み取り制限 5 秒(サーバーは知らない) | 呼び出しごとに **期限 3 秒 — サーバーへ伝播**(サーバー側の処理がキャンセルされる) |
| トレース | Feign → Tomcat | gRPC クライアント → サーバーの span が親子でつながる |
| ガード | B-67(Feign のパスルール) | **B-73** — proto サービス ↔ パスルール、サーバーポート ↔ マニフェスト ↔ 呼び出し先、スイッチの値 |

**性能の向上は期待していない。**ノード 1 台 · サービスごとに 1 つ · トラフィックは少なく、遅延の大半は DB(悲観的ロック)である。
今回の試行の価値は、① proto で契約を固定し、② 期限を伝播し、③ 運用できる水準(mTLS · 判定 · トレース · ガード · 戻し方)で一度きちんとやってみることにある。

## 1. なぜこの呼び出しか

サービス間の同期呼び出し(Feign 12 個)のうちこれを選んだ理由:

- **内部専用** — ブラウザ · ゲートウェイに現れない。変えても画面に影響がない。
- **コマンドが 2 つ** — `Reserve` · `Release`。契約が小さい。
- **gRPC の長所が実際に効く場所** — 決済画面の手前の呼び出しなので期限に意味があり、「不足」と「失敗」を区別する必要がある。
- **中核の経路なので測りやすい** — トレース(docs/63)で前後をすぐ比較できる。

選ばなかったもの: ブラウザ側(gRPC-Web プロキシが一枚増える)、決済会社の Webhook(相手が HTTP)、決済 Saga · 在庫引き当てのイベント(あえて非同期 — Kafka を同期呼び出しに変えると障害が波及する)、チャット(WebSocket が適切)。

## 2. 構成

```
order-service                                     catalog-service
┌─────────────────────────────┐                   ┌──────────────────────────────────────────┐
│ OrderStockHold              │                   │ :8081 Tomcat  InternalCallerFilter ──┐    │
│   └ StockReservationPort    │                   │                 └ InternalStockController │
│       ├ Feign   (http) ─────┼── HTTPS :8081 ──▶ │                        │               │    │
│       └ Grpc    (grpc) ─────┼── h2 mTLS :9095 ▶ │ :9095 Netty                            │    │
│           期限 3s · トレース  │                   │   Observation → InternalCallerGrpcInterceptor
└─────────────────────────────┘                   │     └ StockReservationGrpcService        │    │
                                                  │              ▼                ▼          │    │
                                                  │        StockReservationService.reserve() ◀┘    │
                                                  └──────────────────────────────────────────┘
```

2 つの入口が **同じサービスメソッド** に届く — ロックの順序 · 全部か無か · 延長のルールは 1 つである。違うのは伝送とエラーの形だけだ。

| コード | 役割 |
|---|---|
| `common/src/main/proto/stock_reservation.proto` | 契約 |
| `common/.../grpc/StockReservationMapping` | DTO ↔ proto 変換(サーバー · クライアント共用) |
| `common/.../grpc/GrpcTls` | 身元キーストア + 内部 CA で SslContext |
| `common/.../security/InternalCallerGrpcInterceptor` | 呼び出し元判定(HTTP フィルターの判定を再利用) |
| `catalog/.../grpc/StockGrpcServerConfig` · `StockReservationGrpcService` | サーバーの組み立て · 入口 |
| `order/.../stock/StockReservationPort` · `FeignStockReservationPort` · `GrpcStockReservationPort` · `StockReservationTransportConfig` | 伝送の抽象化 · スイッチ |

## 3. 契約 — proto のルール

- **フィールド番号は変えず、再利用もしない。**消したフィールドは `reserved` で残す。新しいフィールドは新しい番号でのみ — 古いクライアントは知らないフィールドを無視する(Avro のスキーマ進化と同じ原則、docs/38 §1.16)。
- **「足りない」はエラーではなく結果である** — `held=false` + `shortages`。ステータスコードは呼び出し自体の失敗にだけ使う。HTTP も不足なら 200 だ。このルールが 2 つの伝送の **同等性** を生む。
- **proto3 には null がない。**既定値の解釈を `StockReservationMapping` の 1 か所で決める: id 0 → null(実際の id は 1 以上)、サイズの空文字列はそのまま(サイズのない商品の名前であり予約キー)、期限 0 → なし。
- 生成物はコミットしない(`build/generated`)。protoc は Gradle プラグインがプラットフォームごとに取得する(x86 の CI · aarch64 の本番ビルド)。

| 状況 | gRPC | (HTTP では) |
|---|---|---|
| 不足 | OK + `held=false` | 200 + `held=false` |
| 不正なリクエスト | `INVALID_ARGUMENT` | 400 |
| 証明書なし(enforce) | `UNAUTHENTICATED` | 403(ANONYMOUS に変わって拒否) |
| パスルール違反(rules enforce) | `PERMISSION_DENIED` | 403 |
| その他のサーバー失敗 | `INTERNAL`(メッセージなし) | 500(メッセージなし) |
| 期限超過 · 接続失敗 | `DEADLINE_EXCEEDED` · `UNAVAILABLE` | 読み取り制限 · 接続エラー |

order は例外(ステータスコードが何であれ)を HTTP の失敗と同じく **「catalog に届かない」→ 予約なしで進む** と扱う(決済後の不足分返金が受け止める — docs/59)。

## 4. セキュリティ — mTLS と呼び出し元判定

**gRPC ポートはサーブレットフィルターの外にある。**`InternalCallerFilter` は Tomcat の前でしか動かない。インターセプターがなければ、クラスター内の任意の Pod が 9095 で在庫を押さえられる — HTTP が塞いだものを横の扉が開けてしまう。

- **判定は複製していない。**インターセプターは HTTP フィルター **Bean のオブジェクト** を受け取り、`callerOf(chain)` · `policyViolation(caller, path)` を呼ぶ。モード · 内部 CA · 許可主体 · パスルールが一揃いである。
- **パスルールがそのまま合う。**gRPC メソッドの HTTP/2 パスは `/ecommerce.stock.v1.StockReservation/Reserve` なので、ConfigMap の `INTERNAL_AUTH_PATH_RULES` に `/ecommerce.stock.v1.StockReservation/=order-service` を 1 つ足せばよい。
- **HTTP との違い** — 身元ヘッダー(User-*)を見ない(gRPC ポートはゲートウェイの後ろにない)。遮断するときは即座に切る(後ろで再判定する認可層がない)。
- **TLS** — サービスの身元証明書(k8s/96、CN=サービス、dnsNames に `catalog-service.ecommerce.svc.cluster.local`、サーバー · クライアント兼用)をそのまま使う。信頼するのは **内部 CA ひとつだけ**(JVM デフォルトのトラストストアの公開 CA は使わない)。
- **鍵は PKCS#12 から読む。**cert-manager の `tls.key` は RSA なら PKCS#1 だが、Netty の PEM 読み込みは PKCS#8 しか受けない(BouncyCastle が必要)。同じ Secret の `keystore.p12` は JVM がすでに読んでいるファイルである(`JAVA_TOOL_OPTIONS` の `javax.net.ssl.keyStore`) — 新しい環境変数は不要だった。
- サーバーはクライアント証明書を **要求するが強制しない**(OPTIONAL)。TLS 層で切ると、observe 段階で「誰が証明書なしで呼んでいるか」が見えない。判定はインターセプターがモードに従って行う。
- **enforce なのに TLS がなければサーバーを起動しない**(ERROR ログ)。catalog 全体を落とすことではない — HTTP の入口はそのまま動く。
- NetworkPolicy は変えていない — 同じ名前空間内はすでに許可されており(`allow-same-namespace`)、9095 は Ingress · ゲートウェイへ出さない。

## 5. 期限(deadline)

HTTP の読み取り制限は **クライアントだけ** があきらめる — サーバーは最後まで処理して結果を捨てる。gRPC の期限はリクエストに載って **サーバーへ伝播する**。catalog は期限が過ぎると `Context` がキャンセルされ、その呼び出しをそれ以上抱えない。

- 既定 3 秒(`STOCK_GRPC_DEADLINE_MS`) — Feign の読み取り制限(5 秒)より短く。決済画面の手前で長く引き止めない。
- テスト(`GrpcStockReservationPortTest.deadlinePropagatesAndOrderProceeds`)が「order は UNAVAILABLE で進む + catalog 側の Context がキャンセルされる」を一緒に確かめる。

## 6. 切り替え手順(運用)

```bash
# 0. 新しいイメージ(catalog · order)をデプロイする — 伝送はまだ http。catalog で gRPC サーバーが起動する。
kubectl -n ecommerce logs deploy/catalog-service | grep "재고 예약 gRPC 서버"
#    期待: 재고 예약 gRPC 서버: port=9095 tls=true mode=enforce
#    「띄우지 않는다」が見えたら、先にキーストア · CA のパスを直す(切り替えない)

# 1. 切り替え — k8s/10-configmap.yaml の STOCK_RPC_TRANSPORT を "grpc" にして
./ops/28-deploy.sh <env> --config-only --restart order-service
kubectl -n ecommerce logs deploy/order-service | grep "재고 예약 전송"      # 期待: 재고 예약 전송: grpc (기한 3000ms)

# 2. 確認 — 注文を 1 つ作ってみる
kubectl -n ecommerce logs deploy/catalog-service | grep "\[mTLS caller\].*StockReservation"   # caller=order-service
kubectl -n ecommerce logs deploy/order-service | grep "재고 예약(gRPC) 실패"                   # 出てはいけない
#    Jaeger: サービス order-service → span "ecommerce.stock.v1.StockReservation/Reserve" の下に catalog の同名 span
#    Prometheus: grpc_server_seconds_count (catalog) — ラベルにサービス · メソッド · ステータスコード

# 3. 戻す — 同じ方法で "http" に。進行中の予約の意味は変わらない(同じサービスメソッド)。
```

失敗ログのステータスコードが原因を切り分ける: `UNAUTHENTICATED` · `PERMISSION_DENIED` → 証明書 · パスルール、`UNAVAILABLE` → 接続(ポート · Service · DNS)、`DEADLINE_EXCEEDED` → catalog が遅い(DB のロック待ちなど)。

## 7. 観測

- **トレース** — Micrometer Observation のインターセプターをサーバーでは **最も外側** に置く(拒否された呼び出しもトレースに残る)。OTel ブリッジが `traceparent` を gRPC メタデータで渡して受け取る → order の CLIENT span と catalog の SERVER span が 1 つのトレースの親子になる(テストで固定)。span 名は gRPC メソッド名である。
- **メトリクス** — `grpc.server` · `grpc.client` のタイマー(ステータスコードのタグ付き)が actuator を経て Prometheus へ行く。テールサンプリング · span_metrics(docs/63)はそのまま適用される。
- **ログ** — 判定は HTTP と同じ文字列(`[mTLS caller]` · `[mTLS rule]` · `[mTLS observe]`)に `gRPC /…` のパスが付く。失敗は order 側の `재고 예약(gRPC) 실패 — status=…`。

## 8. ガード B-73

gRPC は 4 つの端が合わないとつながらない。1 つでもずれると、**テストはすべて緑のまま** 本番でだけ切れる(単体テストは in-process · 任意ポートで動く)。

1. proto のサービスごとに `INTERNAL_AUTH_PATH_RULES` に `/パッケージ.サービス/` のルール
2. catalog の gRPC ポートの既定値 = Deployment の containerPort = Service のポート(名前 `grpc`)
3. `STOCK_GRPC_TARGET` = `dns:///catalog-service.ecommerce.svc.cluster.local:<そのポート>` — サーバー証明書の dnsNames とも合う名前
4. `STOCK_RPC_TRANSPORT` ∈ {http, grpc} — それ以外なら order は起動しない(タイプミスが黙って http にならないように — コードも起動を止める)

ルールを外し、スイッチをタイプミスに変えて、2 つの違反がどちらも捕まることを確認した。

## 9. 検証

| テスト | 何を |
|---|---|
| `InternalCallerGrpcInterceptorTest`(common, 7) | **実際の Netty · TLS** 上の判定 — 許可サービスは通過 · 別サービスは PERMISSION_DENIED · 証明書なしは UNAUTHENTICATED · 別 CA は拒否 · observe · rules-observe · off |
| `StockReservationGrpcServerTest`(catalog, 6) | 本番の組み立てそのまま — 同じサービスメソッド · 不足は結果 · エラーの対応付け(内部メッセージなし) · パスルール · **トレースの親子** · TLS がなければ起動しない |
| `GrpcStockReservationPortTest`(order, 5) | **HTTP ↔ gRPC の同等性**(確保 · 不足) · **期限の伝播** · 失敗のステータスコード · スイッチ |
| コンテナ起動(catalog · order) | 実際のアプリでサーバーが起動し(テストはポート 0)、order は既定の http |
| B-73 | §8 |

証明書は JDK の `keytool` で作る(`TestPki` — common の testFixtures): 内部 CA、その CA が署名したサービスの身元(PKCS#12、SAN=localhost)、別 CA の偽物。

## 10. 限界と次

- **ロードバランシング。**gRPC は HTTP/2 の接続 1 本を長く使う。ClusterIP Service の後ろでは接続が **最初につながった Pod に固定** され、catalog を複数に増やしても負荷が分散しない。今は 1 つなので問題ない。増やすときは headless Service + クライアントの `round_robin` ポリシー(またはメッシュ · L7 プロキシ)が必要になる。
- **プローブ。**k8s のプローブは 8081(actuator)しか見ない。gRPC サーバーが起動しなかった場合(§4 の enforce + TLS なし)はログにしか現れない — gRPC health サービス(`grpc.health.v1`)や actuator のメトリクスを付けられる。
- **証明書の更新。**サーバーの SslContext は起動時に一度作る。cert-manager が証明書を更新すると Reloader が Pod を再起動するので(k8s/41 のコメント)今は正しい — 再起動なしで替えるには、キーマネージャーをファイル監視にする必要がある。
- **測定はまだ。**切り替え後に Jaeger · Prometheus で p50/p95 を HTTP と比べてここに書く。
- **次の候補** — order → coupon の見積もり(`/api/coupon/internal/quote`): 金額がかかる内部呼び出しで、契約が小さい。今回の部品(判定 · TLS · 変換パターン · ガード)をそのまま使える。
