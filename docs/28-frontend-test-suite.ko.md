# 28. 프론트엔드 테스트 도입 — 그리고 곧바로 잡힌 버그들

[日本語](28-frontend-test-suite.md) | **한국어**

> 🕰️ **상태(2026-09-27) — 이전 기록.** CRA 를 걷어내 jest 는 직접 의존성입니다. 테스트는 지금 930건입니다.

docs/26 의 마지막 절에서 "프론트엔드 테스트가 하나도 없다"를 최우선 과제로 꼽았다. 이 문서는
그 작업의 결과다.

**결론부터: 테스트를 붙이자마자 새 버그가 나왔다.** 그중 하나는 회원가입을 사실상 불가능하게
만드는 것이었고, 빌드도 통과하고 코드를 읽어서도 보이지 않는 종류였다.

---

## 1. 무엇을 깔았나

새 의존성을 **하나도 설치하지 않았다.** jest 27, jest-environment-jsdom, `@testing-library/*`,
identity-obj-proxy, babel-jest 는 `react-scripts` 를 통해 이미 `node_modules` 에 있었다.
네트워크가 막힌 환경에서도 `npm test` 가 바로 돌아야 하기 때문이다.

| 파일 | 역할 |
|---|---|
| `jest.config.js` | jsdom 환경, webpack alias 와 같은 모듈 해석, CSS·이미지 목 |
| `src/setupTests.js` | jest-dom 매처, 미처리 프라미스 거부 노출, 알려진 소음만 선별 차단 |
| `src/__mocks__/fileMock.js` | 이미지·폰트 import 대체 |
| `package.json` | `npm test`, `test:watch`, `test:coverage` |

두 가지는 설명이 필요하다.

**`moduleDirectories: ["node_modules", "src"]`** — webpack 의 alias(`api`, `components`,
`util` …)가 전부 `src` 하위를 가리키므로 이 한 줄로 같은 해석 규칙이 된다. `jsconfig.json` 의
`baseUrl` 은 **에디터 전용**이라 jest 에는 아무 영향이 없다(docs/26 에서 웹팩 alias 를 따로
등록해야 했던 것과 같은 이유다).

**axios 를 CJS 빌드로 매핑** — axios 1.x 의 진입점은 ESM 이고 jest 27 은 `node_modules` 를
변환하지 않아 `Cannot use import statement outside a module` 로 죽는다. 같은 버전이 함께
배포하는 `axios/dist/node/axios.cjs` 를 가리켜 해결했다.

**소음은 선별해서만 막았다.** `console.error` 를 통째로 죽이면 PropTypes 경고나 unmounted
setState 같은 진짜 신호까지 사라진다. RTL 13.4 + React 18.3 의 `act` deprecation 과
react-router 의 v7 예고 경고, 이 두 가지 문자열만 걸러 낸다.

---

## 2. 🐛 테스트가 곧바로 잡아낸 것 — 입력한 글자가 사라진다

가장 중요한 발견이다. **테스트를 쓰다가 우연히 나온 게 아니라, 첫 폼 테스트가 바로 실패했다.**

로그인 폼에 `a@example.com` 을 입력했는데 화면에 남은 값은 이랬다:

```
SignInPage   : "a@example.com"  →  "aeapecm"
RegisterPage : "a@example.com"  →  "@xml.o"
```

### 왜 그랬나

폼 값의 **출처가 둘**이었다.

```jsx
// 페이지: 자기 state 를 두고, 그 값을 Formik 의 initialValues 로 넘긴다
const [user, setUser] = useState(initialUser);
const { email, password } = user;

<Formik
  enableReinitialize                    // ← 키 입력마다 폼을 초기화한다
  initialValues={{ email, password }}   // ← 페이지 state 를 가리킨다
  ...
>
  <LoginInput name="email" onChange={handleChange} />   // ← 페이지의 onChange
```

```jsx
// LoginInput: field 를 먼저 펼치고 props 를 나중에 펼친다
const [field, meta] = useField(props);
<input {...field} {...props} />        // ← props.onChange 가 field.onChange 를 덮어쓴다
```

연쇄가 이렇게 된다.

1. `{...props}` 가 뒤에 오므로 페이지의 `onChange` 가 **Formik 의 onChange 를 덮어쓴다.**
   → Formik 은 사용자가 뭘 입력했는지 모른다.
