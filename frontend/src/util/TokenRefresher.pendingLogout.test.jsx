import { render, waitFor } from "@testing-library/react";

jest.mock("react-router-dom", () => ({ useNavigate: () => jest.fn() }));
let mockAuth;
jest.mock("store/AuthContext", () => ({
  STATUS: { IDLE: "idle", PENDING: "pending", SUCCEEDED: "succeeded", FAILED: "failed" },
  useAuthContext: () => mockAuth,
}));
jest.mock("react-hot-toast", () => ({ __esModule: true, default: Object.assign(jest.fn(), { error: jest.fn() }) }));
jest.mock("jwt-decode", () => ({ jwtDecode: () => ({ exp: 999, iat: 99 }) }));
const mockGet = jest.fn();
const mockPost = jest.fn();
const mockApi = jest.fn();
mockApi.interceptors = { request: { use: () => 0, eject: jest.fn() }, response: { use: () => 0, eject: jest.fn() } };
jest.mock("util/api", () => ({
  __esModule: true,
  default: mockApi,
  getRequest: (...a) => mockGet(...a),
  postRequest: (...a) => mockPost(...a),
}));
jest.mock("util/authSync", () => ({ SIGNED_IN: "signed-in", SIGNED_OUT: "signed-out", subscribeAuth: () => () => {} }));

const TokenRefresher = require("util/TokenRefresher").default;
const { hasPendingLogout } = require("util/pendingLogout");

const pendingAuth = () => ({
  login: jest.fn(), logout: jest.fn(), token: null, user: {}, status: "pending",
  isAuthenticated: false, expiresAt: null, setAuthenticationStatus: jest.fn(),
});

/** 🔴 지난번 로그아웃이 서버에 반영되지 못했으면, 다음 접속은 자동 복구 대신 서버 로그아웃을 마무리한다(docs/68 §13). */
describe("TokenRefresher — 마무리 못 한 로그아웃", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    window.localStorage.setItem("ecommerce-pending-logout", "1");
  });

  it("🔴 갱신으로 받은 토큰으로 서버 로그아웃 → 표시를 지우고 로그아웃 상태로 — 다시 로그인시키지 않는다", async () => {
    mockAuth = pendingAuth();
    mockGet.mockResolvedValue({ status: 200, headers: { access: "fresh.access" } });
    mockPost.mockResolvedValue({ status: 200 });
    render(<TokenRefresher />);

    await waitFor(() => expect(mockAuth.logout).toHaveBeenCalledWith({ broadcast: false }));
    expect(mockPost).toHaveBeenCalledWith("/user-service/api/auth/logout", null, undefined,
      expect.objectContaining({ headers: expect.objectContaining({ Authorization: "Bearer fresh.access" }) }));
    expect(mockAuth.login).not.toHaveBeenCalled();
    expect(hasPendingLogout()).toBe(false);
  });

  it("이미 끝난 세션(서버가 거절)이면 표시만 지운다", async () => {
    mockAuth = pendingAuth();
    mockGet.mockRejectedValue({ response: { status: 400 } });
    render(<TokenRefresher />);

    await waitFor(() => expect(mockAuth.logout).toHaveBeenCalled());
    expect(mockPost).not.toHaveBeenCalled();
    expect(hasPendingLogout()).toBe(false);
  });

  it("🔴 네트워크가 아직 안 되면 표시를 남기고 로그아웃 상태로 둔다 — 다음 접속에서 다시 시도", async () => {
    mockAuth = pendingAuth();
    mockGet.mockRejectedValue(new Error("Network Error"));
    render(<TokenRefresher />);

    await waitFor(() => expect(mockAuth.logout).toHaveBeenCalled());
    expect(mockAuth.login).not.toHaveBeenCalled();
    expect(hasPendingLogout()).toBe(true);
  });

  it("표시가 없으면 예전처럼 자동 복구한다", async () => {
    window.localStorage.clear();
    mockAuth = pendingAuth();
    mockGet.mockResolvedValue({ status: 200, headers: { access: "a.b.c" } });
    render(<TokenRefresher />);

    await waitFor(() => expect(mockAuth.login).toHaveBeenCalled());
    expect(mockPost).not.toHaveBeenCalled();
  });
});
