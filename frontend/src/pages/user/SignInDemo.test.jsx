import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import SignInPage from "pages/user/SignInPage";
import { withI18n } from "i18n/testing";
import api from "util/api";

// 데모 계정으로 둘러보기(docs/100) — 서버가 켜 둔 곳에서만 버튼, 누르면 보통 로그인과 같은 끝(login + demo 표시).
// [JA] デモアカウントで試す — サーバーが有効な所でだけボタン、押すと通常ログインと同じ終わり方。

const mockNavigate = jest.fn();
jest.mock("react-router-dom", () => ({
  useNavigate: () => mockNavigate,
  Link: ({ children, to }) => <a href={to}>{children}</a>,
  useSearchParams: () => [new URLSearchParams("")],
  useLocation: () => ({ state: undefined }),
}));
jest.mock("components/auth/SocialLogin", () => ({ __esModule: true, default: () => null }));

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
const mockDecode = jest.fn();
jest.mock("jwt-decode", () => ({ jwtDecode: (...a) => mockDecode(...a) }));

function renderPage() {
  const { QueryClient, QueryClientProvider } = require("@tanstack/react-query");
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(withI18n(<QueryClientProvider client={client}><SignInPage /></QueryClientProvider>, "ko"));
}

function demoStatus(enabled) {
  jest.spyOn(api, "get").mockImplementation((url) =>
    Promise.resolve({ data: url.includes("demo-login") ? { enabled } : { totp: true, sms: false } }));
}

describe("SignInPage — 데모 계정", () => {
  afterEach(() => {
    jest.clearAllMocks();
    jest.restoreAllMocks();
  });

  it("데모가 꺼져 있으면 버튼이 없다", async () => {
    demoStatus(false);
    renderPage();
    await waitFor(() => expect(api.get).toHaveBeenCalledWith("/user-service/api/auth/public/demo-login"));
    expect(screen.queryByRole("button", { name: "데모 계정으로 둘러보기" })).toBeNull();
  });

  it("🔴 켜져 있으면 버튼 — 누르면 데모 로그인 후 demo 표시가 실린 사용자로 login", async () => {
    demoStatus(true);
    jest.spyOn(api, "post").mockResolvedValue({ headers: { access: "h.e.s" }, data: { code: "demo" } });
    mockDecode.mockReturnValue({ sub: "demo1@demo.invalid", role: "ROLE_USER", demo: true, exp: 9999999999 });
    renderPage();

    await userEvent.click(await screen.findByRole("button", { name: "데모 계정으로 둘러보기" }));

    await waitFor(() => expect(mockLogin).toHaveBeenCalled());
    expect(api.post).toHaveBeenCalledWith("/user-service/api/auth/public/demo-login");
    expect(mockLogin.mock.calls[0][0]).toMatchObject({ email: "demo1@demo.invalid", demo: true });
  });

  it("데모 로그인이 실패하면 안내 토스트(503 은 공통 인터셉터가 띄우므로 그 밖의 실패)", async () => {
    demoStatus(true);
    jest.spyOn(api, "post").mockRejectedValue({ response: { status: 500 } });
    renderPage();
    await userEvent.click(await screen.findByRole("button", { name: "데모 계정으로 둘러보기" }));
    await waitFor(() => expect(mockToastError).toHaveBeenCalled());
    expect(mockLogin).not.toHaveBeenCalled();
  });
});
