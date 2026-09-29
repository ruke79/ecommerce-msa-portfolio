# 26. フロントエンド全面レビュー · リファクタリング — 問題点と修正前/後

**日本語** | [한국어](26-frontend-review-and-refactor.ko.md)

> 🕰️ **状態（2026-09-27）— 過去の記録。** フロントのテストは [docs/28](28-frontend-test-suite.md) で導入しました(現在 930 件)。2FA のログイン確認は `/public/verify-2fa` に分離されました。

`frontend/src` 全体(168 ファイル、約 15,500 行)を読み、**実際に動作がおかしいもの**を見つけて直した。
この文書は、何がなぜおかしく、どう直したかを一つずつ記す。

基準を一つ決めてから始める。**好みの問題は直さなかった。** インデント、引用符、命名、
`==` の代わりに `===` といったものは、それ自体がバグでなければ手を付けなかった。代わりに次に該当するものだけを
選んだ。

- 実行すると誤った結果が出るもの
- ユーザーができるべきことを妨げているもの
- エラーを隠して原因を見つけられなくするもの
- 今はたまたま動いているが、条件が少し変わるだけで壊れるもの

検証: `npm run build` 合格(警告はバンドルサイズ関連の 2 件のみ)、バックエンド全体のテスト 209 件合格。

> ⚠️ **限界を先に明かしておく。** このプロジェクトにはフロントエンドのテストが一行もない。そのため以下の
> 修正は**静的解析とビルドだけで**検証しており、ブラウザで実際にクリックして確認したものではない。
> 「決済が正常に記録されるようになった」はコードを読んで下した判断であって、実行で証明した事実ではない。
> 特に §1(PayPal)、§5(2FA)、§8(管理者 API のパス)は実環境で一度ずつ押してみる必要がある。
> フロントエンドのテスト導入は §14 に別途記した。

---

## 重大度別の要約

| # | 場所 | 症状 | 重大度 |
|---|---|---|---|
| 1 | `components/order/Payment.jsx` | PayPal 決済が**注文に反映されない** (capture を 2 回呼び出し) | 🔴 |
| 2 | `pages/user/SignInPage.jsx` | ログイン失敗の理由が絶対に表示されない | 🔴 |
| 3 | `App.jsx`, `components/ProtectedRoute.jsx` | `/admin` が**認証なし・権限なし**で開いている | 🔴 |
| 4 | `components/checkout/Summary.jsx` | 注文失敗時にスピナーが永遠に回る / 重複注文が可能 | 🔴 |
| 5 | `pages/user/SignInPage.jsx` | 2FA を有効にしたユーザーはログイン自体が不可能 | 🔴 |
| 6 | `components/cart/CartPage.jsx` | カートの保存が失敗しても決済画面に進んでしまう | 🔴 |
| 7 | `util/api.js` (15 箇所) | すべての API エラーを握りつぶし、見当違いの場所で落ちる | 🔴 |
| 8 | 管理者画面 5 箇所 + 2 箇所 | API のパスがゲートウェイのルートと異なり、すべて 404 | 🔴 |
| 9 | `redux/store.js` | redux-persist が**何も保存しない** | 🟠 |
| 10 | `hook/hooks.js` | 無限スクロールのフックが TypeScript 構文の誤入力で死んでいる | 🟠 |
| 11 | `pages/user/ButtonInput.jsx` | `disabled` が無視される → 重複送信の防止がすべて無効 | 🟠 |
| 12 | `pages/Product.jsx` | 割引価格の計算式が間違っている (50% 割引が 2% 割引) | 🟠 |
| 13 | `pages/browse.jsx` | フィルターを解除できない / デバウンスが動作しない | 🟠 |
| 14 | `util/Notification.js` | 通知の再接続が永久に止まる、接続が重複する | 🟠 |
| 15 | `pages/Error.jsx` | エラー画面がエラーで落ちて**真っ白な画面**になる | 🟠 |
| 16 | `components/admin/DashboardLayout.jsx` | サイドバーが出ず、画面に `//` が表示される | 🟡 |
| 17 | `pages/header/Header.jsx` | ヘッダーに "Search Desktop" という文字が表示される | 🟡 |
| 18 | `util/imageUtil.js` | Cloudinary の URL に改行が入り、アップロード不可 | 🟡 |
| 19 | `components/checkout/Payment.jsx` | 決済手段のアイコンがすべて 404 | 🟡 |
| 20 | `util/TokenRefresher.js` | 相対パスでの遷移により見当違いの URL に行く | 🟡 |
| 21 | `util/sort_utils.js`, `util/array_utils.js` | `Array.prototype` の汚染 / 偏ったシャッフル | 🟡 |
| 22 | `redux/ChatSlice.js`, `ChatSelectors.js` | 使われていない二つ目のリデューサー、存在しないフィールドのセレクター | 🟡 |
| 23 | `components/delivery`, `components/notification` | 作っておいて**どこにもつながっていない**画面 | 🟠 |
| 24 | `gateway-server/.../application-local.yml` | RewritePath の正規表現のタイプミス (現在はたまたま動作) | 🟡 |

---

## 1. 🔴 PayPal 決済が注文に反映されない

`components/order/Payment.jsx`

最も深刻なバグである。**お金は引き落とされるのに、注文は未決済のまま残る。**

### 修正前

```jsx
async function onApproveHandler(data, actions) {
  dispatch({ type: "PAY_REQUEST" });
  try {
    const details = await actions.order.capture();      // ← ① 1 回目のキャプチャ
    console.log("detail : " + details.id);

    const payment = { orderId: order.id, /* ... details で埋める ... */ };

    dispatch({ type: "PAY_REQUEST" });
    const captureFunc = actions.order.capture().then(   // ← ② 2 回目のキャプチャ(!)
      (details) => {
        payment.paypalOrderId = details.id;
        // ...
        const completePayment = (payment) => {
          let promise = new Promise(async function (resolve, reject) {
            const data = await postRequest('/order-service/api/order/payment/paypal', payment);
            resolve(data);                               // ← ③ ここが唯一のサーバーへの記録
          });
          return promise;
        }
        completePayment(payment).then(res => { /* 注文状態の更新 */ });
      });
    return captureFunc;
  } catch (error) { /* ... */ }
}
```

### 何が問題か

**`actions.order.capture()` を二回呼んでいる。** ① の結果(`details`)で `payment` オブジェクトをすべて埋めて
おきながら、それを使わずに ② で再びキャプチャする。PayPal はすでにキャプチャされた注文を再度キャプチャすると
`ORDER_ALREADY_CAPTURED` で拒否する。

そしてよりによって、**サーバーに決済を記録する呼び出し(③)が ② の `.then` の中に**ある。② が拒否されると
`.then` は実行されない。つまり:

- ① で**実際の決済は成功する** (ユーザーのカードからお金が引き落とされる)
- ② が失敗する
- ③ が実行されず、**バックエンドは決済の事実を知らない**
- 注文は永遠に `UNPAID` のまま残り、決済の Saga も始まらない

ユーザーの立場では「決済したのに注文に反映されない」である。しかも `catch` は ② の失敗を
`PAY_FAIL` として保存するが、**その値を画面に描くコードがないので**、何も表示されなかった。

`new Promise(async (resolve, reject) => ...)` も同じファイルに二回出てくる。このパターンでは async
関数が投げたエラーを誰も受け取れない — `reject` を呼ぶ箇所がないので、Promise が永遠に待機
状態のまま残る。すでに Promise を返す `postRequest` をわざわざ包み直す理由もない。

### 修正後

