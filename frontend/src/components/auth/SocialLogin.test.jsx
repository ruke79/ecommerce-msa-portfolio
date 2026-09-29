import { render as rtlRender, screen, waitFor } from "@testing-library/react";

import SocialLogin from "components/auth/SocialLogin";
import { withI18n } from "i18n/testing";
import { getRequest } from "util/api";

// 버튼 문구가 사전에서 오므로 I18nProvider 안에서 그린다(기본 한국어).
const render = (ui, lang) => rtlRender(withI18n(ui, lang));

jest.mock("util/api", () => ({
  __esModule: true,
  getRequest: jest.fn(),
}));

// 핵심 계약: 버튼은 **서버가 아는 프로바이더만** 그린다. 하드코딩된 버튼은
// client-id 없는 프로바이더로 이동한 뒤에야 에러를 보여주게 된다.
describe("SocialLogin", () => {
  beforeEach(() => jest.clearAllMocks());

  it("서버가 준 프로바이더만 그린다", async () => {
    getRequest.mockResolvedValue({ data: ["google", "github"] });
    render(<SocialLogin />);

    expect(await screen.findByTestId("social-google")).toBeInTheDocument();
    expect(screen.getByTestId("social-github")).toBeInTheDocument();
    expect(screen.queryByTestId("social-facebook")).not.toBeInTheDocument();
  });

  it("프로바이더가 없으면 아무것도 그리지 않는다 — 구분선도", async () => {
    getRequest.mockResolvedValue({ data: [] });
    const { container } = render(<SocialLogin />);

    await waitFor(() => expect(getRequest).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });

  it("목록 조회가 실패해도 조용히 사라진다 — 이메일 로그인을 막지 않는다", async () => {
    getRequest.mockRejectedValue(new Error("network"));
    const { container } = render(<SocialLogin />);

    await waitFor(() => expect(getRequest).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });

  it("버튼을 누르면 전체 페이지 이동으로 인가 경로에 간다 — XHR 로는 동의 화면에 갈 수 없다", async () => {
    getRequest.mockResolvedValue({ data: ["google"] });
    delete window.location;
    window.location = { href: "" };

    render(<SocialLogin />);
    (await screen.findByTestId("social-google")).click();

    expect(window.location.href).toBe("/user-service/oauth2/authorization/google");
  });

  it("버튼 문구가 화면 언어를 따른다 — 상표 이름은 그대로", async () => {
    getRequest.mockResolvedValue({ data: ["google"] });
    render(<SocialLogin />);
    expect(await screen.findByTestId("social-google")).toHaveTextContent("Google 계정으로 로그인");
  });

  it("일본어 화면에서는 일본어로 그린다", async () => {
    getRequest.mockResolvedValue({ data: ["google"] });
    render(<SocialLogin />, "ja");

    expect(await screen.findByTestId("social-google")).toHaveTextContent("Googleアカウントでログイン");
    expect(screen.getByText("または")).toBeInTheDocument();
  });

  it("카카오·LINE 은 각 사 가이드의 문구와 색으로 그린다", async () => {
    getRequest.mockResolvedValue({ data: ["kakao", "line"] });
    render(<SocialLogin />);

    const kakao = await screen.findByTestId("social-kakao");
    expect(kakao).toHaveTextContent("카카오 로그인");
    expect(kakao.className).toContain("bg-[#FEE500]");
    const line = screen.getByTestId("social-line");
    expect(line).toHaveTextContent("LINE으로 로그인");
    expect(line.className).toContain("bg-[#06C755]");
  });

  it("카카오·LINE — 일본어 화면", async () => {
    getRequest.mockResolvedValue({ data: ["kakao", "line"] });
    render(<SocialLogin />, "ja");

    expect(await screen.findByTestId("social-kakao")).toHaveTextContent("カカオでログイン");
    expect(screen.getByTestId("social-line")).toHaveTextContent("LINEでログイン");
  });

  it("카카오 버튼을 누르면 user-service 의 인가 시작점으로 전체 페이지 이동한다", async () => {
    getRequest.mockResolvedValue({ data: ["kakao"] });
    delete window.location;
    window.location = { href: "" };
    render(<SocialLogin />);

    (await screen.findByTestId("social-kakao")).click();

    expect(window.location.href).toBe("/user-service/oauth2/authorization/kakao");
  });
});
