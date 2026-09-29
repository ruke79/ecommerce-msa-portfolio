# 26. 프론트엔드 전면 리뷰 · 리팩토링 — 문제점과 수정 전/후

[日本語](26-frontend-review-and-refactor.md) | **한국어**

> 🕰️ **상태(2026-09-27) — 이전 기록.** 프론트 테스트는 [docs/28](28-frontend-test-suite.ko.md) 에서 도입했습니다(지금 930건). 2FA 로그인 확인은 `/public/verify-2fa` 로 분리됐습니다.

`frontend/src` 전체(168개 파일, 약 15,500줄)를 읽고 **실제로 동작이 잘못된 것**을 찾아 고쳤다.
이 문서는 무엇이 왜 잘못됐고 어떻게 고쳤는지를 하나씩 적는다.

기준을 하나 세워 두고 시작한다. **취향 문제는 고치지 않았다.** 들여쓰기, 따옴표, 이름 짓기,
`==` 대신 `===` 같은 것은 그 자체로 버그가 아니면 손대지 않았다. 대신 다음에 해당하는 것만
골랐다.

- 실행하면 틀린 결과가 나오는 것
- 사용자가 할 수 있어야 하는 일을 막고 있는 것
- 오류를 감춰서 원인을 못 찾게 만드는 것
- 지금은 우연히 동작하지만 조건이 조금만 바뀌면 깨지는 것

검증: `npm run build` 통과(경고는 번들 크기 관련 2건뿐), 백엔드 전체 테스트 209건 통과.

> ⚠️ **한계를 먼저 밝힌다.** 이 프로젝트에는 프론트엔드 테스트가 한 줄도 없다. 그래서 아래
> 수정들은 **정적 분석과 빌드로만** 검증했고, 브라우저에서 실제 클릭해 확인한 것이 아니다.
> "결제가 이제 정상 기록된다"는 코드를 읽어서 내린 판단이지 실행으로 증명한 사실이 아니다.
> 특히 §1(PayPal), §5(2FA), §8(관리자 API 경로)은 실제 환경에서 한 번씩 눌러 봐야 한다.
> 프론트엔드 테스트 도입은 §14 에 따로 적었다.

---

## 심각도별 요약

| # | 위치 | 증상 | 심각도 |
|---|---|---|---|
| 1 | `components/order/Payment.jsx` | PayPal 결제가 **주문에 반영되지 않는다** (capture 2회 호출) | 🔴 |
| 2 | `pages/user/SignInPage.jsx` | 로그인 실패 이유가 절대 표시되지 않는다 | 🔴 |
| 3 | `App.jsx`, `components/ProtectedRoute.jsx` | `/admin` 이 **무인증·무권한**으로 열려 있다 | 🔴 |
| 4 | `components/checkout/Summary.jsx` | 주문 실패 시 스피너가 영원히 돈다 / 중복 주문 가능 | 🔴 |
| 5 | `pages/user/SignInPage.jsx` | 2FA 켠 사용자는 로그인 자체가 불가능 | 🔴 |
| 6 | `components/cart/CartPage.jsx` | 장바구니 저장이 실패해도 결제 화면으로 넘어간다 | 🔴 |
| 7 | `util/api.js` (15곳) | 모든 API 오류를 삼켜 엉뚱한 곳에서 죽는다 | 🔴 |
| 8 | 관리자 화면 5곳 + 2곳 | API 경로가 게이트웨이 라우트와 달라 전부 404 | 🔴 |
| 9 | `redux/store.js` | redux-persist 가 **아무것도 저장하지 않는다** | 🟠 |
| 10 | `hook/hooks.js` | 무한 스크롤 훅이 TypeScript 문법 오입력으로 죽어 있다 | 🟠 |
| 11 | `pages/user/ButtonInput.jsx` | `disabled` 가 무시된다 → 중복 제출 방지가 전부 무효 | 🟠 |
| 12 | `pages/Product.jsx` | 할인가 계산식이 틀렸다 (50% 할인이 2% 할인) | 🟠 |
| 13 | `pages/browse.jsx` | 필터 해제가 안 된다 / 디바운스가 동작하지 않는다 | 🟠 |
| 14 | `util/Notification.js` | 알림 재연결이 영구히 멈춘다, 연결이 중첩된다 | 🟠 |
| 15 | `pages/Error.jsx` | 오류 화면이 오류로 죽어 **빈 화면**이 된다 | 🟠 |
| 16 | `components/admin/DashboardLayout.jsx` | 사이드바가 안 나오고 화면에 `//` 가 찍힌다 | 🟡 |
| 17 | `pages/header/Header.jsx` | 헤더에 "Search Desktop" 글자가 찍힌다 | 🟡 |
| 18 | `util/imageUtil.js` | Cloudinary URL 에 개행이 들어가 업로드 불가 | 🟡 |
| 19 | `components/checkout/Payment.jsx` | 결제수단 아이콘 전부 404 | 🟡 |
| 20 | `util/TokenRefresher.js` | 상대 경로 이동으로 엉뚱한 URL 로 간다 | 🟡 |
| 21 | `util/sort_utils.js`, `util/array_utils.js` | `Array.prototype` 오염 / 편향된 셔플 | 🟡 |
| 22 | `redux/ChatSlice.js`, `ChatSelectors.js` | 쓰이지 않는 두 번째 리듀서, 존재하지 않는 필드 셀렉터 | 🟡 |
| 23 | `components/delivery`, `components/notification` | 만들어 놓고 **어디에도 연결되지 않은** 화면 | 🟠 |
| 24 | `gateway-server/.../application-local.yml` | RewritePath 정규식 오타 (현재는 우연히 동작) | 🟡 |

---

## 1. 🔴 PayPal 결제가 주문에 반영되지 않는다

`components/order/Payment.jsx`

가장 심각한 버그다. **돈은 빠져나가는데 주문은 미결제로 남는다.**

### 수정 전

```jsx
async function onApproveHandler(data, actions) {
  dispatch({ type: "PAY_REQUEST" });
  try {
    const details = await actions.order.capture();      // ← ① 1차 캡처
    console.log("detail : " + details.id);

    const payment = { orderId: order.id, /* ... details 로 채움 ... */ };

    dispatch({ type: "PAY_REQUEST" });
    const captureFunc = actions.order.capture().then(   // ← ② 2차 캡처(!)
      (details) => {
        payment.paypalOrderId = details.id;
        // ...
        const completePayment = (payment) => {
          let promise = new Promise(async function (resolve, reject) {
            const data = await postRequest('/order-service/api/order/payment/paypal', payment);
            resolve(data);                               // ← ③ 여기가 유일한 서버 기록
          });
          return promise;
        }
        completePayment(payment).then(res => { /* 주문 상태 갱신 */ });
      });
    return captureFunc;
  } catch (error) { /* ... */ }
}
```

### 무엇이 문제인가

**`actions.order.capture()` 를 두 번 부른다.** ①의 결과(`details`)로 `payment` 객체를 다 채워
놓고는, 그것을 쓰지 않고 ②에서 다시 캡처한다. PayPal 은 이미 캡처된 주문을 다시 캡처하면
`ORDER_ALREADY_CAPTURED` 로 거절한다.

그리고 하필 **서버에 결제를 기록하는 호출(③)이 ②의 `.then` 안에** 있다. ②가 거절되면
`.then` 은 실행되지 않는다. 즉:

- ① 에서 **실제 결제는 성공한다** (사용자 카드에서 돈이 나간다)
- ② 가 실패한다
- ③ 이 실행되지 않아 **백엔드는 결제 사실을 모른다**
- 주문은 영원히 `UNPAID` 로 남고, 결제 사가(saga)도 시작되지 않는다

사용자 입장에서는 "결제했는데 주문에 반영이 안 됨"이다. 게다가 `catch` 는 ②의 실패를
`PAY_FAIL` 로 저장하지만 **그 값을 화면에 그리는 코드가 없어서** 아무 표시도 나지 않았다.