```jsx
async function onApproveHandler(data, actions) {
  dispatch({ type: "PAY_REQUEST" });
  try {
    const details = await actions.order.capture();       // ちょうど一回

    const payment = { orderId: order.id, paypalOrderId: details.id, /* ... */ };

    const res = await postRequest("/order-service/api/order/payment/paypal", payment);

    // サーバーに記録された後でのみ、画面を決済完了に変える。
    setOrder({ ...order, orderStatus: "PAID" });
    reduxDispatch(emptyCart());
    dispatch({ type: "PAY_SUCCESS", payload: res.data });
  } catch (error) {
    dispatch({ type: "PAY_FAIL", payload:
      "결제는 처리되었지만 주문에 반영하지 못했습니다. 고객센터로 문의해 주세요. " + ... });
  }
}
```

順序も変えた。以前はサーバーへの記録と画面の更新が絡み合っていたが、**サーバーに記録された後でのみ**
画面を決済完了に変える。逆にすると、記録が失敗したときに画面だけが決済完了になり、ユーザーは
再度決済しない — 未収金がそのまま残る。

失敗メッセージの文言にも気を配った。この地点の失敗は「決済失敗」ではなく**「決済はできたが記録が
されていない」**である。ユーザーが再度決済すると二重決済になるので、リトライを勧めずカスタマーサポートへ
案内する。

一緒に直したもの:

- `reducer` に `default` 分岐がなかった → 未知のアクションが来ると `undefined` を返し、次の
  レンダリングで `state.loading` へのアクセスが爆発する。ステートマシンは常に状態を返さなければならない。
- `PAY_FAIL` が保持するエラーメッセージを実際に画面に描く。
- `onCancelHandler` が `order.paymentStatus = 'CANCELED'` で **props オブジェクトを直接変更**して
  いた。props を変更すると、React は参照が同じなので変わったことに気づかない。

---

## 2. 🔴 ログイン失敗の理由が絶対に表示されない

`pages/user/SignInPage.jsx`

### 修正前

```jsx
const signin = async (user) => {
    try {
        const data = await api.post("/user-service/api/auth/public/signin", user);
        return data;
    } catch (err) {
        console.log("erorr >>>", err.response?.data.message);   // ← 握りつぶす
    }
}

const { mutate, isPending } = useMutation({
    mutationFn: signin,
    onSuccess: (response) => {
        try {
            const access = response.headers['access'];          // ← undefined.headers
            // ...
        } catch (error) {
            toast.error("Login Failed. Please retry.");         // ← ここに来る
        }
    },
    onError: (error) => { /* 絶対に実行されない */ }
});
```

### 何が問題か

`signin` の `catch` がログを出すだけで何も投げない。すると関数は `undefined` を
返し、`useMutation` はそれを**成功**として受け取る。そのため:

- パスワードが間違っていても `onError` は**一度も実行されない**
- `onSuccess` の中で `response.headers` へのアクセスが爆発する
- 内側の `catch` がそれを受けて "Login Failed. Please retry." という大雑把なメッセージを出す
- サーバーが知らせた本当の理由(「存在しないアカウント」、「パスワードを 5 回間違えてロック」)はコンソールにしか残らない

ユーザーはなぜログインできないのか分からない。

### 修正後

```jsx
// エラーを捕まえない — useMutation の onError が受け取れるようそのまま上に投げる。
const signin = (user) => api.post("/user-service/api/auth/public/signin", user);

const { mutate, isPending } = useMutation({
    mutationFn: signin,
    throwOnError: false,   // ログイン失敗はこの画面で処理する
    onSuccess: (response) => { /* ... */ },
    onError: (error) => {
        toast.error(error?.response?.data?.message || "Login Failed.");
        setAuthenticationStatus(STATUS.FAILED);
    }
});
```

`throwOnError: false` を明示したのには理由がある。`queryClient` のデフォルト値が `throwOnError: true`
なので、そのままにするとログイン失敗が ErrorBoundary まで上がり、**ログイン画面自体が消える。**
ログイン失敗は例外的な状況ではなく、この画面が処理すべき正常なフローである。

### 併せて: `queryKey` のタイプミス

```jsx
- queryClient.invalidateQueries({ querykey: [LOGIN_QUERY_KEY] });   // 小文字の k
+ queryClient.invalidateQueries({ queryKey: [LOGIN_QUERY_KEY] });
```

`querykey` は react-query が知らないキーである。フィルターオブジェクトに未知のキーしか入っていないと、**フィルターが
ないものとして扱われ、すべてのクエリが無効化される。** ログイン一回でカート・注文・クーポン・商品一覧が
すべて再リクエストされていた。`RegisterPage.jsx` にも同じタイプミスがあったので一緒に直した。

### 併せて: 会員登録はもっと悪かった — **失敗が成功に見える**

`RegisterPage.jsx` の `signup` にもまったく同じ握りつぶしパターンがあったが、結果はもっと悪い。

```jsx
// 修正前
const signup = async (userData) => {
    try {
        console.log(userData);                       // ← 平文のパスワードをコンソールに出力する
        const data = await postRequest("/user-service/api/auth/public/register", userData);
        return data;
    } catch (err) {
        console.log(err.response?.data.message);     // ← 握りつぶす
    }
}
```

登録が失敗しても `onSuccess` が実行されるので:

- **「인증 메일을 보냈습니다」**のトーストが表示され
- ログイン画面に**遷移する**

ユーザーは届かないメールを待つ。「이미 가입된 이메일입니다」のようなサーバーメッセージはコンソールにしか
残らない。ログイン失敗より悪い理由は、ユーザーが**何かがおかしいという事実そのものを知らない**点である。

```jsx
// 修正後
const signup = (userData) =>
    postRequest("/user-service/api/auth/public/register", userData);
```

`onError` も直した — 以前はサーバーメッセージを state にだけ入れ、トーストには固定文言
`"Failed to register user. Please retry."` を出していた。今はサーバーが知らせた理由をそのまま
表示する。そして `console.log(userData)` を消した: **平文のパスワードがブラウザのコンソールに
出力されていた。**

---

## 3. 🔴 `/admin` が認証なし・権限なしで開いている

`App.jsx`, `components/ProtectedRoute.jsx`

### 修正前

```jsx
{
  path: '/admin',
  element: <Suspense fallback={<div>Loading Admin...</div>}><DashboardLayout /></Suspense>,
  children: [ /* category, product, allproducts */ ]
}
```

他のルートはすべて `<ProtectedRoute>` で囲まれているのに、**`/admin` だけが抜けている。** しかも
`ProtectedRoute` 自体にロールチェックがなかった — ログインさえすれば誰でも通過する。

結果として二段階で穴が開いていた。

1. ログインしていない人がアドレスバーに `/admin/product` と打つと、管理者画面がそのまま描かれる
2. 一般会員でログインすると、管理者画面を**普通に**見られる

ファイルの中にはロールチェックを行う以前のバージョンが**コメントとして**残っていた(`adminPage` オプション)。ある
時点で無効化された後、復活しなかったものと思われる。

### 修正後

```jsx
const ProtectedRoute = ({ children, adminOnly = false }) => {
  const { status, isAuthenticated, user } = useAuthContext();

  if (status === STATUS.PENDING) return <DotLoaderSpinner loading={true} />;
  if (!isAuthenticated) return <Navigate to="/signin" replace />;
  if (adminOnly && !hasAdminRole(user)) return <Navigate to="/" replace />;

  return children;
};

// JWT の roles はデプロイによって "ADMIN" だったり "ROLE_ADMIN" だったりする。片方だけ見ると
// 管理者が黙って自分の画面から追い出され、画面には理由がまったく現れない。
function hasAdminRole(user) {
  const roles = user?.roles;
  if (!Array.isArray(roles)) return false;
  return roles.some((r) => ["ADMIN", "ROLE_ADMIN"].includes(String(r).trim().toUpperCase()));
}
```

