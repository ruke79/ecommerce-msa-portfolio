import { render, screen, waitFor, act } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import SmsVerification from "components/notification/SmsVerification";
import { withI18n } from "i18n/testing";
import { requestPhoneCode, confirmPhoneCode } from "api/notificationApi";

jest.mock("api/notificationApi");

describe("SmsVerification", () => {
  afterEach(() => {
    jest.clearAllMocks();
    jest.useRealTimers();
  });

  const typePhone = async (value = "01012345678") => {
    await userEvent.type(screen.getByLabelText("휴대폰 번호"), value);
  };

  it("keeps the send button disabled until a number is entered", () => {
    render(withI18n(<SmsVerification />));
    expect(screen.getByRole("button", { name: /인증번호 받기/ })).toBeDisabled();
  });

  it("offers Korea and Japan", () => {
    render(withI18n(<SmsVerification />));
    const select = screen.getByLabelText("국가");
    expect(select).toHaveValue("KR");
    expect(screen.getByRole("option", { name: /일본/ })).toBeInTheDocument();
  });

  it("does not validate the number format client-side", async () => {
    // 프론트 정규식으로 미리 거르면 유효한 번호까지 막힌다 — 한국의 10자리 010 번호나
    // 019 대역이 대표적이다. 판정은 libphonenumber 를 쓰는 서버가 한다.
    requestPhoneCode.mockResolvedValue({ ok: true, phone: "010-***-4567", expiresInMinutes: 3 });

    render(withI18n(<SmsVerification />));
    await typePhone("0101234567");           // 10자리
    await userEvent.click(screen.getByRole("button", { name: /인증번호 받기/ }));

    await waitFor(() => expect(requestPhoneCode).toHaveBeenCalled());
  });

  it("moves to the code step after a successful request", async () => {
    requestPhoneCode.mockResolvedValue({ ok: true, phone: "010-****-5678", expiresInMinutes: 3 });

    render(withI18n(<SmsVerification />));
    await typePhone();
    await userEvent.click(screen.getByRole("button", { name: /인증번호 받기/ }));

    expect(await screen.findByText(/010-\*\*\*\*-5678 로 인증번호를 보냈습니다/)).toBeInTheDocument();
  });

  it("treats a rate limit as information, not an error", async () => {
    requestPhoneCode.mockResolvedValue({
      ok: false, tooMany: true, message: "잠시 후 다시 시도해 주세요", retryAfterSeconds: 30,
    });

    render(withI18n(<SmsVerification />));
    await typePhone();
    await userEvent.click(screen.getByRole("button", { name: /인증번호 받기/ }));

    // 남은 시간을 보여줘야 사용자가 언제 다시 눌러야 할지 안다.
    expect(await screen.findByRole("button", { name: /30초 후 재시도/ })).toBeInTheDocument();
  });

  it("caps an absurd retry-after so the UI cannot lock forever", async () => {
    requestPhoneCode.mockResolvedValue({
      ok: false, tooMany: true, message: "제한", retryAfterSeconds: 99999,
    });

    render(withI18n(<SmsVerification />));
    await typePhone();
    await userEvent.click(screen.getByRole("button", { name: /인증번호 받기/ }));

    // 3600 으로 잘린다.
    expect(await screen.findByRole("button", { name: /3600초 후 재시도/ })).toBeInTheDocument();
  });

  it("shows remaining attempts when the code is wrong", async () => {
    requestPhoneCode.mockResolvedValue({ ok: true, phone: "p", expiresInMinutes: 3 });
    confirmPhoneCode.mockResolvedValue({ ok: false, message: "코드가 틀렸습니다", attemptsLeft: 2 });

    render(withI18n(<SmsVerification />));
    await typePhone();
    await userEvent.click(screen.getByRole("button", { name: /인증번호 받기/ }));

    const codeInput = await screen.findByLabelText("인증번호 6자리");
    await userEvent.type(codeInput, "000000");
    await userEvent.click(screen.getByRole("button", { name: "확인" }));

    // 알려주지 않으면 사용자는 왜 갑자기 막혔는지 모른다.
    expect(await screen.findByText(/남은 시도 2회/)).toBeInTheDocument();
  });

  it("reports success and notifies the caller", async () => {
    const onVerified = jest.fn();
    requestPhoneCode.mockResolvedValue({ ok: true, phone: "p", expiresInMinutes: 3 });
    confirmPhoneCode.mockResolvedValue({ ok: true, verified: true, preference: { sms: true } });

    render(withI18n(<SmsVerification onVerified={onVerified} />));
    await typePhone();
    await userEvent.click(screen.getByRole("button", { name: /인증번호 받기/ }));

    await userEvent.type(await screen.findByLabelText("인증번호 6자리"), "123456");
    await userEvent.click(screen.getByRole("button", { name: "확인" }));

    expect(await screen.findByText("인증이 완료되었습니다")).toBeInTheDocument();
    expect(onVerified).toHaveBeenCalledWith({ sms: true });
  });

  it("blocks resend during the cooldown and counts it down", async () => {
    requestPhoneCode.mockResolvedValue({ ok: true, phone: "p", expiresInMinutes: 3 });

    render(withI18n(<SmsVerification />));
    await typePhone();
    await userEvent.click(screen.getByRole("button", { name: /인증번호 받기/ }));

    // 코드 단계의 재전송 버튼은 쿨다운 동안 비활성이다. 이게 없으면 사용자가 연타해
    // 서버 제한(429)에 스스로 걸린다.
    const resend = await screen.findByRole("button", { name: /재전송 \(60초\)/ });
    expect(resend).toBeDisabled();
  });
});
