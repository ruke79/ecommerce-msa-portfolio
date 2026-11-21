# 57. 残りの優先事項 — docs/54 §10.1 のオープン項目を順番に

**日本語** | [한국어](57-remaining-priorities.ko.md)

> 🕰️ **状態（2026-11-14）— 各節はその時の記録で、その後の本番適用は次のとおりです。** §11 の「サービス間の mTLS は依然としてない」は §16 · §18 で解決した。§16 の判定 4 つ — `INTERNAL_AUTH_MODE` · `_RULES_MODE` · `_IDENTITY_MODE` · `EUREKA_WRITE_AUTH_MODE` — はリポジトリの既定値（`k8s/10-configmap.yaml`）も本番も **enforce**（身元ヘッダー 2026-10-26 `d68f0307e979`、Eureka 書き込み 2026-10-30）。ストアのパスワード交換（ops/59）は 2026-10-25 に本番適用 · 10-26 にコピーを整理。§18 の Certificate は 10 → **12**（gateway-server · config-server の身元）。残りの作業は [docs/85 §8](85-release-deploy-runbook.md)。

> 最初の優先事項リスト（過剰販売・通貨表示・Saga の遅延）を終えた後、docs/54 §10.1 に「オープン項目」として残っていた
> もののうち **コードで閉じられるもの** を、深刻度・作業量の順に処理した記録である。1 節が 1 コミットだ。

## 1. 例外の文言がレスポンス本文に漏れていた件

### 症状

- 共通の例外ハンドラー（`CustomRestExceptionHandler.handleAll`）の 500 が `ex.getLocalizedMessage()` を本文に載せていた。
- コントローラー **57 か所**（cart・catalog・order・user）が `catch (RuntimeException e)` で受けて `new MessageResponse(e.getMessage())`
  を返していた。chat の例外ハンドラー 2 つも同じだった。
- 400 のハンドラーも `getLocalizedMessage()` を載せていた — 検証失敗なら `Validation failed for argument [0] in public
  ResponseEntity com.project....Controller.method(...)`、つまり **コントローラーのメソッドシグネチャ** だ。制約違反はルート Bean の
  完全なクラス名を付けていた。

### なぜ問題か

`catch (RuntimeException e)` は、サービスが人に向けて書いた文言（「사용 기간이 지난 쿠폰입니다.」）と **ライブラリの例外を
一緒に** 受け取る。JDBC・JPA の例外の文言には SQL、テーブル・カラム・制約の名前、入力値があり（`Key (user_id, coupon_id)=(42, 7)
already exists`）、Feign・Redis の例外には内部ホストがある。JVM の NPE の文言（`Cannot invoke "…Order.getTotal()" because
"order" is null`）はコードの構造だ — docs/47 で実際に画面に出た。

逆に文言を一律に一般化すると **画面が使う文言が消える**（パスワード再設定リンクの期限切れ、クーポンの拒否理由など）。
だから「隠す」ではなく「選ぶ」が必要だった。

### やったこと — `common/exception/ClientMessage`

許可リストだ。文言を外に出すのは 2 つだけだ。

1. `com.project.` 配下の例外（ドメイン例外）。
2. 自分たちのコードが文言を入れて投げる標準例外の 4 つ — `RuntimeException`・`IllegalArgumentException`・
   `IllegalStateException`・`NoSuchElementException` — **まさにそのクラスのときだけ**。サブクラスは含まない: ライブラリ
   例外の大半がこの 4 つのサブクラスだ（`NumberFormatException`、`DataAccessException` 系の祖先など）。

そしてラップしただけの例外（`new RuntimeException(e)` — メッセージが原因の `toString()`）は、許可リストに入っていても原因の文言なので
隠す。それ以外は一般的な文言だ。元の文言はログに残る。

拒否リスト（JDBC・JPA・…を塞ぐ）にしなかった理由: リストにないライブラリが一つ入ってくると、**漏れる方向に** 間違える。
許可リストなら、間違えても一般的な文言が出るだけだ。

| 箇所 | 前 | 後 |
|---|---|---|
| コントローラー 57 か所 | `e.getMessage()` | `ClientMessage.of(e)` |
| 共通 500 | `ex.getLocalizedMessage()` | `ClientMessage.of(ex, "서버 오류가 발생했습니다…")` ＋ スタックはログへ |
| `CustomException` | 500 に落ちる | `ErrorCode` のステータス・文言（🐛 `USER_NOT_FOUND` が 404 ではなく 500 だった） |
| 400 ハンドラー | メソッドシグネチャ・クラス名 | 固定の文言、何が間違っていたかは `errors`（フィールド名・検証の文言） |
| chat 500 | `e.getMessage()` | 固定の文言 ＋ ログ |
| user 401 | Spring Security の文言 | 「인증이 필요합니다.」 |

### ガード

`ExceptionMessageGuardrailTest`（common） — コントローラー・`@ControllerAdvice` のファイルで `getMessage()` がログ呼び出しの外に
あれば失敗する。文単位で見て（複数行の `log.warn(...)` もログとして認める）、文字列リテラル内の `{}` と行末コメントの
`'` は境界として読まない。1 か所を元に戻して失敗することを確認した。

⚠️ Gradle はこのテストが他のモジュールのソースを読むことを知らない — common が変わっていなければ **UP-TO-DATE として
スキップする。** 確認するときは `:common:cleanTest :common:test`。

### 残っているもの

- コントローラーの外（サービス）でレスポンスを作る箇所はガードの範囲外だ。調べた範囲ではなかった。
- ~~自分たちのコードの `IllegalStateException` の中には、人に向けて書いたものではない文言もある~~ → §15 で許可リストから外した。

## 2. リフレッシュトークンの寿命が事実上無限だった件

### 症状

| 箇所 | コード | 結果 |
|---|---|---|
| Redis | `set(key, token)` の後に `expire(key, System.currentTimeMillis() + jwtRefreshExpirationMs, MILLISECONDS)` | 期間の位置に **epoch 時刻**（約 1.8×10¹² ms）が入り、TTL が約 **56 年** |
| Cookie | `createCookie(REFRESH, token, jwtRefreshExpirationMs)` → `setMaxAge(int)` | `Max-Age` は秒なのにミリ秒（1,209,600,000）を入れ、14 日の Cookie が約 **38 年** |

2FA 待機 Cookie とデバイス Cookie は正しく秒を渡していた — 同じメソッドを呼ぶ側ごとに単位が違っていた。

### なぜ問題か

リフレッシュトークンの JWT 自体には 14 日の有効期限があるので、**古いトークンでログインできるわけではない。** しかし

- ログアウトしていないセッションの Redis キーが消されずに溜まり続ける（メモリ）。
- ブラウザは期限切れのトークンを 38 年間持ち歩き、毎回送る。サーバーの寿命ポリシー（14 日）とクライアントの寿命がずれていると、
  後で誰かが JWT の有効期限チェックを緩めた瞬間に、それらの Cookie がすぐによみがえる。

### やったこと

- `JwtUtils.createCookie(key, value, Duration maxAge)` — 単位を **型で** 固定する。呼び出し 7 か所をすべて `Duration` に
  （リフレッシュは `refreshTokenLifetime()`、2FA 待機は `Duration.ofMillis(TTL_MS)`、デバイスは `ofSeconds(...)`、削除は `ZERO`）。
- `RefreshTokenRepository.save` — `set(key, token, Duration)` の 1 コマンド。set と expire の間で落ちて TTL のないキーが残る隙もない。
- テスト `RefreshLifetimeTest`（3） — Cookie の Max-Age が 1,209,600 秒、削除用 Cookie は 0、Redis に TTL 14 日で一度に書き込み、`expire` を
  呼ばない。既存テストの Cookie のモック（mock）も `Duration` を受け取るように直した。

ログアウトのブラックリスト（`registerBlacklist`）はすでに **残り時間**（有効期限 − 現在）を渡していたので、直すものはなかった。

### 運用 — すでに溜まったキー

デプロイ後に新しく発行されるトークンは 14 日で入る。既存のキーはそのまま約 56 年だ。ログアウトすれば消えるが、そうでない
セッションは残る。必要なら、TTL が 14 日より長いキーだけを 14 日に縮める（値は出力しない）:

```bash
# k8s/90-dev-infra.yaml の Redis（ネームスペース redis）。パスワードは Pod 内の REDIS_PASSWORD（シークレット redis-auth） — 外には出さない。
kubectl -n redis exec -it deploy/redis -- sh -c '
  redis-cli -a "$REDIS_PASSWORD" --no-auth-warning --scan | while read k; do
    t=$(redis-cli -a "$REDIS_PASSWORD" --no-auth-warning ttl "$k")
    [ "$t" -gt 1209600 ] && redis-cli -a "$REDIS_PASSWORD" --no-auth-warning expire "$k" 1209600 >/dev/null && echo "$k"
  done | wc -l'
```

14 日より長い TTL を正常に持つキーはこのリポジトリにはない（セッション・ブラックリスト・ロックはどれもそれより短い）。縮めたキーの **件数** だけを出力する。

⚠️ Cookie はブラウザにある — 次のログイン・トークン更新のときに新しい `Max-Age` で上書きされる。サーバー側で別途やることはない。

## 3. 商品の星評価 — 誰でも上書きできた件、そしてレビューが保存されなかった件

### 症状

`PUT /api/product/rating?id=&rating=` を、catalog の書き込みインターセプター（`AdminWriteInterceptor`）は「ログインのみ」要求していた。

| 呼び出す側 | 身元ヘッダー | 結果 |
|---|---|---|
| ブラウザ（ログインした誰でも） | あり | ✅ **通過** — どの商品の星評価も任意の値（範囲チェックなし — 100 や NaN も）で上書きできた |
| user-service `ReviewService.publishRating`（レビューの平均） | **なし**（Feign はヘッダーを載せない） | ❌ **401** — 星評価の例外より身元チェックが先だった |

2 つ目のほうが大きかった。`publishRating` はレビュー保存と **同じトランザクション** で例外を投げ、コントローラーがそれを「レビュー登録失敗」に
変えていた。コード上は、このインターセプターが入って以降（`3d130d1`）**レビュー作成が常に失敗** する（本番で再現はしていない — 本番ログの
`REVIEW_REGISTRATION_FAILED` で確認できる）。

正当な呼び出し元は一つだけだ — 星評価は人が付ける値ではなく、レビューの平均だ。フロントはこの経路を呼ばない。

### やったこと

- **内部専用** — 星評価の経路は `InternalCallGuard.allowed`（身元ヘッダーなし = 内部、または管理者）だけを通す。一般ユーザーは 403。
  身元チェックより **先に** 見る（内部呼び出しにはヘッダーがない）。ゲートウェイの書き込みルート（`catalog-service-write`）は常に JWT フィルターを
  通るので、外から来た PUT には身元ヘッダーが必ずある —「ヘッダーなし = 内部」は他の内部 API と同じ前提だ（docs/27 §9b）。
- **範囲チェック** — 0 以上 5 以下、有限値のみ。外れたら 400。
- **レビューを評価に縛らない** — `publishRating` が失敗したら警告だけ残してレビューは保存する。評価は再計算できる派生値で、
  次のレビューが再び送る。

テスト: インターセプター（一般ユーザー 403・内部は通過・管理者は通過）、コントローラー（403・内部 200・範囲外 400 の 3 つ）、レビュー（評価の反映に失敗しても保存）。

### 残っているもの

- ~~評価の反映に失敗したまま新しいレビューがなければ、評価がずれたまま残る~~ → §13 の日次突合。
- ~~「購入した人だけがレビュー」の検証はない~~ → §13。

## 4. ゲートウェイのルート id の重複 — ログインのレートリミットが上書きされかねなかった件

`gateway-server/.../application-local.yml` にルート id `user-service` が **2 回** あった。

| ルート | パス | レートリミット |
|---|---|---|
| 1 つ目 | `/api/auth/public/**`（ログイン・登録）、トークン更新、OAuth2 コールバック — 認証 **前** | **1/s、バースト 10**（IP 基準 — 総当たり防御） |
| 2 つ目 | `/api/user/**`、`/api/auth/**`、SSE — 認証 **後** | グローバルのデフォルト 20/s、バースト 40 |

`RedisRateLimiter` は設定を **ルート id で** 保存する。id が同じだと、2 つ目のルートのデフォルト値が 1 つ目の強い制限を上書きしうる —
ログイン総当たりの防御が設定 1 行で消えるのに、普段は何の症状もない（docs/38 §2.6 #4 で「実測が必要」として残しておいたもの）。

- id を `user-service-public` / `user-service-auth` に分けた。ルート id を参照している箇所（ダッシュボード・設定）はなかった。
- ガード **B-62**（`ConfigurationGuardrailTest`） — ゲートウェイの設定ファイル内でルート id が重複すると失敗する。重複を復活させて失敗することを
  確認した。

⚠️ ゲートウェイのメトリクス（`spring_cloud_gateway_requests_seconds{routeId=…}`）のルート名が変わる。このリポジトリのダッシュボード・アラートは
ルート id を使っていない。

## 5. 管理者の注文画面に在庫不足の数量

在庫の過剰販売の補償（docs/53 §7）は、不足分を品目の `short_qty` に記録して自動返金する。サーバーのレスポンス（`AdminOrderItemDto.shortQty`）には
あったのに **画面が表示していなかった。** そのため docs/53 §7 の残った限界 —「配送が不足の記録より先に作られていたら、配送数量は元の
数量のままだ、管理者が送り状を合わせる必要がある」— に管理者が気づく方法がなかった。

- 品目のサマリーに `在庫不足 N`、注文行に赤い警告「在庫不足 N点 — 自動返金の対象です。発送数量から差し引いてください」。
- 🐛 返品入庫パネルの上限が `qty − returnedQty` だった — 一度も出荷されていない不足分まで「戻ってきうる数」に見えていた。サーバーの
  `OrderProduct.remainingToReturn`（`qty − shortQty − returnedQty`）と同じ式に揃えた（サーバーはもともと過剰な入庫を拒否する —
  画面が拒否される選択肢を差し出していたのだ）。
- テスト 3（サマリー・警告、不足がなければ警告なし、入庫の上限）。

## 6. CI のシークレットスキャン

漏えいの再発防止装置がプッシュ・PR の段階になかった（docs/30 §5） — `ops/20` は人がたまに回していた。公開リポジトリの漏えいは **コミットされた瞬間に**
終わったことだ（スクレイパーが数分以内に読む）。いちばん安いチェックをいちばん前に置く。

### `ops/20 --range A..B` — 新しいモード

| モード | 何を見るか | 見逃すもの |
|---|---|---|
| `--head` | 現在のツリー | PR の中で **入れて次のコミットで消した** シークレット — マージされると履歴にそのまま残る |
| （デフォルト）履歴全体 | すべての blob | なし。その代わり重い（プッシュのたびに回すには） |
| `--range A..B` ⭐ | その範囲のコミットが作った blob | 範囲外 — それはすでに入っている履歴だ（履歴全体モードの担当） |

陽性対照: 一時リポジトリで偽の AWS キーの形をコミットし、次のコミットで消した — `--head` は 0 件、`--range` は **1 件・exit 2**、
出力には種類とパスだけがあり、値はない。

### CI（`build.yml` の `secrets` ジョブ）

- すべてのプッシュ・PR で（週次の復元訓練スケジュールは除く）`--head` と `--range` を回す。範囲は PR なら `origin/<대상>..HEAD`、プッシュなら
  `<before>..HEAD`、新しいブランチの最初のプッシュ（before = 0000…）なら `origin/<기본 브랜치>..HEAD`。
- 見つかれば失敗し、**リリース候補への昇格（`release-candidate`）がブロックされる** — `needs` に入れた。
- ログが公開されていても新たに漏れはしない — スクリプトは値を出力しない。

### 残っているもの

- **GitHub Push Protection**（プッシュ自体を拒否）はリポジトリの設定だ — コードでは有効にできない。Settings → Code security → Secret
  scanning → Push protection。CI はプッシュの **後で** 知らせるので、止めるのはこちらだ。
- ~~`frontend/dist.zip`（古いマップ・古いホストを含む）がまだ追跡されている~~ → §12 で追跡を外した。履歴のコピーは残る（docs/38 §3 #1）。

## 7. サービスの actuator・API 仕様がゲートウェイ経由で漏れていた件

### 症状

ゲートウェイのルートは `/catalog-service/**` のようにサービスのプレフィックス配下を丸ごと渡し、`RewritePath` でプレフィックスだけを外す。そして
すべてのサービスが共通設定（`application-common.yml`）で `prometheus, health, info, metrics` を公開し、springdoc が入っている。

| リクエスト | 届く先 | ログイン |
|---|---|---|
| `GET /catalog-service/actuator/prometheus` | catalog のメトリクスすべて | **不要**（catalog の読み取りルートは公開） |
| `GET /catalog-service/v3/api-docs` | catalog の API 仕様 — 管理者 API を含む | 不要 |
| `GET /order-service/actuator/metrics/...` など | 他のサービス | ログインさえすれば |

