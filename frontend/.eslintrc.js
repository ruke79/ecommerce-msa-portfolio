/**
 * docs/40 #18 — "안 쓰는 import 가 많다. ESLint no-unused-vars 를 CI 에서 켤 것."
 *
 * 🔴 지금 켜면 경고가 198개다. 전부 error 로 올리면 CI 가 첫날부터 빨갛고, 그러면 사람들은
 * 규칙을 고치는 대신 **규칙을 끈다.** 반대로 경고만 찍고 CI 를 통과시키면 그건 장식이다 —
 * 아무도 읽지 않는다.
 *
 * 그래서 래칫(ratchet)으로 건다. package.json 의 lint 스크립트가 `--max-warnings` 를 현재
 * 개수로 고정하므로 **새로 생긴 경고 하나면 CI 가 빨개진다.** 기존 198개는 고칠 때마다 그
 * 숫자를 내려서 잠근다. 숫자는 올라가지 않는다.
 *
 * `args: "none"` — 쓰지 않는 콜백 인자까지 잡으면 `(e, i) => ...` 같은 흔한 형태가 전부
 * 걸려 신호 대 잡음비가 무너진다. 잡고 싶은 것은 "선언해 놓고 안 쓰는 import·변수" 다.
 * checkout.jsx 가 `@reduxjs/toolkit` 의 isPending 을 엉뚱하게 import 하고 있던 것이
 * 정확히 이 규칙이 잡았어야 할 종류다.
 */
module.exports = {
  root: true,
  extends: ["react-app"],
  rules: {
    "no-unused-vars": ["warn", { args: "none", ignoreRestSiblings: true }],
  },
};
