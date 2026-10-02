import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import Passkeys from "components/profile/Passkeys";
import { withI18n } from "i18n/testing";
import { deletePasskey, getPasskeyRegistrationOptions, getPasskeys, registerPasskey } from "util/api";

jest.mock("util/api");

/**
 * 패스키 관리(user-service docs/68 §20) — 등록 · 목록 · 삭제, 그리고 "최근 로그인" 안내.
 */
const bytes = (...values) => new Uint8Array(values).buffer;
const fakeCredential = {
  id: "AQI", rawId: bytes(1, 2), type: "public-key",
  getClientExtensionResults: () => ({}),
  response: { clientDataJSON: bytes(9), attestationObject: bytes(7), getTransports: () => ["internal"] },
};
const options = { challenge: "AAEC", user: { id: "AwQF", name: "a@example.com", displayName: "A" }, excludeCredentials: [] };

describe("패스키 관리", () => {
  let create;

  beforeEach(() => {
    window.PublicKeyCredential = function PublicKeyCredential() {};
    create = jest.fn().mockResolvedValue(fakeCredential);
    Object.defineProperty(navigator, "credentials", { value: { create, get: jest.fn() }, configurable: true });
    getPasskeys.mockResolvedValue([
      { id: 3, name: "내 노트북", createdAt: "2026-10-01T10:00:00", lastUsedAt: null, synced: true },
    ]);
    getPasskeyRegistrationOptions.mockResolvedValue(options);
    registerPasskey.mockResolvedValue({ id: 4 });
    deletePasskey.mockResolvedValue({});
  });

  afterEach(() => {
    jest.clearAllMocks();
    delete window.PublicKeyCredential;
  });

  it("목록 — 이름 · 동기화 표시 · 아직 사용 전", async () => {
    render(withI18n(<Passkeys />));
    expect(await screen.findByText("내 노트북")).toBeInTheDocument();
    expect(screen.getByText("동기화됨")).toBeInTheDocument();
    expect(screen.getByText(/아직 사용하지 않음/)).toBeInTheDocument();
  });

  it("추가 — 서버 옵션으로 기기에 만들게 하고, 결과를 이름과 함께 보낸다", async () => {
    render(withI18n(<Passkeys />));
    await userEvent.type(await screen.findByLabelText(/이름/), "내 휴대폰");
    await userEvent.click(screen.getByRole("button", { name: "패스키 추가" }));

    await waitFor(() => expect(registerPasskey).toHaveBeenCalled());
    const { publicKey } = create.mock.calls[0][0];
    expect(new Uint8Array(publicKey.challenge)).toEqual(new Uint8Array([0, 1, 2]));
    expect(registerPasskey).toHaveBeenCalledWith("내 휴대폰", expect.objectContaining({
      rawId: "AQI", response: expect.objectContaining({ attestationObject: "Bw" }),
    }));
    expect(await screen.findByText("패스키를 등록했습니다.")).toBeInTheDocument();
  });

  it("🔴 최근 로그인이 아니면(reauth_required) 다시 로그인하라고 안내한다 — 기기 창은 열지 않는다", async () => {
    getPasskeyRegistrationOptions.mockRejectedValue({ response: { status: 403, data: { code: "reauth_required" } } });
    render(withI18n(<Passkeys />));
    await userEvent.click(await screen.findByRole("button", { name: "패스키 추가" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("다시 로그인한 뒤 10분 안에");
    expect(create).not.toHaveBeenCalled();
  });

  it("기기 창을 닫으면 아무 오류도 보이지 않는다", async () => {
    create.mockRejectedValue({ name: "NotAllowedError" });
    render(withI18n(<Passkeys />));
    await userEvent.click(await screen.findByRole("button", { name: "패스키 추가" }));

    await waitFor(() => expect(create).toHaveBeenCalled());
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(registerPasskey).not.toHaveBeenCalled();
  });

  it("삭제", async () => {
    render(withI18n(<Passkeys />));
    await screen.findByText("내 노트북");
    await userEvent.click(screen.getByRole("button", { name: "삭제" }));
    await waitFor(() => expect(deletePasskey).toHaveBeenCalledWith(3));
  });

  it("지원하지 않는 브라우저 — 추가 버튼 대신 안내", async () => {
    delete window.PublicKeyCredential;
    render(withI18n(<Passkeys />));
    expect(await screen.findByText("이 브라우저는 패스키를 지원하지 않습니다.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "패스키 추가" })).not.toBeInTheDocument();
  });
});
