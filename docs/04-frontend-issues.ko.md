# 프론트엔드 문제점 분석 (Production Readiness Review)

[日本語](04-frontend-issues.md) | **한국어**

> 🕰️ **상태(2026-09-27) — 이전 기록.** FE-1~9 는 [05](05-frontend-improvements.ko.md) · [26](26-frontend-review-and-refactor.ko.md) 에서 고쳤습니다. FE-10(CSRF)은 쿠키·CSRF 정책 강화 과제로 이어집니다([docs/62 §8](62-engineering-casebook.ko.md)).

> 작성일: 2026-07-24
> 대상: `frontend/` (React 18 SPA)
> 관점: 보안 · JWT 메모리 저장/재요청(refresh) 로직 · 프로덕션 견고성
> 관련: [개선 결과 문서](05-frontend-improvements.ko.md)

이 문서는 **개선 이전** 프론트엔드의 문제를 파일·라인 근거로 기록한다.

---

## 0. 요약

먼저 **좋은 점**부터: 런타임 실제 경로에서 **액세스 토큰은 메모리(AuthContext reducer)에만** 보관된다. 로그인 흐름(`SignInPage.jsx`의 `login(user, accessToken, exp)`)은 localStorage에 토큰을 쓰지 않으며(해당 라인 107-108은 주석 처리됨), axios 요청 인터셉터는 메모리의 `tokenRef.current`만 사용한다. 리프레시 토큰은 HttpOnly 쿠키(서버) + `withCredentials`로 자동 전송된다. **즉 README가 표방한 "액세스=메모리 / 리프레시=HttpOnly 쿠키" 설계는 런타임에서 지켜지고 있다.**

문제는 그 설계가 **코드로 강제되지 않고**, 재요청/타임아웃/에러 처리 등 **프로덕션 견고성**에 결함이 있다는 점이다.

| # | 문제 | 심각도 | 성격 |
|---|---|---|---|
| FE-1 | `tokenUtil.js`에 localStorage **토큰 접근자 잔존**(dead but footgun) | 🟠 보안 | XSS 토큰 탈취 재도입 위험 |
| FE-2 | `api.js` 요청 래퍼의 **axios 인자 오용 → per-request config 미적용**, 인스턴스 **timeout 부재** | 🔴 정확성 | 요청 무한 대기 가능 |
| FE-3 | 프로액티브 **리프레시 타이머 시간 계산 버그**(exp 초/ms 혼동) | 🔴 정확성 | 자동 갱신 사실상 미동작 |
| FE-4 | **토큰/응답 콘솔 로그 유출**(프로덕션 빌드 포함) | 🟠 보안 | 토큰/PII 노출 |
| FE-5 | 401 리프레시 큐 **엣지 데드락**(비-200 응답 시) | 🟠 정확성 | 요청 영구 hang |
| FE-6 | api 함수 **에러 스왈로잉**(`catch → return undefined`) | 🟡 품질 | 실패 은폐, 하위 크래시 |
| FE-7 | `axiosRetry` **깨진 재시도** 로직(미사용 잔존) | 🟡 품질 | 죽은 코드 |
| FE-8 | **정체불명/미사용 의존성**(`save-dev`, `toast`) | 🟡 공급망 | 위생 |
| FE-9 | **죽은 코드/설정**(redux-persist whitelist, 미사용 isAdmin 등) | 🟡 품질 | 혼란 유발 |
| FE-10 | **CSRF** 방어(리프레시 쿠키) — 프론트 커스텀 헤더 부재 | 🟠 보안(참고) | 서버 쿠키 설정 의존 |

---

## FE-1. localStorage 토큰 접근자 잔존 (footgun) 🟠

`frontend/src/util/tokenUtil.js:3-13`
```js
getToken()   { return localStorage.getItem("access_token"); }
updateToken(token) { localStorage.setItem("access_token", token); }
removeToken(){ localStorage.removeItem("access_token"); }
```
- 현재 **호출되지 않는 죽은 코드**(로그인/ProtectedRoute의 localStorage 경로는 모두 주석 처리됨).
- 그러나 **살아있는 공개 API**로 남아 있어, 누군가 "편의상" 다시 쓰면 즉시 **XSS 토큰 탈취** 표면이 열린다. "메모리 저장" 보장이 *부재로써 강제*되지 않는다.
- 함께: `isAdmin()/setAdmin()`은 `IS_ADMIN`을 localStorage에서 읽지만 **`setAdmin`을 호출하는 곳이 없어** 항상 null(죽은 코드), 그럼에도 `AuthContext.js:87-88`이 이를 읽어 미사용 변수에 담는다.

## FE-2. api.js 요청 래퍼 결함 → config 미적용 & 무한 대기 🔴

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
- axios 시그니처는 `get(url, config)`, `post(url, data, config)`. 위 코드는:
  - GET: 3·4번째 인자(`contentType`, 실제 config 객체)가 **무시** → 의도한 `timeout: 10s`, `validateStatus`가 **전혀 적용되지 않음**.
  - POST/PUT/PATCH: `contentType`(문자열)이 **config 위치**에 전달되는 오용.
- 게다가 `axios.create(...)`에 **`timeout`이 없어**, per-request timeout도 안 먹으므로 **요청이 무한 대기**할 수 있다(느린 게이트웨이/서비스 시 브라우저 탭 hang). 프로덕션에서 치명적.
- (역설) 만약 `validateStatus: status < 500`이 실제로 적용됐다면, 4xx가 **정상 resolve**되어 아래 401 자동 리프레시 인터셉터(에러 콜백)가 **동작하지 않게 된다**. 즉 이 config는 애초에 인터셉터 설계와 상충 → **정답은 validateStatus가 아니라 timeout**이다.

