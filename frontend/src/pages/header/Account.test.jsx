import { render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import Account from "pages/header/Account";
import { withI18n } from "i18n/testing";
import { useAuthContext } from "store/AuthContext";

jest.mock("store/AuthContext", () => ({ useAuthContext: jest.fn(), STATUS: {} }));
jest.mock("react-redux", () => ({ useSelector: (pick) => pick({ cart: { cartItems: [] } }) }));
jest.mock("hook/hooks", () => ({ useLogout: () => ({ logout: jest.fn(), isPending: false }), useReRenderer: () => jest.fn() }));
jest.mock("util/api", () => ({ postRequest: jest.fn() }));

const LONG = "a.very.long.email.address.for.layout.testing@some-long-company-domain.example.com";

const renderAccount = ({ email = LONG, roles = ["ROLE_USER"], signedIn = true, lang = "ko" } = {}) => {
    useAuthContext.mockReturnValue({
        user: signedIn ? { email, roles } : {}, token: signedIn ? "t" : null, isAuthenticated: signedIn,
    });
    return render(withI18n(<MemoryRouter><Account /></MemoryRouter>, lang));
};

/**
 * 헤더의 계정 메뉴 — 마우스를 올리면 뜨는 팝업.
 *
 * 🐛 문구가 전부 영어였고, 이메일과 버튼이 한 줄이라 이메일이 길면 버튼이 팝업 밖으로 밀렸다.
 */
describe("계정 메뉴 팝업", () => {
    it("🔴 한국어로 — 예전 영어 문구가 남지 않는다", () => {
        const { container } = renderAccount();

        expect(screen.getByText("계정 및 목록")).toBeInTheDocument();
        expect(within(screen.getByTestId("account-actions")).getByRole("link", { name: "내 계정" })).toBeInTheDocument();
        expect(screen.getByRole("button", { name: "로그아웃" })).toBeInTheDocument();
        for (const english of ["Hello", "Account & Lists", "Hi,", "Profile", "Sign Out", "Your List", "Your Account",
            "Create a list", "Returns", "Cart"]) {
            expect(container.textContent).not.toContain(english);
        }
    });

    it("🔴 긴 이메일은 제 줄에서 말줄임(전체는 title) — 버튼은 다른 줄이라 밀리지 않는다", () => {
        renderAccount();

        const emailLink = screen.getByTestId("account-email");
        expect(emailLink).toHaveClass("truncate");
        expect(emailLink).toHaveAttribute("title", LONG);

        const actions = screen.getByTestId("account-actions");
        expect(actions).toHaveClass("flex-wrap");
        expect(actions).not.toContainElement(emailLink);
        expect(within(actions).getByRole("button", { name: "로그아웃" })).toBeInTheDocument();
    });

    it("헤더 줄의 인사는 이메일 앞부분만, 넘치면 말줄임", () => {
        renderAccount();

        const hello = screen.getByText("안녕하세요, a.very.long.email.address.for.layout.testing님");
        expect(hello).toHaveClass("truncate");
        expect(hello).toHaveAttribute("title", LONG);
    });

    it("메뉴는 있는 화면으로 가는 링크다", () => {
        renderAccount();

        expect(screen.getAllByRole("link", { name: "주문 내역" })[0]).toHaveAttribute("href", "/profile/orders");
        expect(screen.getByRole("link", { name: "배송지 관리" })).toHaveAttribute("href", "/profile/address");
        expect(screen.getByRole("link", { name: "1:1 고객 상담" })).toHaveAttribute("href", "/support");
    });

    it("상담원에게는 상담 데스크 버튼도 같은 줄에(줄바꿈 가능)", () => {
        renderAccount({ roles: ["ROLE_SUPPORT"] });

        expect(within(screen.getByTestId("account-actions")).getAllByRole("link")).toHaveLength(2);
    });

    it("일본어", () => {
        renderAccount({ lang: "ja" });

        expect(screen.getByText("アカウント＆リスト")).toBeInTheDocument();
        expect(screen.getByRole("button", { name: "ログアウト" })).toBeInTheDocument();
    });

    it("로그인 전에는 로그인·회원가입", () => {
        renderAccount({ signedIn: false });

        expect(screen.getByText("안녕하세요, 로그인해 주세요")).toBeInTheDocument();
        expect(screen.getByRole("button", { name: "로그인" })).toBeInTheDocument();
        expect(screen.getByRole("link", { name: "회원가입" })).toHaveAttribute("href", "/register");
        expect(screen.queryByTestId("account-email")).toBeNull();
    });
});