docs/54 §10.1 に「推定」として残しておいたものだ。ルート・公開設定を読めばそのまま届く（実際のリクエストでは確認していない）。
メトリクスには内部パス・呼び出し量・エラー率・JVM の状態が、仕様には管理者 API の全体像がある — 攻撃の準備に使われる情報だ。

### やったこと — ゲートウェイ 1 か所で

`InternalEndpointBlockFilter`（GlobalFilter、最優先）: パスが `/<…-service>/{actuator|v3|swagger-ui|swagger-ui.html|api-docs|
swagger-resources|webjars}/…` なら **404**（存在するという事実も知らせない）。

- サービスごとに塞がなかった理由: ルート・サービスが増えたときに漏れる。入口は一つだ。
- 迂回防止 — パスを一度 URL デコードし、重なったスラッシュをまとめ、小文字にし、マトリックス変数（`;x=…`）を外してから見る
  （`%61ctuator`、`//actuator`、`/Actuator`、`actuator;x=1` はすべて遮断 — テストで固定）。
- 影響のないもの: Prometheus はサービスを **直接** スクレイプする（observability/prometheus.yml）。ゲートウェイ自身の `/actuator/**` は、最初の区切りが
  サービス名ではないので対象外だ（そのパスはトンネルが外に渡さない — docs/27 §8）。

テスト `InternalEndpointBlockFilterTest`（4） — 遮断 5 つ、迂回 5 つ、通過（業務 API・名前の似た業務パス・ゲートウェイ自身）、404 で
終わって後段に渡さない。

### 残っているもの

- ~~サービス内でも springdoc をオフにする~~ → §12（クラスターの ConfigMap）。管理ポートの分離（`management.server.port`）は残った — プローブ・
  Prometheus の対象も一緒に変える必要がある。

## 8. リフレッシュトークンがアクセストークンとして通っていた件 · user-service が期限切れトークンを受け入れていた件

### 症状

| 箇所 | コード | 結果 |
|---|---|---|
| user-service `JwtUtils` | アクセス・リフレッシュを **同じ鍵・同じクレーム**（sub・uid・role・sessionId）で署名 | 2 つのトークンを区別できない |
| ゲートウェイ `JwtAuthorizationGatewayFilterFactory` | 署名・有効期限・uid だけを見る | 14 日の **リフレッシュトークンを `Authorization: Bearer` で送ると通過** — ログアウトした後でも（ログアウトは Redis のリフレッシュの記録を消すだけ） |
| chat-service `TokenHandler.verifyToken` | 署名・有効期限のみ | STOMP CONNECT・API にリフレッシュトークンで接続できる |
| user-service `JwtAuthFilter` | 有効期限チェックがコメントアウト、`getIdFromJwtToken` が `ExpiredJwtException` を捕まえて subject を返す | **期限切れのアクセストークンを有効として** 受け入れていた |

リフレッシュ Cookie はパスが `/` なので、すべてのリクエストに載って回る（HttpOnly なのでスクリプトは読めないが）。一度漏れれば 14 日有効な API の鍵になり、
ログアウトでも閉じられなかった。

### やったこと

- トークンに種別クレーム `tokenType` = `access` | `refresh`（user-service `JwtUtils.TOKEN_TYPE_CLAIM`）。アクセストークンを作る 4 つの経路すべてで。
- **ゲートウェイ・chat・user-service は `access` だけ** を身元として受け入れる。クレームのない古いトークンも拒否 — 画面は 401 ならリフレッシュで新しい
  トークンを受け取る（TokenRefresher）。
- リフレッシュ・ログアウトの経路は **`access` を拒否** し、`refresh` またはクレームのない古いリフレッシュを受け入れる（古いリフレッシュは最大 14 日後に
  自然に消える — そのときにこの互換処理を取り除けばよい）。
- user-service の認証フィルター: 有効なアクセストークンのときだけ認証を立て、そうでなければ認証なしで渡す — 保護されたパスは `JwtAuthEntryPoint`
  が 401、公開パス（登録・パスワード再設定）は古い Cookie が載ってきてもブロックされない。`getIdFromJwtToken` は期限切れなら例外を投げる。
- ブロックリスト照会のログ（`blackLisTToken : …`）を削除した。

テスト: `TokenTypeTest`（user、4）、`JwtAccessTokenTypeTest`（gateway、3）、`TokenHandlerTypeTest`（chat、2）。

### ⚠️ デプロイ順序 — user-service が先

ゲートウェイ・chat を先に上げると、まだ古い user-service が **種別のないトークン** を発行するので、更新しても 401 が続き → 全員がログアウトされる。

```bash
./ops/33-deploy-some.sh user-service gateway-server chat-service   # user-service → バックエンド → ゲートウェイの順に 1 つずつ上げる
```

`ops/33` はすでに user-service をゲートウェイより先に上げ、2 つをペアにしている。全体デプロイ（`ops/28`）も同じバージョンに収束するので、
数秒の混在区間の後は正常だ。

デプロイ直後にログインしていたユーザーは、リクエスト 1 回が 401 → 自動更新へと移る（画面には見えない）。

## 9. ゲートウェイの自動ルート（discovery locator）をオフにした

`spring.cloud.gateway.discovery.locator.enabled: true` だった。Eureka に登録されたサービスごとに `/ORDER-SERVICE/**` のようなルートが自動的に
作られるが、**そこには JWT フィルターがない。** グローバルフィルター（`IdentityHeaderScrubFilter`）がクライアントの身元ヘッダーを消すので、その経路から入った
リクエストは下位のサービスに **身元ヘッダーなしで** 到着する — `InternalCallGuard.allowed` が「内部呼び出し」と信じる、まさにその形だ
（管理者の注文一覧、内部の購入統計、配送情報の照会など）。

外から届かなかった理由は一つだった — Cloudflare トンネルが **小文字の** `…-service` プレフィックスだけをゲートウェイに渡す（大文字のサービス id の自動
ルートとは一致しない）。防御がトンネル設定の正規表現 1 行にかかっていた。

使っている箇所はなかった — 画面は明示ルート（`/order-service/**` など）だけ、サービス同士は Eureka で直接（`lb://`）呼ぶ。オフにし、ガード **B-64**
（`ConfigurationGuardrailTest`）がゲートウェイ設定で `locator: enabled: true` をブロックする（オンにして失敗することを確認した）。

## 10. 認証 SMS — 番号を変えながら送ると止まらなかった件

`VerificationService.issue` の制限は **番号単位** だけだった（再送 60 秒、番号あたり 1 日 10 回）。コメントには「番号あたり・IP あたり」と書かれて
いたが、IP の制限はなかった。番号を変えながらリクエストすれば、アカウント 1 つで番号ごとに 10 回ずつ — 事実上無制限の実送信で、料金はこちらが払う
（docs/38 §1.6）。

| 層 | 基準 | 上限 | 引っかかると |
|---|---|---|---|
| 番号 | 再送間隔 · 1 日 | 60 秒 · 10 回 | 429 |
| **アカウント**（新規） | `User-Id` — ゲートウェイが通知のパスすべてにログインを要求するので常にある | 1 日 10 回、番号とは無関係 | 429 |
| **全体**（新規） | サービスの 1 日の発行数 | `notification.verification.daily-global-limit`（デフォルト 1000、0 以下ならオフ） | 429 ＋ **ERROR ログ**（アラート対象） |

IP で制限しなかった理由: このサービスが見る IP はトンネル・ゲートウェイを経由した値で、`X-Forwarded-For` はクライアントが偽造できる
（判断には使わないという原則）。アカウントはゲートウェイが検証したトークンから来る。

テスト `VerificationLimitTest`（4） — 番号を変えてもアカウントあたり 10 回、別のアカウントは別枠、全体の上限で止まる、番号あたりの上限は維持。

## 11. ネームスペース間を塞ぐ NetworkPolicy

NetworkPolicy が一つもなかった。クラスター内の **どの Pod からでも** user-service・order-service の内部 API と Postgres・Redis・Kafka・Mongo・ES
にそのまま接続でき、内部 API の「身元ヘッダーなし = 内部呼び出し」（`InternalCallGuard.allowed`）はその前提の上に成り立っている（docs/27 §14）。

`k8s/95-network-policy.yaml` — **入ってくる（ingress）接続だけ**:

| ネームスペース | 許可 |
|---|---|
| ecommerce | ecommerce 内同士（サービス同士・cloudflared・バックアップ CronJob） ＋ kube-system（traefik） → frontend・gateway-server |
| postgres・redis・kafka・mongodb・elasticsearch | 自分のネームスペース ＋ ecommerce |

それ以外（他のネームスペース）から入ってくる接続は遮断される。ノード自身（kubelet のプローブ・`kubectl exec/port-forward`・ノードの可観測性スタック）は、k3s の
ポリシーコントローラーがポリシーとは無関係に許可する。

**出ていく接続は塞がなかった** — 決済会社・SMS・メール・配送業者の API と DNS を使っており、そのリストが間違っていると決済が止まる。入ってくる側だけで
「他のネームスペースから内部 API・DB へ」行く道は閉じる。

### 適用は `ops/35-network-policy.sh apply` でのみ

kustomization には入れなかった。NetworkPolicy は間違えると **静かに** 切断し（Pod は Running なのに決済が止まる）、クラスターが施行
しなければ、適用しても **静かに** 何も塞がない。だから叩いて確かめる:

```bash
./ops/35-network-policy.sh check    # 現在の状態 — 変更しない
./ops/35-network-policy.sh apply    # 適用 → 確認 → 許可すべきフローが塞がれたら自分で元に戻す（exit 1）
./ops/35-network-policy.sh remove   # 元に戻す
```

🐛 最初の実適用（2026-09-26）で MongoDB 1 か所が「塞がれた」と出て、自分で元に戻した。postgres・redis・kafka・es は同じポリシーで
通過したので、ポリシーのせいではなく **もともと届かない対象**（外部の Atlas を使っているか、Pod がない）だった可能性が高い — ところがスクリプトは適用
**後** にしか叩かなかったので、両者を区別できなかった。今は apply が適用 **前** に一度叩き、「前は開いていたのに後で塞がれたもの」だけをポリシーのせいとみなす。

一時的に立ち上がる busybox の Pod から `nc -z`（TCP 接続のみ、資格情報なし）で — ecommerce からサービス・DB の 4 つが **開いているか**、default から
内部 API・DB が **塞がれているか**、そして ecommerce の Pod が Ready 状態を保っているか。塞がれるべきフローが開いていれば「施行されていない」として
exit 2（ポリシーは残す）。判定ロジックは偽の kubectl で確認し、**実際のクラスターではまだ回していない。**

### 残っているもの

- サービス同士の認証（mTLS・サービストークン）は依然としてない — ecommerce 内の Pod 一つが破られれば内部 API は開いている。次の段階は
  内部 API へのサービストークン（またはサービスメッシュ）だ。
- 出ていく接続の許可リスト（egress）は、外部 API のリストを確定した後で。→ 🕰️ 2026-11-19: IP リストなしでできる第 1 段階を行った（§23）。

## 12. 多層防御の整理 — springdoc のオフ · dist.zip · Redis パスワードの分離

| 何 | 前 | 後 |
|---|---|---|
| API 仕様（springdoc） | ゲートウェイだけで塞いでいた（§7）。サービス内ではオンのままで、クラスター内のどこからでも `/v3/api-docs` で管理者 API の全体像を読めた | `k8s/10-configmap.yaml` の `SPRINGDOC_API_DOCS_ENABLED`・`SPRINGDOC_SWAGGER_UI_ENABLED` = false — クラスターでのみ。ローカル開発は ConfigMap を使わないのでオンのまま |
| `frontend/dist.zip` | 古いソースマップ・古いホストを含むビルド成果物（3MB）が追跡されていた | 追跡を外して削除した（`/dist` はすでに gitignore）。履歴のコピーは残る |
| クラスターの Redis パスワード | DB と同じ値 — サービスは `${LOCAL_DB_PASSWORD}`、redis-auth も同じ値。一つが漏れれば両方漏れ、DB パスワードを変えるたびに Redis も合わせる必要があった | `ops/36-rotate-redis-password.sh` で分離 — 下記 |

### `ops/36` — 途切れなく

1. Redis の default ユーザーに **新しいパスワードを追加する**（`ACL SETUSER default >…`）。古い値も引き続き通る。
2. `ecommerce-secrets` に `SPRING_DATA_REDIS_PASSWORD`、`redis-auth` も新しい値に。環境変数がサービス設定ファイルの `${LOCAL_DB_PASSWORD}` を
   上書きする（Spring の優先順位） — サービスの設定は変えない。
3. ecommerce の Deployment を 1 つずつ再起動（ロールアウトが終わってから次へ）。一つでも失敗したら **そこで止まる** — Redis は 2 つの値の両方を受け付けて
   いるので、止まっても問題ない。
4. Redis の再起動 — 新しいプロセスは新しい値だけを受け付ける（数秒の切断）。`--keep-old` ならスキップする。

値はシェル変数でだけ作り、stdin でだけ渡す（`kubectl patch --patch-file /dev/stdin`、`redis-cli` の標準入力） — 引数・ログ・ファイルには残ら
ない（偽の kubectl で呼び出し引数を記録して確認した）。`ops/27` の Redis チェックは、今は `SPRING_DATA_REDIS_PASSWORD` があればその値で
試し、`redis-cli -a` の代わりに `REDISCLI_AUTH` を使う。

⚠️ **実行は運用者の役目** だ。実際のクラスターではまだ回していない。

## 13. レビュー — 受け取った商品だけ · 評価の日次突合

### 受け取った商品だけレビューを書ける

検証がなかった（docs/38 §1.7） — ログインした誰でも、買っていない商品に星評価を残して平均を動かせた。

| 箇所 | やったこと |
|---|---|
| order-service `GET /api/order/internal/purchased?customerId=&productId=` | その顧客の **配送完了（DELIVERED）・購入確定（COMPLETED）** の注文にその商品があり、1 個以上割り当てられたか（`qty > shortQty`）。🔒 `InternalCallGuard.allowed` — 外からは管理者のみ（他人の購入履歴を商品単位で探り出せないように） |
| user-service `ReviewService.addReview` | **新しいレビュー** のときだけ問い合わせる（編集はすでに資格を確認済み）。画像をアップロードする前に — 拒否されるものはアップロードしない |
| レスポンス | 受け取っていない商品は **403**「받으신 상품만 리뷰를 작성할 수 있습니다.」 · 確認失敗は **503**「구매 내역을 확인하지 못했습니다…」（どちらも日本語カタログあり） |

- **わからなければ受け付けない**（fail-closed）。決済の在庫予約（docs/59）は fail-open だった — あちらは塞ぐと売上が止まり、補償経路がある。
  レビューは塞いでも失うものがなく、誤って受け付けた星評価には元に戻す経路がない。
- 決済だけして、まだ届いていない商品には書けない — 受け取ってみてから書くのがレビューだ。返品した商品には依然として書ける（受け取ったし、返品理由もレビューだ）。
- デモ環境で配送まで待つのが難しければ `review.require-purchase=false`。

### 評価の日次突合

評価はレビューが変わるときに送る（§3）。その呼び出しが失敗するとレビューは残して警告だけを残すので、その後レビューのない商品は評価がずれたまま
残っていた。`ReviewRatingReconciler` — 毎日 18:40 UTC（`review.rating-reconcile.cron`）に、レビューのあるすべての商品の平均を 1 回の
集計クエリで求めて送り直す。冪等なのでインスタンスが複数あっても壊れず、1 商品の失敗が残りを妨げない。

テスト: `ReviewServiceTest`（+3 — 受け取っていない商品の拒否・保存・評価反映なし、確認失敗 503、編集は再度問い合わせない）、
`ReviewRatingReconcilerTest`。

## 14. ユーザー複製の突合 — 最近のものを送り直す

docs/48 §6-③ の残り項目。複製は今は 2 つ — chat の会員行（ニックネーム）と coupon の登録クーポン。イベントは Outbox で必ず出ていくが、
**コンシューマで失敗して DLT に行くと、そこで止まる。** 登録クーポンは「受け取れなかった」という事実を後から知る方法がなく、定期的に走査する以外に
答えがなかった。

`UserReplicationReconciler`（user-service、毎日 19:25 UTC）:

| 直近 N 日（デフォルト 7 — Kafka の保持期間と同じ）以内に | 送り直すもの | 受け取る側が冪等である理由 |
|---|---|---|
| 登録 | `user-created` ＋ `user-updated` | coupon:（クーポン名、ユーザー）があれば作らない。chat: 行がないときだけ作る |
| プロフィール変更（それより前に登録） | `user-updated` | chat: `updatedAt` がより新しいときだけ適用 |
| 退会 | `user-deleted` | chat: 匿名化は何回やっても同じ |

- 受け取る側に「何が抜けたか」を問い合わせる API は要らない — 冪等なら送り直すことがそのまま穴埋めになる。
- 古い登録者には `user-created` を送らない — クーポンが期限切れ・整理された後に再び出ていってはいけない。
- このバッチができる前の漏れは、`user.replication.reconcile.window-days` を一度大きくして再起動した後、元に戻す。
- id カーソルで 200 人ずつ、ページごとに 1 トランザクション（Outbox の記録は呼び出し元のトランザクションを要求する — MANDATORY）。