```jsx
{
  path: '/admin',
  element: (
    <ProtectedRoute adminOnly>
      <Suspense fallback={<div>Loading Admin...</div>}><DashboardLayout /></Suspense>
    </ProtectedRoute>
  ),
  children: [ /* ... */ ]
}
```

**はっきりさせておくこと:** これは UI のガードにすぎず、セキュリティ境界ではない。ブラウザで動くコードはユーザーが
いくらでも変えられるので、本当の権限チェックはゲートウェイと各サービスが行わなければならない。それでも
必要な理由は、権限のないユーザーに管理者画面の構造・メニュー・エンドポイントを露出させない
ためである。**ゲートウェイ側の `/admin` パスの権限チェックはこの文書の範囲外であり、別途点検しなければ
ならない。**

`replace` も追加した。ないとリダイレクトが履歴に積まれ、戻るボタンを押したときに再びブロックされた
画面に行ってはまた弾かれるループになる。

---

## 4. 🔴 注文失敗時にスピナーが永遠に回る · 重複注文

`components/checkout/Summary.jsx`

### 修正前

```jsx
const placeOrderHandler = async () => {
    try {
        setLoading(true);
        // ... 検証 ...
        const {data} = await postRequest("/order-service/api/order/create", { /* ... */ });
        navigate(`/order/${data.orderId}`);
        //setLoading(false);              // ← コメントアウト
    } catch (error) {
        //setLoading(false);              // ← コメントアウト
        setOrder_Error(error.response?.data.message);
    }
};

// ...
<button onClick={() => placeOrderHandler()}>Place Order</button>   // ← ガードなし
```

### 何が問題か

**三つが重なっている。**

1. `setLoading(false)` が成功・失敗の**両方の経路でコメントアウト**されている。注文作成が失敗すると
   全画面スピナーが永遠に表示され続け、その下にレンダリングされるエラーメッセージはスピナーに隠れて見えも
   しない。ユーザーにできるのはリロードだけである。
2. **重複送信のガードがない。** "Place Order" を二回押すと注文が二件作成される。決済まで
   二回続きうる経路である。
3. `error.response?.data.message` — オプショナルチェーンが `response` までしか保護していない。サーバーが本文
   なしで 500 を返すと `data` が undefined なので、`.message` で **catch の中で再び TypeError** が
   発生し、元のエラーはその TypeError に隠される。

### 修正後

```jsx
const [placing, setPlacing] = useState(false);

const placeOrderHandler = async () => {
    if (placing) return;                       // 重複送信の防止

    if (paymentMethod === "") { setOrder_Error("please choose a payment method."); return; }
    if (!selectedAddress)     { setOrder_Error("please choose a shipping address."); return; }

    setPlacing(true); setLoading(true); setOrder_Error("");
    try {
        const { data } = await postRequest("/order-service/api/order/create", { /* ... */ });
        navigate(`/order/${data.orderId}`);
    } catch (error) {
        setOrder_Error(error?.response?.data?.message ?? "주문을 생성하지 못했습니다. 다시 시도해 주세요.");
    } finally {
        setLoading(false);
        setPlacing(false);
    }
};
```

```jsx
<button type="button" disabled={placing} className="... disabled:opacity-50 ...">
    {placing ? "주문 처리 중…" : "Place Order"}
</button>
```

`finally` を使ったのが肝心である。成功すれば `navigate` で画面を離れるが、離れられないすべての
経路でスピナーは必ず消えなければならない。

### 併せて: レンダリング中に API を呼び出していた

```jsx
// 修正前 — コンポーネント関数の本体(レンダリング中!)にそのままあった
if (coupon === "" && coupons && isSuccess) {
    if (coupons?.length > 0) {
        setCoupon(coupons[0]);
        applyCouponHandler(coupons[0]);      // ← レンダリング中に非同期 API 呼び出し
    }
}
```

レンダリングには副作用があってはならない。React はレンダリングを何度も実行しうるので(StrictMode では
開発中は常に二回)、そのたびにクーポン適用のリクエストが重複して送られる。effect に移した。

```jsx
useEffect(() => {
    if (isSuccess && coupon === "" && coupons?.length > 0) {
        setCoupon(coupons[0]);
        applyCouponHandler(coupons[0]);
    }
}, [isSuccess, coupons]);
```

### 併せて: フックの順序違反を作りかけた

このファイルには `if (isPending) return <div>Loading...</div>` がフックの**途中に**あった。元の
コードではその下にフックがなかったのでたまたま問題なかったが、私が `useEffect`/`useState` を
追加したことで、早期 return がフックより前に来てしまった。そうなるとレンダリングごとに呼ばれるフックの数が変わり、
`Rendered fewer hooks than expected` でコンポーネントが壊れる。早期 return を**すべてのフックの後ろに**
移し、その理由をコメントで残した。

---

## 5. 🔴 2FA を有効にしたユーザーはログインできない

`pages/user/SignInPage.jsx`

### 修正前

```jsx
const [step, setStep] = useState(1);
// ...
if (decodedToken.is2faEnabled) {
    setStep(2); // Move to 2FA verification step
} else {
    handleSuccessfulLogin(response, decodedToken);
}
```

`setStep(2)` を呼んでいるのに、**`step` の値を読む箇所がファイル全体に一つもない。** 画面はそのまま
ログインフォームである。2FA を有効にしたユーザーは、パスワードを正しく入れても何の反応もないように見え、
ログインする方法がない。

今回の作業で SMS の 2 段階認証を追加したので、なおさらブロックされていてはならない経路である。

### 修正後

トークンを **AuthContext に入れずにしばらく保持しておき**、コードの確認が成功して初めてログインを確定する。
確認前に `login()` を呼ぶと、2 段階認証は何の意味もない。

```jsx
const [pendingAuth, setPendingAuth] = useState(null);

onSuccess: (response) => {
    const access = response.headers['access'];
    const decodedToken = jwtDecode(access);

    if (decodedToken.is2faEnabled) {
        setPendingAuth({ response, decodedToken, access });   // まだログインではない
        setStep(2);
    } else {
        handleSuccessfulLogin(response, decodedToken);
    }
}

const verify2faHandler = async () => {
    // サーバーの /verify-2fa は認証済みユーザーを要求する。発行されたばかりだがまだ
    // AuthContext に入れていないトークンを、このリクエストにだけ直接載せて送る。
    await api.post(`/user-service/api/auth/verify-2fa?code=${encodeURIComponent(code2fa)}`,
        null, { headers: { Authorization: `Bearer ${pendingAuth.access}` } });
    handleSuccessfulLogin(pendingAuth.response, pendingAuth.decodedToken);   // ここで確定
};
```

画面も作った。6 桁の数字だけを受け付け(`replace(/\D/g, "")`)、`autoComplete="one-time-code"` で
iOS/Android の SMS 自動入力を受け、Enter で送信され、「다른 계정으로 로그인」を押すと
保持していたトークンを**捨てる** — 持ち続けると認証を通過していないトークンが画面に残る。