`new Promise(async (resolve, reject) => ...)` 도 같은 파일에 두 번 나온다. 이 패턴은 async
함수가 던진 오류를 아무도 못 받는다 — `reject` 를 부르는 곳이 없어 프라미스가 영원히 대기
상태로 남는다. 이미 프라미스를 돌려주는 `postRequest` 를 굳이 다시 감쌀 이유도 없다.

### 수정 후

```jsx
async function onApproveHandler(data, actions) {
  dispatch({ type: "PAY_REQUEST" });
  try {
    const details = await actions.order.capture();       // 정확히 한 번

    const payment = { orderId: order.id, paypalOrderId: details.id, /* ... */ };

    const res = await postRequest("/order-service/api/order/payment/paypal", payment);

    // 서버에 기록된 뒤에만 화면을 결제 완료로 바꾼다.
    setOrder({ ...order, orderStatus: "PAID" });
    reduxDispatch(emptyCart());
    dispatch({ type: "PAY_SUCCESS", payload: res.data });
  } catch (error) {
    dispatch({ type: "PAY_FAIL", payload:
      "결제는 처리되었지만 주문에 반영하지 못했습니다. 고객센터로 문의해 주세요. " + ... });
  }
}
```

순서도 바꿨다. 예전에는 서버 기록과 화면 갱신이 뒤엉켜 있었는데, **서버에 기록된 뒤에만**
화면을 결제 완료로 바꾼다. 반대로 하면 기록이 실패했을 때 화면만 결제 완료가 되어 사용자가
다시 결제하지 않는다 — 미수금이 그대로 남는다.

실패 메시지 문구도 신경 썼다. 이 지점의 실패는 "결제 실패"가 아니라 **"결제는 됐는데 기록이
안 됨"**이다. 사용자가 다시 결제하면 이중 결제가 되므로, 재시도를 권하지 않고 고객센터로
안내한다.

함께 고친 것:

- `reducer` 에 `default` 분기가 없었다 → 모르는 액션이 오면 `undefined` 를 반환하고 다음
  렌더에서 `state.loading` 접근이 터진다. 상태 머신은 항상 상태를 돌려줘야 한다.
- `PAY_FAIL` 이 담은 오류 메시지를 실제로 화면에 그린다.
- `onCancelHandler` 가 `order.paymentStatus = 'CANCELED'` 로 **props 객체를 직접 변형**하고
  있었다. props 를 변형하면 React 는 참조가 같아서 바뀐 줄 모른다.

---

## 2. 🔴 로그인 실패 이유가 절대 표시되지 않는다

`pages/user/SignInPage.jsx`

### 수정 전

```jsx
const signin = async (user) => {
    try {
        const data = await api.post("/user-service/api/auth/public/signin", user);
        return data;
    } catch (err) {
        console.log("erorr >>>", err.response?.data.message);   // ← 삼킨다
    }
}

const { mutate, isPending } = useMutation({
    mutationFn: signin,
    onSuccess: (response) => {
        try {
            const access = response.headers['access'];          // ← undefined.headers
            // ...
        } catch (error) {
            toast.error("Login Failed. Please retry.");         // ← 여기로 온다
        }
    },
    onError: (error) => { /* 절대 실행되지 않는다 */ }
});
```

### 무엇이 문제인가

`signin` 의 `catch` 가 로그만 찍고 아무것도 던지지 않는다. 그러면 함수는 `undefined` 를
반환하고, `useMutation` 은 그것을 **성공**으로 받는다. 그래서:

- 비밀번호가 틀려도 `onError` 는 **한 번도 실행되지 않는다**
- `onSuccess` 안에서 `response.headers` 접근이 터진다
- 안쪽 `catch` 가 그것을 받아 "Login Failed. Please retry." 라는 뭉뚱그린 메시지를 띄운다
- 서버가 알려준 진짜 이유("존재하지 않는 계정", "비밀번호가 5회 틀려 잠김")는 콘솔에만 남는다

사용자는 왜 로그인이 안 되는지 알 수 없다.

### 수정 후

```jsx
// 오류를 잡지 않는다 — useMutation 의 onError 가 받도록 그대로 올려보낸다.
const signin = (user) => api.post("/user-service/api/auth/public/signin", user);

const { mutate, isPending } = useMutation({
    mutationFn: signin,
    throwOnError: false,   // 로그인 실패는 이 화면에서 처리한다
    onSuccess: (response) => { /* ... */ },
    onError: (error) => {
        toast.error(error?.response?.data?.message || "Login Failed.");
        setAuthenticationStatus(STATUS.FAILED);
    }
});
```

`throwOnError: false` 를 명시한 이유가 있다. `queryClient` 의 기본값이 `throwOnError: true`
라서, 그대로 두면 로그인 실패가 ErrorBoundary 로 올라가 **로그인 화면 자체가 사라진다.**
로그인 실패는 예외 상황이 아니라 이 화면이 처리해야 할 정상적인 흐름이다.

### 함께: `queryKey` 오타

```jsx
- queryClient.invalidateQueries({ querykey: [LOGIN_QUERY_KEY] });   // 소문자 k
+ queryClient.invalidateQueries({ queryKey: [LOGIN_QUERY_KEY] });
```

`querykey` 는 react-query 가 모르는 키다. 필터 객체에 알 수 없는 키만 들어 있으면 **필터가
없는 것으로 취급되어 모든 쿼리가 무효화된다.** 로그인 한 번에 장바구니·주문·쿠폰·상품 목록이
전부 다시 요청됐다. `RegisterPage.jsx` 에도 같은 오타가 있어 함께 고쳤다.

### 함께: 회원가입은 더 나빴다 — **실패가 성공으로 보인다**

`RegisterPage.jsx` 의 `signup` 에도 똑같은 삼킴 패턴이 있었는데, 결과가 더 나쁘다.

```jsx
// 수정 전
const signup = async (userData) => {
    try {
        console.log(userData);                       // ← 평문 비밀번호를 콘솔에 찍는다
        const data = await postRequest("/user-service/api/auth/public/register", userData);
        return data;
    } catch (err) {
        console.log(err.response?.data.message);     // ← 삼킨다
    }
}
```

가입이 실패해도 `onSuccess` 가 실행되므로:

- **"인증 메일을 보냈습니다"** 토스트가 뜨고
- 로그인 화면으로 **이동한다**

사용자는 오지 않는 메일을 기다린다. "이미 가입된 이메일입니다" 같은 서버 메시지는 콘솔에만
남는다. 로그인 실패보다 나쁜 이유는, 사용자가 **뭔가 잘못됐다는 사실 자체를 모른다**는 점이다.

```jsx
// 수정 후
const signup = (userData) =>
    postRequest("/user-service/api/auth/public/register", userData);
```

`onError` 도 고쳤다 — 예전에는 서버 메시지를 state 에만 담고 토스트에는 고정 문구
`"Failed to register user. Please retry."` 를 띄웠다. 이제 서버가 알려준 이유를 그대로
보여 준다. 그리고 `console.log(userData)` 를 지웠다: **평문 비밀번호가 브라우저 콘솔에
찍히고 있었다.**

---

## 3. 🔴 `/admin` 이 무인증·무권한으로 열려 있다

`App.jsx`, `components/ProtectedRoute.jsx`

### 수정 전

```jsx
{
  path: '/admin',
  element: <Suspense fallback={<div>Loading Admin...</div>}><DashboardLayout /></Suspense>,
  children: [ /* category, product, allproducts */ ]
}
```

다른 라우트는 전부 `<ProtectedRoute>` 로 감싸여 있는데 **`/admin` 만 빠져 있다.** 게다가
`ProtectedRoute` 자체에 역할 검사가 없었다 — 로그인만 하면 누구나 통과한다.

결과적으로 두 단계로 뚫려 있었다.

1. 로그인하지 않은 사람이 주소창에 `/admin/product` 를 치면 관리자 화면이 그대로 그려진다
2. 일반 회원으로 로그인하면 관리자 화면을 **정상적으로** 볼 수 있다

