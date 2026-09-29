# フロントエンド改善内容（What Changed）

**日本語** | [한국어](05-frontend-improvements.ko.md)

> 🕰️ **状態（2026-09-27）— 過去の記録。** ロードマップとして残した FE-6 · FE-9 もその後修正しました（24d06b4）。フロントのテストは [docs/28](28-frontend-test-suite.md) から — 現在 930 件。

> 対象: `frontend/` · 関連: [問題点分析](04-frontend-issues.md)

[`docs/04`](04-frontend-issues.md) で導き出した問題を根拠にした実際の変更一覧である。
原則はバックエンドの改善と同じである: **ランタイムで検証可能でリグレッションリスクの低い変更だけを適用** し、サーバー側との協業が必要な項目（CSRF）はロードマップとして残す。フロントは当時の検証環境でフルビルド（node_modules が存在しない）を実行できなかったため、変更した純粋な JS は `node --check` で構文検証し、それ以外は最小限・精密な編集に限定した。

---

## 1. アクセストークン保存の強制 — localStorage の footgun の除去 🟠 (FE-1)

`frontend/src/util/tokenUtil.js`
- localStorage ベースの `getToken/updateToken/removeToken`、`isAdmin/setAdmin/getUser/setUser` を **すべて削除**。
- `remove()` は維持しつつ、`localStorage.clear()` → **レガシーキー（`access_token`, `USER`, `IS_ADMIN`）だけを選択的に削除** に変更（無関係な redux-persist の状態を保持）+ storage にアクセスできない環境（Safari のプライベートなど）の防御。

`frontend/src/store/AuthContext.js`
- 使われていなかった `TokenUtil.isAdmin()` の計算行と `tokenUtil` の import を削除。

**効果**: アクセストークンの「メモリ専用」保証が **コードで強制** されるようになった（トークンを localStorage に入れる経路自体が存在しない → XSS によるトークン窃取の攻撃面を除去）。

## 2. axios リクエストラッパーの正常化 + リクエストタイムアウト 🔴 (FE-2, FE-7)

`frontend/src/util/api.js`
- `axios.create(...)` に **`timeout: 15000`** を追加 → リクエストの無限待機（タブの hang）を遮断。
- リクエストラッパー（get/post/put/patch/delete）を **axios のシグネチャに合わせて書き直し**:
  - GET/DELETE `(url, config)`、POST/PUT/PATCH `(url, data, config)`。
  - `contentType` は `withContentType()` ヘルパーで **per-request ヘッダー** にのみ反映。
- **`validateStatus`（status<500）の未適用** を意図的に維持: これを適用すると 4xx が正常に resolve され、**401 自動リフレッシュのインターセプターが壊れる**。つまり元のコードが入れようとしていた config はインターセプターの設計と相反する → 正解は timeout だけであることを反映。
- 壊れていた（そして未使用だった）`axiosRetry` を **削除**。

**効果**: per-request 設定が実際に適用され、遅いバックエンドでもリクエストが 15 秒以内に失敗として終わる。401 リフレッシュの流れはそのまま保持。

## 3. プロアクティブリフレッシュのタイマーバグ修正 🔴 (FE-3)

`frontend/src/util/TokenRefresher.js`
```diff
- }, new Date(expiresAt).getTime() * 1000 - Date.now() - 10 * 1000);
+ const delay = Math.max(0, expiresAt * 1000 - Date.now() - 10 * 1000);
+ setTimeout(() => refreshAccessToken(), delay);
```
- `expiresAt`（JWT `exp`、秒単位）を正しく ms に変換し、負の遅延を 0 にクランプ。

**効果**: 有効期限の **10 秒前の先行更新** が実際に動作 → 401 の反応型パスだけに依存しないため、ユーザーのリクエスト中のトークン期限切れによる瞬間的な失敗が減少。

## 4. トークン/レスポンスのコンソールログ流出の除去 🟠 (FE-4)

- `TokenRefresher.js`: リフレッシュレスポンス全体を出力していた `console.log(response)`、`console.log(response.status)` を削除（レスポンスヘッダーのアクセストークン露出を遮断）。
- `api.js`: `loadCheckOut` の `console.log(data)`、`getOrder` の `console.log(orderId)` を削除。
- `hooks.js`: `useFetchCoupons` のクーポンレスポンスのログを削除。

## 5. 401 リフレッシュキューのデッドロック修正 🟠 (FE-5)

`frontend/src/util/TokenRefresher.js` の 401 処理を書き直し:
- リフレッシュのレスポンスが **200 でないとき**（例: 204）待機キューを **すべて reject + ログアウト + reject を返す** → 従来の「キューに push するだけで誰も resolve しない」**永久 hang を除去**。
- `originalConfig._retry` フラグで **無限リフレッシュループを防止**。
- 不要だった自分自身のキュー push（`refreshPromise`）を削除して流れを単純化。同時の 401 リクエストは引き続きキューで待機した後、新しいトークンで 1 回リトライ。

## 6. 依存関係の衛生 🟡 (FE-8)

`frontend/package.json` から **ソースのどこからも import されていない** パッケージを削除:
- `save-dev@^0.0.1-security`（誤インストールされたセキュリティホールドのプレースホルダー）
- `toast@^0.5.4`（未使用、`react-hot-toast` を使用中）

---

## ロードマップとして残した項目

| 項目 | 理由 |
|---|---|
| **FE-6 エラー握りつぶしの全面整理** | 多数の `catch → return undefined` を throw に変えると React Query のエラーバウンダリへ伝播するが、多くの呼び出し元が undefined を許容するように書かれているため **広範なリグレッションリスク**。中核パスから段階的に適用する必要がある（フルビルド/E2E 検証が前提）。 |
| **FE-9 redux-persist の整理** | `whitelist:['auth']` が存在しないリデューサーを指しており、事実上無効。これを直すと cart などが新たに永続化される **動作の変化** が生じうるため、意図を確認するまではランタイムの動作を変えずにドキュメント化のみ。 |
| **FE-10 CSRF** | リフレッシュ Cookie の `SameSite`/CSRF 防御は **サーバーの Cookie 設定** の責務。フロントはサーバーのポリシー確定後にカスタムヘッダートークンと連携。 |
| 本番ロギングのゲート | 残っている多数の `console.log("erorr >>>", ...)` を `NODE_ENV!=='production'` でゲートするロガーに一括置換（別途の整理 PR を推奨）。 |

---

## 検証

- `src/util/api.js`、`src/util/tokenUtil.js`: `node --check` **通過**。
- `TokenRefresher.js`、`AuthContext.js`（JSX）: 波括弧/丸括弧/角括弧のバランスを確認。
- `package.json`: 有効な JSON であることを確認、削除したパッケージが **import されていない** ことを確認。
- リクエストラッパーのシグネチャ変更が既存の呼び出し元（2 引数以下）と互換であることを grep で確認。
- ⚠️ 認証インターセプター（401 リフレッシュ）は auth-critical なパスであるため、マージ前に **ログイン/トークン期限切れ/同時リクエストのシナリオの手動 QA** を推奨。
