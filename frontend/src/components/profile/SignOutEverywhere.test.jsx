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
 * 결과(다른 기기도 곧바로)를 정직하게 말한다.
 *
 * <p>[JA] 全端末からログアウト(user-service docs/68 §8)。
 * 通知を受けたユーザーが実際に他の端末を切れる場所だ。この端末もログアウトされるので二度押させ、
 * 結果(他の端末もすぐに)を正直に言う。
 */
describe("모든 기기에서 로그아웃", () => {
  afterEach(() => jest.clearAllMocks());

  it("한 번 눌러서는 끊지 않는다 — 확인을 묻는다", async () => {
    render(withI18n(<SignOutEverywhere />));

    await userEvent.click(screen.getByRole("button", { name: "모든 기기에서 로그아웃" }));

    expect(signOutEverywhere).not.toHaveBeenCalled();
    expect(screen.getByText(/이 기기를 포함한/)).toBeInTheDocument();
  });

  /**
   * 다른 기기가 언제 끊기는지 정직하게 말한다 — 게이트웨이가 해제 시각 전의 토큰을 거절하므로 곧바로(docs/57 §22).
   * 예전 "최대 15분 안에" 는 게이트웨이가 서명만 보던 때의 문구다.
   *
   * <p>[JA] 他の端末がいつ切れるか正直に言う — ゲートウェイが解除時刻前のトークンを拒否するのですぐに(docs/57 §22)。
   * 以前の「最大 15 分以内に」はゲートウェイが署名だけ見ていた頃の文言だ。
   */
  it("다른 기기가 언제 끊기는지 정직하게 말한다", async () => {
    render(withI18n(<SignOutEverywhere />));
    await userEvent.click(screen.getByRole("button", { name: "모든 기기에서 로그아웃" }));

    expect(screen.getByText(/다른 기기도 곧바로/)).toBeInTheDocument();
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
  // [JA] 失敗したのにログアウトされたように見えるとユーザーは切断されたと信じる。この端末もそのままにする。
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