파일 안에는 역할 검사를 하는 예전 버전이 **주석으로** 남아 있었다(`adminPage` 옵션). 어느
시점에 비활성화된 뒤 되살아나지 않은 것으로 보인다.

### 수정 후

```jsx
const ProtectedRoute = ({ children, adminOnly = false }) => {
  const { status, isAuthenticated, user } = useAuthContext();

  if (status === STATUS.PENDING) return <DotLoaderSpinner loading={true} />;
  if (!isAuthenticated) return <Navigate to="/signin" replace />;
  if (adminOnly && !hasAdminRole(user)) return <Navigate to="/" replace />;

  return children;
};

// JWT 의 roles 는 배포에 따라 "ADMIN" 이기도 하고 "ROLE_ADMIN" 이기도 하다. 한쪽만 보면
// 관리자가 조용히 자기 화면에서 쫓겨나고, 화면에는 이유가 전혀 드러나지 않는다.
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

**분명히 해 둘 것:** 이건 UI 가드일 뿐 보안 경계가 아니다. 브라우저에서 도는 코드는 사용자가
얼마든지 바꿀 수 있으므로, 진짜 권한 검사는 게이트웨이와 각 서비스가 해야 한다. 그럼에도
필요한 이유는 권한 없는 사용자에게 관리자 화면의 구조·메뉴·엔드포인트를 노출하지 않기
위해서다. **게이트웨이 쪽 `/admin` 경로 권한 검사는 이 문서의 범위 밖이며 별도로 점검해야
한다.**

`replace` 도 추가했다. 없으면 리다이렉트가 히스토리에 쌓여 뒤로가기를 눌렀을 때 다시 막힌
화면으로 갔다가 또 튕기는 루프가 된다.

---

## 4. 🔴 주문 실패 시 스피너가 영원히 돈다 · 중복 주문

`components/checkout/Summary.jsx`

### 수정 전

```jsx
const placeOrderHandler = async () => {
    try {
        setLoading(true);
        // ... 검증 ...
        const {data} = await postRequest("/order-service/api/order/create", { /* ... */ });
        navigate(`/order/${data.orderId}`);
        //setLoading(false);              // ← 주석 처리
    } catch (error) {
        //setLoading(false);              // ← 주석 처리
        setOrder_Error(error.response?.data.message);
    }
};

// ...
<button onClick={() => placeOrderHandler()}>Place Order</button>   // ← 가드 없음
```

### 무엇이 문제인가

**세 가지가 겹쳐 있다.**

1. `setLoading(false)` 가 성공·실패 경로 **양쪽 다 주석 처리**돼 있다. 주문 생성이 실패하면
   전체 화면 스피너가 영원히 떠 있고, 그 아래 렌더링될 오류 메시지는 스피너에 가려 보이지도
   않는다. 사용자가 할 수 있는 건 새로고침뿐이다.
2. **중복 제출 가드가 없다.** "Place Order" 를 두 번 누르면 주문이 두 건 생성된다. 결제까지
   두 번 이어질 수 있는 경로다.
3. `error.response?.data.message` — 옵셔널 체이닝이 `response` 까지만 보호한다. 서버가 본문
   없이 500 을 주면 `data` 가 undefined 라 `.message` 에서 **catch 안에서 다시 TypeError** 가
   나고, 원래 오류는 그 TypeError 에 가려진다.

### 수정 후

```jsx
const [placing, setPlacing] = useState(false);

