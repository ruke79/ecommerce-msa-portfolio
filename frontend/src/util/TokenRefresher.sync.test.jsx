import { render } from "@testing-library/react";

jest.mock("react-router-dom", () => ({ useNavigate: () => jest.fn() }));

let mockAuth;
jest.mock("store/AuthContext", () => ({
  STATUS: { IDLE: "idle", PENDING: "pending", SUCCEEDED: "succeeded", FAILED: "failed" },
  useAuthContext: () => mockAuth,
}));

const mockToast = Object.assign(jest.fn(), { error: jest.fn(), success: jest.fn() });
jest.mock("react-hot-toast", () => ({ __esModule: true, default: mockToast }));
jest.mock("jwt-decode", () => ({ jwtDecode: () => ({ exp: 999, sub: "a@b.c" }) }));

const mockGetRequest = jest.fn();
const mockApi = jest.fn();
mockApi.interceptors = {
  request: { use: () => 0, eject: jest.fn() },
  response: { use: () => 0, eject: jest.fn() },
};
jest.mock("util/api", () => ({
  __esModule: true,
  default: mockApi,
  getRequest: (...a) => mockGetRequest(...a),
}));

let deliver;
jest.mock("util/authSync", () => ({
  SIGNED_IN: "signed-in",
  SIGNED_OUT: "signed-out",
  subscribeAuth: (h) => {
    deliver = h;
    return () => {};
  },
}));

const TokenRefresher = require("util/TokenRefresher").default;

const auth = (over) => ({
  login: jest.fn(),
  logout: jest.fn(),
  token: "t",
  user: { userId: "77" },
  addresses: null,
  status: "succeeded",
  isAuthenticated: true,
  expiresAt: null,
  setAuthenticationStatus: jest.fn(),
  ...over,
});

/** 다른 탭의 로그인 · 로그아웃을 따라간다(util/authSync). */
describe("TokenRefresher — 다른 탭 따라가기", () => {
  const reload = jest.fn();
  beforeAll(() => {
    delete window.location;
    window.location = { reload };
  });
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("🔴 다른 탭이 로그아웃하면 이 탭도 로그아웃 — 다시 알리지 않는다", () => {
    mockAuth = auth();
    render(<TokenRefresher />);
    deliver({ type: "signed-out", uid: null });
    expect(mockAuth.logout).toHaveBeenCalledWith({ broadcast: false });
    expect(mockToast).toHaveBeenCalled();
  });

  it("로그아웃 상태에서 받은 로그아웃은 무시한다", () => {
    mockAuth = auth({ isAuthenticated: false, status: "idle", user: {} });
    render(<TokenRefresher />);
    deliver({ type: "signed-out", uid: null });
    expect(mockAuth.logout).not.toHaveBeenCalled();
  });

  it("🔴 로그아웃(또는 세션 만료) 상태의 탭은 다른 탭이 로그인하면 자기 토큰을 받는다 — 결과를 다시 알리지 않는다", async () => {
    mockAuth = auth({ isAuthenticated: false, status: "idle", user: {} });
    mockGetRequest.mockResolvedValue({ status: 200, headers: { access: "eyJh.eyJ.sig" } });
    render(<TokenRefresher />);
    await deliver({ type: "signed-in", uid: "77" });
    await Promise.resolve();
    expect(mockGetRequest).toHaveBeenCalledWith("/user-service/api/token/refresh");
    await new Promise((r) => setTimeout(r, 0));
    expect(mockAuth.login).toHaveBeenCalledWith(expect.anything(), "eyJh.eyJ.sig", 999, { broadcast: false });
  });

  it("🔴 같은 사용자로 로그인된 탭은 아무것도 하지 않는다 — 갱신 알림이 갱신 연쇄를 일으키지 않게", () => {
    mockAuth = auth();
    render(<TokenRefresher />);
    deliver({ type: "signed-in", uid: "77" });
    expect(mockGetRequest).not.toHaveBeenCalled();
    expect(reload).not.toHaveBeenCalled();
  });

  it("다른 사용자로 바뀌면 새로고침한다 — 앞 사용자의 화면 상태를 남기지 않는다", () => {
    mockAuth = auth();
    render(<TokenRefresher />);
    deliver({ type: "signed-in", uid: "88" });
    expect(reload).toHaveBeenCalled();
  });

  it("처음 복구 중(PENDING)이면 끼어들지 않는다 — 리프레시 토큰이 두 번 회전하지 않게", () => {
    mockAuth = auth({ isAuthenticated: false, status: "pending", user: {} });
    mockGetRequest.mockResolvedValue({ status: 204, headers: {} });
    render(<TokenRefresher />);
    mockGetRequest.mockClear();
    deliver({ type: "signed-in", uid: "77" });
    expect(mockGetRequest).not.toHaveBeenCalled();
  });
});
