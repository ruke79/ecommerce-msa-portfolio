import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import RecoveryCodes from "components/profile/RecoveryCodes";
import { withI18n } from "i18n/testing";
import api from "util/api";

jest.mock("util/api", () => ({ __esModule: true, default: { get: jest.fn(), post: jest.fn() } }));

/**
 * 2단계 인증 복구 코드(user-service docs/57 §22) — 2단계 인증이 켜져 있을 때만, 만든 코드는 한 번만 보인다, 다시 로그인이 필요하면 알린다.
 * [JA] リカバリーコード — 2FA が有効なときだけ、作ったコードは一度だけ表示、再ログインが必要なら知らせる。
 */
describe("2단계 인증 복구 코드", () => {
  afterEach(() => jest.clearAllMocks());

  it("2단계 인증이 꺼져 있으면 그리지 않는다", async () => {
    api.get.mockResolvedValue({ data: { is2faEnabled: false, smsEnabled: false, recoveryCodesRemaining: 0 } });
    const { container } = render(withI18n(<RecoveryCodes />));
    await waitFor(() => expect(api.get).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });

  it("🔴 만들면 새 코드 10개를 보여 주고 남은 수를 다시 읽는다", async () => {
    const codes = Array.from({ length: 10 }, (_, i) => `ABCDE-FGH${String(i + 20)}`);
    api.get.mockResolvedValueOnce({ data: { is2faEnabled: true, recoveryCodesRemaining: 0 } })
      .mockResolvedValueOnce({ data: { is2faEnabled: true, recoveryCodesRemaining: 10 } });
    api.post.mockResolvedValueOnce({ data: { codes } });

    render(withI18n(<RecoveryCodes />));
    await userEvent.click(await screen.findByRole("button", { name: "복구 코드 만들기" }));

    expect(api.post).toHaveBeenCalledWith("/user-service/api/auth/2fa/recovery-codes");
    expect(await screen.findByText("ABCDE-FGH20")).toBeInTheDocument();
    expect(screen.getAllByRole("listitem")).toHaveLength(10);
    expect(screen.getByText(/다시 보여 드리지 않습니다/)).toBeInTheDocument();
    expect(await screen.findByText("남은 코드 10개")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "복구 코드 새로 만들기" })).toBeInTheDocument();
  });

  it("서버가 최근 로그인을 요구하면(403) 다시 로그인하라고 알린다", async () => {
    api.get.mockResolvedValue({ data: { smsEnabled: true, recoveryCodesRemaining: 3 } });
    api.post.mockRejectedValueOnce({ response: { status: 403 } });

    render(withI18n(<RecoveryCodes />));
    await userEvent.click(await screen.findByRole("button", { name: "복구 코드 새로 만들기" }));

    expect(await screen.findByText(/다시 로그인한 뒤/)).toBeInTheDocument();
    expect(screen.queryAllByRole("listitem")).toHaveLength(0);
  });
});