> **残作業:** バックエンドの `/verify-2fa` は TOTP(Google Authenticator)ベースで、成功時に
> `enable2FA(userId)` を呼び出す。登録用のエンドポイントをログイン検証に再利用する構造なので、
> ログイン経路ではすでに有効なフラグを再度有効にする無害な動作だが、意味上は分離するのが
> 正しい。そして今回追加した **SMS 認証とこの TOTP 2FA はまだ別物**である — SMS で 2FA を
> 行うには、バックエンドに SMS ベースの検証経路をもう一つ設けなければならない。バックエンドの変更が必要なので、今回の
> 範囲では行わなかった。

---

## 6. 🔴 カートの保存が失敗しても決済画面に進んでしまう

`components/cart/CartPage.jsx`

### 修正前

```jsx
try {
    const res = await saveCart(selected, user.email);
    dispatch(commitCart(res.cartId));
} catch(err) {
    console.error(err.response?.data.message);
}
finally {
    navigate("/checkout");        // ← 失敗しても実行される
}
```

`finally` は成功でも失敗でも実行される。保存が失敗しても決済画面に進むが、その画面は
**サーバーに保存されたカートを読み直して**描画する。そのためユーザーは、今入れた商品がないか、
以前のカートが表示された状態で決済を進めることになる。

### 修正後

```jsx
const [saveError, setSaveError] = useState("");
const [saving, setSaving] = useState(false);

const saveCartToDbHandler = async (e) => {
    e.preventDefault();
    if (saving) return;                        // 二回押すと二回コミットされる

    setSaving(true); setSaveError("");
    try {
        const res = await saveCart(selected, user.email);
        dispatch(commitCart(res.cartId));
        setPrevSelectged(selected);
        navigate("/checkout");                 // 成功したときだけ遷移
    } catch (err) {
        setSaveError(err?.response?.data?.message ?? "장바구니를 저장하지 못했습니다. 다시 시도해 주세요.");
    } finally {
        setSaving(false);
    }
};
```

エラーをコンソールではなく**画面に**表示する。併せて `enable` という state と、それだけを更新していた
`useEffect` を消した — どこからも読まれない値で、依存配列が `[]` なのでカートが変わっても
更新されず、仮に読んでいたとしても誤った値だった。

---

## 7. 🔴 すべての API エラーを握りつぶす

`util/api.js` — 同じパターンが **15 箇所**にコピーされていた。

### 修正前

```jsx
export const getOrders = async (filter, userEmail) => {
  try {
    const { data } = await getRequest("/order-service/api/order/orders", { params: {...} });
    return data;
  } catch (error) {
    console.log("erorr >>>", error.response?.data.message);
  }
}
```

### 何が問題か

**三つが同時に間違っている。**

**① エラーを握りつぶす。** `catch` が何も投げずに終わるので、関数は `undefined` を返す。
呼び出し側はそれを成功として受け取り、`orderData.products.map(...)` をしようとして
`Cannot read properties of undefined` で落ちる。実際の原因(401、500、タイムアウト)はコンソールにしか
残らず、画面にはまったく関係のないエラーが表示される。デバッグが最も難しい形の失敗である。

**② React Query を無力化する。** `queryClient` は `throwOnError: true` に設定されており、クエリの
失敗を ErrorBoundary に上げるようになっている。ところがここでエラーを食べてしまうと、react-query から見れば
**常に成功**(値が undefined の成功)である。エラー UI もリトライも動作できない。つまり
プロジェクトが用意しておいたエラー処理の仕組み全体が、この 15 行のせいで無効だった。

**③ catch の中で再び爆発する。** `error.response?.data.message` のオプショナルチェーンは `response`
までしか保護しない。サーバーが本文なしで 500 を返すと `data` が undefined なので、`.message` へのアクセスで
TypeError が発生し、元のエラーはその TypeError に隠される。

### 修正後

```jsx
export const getOrders = async (filter, userEmail) => {
  const { data } = await getRequest("/order-service/api/order/orders", {
    params: { filter, email: userEmail },
  });
  return data;
};
```

**捕まえない。** エラーは呼び出し側(useQuery/useMutation → ErrorBoundary)にそのまま上がる。
画面にどう見せるかは画面が決めることであって、データ層が決めることではない。

この変更でエラーが実際に流れ始めるので、エラーが画面全体を吹き飛ばしてはならない箇所は
個別に捕まえた — クーポン適用の失敗で決済画面が丸ごと消えてはならないので、
`Summary.applyCouponHandler` に `try/catch` を入れる、といった具合である。

一緒に直したもの:

```jsx
// processPayment: 状態を変える呼び出しなのに GET で、リクエスト本文を GET の config の位置に
// 渡していたので、サーバーには何も伝わっていなかった。
- await getRequest("/order-service/api/order/payment/process", request);
+ await postRequest("/order-service/api/order/payment/process", request);
```

`postRequest`/`putRequest`/`patchRequest` に 4 番目の引数 `requestConfig` を追加した。リクエスト
単位でヘッダーを載せられれば、グローバルなデフォルト値に触れずに済むからである(§ 下記)。

---

## 8. 🔴 API のパスがゲートウェイのルートと合っていない

ゲートウェイは `/{service}-service/**` でしかルーティングしない。`/admin/**`, `/auth/**`, `/user/**` で
始まるパスには**ルート自体がない。** それなのにフロントエンドの 7 箇所がそう呼んでいた。

| ファイル | 修正前 | 修正後 |
|---|---|---|
| `pages/admin/Index.jsx` | `/admin/product/products` | `/catalog-service/api/product/products` |
| `pages/admin/Index.jsx` | `/admin/product/subcategories` | `/catalog-service/api/category/product/subcategories` |
| `components/admin/CreateSubCategory.jsx` | `/admin/subcategory` | `/catalog-service/api/category/subcategory` |
| `pages/user/ForgotPage.jsx` | `/auth/public/forgot-password` | `/user-service/api/auth/public/forgot-password` |
| `pages/profile/security.jsx` | `/user/profile/update-password` | `/user-service/api/user/profile/update-password` |

`CreateCategory.jsx` はプレフィックスは合っていたが、**コントローラーのパスが間違っていた。** バックエンドの
`CategoryController` は `@RequestMapping("/api/category")` なのに、フロントは `/api/product/...` で
呼んでいた。

| 修正前 | 修正後 |
|---|---|
| `/catalog-service/api/product/categories` | `/catalog-service/api/category/categories` |
| `/catalog-service/api/product/subcategories` | `/catalog-service/api/category/product/allsubcategories` |
| `/catalog-service/api/product/category` | `/catalog-service/api/category/category` |
| `/catalog-service/api/product/subcategory` | `/catalog-service/api/category/subcategory` |

つまり**パスワード変更、パスワード再設定、管理者のカテゴリ管理がすべて 404** だった。§7 のエラー握りつぶしと
組み合わさって、ユーザーには「ボタンを押しても何も起きない」ように見えていた。

> この対応表はバックエンドのコントローラーの `@RequestMapping`/`@GetMapping` を直接読んで合わせたものである。
> ただし実際の呼び出しで確認できてはいないので、管理者画面は一度押してみながら検証する必要がある。

---

## 9. 🟠 redux-persist が何も保存しない

`redux/store.js`

### 修正前

```jsx
const rootReducer = combineReducers({
    //auth: authReducer,          // ← コメントアウトされている
    menu: menuReducer,
    dialog: dialogReducer,
    cart: cartReducer,
    chat: chatReducer,
});

const persistConfig = {
    key: "root",
    storage,
    whitelist: ['auth'],          // ← 存在しないリデューサー
    blacklist: ['token'],         // ← 存在しないリデューサー
};
```