2. 그런데 `value` 는 여전히 `{...field}` 에서 온다 → **Formik 이 값을 소유한 컨트롤드 인풋**이다.
3. 페이지 state 가 바뀌면 `enableReinitialize` 가 Formik 을 새 `initialValues` 로 리셋한다.
4. 이 왕복이 한 렌더씩 밀리면서 키 입력이 서로를 덮어쓴다 → **글자가 유실된다.**

### 왜 지금까지 아무도 몰랐나

**사람이 손으로 천천히 치면 잘 드러나지 않는다.** 렌더가 따라잡을 시간이 있기 때문이다.
`userEvent.type` 은 사람보다 빠르게 입력해서 이 경합을 그대로 노출한다.

그리고 이건 **코드를 읽어서 보이는 버그가 아니다.** 각 조각은 다 그럴듯하다 — 상태를 페이지에
두는 것도, `enableReinitialize` 도, 재사용 가능한 입력 컴포넌트도. 문제는 셋의 상호작용이다.
빌드도 통과하고 린트도 통과한다.

### 어떻게 고쳤나

**Formik 하나가 폼 값을 소유하게 한다.**

```jsx
// 수정 후
<Formik
  initialValues={{ email: "", password: "" }}   // 정적
  validationSchema={loginValidation}
  onSubmit={signInHandler}                       // Formik 이 검증한 values 를 그대로 받는다
>
  <LoginInput name="email" placeholder="..." />  // onChange 를 내려보내지 않는다
```

같은 형태가 **9개 파일**에 있었고, 그중 폼 값을 다루는 4개를 고쳤다.

| 파일 | 영향 |
|---|---|
| `pages/user/SignInPage.jsx` | 로그인 |
| `pages/user/RegisterPage.jsx` | **회원가입(가장 심각 — 정상 이메일 입력 자체가 불가)** |
| `pages/profile/security.jsx` | 비밀번호 변경 |
| `pages/user/ForgotPage.jsx` | 비밀번호 찾기 |

`security.jsx` 와 `ForgotPage.jsx` 는 핸들러가 페이지 state 를 읽고 있었으므로, Formik 의
`values` 를 받도록 함께 고쳤다 — 그러지 않으면 **빈 값이 서버로 전송**된다.

> 남은 곳: `components/checkout/AddShipping.jsx`(입력 8개), `CreateProduct`, `CreateCategory`,
> `CreateSubCategory`, `Summary`. 같은 형태지만 상태 구조가 더 얽혀 있어 이번 범위에서
> 손대지 않았다. **AddShipping 은 배송 주소를 받는 화면이라 우선순위가 높다** — §6 에 남겼다.

---

## 3. 🐛 회원가입이 한글 이름을 거부했다

위 버그를 고치고 나니 회원가입 테스트가 여전히 실패했다. 원인은 별개였다.

```js
username: Yup.string()...matches(/^[aA-zZ]/, "Numbers and Special characters are not allowed")
```

`"홍길동"` 이 이 검증을 통과하지 못한다. **한국어 UI 에, 한국 택배사와 한국 SMS 를 쓰는
서비스인데 한글 이름으로는 가입할 수 없었다.**

