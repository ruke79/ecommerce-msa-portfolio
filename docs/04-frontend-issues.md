# フロントエンドの問題点分析（Production Readiness Review）

**日本語** | [한국어](04-frontend-issues.ko.md)

> 🕰️ **状態（2026-09-27）— 過去の記録。** FE-1〜9 は [05](05-frontend-improvements.md) · [26](26-frontend-review-and-refactor.md) で修正しました。FE-10（CSRF）はサーバーのクッキーを `SameSite=Lax` と明示して解決しました（[docs/62 §8](62-engineering-casebook.md)）。

> 作成日: 2026-07-24
> 対象: `frontend/`（React 18 SPA）
> 観点: セキュリティ · JWT のメモリ保存/再リクエスト（refresh）ロジック · 本番での堅牢性
> 関連: [改善結果ドキュメント](05-frontend-improvements.md)

このドキュメントは **改善以前** のフロントエンドの問題を、ファイル・行番号を根拠に記録する。

---

## 0. 要約

まず **良い点** から: ランタイムの実経路では、**アクセストークンはメモリ（AuthContext reducer）にのみ** 保持されている。ログインフロー（`SignInPage.jsx` の `login(user, accessToken, exp)`）は localStorage にトークンを書き込まず（該当する 107-108 行はコメントアウトされている）、axios のリクエストインターセプターはメモリ上の `tokenRef.current` だけを使う。リフレッシュトークンは HttpOnly クッキー（サーバー）+ `withCredentials` で自動送信される。**つまり README が掲げる「アクセス=メモリ / リフレッシュ=HttpOnly クッキー」の設計は、ランタイムでは守られている。**

問題は、その設計が **コードで強制されておらず**、再リクエスト/タイムアウト/エラー処理など **本番での堅牢性** に欠陥があることだ。

| # | 問題 | 深刻度 | 性質 |
|---|---|---|---|
| FE-1 | `tokenUtil.js` に localStorage の **トークンアクセサが残存**（dead but footgun） | 🟠 セキュリティ | XSS によるトークン窃取の再導入リスク |
| FE-2 | `api.js` のリクエストラッパーで **axios 引数の誤用 → per-request config が未適用**、インスタンスに **timeout がない** | 🔴 正確性 | リクエストが無限に待機しうる |
| FE-3 | プロアクティブな **リフレッシュタイマーの時間計算バグ**（exp の秒/ms 混同） | 🔴 正確性 | 自動更新が事実上動作しない |
| FE-4 | **トークン/レスポンスのコンソールログ漏洩**（本番ビルドを含む） | 🟠 セキュリティ | トークン/PII の露出 |
| FE-5 | 401 リフレッシュキューの **エッジケースのデッドロック**（非 200 レスポンス時） | 🟠 正確性 | リクエストが永久に hang |
| FE-6 | api 関数の **エラー握りつぶし**（`catch → return undefined`） | 🟡 品質 | 失敗の隠蔽、下流でのクラッシュ |
| FE-7 | `axiosRetry` の **壊れた再試行** ロジック（未使用のまま残存） | 🟡 品質 | デッドコード |
| FE-8 | **正体不明/未使用の依存関係**（`save-dev`, `toast`） | 🟡 サプライチェーン | 衛生 |
| FE-9 | **デッドコード/設定**（redux-persist whitelist、未使用の isAdmin など） | 🟡 品質 | 混乱の原因 |
| FE-10 | **CSRF** 対策（リフレッシュクッキー）— フロントのカスタムヘッダーがない | 🟠 セキュリティ（参考） | サーバーのクッキー設定に依存 |

---

## FE-1. localStorage トークンアクセサの残存（footgun）🟠

`frontend/src/util/tokenUtil.js:3-13`
```js
getToken()   { return localStorage.getItem("access_token"); }
updateToken(token) { localStorage.setItem("access_token", token); }
removeToken(){ localStorage.removeItem("access_token"); }
```
- 現在は **呼び出されていないデッドコード**（ログイン/ProtectedRoute の localStorage 経路はすべてコメントアウトされている）。
- しかし **生きた公開 API** として残っているため、誰かが「便利だから」と再び使えば、すぐに **XSS によるトークン窃取** の攻撃面が開く。「メモリ保存」の保証が *存在しないことによって強制* されていない。
- 併せて: `isAdmin()/setAdmin()` は `IS_ADMIN` を localStorage から読むが、**`setAdmin` を呼ぶ箇所がない** ため常に null（デッドコード）。それにもかかわらず `AuthContext.js:87-88` がこれを読んで未使用の変数に格納している。

## FE-2. api.js リクエストラッパーの欠陥 → config 未適用 & 無限待機 🔴

`frontend/src/util/api.js:52-95`
```js
export function getRequest(URL, payload=null, contentType="application/json") {
  return api.get(`${URL}`, payload, contentType, { timeout: config.timeout, validateStatus: config.statusRange })
             .then(r => r);
}
export function postRequest(URL, payload, contentType="application/json") {
  return api.post(`${URL}`, payload, contentType, { ...config });
}
```
- axios のシグネチャは `get(url, config)`、`post(url, data, config)`。上のコードは:
  - GET: 3・4 番目の引数（`contentType`、実際の config オブジェクト）が **無視される** → 意図した `timeout: 10s`、`validateStatus` が **まったく適用されない**。
  - POST/PUT/PATCH: `contentType`（文字列）が **config の位置** に渡される誤用。
