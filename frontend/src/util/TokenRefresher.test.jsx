import { render, waitFor } from "@testing-library/react";

const mockNavigate = jest.fn();
jest.mock("react-router-dom", () => ({
  useNavigate: () => mockNavigate,
}));

const mockLogin = jest.fn();
const mockLogout = jest.fn();
let mockAuth;
jest.mock("store/AuthContext", () => ({
  STATUS: { IDLE: "idle", PENDING: "pending", SUCCEEDED: "succeeded", FAILED: "failed" },
  useAuthContext: () => mockAuth,
}));

jest.mock("react-hot-toast", () => ({
  __esModule: true,
  default: { error: jest.fn(), success: jest.fn() },
}));

const mockDecode = jest.fn();
jest.mock("jwt-decode", () => ({ jwtDecode: (...a) => mockDecode(...a) }));

const mockGetRequest = jest.fn();
const interceptors = { request: [], response: [] };
const mockApi = jest.fn();
mockApi.interceptors = {
  request: {
    use: (ok, err) => { interceptors.request.push([ok, err]); return interceptors.request.length - 1; },
    eject: jest.fn(),
  },
  response: {
    use: (ok, err) => { interceptors.response.push([ok, err]); return interceptors.response.length - 1; },
    eject: jest.fn(),
  },
};
jest.mock("util/api", () => ({
  __esModule: true,
  default: mockApi,
  getRequest: (...a) => mockGetRequest(...a),
}));

const TokenRefresher = require("util/TokenRefresher").default;
const { REFRESH_URL } = require("util/TokenRefresher");

/** 마지막으로 등록된 응답 인터셉터의 오류 핸들러. */
const onError = () => interceptors.response[interceptors.response.length - 1][1];

const axiosError = (status, { url = "/some-service/api/thing", data, _retry } = {}) => ({
  config: { url, method: "get", headers: {}, _retry },
  response: { status, data },
});

/**
 * 🐛 회귀 대상: **부수적인 요청 하나가 앱 전체를 로그아웃시킨다.**
 *
 * 인터셉터는 401 을 받은 요청이 갱신 후 재시도해도 401 이면 무조건 `logout()` 했다.
 * 실제로 `/admin/product` 가 로그인 화면으로 튕긴 원인이 이것이었다 — 화면 상단 배너의
 * storage-health 호출 하나가 세션을 날렸다.
 *
 * 그리고 어떤 500 이든 홈으로 보냈고, 503 은 로그아웃까지 했다.
 */
