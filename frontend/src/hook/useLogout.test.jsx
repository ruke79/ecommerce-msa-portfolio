import { renderHook, act, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

const mockNavigate = jest.fn();
jest.mock("react-router-dom", () => ({ useNavigate: () => mockNavigate }));
const mockLogout = jest.fn();
jest.mock("store/AuthContext", () => ({ useAuthContext: () => ({ logout: mockLogout }) }));
const mockToast = { error: jest.fn(), success: jest.fn() };
jest.mock("react-hot-toast", () => ({ __esModule: true, default: mockToast }));
const mockPost = jest.fn();
jest.mock("util/api", () => ({
  __esModule: true,
  default: {},
  postRequest: (...a) => mockPost(...a),
  getRequest: jest.fn(),
  putRequest: jest.fn(),
  deleteRequest: jest.fn(),
  patchRequest: jest.fn(),
}));

const { useLogout } = require("hook/hooks");
const { hasPendingLogout } = require("util/pendingLogout");

const wrapper = ({ children }) => (
  <QueryClientProvider client={new QueryClient({ defaultOptions: { mutations: { retry: false } } })}>
    {children}
  </QueryClientProvider>
);

/** 🔴 로그아웃 요청이 실패해도 화면은 로그아웃한다 — 서버 로그아웃은 다음 접속 때 마무리(docs/68 §13). */
describe("useLogout — 서버 로그아웃이 실패하면", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    window.localStorage.clear();
    jest.spyOn(console, "error").mockImplementation(() => {});
  });

  it("🔴 화면은 지금 로그아웃하고, 마무리 표시를 남긴다", async () => {
    mockPost.mockRejectedValue(new Error("Network Error"));
    const { result } = renderHook(() => useLogout(), { wrapper });

    act(() => result.current.logout());

    await waitFor(() => expect(mockLogout).toHaveBeenCalled());
    expect(mockNavigate).toHaveBeenCalledWith("/signin");
    expect(hasPendingLogout()).toBe(true);
    expect(mockToast.error).toHaveBeenCalled();
  });

  it("성공하면 표시를 지운다", async () => {
    window.localStorage.setItem("ecommerce-pending-logout", "1");
    mockPost.mockResolvedValue({ status: 200 });
    const { result } = renderHook(() => useLogout(), { wrapper });

    act(() => result.current.logout());

    await waitFor(() => expect(mockLogout).toHaveBeenCalled());
    expect(hasPendingLogout()).toBe(false);
  });
});
