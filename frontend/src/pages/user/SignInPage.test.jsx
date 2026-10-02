import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import SignInPage from "pages/user/SignInPage";
import { withI18n } from "i18n/testing";
import api from "util/api";

const mockNavigate = jest.fn();
jest.mock("react-router-dom", () => ({
  useNavigate: () => mockNavigate,
  Link: ({ children, to }) => <a href={to}>{children}</a>,
  // 소셜 로그인 에러/2FA 단계가 리다이렉트 쿼리를 읽는다. 테스트가 값을 바꿀 수 있게
  // 가변 변수를 통해 공급한다(기본은 빈 쿼리).
  useSearchParams: () => [new URLSearchParams(global.__mockSearch || "")],
  // 로그인 성공 후 "원래 가려던 곳"으로 돌아가기 위해 읽는다. ProtectedRoute 가
  // state.from 에 넣어 준다. 테스트가 값을 정할 수 있게 가변 변수로 공급한다.
  useLocation: () => ({ state: global.__mockLocationState }),
}));

// 소셜 버튼은 자체 테스트(SocialLogin.test.jsx)가 있다. 여기서는 네트워크 호출이
// 로그인 테스트에 끼어들지 않게 비워 둔다.
jest.mock("components/auth/SocialLogin", () => ({
  __esModule: true,
  default: () => null,
}));

const mockLogin = jest.fn();
jest.mock("store/AuthContext", () => ({
  STATUS: { IDLE: "idle", PENDING: "pending", SUCCEEDED: "succeeded", FAILED: "failed" },
  useAuthContext: () => ({ login: mockLogin, setAuthenticationStatus: jest.fn() }),
}));

const mockToastError = jest.fn();
jest.mock("react-hot-toast", () => ({
  __esModule: true,
  default: { error: (...a) => mockToastError(...a), success: jest.fn() },
}));

// jwtDecode 는 실제 토큰을 요구하므로, 테스트가 정하는 클레임을 그대로 돌려주게 한다.
const mockDecode = jest.fn();
jest.mock("jwt-decode", () => ({ jwtDecode: (...a) => mockDecode(...a) }));

function renderPage(lang = "ko") {
  const { QueryClient, QueryClientProvider } = require("@tanstack/react-query");
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const { QueryClientProvider: P } = { QueryClientProvider };
  return render(
    withI18n(
      <P client={client}>
        <SignInPage />
      </P>,
      lang
    )
  );
}

async function submitCredentials() {
  await userEvent.type(screen.getByPlaceholderText(/이메일 주소/), "a@example.com");
  await userEvent.type(screen.getByPlaceholderText("비밀번호를 입력해 주세요"), "pw123456");
  await userEvent.click(screen.getByRole("button", { name: "로그인" }));
}

