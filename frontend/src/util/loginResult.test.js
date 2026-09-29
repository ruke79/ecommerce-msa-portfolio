import { loginResultMessage } from "util/loginResult";
import ko from "i18n/messages/ko";
import ja from "i18n/messages/ja";

/**
 * 🐛 로그인 직후에만 영어가 떴다. {@code toast.success(response.data.message)} 가 서버가
 * 만든 영어 문장을 그대로 띄웠기 때문이다 — 특히 다중 로그인 안내가
 * "User attempted to log in multiply, logging out from previous logged in state" 였다.
 */
describe("로그인 결과 문구", () => {
  // 실제 사전으로 조회한다. 가짜 t 를 쓰면 키가 사전에 없는 것을 못 잡는다.
  const t = (dict) => (key) => dict[key] ?? key;

  it("코드를 사전 문구로 바꾼다", () => {
    expect(loginResultMessage({ code: "login_success" }, t(ko))).toBe("로그인되었습니다.");
    expect(loginResultMessage({ code: "login_success" }, t(ja))).toBe("ログインしました。");
  });

  it("다중 로그인 안내가 영어로 새지 않는다", () => {
    const data = {
      code: "multi_login",
      message: "User attempted to log in multiply, logging out from previous logged in state",
    };

    for (const dict of [ko, ja]) {
      const shown = loginResultMessage(data, t(dict));
      expect(shown).not.toContain("log in multiply");
      expect(shown).toBe(dict["auth.signin.result.multi_login"]);
    }
  });

  /**
   * 🔴 t() 는 없는 키를 받으면 **키를 그대로 돌려준다.** 모르는 코드를 그냥 넘기면
   * 토스트에 "auth.signin.result.xyz" 가 뜬다 — 서버 문장보다 나쁘다.
   */
  it("모르는 코드면 서버 문구로 떨어진다 — 키가 노출되지 않는다", () => {
    const shown = loginResultMessage(
      { code: "something_new", message: "Server knows best" },
      t(ko)
    );

    expect(shown).toBe("Server knows best");
    expect(shown).not.toContain("auth.signin.result");
  });

  it("코드도 문구도 없으면 성공 문구를 쓴다 — 토스트는 떠야 한다", () => {
    expect(loginResultMessage({}, t(ko))).toBe("로그인되었습니다.");
    expect(loginResultMessage(undefined, t(ko))).toBe("로그인되었습니다.");
  });

  it("빈 문자열 message 는 문구로 치지 않는다", () => {
    expect(loginResultMessage({ message: "   " }, t(ko))).toBe("로그인되었습니다.");
  });

  /** 두 사전에 같은 키가 있어야 한다 — 하나만 넣으면 그 언어에서만 키가 노출된다. */
  it("ko·ja 가 같은 결과 키를 갖는다", () => {
    for (const key of ["auth.signin.result.login_success", "auth.signin.result.multi_login"]) {
      expect(ko[key]).toBeTruthy();
      expect(ja[key]).toBeTruthy();
    }
  });
});
