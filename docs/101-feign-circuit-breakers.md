# 101. サービス間 Feign 呼び出し — サーキットブレーカーの方針

> 2026-11-21 · 第 4 段階 ④ 第 1 次 — cart→catalog · order→catalog · order→coupon に適用(次のデプロイで — cart · order イメージ)。
> 韓国語版: [101-feign-circuit-breakers.ko.md](101-feign-circuit-breakers.ko.md)

## 0. なぜ

Feign 呼び出しはすべて同じ上限(接続 3 秒 · 読み取り 5 秒、`application-common.yml`)で、再試行 · サーキットブレーカーがなかった。相手が落ちる · 遅いと呼ぶ側は要求ごとに最大 5 秒スレッド · DB 接続を握ったまま待ち、その間の要求まで詰まる — 一つのサービスの障害が呼ぶサービスへ広がる。

## 1. 方式 — 同じスレッド、元の例外そのまま

Spring Cloud の Feign サーキットブレーカー(`spring.cloud.openfeign.circuitbreaker.enabled`)は使わない(以前保留した理由 — `application-common.yml` のメモ):

| 問題 | なぜ |
|---|---|
| `Authorization` ヘッダーが切れる | TimeLimiter が別スレッドで呼ぶが、`FeignConfig` は `RequestContextHolder`(スレッドローカル)からヘッダーを移す |
| 既定の時間上限 1 秒 | Feign の読み取り上限 5 秒より短い — 遅い正常な呼び出しが失敗になる |
| 例外を包む | `NoFallbackAvailableException` — `catch (FeignException.NotFound)`(クーポン決済確保)· デコーダーの `CustomException` の分岐が壊れる |

代わりに `common` の `UpstreamCircuitBreakers.wrap` がクライアントのインターフェースを**同じスレッドで**包み、**元の例外をそのまま**投げる。各サービスの `config/UpstreamCircuitBreakerConfig` が包んだものを `@Primary` で出し、Feign の元は `primary = false` · `qualifiers = "raw…"` でだけ残す(両方 primary だと起動失敗)。呼ぶ側のコードはそのまま — 開いている間は `CallNotPermittedException`(RuntimeException)が既存の `catch (RuntimeException)` の道を**5 秒待たずに**すぐ進む。

- 失敗として数えるもの: 接続失敗 · タイムアウト(`RetryableException`)、5xx · 429(`FeignException` · `UpstreamCallException`、原因の連鎖)。4xx は相手が健全に拒否したもので数えない。
- 設定: 直近 20 回のうち 10 回以上 · 失敗率 50% 以上で開く → 30 秒後に半開で 3 回試す。
- 指標 `upstream_circuit_state{name}`(0 閉 · 1 開 · 2 半開)、状態遷移は WARN 一行。

## 2. 第 1 次の適用 — 呼ぶ側の処理(そのまま)

| ブレーカー | 呼び出し | 呼ぶ所 | 失敗 · 開いたとき |
|---|---|---|---|
| `cart-catalog` | `getProductsInfo` · `getCartProductsInfo` · `getColorsInfo` | 追加 · 数量 · 照会 · チェックアウト · 保存 | 追加 · 数量 · 照会 · チェックアウトは 503、チェックアウトの価格合わせ · SKU 補完は保存値で続行、**保存(`savecart`)は 500**(§4) |
| `order-catalog` | `reserveStock` · `releaseStock` · `getColorsInfo` | 注文作成 · 決済窓の更新 · 入金取消 · 期限切れ | 予約は予約なしで続行(fail-open — 開けばすぐ)、解除は警告だけ(catalog の期限が解く)、色は空のまま。拒否(400 · 422)は失敗に数えず 400 |
| `order-coupon` | `quote` · `claimForPayment` | 注文作成 · 決済開始 | 止める(400)、古い coupon の 404 は失敗に数えずそのまま通す |