describe("SignInPage", () => {
  beforeEach(() => {
    // 테스트마다 초기화한다. 남아 있으면 앞 테스트가 정한 리다이렉트 대상이
    // 뒤 테스트로 새어 나간다.
    global.__mockLocationState = undefined;
  });

  afterEach(() => {
    jest.clearAllMocks();
    jest.restoreAllMocks();
    global.__mockLocationState = undefined;
  });

  it("🐛 keeps every character the user types", async () => {
    // 이 프로젝트에서 프론트엔드 테스트가 처음으로 잡아낸 버그다.
    //
    // 폼 값의 출처가 둘이었다 — 페이지의 로컬 state 와 Formik. LoginInput 이
    // `{...field}` 뒤에 `{...props}` 를 펼쳐서 페이지가 내려준 onChange 가 Formik 의
    // onChange 를 덮어썼고, `enableReinitialize` 가 키 입력마다 Formik 을 초기화하면서
    // 컨트롤드 인풋의 값이 한 렌더씩 밀렸다. 그 결과 **입력한 글자가 유실됐다**:
    // "a@example.com" 을 치면 "aeapecm" 이 남았다.
    //
    // 사람이 천천히 타이핑하면 잘 드러나지 않아 지금까지 발견되지 않았다.
    jest.spyOn(api, "post").mockResolvedValue({ headers: { access: "t" }, data: {} });
    mockDecode.mockReturnValue({ sub: "a@example.com", is2faEnabled: false, exp: 1 });

    renderPage();
    const email = screen.getByPlaceholderText(/이메일 주소/);
    await userEvent.type(email, "a@example.com");

    expect(email).toHaveValue("a@example.com");
  });

  it("🐛 surfaces the server's reason when sign-in fails", async () => {
    // 예전에는 signin() 이 오류를 삼켜 undefined 를 반환했고, useMutation 은 그것을
    // **성공**으로 받았다. 그래서 onError 는 한 번도 실행되지 않았고 사용자는 서버가
    // 알려준 진짜 이유("비밀번호가 5회 틀려 잠김" 등) 대신 뭉뚱그린 문구만 봤다.
    jest.spyOn(api, "post").mockRejectedValue({
      response: { status: 401, data: { message: "비밀번호가 올바르지 않습니다" } },
    });

    renderPage();
    await submitCredentials();

    await waitFor(() =>
      expect(mockToastError).toHaveBeenCalledWith(
        "비밀번호가 올바르지 않습니다",
        expect.anything()
      )
    );
    expect(mockLogin).not.toHaveBeenCalled();
  });

  /**
   * 🐛 회귀 대상: <b>실패 사유가 화면에 남지 않았다.</b>
   *
   * <p>페이지에 `const [login_error] = useState("")` — setter 가 없어 영원히 빈 문자열이고,
   * 그것을 그리는 블록은 한 번도 실행되지 않는 죽은 코드였다. 실패는 토스트로만 알렸는데
   * 토스트는 몇 초 뒤 사라진다. "메일 인증 링크를 눌러 주세요" 처럼 <b>읽고 행동해야 하는</b>
   * 안내가 그대로 증발했다.
   */
  describe("실패 사유를 사유에 맞게 보여준다", () => {
    const failWith = async (rejection) => {
      jest.spyOn(api, "post").mockRejectedValue(rejection);
      renderPage();
      await submitCredentials();
    };

    it("서버가 보낸 사유를 폼 아래에 남긴다 — 토스트는 사라진다", async () => {
      await failWith({
        response: { status: 401, data: { message: "로그인이 잠시 잠겼습니다. 약 12분 후 다시 시도해 주세요." } },
      });

      const alert = await screen.findByRole("alert");
      expect(alert).toHaveTextContent("약 12분 후");
    });

    /**
     * 미인증 계정에 "비밀번호가 틀렸다"를 주면 사용자는 맞는 비밀번호를 계속 다시 치다가
     * 결국 잠긴다. 정작 필요한 것은 메일함을 여는 것이다.
     */
    it("메일 인증 안내를 그대로 보여준다", async () => {
      await failWith({
        response: {
          status: 401,
          data: { message: "메일 인증이 완료되지 않은 계정입니다. 가입 확인 메일의 링크를 눌러 주세요." },
        },
      });

      expect(await screen.findByRole("alert")).toHaveTextContent("메일 인증");
    });

    /**
     * 🔴 서버에 닿지도 못했는데 "비밀번호가 틀렸다"로 보이면, 사용자는 맞는 비밀번호를
     * 의심하며 계속 다시 친다.
     */
    it("응답이 없으면 네트워크 문제라고 말한다 — 비밀번호를 의심하게 두지 않는다", async () => {
      await failWith({ message: "Network Error" });

      const alert = await screen.findByRole("alert");
      expect(alert).toHaveTextContent(/연결하지 못했습니다/);
      expect(alert).not.toHaveTextContent(/비밀번호/);
    });

    it("타임아웃은 느린 것이라고 말한다", async () => {
      await failWith({ code: "ECONNABORTED", message: "timeout of 5000ms exceeded" });

      expect(await screen.findByRole("alert")).toHaveTextContent(/응답이 너무 느립니다/);
    });

    it("본문 없는 401 이어도 영어 기본 문구를 띄우지 않는다", async () => {
      await failWith({ response: { status: 401, data: "" } });

      const alert = await screen.findByRole("alert");
      expect(alert).toHaveTextContent("이메일 또는 비밀번호가 올바르지 않습니다.");
      expect(alert).not.toHaveTextContent(/Login Failed/i);
    });

    it("503 은 토스트를 중복해서 띄우지 않는다 — 인터셉터가 이미 띄운다", async () => {
      await failWith({ response: { status: 503, data: "" } });

      // 인라인 안내는 남는다.
      expect(await screen.findByRole("alert")).toHaveTextContent(/일시적으로 응답하지 않습니다/);
      expect(mockToastError).not.toHaveBeenCalled();
    });

    it("다시 성공하면 남아 있던 실패 안내를 지운다", async () => {
      jest.spyOn(api, "post").mockRejectedValueOnce({
        response: { status: 401, data: { message: "이메일 또는 비밀번호가 올바르지 않습니다." } },
      });
      mockDecode.mockReturnValue({ sub: "a@example.com", is2faEnabled: false, exp: 1 });

      renderPage();
      await submitCredentials();
      await screen.findByRole("alert");

      api.post.mockResolvedValue({ headers: { access: "t" }, data: { message: "ok" } });
      await userEvent.click(screen.getByRole("button", { name: "로그인" }));

      await waitFor(() => expect(screen.queryByRole("alert")).not.toBeInTheDocument());
    });
  });

  it("logs in and navigates when 2FA is off", async () => {
    jest.spyOn(api, "post").mockResolvedValue({
      headers: { access: "token-abc" },
      data: { message: "ok" },
    });
    mockDecode.mockReturnValue({
      sub: "a@example.com", uid: 7, role: "ROLE_USER", is2faEnabled: false, exp: 1,
    });

    renderPage();
    await submitCredentials();

    await waitFor(() => expect(mockLogin).toHaveBeenCalled());
    expect(mockNavigate).toHaveBeenCalledWith("/", { replace: true });
  });

  /**
   * 🐛 회귀 대상: 로그인 후 **무조건 홈**이었다(`navigate('/')`).
   *
   * /checkout 에서 세션이 끊겨 로그인하러 갔다 오면 홈으로 떨어져 장바구니부터 다시
   * 시작해야 했다. ProtectedRoute 가 막힌 위치를 `state.from` 으로 넘긴다.
   */
  describe("로그인 후 원래 가려던 곳으로 돌아간다", () => {
    const signInAnd = async (expected) => {
      jest.spyOn(api, "post").mockResolvedValue({
        headers: { access: "token-abc" },
        data: { message: "ok" },
      });
      mockDecode.mockReturnValue({
        sub: "a@example.com", uid: 7, role: "ROLE_USER", is2faEnabled: false, exp: 1,
      });

      renderPage();
      await submitCredentials();

      await waitFor(() => expect(mockLogin).toHaveBeenCalled());
      expect(mockNavigate).toHaveBeenCalledWith(expected, { replace: true });
    };

    it("막혔던 경로로 돌아간다", async () => {
      global.__mockLocationState = { from: { pathname: "/checkout" } };
      await signInAnd("/checkout");
    });

    it("쿼리스트링도 유지한다", async () => {
      global.__mockLocationState = { from: { pathname: "/browse", search: "?category=1" } };
      await signInAnd("/browse?category=1");
    });

    /**
     * 🔒 넘어온 값을 그냥 믿으면 열린 리다이렉트가 된다 — `/signin` 링크에 외부 주소를
     * 심어 로그인 <b>직후</b> 다른 사이트로 보낼 수 있다. 자격증명을 막 입력한 직후라
     * 피싱에 특히 잘 통하는 시점이다.
     */
    it("외부 주소로는 보내지 않는다", async () => {
      global.__mockLocationState = { from: { pathname: "https://evil.example.com" } };
      await signInAnd("/");
    });

    it("프로토콜 상대 URL(//evil.com)도 막는다", async () => {
      // `//evil.com` 은 브라우저가 https://evil.com 으로 해석한다. `startsWith("/")` 만
      // 검사하면 그대로 통과한다.
      global.__mockLocationState = { from: { pathname: "//evil.example.com" } };
      await signInAnd("/");
    });

    it.each([
      ["/\\evil.example.com"],        // 브라우저는 /\ 를 // 로 읽는다 — react-router 6 열린 리다이렉트 권고의 우회
      ["/\\/evil.example.com"],
      ["/\tevil.example.com"],          // 제어 문자는 브라우저가 지우고 읽는다
    ])("🔴 역슬래시·제어 문자로 외부로 나가는 경로(%j)도 막는다", async (pathname) => {
      global.__mockLocationState = { from: { pathname } };
      await signInAnd("/");
    });

    it("로그인 화면 자신으로는 돌아가지 않는다 — 무한 왕복이 된다", async () => {
      global.__mockLocationState = { from: { pathname: "/signin" } };
      await signInAnd("/");
    });

    it("from 이 없으면 홈으로 — 기존 동작 그대로", async () => {
      global.__mockLocationState = undefined;
      await signInAnd("/");
    });
  });

  /**
   * 🔴 2FA 는 서버가 강제한다 — 2FA 계정의 1단계 응답에는 액세스 토큰이 없다(code=two_factor_required,
   * 대기 쿠키만). 예전에는 서버가 토큰을 먼저 주고 이 화면이 들고만 있었다.
   */
  const twoFactorRequired = { headers: {}, data: { code: "two_factor_required", message: "Two-factor code required" } };

  it("🐛 shows a 2FA step instead of silently doing nothing", async () => {
    jest.spyOn(api, "post").mockResolvedValue(twoFactorRequired);

    renderPage();
    await submitCredentials();

    expect(await screen.findByText("2단계 인증")).toBeInTheDocument();
    // 코드 확인 전에는 절대 로그인이 확정되면 안 된다 — 확정할 토큰도 없다.
    expect(mockLogin).not.toHaveBeenCalled();
    expect(mockDecode).not.toHaveBeenCalled();
  });

  it("2FA 코드는 public 엔드포인트로 가고, 통과하면 가려던 곳으로 전체 리로드한다", async () => {
    global.__mockLocationState = { from: { pathname: "/checkout" } };
    const post = jest.spyOn(api, "post")
      .mockResolvedValueOnce(twoFactorRequired)
      .mockResolvedValueOnce({ data: { message: "ok" } });
    delete window.location;
    window.location = { href: "" };

    try {
      renderPage();
      await submitCredentials();

      await userEvent.type(await screen.findByLabelText("인증 코드"), "123456");
      await userEvent.click(screen.getByRole("button", { name: /확인/ }));

      await waitFor(() => expect(post.mock.calls[1][0]).toContain("/api/auth/public/verify-2fa?code=123456"));
      // Authorization 을 싣지 않는다 — 신원은 httpOnly 대기 쿠키다.
      expect(post.mock.calls[1][2]).toBeUndefined();
      await waitFor(() => expect(window.location.href).toBe("/checkout"));
      expect(mockLogin).not.toHaveBeenCalled();
    } finally {
      global.__mockLocationState = undefined;
    }
  });

  it("keeps the user on the 2FA step when the code is wrong", async () => {
    jest.spyOn(api, "post")
      .mockResolvedValueOnce(twoFactorRequired)
      .mockRejectedValueOnce({ response: { status: 401, data: { message: "인증 코드가 올바르지 않습니다." } } });

    renderPage();
    await submitCredentials();

    await userEvent.type(await screen.findByLabelText("인증 코드"), "000000");
    await userEvent.click(screen.getByRole("button", { name: /확인/ }));

    expect(await screen.findByRole("alert")).toHaveTextContent("인증 코드가 올바르지 않습니다");
    expect(mockLogin).not.toHaveBeenCalled();
  });

  /**
   * 🔴 위험 기반 로그인(docs/68 §19) — 처음 보는 기기 + 공격 신호면 서버가 토큰 대신 확인 대기 쿠키만 주고
   * code=login_check_required 로 답한다. 화면은 메일 코드 입력으로 가고, 코드는 2FA 가 아닌 엔드포인트로 간다.
   */
  it("login_check_required 면 메일 코드 단계가 뜨고 verify-login-check 로 보낸다", async () => {
    const post = jest.spyOn(api, "post")
      .mockResolvedValueOnce({ headers: {}, data: { code: "login_check_required", message: "Login check required" } })
      .mockResolvedValueOnce({ data: { message: "ok" } });
    delete window.location;
    window.location = { href: "" };

    renderPage();
    await submitCredentials();

    expect(await screen.findByText("로그인 확인")).toBeInTheDocument();
    expect(screen.getByText(/메일 주소로 보낸 6자리 코드/)).toBeInTheDocument();
    expect(screen.queryByText("2단계 인증")).not.toBeInTheDocument();
    expect(mockLogin).not.toHaveBeenCalled();

    await userEvent.type(screen.getByLabelText("인증 코드"), "123456");
    await userEvent.click(screen.getByRole("button", { name: /확인/ }));

    await waitFor(() => expect(post.mock.calls[1][0]).toContain("/api/auth/public/verify-login-check?code=123456"));
    expect(post.mock.calls[1][0]).not.toContain("verify-2fa");
    await waitFor(() => expect(window.location.href).not.toBe(""));
    expect(mockLogin).not.toHaveBeenCalled();
  });

  it("소셜 2FA(?oauth2fa=1): 바로 2FA 화면이 뜨고, 코드는 public 엔드포인트로 간다", async () => {
    // 신원은 PENDING_2FA httpOnly 쿠키가 나른다 — 화면은 토큰을 만지지 않는다.
    global.__mockSearch = "oauth2fa=1";
    const post = jest.spyOn(api, "post").mockResolvedValue({ data: { message: "ok" } });
    delete window.location;
    window.location = { href: "" };

    try {
      renderPage();

      // 비밀번호 단계를 건너뛰고 곧장 2FA 입력이다.
      const input = await screen.findByLabelText("인증 코드");
      await userEvent.type(input, "654321");
      await userEvent.click(screen.getByRole("button", { name: /확인/ }));

      await waitFor(() =>
        expect(post.mock.calls[0][0]).toContain("/api/auth/public/verify-2fa"));
      // 통과하면 전체 리로드 — REFRESH 쿠키로 세션이 복구된다.
      await waitFor(() => expect(window.location.href).toBe("/"));
      expect(mockLogin).not.toHaveBeenCalled();
    } finally {
      global.__mockSearch = "";
    }
  });

  it.each([
    ["email_required", "이메일 제공에 동의해야 로그인할 수 있습니다"],
    ["unverified_email", "인증되지 않은 이메일입니다"],
    ["access_denied", "소셜 로그인을 취소했습니다"],
    ["something_else", "소셜 로그인에 실패했습니다."],
  ])("소셜 로그인 실패 ?error=%s — 이유를 알려 준다(모르는 코드는 일반 문구)", async (code, text) => {
    global.__mockSearch = `error=${code}`;
    try {
      renderPage();
      await waitFor(() => expect(mockToastError).toHaveBeenCalled());
      expect(mockToastError.mock.calls.at(-1)[0]).toContain(text);
    } finally {
      global.__mockSearch = "";
    }
  });

  it("소셜 2FA: 코드가 틀리면 서버 메시지를 보여주고 그 자리에 머문다", async () => {
    global.__mockSearch = "oauth2fa=1";
    jest.spyOn(api, "post").mockRejectedValue({
      response: { status: 401, data: { message: "인증 코드가 올바르지 않습니다." } },
    });

    try {
      renderPage();
      await userEvent.type(await screen.findByLabelText("인증 코드"), "000000");
      await userEvent.click(screen.getByRole("button", { name: /확인/ }));

      expect(await screen.findByRole("alert")).toHaveTextContent("인증 코드가 올바르지 않습니다.");
    } finally {
      global.__mockSearch = "";
    }
  });

  it("rejects non-digits in the 2FA field", async () => {
    jest.spyOn(api, "post").mockResolvedValue(twoFactorRequired);

    renderPage();
    await submitCredentials();

    const input = await screen.findByLabelText("인증 코드");
    await userEvent.type(input, "12ab34");

    expect(input).toHaveValue("1234");
  });

  /** 일본어 화면에 한국어·영어가 남지 않는다 — 문구가 전부 사전에서 온다(docs/60 §5). */
  it("일본어로 바꾸면 화면 문구와 검증 메시지가 일본어다", async () => {
    renderPage("ja");

    expect(screen.getByRole("heading", { name: "ログイン" })).toBeInTheDocument();
    expect(screen.getByPlaceholderText("メールアドレスを入力してください")).toBeInTheDocument();
    expect(screen.queryByText(/^로그인$|계속하면/)).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: /ログイン/ }));
    expect(await screen.findByText("メールアドレスを入力してください。")).toBeInTheDocument();
  });
});
