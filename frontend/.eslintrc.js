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
 *
 * <p>[JA] docs/40 #18 — 「使っていない import が多い。ESLint no-unused-vars を CI で有効にすること。」
 * 🔴 今有効にすると警告が 198 個である。すべて error に上げると CI が初日から赤くなり、そうなると人は規則を直す代わりに**規則を切る。**
 * 逆に警告だけ出して CI を通すならそれは飾りである — 誰も読まない。
 * そこでラチェット(ratchet)で掛ける。package.json の lint スクリプトが `--max-warnings` を現在の数に固定するので、**新しい警告が一つでも出れば CI が赤くなる。**
 * 既存の 198 個は直すたびにその数を下げてロックする。数は上がらない。
 * `args: "none"` — 使わないコールバック引数まで捕まえると `(e, i) => ...` のようなよくある形がすべて引っかかり、S/N 比が崩れる。
 * 捕まえたいのは「宣言しておいて使わない import・変数」である。checkout.jsx が `@reduxjs/toolkit` の isPending を見当違いに import していたのが、
 * まさにこの規則が捕まえるべき種類である。
 */
module.exports = {
  root: true,
  extends: ["react-app"],
  rules: {
    "no-unused-vars": ["warn", { args: "none", ignoreRestSiblings: true }],
  },
};
