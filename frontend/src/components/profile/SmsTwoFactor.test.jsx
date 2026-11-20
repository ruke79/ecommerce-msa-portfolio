import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import SmsTwoFactor from "components/profile/SmsTwoFactor";
import { withI18n } from "i18n/testing";
import api from "util/api";

jest.mock("util/api", () => ({ __esModule: true, default: { get: jest.fn(), post: jest.fn() } }));

/**
 * SMS 2단계 인증 켜기 · 끄기(user-service docs/24 §10) — 코드를 받아 확인해야 바뀌고, 서버의 거절 사유를 알려 준다.
 *
 * <p>[JA] SMS 2 段階認証のオン・オフ(user-service docs/24 §10) — コードを受け取って確認しないと変わらず、サーバーの拒否理由を伝える。
 */
describe("SMS 2단계 인증 설정", () => {
  afterEach(() => jest.clearAllMocks());

  it("켜기 — 코드를 받고(마스킹 번호) 6자리를 보내면 켜진다", async () => {
    api.get.mockResolvedValueOnce({ data: { is2faEnabled: false, smsEnabled: false } })
      .mockResolvedValueOnce({ data: { is2faEnabled: false, smsEnabled: true } });
    api.post.mockResolvedValueOnce({ data: { maskedPhone: "+8210****5678", ttlMinutes: 5 } })
      .mockResolvedValueOnce({ data: "SMS 2FA enabled" });

    render(withI18n(<SmsTwoFactor />));
    expect(await screen.findByText("사용 안 함")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "켜기" }));

    expect(await screen.findByText(/\+8210\*\*\*\*5678 로 보낸 6자리/)).toBeInTheDocument();
    expect(api.post.mock.calls[0][0]).toBe("/user-service/api/auth/sms-2fa/code");
    await userEvent.type(screen.getByLabelText("SMS 인증번호"), "123456");
    await userEvent.click(screen.getByRole("button", { name: "확인" }));

    await waitFor(() => expect(api.post.mock.calls[1][0]).toBe("/user-service/api/auth/sms-2fa/enable?code=123456"));
    expect(await screen.findByText("SMS 2단계 인증을 켰습니다.")).toBeInTheDocument();
    expect(await screen.findByText("사용 중")).toBeInTheDocument();
  });

  it("끄기 — 켜져 있으면 끄기 버튼, 코드는 disable 로", async () => {
    api.get.mockResolvedValue({ data: { smsEnabled: true } });
    api.post.mockResolvedValueOnce({ data: { maskedPhone: "+8190****1234" } }).mockResolvedValueOnce({ data: "ok" });

    render(withI18n(<SmsTwoFactor />));
    await userEvent.click(await screen.findByRole("button", { name: "끄기" }));
    await userEvent.type(await screen.findByLabelText("SMS 인증번호"), "654321");
    await userEvent.click(screen.getByRole("button", { name: "확인" }));

    await waitFor(() => expect(api.post.mock.calls[1][0]).toBe("/user-service/api/auth/sms-2fa/disable?code=654321"));
  });

  it.each([
    [{ status: 403, data: "reauth_required" }, /다시 로그인한 뒤/],
    [{ status: 409, data: { code: "no_phone" } }, /휴대폰 번호를 인증해 주세요/],
    [{ status: 409, data: { code: "phone_changed" } }, /켤 때와 달라/],
    [{ status: 429, data: {} }, /잠시 후 다시 시도/],
  ])("거절 사유를 알려 준다 %#", async (response, text) => {
    api.get.mockResolvedValue({ data: { smsEnabled: false } });
    api.post.mockRejectedValueOnce({ response });

    render(withI18n(<SmsTwoFactor />));
    await userEvent.click(await screen.findByRole("button", { name: "켜기" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(text);
    expect(screen.queryByLabelText("SMS 인증번호")).not.toBeInTheDocument();
  });

  it("틀린 코드는 401 — 입력을 비우고 다시 받을 수 있다", async () => {
    api.get.mockResolvedValue({ data: { smsEnabled: false } });
    api.post.mockResolvedValueOnce({ data: { maskedPhone: "+8210****5678" } })
      .mockRejectedValueOnce({ response: { status: 401, data: "Invalid 2FA Code" } });

    render(withI18n(<SmsTwoFactor />));
    await userEvent.click(await screen.findByRole("button", { name: "켜기" }));
    const input = await screen.findByLabelText("SMS 인증번호");
    await userEvent.type(input, "000000");
    await userEvent.click(screen.getByRole("button", { name: "확인" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("인증번호가 올바르지 않습니다.");
    expect(input).toHaveValue("");
  });

  it("상태를 모르면 아무 버튼도 보여 주지 않는다 — 켜진 계정에 '켜기' 를 띄우지 않게", async () => {
    api.get.mockRejectedValue(new Error("down"));
    const { container } = render(withI18n(<SmsTwoFactor />));
    await waitFor(() => expect(api.get).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });
});
