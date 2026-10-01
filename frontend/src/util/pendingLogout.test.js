import { markPendingLogout, clearPendingLogout, hasPendingLogout } from "./pendingLogout";

describe("pendingLogout — 서버에 반영되지 못한 로그아웃 표시", () => {
  beforeEach(() => window.localStorage.clear());

  it("표시 · 확인 · 지우기", () => {
    expect(hasPendingLogout()).toBe(false);
    markPendingLogout();
    expect(hasPendingLogout()).toBe(true);
    clearPendingLogout();
    expect(hasPendingLogout()).toBe(false);
  });

  it("🔒 토큰 · 사용자 정보를 남기지 않는다 — 값은 '1' 뿐", () => {
    markPendingLogout();
    expect(window.localStorage.length).toBe(1);
    expect(window.localStorage.getItem("ecommerce-pending-logout")).toBe("1");
  });
});