describe("TokenRefresher — 언제 세션을 끝내는가", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    interceptors.request.length = 0;
    interceptors.response.length = 0;
    mockAuth = {
      login: mockLogin,
      logout: mockLogout,
      token: "t",
      addresses: null,
      status: "succeeded",
      isAuthenticated: true,
      expiresAt: null,
      setAuthenticationStatus: jest.fn(),
    };
    render(<TokenRefresher />);
  });

  // ── 개별 요청의 401 ─────────────────────────────────────────────────────

  it("🔴 갱신 후에도 401 이면 그 요청만 실패시킨다 — 로그아웃하지 않는다", async () => {
    const error = axiosError(401, { _retry: true, url: "/catalog-service/api/content/admin/storage-health" });

    await expect(onError()(error)).rejects.toBe(error);

    // 세션은 살아 있어야 한다. 갱신은 성공했으므로 토큰이 무효한 것이 아니라,
    // 그 엔드포인트가 이 사용자에게 닫혀 있는 것이다(사실상 403).
    expect(mockLogout).not.toHaveBeenCalled();
    expect(mockNavigate).not.toHaveBeenCalled();
  });

  it("첫 401 이면 갱신을 시도하고, 성공하면 새 토큰으로 재시도한다", async () => {
    mockGetRequest.mockResolvedValue({ status: 200, headers: { access: "new-token" } });
    mockDecode.mockReturnValue({ sub: "a@b.com", uid: 1, exp: 123 });
    mockApi.mockResolvedValue({ status: 200, data: "ok" });

    const error = axiosError(401);
    await onError()(error);

    expect(mockGetRequest).toHaveBeenCalledWith(REFRESH_URL);
    expect(mockLogin).toHaveBeenCalled();
    // 재시도 요청은 새 토큰을 단다.
    expect(mockApi).toHaveBeenCalledWith(
      expect.objectContaining({
        _retry: true,
        headers: expect.objectContaining({ Authorization: "Bearer new-token" }),
      })
    );
    expect(mockLogout).not.toHaveBeenCalled();
  });

  // ── 갱신 자체의 실패 = 진짜 세션 종료 ───────────────────────────────────

  it("갱신이 200 이 아니면 로그아웃한다 — 세션이 진짜로 끝난 경우다", async () => {
    // 204 = 리프레시 토큰이 아예 없다.
    mockGetRequest.mockResolvedValue({ status: 204 });

    await expect(onError()(axiosError(401))).rejects.toThrow();

    expect(mockLogout).toHaveBeenCalled();
  });

  it("갱신 요청 자체가 터져도 로그아웃한다", async () => {
    mockGetRequest.mockRejectedValue(new Error("network"));

    await expect(onError()(axiosError(401))).rejects.toThrow();

    expect(mockLogout).toHaveBeenCalled();
  });

  it("갱신 토큰 만료(400)면 로그아웃하고 로그인 화면으로", async () => {
    await expect(
      onError()(axiosError(400, { data: "refresh token expired" }))
    ).rejects.toBeDefined();

    expect(mockLogout).toHaveBeenCalled();
    expect(mockNavigate).toHaveBeenCalledWith("/signin");
  });

  it("그밖의 400 은 로그아웃하지 않는다", async () => {
    await expect(
      onError()(axiosError(400, { data: "이름을 입력해 주세요." }))
    ).rejects.toBeDefined();

    expect(mockLogout).not.toHaveBeenCalled();
    expect(mockNavigate).not.toHaveBeenCalled();
  });

  // ── 서버 오류 ───────────────────────────────────────────────────────────

  /**
   * 🐛 예전에는 어떤 500 이든 홈으로 보냈다. 사용자는 자기가 무엇을 하다 튕겼는지 알 수
   * 없었고 작성 중이던 폼도 함께 사라졌다.
   */
  it("🔴 500 은 홈으로 보내지 않고 호출부로 돌려준다", async () => {
    const error = axiosError(500);

    await expect(onError()(error)).rejects.toBe(error);

    expect(mockNavigate).not.toHaveBeenCalled();
    expect(mockLogout).not.toHaveBeenCalled();
  });

  /**
   * 🐛 503 은 서비스가 잠시 뜨지 않았다는 뜻이다(서킷 브레이커·파드 재시작).
   * 사용자의 세션과는 아무 상관이 없는데 로그아웃까지 했다 — 배포 중에 503 하나를
   * 만난 사용자가 장바구니에서 쫓겨났다.
   */
  it("🔴 503 은 로그아웃하지 않는다", async () => {
    const error = axiosError(503);

    await expect(onError()(error)).rejects.toBe(error);

    expect(mockLogout).not.toHaveBeenCalled();
    expect(mockNavigate).not.toHaveBeenCalled();
  });

  it("403·404·429 는 그대로 호출부로 간다", async () => {
    for (const status of [403, 404, 429]) {
      const error = axiosError(status);
      await expect(onError()(error)).rejects.toBe(error);
    }
    expect(mockLogout).not.toHaveBeenCalled();
    expect(mockNavigate).not.toHaveBeenCalled();
  });

  // ── 갱신 요청 자신은 인터셉터가 손대지 않는다 ───────────────────────────

  /**
   * 🔴 교착 방지. 갱신 요청이 401 을 받으면 401 분기로 들어가는데 그때 isRefreshing 은
   * 이미 true 라, "다른 요청이 갱신 중" 경로를 타고 큐에 자기 자신을 넣은 뒤 아무도
   * 깨우지 않는 프라미스를 기다린다. 큐를 깨우는 코드는 바깥 await 가 끝나야 도는데
   * 그 await 가 방금 큐에 들어간 그 프라미스다 — 영원히 멈추고 isRefreshing 이 true 로
   * 굳어서 **그 뒤 모든 401 요청이 같은 큐에서 멈춘다.**
   */
  it("갱신 요청의 401 은 즉시 거절한다 — 큐에서 멈추지 않는다", async () => {
    const error = axiosError(401, { url: REFRESH_URL });

    // 멈추지 않고 거절되는 것 자체가 검증 대상이다.
    await expect(onError()(error)).rejects.toBe(error);

    // 갱신을 또 부르지 않는다(재귀 방지).
    expect(mockGetRequest).not.toHaveBeenCalled();
  });

  it("갱신 요청의 500 도 즉시 거절한다", async () => {
    const error = axiosError(500, { url: REFRESH_URL });

    await expect(onError()(error)).rejects.toBe(error);
    expect(mockNavigate).not.toHaveBeenCalled();
  });

  // ── 요청 인터셉터 ───────────────────────────────────────────────────────

  it("토큰이 있으면 Authorization 헤더를 단다", async () => {
    const [ok] = interceptors.request[interceptors.request.length - 1];
    const config = await ok({ headers: {} });

    expect(config.headers.Authorization).toBe("Bearer t");
  });
});

