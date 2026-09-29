# 証明書・キーストア管理

**日本語** | [한국어](18-certificates.ko.md)

> 🕰️ **状態（2026-09-27）— 一部古い。** クライアント証明書は現在サービスごとです（CN = サービス名、`k8s/96`、cert-manager による自動更新 — [docs/57 §16・§18](57-remaining-priorities.md)）。サーバー証明書 `tls-keystore` はまだ共有です。

> 作成日: 2026-07-28
> 関連: [ops/07-issue-certificates.sh](../ops/07-issue-certificates.sh) · [バックアップ/DR](15-backup-and-dr.md) · [シークレット/HA](13-secrets-dbha-es-search.md)

## 0. 何が問題だったか

このリポジトリのすべてのサービスは HTTPS で起動し、Kafka・Redis・MongoDB・PostgreSQL に TLS で接続する。
ところが、その証明書を**どう作るのかがどこにも書かれていなかった。** 結果:

| 症状 | 実際のコード |
|---|---|
| 新しくクローンするとどのサービスも起動できない | `${LOCAL_KEY_STORE_PATH}` を埋める方法が文書化されていない |
| 設定に原作者の PC のパスが埋め込まれている | `file:E:/github/amazon-clone-msa/.../local_truststore.jks` — 7 サービス |
| mTLS を有効にすると落ちる | Java がストア形式を `"JKS"` とハードコード、パスは `.p12` — 19 クラス |
| ゲートウェイが TLS 検証をしない | `useInsecureTrustManager: true` |
| 期限が切れても誰も気づかない | 更新手順・監視なし |

[docs/15 §0](15-backup-and-dr.md) が「証明書/キーストア = ❌ リポジトリ外の手動管理 = 🔴」として残していた
項目がまさにこれである。以下の 4 つを直した。

## 1. ファイルの場所は環境変数でのみ受け取る

各サービスの `application-local.yml` にあった `kafka:` ブロックをすべて削除した。そのブロックは
存在しない Windows パスを指したまま `protocol: "SSL"` を有効にしていた — ローカルブローカーが
PLAINTEXT だったので黙って通っていただけで、SSL ブローカーに接続した瞬間に落ちる構成だった。

今は Kafka 接続設定を `common/src/main/resources/application-common.yml` の一箇所が所有する:

```yaml
kafka:
  url: ${KAFKA_URL:localhost:29092}
  security:
    protocol: ${KAFKA_SECURITY_PROTOCOL:PLAINTEXT}
  ssl:
    trust-store-location: ${KAFKA_SSL_TRUST_STORE_LOCATION:}
    trust-store-password: ${KAFKA_SSL_TRUST_STORE_PASSWORD:}
    key-store-location:   ${KAFKA_SSL_KEY_STORE_LOCATION:}
    key-store-password:   ${KAFKA_SSL_KEY_STORE_PASSWORD:}
    key-store-type:       ${KAFKA_SSL_KEY_STORE_TYPE:PKCS12}
    trust-store-type:     ${KAFKA_SSL_TRUST_STORE_TYPE:JKS}
```

環境変数名は `k8s/10-configmap.yaml` がすでに使っていた `KAFKA_SSL_*` に統一した。
（共有設定は `KAFKA_TRUST_STORE_*` という別の名前を見ていたため、マニフェストが注入する値が
どこにも届いていない状態だった。）

### `file:` プレフィックス — どこで必要で、どこで不要か

同じパス文字列なのに、消費する側ごとに解釈が異なる。これを知らずに起動が落ちることが実際にあった。

| 設定 | プレフィックス | 理由 |
|---|---|---|
| `server.ssl.key-store` | 任意 | Spring Boot が普通のファイルパスも処理する |
| `eureka.client.tls.key-store` / `trust-store` | **`file:` 必須** | Spring の `Resource` として解釈 → プレフィックスがないと ServletContext 相対パス |
| `spring.cloud.gateway.server.webflux.httpclient.ssl.trust-store` | **`file:` 必須** | 上と同じ |
| `spring.data.redis.ssl.*` | **プレフィックス禁止** | `RedisCacheConfig` がコードで `"file:" +` を直接付ける |
| `kafka.ssl.*-location` | 無関係 | Java 側の `checkPath()` が `file:` を剥がす |

プレフィックスがないと、ファイルがちゃんと存在していても次のように落ちる:

```
KeyStore not exists: ServletContext resource [/home/.../certs/truststore.p12]
```

そのため**環境変数の値はプレフィックスなしの純粋なパス**にしておき、`file:` が必要な 2 箇所（eureka-server、
gateway-server の `eureka.client.tls`）だけ yml で `file:${...}` と付ける。

## 2. ストア形式も注入する

19 個の Kafka 設定クラスがこうなっていた:

```java
.put(SslConfigs.SSL_KEYSTORE_TYPE_CONFIG, "JKS")        // ← ハードコード
.put(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG, checkPath(keyStoreLocation))   // ← .p12 ファイル
```