テスト `UserReplicationReconcilerTest`（2） — 種類別に送るイベント、古い登録者には登録イベントなし、カーソルページング。

## 15. `IllegalStateException` をレスポンス文言の許可リストから外した

`ClientMessage`（§1）は `IllegalStateException` の文言をそのまま外に出していた。ところが自分たちのコードはその例外を **2 通りに** 使っている:

| 用途 | 例 | 箇所 |
|---|---|---|
| 人に見せる状態の衝突 | 「진행 중인 기획전은 삭제할 수 없습니다. 먼저 종료해 주세요.」 · 「채팅 회원 정보가 없습니다…」 | 特集 3、チャット 2 |
| **運用者向け** | 「SKU 생성이 10회 연속 충돌했습니다 — product_sku_seq 시퀀스 상태를 확인하세요」 · 「users.roles 에 ROLE_USER 가 없습니다. 마이그레이션 V3…」 · 「SHA-256 을 쓸 수 없습니다」 · "Failed to acquire lock for …" | 約 60 か所（大半は起動時の設定検証） |

2 つ目がリクエスト経路で起きると、シーケンス・マイグレーション・設定キーの名前がお客様の画面に出ていた。許可リストでは両者を区別できない。

- 新しいドメイン例外 `StateConflictException`（common） —「リクエストは正しいが、今の状態ではできない」、409。特集の 3 つを移し、
  管理コントローラーの catch も変えた。
- チャットの 2 つは `DomainException` に — そして chat の例外ハンドラーに `DomainException` → **400 ＋ その文言** を追加した。以前は同じ
  文言が `IllegalStateException` だったので 500「서버 오류가 발생했습니다」に落ち、**人のために書いた文言がかえって見えなかった。**
- `ClientMessage` の許可リストから `IllegalStateException` を外した — 運用者向けの文言は一般的な文言に、元の文言はログに。
  `RuntimeException`・`IllegalArgumentException`・`NoSuchElementException` はそのままだ（入力検証の文言がこれらだ）。

テスト: `ClientMessageTest`（ISE は一般的な文言、`StateConflictException` はそのまま）、`EventLifecycleTest`・`ChatServiceConnectTest`
（例外の種類）、`DomainExceptionResponseTest`（chat — ドメイン例外は 400、ISE は 500 の一般的な文言）。バックエンドの 9 モジュールすべて通過。

## 16. サービス間の mTLS —「内部呼び出し」を証明書で判定する

### 何が問題だったか

内部専用 API（購入集計・配送情報・在庫予約・星評価・購入有無・ユーザー照会など）は、`InternalCallGuard` で **「ゲートウェイが付けた身元
ヘッダーがなければ内部呼び出し」** と判定していた。ところが身元ヘッダーのないリクエストは内部呼び出しだけではない:

| ヘッダーのないリクエスト | 今塞がれている理由 |
|---|---|
| ゲートウェイの **公開ルート**（ログインなしのカタログ読み取り、Stripe・PortOne の Webhook） | 内部 API がたまたまそのルートのメソッド・パスの外にある — カタログの内部 API は POST なので JWT ルートを通る |
| クラスター内の **任意の Pod** | なし — §11 の NetworkPolicy は他のネームスペースは塞ぐが、ecommerce 内の Pod はそのまま届く |

ルートが一つ変わるか、ecommerce 内の Pod が一つ破られれば、静かに開いてしまう。

### やったこと

すべてのサービスはすでに HTTPS で起動し、同じ内部 CA が署名した証明書（`ops/07`、serverAuth・clientAuth）を持っている。それを使って:

| 箇所 | やったこと |
|---|---|
| 呼び出す側 — バックエンドサービス（k8s/40〜49） | JVM キーストア（`-Djavax.net.ssl.keyStore=…keystore.p12`）を渡す。サービス間の呼び出しはすべて Feign（JDK デフォルトの TLS）なので、サーバーが要求すればこの証明書を **クライアント証明書として** 提示する。ゲートウェイ（31）は JVM キーストアではなく自分の身元（`tls-id-gateway-server`）を HttpClient のクライアント証明書として提示する（2026-10-14、下記「身元ヘッダー」） — それでもヘッダーなしで渡すリクエストは内部呼び出しではない |
| 受ける側 — 同じバックエンド | `SERVER_SSL_CLIENT_AUTH=want` — クライアント証明書を **要求** する（なくても接続はできる: kubelet のプローブ・ゲートウェイ・Prometheus）。eureka・config・ゲートウェイはそのままにする — 間違えると登録・設定が止まって全体が停止する（eureka は 2026-10-14 に `want` + 書き込み判定 — 下の「Eureka 書き込み」） |
| 判定 — `InternalCallerFilter`（common、すべてのサーブレットサービスに自動構成） | 身元ヘッダーが **ない** リクエストだけを見る。TLS で受け取ったクライアント証明書が **内部 CA で署名** されていれば（ここで改めて検証する — JVM のトラストストアが公開 CA を信頼していても、公開 CA の証明書は内部ではない）内部呼び出し。そうでなければ `User-Role: ROLE_ANONYMOUS` を付けて渡す → `InternalCallGuard` が「外部・管理者ではない」と読み、**内部専用 API が 403**。数十か所の呼び出し箇所を直さずに、判定の入力を正す |

モード `INTERNAL_AUTH_MODE`（ConfigMap）:

| モード | すること |
|---|---|
| `off`（コードのデフォルト — ローカル開発） | 何もしない |
| `observe`（段階的適用の最初の段階） | 判定のみ。内部パスに内部証明書なしで来たリクエストを WARN `[mTLS observe]` —「サービス同士の呼び出しがすべて証明書を提示しているか」を確認する段階 |
| `enforce` | 上記のように印を付ける（403）。CA を読めなければ **起動を止める** — 静かにすべての内部呼び出しが 403 にならないように |

`internal-auth.allowed-subjects`（CN のリスト）を与えると、その CN だけを内部とみなす — サービスごとの証明書に移行するときに使う（下記）。

### 有効化の順序

```bash
./ops/28-deploy.sh <환경> <버전>                 # 新しいイメージ ＋ マニフェスト（JAVA_TOOL_OPTIONS・SERVER_SSL_CLIENT_AUTH・INTERNAL_AUTH_MODE=observe）
# 注文・決済・配送のフローを一度回す（内部呼び出しが起きないと警告が出ない）
./ops/37-check-mtls.sh                            # ① observe の警告 0 件 ② 証明書なしで 200 · 内部証明書で 200
kubectl -n ecommerce patch configmap ecommerce-config --type merge -p '{"data":{"INTERNAL_AUTH_MODE":"enforce"}}'
kubectl -n ecommerce rollout restart deploy/<백엔드 서비스들>
./ops/37-check-mtls.sh                            # 証明書なしで 403 · 内部証明書で 200
```

元に戻す: `INTERNAL_AUTH_MODE` を `observe` に戻して再起動。TLS 層まで戻すなら、マニフェストの `SERVER_SSL_CLIENT_AUTH` を `none` に。

`ops/37-check-mtls.sh` — 一時的に立ち上がる curl の Pod に `tls-keystore` Secret をマウントし、order-service の内部 API を **証明書なしで／内部
証明書で** 呼び出してステータスコードだけを見る（両方が期待どおりであってはじめて判定が生きている）。observe の警告は対照呼び出しより **先に** 数える — 対照の
「証明書なし」の呼び出し自体が痕跡を残すからだ（その呼び出しには `X-Mtls-Probe` の印を付けてログだけを区別する。認可とは無関係だ）。

テスト: `InternalCallerFilterTest`（7 — JDK の keytool で内部 CA・サービス証明書・別 CA の証明書を作り: 証明書なし → 403 判定、内部
証明書 → 内部・CN を記録、別 CA → 外部、許可 CN リスト、身元ヘッダーはそのまま、off・observe は塞がない、enforce なのに CA がなければ起動失敗）。
バックエンドの 10 モジュールすべて通過。`ops/37` は偽の kubectl で 4 つのケース（enforce 正常・enforce 未適用・observe 正常・observe 警告）を確認した。

**✅ 本番適用（2026-09-26）** — observe で警告 0 件・対照 200/200 を見た後、enforce に上げた。`ops/37`: 起動モード ENFORCE、
**証明書なしで 403 · 内部証明書で 200**。上げる前に注文・決済（Stripe）・カートを空にする処理が最後まで回ることを確認した。
実際に回しながら `ops/37` が 3 回間違え、直した:

| 症状 | 原因 | 修正 |
|---|---|---|
| 証明書なし・ありの両方が `000` | HTTP コードだけを出力していて理由がわからなかった | curl の終了コード・エラー文言、Secret の欠けたキー、Service のエンドポイントが空かどうかを先に見る |
| Ready の order-service にも接続拒否（curl 7） | NetworkPolicy（k3s の kube-router）が **起動したばかりの Pod** の IP を許可リストに入れるまで数秒 — その間は拒否 | 接続拒否なら 2 秒おきに最大 8 回やり直す（ops/35 も叩く前に 5 秒） |
| enforce なのに証明書なしで `200` | 再起動の **直後** に回したため、古い observe の Pod が応答 | ロールアウトが終わるまで待ち、Pod の起動モード（ログ `서비스 간 mTLS 판정: mode=…`）を表示する |

### サービスごとの身元 —「どのサービスか」まで（2026-09-26）

以前はすべてのサービスが証明書 **一つ**（CN=localhost、`tls-keystore`）を共有していた。判定は「内部サービスか」までで、ゲートウェイも
同じ鍵をサーバー証明書として持っていたので、ゲートウェイが破られればその鍵で内部呼び出しを装えた。

**変えたもの — クライアントの身元だけ。** サービスが他のサービスを呼ぶときに提示する証明書（JVM デフォルトの keystore → Feign）をサービスごとに別に置く
（`tls-id-<서비스>`、CN=サービス名、`/app/identity` にマウント）。サーバー証明書・Redis・Kafka は共有証明書のままだ（同じ CA）。
Secret の形は cert-manager の Certificate（`keystores.pkcs12`）が作るものと同じだ（`tls.crt`・`tls.key`・`ca.crt`・`keystore.p12`）
— 発行者を変えてもマニフェストはそのままだ（§18）。

**判定 — 許可主体 ＋ パスのルール**（`InternalCallerFilter`、ConfigMap）:

| キー | 値 |
|---|---|
| `INTERNAL_AUTH_ALLOWED_SUBJECTS` | バックエンド 10 個の名前。共有証明書（localhost）は含まない — ゲートウェイの鍵は内部呼び出しではない |
| `INTERNAL_AUTH_PATH_RULES` | 下の表。最も長い前方一致が勝つ。`none` = サービスは呼べない（管理者のみ） |
| `INTERNAL_AUTH_RULES_MODE` | 段階的適用（観察 → 強制）: `observe`（ずれていれば `[mTLS rule]` として残すだけ） → `enforce`（塞ぐ） |

| 内部パス（前方部分） | 呼び出せるサービス | 呼び出し箇所 |
|---|---|---|
| `/api/order/internal/purchase-stats` · `/purchased` | user-service | 顧客ランク · レビューの購入確認 |
| `/api/order/internal/orders` | delivery-service | 決済完了リスナー · 配送のバックフィル |
| `/api/coupon/internal/quote` | order-service | 注文金額 |
| `/api/coupon/internal/payment-claim` | order-service | 決済開始時のクーポン確保(V7) |
| `/api/internal/stock/reservations` | order-service | 在庫予約・解除 |
| `/api/product/rating` | user-service | レビュー評価・日次突合 |
| `/api/auth/user/emails` · `/name` | order-service | 管理者の注文一覧 |
| `/api/auth/user/segments` | coupon-service | 特集クーポン |
| `/api/auth/user/id` | coupon・order・chat | クーポン発行（chat・order は宣言のみ） |
| `/api/auth/user/email/` · `/profile/` | none | 管理者のみ（ゲートウェイ） |
| `/api/notifications/internal/two-factor` | user-service | ログイン二段階認証の SMS 送信 · 確認(docs/24 §10 — 管理者もゲートウェイ経由では呼べない) |

ゲートウェイ・saga-projection は内部 API を呼ばない（Feign なし）。初めて見る（呼び出しサービス、パス）の組は `[mTLS caller]` として 1 回
残る — `./ops/37-check-mtls.sh --callers` が集めて表示する。

**ガード**: `InternalCallerFilterTest`（+5 — パスのルールが呼び出し元を絞る、最も長い前方一致、ルールのないパス、rules の observe は塞がない、
ルール文字列が間違っていれば起動失敗） · **B-67**（Feign が呼ぶ内部パスを、ルールがその呼び出しモジュールに許可しているか — ルールから呼び出し元を外すと
失敗することを確認）。Feign の呼び出しを追加したり移したりすると、B-67 がルールを直せと言う。

**上げる順序** — 途切れなく:

```bash
./ops/38-issue-service-identities.sh          # ① サービスごとの身元 Secret（ops/07 の CA で、値は出力しない）
./ops/38-issue-service-identities.sh check
./ops/28-deploy.sh local <버전>                # ② マニフェスト — /app/identity のマウント ＋ ルール（観察段階から）
                                               #    （ops/28 は身元 Secret がなければ適用前に止まる）
# ③ 主要なフロー（注文・決済・配送・レビュー・クーポン）を一度回した後
./ops/37-check-mtls.sh --callers               #    誰が何を呼んだか — すべて自分の名前か
./ops/37-check-mtls.sh                         #    ①-2 ルール違反が 0 件なら ↓
# k8s/10-configmap.yaml の INTERNAL_AUTH_RULES_MODE を "enforce" に（✅ 2026-10-01 リポジトリの既定値）→
./ops/28-deploy.sh local --config-only         #    kubectl patch だけで変えると次のデプロイがマニフェストの値に戻す
kubectl -n ecommerce rollout restart deploy/<백엔드 열 개>
./ops/37-check-mtls.sh                         # ④ 対照は user-service の身元で 200、証明書なしで 403
```

元に戻す: `INTERNAL_AUTH_RULES_MODE=observe` に変えて `--config-only` ＋ 再起動（判定は「内部 CA の証明書ならよい」に戻る）。B-67 はリポジトリの既定値が enforce かも見る。

### 身元ヘッダー — 誰が付けたか（2026-10-14）

**何が問題だったか。** 判定フィルターは身元ヘッダー（`User-Id`・`User-Role`・`User-Email`）が**ありさえすれば**ゲートウェイが付けたものとみなし、何も見ていなかった。
サービスは証明書のない接続も受け（`want`）、NetworkPolicy `allow-same-namespace` は同じ名前空間をすべて許可する — ecommerce の Pod を一つ乗っ取れば
`User-Role: ROLE_ADMIN` 一行で内部専用 API（配送先など）を読み、他人の `User-Id` でその利用者になれた。証明書を持つサービスもヘッダー一行で許可主体・パスルールを飛ばせた。

**やったこと。** ゲートウェイも自分の証明書（CN=gateway-server — ops/38・k8s/96 の `tls-id-gateway-server`）を**クライアント証明書として**提示する
（Spring Cloud Gateway の `httpclient.ssl.key-store` — Netty クライアントは JVM キーストアを読まない）。受け側がヘッダーを信じるかは送り手で決まる:

| 送り手（証明書） | 信じるヘッダー |
|---|---|
| ゲートウェイ（`INTERNAL_AUTH_GATEWAY_SUBJECTS`、既定 gateway-server） | 三つとも — ゲートウェイはクライアントが送った同じヘッダーを消してトークンから付け直す |
| 内部サービス（許可主体） | `User-Id` だけ — 利用者の代理呼び出し（order・coupon → cart `getCart`）。ロール・メールは捨てる — サービスが管理者を名乗れない |
| 内部証明書なし・別の CA | なし — 三つとも捨てて `ROLE_ANONYMOUS` |

ヘッダーなしでゲートウェイ証明書で来たリクエスト（公開ルート）は**内部呼び出しではない** — 許可主体リストに関係なく外部とみなす。

**有効化の順序**（`INTERNAL_AUTH_IDENTITY_MODE`、既定 observe）:

1. ゲートウェイ証明書 — `./ops/38-issue-service-identities.sh --only gateway-server`（cert-manager なら `./ops/39-cert-manager.sh` が k8s/96 の
   `id-gateway-server` も発行）。`ops/28` はマニフェストがマウントする `tls-id-*` がなければデプロイ前に止まる。
2. デプロイ — ゲートウェイが証明書を提示し始め、バックエンドは observe で外れたものを `[mTLS identity]` として記録する（値は残さない）。
3. `./ops/37-check-mtls.sh`（①-3 が 0）→ ログイン・注文・管理画面が一通り動いた後に `SINCE=24h` でもう一度。
4. `k8s/10-configmap.yaml` の `INTERNAL_AUTH_IDENTITY_MODE: "enforce"` → `./ops/28-deploy.sh local --config-only` → バックエンド再起動 →
   `./ops/37` の ②-2（証明書なしで管理者ヘッダー → 403）。

戻し方: observe で同じ手順。`InternalCallerFilterTest`（身元ヘッダー 5 件）・`CheckMtlsScriptTest` が守る。

