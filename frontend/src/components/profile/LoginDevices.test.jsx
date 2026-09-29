import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import LoginDevices from "components/profile/LoginDevices";
import { withI18n } from "i18n/testing";
import { forgetDevice, getMyDevices } from "util/api";

jest.mock("util/api");

const devices = [
  { id: 1, label: "Chrome · Windows", lastIp: "203.0.113.xxx",
    firstSeenAt: "2026-09-01T10:00:00", lastSeenAt: "2026-09-16T09:00:00" },
  { id: 2, label: "Safari · iOS", lastIp: "198.51.100.xxx",
    firstSeenAt: "2026-08-01T10:00:00", lastSeenAt: "2026-09-10T09:00:00" },
];

/**
 * 로그인 기기 목록.
 *
 * <p>"새로운 기기에서 로그인되었습니다" 알림을 받은 사용자가 <b>갈 곳</b>이다. 알림만
 * 있고 확인할 화면이 없으면 정말 조치가 필요한 상황인지 판단할 근거가 없다.
 */
describe("로그인 기기", () => {
  beforeEach(() => {
    getMyDevices.mockResolvedValue(devices);
    forgetDevice.mockResolvedValue({});
  });

  afterEach(() => jest.clearAllMocks());

  it("기기 목록을 보여준다", async () => {
    render(withI18n(<LoginDevices />));

    expect(await screen.findByText("Chrome · Windows")).toBeInTheDocument();
    expect(screen.getByText("Safari · iOS")).toBeInTheDocument();
  });

  it("마지막 로그인 시각과 위치를 함께 보여준다 — 본인인지 판단할 재료다", async () => {
    render(withI18n(<LoginDevices />));

    const row = (await screen.findByText("Chrome · Windows")).closest("li");
    expect(within(row).getByText(/203\.0\.113\.xxx/)).toBeInTheDocument();
  });

  /**
   * 🔴 모르는 기기를 봤을 때 <b>무엇을 해야 하는지</b>가 화면에 있어야 한다. 목록만
   * 보여 주면 사용자는 이상한 기기를 발견하고도 할 일을 모른다.
   */
  it("모르는 기기가 있으면 비밀번호를 바꾸라고 말한다", async () => {
    render(withI18n(<LoginDevices />));
    await screen.findByText("Chrome · Windows");

    expect(screen.getByText(/비밀번호를 바로 변경/)).toBeInTheDocument();
  });

  /**
   * 🔴 "기억 지우기" 가 로그아웃으로 읽히면 사용자는 조치를 했다고 믿고 더 이상
   * 아무것도 하지 않는다 — 실제로는 그 기기의 세션이 그대로 살아 있다.
   */
  it("기억 지우기가 로그아웃이 아니라고 분명히 말한다", async () => {
    render(withI18n(<LoginDevices />));
    await screen.findByText("Chrome · Windows");

    expect(screen.getByText(/로그아웃이 아닙니다/)).toBeInTheDocument();
  });

  it("기억 지우기를 누르면 그 기기만 지운다", async () => {
    render(withI18n(<LoginDevices />));

    const row = (await screen.findByText("Chrome · Windows")).closest("li");
    await userEvent.click(within(row).getByRole("button", { name: "기억 지우기" }));

    await waitFor(() => expect(forgetDevice).toHaveBeenCalledWith(1));
  });

  it("지운 뒤 목록을 다시 읽는다", async () => {
    render(withI18n(<LoginDevices />));

    const row = (await screen.findByText("Safari · iOS")).closest("li");
    await userEvent.click(within(row).getByRole("button", { name: "기억 지우기" }));

    await waitFor(() => expect(getMyDevices).toHaveBeenCalledTimes(2));
  });

  it("기록이 없으면 그렇게 말한다", async () => {
    getMyDevices.mockResolvedValue([]);

    render(withI18n(<LoginDevices />));

    expect(await screen.findByText(/기록된 기기가 없습니다/)).toBeInTheDocument();
  });

  /** 기기 목록 하나 때문에 보안 설정 화면 전체가 죽으면 안 된다. */
  it("불러오지 못해도 화면이 죽지 않는다", async () => {
    getMyDevices.mockRejectedValue(new Error("500"));

    render(withI18n(<LoginDevices />));

    expect(await screen.findByText(/불러오지 못했습니다/)).toBeInTheDocument();
  });

  it("지우지 못하면 이유를 보여준다", async () => {
    forgetDevice.mockRejectedValue(new Error("500"));

    render(withI18n(<LoginDevices />));
    const row = (await screen.findByText("Chrome · Windows")).closest("li");
    await userEvent.click(within(row).getByRole("button", { name: "기억 지우기" }));

    expect(await screen.findByText(/지우지 못했습니다/)).toBeInTheDocument();
  });
});