describe("TokenRefresher — 첫 로딩 시 세션 복구", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    interceptors.request.length = 0;
    interceptors.response.length = 0;
  });

  it("status 가 pending 이면 갱신을 시도한다 — 새로고침 후 세션 복구", async () => {
    mockGetRequest.mockResolvedValue({ status: 200, headers: { access: "tok" } });
    mockDecode.mockReturnValue({ sub: "a@b.com", uid: 1, exp: 999 });
    mockAuth = {
      login: mockLogin, logout: mockLogout, token: null, addresses: null,
      status: "pending", isAuthenticated: false, expiresAt: null,
      setAuthenticationStatus: jest.fn(),
    };

    render(<TokenRefresher />);

    await waitFor(() => expect(mockLogin).toHaveBeenCalled());
  });

  it("복구할 세션이 없으면(204) 로그아웃 상태로 정리한다", async () => {
    mockGetRequest.mockResolvedValue({ status: 204 });
    mockAuth = {
      login: mockLogin, logout: mockLogout, token: null, addresses: null,
      status: "pending", isAuthenticated: false, expiresAt: null,
      setAuthenticationStatus: jest.fn(),
    };

    render(<TokenRefresher />);

    await waitFor(() => expect(mockLogout).toHaveBeenCalled());
  });
});

describe("TokenRefresher — 긴 연결이 갱신을 부탁할 때(docs/68 §9)", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    interceptors.request.length = 0;
    interceptors.response.length = 0;
  });

  it("🔴 채팅 · 알림이 서버에게 닫히면 토큰을 갱신한다 — 여러 연결이 동시에 닫혀도 한 번만", async () => {
    let resolve;
    mockGetRequest.mockReturnValue(new Promise((r) => { resolve = r; }));
    mockDecode.mockReturnValue({ sub: "a@b.com", uid: 1, exp: 999 });
    mockAuth = {
      login: mockLogin, logout: mockLogout, token: "old", addresses: null,
      status: "succeeded", isAuthenticated: true, expiresAt: null,
      setAuthenticationStatus: jest.fn(),
    };
    render(<TokenRefresher />);

    window.dispatchEvent(new Event("auth:refresh-needed"));
    window.dispatchEvent(new Event("auth:refresh-needed"));
    resolve({ status: 200, headers: { access: "new" } });

    await waitFor(() => expect(mockLogin).toHaveBeenCalledTimes(1));
    expect(mockGetRequest).toHaveBeenCalledTimes(1);
  });

  it("로그인하지 않았으면 갱신하지 않는다", async () => {
    mockAuth = {
      login: mockLogin, logout: mockLogout, token: null, addresses: null,
      status: "succeeded", isAuthenticated: false, expiresAt: null,
      setAuthenticationStatus: jest.fn(),
    };
    render(<TokenRefresher />);

    window.dispatchEvent(new Event("auth:refresh-needed"));

    expect(mockGetRequest).not.toHaveBeenCalled();
  });
});
