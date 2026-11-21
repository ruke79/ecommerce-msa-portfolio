import { render, screen } from "@testing-library/react";
import { withI18n } from "i18n/testing";
import DemoBanner from "components/demo/DemoBanner";

// 데모 계정 안내 띠 — 데모 사용자일 때만. [JA] デモアカウント案内バー — デモユーザーのときだけ。
let mockUser;
jest.mock("store/AuthContext", () => ({ useAuthContext: () => ({ user: mockUser }) }));

describe("DemoBanner", () => {
  it("🔴 데모 사용자면 안내를 띄운다", () => {
    mockUser = { email: "demo1@demo.invalid", demo: true };
    render(withI18n(<DemoBanner />, "ja"));
    expect(screen.getByRole("status").textContent).toContain("デモアカウント");
  });

  it("보통 사용자 · 로그인 전에는 없다", () => {
    mockUser = { email: "a@example.com", demo: false };
    const { container, rerender } = render(withI18n(<DemoBanner />, "ko"));
    expect(container.firstChild).toBeNull();
    mockUser = undefined;
    rerender(withI18n(<DemoBanner />, "ko"));
    expect(container.firstChild).toBeNull();
  });
});
