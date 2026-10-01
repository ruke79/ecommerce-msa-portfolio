# 프론트엔드 개선 내역 (What Changed)

[日本語](05-frontend-improvements.md) | **한국어**

> 🕰️ **상태(2026-09-27) — 이전 기록.** 로드맵으로 둔 FE-6 · FE-9 도 이후 고쳤습니다(24d06b4). 프론트 테스트는 [docs/28](28-frontend-test-suite.ko.md) 부터 — 지금 930건.

> 대상: `frontend/` · 관련: [문제점 분석](04-frontend-issues.ko.md)

[`docs/04`](04-frontend-issues.ko.md)에서 도출한 문제를 근거로 한 실제 변경 목록이다.
원칙은 백엔드 개선과 동일하다: **런타임에서 검증 가능하고 회귀 위험이 낮은 변경만 적용**하고, 서버 협업이 필요한 항목(CSRF)은 로드맵으로 남긴다. 프론트는 당시 검증 환경에서 전체 빌드(node_modules 부재)를 돌릴 수 없었으므로, 변경한 순수 JS는 `node --check`로 문법 검증하고 나머지는 최소·정밀 편집으로 제한했다.

---

## 1. 액세스 토큰 저장 강제 — localStorage footgun 제거 🟠 (FE-1)

`frontend/src/util/tokenUtil.js`
- localStorage 기반 `getToken/updateToken/removeToken`, `isAdmin/setAdmin/getUser/setUser`를 **전부 제거**.
- `remove()`는 유지하되 `localStorage.clear()` → **레거시 키(`access_token`, `USER`, `IS_ADMIN`)만 선별 제거**로 변경(무관한 redux-persist 상태 보존) + storage 접근 불가 환경(사파리 프라이빗 등) 방어.

`frontend/src/store/AuthContext.js`
- 사용되지 않던 `TokenUtil.isAdmin()` 계산 라인과 `tokenUtil` import를 제거.

**효과**: 액세스 토큰의 "메모리 전용" 보장이 이제 **코드로 강제**된다(토큰을 localStorage에 넣는 경로 자체가 존재하지 않음 → XSS 토큰 탈취 표면 제거).

## 2. axios 요청 래퍼 정상화 + 요청 타임아웃 🔴 (FE-2, FE-7)

`frontend/src/util/api.js`
- `axios.create(...)`에 **`timeout: 15000`** 추가 → 요청 무한 대기(탭 hang) 차단.
- 요청 래퍼(get/post/put/patch/delete)를 **axios 시그니처에 맞게 재작성**:
  - GET/DELETE `(url, config)`, POST/PUT/PATCH `(url, data, config)`.
  - `contentType`은 `withContentType()` 헬퍼로 **per-request 헤더**에만 반영.
- **`validateStatus`(status<500) 미적용**을 의도적으로 유지: 이를 적용하면 4xx가 정상 resolve되어 **401 자동 리프레시 인터셉터가 깨진다**. 즉 원래 코드가 넣으려던 config는 인터셉터 설계와 상충 → 정답은 timeout뿐임을 반영.
- 깨져 있던(그리고 미사용이던) `axiosRetry`를 **제거**.

**효과**: per-request 설정이 실제로 적용되고, 느린 백엔드에서도 요청이 15초 내 실패로 귀결된다. 401 리프레시 흐름은 그대로 보존.

## 3. 프로액티브 리프레시 타이머 버그 수정 🔴 (FE-3)

`frontend/src/util/TokenRefresher.js`
```diff
- }, new Date(expiresAt).getTime() * 1000 - Date.now() - 10 * 1000);
+ const delay = Math.max(0, expiresAt * 1000 - Date.now() - 10 * 1000);
+ setTimeout(() => refreshAccessToken(), delay);
```
- `expiresAt`(JWT `exp`, 초 단위)을 올바르게 ms로 변환하고 음수 지연을 0으로 클램프.

