import { render, act } from "@testing-library/react";

const mockPublish = jest.fn();
jest.mock("util/authSync", () => ({
  SIGNED_IN: "signed-in",
  SIGNED_OUT: "signed-out",
  publishAuth: (...a) => mockPublish(...a),
}));
jest.mock("util/api", () => ({ __esModule: true, default: {}, getRequest: jest.fn() }));
jest.mock("react-redux", () => ({ useSelector: jest.fn() }));
jest.mock("react-router-dom", () => ({ useNavigate: () => jest.fn() }));
jest.mock("react-error-boundary", () => ({ useErrorBoundary: () => ({}) }));

const { ContextProvider, useAuthContext } = require("store/AuthContext");

let ctx;
const Probe = () => {
  ctx = useAuthContext();
  return null;
};

/** 로그인 · 로그아웃이 다른 탭에 알리는 조건(util/authSync). */
describe("AuthContext — 탭 간 알림", () => {
  beforeEach(() => {
    mockPublish.mockClear();
    render(<ContextProvider><Probe /></ContextProvider>);
  });

  it("로그인하면 사용자 id 와 함께 알린다 — 토큰은 넘기지 않는다", () => {
    act(() => ctx.login({ userId: "77" }, "eyJ.secret.token", 100));
    expect(mockPublish).toHaveBeenCalledWith("signed-in", "77");
    expect(JSON.stringify(mockPublish.mock.calls)).not.toContain("eyJ");
  });

  it("🔴 로그인돼 있던 탭의 로그아웃만 알린다 — 처음 열린 탭의 복구 실패가 다른 탭을 끊지 않게", () => {
    act(() => ctx.logout());
    expect(mockPublish).not.toHaveBeenCalled();

    act(() => ctx.login({ userId: "77" }, "t", 100));
    mockPublish.mockClear();
    act(() => ctx.logout());
    expect(mockPublish).toHaveBeenCalledWith("signed-out");
  });

  it("🔴 다른 탭을 따라 할 때(broadcast:false)는 알리지 않는다 — 탭끼리 주고받기가 끝나지 않는다", () => {
    act(() => ctx.login({ userId: "77" }, "t", 100, { broadcast: false }));
    act(() => ctx.logout({ broadcast: false }));
    expect(mockPublish).not.toHaveBeenCalled();
  });
});