const placeOrderHandler = async () => {
    if (placing) return;                       // 중복 제출 방지

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

`finally` 를 쓴 것이 핵심이다. 성공하면 `navigate` 로 화면을 떠나지만, 떠나지 못하는 모든
경로에서 스피너는 반드시 꺼져야 한다.

### 함께: 렌더 중에 API 를 호출하고 있었다

```jsx
// 수정 전 — 컴포넌트 함수 본문(렌더 중!)에 그대로 있었다
if (coupon === "" && coupons && isSuccess) {
    if (coupons?.length > 0) {
        setCoupon(coupons[0]);
        applyCouponHandler(coupons[0]);      // ← 렌더 중에 비동기 API 호출
    }
}
```

렌더는 부수 효과가 없어야 한다. React 는 렌더를 여러 번 실행할 수 있고(StrictMode 에서는
개발 중 항상 두 번), 그때마다 쿠폰 적용 요청이 중복으로 나간다. effect 로 옮겼다.

```jsx
useEffect(() => {
    if (isSuccess && coupon === "" && coupons?.length > 0) {
        setCoupon(coupons[0]);
        applyCouponHandler(coupons[0]);
    }
}, [isSuccess, coupons]);
```

### 함께: 훅 순서 위반을 만들 뻔했다

이 파일에는 `if (isPending) return <div>Loading...</div>` 가 훅들 **중간에** 있었다. 원래
코드에서는 그 아래에 훅이 없어서 우연히 문제가 없었지만, 내가 `useEffect`/`useState` 를
추가하면서 조기 return 이 훅보다 앞서게 됐다. 그러면 렌더마다 호출되는 훅의 개수가 달라져
`Rendered fewer hooks than expected` 로 컴포넌트가 깨진다. 조기 return 을 **모든 훅 뒤로**
옮기고 그 이유를 주석으로 남겼다.

---

## 5. 🔴 2FA 를 켠 사용자는 로그인할 수 없다

`pages/user/SignInPage.jsx`

### 수정 전

```jsx
const [step, setStep] = useState(1);
// ...
if (decodedToken.is2faEnabled) {
    setStep(2); // Move to 2FA verification step
} else {
    handleSuccessfulLogin(response, decodedToken);
}
```

`setStep(2)` 를 호출하는데 **`step` 값을 읽는 곳이 파일 전체에 하나도 없다.** 화면은 그대로
로그인 폼이다. 2FA 를 켠 사용자는 비밀번호를 맞게 넣어도 아무 반응이 없는 것처럼 보이고,
로그인할 방법이 없다.

이번 작업에서 SMS 2단계 인증을 추가했으므로 더더욱 막혀 있으면 안 되는 경로다.

### 수정 후

토큰을 **AuthContext 에 넣지 않고 잠시 들고 있다가**, 코드 확인이 성공해야 로그인을 확정한다.
확인 전에 `login()` 을 부르면 2단계 인증이 아무 의미가 없다.

```jsx
const [pendingAuth, setPendingAuth] = useState(null);

onSuccess: (response) => {
    const access = response.headers['access'];
    const decodedToken = jwtDecode(access);

    if (decodedToken.is2faEnabled) {
        setPendingAuth({ response, decodedToken, access });   // 아직 로그인 아님
        setStep(2);
    } else {
        handleSuccessfulLogin(response, decodedToken);
    }
}

const verify2faHandler = async () => {
    // 서버의 /verify-2fa 는 인증된 사용자를 요구한다. 방금 발급받았지만 아직
    // AuthContext 에 넣지 않은 토큰을 이 요청에만 직접 실어 보낸다.
    await api.post(`/user-service/api/auth/verify-2fa?code=${encodeURIComponent(code2fa)}`,
        null, { headers: { Authorization: `Bearer ${pendingAuth.access}` } });
    handleSuccessfulLogin(pendingAuth.response, pendingAuth.decodedToken);   // 여기서 확정
};
```

화면도 만들었다. 6자리 숫자만 받고(`replace(/\D/g, "")`), `autoComplete="one-time-code"` 로
iOS/Android 의 문자 자동입력을 받고, Enter 로 제출되며, "다른 계정으로 로그인" 을 누르면
들고 있던 토큰을 **버린다** — 계속 들고 있으면 인증을 통과하지 않은 토큰이 화면에 남는다.

> **남은 일:** 백엔드의 `/verify-2fa` 는 TOTP(Google Authenticator) 기반이고, 성공 시
> `enable2FA(userId)` 를 호출한다. 등록용 엔드포인트를 로그인 검증에 재사용하는 구조라
> 로그인 경로에서는 이미 켜진 플래그를 다시 켜는 무해한 동작이지만, 의미상 분리하는 것이
> 맞다. 그리고 이번에 추가한 **SMS 인증과 이 TOTP 2FA 는 아직 별개**다 — SMS 로 2FA 를
> 하려면 백엔드에 SMS 기반 검증 경로를 하나 더 두어야 한다. 백엔드 변경이 필요해 이번
> 범위에서는 하지 않았다.

---

## 6. 🔴 장바구니 저장이 실패해도 결제 화면으로 넘어간다

`components/cart/CartPage.jsx`

### 수정 전

```jsx
try {
    const res = await saveCart(selected, user.email);
    dispatch(commitCart(res.cartId));
} catch(err) {
    console.error(err.response?.data.message);
}
finally {
    navigate("/checkout");        // ← 실패해도 실행된다
}
```

`finally` 는 성공이든 실패든 실행된다. 저장이 실패해도 결제 화면으로 넘어가는데, 그 화면은
**서버에 저장된 장바구니를 다시 읽어서** 그린다. 그래서 사용자는 방금 담은 물건이 없거나
예전 장바구니가 뜬 상태로 결제를 진행하게 된다.

### 수정 후

```jsx
const [saveError, setSaveError] = useState("");
const [saving, setSaving] = useState(false);

const saveCartToDbHandler = async (e) => {
    e.preventDefault();
    if (saving) return;                        // 두 번 누르면 두 번 커밋된다

    setSaving(true); setSaveError("");
    try {
        const res = await saveCart(selected, user.email);
        dispatch(commitCart(res.cartId));
        setPrevSelectged(selected);
        navigate("/checkout");                 // 성공했을 때만 이동
    } catch (err) {
        setSaveError(err?.response?.data?.message ?? "장바구니를 저장하지 못했습니다. 다시 시도해 주세요.");
    } finally {
        setSaving(false);
    }
};
```

오류를 콘솔이 아니라 **화면에** 보여 준다. 함께 `enable` 이라는 state 와 그것만 갱신하던
`useEffect` 를 지웠다 — 어디에서도 읽지 않는 값이었고, 의존성이 `[]` 라 장바구니가 바뀌어도
갱신되지 않아 읽었더라도 틀린 값이었다.

---

## 7. 🔴 모든 API 오류를 삼킨다

`util/api.js` — 같은 패턴이 **15곳**에 복사돼 있었다.

### 수정 전

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

### 무엇이 문제인가

**세 가지가 동시에 잘못돼 있다.**

**① 오류를 삼킨다.** `catch` 가 아무것도 던지지 않고 끝나므로 함수는 `undefined` 를 반환한다.
호출부는 그것을 성공으로 받아 `orderData.products.map(...)` 을 하다가
`Cannot read properties of undefined` 로 죽는다. 실제 원인(401, 500, 타임아웃)은 콘솔에만
남고 화면에는 전혀 상관없는 오류가 뜬다. 디버깅이 가장 어려운 형태의 실패다.

**② React Query 를 무력화한다.** `queryClient` 는 `throwOnError: true` 로 설정돼 있어 쿼리
실패를 ErrorBoundary 로 올리게 돼 있다. 그런데 여기서 오류를 먹어 버리면 react-query 입장에서는
**항상 성공**(값이 undefined 인 성공)이다. 오류 UI 도, 재시도도 동작할 수 없다. 즉
프로젝트가 갖춰 놓은 오류 처리 장치 전체가 이 15줄 때문에 무효였다.

**③ catch 안에서 다시 터진다.** `error.response?.data.message` 의 옵셔널 체이닝은 `response`
까지만 보호한다. 서버가 본문 없이 500 을 주면 `data` 가 undefined 라 `.message` 접근에서
TypeError 가 나고, 원래 오류는 그 TypeError 에 가려진다.

### 수정 후

```jsx
export const getOrders = async (filter, userEmail) => {
  const { data } = await getRequest("/order-service/api/order/orders", {
    params: { filter, email: userEmail },
  });
  return data;
};
```

**잡지 않는다.** 오류는 호출부(useQuery/useMutation → ErrorBoundary)로 그대로 올라간다.
화면에 어떻게 보여줄지는 화면이 정할 일이지 데이터 계층이 정할 일이 아니다.

이 변경으로 오류가 실제로 흐르기 시작하므로, 오류가 화면 전체를 날려서는 안 되는 지점들은
개별적으로 잡아 줬다 — 쿠폰 적용 실패로 결제 화면이 통째로 사라지면 안 되므로
`Summary.applyCouponHandler` 에 `try/catch` 를 넣는 식이다.

함께 고친 것:

```jsx
// processPayment: 상태를 바꾸는 호출인데 GET 이었고, 요청 본문을 GET 의 config 자리에
// 넘기고 있어서 서버에는 아무것도 전달되지 않았다.
- await getRequest("/order-service/api/order/payment/process", request);
+ await postRequest("/order-service/api/order/payment/process", request);
```

`postRequest`/`putRequest`/`patchRequest` 에 4번째 인자 `requestConfig` 를 추가했다. 요청
단위로 헤더를 실을 수 있어야 전역 기본값을 건드리지 않아도 되기 때문이다(§ 아래).

---

## 8. 🔴 API 경로가 게이트웨이 라우트와 맞지 않는다

게이트웨이는 `/{service}-service/**` 로만 라우팅한다. `/admin/**`, `/auth/**`, `/user/**` 로
시작하는 경로에는 **라우트 자체가 없다.** 그런데 프론트엔드 7곳이 그렇게 부르고 있었다.

| 파일 | 수정 전 | 수정 후 |
|---|---|---|
| `pages/admin/Index.jsx` | `/admin/product/products` | `/catalog-service/api/product/products` |
| `pages/admin/Index.jsx` | `/admin/product/subcategories` | `/catalog-service/api/category/product/subcategories` |
| `components/admin/CreateSubCategory.jsx` | `/admin/subcategory` | `/catalog-service/api/category/subcategory` |
| `pages/user/ForgotPage.jsx` | `/auth/public/forgot-password` | `/user-service/api/auth/public/forgot-password` |
| `pages/profile/security.jsx` | `/user/profile/update-password` | `/user-service/api/user/profile/update-password` |

`CreateCategory.jsx` 는 접두사는 맞았지만 **컨트롤러 경로가 틀렸다.** 백엔드의
`CategoryController` 는 `@RequestMapping("/api/category")` 인데 프론트는 `/api/product/...` 로
부르고 있었다.

| 수정 전 | 수정 후 |
|---|---|
| `/catalog-service/api/product/categories` | `/catalog-service/api/category/categories` |
| `/catalog-service/api/product/subcategories` | `/catalog-service/api/category/product/allsubcategories` |
| `/catalog-service/api/product/category` | `/catalog-service/api/category/category` |
| `/catalog-service/api/product/subcategory` | `/catalog-service/api/category/subcategory` |

즉 **비밀번호 변경, 비밀번호 찾기, 관리자 카테고리 관리가 전부 404** 였다. §7 의 오류 삼킴과
결합해서, 사용자에게는 "버튼을 눌러도 아무 일도 안 일어남"으로 보였다.

> 이 대응표는 백엔드 컨트롤러의 `@RequestMapping`/`@GetMapping` 을 직접 읽어 맞춘 것이다.
> 다만 실제 호출로 확인하지는 못했으므로, 관리자 화면은 한 번 눌러 보며 검증해야 한다.

---

## 9. 🟠 redux-persist 가 아무것도 저장하지 않는다

`redux/store.js`

### 수정 전

```jsx
const rootReducer = combineReducers({
    //auth: authReducer,          // ← 주석 처리돼 있다
    menu: menuReducer,
    dialog: dialogReducer,
    cart: cartReducer,
    chat: chatReducer,
});

const persistConfig = {
    key: "root",
    storage,
    whitelist: ['auth'],          // ← 존재하지 않는 리듀서
    blacklist: ['token'],         // ← 존재하지 않는 리듀서
};
```

`whitelist` 는 **존재하는 최상위 리듀서 키**만 의미가 있다. `auth` 는 위에서 주석 처리돼
rootReducer 에 없으므로, 저장 대상이 **0개**다. `App.jsx` 가 `PersistGate` 로 앱을 감싸고
`persistStore` 까지 부르는데 실제로는 아무 일도 하지 않는다.

체감되는 증상: **장바구니가 새로고침할 때마다 비워진다.**

`blacklist: ['token']` 도 마찬가지로 `token` 이라는 리듀서가 없어 아무것도 하지 않았다.
"토큰은 저장 안 함"이라는 안심을 주는 주석만 남기고 실제 보호는 하지 않는, 있어서 더 위험한
설정이었다.

그 외에:

- `getDefaultMiddleware` 를 `@reduxjs/toolkit` 에서 named import 하고 있었다. RTK 2.x 에는
  그런 내보내기가 없다(이 프로젝트는 2.2.7). 콜백 파라미터에 가려져 터지지는 않았지만 죽은
  import 다.
- `serializableCheck: false` 로 검사를 **통째로** 껐다. 그러면 redux-persist 내부 액션뿐
  아니라 우리 코드가 스토어에 넣는 File·Date 같은 값도 검사에서 빠진다.

### 수정 후

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
            // 검사는 켜 두고 redux-persist 액션만 예외로 둔다(공식 권장 방식).
            serializableCheck: {
                ignoredActions: [FLUSH, REHYDRATE, PAUSE, PERSIST, PURGE, REGISTER],
            },
        }),
});
```

**`cart` 만** 저장한다. 담아 둔 물건이 새로고침으로 사라지는 것은 사용자가 가장 크게 체감하는
손실이다. 반대로 저장하면 안 되는 것들:

- `chat` — 서버가 진실이다. 오래된 대화 목록을 되살리면 실제와 어긋난다.
- `menu`/`dialog` — 순수 UI 상태다. 열려 있던 모달이 새로고침 후 되살아나면 이상하다.
- **인증 토큰** — 애초에 이 스토어에 없다. XSS 탈취를 막기 위해 메모리(AuthContext)에만 두는
  것이 의도된 설계다(`util/tokenUtil.js`). 여기 whitelist 에 `auth` 를 넣는 것은 그 설계를
  되돌리는 일이므로 **하면 안 된다.** 주석으로 못박아 뒀다.

---

## 10. 🟠 무한 스크롤 훅이 TypeScript 문법 때문에 죽어 있다

`hook/hooks.js`

### 수정 전

```js
const ref = useRef < HTMLDivElement > (null);
```

`.js` 파일이다. TypeScript 의 제네릭 문법을 그대로 붙여 넣은 것인데, JS 파서는 이것을
제네릭이 아니라 **비교 연산 두 번**으로 읽는다:

```js
(useRef < HTMLDivElement) > (null)
```

그래서 `ref` 에는 DOM 참조가 아니라 **불리언 `false`** 가 들어간다. 결과적으로:

- `ref.current` 는 항상 `undefined` → `IntersectionObserver` 가 만들어지지 않는다
- 이 ref 를 실제 엘리먼트에 붙이면 React 가 ref 로 인식하지 못한다

무한 스크롤이 **조용히** 죽어 있었다. 문법 오류가 아니라 유효한 JS 라서 빌드도 통과한다.

### 수정 후

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

의존성에서 `ref` 도 뺐다. `useRef` 가 돌려주는 객체는 항상 같은 참조라 의존성으로서 의미가
없고, 있으면 "ref 가 바뀌면 다시 붙는다"는 잘못된 인상을 준다. 정리(cleanup)에서 `ref.current`
를 다시 읽지 않고 effect 실행 시점의 `target` 을 캡처하도록 한 것도 의도적이다 — 정리 시점의
`ref.current` 는 이미 다른 엘리먼트일 수 있다.

---

## 11. 🟠 `disabled` 가 무시된다 — 중복 제출 방지가 전부 무효

`pages/user/ButtonInput.jsx`

### 수정 전

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

`...props` 를 받아 놓고 **button 에 펼치지 않는다.** 그래서 호출부의 `disabled={isPending}` 은
아무 효과가 없다. `SignInPage.jsx` 에는 이런 주석까지 달려 있었다:

```jsx
<ButtonInput type="submit" text="Sign in" disabled={isPending}  // isPending 상태를 사용하여 버튼을 비활성화합니다.
/>
```

주석은 의도를 말하고 있지만 실제로는 비활성화되지 않는다. 로그인·회원가입 버튼을 두 번 누르면
요청이 두 번 나간다. `onClick` 같은 다른 핸들러도 전부 조용히 버려졌다.

### 수정 후

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

`disabled:` 스타일도 넣었다. 비활성화됐는데 겉모습이 그대로면 사용자는 계속 누른다.

---

## 12. 🟠 할인가 계산이 틀렸다

`pages/Product.jsx`

### 수정 전

```jsx
price:
    subProduct.discount > 0
        ? (subProduct.sizes[size].price - subProduct.sizes[size].price / subProduct.discount).toFixed(2)
        : subProduct.sizes[size].price,