> 🕰️ 2026-11-14: 4 段階目まで終わった — 本番 2026-10-26（`d68f0307e979`、`ops/37` ①-3 0 · ②-2 403）、リポジトリの既定値も `enforce`。パス規則にはその後 gRPC の在庫予約（`/ecommerce.stock.v1.StockReservation/=order-service`）が加わった。

**enforce の前提と最後の防衛線 — ガード二つ（`InternalEndpointGuardTest`）。**

- **B-81 — Feign は `User-Role` ・ `User-Email` を載せない。** enforce は内部サービスが送ったその二つを捨てる。今は載せる Feign が
  なく（代理の呼び出しは `User-Id` だけ）、手順 4 で動作は変わらない — その前提をコードで固定した。ロールが必要な内部呼び出しが
  できたら、載せずに受け側が `User-Id` で照会する。
- **B-80 — 内部専用エンドポイントはすべてコントローラーで `InternalCallGuard.allowed` により判定する。** パス規則（`INTERNAL_AUTH_PATH_RULES`）は
  身元ヘッダーのあるリクエスト（ゲートウェイ経由）を飛ばす — そのとき実際に止めるのはその一行（catalog は `AdminWriteInterceptor`）だ。ガードは
  規則の全パスにその判定があるか、`/internal/` を持つエンドポイントが規則に漏れなくあるか、規則が指すエンドポイントが実在するか
  （パスを変えたら規則も）を見る。

### ストアのパスワードは JVM オプションの外へ（2026-10-14、B-83）

**何が問題だったか。** 13 のマニフェストが `JAVA_TOOL_OPTIONS` に `-Djavax.net.ssl.trustStorePassword=$(LOCAL_KEY_STORE_PASSWORD)`（サービスは
`keyStorePassword` も）を入れていた。JVM は起動のたびにその変数を**まるごと** `Picked up JAVA_TOOL_OPTIONS: …` として stderr に出す — logback を
通らないのでマスキングされず、Pod ログと Loki（alloy が `/var/log/pods/ecommerce_*` を送る）に平文で残った。同じ値がワイルドカード秘密鍵 ·
サービス身元キーストア · ゲートウェイ身元のパスワードでもある。

**やったこと。** `JAVA_TOOL_OPTIONS` にはパスと形式だけを置く。パスワードは環境変数 `JAVAX_NET_SSL_TRUST_STORE_PASSWORD` · `JAVAX_NET_SSL_KEY_STORE_PASSWORD`
で受け、アプリが `SpringApplication.run` の最初（設定サーバー · Eureka の最初の TLS より前）に `javax.net.ssl.*Password` へ移す —
common の `JvmSslPasswords`（実行リスナー）、common を使わないゲートウェイ · config-server は `main`。JDK は既定の SSLContext を初めて使うときに
このプロパティを読む（実測: 変数で渡したパスワードでトラストストアが開き、なければ証明書 0 件、`Picked up` 行にパスワードはない）。
ops/12 · ops/13 も同じ方式。B-83（`JvmSslPasswordsTest`）がマニフェスト · オーバーレイ · ops の JVM オプションに `Password=` がないこと、ストアごとに
パスワード変数があることを見る。

**運用ですること。** デプロイだけで新しいログにはもう残らない。すでに残ったものは消えない — Loki の保持期間中は照会できる。パスワードが漏れたと
みなして `LOCAL_KEY_STORE_PASSWORD` を変えるのが正しい。それまでは Grafana · Loki へのアクセスを運用者だけにする（観測ポートはもともと公開しない）。

> 🕰️ 2026-11-14: どちらも終わった — 新しいマニフェストは一括リリース（2026-10-24 `4415e498565c`）で、パスワード交換は下の ops/59 で 2026-10-25 に本番適用（ストア 27 · 再起動 0）、1 日後に cleanup。

**交換 — `ops/59-rotate-store-password.sh`(2026-10-25)。** ops/07 で発行し直さない — CA が変わるとサービス身元(ops/38)· PostgreSQL の証明書 ·
ブラウザの信頼まですべてやり直しになる。鍵と証明書はそのまま、ストアのパスワードだけ変える(`keytool -storepasswd` — PKCS12 は鍵のパスワードも
一緒に変わり、トラストストアの信頼項目はそのまま。実測: 新しいパスワードで秘密鍵が取り出せ、古いパスワードでは開かない)。

| 対象 | 何を |
|---|---|
| クラスタの Secret | 名前空間の `.p12` · `.jks` すべて(`tls-keystore` · `tls-id-<サービス>` …)— 一つでも今のパスワードで開かなければ何も変えない |
| `ecommerce-secrets` | `LOCAL_KEY_STORE_PASSWORD` · `LOCAL_KEY_PASSWORD` · `KAFKA_SSL_*_PASSWORD` のうち**古い値と同じものだけ**(違う値はそのまま) |
| この機械 | `certs/` のストア · `certs/certs.env` · `k8s/20-secret.yaml` — 後で適用し直しても古い値に戻らないように |
| 再起動 | そのパスワードを読む Deployment だけ、**一つずつ**(ノード一つ — eureka · config を先に、gateway は最後) |
| 戻し | 変える前の Secret の写し `<名前>-prerotate`(同じ名前空間、ラベル `ecommerce.io/store-password-rotation`)· ファイルの `-prerotate` |

```bash
./ops/59-rotate-store-password.sh            # check — ストアごとに開くか · 変えるキー · 再起動対象(値は出力しない)
./ops/59-rotate-store-password.sh apply      # 交換 + 再起動('yes' を聞く)
./ops/59-rotate-store-password.sh rollback   # 問題があれば — 写しで戻して再起動
./ops/59-rotate-store-password.sh cleanup    # 一日ほど問題がなければ — 写しに古いパスワードがある
```

🔒 パスワードは引数 · ログ · 画面に出ない(keytool には 0600 のファイルで `-storepass:file`、出力は長さだけ)。ホストに keytool がなければ
サービスと同じ JRE イメージ(B-98)を `docker run --network none` で使う。cert-manager(ops/39)が受け持つ身元(本番は `tls-id-*` 10 個 — 2026-10-25 の check)は次の更新から新しいパスワードで作る — だから `ecommerce-secrets` を**先に**変え(その間に cert-manager が作り直しても新しい値)、Secret のラベル · 注釈 · 所有者はそのまま残す(抜けると cert-manager が証明書を発行し直しうる — 写しには畳んで置き、戻すときに広げる)。
偽の kubectl の上で本物の PKCS12 · JKS で試す(`StorePasswordRotationScriptTest` — 元に戻すと失敗するもの六つを確認)。

### Eureka 書き込み — 登録はそのサービス自身の証明書でのみ（2026-10-14、B-86）

**何が問題だったか。** Eureka はクライアント証明書を見ていなかった（サーバーの `client-auth` なし）。ecommerce 名前空間のポッドが一つ破られるだけで
`PUT /eureka/apps/ORDER-SERVICE/<id>/status?value=OUT_OF_SERVICE` の一行で order-service のインスタンスが一覧から外れ（ゲートウェイ · Feign の
`lb://` が宛先を失って 503）、`POST /eureka/apps/ORDER-SERVICE` で偽のインスタンスを差し込めばサービス間呼び出しがそちらへ流れた。上の mTLS 判定は
バックエンド同士の呼び出しだけを守っていた。

しかも **クライアント側の証明書がなかった。** バックエンドは JVM キーストア（`-Djavax.net.ssl.keyStore`）を持つが、Eureka クライアントの転送（RestTemplate ·
HttpClient 5）は自分の SSLContext を **鍵なしで** 作る（信頼だけ JVM 既定）— 実際の TLS で確かめた: 同じ JVM キーストアで JDK 既定の接続（Feign の道）は
証明書を出し、Eureka クライアントは出さなかった。ゲートウェイの `tls:` ブロックは `eureka.instance` の下にあって誰も読んでいなかった。

**やったこと。**

| 場所 | やったこと |
|---|---|
| クライアント — 共通設定 | `application-common.yml` の `eureka.client.tls` — 身元キーストア（`/app/identity/keystore.p12`、CN=サービス名）· 共有トラストストア。`EUREKA_CLIENT_TLS_ENABLED` で有効化（既定は無効 — ローカル開発 · ops/13）。import されるファイルなのでサービス自身の yml より優先する |
| クライアント — マニフェスト | 身元をマウントした Deployment（バックエンド 10 · ゲートウェイ · config-server）が `EUREKA_CLIENT_TLS_ENABLED=true`。config-server にも身元を与えた（k8s/96 · ops/38 · ops/39）— 内部 API は呼ばないが Eureka に自分を登録する。⚠️ config-server は common を使わないので共通設定の `eureka.client.tls` を受け取れない — 同じブロックを自分の `application-local.yml` に置く（2026-10-15 以前はなく、鍵なしの SSL で登録していた — observe で 30 秒ごとに `[eureka write]`、ガード B-86 が今は照合する） |
| 受け側 — eureka-server | `SERVER_SSL_CLIENT_AUTH=want`（プローブ · ダッシュボード · 登録の読み取りは証明書なしのまま）+ `RegistryWriteGuard`: 書き込み（GET · HEAD · OPTIONS 以外）は内部 CA が署名した証明書の CN がパスのアプリ名と一致すること（大小無視、パスはデコード · `;…` 除去後 — Jersey が見る名前）。`/eureka/apps/` 以外の書き込み（ピア複製 · ASG）はこの単独 Eureka に来ないので拒否。証明書判定は `InternalCallerFilter.verifiedCommonName` 一つを使う。common の `InternalCallerFilter`（内部 API 判定）は eureka-server では無効にする — 有効のままだと許可主体外（config-server）のハートビートごとに `[mTLS rule]` 警告が残る |
| モード | ConfigMap `EUREKA_WRITE_AUTH_MODE`: off · observe（`[eureka write]` として残して通す）· enforce（403）。enforce なのに CA を読めなければ起動を止める — そのまま起動するとすべてのハートビートが 403 になり 90 秒後に登録が空になる |

**有効化の順序。**

```bash
./ops/38-issue-service-identities.sh --only config-server     # cert-manager なら ops/39（k8s/96 に id-config-server）
./ops/28-deploy.sh <環境> <バージョン>                        # クライアント TLS · want · EUREKA_WRITE_AUTH_MODE=observe
# 1 日後 — 外れた書き込みがないこと（初めて見る (呼び出し元, メソッド, アプリ) ごとに一行）
kubectl -n ecommerce logs deploy/eureka-server --since=24h | grep -c '\[eureka write\]'      # 0
# k8s/10 の EUREKA_WRITE_AUTH_MODE を enforce にして
./ops/28-deploy.sh <環境> --config-only --restart eureka-server
```

**本番の最初の判定(2026-10-26) — サーバーが自分自身に複製していた。** 一日一行: `caller=(인증서 없음) POST app=/eureka/peerreplication/batch/`。
peer 複製は Eureka サーバーだけが送る — 単独サーバーなのに `eureka.server.my-url` がなく、`PeerEurekaNodes` が defaultZone の `https://localhost:8761/eureka/`
を Pod のホスト名と比べ(違う)、自分を peer とみなして登録 · ハートビートを自分に複製していた(同じ行は Pod ごとに一度だけ残すので一日一行)。enforce
ならその複製はすべて 403。修正: `my-url` を defaultZone と同じに(`EurekaServerPropertiesTest` — 違えば失敗)。このイメージが起動した後、改めて一日 0 を見て enforce。

戻し: `EUREKA_WRITE_AUTH_MODE` を observe にして同じ手順。サーバーが再起動してもクライアントは 30 秒のハートビートで再登録する。

> 🕰️ 2026-11-14: `my-url` の修正は 2026-10-26 に本番へ入り、2026-10-30 に再び 1 日 0 件を確認して enforce に上げた（ログ `mode=ENFORCE (CA 읽음)` · 10 分間 `[eureka write]` 0 · 再起動 0）。

テスト: `RegistryWriteGuardTlsTest`（実際の TLS で起動した Eureka に **実際の Eureka クライアント** が共通設定のまま登録 — 身元は通過 · TLS を切った
クライアントは JVM キーストアがあっても証明書を出さず拒否 · 他サービスの証明書で他のアプリの登録を拒否 · 証明書なしの OUT_OF_SERVICE は 403 ·
読み取りは 200 · エンコードしたアプリ名も同じ判定）、`RegistryWriteGuardTest`（モード · メソッド · パス）、B-86 `EurekaClientIdentityGuardTest`（身元マウント ↔
`EUREKA_CLIENT_TLS_ENABLED` · 発行リスト 3 つ · Reloader · want · モード）。

### 残っているもの — 正直に

- サーバー証明書は依然として共有だ — ゲートウェイもその鍵を持っている。ただしその鍵（CN=localhost）は今は **許可主体ではない** ので、内部呼び出しには
  使えない。サーバー証明書までサービスごとに分けるのは利点が小さい（サーバー認証は「同じ CA か」だけを見る）。
- サーバー側は `need`（証明書がなければ接続拒否）ではなく `want` だ — kubelet の HTTPS プローブが証明書を提示できない。判定はアプリが行う（Eureka も同じ — 書き込みだけを判定する）。
- サービスごとの身元（クライアント証明書）は cert-manager が更新する（§18）。**共有サーバー証明書**（`tls-keystore`、825 日）は依然として `ops/07`
  ＋ 再起動だ — `MODE=verify ./ops/07-issue-certificates.sh` が残り日数を知らせる。

## 17. 🔴 2026-09-26 のデプロイ失敗 — 85 コミットを一度に、そして自動復帰が障害を拡大させた件

`0afb735 → 675ca96`（85 コミット）を `ops/28` で一度に上げた。

| サービス | 前進するとき | 原因 |
|---|---|---|
| user-service | 🔴 起動失敗 `UserLifecycleProducer: No default constructor found` | コンストラクタが 2 つ（本番用・テスト用）あるのに、どちらにも `@Autowired` がなかった。単体テストはコンストラクタを直接呼ぶのでグリーン、コンテキスト起動テストは Docker がないと動かないので CI でスキップされていた |
| chat-service | 🔴 V6 が自ら拒否「legacy_sender_email 에 행이 남아 있다」 | **2 リリースに分けて上げるように作った移行**（docs/48 §6-⑥）を飛ばした — 第 1 段階（V5 ＋ 起動時バックフィル、コミット `1227834a0a5a`）が一度も回らず、マッピング表が空にならなかった。V6 の安全装置が自分の仕事をした |
| order・pay ほか | ✅ 新しいバージョンで起動した | order V16・V18、pay V6 など **元に戻せないマイグレーションが成功** した |

そして `ops/28` が **自動復帰** した。イメージは戻したがスキーマは戻さないので、古い order・pay・chat のイメージは「missing table
orders.customer」「missing column paypal_order_id」「missing column email」で起動できなかった — 復帰すら失敗した。ローリングが止まり、
order・pay は **新しいバージョンの Pod が**、chat は **デプロイ前の古い Pod が**（V5 で email カラムがなくなったスキーマの上で）動き続けている。

### 直したもの

- `UserLifecycleProducer` — 本番用コンストラクタに `@Autowired`。ガード **B-65**: コンストラクタが 2 つ以上ある Bean は、1 つに `@Autowired`（または引数
  なしのコンストラクタ）。修正前のコードに戻して失敗することを確認した。
- `ops/28` — デプロイ範囲（`현재 → 새 버전`）に `ROLLBACK-UNSAFE` のマイグレーションがあれば **事前に知らせ、失敗しても自動復帰しない**
  （前進して直す）。手動の `--rollback` もその範囲を越えるなら拒否する（`FORCE_UNSAFE_ROLLBACK=1` はスキーマを先に戻した後でのみ）。

### 復旧 — 前進する

```bash
git pull                                   # @Autowired・ops/28 のガード
# ① chat の移行リリース（1227834a0a5a）を一度 — 起動時バックフィルが Mongo の古い送信者を id に置き換え、マッピング表を空にする
git worktree add ../ecommerce-msa-transition 1227834a0a5a
cp common/src/main/resources/kafka-secrets.properties ../ecommerce-msa-transition/common/src/main/resources/ 2>/dev/null
( cd ../ecommerce-msa-transition && ./ops/19-build-images.sh --registry ghcr.io/ruke79 --push --only chat-service )
kubectl -n ecommerce set image deploy/chat-service chat-service=ghcr.io/ruke79/ecommerce-msa/chat-service:1227834a0a5a
kubectl -n ecommerce rollout status deploy/chat-service --timeout=900s
kubectl -n ecommerce logs deploy/chat-service | grep "옛 발신자 백필"     # 「… 지운 매핑 N건」が出れば完了
# ② すべてを新しいバージョンへ — 今回は失敗しても自動復帰しない
VER=$(git rev-parse --short=12 HEAD)
REACT_APP_...=... ./ops/19-build-images.sh --registry ghcr.io/ruke79 --push
./ops/28-deploy.sh local $VER
git worktree remove ../ecommerce-msa-transition
```

① のログが「백필 실패」なら、Mongo（Atlas）に届かなかったということだ。古い会話の送信者を諦めてよいなら（デモデータ）、表を空にして ② へ進む —
それらのドキュメントは「送信者不明」として表示され、メールアドレスは Mongo に残る:
`DELETE FROM chat.legacy_sender_email;`