`whitelist` は**存在する最上位のリデューサーキー**にしか意味がない。`auth` は上でコメントアウトされていて
rootReducer にないので、保存対象が **0 個**である。`App.jsx` が `PersistGate` でアプリを包み、
`persistStore` まで呼んでいるのに、実際には何もしていない。

体感できる症状: **カートがリロードのたびに空になる。**

`blacklist: ['token']` も同様に `token` というリデューサーがないので何もしていなかった。
「トークンは保存しない」という安心感を与えるコメントだけを残して実際の保護はしない、あるせいでかえって危険な
設定だった。

その他:

- `getDefaultMiddleware` を `@reduxjs/toolkit` から named import していた。RTK 2.x には
  そのようなエクスポートはない(このプロジェクトは 2.2.7)。コールバックのパラメーターに隠れて爆発はしなかったが、死んだ
  import である。
- `serializableCheck: false` でチェックを**丸ごと**無効にしていた。そうすると redux-persist 内部のアクションだけで
  なく、自分たちのコードがストアに入れる File・Date のような値もチェックから外れる。

### 修正後

```jsx
const persistConfig = {
    key: "root",
    storage,
    whitelist: ["cart"],
};

export const store = configureStore({
    reducer: persistedReducer,
    devTools: process.env.REACT_APP_PROFILE !== "production",
    middleware: (getDefaultMiddleware) =>
        getDefaultMiddleware({
            // チェックは有効にしたまま、redux-persist のアクションだけを例外にする(公式推奨の方法)。
            serializableCheck: {
                ignoredActions: [FLUSH, REHYDRATE, PAUSE, PERSIST, PURGE, REGISTER],
            },
        }),
});
```

**`cart` だけを**保存する。入れておいた商品がリロードで消えるのは、ユーザーが最も大きく体感する
損失である。逆に保存してはならないもの:

- `chat` — サーバーが真実である。古い会話リストを復元すると実際とずれる。
- `menu`/`dialog` — 純粋な UI 状態である。開いていたモーダルがリロード後に復活したらおかしい。
- **認証トークン** — そもそもこのストアにない。XSS による窃取を防ぐためにメモリ(AuthContext)にだけ置くのが
  意図された設計である(`util/tokenUtil.js`)。ここで whitelist に `auth` を入れるのはその設計を
  元に戻すことなので、**してはならない。** コメントで釘を刺しておいた。

---

## 10. 🟠 無限スクロールのフックが TypeScript 構文のせいで死んでいる

`hook/hooks.js`

### 修正前

```js
const ref = useRef < HTMLDivElement > (null);
```

`.js` ファイルである。TypeScript のジェネリクス構文をそのまま貼り付けたものだが、JS のパーサーはこれを
ジェネリクスではなく**比較演算二回**として読む:

```js
(useRef < HTMLDivElement) > (null)
```

そのため `ref` には DOM 参照ではなく**ブール値 `false`** が入る。結果として:

- `ref.current` は常に `undefined` → `IntersectionObserver` が作られない
- この ref を実際の要素に付けても、React が ref として認識できない

無限スクロールが**静かに**死んでいた。構文エラーではなく有効な JS なので、ビルドも通る。

### 修正後

```js
export function useInfiniteScroll({ hasNextPage, fetchNextPage }) {
  const ref = useRef(null);

  const handleIntersect = useCallback(([entry]) => {
    if (entry?.isIntersecting && hasNextPage) fetchNextPage();
  }, [fetchNextPage, hasNextPage]);

  useEffect(() => {
    const target = ref.current;
    if (!target) return undefined;

    const observer = new IntersectionObserver(handleIntersect, { threshold: 0.6 });
    observer.observe(target);
    return () => observer.disconnect();
  }, [handleIntersect]);

  return ref;
}
```

依存配列から `ref` も外した。`useRef` が返すオブジェクトは常に同じ参照なので依存としての意味が
なく、あると「ref が変われば付け直す」という誤った印象を与える。クリーンアップで `ref.current`
を読み直さず、effect 実行時点の `target` をキャプチャするようにしたのも意図的である — クリーンアップ時点の
`ref.current` はすでに別の要素かもしれない。

---

## 11. 🟠 `disabled` が無視される — 重複送信の防止がすべて無効

`pages/user/ButtonInput.jsx`

### 修正前

```jsx
const ButtonInput = ({type, text, ...props}) => {
    return (
        <button type={type} className="...">
            {text}
            <ChevronRightIcon className="h-3 ml-1" />
        </button>
    );
};
```

`...props` を受け取っておきながら **button に展開していない。** そのため呼び出し側の `disabled={isPending}` は
何の効果もない。`SignInPage.jsx` にはこんなコメントまで付いていた:

```jsx
<ButtonInput type="submit" text="Sign in" disabled={isPending}  // isPending の状態を使ってボタンを無効化します。
/>
```

コメントは意図を語っているが、実際には無効化されない。ログイン・会員登録のボタンを二回押すと
リクエストが二回送られる。`onClick` のような他のハンドラーもすべて黙って捨てられていた。

### 修正後

```jsx
const ButtonInput = ({ type, text, className = "", ...props }) => {
    return (
        <button
            type={type}
            className={`... disabled:opacity-50 disabled:cursor-not-allowed ${className}`}
            {...props}
        >
            {text}
            <ChevronRightIcon className="h-3 ml-1" />
        </button>
    );
};
```

`disabled:` のスタイルも入れた。無効化されたのに見た目が変わらなければ、ユーザーは押し続ける。

---

## 12. 🟠 割引価格の計算が間違っている

`pages/Product.jsx`

### 修正前

```jsx
price:
    subProduct.discount > 0
        ? (subProduct.sizes[size].price - subProduct.sizes[size].price / subProduct.discount).toFixed(2)
        : subProduct.sizes[size].price,
```

`discount` は**パーセント**である(シードデータが 5~70 を入れる)。ところが式は
`정가 - 정가 / 할인율` である。これは何の意味もない計算である。

10,000 ウォンの商品に 50% 割引なら 5,000 ウォンになるべきなのに:

```
10000 - 10000/50 = 10000 - 200 = 9800   →  実際には 2% 割引
```

しかも**割引率が大きいほど、割引幅がかえって小さくなる。** 70% 割引なら `10000 - 143 = 9857`、
つまり 1.4% 割引である。逆さまの計算である。

### もう一つ: 条件が常に偽

```jsx
priceRange:
    prices.discount > 1        // ← prices は配列である
        ? `From ${...할인 적용...}`
        : `From ${prices[0]} to ${prices[prices.length - 1]}$`,
```

`prices` は `subProduct.sizes.map(s => s.price)` で作った**配列**である。配列に `.discount`
プロパティはないので常に `undefined` で、条件は常に偽である。割引商品なのに価格帯は
常に定価でしか表示されなかった。

### 修正後

```jsx
/** 割引価格を計算する。`discount` はパーセントである。 */
function discounted(price, discountPercent) {
    const p = Number(price) || 0;
    const d = Number(discountPercent) || 0;
    if (d <= 0) return p;
    return p * (1 - Math.min(d, 100) / 100);
}
```

```jsx
priceRange:
    subProduct.discount > 0
        ? `From ${discounted(lo, subProduct.discount).toFixed(2)}$ to ${discounted(hi, subProduct.discount).toFixed(2)}$`
        : `From ${lo}$ to ${hi}$`,
price: subProduct.discount > 0
    ? discounted(selectedSize.price, subProduct.discount).toFixed(2)
    : selectedSize.price,
```

`Math.min(d, 100)` は、データのエラーで 100 を超える割引率が入ってきても価格がマイナスにならないように
する。