```

`discount` 는 **퍼센트**다(시드 데이터가 5~70 을 넣는다). 그런데 식은
`정가 - 정가 / 할인율` 이다. 이건 아무 의미가 없는 계산이다.

10,000원 상품에 50% 할인이면 5,000원이 나와야 하는데:

```
10000 - 10000/50 = 10000 - 200 = 9800   →  실제로는 2% 할인
```

게다가 **할인율이 클수록 할인 폭이 오히려 작아진다.** 70% 할인이면 `10000 - 143 = 9857`,
즉 1.4% 할인이다. 거꾸로 된 계산이다.

### 또 하나: 조건이 항상 거짓

```jsx
priceRange:
    prices.discount > 1        // ← prices 는 배열이다
        ? `From ${...할인 적용...}`
        : `From ${prices[0]} to ${prices[prices.length - 1]}$`,
```

`prices` 는 `subProduct.sizes.map(s => s.price)` 로 만든 **배열**이다. 배열에 `.discount`
프로퍼티는 없으므로 언제나 `undefined`, 조건은 항상 거짓이다. 할인 상품인데도 가격 범위는
언제나 정가로만 표시됐다.

### 수정 후

```jsx
/** 할인가를 계산한다. `discount` 는 퍼센트다. */
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

`Math.min(d, 100)` 은 데이터 오류로 100 을 넘는 할인율이 들어와도 가격이 음수가 되지 않게
한다.

### 같은 파일에서 함께 고친 것

**제자리 정렬이 원본을 망가뜨렸다.**

```jsx
- const prices = subProduct.sizes.map((s) => s.price).sort((a, b) => a - b);
+ const prices = subProduct.sizes.map((s) => s.price).slice().sort((a, b) => a - b);
```

`.map()` 은 새 배열을 주므로 이 줄 자체는 안전하지만, 바로 아래 `allSizes` 의
`.flat().sort(...)` 는 `product.skus` 안의 배열을 건드릴 수 있다. 두 곳 모두 복사 후 정렬로
바꿨다.

**쿼리스트링이 문자열인 채로 인덱스에 쓰였다.**

```jsx
- const style = searchParams.get("style") || 0;
+ const style = Number(searchParams.get("style")) || 0;
```

**오류를 삼키고 `null` 을 반환했다.**

```jsx
} catch(err) {
    console.error(err.response?.data.message)
    return null;                     // ← 컴포넌트가 product.name 에서 죽는다
}
```

로더가 `null` 을 주면 `SingleProduct` 가 `product.name` 에서 죽는다. 그때 화면에 뜨는
메시지는 진짜 원인(404·500·네트워크)과 아무 상관이 없다. 이제 그대로 던져서 라우터의
`errorElement` 가 처리하게 한다.