### 2 回目の試行 — chat の Bean の循環参照

移行リリースでバックフィルを終えた後（`id 로 바꾼 문서 4건 … 지운 매핑 1건`）再びデプロイすると、chat が「APPLICATION FAILED TO START」で
止まった。サポートチャット（docs/58）が入って `StompHandler` が `SupportService` を受け取るようになったのだが:

```
brokerMessagingTemplate → clientInboundChannel → StompHandler(インターセプター) → SupportService → SupportDeskNotifier → brokerMessagingTemplate
```

Spring Boot は循環を拒否する。サポート機能が今回初めて本番に上がったので、初めて表に出た。`StompHandler.supportService` に `@Lazy`
（最初に使うときに受け取る — STOMP フレームを処理するときにはコンテキストはすべて起動済みだ）。`WebSocketWiringTest` が実際の WebSocket 設定・サポートサービス・
通知器を立ち上げて組み立てを確認する（修正前にまさにこの循環で失敗することを確認した）。

### 教訓

- **コンテキストの組み立ては単体テストでは見えない。** 今回の 2 つの起動失敗（コンストラクタ 2 つ・循環参照）はどちらも「Spring が Bean を組み上げる瞬間」の
  問題で、それを見る起動テストは Docker がないと動かないので CI でスキップされていた。組み立てが厄介な箇所（WebSocket ブローカー）はモックを使った
  軽量なコンテキストテストで、よくあるミス（複数のコンストラクタ）はガードで防ぐ。
- **複数リリースに分けた移行は飛ばせない。** 長く溜めてから一度に上げると、中間リリースがやってくれていた仕事（バックフィル）が抜ける。
  今回は V6 の安全装置が止めたが、そういう装置のない移行だったらデータを失っていた。
- **元に戻せないマイグレーションが通った後の自動復帰は、復旧ではない。** スキーマは前にしか進まない。
- 単一ノードなので、ロールアウトのたびに新しい Pod が Pending で待たされた（サービスあたり約 600 秒）。`maxSurge: 0 / maxUnavailable: 1` を使えば速くなるが、
  サービスごとに再起動の間は切断される — トレードオフなのでそのままにした。

## 18. サービスごとの身元証明書 — 発行・更新を cert-manager で

**問題**: ops/38 で作った身元は 1 年有効で、更新は人が再実行して再起動する必要があった。忘れると、期限切れの日にサービス間の呼び出しがすべて
切れる — mTLS が enforce なので、静かにやり過ごされることはない。

**構成**

| 何 | どこ | すること |
|---|---|---|
| cert-manager（v1.16.2、固定） | `cert-manager` ネームスペース | Certificate を見て Secret を発行・更新 |
| ClusterIssuer `internal-ca` | CA の鍵は `cert-manager/internal-ca` にのみ | ops/07 の CA で署名 — アプリのネームスペースには CA の鍵がない |
| Certificate `id-<서비스>` ×10 | `k8s/96-service-identities.yaml`（kustomization の外 — CRD が先） | 90 日有効のものを期限の 30 日前に新しく（鍵も新しく）。Secret は ops/38 と同じ名前・キー（`tls-id-<서비스>` — `tls.crt`・`tls.key`・`ca.crt`・`keystore.p12`） |
| Reloader（v1.1.0、固定） | `default` | Secret が変われば、その Deployment だけをローリング再起動 — Java は keystore を起動時にしか読まない（`secret.reloader.stakater.com/reload` アノテーション、k8s/40〜49） |

`keystore.p12` は `Modern2023`（AES-256/PBKDF2） — Java 17 が読める。パスワードはサービスがすでに使っている `LOCAL_KEY_STORE_PASSWORD`。

**上げる順序**（§16 のサービスごとの身元が先に上がっている必要がある）:

```bash
./ops/39-cert-manager.sh          # install → ca（フィンガープリントで同じ CA か確認） → apply（Certificate 10 個が Ready） → check
# cert-manager が ops/38 の Secret を新しく発行して上書きする → Reloader がサービスを 1 回ずつローリング再起動する
./ops/37-check-mtls.sh            # すべて起動した後 — 対照 200・403、ルール違反 0
./ops/39-cert-manager.sh check    # 有効期限・次回更新の時刻、Reloader が起動しているか
```

更新を練習するなら `./ops/39-cert-manager.sh renew order-service` — そのサービスだけが新しく発行され、再起動される。cert-manager が担当した
後は、ops/38 はそのサービスに触れない（2 つが交互に上書きすると再起動が続く）。

> 🕰️ 2026-11-14: k8s/96 の Certificate は今は **12 個** — §16 のゲートウェイ（`id-gateway-server`、2026-10-14）と config-server（`id-config-server`、Eureka 書き込み）が加わった。

**ガード**: B-68 — バックエンドがマウントする身元 Secret ごとに、k8s/96 に同じ名前を発行する Certificate があり、その Deployment に Reloader の
アノテーションがある（アノテーションを消すと失敗することを確認）。

**残っているもの**: 共有サーバー証明書（`tls-keystore`）は依然として手作業だ — 825 日なので急ぎではないが、同じ方式（Certificate ＋ Reloader）に
移せる。そのときは Kafka・Redis・Postgres のクライアントが同じファイルを使うので、インフラ側の再起動まで一緒に計画する必要がある。

> 🕰️ 2026-11-20: サーバー証明書は §27 に移した — 新しい Certificate は作らず、この身元をサーバー証明書にも使う（eureka-server の身元を追加）。共有ファイルの Kafka クライアント鍵 · CA はそのまま。

## 19. ブラウザのセキュリティヘッダー・外部 CSS — フロントエンド点検(2026-10-22)

`manuscripts/보안-면접`(problem-solving リポジトリ)のブラウザ側の設問(Q5・Q9・Q10・Q25〜Q28・Q33・Q36)を点検表にして frontend を見た。トークン(メモリ + `HttpOnly・Secure・SameSite=Lax` のリフレッシュ Cookie)・XSS のシンク(0 件)・オープンリダイレクト(バックスラッシュまで除外)・バンドル内の秘密(公開 id のみ)・ソースマップ(404)はすでに基準を満たしていた。空いていた二つを埋めた。

**セキュリティヘッダーが一つもなかった** — 決済・アカウント・管理画面を他サイトが透明な iframe に入れるクリックジャッキング(Q33)が可能だった。今は frontend nginx のすべての応答に:

| ヘッダー | 値 | 強制? |
|---|---|---|
| `X-Frame-Options` | `DENY` | 強制 |
| `Content-Security-Policy` | `default-src 'self'`・スクリプトは自オリジン + PortOne v1・v2・PayPal(`'unsafe-inline'` なし)・`object-src 'none'`・`base-uri 'self'`・`frame-ancestors 'none'` | 強制(2026-10-25 — それまでの 3 日は `frame-ancestors` だけ強制、残りは `-Report-Only`) |
| `X-Content-Type-Options`・`Referrer-Policy`・`Permissions-Policy`・`Strict-Transport-Security` | `nosniff`・`strict-origin-when-cross-origin`・カメラ・マイク・位置・USB を無効・1 年(サブドメインは含めない) | 強制 |

- CSP を報告のみにする理由: 決済 SDK と MUI・emotion のインラインスタイルがあり、最初から強制すると決済画面が壊れうる(Q26 — 後から入れる CSP は report-only で違反から集める)。**デプロイ後にカード・PayPal・銀行振込まで一通り回してブラウザのコンソールに CSP 違反がなければ** `Content-Security-Policy` に切り替える。**2026-10-25 に切り替えた** — 本番でカード・PayPal・銀行振込を回してコンソールの違反 0。PortOne は隠したまま(`HIDDEN_METHODS`)なので、再び開くときに決済画面まで一度回してコンソールを見る。新しい外部スクリプト・決済画面・API のオリジンは先にポリシーへ足す(抜けるとその機能が黙って止まり、コンソールにだけ出る)。
- ⚠️ nginx は location に `add_header` が一つでもあると server の分を継承しない — `/index.html`・`/static/`・`/healthz` にも同じ行を置く(`nginxHeaders.test.js`)。実際の nginx 1.27 に dist を載せ、`/`・SPA の経路・`/static/*.js`・404・`.map` のすべてで 7 つを確認した(強制への切り替えで 6 つ — CSP が一本になった)。

**Font Awesome の CSS を cdnjs から SRI なしで読んでいた** — アイコンはチャットの 3 つだけだった。同じ Font Awesome 5 のアイコンを `react-icons/fa`(SVG)に替え、外部オリジンをなくした(`indexHtml.test.js` — 外部の `link`・`script` 0、`fas fa-` 0)。

残り: ~~再設定リンクの `?token=` を読んだ後にアドレスバーから消す、配送照会 URL の送り状番号のエンコード~~(2026-11-19 完了 — §22)、react-router v7・jest 30 のメジャー更新(残る npm 勧告 7 件 — 開発ツール・すでに防いだもの)。

## 20. JRE のセキュリティ修正 — CVE-2026-114938（「Java4Shell」）· 10 月 CPU（2026-11-18）

**何か**: 2026-11-17 に公開された java.util.Base64 デコーダーのメモリ破損（報道ベースで CVSS 8.1 — 細工した Base64 入力で JVM が落ち、リモートコード実行の可能性が取り沙汰されている。2026-11-17 時点で実際の悪用は確認されていないとの報道）。影響は JDK 17 · 21 · 25 · 27、修正版は 17.0.21.1 · 21.0.13.1 · 25.0.5.1 · 27.0.1.1。同じ日に Oracle の JSSE のアラート CVE-2026-91403（報道ベースで 9.1）も 17.0.21 を影響版に挙げている。
⚠️ 根拠は報道 · 検索の要約だ — Oracle · CISA の原文はこの環境では開けなかった（403 · DNS）。デプロイ前に原文で版と回避策をもう一度確かめる。

**こちら側**: ランタイムイメージは `eclipse-temurin:17.0.20.1`（B-98 で固定）だった — 10 月 CPU（17.0.21、セキュリティ修正 55）よりも前だ。外部入力を java.util.Base64 でデコードする場所がある:

| 経路 | 入力 | 認証 |
|---|---|---|
| `PortOneV2WebhookVerifier` | Webhook の署名ヘッダー | 前 |
| `PasskeyService`（`Base64.getUrlDecoder`） | パスキーの登録 · ログインの応答 | 前（ログイン） |
| `GraceTokenCipher` | 2FA 待ちトークン | 前（2 段階目） |
| Spring Security の基本認証（config-server） | `Authorization` ヘッダー | クラスター内 |

ゲートウェイの JWT（jjwt 0.12）と Jackson の byte[] は自前の Base64 実装を使うと理解している — 確認はしていない。

**やったこと**（イメージを作り直してデプロイして初めて入る）:
1. JRE → `eclipse-temurin:17.0.21_9-jre-jammy@sha256:aa638138…`（10 月 CPU、amd64 · arm64 のリスト — Docker Hub API 基準）。11 月の修正版（17.0.21.1）の Temurin イメージは 2026-11-18 時点でまだない。
2. エントリーポイントに `-XX:-UseBASE64Intrinsics` — 報道された回避策（内蔵関数を切ると Base64 が純粋な Java で動く — リクエストごとに数百バイトなので差はないと見る、測定はしていない）。JDK 17.0.20.1 でこのオプションが受け付けられ Base64 が正常なことだけ確かめた。
3. ガード **B-113**: JRE が修正版より前ならエントリーポイントにそのオプション（JAVA_OPTS の前）、マニフェスト · オーバーレイ · スクリプトのどこにも `+UseBASE64Intrinsics` がない。修正版に上げればオプションを求めない。

**残り**: ① デプロイ — イメージは CI が作る → リリース候補が緑 → Actions → deploy（docs/85）。デプロイ後 `kubectl -n ecommerce exec deploy/catalog-service -- sh -c 'java -version 2>&1 | grep -v JAVA_TOOL_OPTIONS'` が 17.0.21 · `ps` のコマンドラインに `-XX:-UseBASE64Intrinsics`。② Temurin 17.0.21.1 が出たら JRE の行をその版に · オプション削除（B-113 が許す）· 再デプロイ — CVE-2026-91403（JSSE）はそれでしか閉じない。③ jdk21-build ブランチは 21 の版（修正版 21.0.13.1）で同じこと。④ CI ランナー · 開発 PC の JDK は外部入力を受けないので急がない。

---

## 21. フロントの nginx のセキュリティ修正 — 「NGINX2Root」（CVE-2026-123007 ほか四つ、2026-11-19）

**何か**: 2026-11-18 に公開された nginx の脆弱性群。中心は CVE-2026-123007 — HTTP/2 処理のメモリー破損による認証なしのリモートコード実行（F5 基準 CVSS 9.2、公開 PoC あり; 1.9.5 からあったコード）。同じ群に HTTP/2 の use-after-free（CVE-2026-123633）· HTTP/3 · resolver · mail モジュールの脆弱性。影響 1.9.5 〜 1.30.6 · 1.31.0 〜 1.31.8、修正版 **1.30.7（stable）· 1.31.9（mainline）**。HTTP/2 を切ると HTTP/2 の二件だけが塞がる。
⚠️ 根拠は報道 · セキュリティ企業の記事（Qualys · Upwind · The Hacker News など — 版の範囲が記事ごとに少し違う）。F5 の告知の原文はこの環境では確かめていない。

**こちら側**: フロントのイメージは `nginx:1.27-alpine` だった — 影響範囲内で、1.27 の系列はサポートが終わり修正版が出ない。ただし `frontend/nginx.conf` は `listen 8080`（HTTP/1.1）だけで `http2` · `quic` · `resolver` · `mail` がない。客の HTTP/2 は Cloudflare が受け、内側は cloudflared → traefik → frontend が HTTP/1.1 なので、中心の HTTP/2 の経路は外から届かなかったと見る。本番ノードに他の nginx コンテナーはない（2026-11-19 `docker ps` 0 件）。リポジトリで nginx を使うのは二つの Dockerfile と `ops/18`（SSE テスト）だけ。

**したこと**（frontend イメージを作り直してデプロイして初めて入る）:
1. `nginx:1.30.7-alpine@sha256:bef15473…`（マルチアーキテクチャーの一覧 — Docker Hub API 基準）— `frontend/Dockerfile` · `ops/Dockerfile.frontend-runtime` · `ops/18-test-nginx-sse.sh`。
2. 確認: そのイメージで実際の `nginx.conf`（テンプレート置換込み）の `nginx -t` が通る · セキュリティヘッダー七行がそのまま · SPA の経路が 200、`ops/18`（SSE のバッファリング · envsubst フィルター · gzip の対照群）8/8 通過。
3. ガード **B-114**: 三か所が同じ `nginx:<版>-alpine@sha256:…`（タグだけは禁止）、版は 1.30.7 · 1.31.9 以上（1.30 より前の系列は拒否）、`nginx.conf` は HTTP/1.1 だけ（`http2` · `quic` · `http3` · `resolver` · `mail` なし — 理由なくそのコードの経路を開かない）。変異の確認: 1.27 タグ · 三か所 1.30.6 · 三か所 1.31.8 · 一か所だけ違う版 · `http2 on` → 失敗。

**残り**: デプロイ — 次のデプロイ（docs/85 §9）と一緒に、急ぐなら frontend だけ（Actions → deploy）。デプロイ後、`curl -sI https://www.ysyproject.com/` は Cloudflare が `Server` を書き換えるので版は Pod で見る: `kubectl -n ecommerce exec deploy/frontend -- nginx -v` が 1.30.7。任意: `server_tokens off`（内側の応答の `Server: nginx/1.30.7` の版表示 — 外は Cloudflare が隠す）。

## 22. セキュリティの残りの整理 — トークンの即時失効 · リカバリーコード · Kafka 信頼パッケージほか（2026-11-19）

「すべての文書を通した残りの作業」のセキュリティ欄のうち、コードで終えられる七つを行った。しなかったものとその理由は下の **残り**。