### 同じファイルで一緒に直したもの

**インプレースのソートが元データを壊していた。**

```jsx
- const prices = subProduct.sizes.map((s) => s.price).sort((a, b) => a - b);
+ const prices = subProduct.sizes.map((s) => s.price).slice().sort((a, b) => a - b);
```

`.map()` は新しい配列を返すのでこの行自体は安全だが、すぐ下の `allSizes` の
`.flat().sort(...)` は `product.skus` の中の配列に触れうる。両方ともコピーしてからソートするように
変えた。

**クエリ文字列が文字列のままインデックスに使われていた。**

```jsx
- const style = searchParams.get("style") || 0;
+ const style = Number(searchParams.get("style")) || 0;
```

**エラーを握りつぶして `null` を返していた。**

```jsx
} catch(err) {
    console.error(err.response?.data.message)
    return null;                     // ← コンポーネントが product.name で落ちる
}
```

ローダーが `null` を返すと `SingleProduct` が `product.name` で落ちる。そのとき画面に出る
メッセージは本当の原因(404・500・ネットワーク)と何の関係もない。今はそのまま投げてルーターの
`errorElement` に処理させる。

**死んだ重複関数 70 行を消した。** ファイルの下の方に `loaderProduct` という、`loader` とほぼ
同じ内容の関数があった。呼び出し側はコメントアウトされていて誰も呼んでいない。同じバグが二
組あると、直すときに片方だけ直すことになる。

---

## 13. 🟠 フィルターが解除されない · デバウンスが動作しない

`pages/browse.jsx`

### ① トグルが値で照会している

```jsx
// 修正前
function toggleParam(name, param) {
    if (param && !searchParams.get(param) && !isEmptyObject(param)) {
        searchParams.set(name, param);
    }
    else if (param) {
        searchParams.delete(name);
    }
}
```

`searchParams.get(param)` — パラメーターの**名前**ではなく**値**で照会している。
`toggleParam('category', 'shoes')` は `searchParams.get('shoes')` を見るが、そのようなパラメーターは
ないので常に `null` である。したがって最初の条件は常に真で、**同じフィルターをもう一度押しても
解除されない。**

```jsx
// 修正後
function toggleParam(name, param) {
    if (!param || isEmptyObject(param)) {
        searchParams.delete(name);        // 空の値 = 「このフィルターを外してほしい」
        return;
    }
    if (searchParams.get(name) === String(param)) {
        searchParams.delete(name);        // 同じ値をもう一度押すと解除
    } else {
        searchParams.set(name, param);
    }
}
```

`shipping` と `rating` は同じロジックが**コピーされて**別々に書かれており、同じバグを持っていた。
`toggleParam` の呼び出しにまとめた。

### ② デバウンスが毎回新しく作られる

```jsx
// 修正前
const priceHandler = (price, type, delay) => {
    // ...
    let filterPrice = debounce((price) => filter(price), delay);   // ← 呼び出しごとに新規生成
    filterPrice({ price: newPrice });
};
```

デバウンスは**前のタイマーを覚えているクロージャ**に依存する。呼び出すたびに新しいクロージャを作ると、各
呼び出しが自分だけのタイマーを持ち、キャンセルすべき前のタイマーがない。**デバウンスがまったく動作しない** —
価格をタイピングすると、文字数の分だけ検索リクエストが送られる。

```jsx
// 修正後
const priceTimer = useRef(null);
useEffect(() => () => clearTimeout(priceTimer.current), []);   // アンマウント時にクリーンアップ

const priceHandler = (price, type, delay = 300) => {
    // ...
    clearTimeout(priceTimer.current);
    priceTimer.current = setTimeout(() => filter({ price: newPrice }), delay);
};
```

タイマー id を ref に置き、レンダリングが変わっても**同じタイマー**をキャンセルする。アンマウント時のクリーンアップも
入れた — ないと画面を離れた後に `setSearchParams` が呼ばれる。

---

## 14. 🟠 通知の再接続が永久に止まる

`util/Notification.js` — しかもこのコンポーネントは **`App.jsx` でコメントアウトされていた。**

### 修正前

```jsx
const fetchSSE = useCallback(() => {
    if (reconnectAttempts.current > 5) {
      console.log("Max reconnection attempts reached.");
      return;                                    // ← ここで永遠に終わり
    }
    // ...
    eventSource.current = new EventSoruce(...);  // ← タイプミス(EventSoruce)、前の接続を閉じない
    // ...
    eventSource.current.onerror = (err) => {
        const delay = Math.min(1000 * 2 ** reconnectAttempts.current, 30000);
        reconnectAttempts.current += 1;
        timerId.current = setTimeout(fetchSSE, delay);
    };
}, [token, isPending, logout, isAuthenticated]);
```

**問題 1 — 再接続が永久に止まる。** `reconnectAttempts` は成功(`onopen`)したときにしか 0 に
戻らない。サーバーのデプロイのように数分間切れる状況で 5 回をすぐに使い切ると、**その後サーバーが戻っても
二度と接続を試みない。** ユーザーがページをリロードするまで通知が死んでいる。

**問題 2 — 接続が重複する。** 新しい接続を作る前に前の `EventSource` を閉じない。
`fetchSSE` の依存に `token` があるので、`TokenRefresher` が期限切れの 10 秒前にトークンを更新する
たびに effect が再実行され、**接続が一つずつ増える。** 長く開いたままのタブでは同じ通知が何
度も届く。

**問題 3 — クリーンアップが再接続を止められない。** アンマウント時にタイマーは消すが、すでに実行中だった
`onerror` がその後で新しいタイマーを設定すると、そのまま生き残る。

**問題 4 — すべての通知本文をコンソールに出力する。** 通知には注文番号のような個人情報が入りうる。

### 修正後

```jsx
const stopped = useRef(false);

const connect = useCallback(() => {
    if (stopped.current || !isAuthenticated || !token || isPending) return;

    function scheduleReconnect() {
        if (stopped.current) return;                  // クリーンアップ後は再接続しない

        const attempt = reconnectAttempts.current;
        // 上限を超えても諦めない — 5 分ごとに静かに再試行する。
        const delay = attempt >= MAX_RECONNECT_ATTEMPTS
            ? 5 * 60 * 1000
            : Math.min(1000 * 2 ** attempt, 30000);

        reconnectAttempts.current = attempt + 1;
        clearTimeout(timerId.current);
        timerId.current = setTimeout(connect, delay);
    }

    eventSource.current?.close();                     // 新しく開く前に必ず閉じる
    // ...
}, [token, isAuthenticated, isPending, logout]);

useEffect(() => {
    stopped.current = false;
    connect();
    return () => {
        stopped.current = true;
        clearTimeout(timerId.current);
        eventSource.current?.close();
        eventSource.current = null;
    };
}, [connect]);
```

肝心なのは**上限に達しても諦めないこと**である。指数バックオフの目的はサーバー負荷を減らす
ことであって、永久に諦めることではない。5 分間隔まで引き下がりつつ、試行は続ける。

そして `App.jsx` でコメントを外した。このコンポーネントがオフになっている間は、**別の端末で
ログインしてセッションが押し出されても、このタブはログイン状態のまま残っていた** — サーバーが送る `Logout`
イベントを誰も聞いていないからである。

---

## 15. 🟠 エラー画面がエラーで落ちる

`pages/Error.jsx`

