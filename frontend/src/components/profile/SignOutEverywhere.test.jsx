import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import SignOutEverywhere from "components/profile/SignOutEverywhere";
import { withI18n } from "i18n/testing";
import { signOutEverywhere } from "util/api";

jest.mock("util/api");

/**
 * 모든 기기에서 로그아웃(user-service docs/68 §8).
 *
 * <p>알림을 받은 사용자가 실제로 다른 기기를 끊을 수 있는 곳이다. 이 기기도 로그아웃되므로 두 번 누르게 하고,
 * 결과(다른 기기는 최대 15분 안에)를 정직하게 말한다.
 */
describe("모든 기기에서 로그아웃", () => {
  afterEach(() => jest.clearAllMocks());

  it("한 번 눌러서는 끊지 않는다 — 확인을 묻는다", async () => {
    render(withI18n(<SignOutEverywhere />));

    await userEvent.click(screen.getByRole("button", { name: "모든 기기에서 로그아웃" }));

    expect(signOutEverywhere).not.toHaveBeenCalled();
    expect(screen.getByText(/이 기기를 포함한/)).toBeInTheDocument();
  });

  /** 🔴 "즉시" 라고 쓰면 거짓말이다 — 다른 기기의 액세스 토큰은 수명까지 유효하다. */
  it("다른 기기가 언제 끊기는지 정직하게 말한다", async () => {
    render(withI18n(<SignOutEverywhere />));
    await userEvent.click(screen.getByRole("button", { name: "모든 기기에서 로그아웃" }));

    expect(screen.getByText(/최대 15분 안에/)).toBeInTheDocument();
  });

  it("확인하면 끊고, 이 기기를 로그아웃시킨다", async () => {
    signOutEverywhere.mockResolvedValue({ revoked: 2 });
    const onSignedOut = jest.fn();
    render(withI18n(<SignOutEverywhere onSignedOut={onSignedOut} />));

    await userEvent.click(screen.getByRole("button", { name: "모든 기기에서 로그아웃" }));
    await userEvent.click(screen.getByRole("button", { name: "로그아웃" }));

    await waitFor(() => expect(onSignedOut).toHaveBeenCalledTimes(1));
    expect(signOutEverywhere).toHaveBeenCalledTimes(1);
  });

  it("취소하면 아무것도 하지 않는다", async () => {
    render(withI18n(<SignOutEverywhere />));

    await userEvent.click(screen.getByRole("button", { name: "모든 기기에서 로그아웃" }));
    await userEvent.click(screen.getByRole("button", { name: "취소" }));

    expect(signOutEverywhere).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "모든 기기에서 로그아웃" })).toBeInTheDocument();
  });

  /** 실패했는데 로그아웃된 것처럼 보이면 사용자는 끊겼다고 믿는다. 이 기기도 그대로 둔다. */
  it("실패하면 그렇게 말하고, 이 기기를 로그아웃시키지 않는다", async () => {
    signOutEverywhere.mockRejectedValue(new Error("500"));
    const onSignedOut = jest.fn();
    render(withI18n(<SignOutEverywhere onSignedOut={onSignedOut} />));

    await userEvent.click(screen.getByRole("button", { name: "모든 기기에서 로그아웃" }));
    await userEvent.click(screen.getByRole("button", { name: "로그아웃" }));

    expect(await screen.findByText(/로그아웃하지 못했습니다/)).toBeInTheDocument();
    expect(onSignedOut).not.toHaveBeenCalled();
  });
});
