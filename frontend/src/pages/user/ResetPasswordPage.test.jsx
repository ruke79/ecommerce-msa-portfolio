import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";

import ResetPasswordPage from "pages/user/ResetPasswordPage";
import { postRequest } from "util/api";
import { withI18n } from "i18n/testing";

jest.mock("util/api");

const mockNavigate = jest.fn();
jest.mock("react-router-dom", () => ({
    ...jest.requireActual("react-router-dom"),
    useNavigate: () => mockNavigate,
}));

const renderAt = (search = "?token=TOK") =>
    render(
        withI18n(
            <MemoryRouter initialEntries={[`/reset-password${search}`]}>
                <ResetPasswordPage />
            </MemoryRouter>
        )
    );

const fill = async (pw, confirm = pw) => {
    await userEvent.type(screen.getByLabelText("새 비밀번호"), pw);
    await userEvent.type(screen.getByLabelText("새 비밀번호 확인"), confirm);
    await userEvent.click(screen.getByRole("button", { name: /비밀번호 변경/ }));
};

/**
 * 🐛 <b>이 화면이 없었다.</b> 서버는 재설정 메일에 {@code /reset-password?token=…} 을 실어
 * 보내는데 프론트 전체에 그 문자열이 0건이었다 — 링크를 누르면 라우터 오류 화면이 떴다.
 * 비밀번호 찾기는 처음부터 끝까지 동작한 적이 없다.
 */
describe("비밀번호 재설정 화면", () => {
    beforeEach(() => {
        postRequest.mockResolvedValue({ data: { message: "비밀번호가 변경되었습니다." } });
    });

    afterEach(() => jest.clearAllMocks());

    /**
     * 🔴 토큰을 <b>바디로</b> 보낸다. 쿼리스트링은 nginx 액세스 로그·프록시·브라우저
     * 히스토리에 남는데, 이 토큰은 그 자체로 계정을 가져갈 수 있는 자격증명이다.
     */
    it("🔴 주소의 토큰을 바디에 담아 보낸다", async () => {
        renderAt("?token=TOK");

        await fill("newpw123");

        await waitFor(() =>
            expect(postRequest).toHaveBeenCalledWith(
                "/user-service/api/auth/public/reset-password",
                { token: "TOK", newPassword: "newpw123" }
            )
        );
    });

    it("성공하면 알리고 로그인 화면으로 보낸다", async () => {
        renderAt();

        await fill("newpw123");

        expect(await screen.findByText(/변경되었습니다/)).toBeInTheDocument();
        await waitFor(() => expect(mockNavigate).toHaveBeenCalledWith("/signin"), { timeout: 3000 });
    });

    /**
     * 재설정 토큰은 1회용이다. 확인란 불일치로 태우면 메일을 다시 받아야 하므로
     * 서버에 보내기 전에 막는다.
     */
    it("두 비밀번호가 다르면 요청하지 않는다 — 토큰은 1회용이다", async () => {
        renderAt();

        await fill("newpw123", "different");

        expect(await screen.findByRole("alert")).toHaveTextContent(/일치하지 않습니다/);
        expect(postRequest).not.toHaveBeenCalled();
    });

    it("너무 짧으면 요청하지 않는다", async () => {
        renderAt();

        await fill("123");

        expect(await screen.findByRole("alert")).toHaveTextContent(/6자 이상/);
        expect(postRequest).not.toHaveBeenCalled();
    });

    /** 토큰 없이 주소만 치고 들어온 경우 — 폼을 그려 봐야 반드시 실패한다. */
    it("토큰이 없으면 폼 대신 안내를 준다", () => {
        renderAt("");

        expect(screen.queryByLabelText("새 비밀번호")).not.toBeInTheDocument();
        expect(screen.getByRole("alert")).toHaveTextContent(/올바르지 않습니다/);
        expect(screen.getByRole("link", { name: /비밀번호 찾기/ })).toBeInTheDocument();
    });

    /** 만료·사용됨은 서버만 아는 사실이다. 그 문구를 그대로 보여준다. */
    it("서버가 거절하면 그 이유를 보여준다", async () => {
        postRequest.mockRejectedValue({
            response: { data: { message: "링크의 유효기간이 지났습니다." } },
        });
        renderAt();

        await fill("newpw123");

        expect(await screen.findByRole("alert")).toHaveTextContent(/유효기간이 지났습니다/);
    });
});

/**
 * 🔴 <b>문구를 사전으로 옮긴 것이 실제로 화면에 닿는지</b> 확인한다.
 *
 * <p>키만 바꿔 놓고 컴포넌트가 여전히 하드코딩된 한국어를 그리고 있어도, 한국어 테스트는
 * 전부 통과한다 — 일본어로 렌더해 봐야 드러난다.
 */
describe("비밀번호 재설정 화면 (일본어)", () => {
    const renderJa = () =>
        render(
            withI18n(
                <MemoryRouter initialEntries={["/reset-password?token=TOK"]}>
                    <ResetPasswordPage />
                </MemoryRouter>,
                "ja"
            )
        );

    beforeEach(() => {
        postRequest.mockResolvedValue({ data: { message: "変更しました。" } });
    });

    afterEach(() => jest.clearAllMocks());

    it("🔴 일본어로 렌더된다 — 한국어가 남아 있으면 안 된다", () => {
        renderJa();

        expect(screen.getByText("新しいパスワードの設定")).toBeInTheDocument();
        expect(screen.getByLabelText("新しいパスワード")).toBeInTheDocument();
        expect(screen.queryByText("새 비밀번호 설정")).not.toBeInTheDocument();
    });

    /** 치환 자리가 일본어 문구에서도 값으로 채워져야 한다. */
    it("검증 문구도 일본어다 — {{min}} 이 채워진 채로", async () => {
        renderJa();

        await userEvent.type(screen.getByLabelText("新しいパスワード"), "123");
        await userEvent.type(screen.getByLabelText("新しいパスワード（確認）"), "123");
        await userEvent.click(screen.getByRole("button", { name: /パスワードを変更/ }));

        const alert = await screen.findByRole("alert");
        expect(alert).toHaveTextContent("6文字以上");
        expect(alert).not.toHaveTextContent("{{min}}");
    });
});