PKCS12 ファイルを JKS と宣言していたのである。`security.protocol` を `SSL` に変えた瞬間、
`java.io.IOException: Invalid keystore format` で全サービスが起動不能になる。
ローカルブローカーが PLAINTEXT だったため、一度も踏まれなかっただけである。

形式もプロパティに切り出し、デフォルト値は `ops/07` が作るファイル形式と一致させた。
`ConfigurationGuardrailTest` がハードコードの再発を防ぐ。

## 3. ゲートウェイの TLS 検証を有効にした

```yaml
useInsecureTrustManager: ${GATEWAY_INSECURE_TRUST_MANAGER:false}   # 以前は true 固定
```

`true` はダウンストリーム証明書の検証を丸ごと無効にする — 決済トラフィックが通る区間で MITM が
成立する。同じブロックに `trust-store` を書いてあったが、`useInsecureTrustManager: true` がそれを
無視するので何の効果もなかった。

デフォルトを検証する側に変えた。すべてのサービス証明書が同じ CA で署名されていて、SAN にサービス
名が入っていればそのまま動作する — `ops/07` はまさにそういう証明書を作る。外部証明書へ
移行する過渡期にだけ `GATEWAY_INSECURE_TRUST_MANAGER=true` で一時的に回避する。

## 4. 発行と注入 — `ops/07-issue-certificates.sh`

```bash
./ops/07-issue-certificates.sh                 # 開発用: ローカル CA + サービス証明書
set -a; source ./certs/certs.env; set +a       # 注入
./gradlew :gateway-server:bootRun
```

### 成果物

| ファイル | 形式 | 使う場所 |
|---|---|---|
| `ca.crt` / `ca.key` | PEM | ローカル CA（dev モード）· `LOCAL_CACERT_PATH` |
| `tls.crt` / `tls.key` | PEM | Redis SSL、ES トラストバンドル · `LOCAL_CRT_PATH` / `LOCAL_KEY_PATH` |
| `keystore.p12` | PKCS12 | `server.ssl.key-store` · `LOCAL_KEY_STORE_PATH` |
| `truststore.p12` | PKCS12 | `LOCAL_TRUST_STORE_PATH` |
| `truststore.jks` | JKS | レガシーな消費者 |
| `kafka-keystore.p12` | PKCS12 | `KAFKA_SSL_KEY_STORE_LOCATION` |
| `kafka-truststore.jks` | JKS | `KAFKA_SSL_TRUST_STORE_LOCATION` |
| `certs.env` | sh | 上記すべてを export |
| `k8s-tls-keystore.secret.yaml` | k8s Secret | `kubectl apply -f` |

### モード

| モード | 用途 |
|---|---|
| `MODE=dev`（デフォルト） | ローカル CA を作り、その CA でサービス証明書を発行。オフライン・無料・即時 |
| `MODE=letsencrypt` | certbot（ACME）で実ドメインの証明書を無料発行 → Java ストアに変換 |
| `MODE=import` | すでに持っている証明書/鍵をストアに変換するだけ |
| `MODE=verify` | 既存成果物の検証のみ（cron で期限切れを監視） |

```bash
MODE=letsencrypt LE_DOMAIN=api.example.com LE_EMAIL=ops@example.com \
  ./ops/07-issue-certificates.sh
```

### パスワードが 1 つである理由

キーストア・鍵・トラストストアのパスワードは**同じでなければならない**。選択ではなく既存設定の制約である:

- `gateway-server/application-local.yml`: `trust-store-password: ${LOCAL_KEY_STORE_PASSWORD}`
- 各サービス: `key-password: ${LOCAL_KEY_PASSWORD}`
- `RedisCacheConfig`: `storePassword: ${LOCAL_KEY_PASSWORD}`

すでに 2 つの名前が混在して使われているため、値を別々にすると必ずどこかが壊れる。
スクリプトはパスワードを自動生成して `certs.env`（権限 600）にだけ記録し、画面には出力しない。
再実行時は既存の `certs.env` の値を再利用し、デプロイ済みの Secret とずれないようにする。

### SAN — ここにない名前で接続するとハンドシェイクが壊れる

デフォルトの SAN は `localhost`・`127.0.0.1`・`::1`・`host.docker.internal`、compose ネットワークのサービス
名 12 種、インフラ名 5 種、そして `*.ecommerce.svc.cluster.local` を含む。
`SANS=` で置き換えられる。

証明書には `serverAuth` と `clientAuth` を**両方**入れる。サービス同士が互いにクライアントとしても
接続するからである（Kafka mTLS、Feign、Eureka peer）。`serverAuth` だけだと mTLS で相手に拒否される。

### 自己検証

スクリプトは発行後に 6 項目を検査し、1 つでも失敗すれば exit 1 する:

1. チェーン検証（`openssl verify`）
2. 証明書と秘密鍵が同じペアか（公開鍵ハッシュの比較）
3. SAN の存在と `localhost` の包含 — SAN のない証明書は Java 17 が拒否する
4. 有効期限（デフォルトで 30 日前に警告）
5. **Java が実際にストアを開けるか**（`keytool -list` 5 種）— サービスが起動中に通る経路をあらかじめ踏む
6. 秘密鍵ファイルの権限 600

**これらの検証が飾りではないことも確認した。** 有効期間 10 日の証明書を発行すると `WARN: 30일 이내에
만료됩니다` が表示され、キーストアファイルをわざと壊すと `FAIL keystore.p12 (PKCS12) 를 열 수
없습니다` とともに exit 1 で終わった。失敗しない検査は検査ではない。

### 実際に確認したこと

`ops/07` で発行した証明書だけで eureka-server を起動して確認した:

```
$ curl --cacert ./certs/ca.crt https://localhost:8761/actuator/health
{"status":"UP"}

$ openssl s_client -connect localhost:8761 -CAfile ./certs/ca.crt
subject=CN = localhost, O = ecommerce-msa
issuer=O = ecommerce-msa, CN = ecommerce-msa local development CA
Verify return code: 0 (ok)

$ curl https://localhost:8761/actuator/health          # CA なし
curl: (60) SSL certificate problem: self-signed certificate in certificate chain
```

## 5. 更新

Let's Encrypt 証明書は 90 日ごとに更新される。更新は PEM だけを変えるので、**キーストアを作り直さ
なければサービスは古い証明書を使い続ける。** このギャップが実際の障害の常連原因である。

`MODE=letsencrypt` は `certs/renew-deploy-hook.sh` を一緒に生成する:

```bash
sudo ln -sf "$PWD/certs/renew-deploy-hook.sh" \
     /etc/letsencrypt/renewal-hooks/deploy/rebuild-java-stores.sh
```

フックは更新された PEM でストアを作り直し、kubectl があれば Secret を apply した後
`rollout restart` まで行う。Java は起動時にキーストアを一度しか読まないため、再起動が必要である。

期限切れの監視は cron に検証モードを仕掛ければよい:

```cron
0 6 * * * cd /srv/ecommerce && MODE=verify WARN_DAYS=30 ./ops/07-issue-certificates.sh
```

## 6. 絶対にコミットしないこと

`certs/` 全体が `.gitignore` の対象である。スクリプトも、出力ディレクトリが git の追跡対象であれば
`FORCE=1` なしでは実行を拒否する。

秘密鍵が git 履歴に入ると、[ops/01](../ops/01-rotate-db-password.sh) の DB パスワードと
まったく同じことが起こる — **ファイルを消しても履歴には残り**、唯一の解決策は全量再発行である。

## 6.1 同じ類の残り — コードに埋め込まれたデプロイ情報

証明書パスを整理していると、同じ性質のものがほかにもあった。すべて「このコードは 1 人の 1 環境でしか
動かない」という意味である。

| 場所 | あったもの | 変えたもの |
|---|---|---|
| `RegistrationListener` | 登録確認メールのリンクに特定のデプロイ先ホスト名をハードコード | `${app.public-url:https://localhost:8000}` |
| `*-outbox-connector-postgresql.json` | `"database.hostname": "<공개 호스트>"` | Connect ファイル config provider の参照（`db-host`） |

登録確認リンクが特にまずかった。どの環境で実行してもリンクがその 1 つのホストを指していたため、
ローカルやステージングで登録したユーザーは**アカウントを有効化する方法がなかった。** そのホストが落ちれば
本番でも同じである。

コネクタのホスト名はパスワードほど致命的ではない。ただ、リポジトリを読む誰にでも
「このアドレスの 5435 番ポートに DB がある」と教える情報なので、偵察コストをタダで下げてやる理由はない。
**すでに公開された履歴は取り消せないので**、そのホストのファイアウォール点検は依然として必要である
（[docs/13 §1](13-secrets-dbha-es-search.md)）。

## 7. 残っていること

- **HSM/KMS 連携** — 秘密鍵がいまだにディスク上に平文で置かれている。本番では cert-manager +
  Vault PKI またはクラウド KMS に移すのが定石である。このスクリプトはその前段階を埋める。
- **証明書ローテーションの自動化（k8s）** — cert-manager を導入すれば `Certificate` リソースの宣言だけで
  発行・更新・Secret への反映が自動化される。現在はフック + `rollout restart` で代替している。
- **個別のサービス証明書** — 今はすべてのサービスが SAN の多い**1 つの**証明書を共有している。
  本番ではサービスごとに別のアイデンティティを与えるほうがよい（`SANS=` でサービス別の発行が可能）。サービス間 mTLS（docs/57 §16）がこの証明書を
  クライアント証明書として使い始めたので、サービスごとに分ければ `internal-auth.allowed-subjects` で「どのサービスか」まで区別できる。