| # | 何が問題だったか | したこと | テスト（変異確認） |
|---|---|---|---|
| 1 | **盗まれたアクセストークンがログイン解除後も最大 15 分** — ゲートウェイは署名 · 期限だけを見ていた（「すべての端末からログアウト」· パスワード変更 · 再設定 · 管理者ロック · 退会 · リフレッシュ再利用検知の後も。docs/68 §7 は「十分」としていた） | ゲートウェイが user-service の書いていた `auth:cutoff:<uid>`（長い接続用、docs/68 §9）を読み、それより早い `iat` は 401（`JwtAuthorizationGatewayFilterFactory#sessionStillValid`）。認証リクエストごとに Redis GET 一つ · 上限 300ms（`GATEWAY_SESSION_CUTOFF_TIMEOUT`）、遅い · 失敗なら通す（期限が 15 分の上限）、無効化 `GATEWAY_SESSION_CUTOFF_ENABLED=false`。画面の文言「最大 15 分以内」→「すぐに」 | gateway `JwtSessionCutoffTest` 4（早いトークン 401 · 後のトークンは通過 · 記録なし · Redis 失敗/無応答は通過 — 確認を外すと失敗） |
| 2 | **二段階認証の復旧手段がない** — 認証アプリを消すか番号を変えると（SMS は有効化時の番号に結束）、パスワードを知っていても入れない。パスワード再設定は二段階認証を解かない | リカバリーコード 10 個（`RecoveryCodes`、user V21 `two_factor_recovery_code`）: 31 文字 × 10 桁 · 原文は作成時の応答だけ（`no-store`）、DB には HMAC（派生鍵 — `auth.recovery-code.pepper`、なければ JWT 秘密。変えると全コード無効）· 一度だけ（未使用の行だけ UPDATE）· 試行は TOTP · SMS と同じ枠 · 作成 · 使用は持ち主に通知（`recovery-codes-created` · `recovery-code-used`）· 最後の手段を無効にすると削除 · 退会時に削除。作成には直近のログイン（10 分）が必要。ログイン二段階目に「リカバリーコードを使う」、セキュリティ画面に作成 · 残り数 | user `RecoveryCodesTest` 5 · `RecoveryCodeLoginTest` 5 · `RecoveryCodeMigrationTest`（コンテナ）· notification `DeliveryStatusNotificationTest` +1 · フロント `RecoveryCodes.test.jsx` 3 · `SignInPage.test.jsx` +2 |
| 3 | **Kafka JSON デシリアライズが `addTrustedPackages("*")`**（10 か所）— コンシューマーがヘッダー（`__TypeId__`）に従うので、トピックに書ける誰でもヘッダー一つでクラスパス上の任意のクラスを作らせられた（Jackson ガジェットの入口） | メッセージ DTO パッケージ（`com.project.common.message.dto.*`）だけ — `KafkaTrustedPackages.trustMessageDtos`。ガード **B-115**（`"*"` 禁止 · ヘルパー 10 か所以上） | common `KafkaTrustedPackagesTest` 2 · ガード 1 |
| 4 | **ソーシャルログインは新しい端末を見ていなかった** — ソーシャルアカウントを奪った人は「新しい端末からのログイン」通知なしに入れた（docs/44 §4） | `OAuth2SuccessHandler` がパスワードログインと同じ位置（本人確認の後 · 2FA の前）で端末を記録し、初めての端末なら知らせる。失敗してもログインは続く | user `OAuth2SuccessHandlerTest` +2 |
| 5 | **ログイン時には漏えいパスワードを見ていなかった** — 登録 · 変更 · 再設定だけ（docs/68 §18）。その後に漏れたパスワードを使う人は知る術がなかった | `LoginPasswordAdvisor`: パスワードが**合った後だけ** HIBP（k-匿名性）· ありふれた一覧を見て、該当すれば応答 `passwordAdvice: "breached"` → 画面が変更を勧める（**止めない**）。「問題なし」は 7 日記憶（`auth:pw-ok:<uid>`、ハッシュも置かない — 変えたパスワードは変更経路で通過済み）、確認できないときは 1 時間。無効化 `auth.breached-password.login-check.enabled=false` | user `LoginPasswordAdvisorTest` 4 · `LoginPasswordAdviceFilterTest` 3（間違ったパスワードでは問い合わせない）· `SignInPage.test.jsx` +1 |
| 6 | **再設定リンクのトークンがアドレスバー · 履歴に残った**（docs/57 §19） | 最初に読んだ値を保持し、`history.replaceState` で `?token=` だけ消す | `ResetPasswordPage.test.jsx` +1 |
| 7 | **配送追跡リンクに送り状番号をそのまま入れていた** — `&` · `#` · `/` がリンクのクエリ · パスを変えた | RFC 3986 の非予約文字以外はパーセントエンコード（`Carrier.trackingUrlFor`） | delivery `CarrierTrackingUrlTest` 3 |

**デプロイ順**: notification（新しい通知二種類 — 古い notification は「新しい端末」の文言に落ちる）を user より先に、user（V21 — 新しい表一つ、戻しても安全）· gateway · frontend · 共通イメージ（cart · catalog · coupon · order · pay — 信頼パッケージ）。ゲートウェイと user-service は同じ Redis（`REDIS_HOST`、db 0）を見る必要がある — 違えば解除時刻を読めず以前どおり 15 分。デプロイ後: 「すべての端末からログアウト」の後、別のブラウザの次のリクエストが 401 か、ログ `로그인 비밀번호가 유출` の数 · 指標 `auth_breached_password_login_advised_total`。

**残り（コードだけでは終わらない）**:
- NetworkPolicy の egress 許可リスト — 決済 · SMS · メール · 配送会社 · HIBP · DNS の一覧を決めてから（誤って止めると決済が止まる）。 → 🕰️ 第 1 段階は §23（IP リストなし）。
- ~~共有サーバー証明書 `tls-keystore`（825 日）をサービスごとに · cert-manager + Reloader（§18）~~ → 🕰️ 2026-11-20 §27（サーバー証明書 = サービスごとの身元）、秘密管理ツール（SOPS · Sealed Secrets · Vault）と定期ローテーション、秘密鍵の HSM/KMS — 本番機材 · コストの判断。
- ~~管理ポートの分離（`management.server.port`）— プローブ · metrics-agent · マニフェスト 13 個を一緒に変えるデプロイ作業。~~ → 🕰️ 2026-11-20 §25。
- react-router v7 · jest 30 のメジャーアップ — 画面全体の回帰が必要な別作業。
- 同じ電話番号を二つのアカウントが使うこと — 止めるか（家族共用の番号）から決めること。

## 23. 外向き接続（egress）の第 1 段階 — IP リストなしで（2026-11-19）

**問題**: 受信は §11 で塞いだが、外向きの接続はすべて開いていた。サービス一つに SSRF · リモートコード実行があれば、その Pod からクラウドのメタデータ（`169.254.169.254` — 資格情報）· ノードの他のポート（kubelet · SSH · 観測スタック）· 同じ VCN の機器に届き、外へデータを出せる。ただし NetworkPolicy はドメインではなく IP でしか選べず、決済 · Atlas · Gmail · S3 の IP は変わる — IP の許可リストはいつか決済を止める（§22）。

**したこと** — `k8s/97-network-policy-egress.yaml`（ポリシー二つ、kustomization の外）:

| グループ | サービス | 出られる先 |
|---|---|---|
| クラスタ内のみ（`egress-cluster-only`） | eureka · gateway · cart · coupon · pay · saga-projection · frontend | 同じネームスペース · インフラのネームスペース（postgres · redis · kafka · mongodb · elasticsearch）· kube-dns（53）· ノードの OTLP 収集器（4318） |
| 外部が必要（`egress-public-no-private`） | user（OAuth · HIBP · 本人認証）· catalog（S3）· order（Stripe · PayPal · ポートワン · 為替）· notification（SMTP · SMS）· delivery（配送会社）· chat（Atlas）· config-server（設定 Git） | 上 + インターネット — ただし `10/8` · `172.16/12` · `192.168/16` · `169.254/16`（メタデータ）· `100.64/10` · `127/8` は閉じる |

ポリシーの外（以前どおり全開）: metrics-agent（ノード 9090 · API サーバー）· cloudflared · バックアップ CronJob — それぞれノード · 外部が必要で、客のリクエストを受けない（第 2 段階で）。

**適用は `ops/35-network-policy.sh egress-apply` だけで**:
1. 事前点検 — 「クラスタ内のみ」グループが使う PostgreSQL · Redis · Kafka のアドレス（ConfigMap `ecommerce-config`）がすべて `*.svc.cluster.local` か。external オーバーレイのように Neon · Upstash · Confluent に出した環境なら**適用しない**（そのグループが DB に届かない）。
2. マニフェストの `__NODE_IP__` の行をノードの InternalIP ごとに展開する（Pod の `HOST_IP` = トレースの送り先）。原本をそのまま `kubectl apply` すると CIDR が誤りで API サーバーが拒否する — 意図的。
3. 適用**前**に二つのグループ名の確認用 Pod（`netpol-probe-internal` · `-public`、同じポリシーを受ける）で流れを記録し、適用**後**にもう一度叩く — 許可: DNS · ゲートウェイ · user · PostgreSQL · Redis · Kafka · ノード:4318（public は + `api.stripe.com:443` · `1.1.1.1:443`）、遮断: メタデータ · ノード:10250（internal は + 外部二つ）。許可が一つでも塞がれば**自分で消す**、塞がるべきものが一つでも開いていれば警告終了（2、消さない）、ポリシー前から閉じていたものは判定から除く。

**確認**: 展開したマニフェストは k3s v1.30.6 の API サーバーの server dry-run を通過（原本は `__NODE_IP__/32` で拒否）· 偽の kubectl で判断六つ（`EgressPolicyScriptTest` — 適用 · 戻し · 外部 DB の拒否 · 未執行 · 一部だけ執行 · 前から閉じていたもの。戻し · 事前点検 · IP 展開 · 一部執行の判定を外すとそれぞれ失敗）· ガード **B-117**（`EgressPolicyGuardTest` 4 — ecommerce の Deployment はちょうど一つのグループ、「クラスタ内のみ」グループのコード · 設定 · frontend の nginx に外部アドレスなし、除外帯域 · 0.0.0.0/0 なし、ノードのプレースホルダー · kustomization の外。グループから外す · catalog を内側に入れる · 169.254 を外す · IP を手書きするとそれぞれ失敗）。⚠️ **実際の執行（kube-router）は見ていない** — この環境の docker 内 k3s は Pod を起動できなかった（入れ子の runc）。実習クラスタで先に `egress-apply` を回す。

🕰️ **2026-11-19 最初の実習**: registry-vm（イメージビルド VM）で回して「ConfigMap からバックエンドのアドレスを読めなかった」で止まった — そのシェルの kubectl が実習クラスタを指していなかったとみられる（スクリプトがエラーを握りつぶし理由を言わなかった）。今は事前点検が**クラスタに届かない（今のコンテキストと kubectl エラーの最初の行）· ConfigMap なし · キーが空**を分けて言う（`EgressPolicyScriptTest` +1）。実習クラスタの kubeconfig がある所（サーバーノード · kubectl を使う PC）で回す。

**順序**: 実習クラスタで `egress-apply` → 決済（カード · PayPal）· メール · ソーシャルログイン · 画像アップロード · チャット · 配送照会を一巡 → 本番ノードで `egress-apply`（戻すのは `egress-remove`）。本番にハイブリッドのリモートノード（docs/71）があればノード IP は二つ — スクリプトが両方を展開する。

**第 2 段階** → 🕰️ 2026-11-20 §26（metrics-agent · cloudflared · バックアップ CronJob のポリシー、public グループのポート絞り込み）。残り: 外部アドレスごとの許可（ドメイン単位が必要になれば Cilium など — ノードが増えるときに一緒に判断）。

## 24. 本番 Secret を暗号文でリポジトリに — SOPS + age（2026-11-19）

**問題**: 本番の値は平文の `k8s/20-secret.yaml`（gitignore）一つだけで、そのファイルが本番ノード · 作業 PC に散らばっていた。どれが最新か、いつ何を変えたかが残らない（ずれは ops/27 が事後に見つけた）。ディスクを失えば値も失う。

**決めたこと**: SOPS + age。Vault はサーバーをもう一つ運用する必要があり、Sealed Secrets はクラスタの鍵を別途バックアップしなければならない — 運用者一人 · ノード一台にはファイル一つが合う。値（`stringData` · `data`）だけを暗号化するので `kind` · 名前 · **キー名は平文**で、コミットの diff で「何が変わったか」は見え、値は見えない。

**したこと**:
- `ops/62-secrets-sops.sh` — `keygen [<ファイル>]`（age 鍵、公開鍵だけ出力 — ファイルを渡すとその名前で: 保管用 · 別の機械用の鍵をこの機械の鍵と別に）· `init <age1…>`（受け取る人）· `encrypt`（往復確認の後にだけ暗号文を替える）· `check`（値がすべて暗号文 · この機械の鍵で復号できるか · 例とキー名 · 平文と同じか — 値の代わりに sha256 の先頭 12 文字で比較）· `apply`（平文ファイルなしでパイプで `kubectl apply`）· `decrypt`（0600、違う平文は上書きしない — `FORCE=1`）· `updatekeys`。どの経路も値を出力しない。
- `.sops.yaml` — そのファイルだけ · 値だけを暗号化。受け取る人は最初プレースホルダー（`age1REPLACE_ME`）でコミットし — プレースホルダーの間 `encrypt` は拒否する — 2026-11-19 に実際の公開鍵三つに替えた。
- 暗号文 `k8s/20-secret.enc.yaml` は `.gitignore`（`k8s/20-secret.yaml*`）にかからない — コミットする。ポートフォリオ（公開）には移さない（`.sops.yaml` も）。
- 受け取る人は三つを勧める — 本番ノード · 作業 PC · オフライン保管（紙 · パスワード管理）。秘密鍵をすべて失うと暗号文は復号できない。

**確認**: 実際の sops 3.9.4（公式リリース · チェックサム確認）· age で `SopsSecretsScriptTest` 6 — 値はすべて暗号文（平文の値なし、キー名は見える）· 二人の受け取る人とも復号 · プレースホルダー · 誤った鍵の拒否 · 暗号文に平文が一行混じるとそのキー名だけを言って失敗（値は言わない）· 違う平文を上書きしない · `updatekeys` で外した鍵は復号できない · `apply` は平文ファイルを作らない。sops · age が PATH になければスキップ（CI）— そのときもガード **B-118**（`SopsSecretGuardTest` 4: `.sops.yaml` の対象 · `encrypted_regex` · 受け取る人の形、コミットされた暗号文の値がすべて `ENC[…]`（空の値を除く）、`.gitignore` が平文は塞ぎ暗号文は塞がない、ポートフォリオ除外）が回る。変異: プレースホルダー検査 · キー名の読み取り · 上書き防止 · パイプ apply（平文ファイルに替えると止まる）を外すとそれぞれ失敗、`.gitignore` が暗号文まで塞ぐか `encrypted_regex` を広げるとガードが失敗。
🐛 作りながら捕まえたこと: sops は YAML を 4 桁のインデントで書き直す — 最初のキー名の読み取り（2 桁だけ）は暗号文の「平文混入」検査で**一行も見ずに通過**していた（実際の sops で回して判明）。空の値（`""`）は sops が暗号化しない（隠すものがない — 漏えいとは数えない）。`diff` は違えば 1 を返し、`pipefail` の下でスクリプトが**何も言わずに**終わっていた。

**最初の一回**（本番ノードで — 平文がある所）:
1. sops · age をインストール（age は `apt install age`、sops はリリースのバイナリ + チェックサム）。
2. 本番ノード · 作業 PC はそれぞれ `./ops/62-secrets-sops.sh keygen`、保管用は名前を渡して `keygen ~/age-offline-backup.txt`（移した後その機械では消す）→ 公開鍵三つを集めて `init age1… age1… age1…`。**完了（2026-11-19）** — 受け取る人三つ（作業 PC · オフライン保管 · 本番ノード、三つとも作業 PC で作って移した）を `.sops.yaml` にコミットした。
   ⚠️ `init` は**リポジトリの中で**回す — スクリプトは自分の位置（`$0/..`）でリポジトリのルートを決める。`/tmp` にコピーを出して `sudo` で回すとルートが `/` になり、`/.sops.yaml` に root 所有で書く（リポジトリには何も生まれない）。本番のチェックアウトを汚さないなら場所だけ指定する: `SOPS_CONFIG=$HOME/ecommerce-msa/.sops.yaml /tmp/62.sh init …`。
   ⚠️ 同じ公開鍵を二度渡しても `sops` は拒否しない — レビューで「受け取る人が三つ」に見え、誰がもう一人いるのかと数えさせる。`init` が重複を取り除き、いくつ外したかを言う（実際に本番ノードの最初の `.sops.yaml` に同じ鍵が二つあった）。
3. `encrypt` → `check` → `git add k8s/20-secret.enc.yaml .sops.yaml` → コミット · push（値は暗号文）。
4. その後は平文ではなく暗号文が原本 — 変えるときは `decrypt` → 修正 → `encrypt` → コミット、適用は `apply`。ops/27 · 46 · 59 のように平文を読むスクリプトは `decrypt` で平文を作ってから回す。