```jsx
// 修正前
const error = useRouteError();

console.log(error.code);            // ← error が undefined ならここで落ちる
console.log(error.status);

if (error.status === 500) {
  message = error.data.message;     // ← 本文のない 500 ならここで落ちる
}

useEffect(() => {
  if(error.code === 'ERR_NETWORK') {
    navigate('error_server', {replace:true});   // ← 相対パス
  }
}, [])

const handler = () => { toast("Worng Error"); }   // ← "Try again" がやること
```

**エラー画面自体がエラーで落ちると、ユーザーは完全に真っ白な画面を見る。** ところがこのファイルには
防御がまったくない。ルーターは `throw new Response(...)` や文字列もエラーとして受け取ることがあり、そのとき
`error` には `code` がない。

`navigate('error_server')` は先頭にスラッシュがないので**相対パス**である。`/order/12` でエラーが起きると
`/order/error_server` に行き、そのようなルートはないので再びエラーになる。

"Try again" ボタンは `toast("Worng Error")` を出すだけである(タイプミス込み)。ユーザーにできることが
何もない。

```jsx
// 修正後
const status = error?.status;
const code = error?.code;
const isNetworkError = code === 'ERR_NETWORK';

if (status === 500) {
  message = error?.data?.message ?? '서버에서 오류가 발생했습니다.';
} else if (status === 404) { /* ... */ }
else if (error?.message) { message = error.message; }

useEffect(() => {
  if (isNetworkError) navigate('/error_server', { replace: true });
}, [isNetworkError, navigate]);

// 実際に再試行する。
<button onClick={() => navigate(0)}>다시 시도</button>
<button onClick={() => navigate('/', { replace: true })}>홈으로</button>
```

`navigate(0)` は現在のルートを再ロードする。ルーターのエラーはたいていローダーの失敗なので、再リクエストで
解消することが多い。

---

## 16~17. 🟡 JSX 内の `//` と裸のテキスト

JSX の中で `//` はコメントではなく**ただの文字**である。二箇所で画面にそのまま表示されていた。

### `components/admin/DashboardLayout.jsx`

```jsx
// 修正前
<div className="flex min-h-screen">
    //<Sidebar/>                              // ← "//" が画面に表示され、サイドバーは出ない
    <div className="w-full md:pl-8 "><Outlet/></div>
</div>
```

管理者画面に**サイドバーが丸ごとなく**、左上に `//` の二文字が表示されていた。管理者メニューの
移動が不可能である。

```jsx
// 修正後
<div className="flex min-h-screen">
    <Sidebar/>
    <div className="w-full md:pl-8 "><Outlet/></div>
</div>
```

### `pages/header/Header.jsx`

```jsx
// 修正前
<DeliveryTo />

Search Desktop                                // ← ヘッダーにこの文字がそのまま表示される
<div className="hidden md:flex flex-grow">
```

コメントから波括弧が抜けたものと思われる。すべてのページのヘッダーに "Search Desktop" という文字が
表示されていた。`{/* ... */}` で囲んだ。

---

## 18~20. 🟡 その他の確実なエラー

### `util/imageUtil.js` — 改行の入った URL

```jsx
// 修正前
const response = await instance.post(`https://api.cloudinary.com/v1_1/
    ${process.env.REACT_APP_CLOUDINARY_NAME}/upload`,
     formData, config,
     { headers: { "content-type": "multipart/form-data" } })   // ← 4 番目の引数
```

テンプレートリテラルは**ソースの改行を文字として保持する。** そのため実際の URL は
`https://api.cloudinary.com/v1_1/\n        demo/upload` になる。このアドレスではアップロードが
成功しえない。

そして `axios.post` は引数を三つ(url, data, config)受け取る。**四番目の引数は無視される** —
ヘッダーが丸ごと捨てられていた。

```jsx
// 修正後
const cloudName = process.env.REACT_APP_CLOUDINARY_NAME;
const response = await instance.post(
    `https://api.cloudinary.com/v1_1/${cloudName}/upload`,
    formData,
    config
);
```

Content-Type はそもそも指定しない。FormData を送るときはブラウザが multipart の境界
(boundary)を含むヘッダーを自分で作る必要があるが、手で指定すると boundary が抜けてサーバーが
パースできない。

同じファイルの `fetchImageFromURL` も直した — `new Promise(async (resolve, reject) => ...)` で
包んでいたので、エラーが起きると Promise が永遠に待機状態のまま残っていた(§1 と同じパターン)。

### `components/checkout/Payment.jsx` — 決済手段アイコンの 404

```jsx
- src={`${process.env.PUBLIC_URL}/public/assets/images/${payment.id}.png`}
+ src={`${process.env.PUBLIC_URL}/assets/images/${payment.id}.png`}
```

`public/` フォルダの内容は**ルート(`/`)として**配信される。パスに `/public` を入れると
`/public/assets/...` を探して 404 になる。決済手段のアイコンがすべて壊れていた。同じ画像を
`order/Payment.jsx` は `/assets/images/...` で正しく参照していたので、二つの画面で表示が
異なっていた。

### `util/TokenRefresher.js` — 相対パスでの遷移

```jsx
- navRef.current("error_server");      →  + navRef.current("/error_server");
- navRef.current("signin");            →  + navRef.current("/signin");
```

先頭にスラッシュがないと、現在のパス基準の**相対パス**として解釈される。`/profile/orders` で 503 が
起きると `/profile/error_server` に行き、リフレッシュトークンが期限切れになると `/profile/signin` に行く。
どちらも存在しないルートなのでエラー画面が出る — セッションが期限切れになったときにログイン画面へ行け
ないということである。

---

## 21. 🟡 `Array.prototype` の汚染と偏ったシャッフル

### `util/sort_utils.js`

```js
// 修正前
Array.prototype.keySort = function(keys) { /* ... */ };
export default Array.prototype.keySort;
```

組み込みのプロトタイプを直接書き換えている。すると:

- このモジュールを import するだけで、アプリケーションの**すべての配列**が変わる。import の副作用が
  グローバルなので、どこでなぜ生えたメソッドなのか追跡しにくい。
- **列挙可能な**プロパティとして追加されるので、`for (const k in someArray)` がインデックスと一緒に
  `"keySort"` まで返す。こうしたループが一つでもあると黙って壊れる。
- 同じ名前を使う他のライブラリや、将来の標準メソッドと衝突する。

そして `export default Array.prototype.keySort` は**バインドされていない関数**なので、import して
そのまま呼ぶと `this` が undefined で即座に爆発する。実際にこのモジュールを import している箇所は一つも
なかった — 誰も使えないコードだった。

配列を引数に受け取り、**新しい配列**を返す普通の関数に変えた。

### `util/array_utils.js` — `randomize`

```js
// 修正前
export const randomize = (array) => array.sort(() => 0.5 - Math.random());
```

問題は二つある。

**呼び出し元の配列を壊す。** `sort` はインプレースのソートなので、引数で受け取った配列自体が変わる。
画面に表示するリストを混ぜようとして呼んだのに、元データ(ローダーが返したデータ、redux の状態)まで順序が変わる。

**均等に混ざらない。** 比較関数には一貫性がなければならない — 同じ二つの要素を比較すれば常に
同じ答えが出なければならない。ランダムな比較関数はそのルールを破るので、エンジンのソートアルゴリズムがある順序を
他の順序よりはるかに頻繁に生み出してしまう。

```js
// 修正後 — Fisher–Yates
export const randomize = (array) => {
    const copy = [...array];
    for (let i = copy.length - 1; i > 0; i--) {
        const j = Math.floor(Math.random() * (i + 1));
        [copy[i], copy[j]] = [copy[j], copy[i]];
    }
    return copy;
}
```

同じファイルの `compareArrays` はパラメーター `array1` を Set で**再代入**していた。名前は
配列なのに値が Set だと、後で直す人が必ず混乱する。ローカル変数に分けた。

### `hook/hooks.js` — グローバルヘッダーの汚染

```jsx
// 修正前
mutationFn: () => {
  api.defaults.headers.post["MultiLogin"] = isMultipleLogin ? "true" : "false";
  return postRequest('/user-service/api/auth/logout', null);
},
```

`api.defaults` は axios インスタンスの**グローバルなデフォルト値**である。一度のログアウトがその後の**すべての POST
リクエスト**のヘッダーを変えてしまい、元に戻さない。リクエスト単位のヘッダーに変えた(そのため §7 で
`postRequest` に `requestConfig` 引数を追加した)。

---

## 22. 🟡 チャット: 使われていない二つ目のリデューサー

`redux/ChatSlice.js` には `createSlice` で作ったリデューサーと、その下に手書きの
`chatReducer` の switch 文(約 40 行)が**両方**あった。ストアが使うのは `chatSlice.reducer`
で、下の関数はどこにもつながらないまま**別の状態遷移ルール**を持っていた。

チャットの動作を直しに来た人は、二つのうちどちらが本物か分からず、実際には使われていない方を
直しても何も起きない。死んだリデューサーと、それだけが使っていたアクションタイプの import を消した。
`setRoomConversation` の中の `console.log` も一緒に消した。

`redux/ChatSelectors.js` では `conversation` セレクターを消した:

```js
export const conversation = (state) => state.chat.conversation;   // そのようなフィールドはない
```

chat スライスに `conversation` というフィールドはない(あるのは `roomConversation` だけである)。
このセレクターは常に `undefined` を返すが、名前がそれらしいので「会話内容を取得する
セレクター」として誤って使われるのにうってつけだった。

---

## 23. 🟠 作っておいてつないでいない画面

今回の作業で追加したコンポーネント二つが**どこからも import されて**いなかった。ファイルはあるが、
ユーザーが到達できる経路がないので、機能がないのと同じである。

### `components/delivery/DeliveryTracker.jsx` → 注文詳細につなぐ

```jsx
// pages/order.jsx
{orderData.orderStatus !== "NOT_PROCESSED" && orderData.orderStatus !== "UNPAID" && (
    <div className="mt-4">
        <DeliveryTracker orderId={orderData.id ?? orderId} />
    </div>
)}
```

決済が終わった注文にだけ表示する。決済直後にはまだ delivery のレコードがないことがあるが、それは
エラーではなく「配送準備中」であり、`DeliveryTracker` がその状態を自分で処理する。

### `components/notification/SmsVerification.jsx` → セキュリティ設定につなぐ

```jsx
// pages/profile/security.jsx
<div className="mt-10 border-t pt-6">
    <SmsVerification title="휴대폰 인증 (SMS 알림 · 2단계 인증)"
                     onVerified={() => setPhoneVerified(true)} />