**죽은 중복 함수 70줄을 지웠다.** 파일 아래쪽에 `loaderProduct` 라는, `loader` 와 거의
같은 내용의 함수가 있었다. 호출부는 주석 처리돼 있어 아무도 부르지 않는다. 같은 버그가 두
벌 있으면 고칠 때 한쪽만 고치게 된다.

---

## 13. 🟠 필터가 해제되지 않는다 · 디바운스가 동작하지 않는다

`pages/browse.jsx`

### ① 토글이 값으로 조회한다

```jsx
// 수정 전
function toggleParam(name, param) {
    if (param && !searchParams.get(param) && !isEmptyObject(param)) {
        searchParams.set(name, param);
    }
    else if (param) {
        searchParams.delete(name);
    }
}
```

`searchParams.get(param)` — 파라미터의 **이름**이 아니라 **값**으로 조회하고 있다.
`toggleParam('category', 'shoes')` 는 `searchParams.get('shoes')` 를 보는데 그런 파라미터는
없으므로 언제나 `null` 이다. 따라서 첫 조건은 항상 참이고, **같은 필터를 다시 눌러도
해제되지 않는다.**

```jsx
// 수정 후
function toggleParam(name, param) {
    if (!param || isEmptyObject(param)) {
        searchParams.delete(name);        // 빈 값 = "이 필터를 없애 달라"
        return;
    }
    if (searchParams.get(name) === String(param)) {
        searchParams.delete(name);        // 같은 값을 다시 누르면 해제
    } else {
        searchParams.set(name, param);
    }
}
```

`shipping` 과 `rating` 은 같은 로직이 **복사돼** 별도로 쓰여 있었고 같은 버그를 갖고 있었다.
`toggleParam` 호출로 합쳤다.

### ② 디바운스가 매번 새로 만들어진다

```jsx
// 수정 전
const priceHandler = (price, type, delay) => {
    // ...
    let filterPrice = debounce((price) => filter(price), delay);   // ← 호출마다 새로 생성
    filterPrice({ price: newPrice });
};
```

디바운스는 **이전 타이머를 기억하는 클로저**에 의존한다. 호출할 때마다 새 클로저를 만들면 각
호출이 자기만의 타이머를 갖고, 취소할 이전 타이머가 없다. **디바운스가 전혀 동작하지 않는다** —
가격을 타이핑하면 글자 수만큼 검색 요청이 나간다.

```jsx
// 수정 후
const priceTimer = useRef(null);
useEffect(() => () => clearTimeout(priceTimer.current), []);   // 언마운트 시 정리

const priceHandler = (price, type, delay = 300) => {
    // ...
    clearTimeout(priceTimer.current);
    priceTimer.current = setTimeout(() => filter({ price: newPrice }), delay);
};
```

타이머 id 를 ref 에 두어 렌더가 바뀌어도 **같은 타이머**를 취소한다. 언마운트 시 정리도
넣었다 — 없으면 화면을 떠난 뒤에 `setSearchParams` 가 호출된다.

---

## 14. 🟠 알림 재연결이 영구히 멈춘다

`util/Notification.js` — 그리고 이 컴포넌트는 **`App.jsx` 에서 주석 처리돼 있었다.**

### 수정 전

```jsx
const fetchSSE = useCallback(() => {
    if (reconnectAttempts.current > 5) {
      console.log("Max reconnection attempts reached.");
      return;                                    // ← 여기서 영원히 끝
    }
    // ...
    eventSource.current = new EventSoruce(...);  // ← 오타(EventSoruce), 이전 연결을 닫지 않음
    // ...
    eventSource.current.onerror = (err) => {
        const delay = Math.min(1000 * 2 ** reconnectAttempts.current, 30000);
        reconnectAttempts.current += 1;
        timerId.current = setTimeout(fetchSSE, delay);
    };
}, [token, isPending, logout, isAuthenticated]);
```

**문제 1 — 재연결이 영구히 멈춘다.** `reconnectAttempts` 는 성공(`onopen`)했을 때만 0 으로
돌아간다. 서버 배포처럼 몇 분간 끊기는 상황에서 5번을 금방 소진하면, **이후 서버가 돌아와도
다시는 연결을 시도하지 않는다.** 사용자가 페이지를 새로고침하기 전까지 알림이 죽어 있다.

**문제 2 — 연결이 겹친다.** 새 연결을 만들기 전에 이전 `EventSource` 를 닫지 않는다.
`fetchSSE` 의 의존성에 `token` 이 있으므로, `TokenRefresher` 가 만료 10초 전에 토큰을 갱신할
때마다 effect 가 다시 돌고 **연결이 하나씩 늘어난다.** 오래 열어 둔 탭에서는 같은 알림이 여러
번 도착한다.

**문제 3 — 정리가 재연결을 막지 못한다.** 언마운트 시 타이머는 지우지만, 이미 실행 중이던
`onerror` 가 그 뒤에 새 타이머를 걸면 그대로 살아남는다.

**문제 4 — 모든 알림 본문을 콘솔에 찍는다.** 알림에는 주문번호 같은 개인정보가 들어갈 수 있다.

### 수정 후

```jsx
const stopped = useRef(false);

const connect = useCallback(() => {
    if (stopped.current || !isAuthenticated || !token || isPending) return;

    function scheduleReconnect() {
        if (stopped.current) return;                  // 정리 후에는 재연결하지 않는다

        const attempt = reconnectAttempts.current;
        // 상한을 넘겨도 포기하지 않는다 — 5분마다 조용히 다시 시도한다.
        const delay = attempt >= MAX_RECONNECT_ATTEMPTS
            ? 5 * 60 * 1000
            : Math.min(1000 * 2 ** attempt, 30000);

        reconnectAttempts.current = attempt + 1;
        clearTimeout(timerId.current);
        timerId.current = setTimeout(connect, delay);
    }

    eventSource.current?.close();                     // 새로 열기 전에 반드시 닫는다
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

핵심은 **상한에 닿아도 포기하지 않는 것**이다. 지수 백오프의 목적은 서버 부하를 줄이는
것이지 영구히 포기하는 것이 아니다. 5분 간격으로 물러나되 계속 시도한다.

그리고 `App.jsx` 에서 주석을 풀었다. 이 컴포넌트가 꺼져 있는 동안에는 **다른 기기에서
로그인해 세션이 밀려나도 이 탭은 계속 로그인 상태로 남아 있었다** — 서버가 보내는 `Logout`
이벤트를 아무도 듣지 않기 때문이다.

---

## 15. 🟠 오류 화면이 오류로 죽는다

`pages/Error.jsx`

```jsx
// 수정 전
const error = useRouteError();

console.log(error.code);            // ← error 가 undefined 면 여기서 죽는다
console.log(error.status);

if (error.status === 500) {
  message = error.data.message;     // ← 본문 없는 500 이면 여기서 죽는다
}

useEffect(() => {
  if(error.code === 'ERR_NETWORK') {
    navigate('error_server', {replace:true});   // ← 상대 경로
  }
}, [])

const handler = () => { toast("Worng Error"); }   // ← "Try again" 이 하는 일
```

**오류 화면 자체가 오류로 죽으면 사용자는 완전히 빈 화면을 본다.** 그런데 이 파일은
방어가 전혀 없다. 라우터는 `throw new Response(...)` 나 문자열도 오류로 받을 수 있고, 그때
`error` 에는 `code` 가 없다.

`navigate('error_server')` 는 앞에 슬래시가 없어 **상대 경로**다. `/order/12` 에서 오류가 나면
`/order/error_server` 로 가고, 그런 라우트는 없으니 다시 오류다.

"Try again" 버튼은 `toast("Worng Error")` 만 띄운다(오타 포함). 사용자가 할 수 있는 일이
아무것도 없다.

```jsx
// 수정 후
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