**最初の暗号化（2026-11-19、本番ノード）**: `encrypt` → キー **32 個**を暗号化 · `check` は四行緑（値はすべて暗号文 · この機械の鍵で復号 · 平文と同じ）。`check` が出した二行は**本番の実際の状態**で、欠陥ではない:
- 例にあって暗号文にない三つ — `TOTP_ENCRYPTION_KEY`（なければ `TotpSecretCipher` が JWT 秘密から派生した鍵を使い警告を出す、docs/70 — **JWT 秘密を交換する前に**専用鍵を入れて一度デプロイする必要がある）· `JWT_SIGNING_KEYS` · `JWT_ACTIVE_KID`（なければ legacy = `JWT_SECRET_KEY` の一鍵、ops/46 をまだ回していない）。クラスタの Secret も 32 個でファイルとずれはない。
- 暗号文にだけある四つ — `OAUTH2_{GOOGLE,GITHUB}_CLIENT_{ID,SECRET}`。例では任意のプロバイダーはコメントなので比較に乗らない。
⚠️ 本番ノードで `ops/62` を回すときは**スクリプトをリポジトリの `ops/` の中に置いて**回す（`$0/..` でルートを決めるため `/tmp` のコピーは平文 · 例 · 暗号文の場所をすべて `/` で探す — `SOPS_PLAIN` · `SOPS_ENC` は変えられるが例のパスは変えられない）。終わったら取り出したファイルを消し、チェックアウトをデプロイ版のままにする（docs/85 §9-2 ⓠ）。
🐛 そのときディスクに平文が三つあった — nano が残した `20-secret.yaml.save`（2026-08-01）· `.save.1`（2026-10-24、ストアのパスワード交換の直前）で指紋が互いに違った。`.gitignore` の `k8s/20-secret.yaml*` はコミットを塞ぐが、「どれが最新か分からない」はこの節が無くそうとした問題そのものだ — `shred -u` で消した（暗号文のコミット履歴がその役を果たす）。

**残り** → 🕰️ 2026-11-20 下の §24-1（ずれの警告 · 交換チェックリスト · 保管鍵の復旧訓練）。~~まだ残るもの: 専用の `TOTP_ENCRYPTION_KEY` を入れる~~ — 完了（2026-11-20、§24-2 の末尾）。

### 24-1. ずれの警告 · 交換チェックリスト · 保管鍵の復旧訓練（2026-11-20）

- `./ops/62-secrets-sops.sh drift` — 暗号文 ↔ クラスタの `ecommerce/ecommerce-secrets`。値の代わりに正規化した指紋（stringData は値のまま、`data` · クラスタは base64 を解いたバイトの sha256 先頭 12 文字）で**キー名だけ**を言う: 片方にだけあるキー · 値が違うキー。同じなら 0、違えば 1。
  **`ops/28` がデプロイ前に呼ぶ**（`check_secret_drift`）— 違えば知らせ、デプロイは続ける。sops · この機械の鍵 · 暗号文がなければ飛ばす（`SKIP_SECRET_DRIFT=1` でも）。
- `./ops/62-secrets-sops.sh drill <保管鍵ファイル>` — **この機械の鍵を使わず**その鍵一つで復号できるか · `.sops.yaml` の受け取る人にいるか · 平文 · クラスタと同じか。四半期ごとにオフラインの鍵を出して一度回し、日付を下の表に書く（鍵は再びオフラインへ）。
- `./ops/62-secrets-sops.sh rotation` — キーごとに**値が最後に変わった日**、古い順 · `ROTATION_DAYS`（既定 90）を超えると ⏰。🐛 最初は暗号文のそのキーの行が変わったコミットで見たが、`encrypt` は値を**すべて暗号化し直す**（新しい IV）ので行は毎回変わる — その日付は「最後の encrypt」にすぎない。履歴の版ごとにこの機械の鍵で復号し、指紋が最後に変わった版を探す（復号できない古い版で止まる、値 · 平文ファイルは残さない）。

**🐛 最初の `drift` が見つけた 2 つ（2026-11-20 本番、`ops/28` のデプロイ前の警告）** — 暗号文 32 · クラスタ 34:

- `SPRING_DATA_REDIS_PASSWORD` — **クラスタが正しい。** このキーはサンプルのテンプレートから来た**コメント行**（`# SPRING_DATA_REDIS_PASSWORD: "REPLACE_ME…"`）としてしかなく、値は `ops/36-rotate-redis-password.sh` がクラスタに直接書く — 最初の `encrypt` は平文ファイルをそのまま暗号化したので、**コメントはキーではなく**漏れた。⚠️ このとき `./ops/62 apply` を回してはいけない — `sops --decrypt | kubectl apply -f -` なので、last-applied の注釈次第でそのキーを**消し得る**（Redis を使う全部 — 流入制限 · カートのキャッシュ · chat のファンアウト · フィルターメニューの先取り — が認証失敗になる）。クラスタの値を暗号文に**入れる**方向で直す。
- `REACT_APP_PAYPAL_CLIENT_ID` — **消すもの。** ビルド時点の値でマニフェストから読む場所がなく（B-51 — REACT_APP_* は k8s に置かない）、PayPal の CLIENT_ID は公開値だ。`kubectl -n ecommerce patch secret ecommerce-secrets --type=json -p '[{"op":"remove","path":"/data/REACT_APP_PAYPAL_CLIENT_ID"}]'`（2026-11-20 適用）。

平文にキーを**足す**ときはファイルの末尾に付けない — `stringData` ブロックの後に長いコメントと `sops:` のメタデータがあり、EOF への追加は YAML を壊す（2026-11-20: それで `encrypt` が `Error unmarshalling file: yaml: line 8` で拒否した — **暗号文はそのままで被害はなかった**）。`stringData` の最後のキー行（4 スペースのインデント）の後に同じインデントで挿し、`encrypt` の前に `yaml.safe_load` で読めるか · 値がそのままかを見る。値は `kubectl get secret … -o jsonpath` → ファイルで受けて使い、画面に出さない（§5）。

~~**残り**: `SPRING_DATA_REDIS_PASSWORD` を暗号文に入れて `drift` を 0 にする。~~ — 完了（2026-11-20、§24-2 の末尾 — `from-cluster` で作り直した暗号文に入った）。

**四半期の交換チェックリスト**（`rotation` の ⏰ から）:

| キー | 変え方 | 読み直すところ |
|---|---|---|
| `LOCAL_DB_PASSWORD` | DB で `ALTER ROLE product PASSWORD …` → `decrypt` → 修正 → `encrypt` → `apply` | DB を使うバックエンドをすべて再起動 — 変える間、古い接続はそのまま、新しい接続だけ新しい値 |
| `LOCAL_KEY_STORE_PASSWORD` · `LOCAL_KEY_PASSWORD` | `ops/59-rotate-store-password.sh` check → apply → 一日後に cleanup（ストア · Secret · 平文を一緒に） | ops/59 が一つずつ再起動 |
| `JWT_SIGNING_KEYS` · `JWT_ACTIVE_KID` | `ops/46-rotate-jwt-key.sh` add → activate → トークンの寿命の後に retire（docs/68 §17） | user · gateway · chat |
| `JWT_SECRET_KEY` · `TOTP_ENCRYPTION_KEY` · `SMS_CODE_PEPPER` | **定期交換しない** — 2FA 待ちトークン · リカバリーコード（`auth.recovery-code.pepper` がなければ）· TOTP の秘密 · 認証番号の根なので、変えるとそれらが無効になる。漏れたときだけ、利用者の再登録の案内と一緒に | user · notification |
| `KAFKA_SSL_*` | ブローカーのストアと一緒に | Kafka を使うサービス |
| 外部 API キー（`AMAZON_*` · `PORTONE_*` · `STRIPE_*` · `PAYPAL_*` · `MAIL_PASSWORD` · `DELIVERY_*` · `SPRING_DATA_MONGODB_PASSWORD` · ES） | 提供元のコンソールで新しいキー → `encrypt` → `apply` → そのサービスを再起動 → 確認後に古いキーを廃棄 | そのキーを読むサービス |

Redis のパスワード（`SPRING_DATA_REDIS_PASSWORD`）はこの Secret に**ある**（🕰️ 2026-11-20 訂正 — 最初はないと書いた）: `ops/36-rotate-redis-password.sh` がこのキーと `redis/redis-auth` を一緒に変える — 変えた後 `from-cluster` → `encrypt` で暗号文にも。交換 · 訓練の記録:

| 日付 | したこと | 結果 |
|---|---|---|
| 2026-11-20 | 古い暗号文の適用事故の復旧（§24-2）→ `from-cluster` で暗号文を作り直し + `TOTP_ENCRYPTION_KEY` を新しく | `drift` キー 35 · 35 で一致、2FA 秘密 0 行（再暗号化するものなし）、コミット `539a2a2c` |
| — | （最初の `drill` · `rotation` をここに） | |

**確認**: `SopsSecretsScriptTest` +3（実際の sops · age — `drift` は違うキー名だけ · 値を出さない · 終了 1、`drill` は保管鍵一つで通り、受け取る人でない鍵は失敗、`rotation` は暗号化し直しただけの版を交換と数えない）· ガード `SopsSecretGuardTest` +1（ops/28 が `drift` を一度呼ぶ）。変異: 指紋の比較を外す · `drill` がこの機械の鍵で復号する · `rotation` が版 0 の日付だけを見る、でそれぞれ失敗。

### 24-2. 古い暗号文を適用した事故 · apply が先に比べる（2026-11-20）

**何が起きたか**: 本番ノードで TOTP 専用鍵（docs/70）を入れようとして `encrypt` が「平文がない」で止まり、その前の暗号文（11-19 の最初の `encrypt` — そのときの平文がすでに古かった）を `sops -d … | kubectl apply -f -` で適用した。結果: **22 キーが古い値で上書きされ**（10-25 に変えたストアのパスワード · JWT · DB · メール · 決済 …）、暗号文になかった **5 キーは消えた**（`SPRING_DATA_REDIS_PASSWORD` · OAuth GitHub · Google の 4 つ — `kubectl apply` は前回の適用にあったキーが新しいファイルになければ消す）。空の値 3 つ（`JWT_SIGNING_KEYS` · `JWT_ACTIVE_KID` · `TOTP_ENCRYPTION_KEY`）は新しくできた。再起動した user-service の新しい Pod だけが `keystore password was incorrect` で CrashLoop — Reloader は `tls-id-*` しか見ないので他のサービスは再起動されず、古い Pod が持ちこたえて利用者への影響はなかった。

**戻し方**（値を出力せずに）: 生きている古い Pod の環境変数は適用前の Secret そのままだ。① 古い Pod の env 名 ↔ Secret · ConfigMap の名前（`env -0 | cut -z -d= -f1` — 複数行の値の断片が名前の一覧に漏れないように）② Secret のキーごとに古い Pod の値と sha256 で比べる → 違うキー · 消えたキーを古い Pod の値で `kubectl patch --type merge`（base64 をパイプで）③ もう一度比べて 0 · 新しくできた空のキーは `null` で削除 ④ `kubectl.kubernetes.io/last-applied-configuration` 注釈を削除（古い値がその中にあり、次の apply がキーを消す根拠になる）。

**直したこと**（`ops/62`）:
- `apply` はまずクラスタと指紋で比べる — **クラスタにだけあるキーがあれば拒否**（消えるキーの名前だけ）、値が変わる · 新しくできるキーは **`EXPECT_CHANGED="K1 K2"` で名前を挙げたときだけ**適用する（変えるつもりのキーだけが変わるか）。同じなら何もしない。クラスタに届かなければ比較なしには適用しない（NotFound のときだけ新しく作る）。
- `from-cluster` — クラスタの Secret から平文（0600）を作る。暗号文が本番より古いとき本番の値からやり直す: `from-cluster` → 変えるキーだけ直す → `encrypt` → `drift`（変えたキーだけが違う）→ `EXPECT_CHANGED=… apply` → コミット。
- `encrypt` が失敗しても一時ファイル（`20-secret.enc.yaml.XXXXXX`）を残さない — RETURN トラップは `exit` では動かなかった（本番ノードに一つ残っていた）。今は EXIT トラップ。

**確認**: `SopsSecretsScriptTest` +4（実際の sops · age — クラスタにだけあるキーの拒否 · 名前のない変更の拒否 · `EXPECT_CHANGED` なら適用 · 同じなら何もしない、`from-cluster` → `encrypt` → `drift` が同じ · 既存の平文は上書きしない · 引用符 · コロン · ハングルの値、`encrypt` 失敗で一時ファイルなし）。変異: 拒否の検査 · 名前の検査 · 後始末の登録をそれぞれ外すと失敗。

**仕上げ（2026-11-20）**: 本番ノードで新しい `ops/62` により `from-cluster`（キー 34）→ `TOTP_ENCRYPTION_KEY` 1 行（`openssl rand -base64 32` — 初めて入れる鍵なので新しく作った。その前のコミット `4be5da5c` の値は適用されたことがない）→ `encrypt` → `drift`（暗号文にだけ TOTP）→ `EXPECT_CHANGED=TOTP_ENCRYPTION_KEY apply` → user-service 再起動。確認: Secret の TOTP 長 44 · Pod ログに「TOTP_ENCRYPTION_KEY がない」なし · `users.users.two_factor_secret` 0 行（TOTP を有効にした利用者はまだいない — 今後は `enc:k1:`）· `drift` 35 · 35 で一致 · 暗号文コミット `539a2a2c`。事故中に作った写し（誤って適用された Secret の yaml · 古い暗号文 2 つ）は消した。

## 25. 管理ポートの分離 — actuator は 9180、指標エージェントだけ（2026-11-20、ガード B-119）

**問題**: actuator（health の詳細 · metrics · prometheus）がアプリのポートにあり、入ってくるポリシー（§11、k8s/95）は同じネームスペースに**すべてのポート**を開いていた。B-112 で公開リストを四つに絞ったが、同じネームスペースの Pod が一つ破られるだけで全サービスの指標 · health の詳細を認証なしで読めた。egress 第 1 段階（§23）も同じネームスペースは開く — その道は受ける側で閉じる必要がある。

**したこと**:
- Java の Deployment 13 個（k8s/30 〜 49）に `MANAGEMENT_SERVER_PORT=9180` · コンテナポート `management`（9180）· actuator の HTTP プローブは `port: management`。**設定ファイル（yml）には入れていない** — 開発 PC はサービスを一台で一緒に立てるので管理ポートが重なる。変わるのは k8s だけ。
- 管理ポートは**アプリのポートの TLS を引き継ぐ**（`server.ssl` — プローブはそのまま HTTPS）· Spring Security の規則も引き継ぐ（config-server のパスワード、user-service の `EndpointRequest`）。Service は管理ポートを出さない — Ingress · トンネル · 他の Pod が Service 名で届く道がない。
- k8s/95: `allow-same-namespace` は**アプリのポートのリストだけ**（マニフェストの containerPort と同じ — ポートを足すならここにも、ガードが合わせる）· 新しい `allow-management-from-metrics-agent`（9180 は `app=metrics-agent` · 確認用 Pod だけ）· traefik → アプリのポート（8000 · 8080）だけ。kubelet のプローブ · `kubectl port-forward` はノードから来るのでポリシーと無関係（kube-router）。
- 指標エージェント（external · demo）はポート名 `management` だけを収集する（以前はポートごとに収集し gRPC 9095 だけを外していた）。
- デプロイのスモーク: `ops/28` がゲートウェイの health を `port-forward deploy/gateway-server 19180:9180` で見る（`GATEWAY_HEALTH` → ops/06）。管理ポートのない旧バージョン（自動復帰の後）なら以前どおりアプリのポートを見る。⚠️ これを直さなければスモークの「ゲートウェイ health」が 404 で失敗し、**すべてのデプロイが自動復帰**していた。
- `ops/35 check · apply` が管理ポートを確認する（Pod IP:9180 — 指標エージェントのラベルは届き、同じネームスペースの他の Pod は塞がれる · エージェントが塞がれたら戻す）。

**デプロイの順序**（イメージの変更なし — マニフェストだけ、次のデプロイと一緒に）:
1. `ops/28` でデプロイ — Pod が管理ポートで立ち直す（プローブがそのポート）。
2. **すぐに**指標エージェントを新しい規則に: `kubectl apply -f k8s-overlays/external/metrics-agent.yaml && kubectl -n ecommerce rollout restart deploy/metrics-agent`。その間はサービスの指標が途切れる（旧規則はアプリのポートを収集して 404 — `ServiceDown`）。`ops/28` は旧規則なら知らせる（`warn_metrics_agent`）。
3. 入ってくるポリシーを使うクラスタなら `./ops/35-network-policy.sh apply`（管理ポートの行が新しく出る）。
4. 確認: Pod の中で `curl -sk https://localhost:9180/actuator/health` · `up{job="spring-actuator"}` が 11 個 · アプリのポートの `/actuator/health` は 404。

**確認**: gateway · config-server の `ManagementPortTest`（テスト用の証明書をその場で作り **HTTPS で** — アプリのポートの /actuator は 404、管理ポートは TLS で health · prometheus、config-server は管理ポートでもパスワードなしで 401 · 正しければ 404（公開外）— セキュリティ規則がかかる証拠）· user-service の `ManagementPortTest`（コンテナ — `EndpointRequest` の規則が管理ポートにも、業務 API は 401）· ガード **B-119** `ManagementPortGuardTest` 5 · `NetworkPolicyScriptTest` 3。変異: プローブ一つをアプリのポートに · 同じネームスペースの許可からポートのリストを外す · エージェントの keep 規則を drop に · スモークをアプリのポートに · テストから管理ポートの設定を外す · ops/35 がエージェントの遮断を失敗と見ない、でそれぞれ失敗。

