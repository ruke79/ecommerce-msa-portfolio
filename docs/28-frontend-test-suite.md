# 28. フロントエンドテストの導入 — そしてすぐに捕まったバグたち

**日本語** | [한국어](28-frontend-test-suite.ko.md)

> 🕰️ **状態（2026-09-27）— 過去の記録。** CRA を取り除いたので jest は直接依存です。テストは現在 930 件です。

docs/26 の最後の節で「フロントエンドのテストが一つもない」ことを最優先課題に挙げた。このドキュメントは
その作業の結果である。

**結論から言うと、テストを付けた途端に新しいバグが出てきた。** そのうちの一つは会員登録を事実上不可能に
するもので、ビルドも通り、コードを読んでも見えない種類のものだった。

---

## 1. 何を入れたか

新しい依存は **一つもインストールしなかった。** jest 27、jest-environment-jsdom、`@testing-library/*`、
identity-obj-proxy、babel-jest は `react-scripts` 経由ですでに `node_modules` にあった。
ネットワークが遮断された環境でも `npm test` がすぐに動かなければならないからだ。

| ファイル | 役割 |
|---|---|
| `jest.config.js` | jsdom 環境、webpack alias と同じモジュール解決、CSS・画像のモック |
| `src/setupTests.js` | jest-dom マッチャー、未処理の Promise 拒否の露出、既知のノイズだけを選別して遮断 |
| `src/__mocks__/fileMock.js` | 画像・フォントの import を代替 |
| `package.json` | `npm test`、`test:watch`、`test:coverage` |

2 点は説明が必要だ。

**`moduleDirectories: ["node_modules", "src"]`** — webpack の alias（`api`、`components`、
`util` …）はすべて `src` 配下を指しているので、この 1 行で同じ解決ルールになる。`jsconfig.json` の
`baseUrl` は **エディター専用** なので jest には何の影響もない（docs/26 で webpack の alias を別途
登録しなければならなかったのと同じ理由だ）。

**axios を CJS ビルドにマッピング** — axios 1.x のエントリーポイントは ESM で、jest 27 は `node_modules` を
変換しないため `Cannot use import statement outside a module` で落ちる。同じバージョンが一緒に
配布している `axios/dist/node/axios.cjs` を指すことで解決した。

**ノイズは選別してだけ止めた。** `console.error` を丸ごと殺すと、PropTypes の警告や unmounted
setState のような本物のシグナルまで消えてしまう。RTL 13.4 + React 18.3 の `act` deprecation と
react-router の v7 予告警告、この 2 つの文字列だけを除外する。

---

## 2. 🐛 テストがすぐに捕まえたもの — 入力した文字が消える

最も重要な発見だ。**テストを書いているうちに偶然出てきたのではなく、最初のフォームテストがいきなり失敗した。**

ログインフォームに `a@example.com` を入力したのに、画面に残った値はこうだった:

```
SignInPage   : "a@example.com"  →  "aeapecm"
RegisterPage : "a@example.com"  →  "@xml.o"
```

### なぜそうなったか

フォームの値の **出どころが 2 つ** あった。

```jsx
// ページ: 自分の state を持ち、その値を Formik の initialValues に渡す
const [user, setUser] = useState(initialUser);
const { email, password } = user;

<Formik
  enableReinitialize                    // ← キー入力のたびにフォームを初期化する
  initialValues={{ email, password }}   // ← ページの state を指している
  ...
>
  <LoginInput name="email" onChange={handleChange} />   // ← ページの onChange
```

```jsx
// LoginInput: field を先に展開し、props を後で展開する
const [field, meta] = useField(props);
<input {...field} {...props} />        // ← props.onChange が field.onChange を上書きする
```

連鎖はこうなる。

1. `{...props}` が後ろに来るので、ページの `onChange` が **Formik の onChange を上書きする。**
   → Formik はユーザーが何を入力したかを知らない。