// 실제로 다시 시도한다.
<button onClick={() => navigate(0)}>다시 시도</button>
<button onClick={() => navigate('/', { replace: true })}>홈으로</button>
```

`navigate(0)` 은 현재 라우트를 다시 로드한다. 라우터 오류는 대개 로더 실패라 재요청으로
풀리는 경우가 많다.

---

## 16~17. 🟡 JSX 안의 `//` 와 맨텍스트

JSX 안에서 `//` 는 주석이 아니라 **그냥 글자**다. 두 곳에서 화면에 그대로 찍히고 있었다.

### `components/admin/DashboardLayout.jsx`

```jsx
// 수정 전
<div className="flex min-h-screen">
    //<Sidebar/>                              // ← "//" 가 화면에 찍히고 사이드바는 안 나온다
    <div className="w-full md:pl-8 "><Outlet/></div>
</div>
```

관리자 화면에 **사이드바가 통째로 없고** 왼쪽 위에 `//` 두 글자가 찍혀 있었다. 관리자 메뉴
이동이 불가능하다.

```jsx
// 수정 후
<div className="flex min-h-screen">
    <Sidebar/>
    <div className="w-full md:pl-8 "><Outlet/></div>
</div>
```

### `pages/header/Header.jsx`

```jsx
// 수정 전
<DeliveryTo />

Search Desktop                                // ← 헤더에 이 글자가 그대로 찍힌다
<div className="hidden md:flex flex-grow">
```

주석에서 중괄호가 빠진 것으로 보인다. 모든 페이지 헤더에 "Search Desktop" 이라는 글자가
표시되고 있었다. `{/* ... */}` 로 감쌌다.

---

## 18~20. 🟡 그 밖의 확실한 오류들

### `util/imageUtil.js` — 개행이 들어간 URL

```jsx
// 수정 전
const response = await instance.post(`https://api.cloudinary.com/v1_1/
    ${process.env.REACT_APP_CLOUDINARY_NAME}/upload`,
     formData, config,
     { headers: { "content-type": "multipart/form-data" } })   // ← 4번째 인자
```

템플릿 리터럴은 **소스의 개행을 문자로 보존한다.** 그래서 실제 URL 은
`https://api.cloudinary.com/v1_1/\n        demo/upload` 가 된다. 이 주소로는 업로드가
성공할 수 없다.

그리고 `axios.post` 는 인자를 세 개(url, data, config) 받는다. **네 번째 인자는 무시된다** —
헤더가 통째로 버려지고 있었다.

```jsx
// 수정 후
const cloudName = process.env.REACT_APP_CLOUDINARY_NAME;
const response = await instance.post(
    `https://api.cloudinary.com/v1_1/${cloudName}/upload`,
    formData,
    config
);
```

Content-Type 은 아예 지정하지 않는다. FormData 를 보낼 때는 브라우저가 multipart 경계
(boundary)를 포함한 헤더를 직접 만들어야 하는데, 손으로 지정하면 boundary 가 빠져 서버가
파싱하지 못한다.

같은 파일의 `fetchImageFromURL` 도 고쳤다 — `new Promise(async (resolve, reject) => ...)` 로
감싸고 있어서 오류가 나면 프라미스가 영원히 대기 상태로 남았다(§1 과 같은 패턴).

### `components/checkout/Payment.jsx` — 결제수단 아이콘 404

```jsx
- src={`${process.env.PUBLIC_URL}/public/assets/images/${payment.id}.png`}
+ src={`${process.env.PUBLIC_URL}/assets/images/${payment.id}.png`}
```

`public/` 폴더의 내용은 **루트(`/`)로** 서빙된다. 경로에 `/public` 을 넣으면
`/public/assets/...` 를 찾아 404 가 난다. 결제수단 아이콘이 전부 깨져 있었다. 같은 이미지를
`order/Payment.jsx` 는 `/assets/images/...` 로 올바르게 참조하고 있어서, 두 화면의 표시가
달랐다.

### `util/TokenRefresher.js` — 상대 경로 이동

```jsx
- navRef.current("error_server");      →  + navRef.current("/error_server");
- navRef.current("signin");            →  + navRef.current("/signin");
```

앞에 슬래시가 없으면 현재 경로 기준의 **상대 경로**로 해석된다. `/profile/orders` 에서 503 이
나면 `/profile/error_server` 로 가고, 리프레시 토큰이 만료되면 `/profile/signin` 으로 간다.
둘 다 존재하지 않는 라우트라 오류 화면이 뜬다 — 세션이 만료됐을 때 로그인 화면으로 가지
못한다는 뜻이다.

---

## 21. 🟡 `Array.prototype` 오염과 편향된 셔플

### `util/sort_utils.js`

```js
// 수정 전
Array.prototype.keySort = function(keys) { /* ... */ };
export default Array.prototype.keySort;
```

내장 프로토타입을 직접 고친다. 그러면:

- 이 모듈을 import 하는 것만으로 애플리케이션의 **모든 배열**이 바뀐다. 임포트의 부작용이
  전역이라 어디서 왜 생긴 메서드인지 추적하기 어렵다.
- **열거 가능한** 프로퍼티로 추가되므로 `for (const k in someArray)` 가 인덱스와 함께
  `"keySort"` 까지 돌려준다. 이런 루프가 하나라도 있으면 조용히 깨진다.
- 같은 이름을 쓰는 다른 라이브러리나 미래의 표준 메서드와 충돌한다.

그리고 `export default Array.prototype.keySort` 는 **바인딩되지 않은 함수**여서, import 해서
그냥 호출하면 `this` 가 undefined 라 즉시 터진다. 실제로 이 모듈을 import 하는 곳은 한 군데도
없었다 — 아무도 쓸 수 없는 코드였다.

배열을 인자로 받아 **새 배열**을 돌려주는 평범한 함수로 바꿨다.

### `util/array_utils.js` — `randomize`

```js
// 수정 전
export const randomize = (array) => array.sort(() => 0.5 - Math.random());
```

두 가지가 문제다.

**호출자의 배열을 망가뜨린다.** `sort` 는 제자리 정렬이라 인자로 받은 배열 자체가 바뀐다.
화면에 뿌릴 목록을 섞으려고 불렀는데 원본(로더가 준 데이터, redux 상태)까지 순서가 바뀐다.

**고르게 섞이지 않는다.** 비교 함수는 일관성이 있어야 한다 — 같은 두 원소를 비교하면 항상
같은 답이 나와야 한다. 무작위 비교자는 그 규칙을 깨서, 엔진의 정렬 알고리즘이 어떤 순서를
다른 순서보다 훨씬 자주 만들어 낸다.

```js
// 수정 후 — Fisher–Yates
export const randomize = (array) => {
    const copy = [...array];
    for (let i = copy.length - 1; i > 0; i--) {
        const j = Math.floor(Math.random() * (i + 1));
        [copy[i], copy[j]] = [copy[j], copy[i]];
    }
    return copy;
}
```

같은 파일의 `compareArrays` 는 파라미터 `array1` 을 Set 으로 **재할당**하고 있었다. 이름은
배열인데 값은 Set 이면 나중에 고치는 사람이 반드시 헷갈린다. 지역 변수로 분리했다.

### `hook/hooks.js` — 전역 헤더 오염

```jsx
// 수정 전
mutationFn: () => {
  api.defaults.headers.post["MultiLogin"] = isMultipleLogin ? "true" : "false";
  return postRequest('/user-service/api/auth/logout', null);
},
```

`api.defaults` 는 axios 인스턴스의 **전역 기본값**이다. 로그아웃 한 번이 이후 **모든 POST
요청**의 헤더를 바꿔 놓고 되돌리지 않는다. 요청 단위 헤더로 바꿨다(그래서 §7 에서
`postRequest` 에 `requestConfig` 인자를 추가했다).

---

## 22. 🟡 채팅: 쓰이지 않는 두 번째 리듀서

`redux/ChatSlice.js` 에는 `createSlice` 로 만든 리듀서와, 그 아래 손으로 쓴
`chatReducer` 스위치문(약 40줄)이 **둘 다** 있었다. 스토어가 쓰는 것은 `chatSlice.reducer`
이고, 아래 함수는 어디에도 연결되지 않은 채 **다른 상태 전이 규칙**을 담고 있었다.

채팅 동작을 고치러 온 사람이 둘 중 어느 쪽이 진짜인지 알 수 없고, 실제로는 쓰이지 않는 쪽을
고쳐도 아무 일이 일어나지 않는다. 죽은 리듀서와 그것만 쓰던 액션 타입 import 를 지웠다.
`setRoomConversation` 안의 `console.log` 도 함께 지웠다.

`redux/ChatSelectors.js` 에서는 `conversation` 셀렉터를 지웠다:

```js
export const conversation = (state) => state.chat.conversation;   // 그런 필드가 없다
```

chat 슬라이스에 `conversation` 이라는 필드는 없다(있는 것은 `roomConversation` 뿐이다).
이 셀렉터는 언제나 `undefined` 를 돌려주는데, 이름이 그럴듯해서 "대화 내용을 가져오는
셀렉터"로 잘못 쓰이기 딱 좋았다.

---

## 23. 🟠 만들어 놓고 연결하지 않은 화면

이번 작업에서 추가한 컴포넌트 두 개가 **어디에서도 import 되지 않고** 있었다. 파일은 있지만
사용자가 도달할 수 있는 경로가 없으니 기능이 없는 것과 같다.

### `components/delivery/DeliveryTracker.jsx` → 주문 상세에 연결

```jsx
// pages/order.jsx
{orderData.orderStatus !== "NOT_PROCESSED" && orderData.orderStatus !== "UNPAID" && (
    <div className="mt-4">
        <DeliveryTracker orderId={orderData.id ?? orderId} />
    </div>
)}
```

결제가 끝난 주문에만 보여 준다. 결제 직후에는 아직 delivery 레코드가 없을 수 있는데, 그건
오류가 아니라 "배송 준비 중"이고 `DeliveryTracker` 가 그 상태를 스스로 처리한다.

### `components/notification/SmsVerification.jsx` → 보안 설정에 연결

```jsx
// pages/profile/security.jsx
<div className="mt-10 border-t pt-6">
    <SmsVerification title="휴대폰 인증 (SMS 알림 · 2단계 인증)"
                     onVerified={() => setPhoneVerified(true)} />
