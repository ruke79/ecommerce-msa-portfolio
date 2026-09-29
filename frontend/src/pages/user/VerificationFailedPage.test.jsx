import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import VerificationFailedPage from "pages/user/VerificationFailedPage";
import { withI18n } from "i18n/testing";

jest.mock("util/api");

const renderAt = (search) =>
    render(
        withI18n(
            <MemoryRouter initialEntries={[`/verification-failed${search}`]}>
                <VerificationFailedPage />
            </MemoryRouter>
        )
    );

/**
 * 🐛 <b>이 화면이 없었다.</b> user-service 의 {@code RegistrationController} 는 인증 실패 시
 * {@code /verification-failed?reason=…} 으로 리다이렉트하는데 프론트에 라우트가 없어서,
 * 링크를 늦게 누른 사용자는 <b>라우터 오류 화면</b>을 봤다. /forgot 라우트가 없어 비밀번호를
 * 되찾을 수 없던 것과 똑같은 종류의 구멍이다.
 */
describe("가입 확인 실패 화면", () => {
    it("만료된 링크임을 알려준다", () => {
        renderAt("?reason=expired");

        expect(screen.getByText(/유효기간이 지났습니다/)).toBeInTheDocument();
        expect(screen.getByText(/24시간/)).toBeInTheDocument();
    });

    it("잘못된 링크는 다르게 설명한다 — 기다린 게 문제가 아니다", () => {
        renderAt("?reason=invalid");

        expect(screen.getByText(/확인할 수 없습니다/)).toBeInTheDocument();
    });

    /** 🔴 사과 문구가 아니라 이것이 이 화면의 존재 이유다. */
    it("🔴 재발송 폼을 함께 준다 — 스스로 빠져나올 수 있어야 한다", () => {
        renderAt("?reason=expired");

        expect(screen.getByLabelText(/이메일/)).toBeInTheDocument();
        expect(screen.getByRole("button", { name: /재발송/ })).toBeInTheDocument();
    });

    /** 서버가 이유 코드를 늘려도 화면이 비면 안 된다. */
    it("모르는 이유·이유 없음에도 안내와 폼이 뜬다", () => {
        renderAt("?reason=something-new");
        expect(screen.getByRole("button", { name: /재발송/ })).toBeInTheDocument();

        renderAt("");
        expect(screen.getAllByRole("button", { name: /재발송/ })).not.toHaveLength(0);
    });
});