## FE-3. 프로액티브 리프레시 타이머 시간 계산 버그 🔴

`frontend/src/util/TokenRefresher.js` (proactive refresh timer)
```js
refreshAccessTokenTimerId = setTimeout(() => { refreshAccessToken(); },
  new Date(expiresAt).getTime() * 1000 - Date.now() - 10 * 1000);
```
- `expiresAt = decodedToken.exp` → **초(seconds) 단위 epoch 숫자**.
- `new Date(expiresAt)`는 인자를 **밀리초로 해석** → 1970-01-01 근처의 잘못된 시각. 여기에 `* 1000`을 **또** 곱해 값이 완전히 어긋난다.
- 결과: 지연값이 음수(→ 즉시 발화) 또는 비정상적으로 큰 값 → **만료 10초 전 자동 갱신이 사실상 깨져 있고**, 오직 401 반응형 경로에만 의존한다. 올바른 식은 `expiresAt * 1000 - Date.now() - 10000`.

## FE-4. 토큰/응답 콘솔 로그 유출 🟠

- `TokenRefresher.js`: `console.log(response.status)`, `console.log(response)` — **응답 헤더의 `access`(액세스 토큰)** 가 콘솔에 그대로 찍힌다.
- `api.js`: `loadCheckOut`의 `console.log(data)`, `getOrder`의 `console.log(orderId)` 등 데이터/PII 로그가 다수.
- CRA/webpack 프로덕션 빌드는 `console.log`를 제거하지 않으므로 **운영 콘솔에 토큰·주문정보가 노출**된다.

## FE-5. 401 리프레시 큐 엣지 데드락 🟠

`TokenRefresher.js` 401 처리: 리프레시 요청 응답이 `200`이 아닌 경우(예: 리프레시 토큰 없음 → `204`), `if (response.status == 200)` 블록을 건너뛰고 **try 내부에서 return하지 않은 채** 통과한다. 이후 하단의
```js
return new Promise((resolve, reject) => { refreshAndRetryQueue.current.push({ resolve, reject }); })
```
로 큐에 push되지만 이를 resolve할 주체가 없어 **해당 요청이 영구 hang**한다. (200 실패 처리 부재 + 무한 루프 방지 플래그 부재)

## FE-6. api 함수 에러 스왈로잉 🟡

`api.js`의 대부분 함수가
```js
catch (error) { console.log("erorr >>>", error.response?.data.message); }
```
로 끝나 **undefined를 반환**한다. React Query는 `throwOnError:true`인데 함수가 throw하지 않으니 **에러 바운더리가 잡지 못하고**, 호출부는 `undefined`를 구조분해/`.map`하다 크래시한다. 실패가 조용히 사라지는 안티패턴.

## FE-7. axiosRetry 깨진 재시도 🟡

`api.js`의 `axiosRetry`는 `setTimeout(() => { return axiosRetry(...) }, delay)`로, **setTimeout 콜백에서 return**하므로 재시도 결과가 상위 프로미스로 연결되지 않는다(프로미스 미해결). 현재 미사용이나 잔존.

## FE-8. 정체불명/미사용 의존성 🟡

`frontend/package.json`
- `"save-dev": "^0.0.1-security"` — `npm i save-dev` 오타 설치로 들어온 **보안-홀드 플레이스홀더 패키지**. 무의미/오해 소지.
- `"toast": "^0.5.4"` — 코드는 `react-hot-toast`만 import. **미사용**.
- 소스 어디서도 두 패키지를 import하지 않음 → 제거 안전.

## FE-9. 죽은 코드/설정 🟡

- `redux/store.js`: `persistConfig.whitelist: ['auth']`인데 `auth` 리듀서는 `rootReducer`에서 **주석 처리**되어 존재하지 않음. `blacklist: ['token']`도 중첩 키에 미적용 → **의미 없는 설정**.
- `AuthContext.js:86-89`: `isADmin` 계산 후 어디서도 사용 안 함.
- `ProtectedRoute.jsx` 하단: localStorage/`console.log` 기반 죽은 주석 블록.

## FE-10. CSRF (참고) 🟠

`withCredentials: true`로 **리프레시 토큰 쿠키가 자동 전송**된다. 리프레시 엔드포인트는 상태 변경(rotation)이므로, 쿠키 기반 인증 표면에 대한 **CSRF 방어**(SameSite=strict/lax + 필요 시 커스텀 헤더/토큰)가 중요하다. 이는 주로 **서버 쿠키 설정** 책임이며, 프론트는 커스텀 헤더 CSRF 토큰을 사용하지 않는다. → [`docs/05`](05-frontend-improvements.ko.md) 로드맵에 명시.

---

## 개선 우선순위

- **즉시 수정(안전·고가치)**: FE-1(토큰 접근자 제거), FE-2(timeout+인자 수정), FE-3(타이머 버그), FE-4(로그 유출), FE-5(데드락), FE-8(정크 의존성).
- **부분 수정/문서화**: FE-6(핵심 경로만), FE-7(제거), FE-9(정리).
- **서버 협업 필요**: FE-10(CSRF 쿠키 정책).

실제 적용 내역은 [`docs/05-frontend-improvements.md`](05-frontend-improvements.ko.md).