2. ところが `value` は依然として `{...field}` から来る → **Formik が値を所有するコントロールドインプット** だ。
3. ページの state が変わると、`enableReinitialize` が Formik を新しい `initialValues` にリセットする。
4. この往復が 1 レンダーずつずれながら、キー入力が互いを上書きする → **文字が失われる。**

### なぜ今まで誰も気づかなかったか

**人が手でゆっくり打つと、なかなか表に出ない。** レンダーが追いつく時間があるからだ。
`userEvent.type` は人より速く入力するので、この競合をそのまま露出させる。

そしてこれは **コードを読んで見えるバグではない。** 各部品はどれももっともらしい — state をページに
置くことも、`enableReinitialize` も、再利用可能な入力コンポーネントも。問題は 3 つの相互作用だ。
ビルドも通るし、リントも通る。

### どう直したか

**Formik ひとつにフォームの値を所有させる。**

```jsx
// 修正後
<Formik
  initialValues={{ email: "", password: "" }}   // 静的
  validationSchema={loginValidation}
  onSubmit={signInHandler}                       // Formik が検証した values をそのまま受け取る
>
  <LoginInput name="email" placeholder="..." />  // onChange を渡さない
```

同じ形が **9 ファイル** にあり、そのうちフォームの値を扱う 4 つを直した。

| ファイル | 影響 |
|---|---|
| `pages/user/SignInPage.jsx` | ログイン |
| `pages/user/RegisterPage.jsx` | **会員登録（最も深刻 — 正常なメールアドレスの入力自体が不可能）** |
| `pages/profile/security.jsx` | パスワード変更 |
| `pages/user/ForgotPage.jsx` | パスワード再設定 |

`security.jsx` と `ForgotPage.jsx` はハンドラーがページの state を読んでいたので、Formik の
`values` を受け取るように一緒に直した — そうしないと **空の値がサーバーに送信される。**

> 残っている箇所: `components/checkout/AddShipping.jsx`（入力 8 個）、`CreateProduct`、`CreateCategory`、
> `CreateSubCategory`、`Summary`。同じ形だが state の構造がより絡み合っているため、今回の範囲では
> 手を付けなかった。**AddShipping は配送先住所を受け取る画面なので優先度が高い** — §6 に残した。

---

## 3. 🐛 会員登録がハングルの名前を拒否していた

上のバグを直しても、会員登録のテストはまだ失敗した。原因は別物だった。

```js
username: Yup.string()...matches(/^[aA-zZ]/, "Numbers and Special characters are not allowed")
```

`"홍길동"` がこの検証を通過できない。**韓国語 UI で、韓国の配送業者と韓国の SMS を使う
サービスなのに、ハングルの名前では登録できなかった。**