**효과**: 만료 **10초 전 선제 갱신**이 실제로 동작 → 401 반응형 경로에만 의존하지 않아 사용자 요청 중 토큰 만료로 인한 순간 실패가 감소.

## 4. 토큰/응답 콘솔 로그 유출 제거 🟠 (FE-4)

- `TokenRefresher.js`: 리프레시 응답 전체를 찍던 `console.log(response)`, `console.log(response.status)` 제거(응답 헤더의 액세스 토큰 노출 차단).
- `api.js`: `loadCheckOut`의 `console.log(data)`, `getOrder`의 `console.log(orderId)` 제거.
- `hooks.js`: `useFetchCoupons`의 쿠폰 응답 로그 제거.

## 5. 401 리프레시 큐 데드락 수정 🟠 (FE-5)

`frontend/src/util/TokenRefresher.js` 401 처리 재작성:
- 리프레시 응답이 **200이 아닐 때**(예: 204) 대기 큐를 **모두 reject + 로그아웃 + reject 반환** → 기존의 "큐에 push만 하고 아무도 resolve 안 하는" **영구 hang 제거**.
- `originalConfig._retry` 플래그로 **무한 리프레시 루프 방지**.
- 불필요하던 자기 자신 큐 push(`refreshPromise`) 제거로 흐름 단순화. 동시 401 요청은 여전히 큐에서 대기 후 새 토큰으로 1회 재시도.

## 6. 의존성 위생 🟡 (FE-8)

`frontend/package.json`에서 **소스 어디서도 import하지 않는** 패키지 제거:
- `save-dev@^0.0.1-security` (오설치된 보안-홀드 플레이스홀더)
- `toast@^0.5.4` (미사용, `react-hot-toast` 사용 중)

---

## 로드맵으로 남긴 항목

| 항목 | 이유 |
|---|---|
| **FE-6 에러 스왈로잉 전면 정리** | `catch → return undefined` 다수를 throw로 바꾸면 React Query 에러 바운더리로 전파되나, 다수 호출부가 undefined를 허용하도록 작성돼 있어 **광범위 회귀 위험**. 핵심 경로부터 점진 적용 필요(전체 빌드/E2E 검증 전제). |
| **FE-9 redux-persist 정리** | `whitelist:['auth']`가 존재하지 않는 리듀서를 가리켜 사실상 무효. 이를 고치면 cart 등이 새로 영속화되는 **동작 변화**가 생길 수 있어, 의도 확인 전까지 런타임 동작을 바꾸지 않고 문서화만. |
| **FE-10 CSRF** | 리프레시 쿠키에 `SameSite`/CSRF 방어는 **서버 쿠키 설정** 책임. 프론트는 서버 정책 확정 후 커스텀 헤더 토큰 연동. → ✅ 해결: 서버 쿠키를 `SameSite=Lax` 로 명시([docs/62 §8](62-engineering-casebook.ko.md)). 액세스 토큰은 헤더로 다니므로 커스텀 헤더 토큰은 두지 않는다. |
| 프로덕션 로깅 게이트 | 남은 `console.log("erorr >>>", ...)` 다수를 `NODE_ENV!=='production'` 게이트 로거로 일괄 치환(별도 정리 PR 권장). |

---

## 검증

- `src/util/api.js`, `src/util/tokenUtil.js`: `node --check` **통과**.
- `TokenRefresher.js`, `AuthContext.js`(JSX): 중괄호/괄호/대괄호 밸런스 확인.
- `package.json`: 유효 JSON 확인, 제거한 패키지 **미import** 확인.
- 요청 래퍼 시그니처 변경이 기존 호출부(2-arg 이하)와 호환됨을 grep으로 확인.
- ⚠️ 인증 인터셉터(401 리프레시)는 auth-critical 경로이므로, 병합 전 **로그인/토큰만료/동시요청 시나리오 수동 QA**를 권장.
