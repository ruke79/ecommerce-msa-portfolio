import { fallbackMessage, alreadyToasted, loginErrorMessage } from "util/loginError";
import { NO_SESSION_URLS } from "util/TokenRefresher";

/**
 * 🐛 회귀 대상: 로그인이 왜 실패했는지 알 수 없었다.
 *
 * <p>원인이 셋이었고 각각 다른 자리에 있었다. 이 파일은 그중 프론트엔드 쪽 둘을 고정한다:
 * 사유 → 문구 변환과, 인터셉터가 그 사유를 덮지 않는다는 것.
 */
describe("로그인 실패 문구", () => {
  const withMessage = (status, message) => ({ response: { status, data: { message } } });

  /**
   * 🔴 서버만 아는 사실이 있다 — 잠금까지 남은 시간, 메일 인증 안내. 화면에서 다시
   * 분류하면 같은 규칙이 두 곳에 생기고 언젠가 어긋난다.
   */
  it("서버가 보낸 문구를 항상 우선한다", () => {
    expect(loginErrorMessage(withMessage(401, "약 12분 후 다시 시도해 주세요.")))
      .toBe("약 12분 후 다시 시도해 주세요.");
  });

  it("빈 문자열 메시지는 무시하고 상태 코드로 판단한다", () => {
    expect(loginErrorMessage(withMessage(401, "   "))).toContain("이메일 또는 비밀번호");
  });

  describe("서버에 닿지 못한 경우", () => {
    /**
     * 🔴 여기서 "비밀번호가 틀렸다"로 보이면 사용자는 맞는 비밀번호를 의심하며 계속
     * 다시 친다 — 그러다 실제로 잠긴다.
     */
    it("응답이 없으면 네트워크 문제라고 말한다", () => {
      const message = loginErrorMessage({ message: "Network Error" });

      expect(message).toMatch(/연결하지 못했습니다/);
      expect(message).not.toMatch(/비밀번호/);
    });

    it("타임아웃은 따로 구분한다", () => {
      expect(loginErrorMessage({ code: "ECONNABORTED", message: "timeout of 5000ms exceeded" }))
        .toMatch(/너무 느립니다/);
      expect(loginErrorMessage({ message: "timeout exceeded" })).toMatch(/너무 느립니다/);
    });
  });

  describe("본문 없이 상태 코드만 온 경우", () => {
    const bare = (status) => loginErrorMessage({ response: { status, data: "" } });

    it("400 은 입력이 비었다고 말한다 — 비밀번호를 의심하게 두지 않는다", () => {
      expect(bare(400)).toMatch(/모두 입력해 주세요/);
      expect(bare(400)).not.toMatch(/올바르지 않습니다/);
    });

    it("401·403 은 자격 증명 문제다", () => {
      expect(bare(401)).toBe("이메일 또는 비밀번호가 올바르지 않습니다.");
      expect(bare(403)).toBe("이메일 또는 비밀번호가 올바르지 않습니다.");
    });

    it("429 는 기다리라고 한다", () => {
      expect(bare(429)).toMatch(/잠시 후/);
    });

    it("503 은 서버 문제라고 말한다", () => {
      expect(bare(503)).toMatch(/일시적으로 응답하지 않습니다/);
    });

    it("모르는 상태 코드에도 한국어 기본 문구가 나간다", () => {
      expect(bare(418)).toBe(fallbackMessage());
      expect(bare(418)).not.toMatch(/Login Failed/i);
    });
  });

  it("어떤 입력에도 영어 기본 문구를 내지 않는다", () => {
    const inputs = [undefined, null, {}, { response: {} }, { response: { status: 500 } }];

    inputs.forEach((input) => {
      expect(loginErrorMessage(input)).not.toMatch(/Login Failed/i);
      expect(loginErrorMessage(input)).toEqual(expect.any(String));
    });
  });

  describe("토스트 중복", () => {
    it("503 은 인터셉터가 이미 띄운다", () => {
      expect(alreadyToasted({ response: { status: 503 } })).toBe(true);
    });

    it("나머지는 화면이 띄운다", () => {
      expect(alreadyToasted({ response: { status: 401 } })).toBe(false);
      expect(alreadyToasted(undefined)).toBe(false);
    });
  });
});

/**
 * 🐛 회귀 대상: <b>인터셉터가 로그인 실패 사유를 덮어썼다.</b>
 *
 * <p>비밀번호를 틀리면 서버는 401 + 사유를 담은 본문을 돌려준다. 그런데 그 401 이
 * 인터셉터의 401 분기로 들어가면 "세션 만료"로 보고 토큰 갱신을 시도한다. 로그인 전이라
 * 갱신 쿠키가 없으니 204 가 오고, 인터셉터는 원래 오류를
 * {@code Error("token refresh failed")} 로 <b>갈아치운 뒤</b> reject 한다. 그 에러에는
 * {@code response.data.message} 가 없어서 화면은 사유를 영영 보지 못했다.
 *
 * <p>게다가 로그인도 안 한 사용자에게 logout() 과 강제 로그아웃 로그까지 남겼다.
 */
describe("세션 없는 요청은 인터셉터가 손대지 않는다", () => {
  const matches = (url) => NO_SESSION_URLS.some((prefix) => url.includes(prefix));

  it("로그인 요청이 목록에 걸린다", () => {
    expect(matches("/user-service/api/auth/public/signin")).toBe(true);
  });

  it("공개 인증 경로 전체가 걸린다 — 소셜 2FA 확인도 로그인 전이다", () => {
    expect(matches("/user-service/api/auth/public/oauth2/verify-2fa?code=123456")).toBe(true);
  });

  /**
   * 로그인한 사용자의 요청까지 제외하면 토큰 갱신이 통째로 멈춘다 — 액세스 토큰이
   * 만료될 때마다 사용자가 다시 로그인해야 한다.
   */
  it("로그인한 사용자의 일반 요청은 그대로 갱신 대상이다", () => {
    expect(matches("/order-service/api/order/orders")).toBe(false);
    expect(matches("/user-service/api/user/profile/me")).toBe(false);
    // 2FA 확인의 **인증된** 경로(public 아님)는 토큰을 쓰므로 제외되면 안 된다.
    expect(matches("/user-service/api/auth/verify-2fa?code=123456")).toBe(false);
  });
});