> 🕰️ 2026-11-20 の本番デプロイ（`d8922fc53e0c`）の後、**サービス指標が 21 分途切れた** — ConfigMap はすでに新しい規則だったので `ops/28` は警告しなかったが、エージェントの **Pod** は古い規則を握っていた（起動時に initContainer が ConfigMap を設定ファイルにし、その後は読み直さない）。今は `ops/28` がデプロイの最後に **Pod の起動時刻 < ConfigMap の最終更新（managedFields の最も遅い時刻）** ならエージェントを再起動する（`METRICS_AGENT_RESTART=0` なら知らせるだけ · デプロイは止めない · ConfigMap が古い規則なら以前どおり再適用を促す）。テスト `MetricsAgentStaleScriptTest` 5（変異: 時刻の比較を外すと 2 失敗）。

## 26. 外向き接続（egress）第 2 段階 — ポートの絞り込み · ポリシー外だった Pod（2026-11-20）

第 1 段階（§23）の残りをした。適用はそのまま `./ops/35-network-policy.sh egress-apply` だけ（k8s/97 全体 — 第 1 · 2 段階を一度に）。

| ポリシー | 選ぶ Pod | 開くもの |
|---|---|---|
| `egress-public-no-private` | 外が必要な七つ | 第 1 段階のまま + インターネットは **TCP 443 だけ**（以前はすべてのポート） |
| `egress-smtp` | notification | メール 587 · 465（プライベート · メタデータの外） |
| `egress-mongo-atlas` | chat · mongo-backup | Atlas 27017 · DNS · クラスタ内の mongodb |
| `egress-metrics-agent` | metrics-agent | 同じネームスペースの **9180 だけ**（管理ポート、§25）· ノード IP:6443（API サーバー — kubernetes Service は DNAT の後ノードの 6443）· remote write TCP 9090（本番はノード IP、デモは観測 VM のプライベート IP — だから帯域ではなくポートで）· DNS |
| `egress-cloudflared` | cloudflared | Cloudflare TCP · UDP 7844 · TCP 443（プライベートの外）· gateway（8000）· frontend（8080）· kube-system の traefik · DNS |
| `egress-backup-postgres` | postgres-backup · restore-drill | postgres ネームスペースの 5432 · DNS — IP 帯域は一つも開かない |

- バックアップの CronJob の Pod には選ぶためのラベルがなかった — `app: postgres-backup · mongo-backup · restore-drill` を足した（k8s/85 — 適用はいつも `ACTION=cronjobs ./ops/29`）。
- 事前点検が第 2 段階の前提を見る（値は出さない）: メールのポート（Secret `MAIL_PORT`）が 465 · 587 · 設定リポジトリ（`CONFIG_GIT_URI`）が https（ssh なら 443 の外）· バックアップの `PGHOST` がクラスタ内。一つでも違えば適用しない。
- 確認するグループは七つ（internal · public · mail · mongo · metrics · cloudflared · backup）— public は `1.1.1.1:80` が塞がれ、指標エージェントはアプリのポート · DB へ、cloudflared は DB · アプリのサービスへ、バックアップは Redis · gateway · インターネットへ行けないこと。戻し（`egress-remove` · 自動）は七つのポリシーをすべて消す。

**まだ見ていないもの**: kube-router の実際の適用（第 1 段階と同じ — この環境の docker 内の k3s は Pod を立てられない）。特に ① 指標エージェントの API サーバーへの接続（ポリシーが DNAT の後のノード:6443 を見るという前提）② cloudflared の QUIC（UDP 7844）③ traefik の Pod ラベル `app.kubernetes.io/name: traefik`（k3s の既定チャート）。実習クラスタで `egress-apply` → 指標 11 個 up · サイト · 決済 · メール · チャット · バックアップ一回（`kubectl create job --from=cronjob/postgres-backup …`）→ 本番。

**確認**: `EgressPolicyScriptTest` 9（+2 — public の 443 の外が開いていれば成功ではない、メールのポート · ssh のリポジトリ · 外の PGHOST なら適用せず値を言わない）· ガード B-117 `EgressPolicyGuardTest` 6（+2 — インターネットの規則はすべてポートを持つ · public 443 · メール · Atlas · トンネル · エージェントのポートと選ぶ Pod、バックアップは IP 帯域なし、ポリシーの名前が CronJob · オーバーレイの実際のラベルと同じ、ops/35 が消すポリシー = k8s/97）。変異: public のポートを外す · CronJob のラベルを変える · 消すリストから一つ外す · public の 80 の確認を外す · メールのポートの点検を開く、でそれぞれ失敗。

🕰️ **2026-11-20 最初の実習（server-node · agent 三つ · data、第 1 段階は 31 時間前から掛かっていた）**: `egress-apply` が事前点検で止まった — デモの Secret のメールは `MAIL_HOST=localhost` · `MAIL_PORT=25`（送らない設定）なのにポートだけを見て拒否した。Pod 内のループバックは NetworkPolicy の対象外なので、今はホストが localhost · 127.x · ::1 なら「使わない」と見る。同じ `egress-check` で metrics グループの `最初のノード:6443` · `最初のノード:9090` がポリシー前から閉じていて判定から外れた — 複数ノードでは API サーバーは一つのノードにだけあり、remote write は観測 VM だ。今は metrics グループは `kubernetes` のエンドポイント（実際の API サーバー）と `metrics-agent-config` の remote write の url（`__HOST_IP__` ならノード）を叩く。実習のノードには OTLP 収集器（ノード:4318）がなく、その行は常に「ポリシー前から閉じていた」（実習のトレース構成の問題 — ポリシーとは無関係）。実習にはバックアップ CronJob がない — バックアップのポリシーは叩き Pod だけで見て、実際の Job は本番で。テスト `EgressPolicyScriptTest` +2（localhost のメール · 外部ホストの 25 は拒否、指標グループの実際の宛先 — 変異: 二つの修正をそれぞれ戻すと失敗）。適用結果（同日）: rc=0 — 第 2 段階のポリシー五つを作成 · public の 80 を遮断 · メール · Mongo · 指標 · cloudflared · バックアップのグループの担当外はすべて遮断、**指標エージェント → API サーバー（`server-node:6443`、DNAT 後）· remote write（obs-vm）· 管理ポートは開いている**、**cloudflared → traefik は開いている**（ラベル確認）— 未確認の三つのうち二つが解けた（QUIC は実習に cloudflared がなく本番で）。一緒に分かったこと（ポリシーとは無関係 — ポリシーを全部外しても同じだった）: ① 指標エージェントの Pod が 11-19 に起動し、13:15 の新しい規則を知らないまま対象 42 個（アプリのポートまで）を収集していた — 再起動で 21 個すべて up（今の `ops/28` はこれを捕まえる）② **チャットの SockJS がすべて 403** — chat だけ `frontend.url` が `https://localhost:3000` 固定で `shop.lab` のオリジンを拒否した。order · user と同じく `APP_PUBLIC_URL` に従うよう直した（chat イメージ、`WebSocketAllowedOriginTest` +2）③ 実習のトレース先（`otel-collector` → obs-vm のプライベート IP）はノード IP:4318 だけを開くポリシーの外なので遮断される — 本番はノード上の収集器なので無関係、実習でトレースが要るときはポリシーを外す。④ オリジンを直した後もチャットが切れた — 相談の購読 · 切断処理が `StackOverflowError`（スタックは `DefaultedRedisConnection.pExpire` だけ）: chat の Redisson 3.23.5 は Spring Data Redis 3.1 用のモジュールで、Boot 3.5 の条件付き `pExpire` が接続自身を呼び直した。`Error` なので presence の catch も通り抜け、STOMP の購読が「Failed to send message to clientInboundChannel」で死んだ — 3.50.0（`redisson-spring-data-35`）へ（テスト `RedissonExpireRecursionTest`、旧版で失敗; `ChatScaleOutRedisTest` · `ChatStompIntegrationTest` のコンテナ試験は通過）。cart · order も 3.23.5 — `expire` を呼ばないので表に出なかった → 2026-11-21 同じ 3.50.0 に合わせた（cart は `RedisTemplate` · キャッシュが Redisson 接続の上、order は Lettuce だが starter が Redisson の Bean を作る。同名の試験二つ、旧版で失敗 · コンテナ試験は通過、次のデプロイに同梱）。⑤ その後 WebSocket だけが 403 — chat 直接は 101、ゲートウェイ経由は 403、Origin を外すと 101: ゲートウェイの CORS 許可オリジンが `https://localhost:3000` だけだった → `${APP_PUBLIC_URL}` を加えた（gateway `CorsAllowedOriginTest`）。同じ日の決済画面: Stripe のテスト決済 → 配送作成 → 発送まで進み、order → `api.stripe.com:443` を確認。上の進行バーの 4 番目の名前は `配送中` だったものを注文状態の文字（`発送済み` · `발송 완료`）に — 下の配送追跡の「移動中」と食い違って見えた（`OrderProgress.test.jsx`）。🕰️ 2026-11-21: 三つの修正（`429a3f35` · `f1c205eb` · `c613ba2e`）を含む新しいイメージを実習へデプロイ — **チャット · 相談の動作、進行バーの `発送済み` を確認**（egress ポリシーを掛けたまま）。本番は次のデプロイで一緒に。

🕰️ **2026-11-21 本番の最初の適用 — 自ら元に戻した（rc=1）**: 事前点検 · バックアップのラベル（`ACTION=cronjobs ./ops/29` — ラベルは CronJob ではなく Pod テンプレートに付く）は通過、叩きもメール 587 · Stripe 443 · 指標エージェント → ノード 6443 · 9090 · 管理ポート · cloudflared → traefik · バックアップ → postgres はすべて開き、塞ぐべきものはすべて塞がった — **ノードの収集器 `ノードIP:4318` だけが塞がれた**（三つのグループすべて）。原因: 本番ノードの観測スタックは docker compose で、公開ポートが DNAT で**コンテナ IP（docker ブリッジ）**に変わる — ポリシーは変わった宛先を見るので `ノードIP/32` の規則に合わなかった（同じ compose の Prometheus 9090 は指標ポリシーがポートだけで開いていて通過、k3s API 6443 はホストが直接受けるので DNAT なし）。実習のノードには収集器がなく表に出なかった。修正: 4318 の規則に docker ブリッジ帯域 `172.16.0.0/12` を加えた（その規則のポートは 4318 一つ — ガード `EgressPolicyGuardTest` が見る、戻すと失敗）。本番の Atlas 27017 は叩きの対象ではないので（本番 mongodb 0/0 — ℹ️）適用後にチャットで見る。→ 同日二度目の適用 **rc=0**（DNAT の宛先 `172.18.0.8:4318` を確認、三つのグループすべてで 4318 が開いた）。適用後、本番の 1:1 相談の入力ボタンがグレー — **ポリシーを外しても同じだった**: ブラウザ `…/chat-service/chat/…/xhr_streaming 403`、chat のログ 0 行 = 本番イメージ（`d8922fc53e0c`）の SockJS オリジン固定（`429a3f35` で修正）で egress 以前から相談が塞がっていた。次のデプロイ（chat · gateway · frontend）で直る。

## 27. サーバー証明書をサービスごとに — 共有 `tls-keystore` の代わりに身元で（2026-11-20、ガード B-121）

**問題**: Java サービス 13 個がすべて**同じサーバー鍵**（ops/07 の `keystore.p12`、Secret `tls-keystore`）で HTTPS を開いていた。その証明書の SAN には `*.ecommerce.svc.cluster.local` と全サービス名がある — Pod 一つでその鍵が漏れれば**どのサービスにも成りすませた**（ゲートウェイ → バックエンド · Feign · Eureka 登録がサーバーを確認するとき、すべて通る）。有効期間 825 日 · 手で交換して全 Pod を再起動する必要があった（§18 の残り）。サービスごとの身元（§16 · §18 — 90 日、cert-manager が期限 30 日前に再発行、Reloader が再起動）にはすでに `server auth` とそのサービス名 · FQDN があった — gRPC サーバー（catalog 9095）はもともとそれを使っていた。

**やったこと**:
- Java Deployment 13 個（k8s/30 〜 49）に `SERVER_SSL_KEY_STORE=/app/identity/keystore.p12` · 形式 PKCS12 · パスワードは両方 `$(LOCAL_KEY_STORE_PASSWORD)`（cert-manager の PKCS12 は鍵パスワード = ストアパスワード）。環境変数は yml の `server.ssl.key-store: ${LOCAL_KEY_STORE_PATH}` より優先される（Spring のプロパティ順序）— **`LOCAL_KEY_STORE_PATH` はそのまま**: Postgres（`sslrootcert`）· Redis の設定が同じ変数を読む。管理ポート（§25）はサーバー TLS を引き継ぐので一緒に変わる。
- eureka-server にも身元 `id-eureka-server`（k8s/96 · ops/38 · ops/39）— マウント · Reloader 注釈 · `EUREKA_CLIENT_TLS_ENABLED=true`（身元をマウントした Pod の規則 B-86 — 自分のピア複製もその証明書を出す）。config-server はすでに `SERVER_SSL_*` 四つがあり（B-91）、パスだけ身元に。
- 身元ごとに名前を一つ追加: `metrics.ecommerce.svc.cluster.local` — 指標エージェント（k8s-overlays/external）が Pod IP で収集し、**全 Pod でこの名前で**サーバーを確認する（Prometheus は対象ごとに `server_name` を変えられない）。以前は共有証明書のワイルドカードがカバーしていた。この名前で接続するのはエージェントだけで、エージェントは「内部 CA が出した証明書か」だけを見る — 今と同じ保証。
- `ops/38`（手発行）: eureka-server を追加 · SAN にその名前 · **期限が残っていてもその名前のない古い身元は作り直す** · `check` が知らせる。`ops/39`（cert-manager）: 一覧に eureka-server — k8s/96 の `dnsNames` が変わるので `apply` すると cert-manager が 13 個を再発行し、Reloader が一つずつ再起動する。
- `ops/28` のデプロイ前点検: マウントする身元 Secret ごとに指標の名前がなければ**止める**（なしでデプロイするとサービス指標がすべて途切れる — `ServiceDown`）。
- 共有 `tls-keystore` は残る — 私設 CA（`ca.crt` · トラストストア）、Kafka クライアントのキーストア、Postgres · Redis クライアント設定が読む。サーバー鍵としてはもう誰も使わない（本番クラスタの外 — 開発 PC の `LOCAL_KEY_STORE_PATH` はそのままその鍵）。

**変わらないもの**: サーバーを確認する側が使う名前 — ゲートウェイ（Eureka の `EUREKA_INSTANCE_HOSTNAME` = FQDN）· Feign（`FEIGN_*_URL` = FQDN）· Eureka 登録 · config クライアント（FQDN）· デモ Ingress（`serverName` = ゲートウェイ FQDN）— すべて身元の `dnsNames` にある。cloudflared は `noTLSVerify`、kubelet のプローブは確認しない。CA が同じなのでトラストストアもそのまま。

**デプロイ順序**（イメージ変更なし — マニフェストだけ、次のデプロイに一緒に）:
1. 身元を新しい名前で: cert-manager なら `./ops/39-cert-manager.sh apply`（13 個を再発行 · Reloader が再起動 — まだ古いマニフェストなので動作は同じ）、手発行なら `./ops/38-issue-service-identities.sh`（eureka-server を新規 · 名前のない身元を作り直し）→ サービスの再起動は 2 が行う。
2. `ops/28` でデプロイ — 上の点検が名前 · eureka の身元を確認してから適用し、Pod が身元で HTTPS を開き直す。
3. 確認: `./ops/37-check-mtls.sh`（内部呼び出しの 200 · 403 はそのまま）· `up{job="spring-actuator"}` 11 · どれかの Pod で `openssl s_client -connect order-service.ecommerce.svc.cluster.local:8084 -servername order-service.ecommerce.svc.cluster.local </dev/null 2>/dev/null | openssl x509 -noout -subject -enddate` が `CN=order-service` · 90 日以内。
4. 戻し: 前のバージョンへ `ops/28 --rollback`（古いマニフェスト = 共有鍵）— 身元の名前が増えたことは古いマニフェストにも害がない。

**確認**: ガード **B-121** `ServerCertificateGuardTest` 3（Java Deployment ごとにサーバーキーストア = 自分の身元 · `LOCAL_KEY_STORE_PATH` そのまま · その Secret をマウント · Reloader 注釈、k8s/96 の Certificate ごとに server auth · 名前 · FQDN · 指標の名前 · ワイルドカードなし、ops/38 · ops/39 · Java Deployment の一覧が同じで ops/38 の SAN · 作り直し · ops/28 の点検）。変異: サービス一つのサーバーキーストアを共有鍵に · 身元一つから指標の名前を抜く · ops/38 が名前のない身元を期限だけ見て飛ばす、のそれぞれで失敗。ops/38 の openssl 設定で作った証明書が serverAuth · 三つの名前を持つことを別に確認した。**まだ見ていないもの**: 実クラスタで新しいキーストアでの起動 · 更新後の再起動（この環境の docker 内の k3s は Pod を立てられない）— 実習クラスタで 1 → 3 の順に。

**残り**: 共有 `tls-keystore` の Kafka クライアント鍵（ブローカーが確認するクライアント証明書）もサービスごとに · インフラ（Kafka · Redis · Postgres）のサーバー証明書を cert-manager に — インフラの再起動を伴うので別に計画する。秘密鍵の HSM/KMS は本番機材 · コストの判断。