</div>
```

notification-service の SMS 認証を使う画面がこれまでどこにもなかった — コンポーネントはあるのに
付ける場所がなく、**ユーザーが番号を登録する方法自体がなかった。** セキュリティ設定ページが最も
自然な場所である。

---

## 24. 🟡 ゲートウェイの RewritePath のタイプミス (今はたまたま動作)

`gateway-server/src/main/resources/application-local.yml`

```yaml
- RewritePath=/user-service/(?<segment>. *) , /$\{segment}
                                       ↑ 空白
```

`user-service` のルート二箇所だけ、正規表現に空白が入っている。他の 8 サービスはすべて
`(?<segment>.*)` で正しい。

**正直に書くと、これのせいで今壊れているものはない。** `RewritePath` は内部的に
`String.replaceAll` を使うが、これはアンカーされない置換なので、`. *`(任意の一文字 + 空白 0 個
以上)が最初の一文字にだけマッチし、`/user-service/a` → `/a` に置換されて、**結果的にプレフィックス
除去と同じ効果**が出る。確認してみた:

```
'/user-service/api/auth/public/signin'  →  '/api/auth/public/signin'   (タイプミス版)
'/user-service/api/auth/public/signin'  →  '/api/auth/public/signin'   (正しい版)
```

それでも直した理由は、これが**偶然に頼っているから**である。パスに空白が入ったり、フィルターが
アンカーマッチに変わったり、誰かがこの正規表現をコピーして別の場所で使ったりすると、そのときに壊れる。そして次に
このファイルを読む人は「なぜここだけ違うのか?」を改めて調べなければならない。

```yaml
- RewritePath=/user-service/(?<segment>.*), /$\{segment}
```

---

## 直さなかったもの (意図的)

レビュー中に見つけたが**手を付けないことにした**ものである。理由を残す。

**`pages/forgot.jsx`** — import パスがすべて存在しない(`@/components/Footer` など)。どの
ルートにもつながっていないのでバンドルに含まれず、したがってビルドも壊れない。この画面を
復活させるには `ForgotPage` との関係を整理する必要があるが、それは機能の決定であってバグ修正ではない。

**`util/loadFake.js`** — 開発用のシードデータ生成器である。商品 20 個をインデックスでハードコードして
二十行ずつ繰り返すなど問題が多く、`postRequest` の三番目の引数を config として誤って渡して
いるが、どこからも import されていない開発ツールである。ユーザーがまだ必要とするかもしれないので残した。

**配列のインデックスを key に使っている箇所(20 箇所余り)** — `key={i}` はリストの**順序が変わったり途中が
削除されたりしたときに**状態が見当違いの行に残る問題を生む。実際にカート(`CartPage`)のように削除が
起きるリストでは直すのが正しいが、安定した id が何かはバックエンドのレスポンス形式を
確認しないと決められない。今回の範囲で確信を持って直せる箇所ではないので残した。

**`==` を `===` に変えること(20 箇所余り)** — ほとんどが文字列/数値の比較なので、実際の動作に違いはない。
動作が変わらない変更で diff を大きくすると、肝心の重要な修正が埋もれる。

**`redux/authSlice.js`** — ストアにつながらないまま `AuthContext` と同じ役割を果たす
重複実装である。消すのが正しいが、「認証状態を Context で行くか Redux で行くか」は設計の
決定なので、勝手に片方をなくさなかった。**次に決めるべきこと**として残す。

**バンドルサイズ** — `main.bundle.js` が 1.97 MiB である。管理者画面だけが lazy ロードされていて、残りは
すべて一塊である。ルート単位のコード分割で大きく減らせるが、別作業である。

---

## 次にやること

優先順位順に。

1. **フロントエンドのテストが一つもない。** 今回直したもののうち相当数(§10 の TS 構文、§11 の
   `disabled`、§12 の割引計算、§13 のトグル)は、**テストが一行でもあれば即座に捕まっていた**
   種類である。`@testing-library/react` はすでに devDependency にある。最低限 §12 の `discounted`
   のような純粋関数から始めれば、コストに対する効果が大きい。
2. **§1・§5・§8 を実環境で確認。** PayPal 決済、2FA ログイン、管理者 API のパスは、コードの
   判断だけでは終われない。
3. **ゲートウェイの `/admin` 権限チェックの確認。** §3 は UI のガードだけを直した。サーバー側に穴が開いていれば
   何の役にも立たない。
4. **SMS 2FA を実際のログイン経路につなぐ。** 今の 2FA は TOTP で、今回作った SMS 認証は
   番号登録・通知受信用である。二つをつなぐバックエンド作業が残っている。
5. **認証状態の二重化の整理** — `AuthContext` と `redux/authSlice` のどちらか一方に。