</div>
```

notification-service 의 SMS 인증을 쓰는 화면이 지금까지 어디에도 없었다 — 컴포넌트는 있는데
붙일 자리가 없어 **사용자가 번호를 등록할 방법 자체가 없었다.** 보안 설정 페이지가 가장
자연스러운 자리다.

---

## 24. 🟡 게이트웨이 RewritePath 오타 (지금은 우연히 동작)

`gateway-server/src/main/resources/application-local.yml`

```yaml
- RewritePath=/user-service/(?<segment>. *) , /$\{segment}
                                       ↑ 공백
```

`user-service` 라우트 두 곳에만 정규식에 공백이 들어가 있다. 다른 8개 서비스는 전부
`(?<segment>.*)` 로 올바르다.

**정직하게 적자면, 이것 때문에 지금 깨지는 것은 없다.** `RewritePath` 는 내부적으로
`String.replaceAll` 을 쓰는데 이건 앵커되지 않은 치환이라, `. *`(임의의 한 글자 + 공백 0개
이상)이 첫 글자 하나에만 매칭되고 `/user-service/a` → `/a` 로 치환되어 **결과적으로 접두사
제거와 같은 효과**가 난다. 확인해 봤다:

```
'/user-service/api/auth/public/signin'  →  '/api/auth/public/signin'   (오타 버전)
'/user-service/api/auth/public/signin'  →  '/api/auth/public/signin'   (올바른 버전)
```

그래도 고친 이유는, 이게 **우연에 기대고 있기 때문**이다. 경로에 공백이 들어가거나, 필터가
앵커 매칭으로 바뀌거나, 누군가 이 정규식을 복사해 다른 곳에 쓰면 그때 깨진다. 그리고 다음에
이 파일을 읽는 사람은 "왜 여기만 다르지?" 를 다시 조사해야 한다.

```yaml
- RewritePath=/user-service/(?<segment>.*), /$\{segment}
```

---

## 고치지 않은 것들 (의도적)

리뷰하면서 발견했지만 **손대지 않기로** 한 것들이다. 이유를 남긴다.

**`pages/forgot.jsx`** — import 경로가 전부 존재하지 않는다(`@/components/Footer` 등). 어떤
라우트에도 연결돼 있지 않아 번들에 포함되지 않고, 따라서 빌드도 깨지지 않는다. 이 화면을
되살리려면 `ForgotPage` 와의 관계를 정리해야 하는데 그건 기능 결정이지 버그 수정이 아니다.

**`util/loadFake.js`** — 개발용 시드 데이터 생성기다. 상품 20개를 인덱스로 하드코딩해
스무 줄씩 반복하는 등 문제가 많고 `postRequest` 의 세 번째 인자를 config 로 잘못 넘기고
있지만, 아무 데서도 import 되지 않는 개발 도구다. 사용자가 여전히 필요로 할 수 있어 남겼다.

**배열 인덱스를 key 로 쓰는 곳(20여 곳)** — `key={i}` 는 목록의 **순서가 바뀌거나 중간이
삭제될 때** 상태가 엉뚱한 행에 남는 문제를 만든다. 실제로 장바구니(`CartPage`)처럼 삭제가
일어나는 목록에서는 고치는 것이 맞지만, 안정적인 id 가 무엇인지는 백엔드 응답 형태를
확인해야 정할 수 있다. 이번 범위에서 확신을 갖고 고칠 수 있는 곳이 아니라 남겼다.

**`==` 를 `===` 로 바꾸는 것(20여 곳)** — 대부분 문자열/숫자 비교라 실제 동작 차이가 없다.
동작이 바뀌지 않는 변경으로 diff 를 키우면 정작 중요한 수정이 묻힌다.

**`redux/authSlice.js`** — 스토어에 연결돼 있지 않은 채 `AuthContext` 와 같은 역할을 하는
중복 구현이다. 지우는 것이 맞지만, "인증 상태를 Context 로 갈지 Redux 로 갈지"는 설계
결정이라 임의로 한쪽을 없애지 않았다. **다음에 정해야 할 것**으로 남긴다.

**번들 크기** — `main.bundle.js` 가 1.97 MiB 다. 관리자 화면만 lazy 로딩되어 있고 나머지는
전부 한 덩어리다. 라우트 단위 코드 스플리팅으로 크게 줄일 수 있지만 별도 작업이다.

---

## 다음에 할 것

우선순위 순으로.

1. **프론트엔드 테스트가 하나도 없다.** 이번에 고친 것 중 상당수(§10 의 TS 문법, §11 의
   `disabled`, §12 의 할인 계산, §13 의 토글)는 **테스트가 한 줄만 있었어도 즉시 잡혔을**
   종류다. `@testing-library/react` 는 이미 devDependency 에 있다. 최소한 §12 의 `discounted`
   같은 순수 함수부터 시작하면 비용 대비 효과가 크다.
2. **§1·§5·§8 을 실제 환경에서 확인.** PayPal 결제, 2FA 로그인, 관리자 API 경로는 코드
   판단만으로 끝낼 수 없다.
3. **게이트웨이의 `/admin` 권한 검사 확인.** §3 은 UI 가드만 고쳤다. 서버 쪽이 뚫려 있으면
   아무 소용이 없다.
4. **SMS 2FA 를 실제 로그인 경로에 연결.** 지금 2FA 는 TOTP 고, 이번에 만든 SMS 인증은
   번호 등록·알림 수신용이다. 둘을 잇는 백엔드 작업이 남아 있다.
5. **인증 상태 이중화 정리** — `AuthContext` 와 `redux/authSlice` 중 하나로.
