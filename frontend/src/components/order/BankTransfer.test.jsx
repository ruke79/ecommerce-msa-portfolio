import { fireEvent, render as rtlRender, screen, waitFor } from "@testing-library/react";

import BankTransfer from "components/order/BankTransfer";
import { withI18n } from "i18n/testing";

// 컴포넌트가 useTranslation 을 쓰므로 Provider 로 감싼다 — rerender 도 같은 wrapper 를 쓴다.
const render = (ui, options) => rtlRender(ui, { wrapper: ({ children }) => withI18n(children), ...options });

jest.mock("util/api", () => ({
    startBankTransfer: jest.fn(),
    getBankTransferNotice: jest.fn(),
    cancelBankTransfer: jest.fn(),
}));
jest.mock("react-hot-toast", () => ({
    __esModule: true,
    default: { success: jest.fn(), error: jest.fn() },
}));

const { startBankTransfer, getBankTransferNotice, cancelBankTransfer } = require("util/api");
const toast = require("react-hot-toast").default;

const notice = {
    bank: "국민은행", account: "123-456-789", holder: "예시상점",
    amount: "80973", currency: "KRW", depositorName: "홍길동", depositDeadline: "2026-09-21T10:00:00",
};

beforeEach(() => jest.clearAllMocks());

/** 무통장입금 상태 기계의 고객 쪽 — 안내 받기 → 입금 대기 → (취소). 금액은 서버가 준다. */
describe("BankTransfer", () => {
    it("결제 전이면 입금자명을 받아 안내를 요청한다 — 본문은 어느 주문·누구 뿐", async () => {
        startBankTransfer.mockResolvedValue(notice);
        render(<BankTransfer order={{ trackingId: "t-1", orderStatus: "NOT_PROCESSED", paymentMethod: "cash" }} />);

        fireEvent.change(screen.getByLabelText("입금자명"), { target: { value: " 홍길동 " } });
        fireEvent.click(screen.getByRole("button", { name: "입금 안내 받기" }));

        await waitFor(() => expect(startBankTransfer).toHaveBeenCalledWith("t-1", "홍길동"));
        expect(await screen.findByTestId("bank-transfer-notice")).toBeInTheDocument();
        expect(screen.getByText("123-456-789")).toBeInTheDocument();
        expect(screen.getByText(/80,973/)).toBeInTheDocument();     // 서버의 청구액(KRW), total 환산 아님
    });

    it("입금자명이 비면 보내지 않는다", () => {
        render(<BankTransfer order={{ trackingId: "t-1", orderStatus: "NOT_PROCESSED" }} />);
        fireEvent.click(screen.getByRole("button", { name: "입금 안내 받기" }));
        expect(startBankTransfer).not.toHaveBeenCalled();
        expect(toast.error).toHaveBeenCalled();
    });

    it("입금 대기면 안내를 다시 읽어 보여 주고 취소할 수 있다", async () => {
        getBankTransferNotice.mockResolvedValue(notice);
        cancelBankTransfer.mockResolvedValue({ orderStatus: "CANCELED" });
        const onChanged = jest.fn();
        render(<BankTransfer order={{ trackingId: "t-1", orderStatus: "UNPAID" }} onChanged={onChanged} />);

        expect(await screen.findByText("예시상점")).toBeInTheDocument();
        fireEvent.click(screen.getByRole("button", { name: "입금 전 주문 취소" }));

        await waitFor(() => expect(cancelBankTransfer).toHaveBeenCalledWith("t-1"));
        await waitFor(() => expect(onChanged).toHaveBeenCalled());
    });

    it("서버가 거절하면 이유를 보여 준다 — 계좌 미설정 등", async () => {
        startBankTransfer.mockRejectedValue({ response: { data: { message: "입금 계좌가 설정되지 않았습니다." } } });
        render(<BankTransfer order={{ trackingId: "t-1", orderStatus: "NOT_PROCESSED" }} />);
        fireEvent.change(screen.getByLabelText("입금자명"), { target: { value: "홍길동" } });
        fireEvent.click(screen.getByRole("button", { name: "입금 안내 받기" }));

        expect(await screen.findByRole("alert")).toHaveTextContent("입금 계좌가 설정되지 않았습니다.");
    });

    /** 가상계좌는 포트원 결제창이 발급한다 — 여기서 안내를 만들 일이 없고, 입금자명도 없다. */
    it("포트원 가상계좌: 입금 대기면 PG 의 계좌를 보여 주고, 결제 전이면 아무것도 그리지 않는다", async () => {
        getBankTransferNotice.mockResolvedValue({ ...notice, provider: "PORTONE", bank: "KOOKMIN", depositorName: null });
        const { unmount } = render(
            <BankTransfer order={{ trackingId: "t-1", orderStatus: "UNPAID", paymentMethod: "portone" }} />);

        expect(await screen.findByText("KOOKMIN")).toBeInTheDocument();   // 안내가 로드된 뒤
        expect(screen.getByText("가상계좌 입금 대기 중")).toBeInTheDocument();
        expect(screen.queryByText("입금자명")).not.toBeInTheDocument();
        expect(screen.getByText(/포트원이 자동으로 확인/)).toBeInTheDocument();
        unmount();

        const { container } = render(
            <BankTransfer order={{ trackingId: "t-1", orderStatus: "NOT_PROCESSED", paymentMethod: "portone" }} />);
        expect(container).toBeEmptyDOMElement();
    });

    it("결제된 주문에는 아무것도 그리지 않는다", () => {
        const { container } = render(<BankTransfer order={{ trackingId: "t-1", orderStatus: "PAID" }} />);
        expect(container).toBeEmptyDOMElement();
    });
});

describe("BankTransfer (일본어)", () => {
    it("입금 안내 받기 화면이 일본어다 — 한국어가 남지 않는다", () => {
        rtlRender(withI18n(<BankTransfer order={{ trackingId: "t-1", orderStatus: "NOT_PROCESSED", paymentMethod: "cash" }} />, "ja"));
        expect(screen.getByText("銀行振込")).toBeInTheDocument();
        expect(screen.getByLabelText("振込名義人")).toBeInTheDocument();
        expect(screen.getByRole("button", { name: "お振込のご案内を受け取る" })).toBeInTheDocument();
        expect(document.body.textContent).not.toMatch(/[가-힣]/);
    });
});
