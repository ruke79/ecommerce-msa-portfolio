import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import ResendVerification from "components/auth/ResendVerification";
import { postRequest } from "util/api";
import { withI18n } from "i18n/testing";

jest.mock("util/api");

/** i18n 컨텍스트로 감싸 렌더한다 — useTranslation 은 Provider 밖에서 던진다. */
const renderResend = (props = {}) => render(withI18n(<ResendVerification {...props} />));

/**
 * 🐛 <b>서버는 이 기능을 갖고 있었는데 부를 방법이 없었다.</b>
 * {@code POST /api/auth/public/resend-verification} 은 구현돼 있었지만 프론트 어디에서도
 * 부르지 않았다. 로그인 실패 문구가 "확인 메일 재발송을 이용해 주세요" 라고 안내하는데
 * 이용할 화면이 없었고, 링크는 24시간이면 만료되며 같은 주소로 재가입도 막힌다 —
 * 메일을 못 받은 계정은 <b>막다른 길</b>이었다.
 */
describe("확인 메일 재발송", () => {
    beforeEach(() => {
        postRequest.mockResolvedValue({ data: { message: "확인 메일을 보냈습니다." } });
    });

    afterEach(() => jest.clearAllMocks());

    /** 🔴 이 파일의 핵심 — 이 호출이 없으면 화면은 있지만 아무 일도 일어나지 않는다. */
    it("입력한 주소로 재발송을 요청한다", async () => {
        renderResend();

        await userEvent.type(screen.getByLabelText(/이메일/), "buyer@example.com");
        await userEvent.click(screen.getByRole("button", { name: /재발송/ }));

        await waitFor(() =>
            expect(postRequest).toHaveBeenCalledWith(
                "/user-service/api/auth/public/resend-verification",
                { email: "buyer@example.com" }
            )
        );
    });

    it("이미 아는 주소는 채워 둔다 — 다시 치게 하지 않는다", () => {
        renderResend({ defaultEmail: "buyer@example.com" });

        expect(screen.getByLabelText(/이메일/)).toHaveValue("buyer@example.com");
    });

    /**
     * 서버는 가입 여부와 무관하게 <b>같은 문구</b>를 돌려준다 — 다르게 답하면 이 화면이
     * 가입 여부 확인 도구가 된다. 그래서 화면도 추측하지 않고 서버 문구를 그대로 쓴다.
     */
    it("서버가 준 문구를 그대로 보여준다", async () => {
        postRequest.mockResolvedValue({
            data: { message: "입력하신 주소로 확인 메일을 보냈습니다." },
        });
        renderResend({ defaultEmail: "buyer@example.com" });

        await userEvent.click(screen.getByRole("button", { name: /재발송/ }));

        expect(
            await screen.findByText("입력하신 주소로 확인 메일을 보냈습니다.")
        ).toBeInTheDocument();
    });

    /**
     * 게이트웨이의 인증 전 속도 제한에 걸리는 것이 현실적으로 가장 흔한 실패다.
     * "실패했습니다" 만 띄우면 사용자는 계속 다시 누르고, 그러면 더 오래 막힌다.
     */
    it("속도 제한에 걸리면 기다리라고 말한다", async () => {
        postRequest.mockRejectedValue({ response: { status: 429 } });
        renderResend({ defaultEmail: "buyer@example.com" });

        await userEvent.click(screen.getByRole("button", { name: /재발송/ }));

        expect(await screen.findByRole("alert")).toHaveTextContent(/잠시 후/);
    });

    it("보내는 동안 버튼을 잠근다 — 두 번 누르면 메일이 두 통 간다", async () => {
        let resolve;
        postRequest.mockReturnValue(new Promise((r) => { resolve = r; }));
        renderResend({ defaultEmail: "buyer@example.com" });

        await userEvent.click(screen.getByRole("button", { name: /재발송|보내는 중/ }));

        expect(screen.getByRole("button", { name: /보내는 중/ })).toBeDisabled();

        // 프라미스를 우리가 직접 푸는 자리라 act 로 감싼다 — 감싸지 않으면 해제 뒤의 상태
        // 갱신이 React 의 렌더 배치 밖에서 일어나 아래 단언이 갱신 전 DOM 을 볼 수 있다.
        // (이 저장소는 user-event 13 + React 18 조합이라 act 경고 자체는 어차피 남는다.)
        await act(async () => resolve({ data: { message: "ok" } }));

        await waitFor(() => expect(screen.getByRole("button")).not.toBeDisabled());
    });
});
