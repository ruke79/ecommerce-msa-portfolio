/**
 * 브라우저 번들에 박히는 환경변수 — dev·prod 설정이 같은 것을 쓴다.
 *
 * 🐛 예전에는 `EnvironmentPlugin([네 개 이름])` 이었다. 그 목록에 없는
 * `process.env.REACT_APP_STORE_CURRENCY`(util/money.js, 모듈 최상단) 는 치환되지 않은 채
 * 번들에 남았고, 브라우저에는 `process` 가 없으므로 첫 화면에서
 *
 *     Uncaught ReferenceError: process is not defined
 *
 * 로 **앱 전체가 죽었다.** 이름을 하나 쓸 때마다 목록에도 적어야 하는 규칙은 지켜지지
 * 않는다 — 코드는 통과하고(테스트는 Node 라 process 가 있다) 운영에서만 터진다.
 *
 * 그래서 목록을 없앤다. 빌드 시점 환경의 `REACT_APP_*` 를 **전부** 싣고, 없는 이름은
 * `undefined` 가 되게 `process.env` 자체를 객체로 정의한다(DefinePlugin 의 중첩 객체 —
 * `process.env.X` 도, 맨몸의 `process.env` 도 이 객체로 바뀐다). CRA 가 하는 방식이다.
 *
 * 🔒 여기 실리는 값은 전부 **공개**된다(B-51). REACT_APP_ 접두사가 그 약속이다 —
 *    비밀은 절대 이 접두사로 부르지 않는다.
 */
function clientEnv(nodeEnv) {
  const env = {};
  for (const key of Object.keys(process.env)) {
    if (key.startsWith("REACT_APP_")) {
      env[key] = JSON.stringify(process.env[key]);
    }
  }
  env.PUBLIC_URL = JSON.stringify(process.env.PUBLIC_URL ?? "");
  env.NODE_ENV = JSON.stringify(nodeEnv);
  return { "process.env": env };
}

module.exports = { clientEnv };