同じ正規表現には 2 つ目の問題もある。文字クラス `A-z` は、ASCII で `Z` と `a` の間にある
`` [ \ ] ^ _ ` `` まで含んでしまう。つまり「特殊文字禁止」と言いながら、`_홍길동` のような値は通していた。

意図（「数字・特殊文字禁止」）をそのまま移した:

```js
.matches(/^[\p{L}][\p{L}\s]*$/u, "Numbers and Special characters are not allowed")
```

Unicode の文字と空白だけを許可するので、ハングル・日本語・ラテン文字はすべて通過し、数字・記号は弾かれる。

---

## 4. 何をテストしたか

**129 テスト、15 ファイル。** テストのためのテストではなく、*実際に壊れていたもの* と *壊れたら
ユーザーがすぐに痛い目を見るもの* を選んだ。

| ファイル | 件数 | 守るもの |
|---|---|---|
| `util/jwtUser.test.js` | 12 | `role`（単数）クレームのパース — 間違えると管理者が締め出される |
| `util/array_utils.test.js` | 9 | `randomize` が元の配列を壊さず、実際にシャッフルする |
| `util/pricing.test.js` | 6 | 割引計算 — 50% 割引は 50% 割引でなければならない |
| `util/api.test.js` | 12 | エラーを握りつぶさない、身元をクエリで送らない |
| `api/deliveryApi.test.js` | 10 | 409 =「発送済み」、400 と区別 |
| `api/notificationApi.test.js` | 7 | 429 = 正常な制限、残り試行回数を伝達 |
| `components/ProtectedRoute.test.jsx` | 8 | 未ログインの遮断、**管理者は通過**、セッション復元中は待機 |
| `components/delivery/DeliveryTracker.test.jsx` | 13 | ステップの折りたたみ、分割配送、通関、キャンセル拒否の理由 |
| `components/notification/NotificationBell.test.jsx` | 12 | バッジ、開いたときだけ一覧をリクエスト、削除が遷移に波及しない |
| `components/notification/SmsVerification.test.jsx` | 9 | ステップ遷移、クールダウン、残り試行回数、クライアント側では検証しない |
| `components/admin/DashboardLayout.test.jsx` | 2 | サイドバーの描画、画面に `//` が表示されない |
| `pages/user/ButtonInput.test.jsx` | 5 | **`disabled` が実際に適用される** |
| `pages/user/SignInPage.test.jsx` | 7 | 文字の消失なし、失敗理由の表示、2FA ステップ |
| `pages/user/RegisterPage.test.jsx` | 6 | 文字の消失なし、失敗を成功に偽装しない |
| `redux/store.test.js` | 11 | persist が cart を実際に保存し、auth は保存しない |

### テストを書きながらコードも一つ移した

`discounted()` が `pages/Product.jsx` の中にあったため、純粋な計算一つを検証するのに
Header → AuthContext → axios → ProductPage → Swiper まですべて引っ張ってこなければならなかった。`util/pricing.js`
に移した。**テストしにくいというシグナルは、たいてい設計のシグナルだ** — 計算ルールは画面と無関係なので、
画面モジュールに縛られている理由はなかった。

---

## 5. CI

`.github/workflows/build.yml` のフロントエンドジョブにテストを入れ、**ビルドより先に** 実行する。

今回見つかったものは、どれも `npm run build` が通るコードだった — 文字の消失も、`disabled`
の無視も、割引計算の誤りも、権限ガードの問題も。ビルドはこういうものを捕まえられない。

---

## 6. 残りの作業

1. **`AddShipping.jsx`** — 同じ二重 state の形で、入力が 8 個ある。配送先住所を受け取る画面なので、
   文字の消失がそのまま **誤配送** になる。次の優先事項。
2. **管理者フォーム 3 種**（`CreateProduct`、`CreateCategory`、`CreateSubCategory`） — 同じ形。
3. **`LoginInput` 自体を直す案** — 今は `{...field} {...props}` の順序のせいで、呼び出し側が
   うっかり Formik を上書きできてしまう。順序を逆にするか `onChange` を塞げば、同じミスが構造的に
   不可能になる。ただし 8 か所の呼び出し側に影響があるので、別途扱う。
4. **カバレッジ目標はまだ掲げていない。** 数字を先に決めると、意味のないテストで埋めることになる。
   今は「壊れていたもの ＋ 壊れたら痛いもの」を基準に増やしていくほうがよい。
5. **E2E（Playwright）** — このドキュメントのテストはすべてコンポーネント単位だ。ゲートウェイを通る
   本物のフローはまだ検証されていない（docs/27 §検証範囲を参照）。

---

## 補足: この作業が実際に証明したこと

docs/26 でこう書いた — *「今回直したものの多くは、テストが 1 行でもあれば即座に
捕まっていた種類だ。」*

それはすでに直したバグについての事後評価だった。今回は **テストを先に付けたら、誰も知らなかった
バグが 2 つ出てきて、そのうち一つは会員登録を塞いでいた。** そしてどちらもコードレビューでは見えない
種類だった — 一つはレンダーのタイミング競合で、もう一つは正規表現と言語の相互作用だった。