같은 정규식에 두 번째 문제도 있다. 문자 클래스 `A-z` 는 ASCII 에서 `Z` 와 `a` 사이에 있는
`` [ \ ] ^ _ ` `` 까지 포함한다. 즉 "특수문자 금지"라고 말하면서 `_홍길동` 같은 값은 통과시켰다.

의도("숫자·특수문자 금지")를 그대로 옮겼다:

```js
.matches(/^[\p{L}][\p{L}\s]*$/u, "Numbers and Special characters are not allowed")
```

유니코드 글자와 공백만 허용하므로 한글·일본어·라틴 문자가 모두 통과하고 숫자·기호는 걸린다.

---

## 4. 무엇을 테스트했나

**129개 테스트, 15개 파일.** 테스트를 위한 테스트가 아니라 *실제로 고장 났던 것*과 *고장 나면
사용자가 바로 다치는 것*을 골랐다.

| 파일 | 개수 | 지키는 것 |
|---|---|---|
| `util/jwtUser.test.js` | 12 | `role`(단수) 클레임 파싱 — 틀리면 관리자가 차단된다 |
| `util/array_utils.test.js` | 9 | `randomize` 가 원본을 훼손하지 않고 실제로 섞는다 |
| `util/pricing.test.js` | 6 | 할인 계산 — 50% 할인이 50% 할인이어야 한다 |
| `util/api.test.js` | 12 | 오류를 삼키지 않는다, 신원을 쿼리로 보내지 않는다 |
| `api/deliveryApi.test.js` | 10 | 409 = "이미 발송됨", 400 과 구분 |
| `api/notificationApi.test.js` | 7 | 429 = 정상적인 제한, 남은 시도 횟수 전달 |
| `components/ProtectedRoute.test.jsx` | 8 | 비로그인 차단, **관리자 통과**, 세션 복구 중 대기 |
| `components/delivery/DeliveryTracker.test.jsx` | 13 | 단계 접기, 분할배송, 통관, 취소 거절 사유 |
| `components/notification/NotificationBell.test.jsx` | 12 | 배지, 열 때만 목록 요청, 삭제가 이동으로 번지지 않음 |
| `components/notification/SmsVerification.test.jsx` | 9 | 단계 전환, 쿨다운, 남은 시도, 클라이언트 검증 안 함 |
| `components/admin/DashboardLayout.test.jsx` | 2 | 사이드바 렌더, 화면에 `//` 안 찍힘 |
| `pages/user/ButtonInput.test.jsx` | 5 | **`disabled` 가 실제로 적용된다** |
| `pages/user/SignInPage.test.jsx` | 7 | 글자 유실 없음, 실패 사유 표시, 2FA 단계 |
| `pages/user/RegisterPage.test.jsx` | 6 | 글자 유실 없음, 실패를 성공으로 위장하지 않음 |
| `redux/store.test.js` | 11 | persist 가 cart 를 실제로 저장, auth 는 저장하지 않음 |

### 테스트를 쓰면서 코드도 하나 옮겼다

`discounted()` 가 `pages/Product.jsx` 안에 있어서, 순수 계산 하나를 검증하려고
Header → AuthContext → axios → ProductPage → Swiper 까지 전부 끌고 와야 했다. `util/pricing.js`
로 옮겼다. **테스트하기 어렵다는 신호는 대개 설계 신호다** — 계산 규칙은 화면과 무관하므로
화면 모듈에 묶여 있을 이유가 없었다.

---

## 5. CI

`.github/workflows/build.yml` 의 프론트엔드 잡에 테스트를 넣었고, **빌드보다 먼저** 돌린다.

이번에 발견된 것들은 전부 `npm run build` 가 통과하는 코드였다 — 글자 유실도, `disabled`
무시도, 할인 계산 오류도, 권한 가드 문제도. 빌드는 이런 걸 잡지 못한다.

---

## 6. 남은 일

1. **`AddShipping.jsx`** — 같은 이중 상태 형태에 입력이 8개다. 배송 주소를 받는 화면이라
   글자 유실이 그대로 **오배송**이 된다. 다음 우선순위.
2. **관리자 폼 3종**(`CreateProduct`, `CreateCategory`, `CreateSubCategory`) — 같은 형태.
3. **`LoginInput` 자체를 고치는 방안** — 지금은 `{...field} {...props}` 순서 때문에 호출부가
   실수로 Formik 을 덮어쓸 수 있다. 순서를 뒤집거나 `onChange` 를 막으면 같은 실수가 구조적으로
   불가능해진다. 다만 8개 호출부에 영향이 있어 별도로 다룬다.
4. **커버리지 목표는 아직 걸지 않았다.** 숫자를 먼저 정하면 의미 없는 테스트로 채우게 된다.
   지금은 "고장 났던 것 + 고장 나면 아픈 것"을 기준으로 늘리는 편이 낫다.
5. **E2E(Playwright)** — 이 문서의 테스트는 전부 컴포넌트 단위다. 게이트웨이를 거치는
   진짜 흐름은 여전히 검증되지 않았다(docs/27 §검증 범위 참조).

---

## 덧붙임: 이 작업이 실제로 증명한 것

docs/26 에서 이렇게 적었다 — *"이번에 고친 것 중 상당수는 테스트가 한 줄만 있었어도 즉시
잡혔을 종류다."*

그건 이미 고친 버그를 두고 한 사후 평가였다. 이번에는 **테스트를 먼저 붙였더니 아무도 모르던
버그가 두 개 나왔고, 그중 하나는 회원가입을 막고 있었다.** 그리고 둘 다 코드 리뷰로는 보이지
않는 종류였다 — 하나는 렌더 타이밍 경합이었고, 하나는 정규식과 언어의 상호작용이었다.