- さらに `axios.create(...)` に **`timeout` がない** ため per-request timeout も効かず、**リクエストが無限に待機** しうる（ゲートウェイ/サービスが遅いときにブラウザタブが hang）。本番では致命的。
- （逆説）もし `validateStatus: status < 500` が実際に適用されていたら、4xx が **正常に resolve** され、下記の 401 自動リフレッシュインターセプター（エラーコールバック）が **動作しなくなる**。つまりこの config はそもそもインターセプターの設計と相反する → **正解は validateStatus ではなく timeout** である。

## FE-3. プロアクティブなリフレッシュタイマーの時間計算バグ 🔴

`frontend/src/util/TokenRefresher.js`（proactive refresh timer）
```js
refreshAccessTokenTimerId = setTimeout(() => { refreshAccessToken(); },
  new Date(expiresAt).getTime() * 1000 - Date.now() - 10 * 1000);
```
- `expiresAt = decodedToken.exp` → **秒（seconds）単位の epoch 数値**。
- `new Date(expiresAt)` は引数を **ミリ秒として解釈** → 1970-01-01 付近の誤った時刻になる。そこへ `* 1000` を **さらに** 掛けるので値が完全にずれる。
- 結果: 遅延値が負（→ 即時発火）または異常に大きな値になり、**有効期限 10 秒前の自動更新は事実上壊れており**、401 に反応する経路だけに依存している。正しい式は `expiresAt * 1000 - Date.now() - 10000`。

## FE-4. トークン/レスポンスのコンソールログ漏洩 🟠

- `TokenRefresher.js`: `console.log(response.status)`、`console.log(response)` — **レスポンスヘッダーの `access`（アクセストークン）** がそのままコンソールに出力される。
- `api.js`: `loadCheckOut` の `console.log(data)`、`getOrder` の `console.log(orderId)` など、データ/PII のログが多数。
- CRA/webpack の本番ビルドは `console.log` を除去しないため、**本番のコンソールにトークン・注文情報が露出** する。

## FE-5. 401 リフレッシュキューのエッジケースのデッドロック 🟠

`TokenRefresher.js` の 401 処理: リフレッシュリクエストのレスポンスが `200` 以外の場合（例: リフレッシュトークンなし → `204`）、`if (response.status == 200)` ブロックを飛ばし、**try の中で return しないまま** 通過する。その後、下部の
```js
return new Promise((resolve, reject) => { refreshAndRetryQueue.current.push({ resolve, reject }); })
```
でキューに push されるが、これを resolve する主体がいないため **そのリクエストは永久に hang** する。（200 以外の失敗処理がない + 無限ループ防止フラグがない）

## FE-6. api 関数のエラー握りつぶし 🟡

`api.js` のほとんどの関数が
```js
catch (error) { console.log("erorr >>>", error.response?.data.message); }
```
で終わり、**undefined を返す**。React Query は `throwOnError:true` なのに関数が throw しないため **エラーバウンダリが捕捉できず**、呼び出し側は `undefined` を分割代入/`.map` してクラッシュする。失敗が静かに消えるアンチパターン。

## FE-7. axiosRetry の壊れた再試行 🟡

`api.js` の `axiosRetry` は `setTimeout(() => { return axiosRetry(...) }, delay)` となっており、**setTimeout のコールバック内で return** しているため、再試行の結果が上位のプロミスにつながらない（プロミスが未解決のまま）。現在は未使用だが残存している。

## FE-8. 正体不明/未使用の依存関係 🟡

`frontend/package.json`
- `"save-dev": "^0.0.1-security"` — `npm i save-dev` のタイプミスで入り込んだ **セキュリティホールド用のプレースホルダーパッケージ**。無意味で誤解を招く。
- `"toast": "^0.5.4"` — コードは `react-hot-toast` しか import していない。**未使用**。
- ソースのどこでも両パッケージを import していない → 削除しても安全。

## FE-9. デッドコード/設定 🟡

- `redux/store.js`: `persistConfig.whitelist: ['auth']` だが、`auth` リデューサーは `rootReducer` で **コメントアウト** されており存在しない。`blacklist: ['token']` もネストしたキーには適用されない → **意味のない設定**。
- `AuthContext.js:86-89`: `isADmin` を計算した後、どこでも使っていない。
- `ProtectedRoute.jsx` の下部: localStorage/`console.log` ベースのデッドなコメントブロック。

## FE-10. CSRF（参考）🟠

`withCredentials: true` により **リフレッシュトークンのクッキーが自動送信** される。リフレッシュエンドポイントは状態を変更する（rotation）ため、クッキーベースの認証面に対する **CSRF 対策**（SameSite=strict/lax + 必要に応じてカスタムヘッダー/トークン）が重要になる。これは主に **サーバーのクッキー設定** の責任であり、フロントはカスタムヘッダーの CSRF トークンを使っていない。→ [`docs/05`](05-frontend-improvements.md) のロードマップに明記。

---

## 改善の優先順位

- **即時修正（安全・高価値）**: FE-1（トークンアクセサの削除）、FE-2（timeout + 引数の修正）、FE-3（タイマーのバグ）、FE-4（ログ漏洩）、FE-5（デッドロック）、FE-8（ジャンク依存関係）。
- **部分修正/文書化**: FE-6（主要経路のみ）、FE-7（削除）、FE-9（整理）。
- **サーバーとの協業が必要**: FE-10（CSRF クッキーポリシー）。

実際の適用内容は [`docs/05-frontend-improvements.md`](05-frontend-improvements.md)。