試験: common `UpstreamCircuitBreakersTest`(4 — 分類 · 開いた後は呼ばない · 4xx は閉じたまま元の例外 · 同じスレッド)、cart · order のコンテキスト試験(コンテナ — 注入値がプロキシ · 元は qualifier · 指標)。変異: 包んだ Bean の `@Primary` を外すと cart のコンテキストが起動しない。

## 3. 全体の表(34 メソッド、2026-11-21 調査)

| 呼び出し | 経路 | 失敗処理 | 第 1 次 |
|---|---|---|---|
| cart→catalog 商品 · カート情報 · 色(3) | 利用者 | 503 · 保存値 · savecart 500 | ✅ |
| cart→user デモアカウント | 毎日のジョブ | 翌日に再度 | — |
| coupon→cart カート | 利用者(見積) | 200 + 「確認できない」(止める) | 次 |
| coupon→catalog 商品情報 · 企画展クーポン | 利用者 | 空(カテゴリ制約が合わない)· 409 | 次 |
| coupon→user 利用者 · 顧客群 | 管理者 · 利用者 | 400 · 空リスト(止める) | 次 |
| delivery→order 配送先 | Kafka · 管理者 | 再試行約 3 分 → DLT · 200 created:false | 次(delivery に resilience4j なし) |
| order→cart カート · 商品 id | 利用者 | **500**(§4) | 次 |
| order→catalog 予約 · 解除 · 色 | 利用者 · 予約ジョブ | fail-open · 警告 · 空 | ✅ |
| order→coupon 見積 · 決済確保 | 利用者 | 400(止める)· 404 通過 | ✅ |
| order→user メールのまとめ | 管理者 | メールなし | 次 |
| user→catalog 評点 · 商品 | 利用者 · 照合ジョブ | レビューは保存 · 400 | 次 |
| user→notification 二段階 SMS 送信 · 確認 · デモ初期化 | ログイン · 毎日のジョブ | 503 · 翌日 | 次 |
| user→order 購入統計 · 購入確認 | 等級ジョブ · レビュー | 次の回 · 503(止める) | 次 |

呼ぶ所がないメソッド 9 個(cart `getProductInfo` · `getCartProductInfo` · `getColorInfo`、chat `findUserByEmail`、order `getColorInfo` · `findUserByEmail` · `findUserByName` · `getUserInfoWithAddresses` · `selectShippingAddresses`)は削除の候補。

## 4. 調査で分かったこと — 修正(2026-11-21、次のデプロイで)

| # | 何 | 修正 | 試験 |
|---|---|---|---|
| 1 | cart `savecart` の catalog 失敗が **500** | 他のカート経路と同じ `failure()` — 数量範囲は 400、上流 · ブレーカー開 · DB は 503 | `CartSaveFailureStatusTest` |
| 2 | order の注文作成で上流(cart …)の失敗が **500** | `isUpstreamFailure` か `CallNotPermittedException` なら 503(文言同じ)、それ以外だけ 500 | `OrderCreateAddressValidationTest#upstreamFailureIs503` |
| 3 | user · chat のデコーダーが 400 · (find でない)404 で `null` → **NPE**、他は検査例外 | cart と同じく常に `UpstreamCallException(メソッド, 状態)` · find の 404 はそのまま利用者なし — 呼ぶ側の `catch (RuntimeException)` はそのまま受ける | user `FeignErrorDecoderTest` |
| 4 | 全体のハンドラーに上流失敗の 503 がない | common `handleUpstream` — `UpstreamCallException` は 503(上流の状態 · メソッドは本文に出ない)。`CallNotPermittedException` は common が実行時に resilience4j を持たないので呼び出し地点で(1 · 2) | `CustomRestExceptionHandlerTest#upstreamCallIs503` |
| 5 | 呼ぶ所のないメソッド 9 個 | 削除した(chat はクライアントごと)— 試験の `never()` 確認も(その道に戻れない) | コンパイル |
| 6 | ブレーカーの警報がない | `UpstreamCircuitOpen`(開 · 半開が 5 分) | promtool `reliability-rules.test.yml` |
