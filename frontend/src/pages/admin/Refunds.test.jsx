import { fireEvent, render, screen, waitFor } from "@testing-library/react";

import Refunds from "pages/admin/Refunds";
import { withI18n } from "i18n/testing";
import { adminConfirmDeposit, adminListOrders, adminReceiveReturn, adminRefundOrder } from "util/api";

jest.mock("util/api");

const row = (over = {}) => ({
    orderId: 1,
    trackingId: "track-1",
    customerId: 7,
    customerEmail: "buyer@example.com",
    total: 5998,
    paymentMethod: "stripe",
    paymentProvider: "STRIPE",
    providerPaymentId: "cs_test_1",
    orderStatus: "PAID",
    createdAt: "2026-09-18T10:00:00",
    paidCreatedAt: "2026-09-18T10:05:00",
    ...over,
});

const page = (content) => ({ content, totalPages: 1, totalElements: content.length });

/**
 * 관리자 환불 화면.
 *
 * <p>고객 화면과 같은 엔드포인트를 쓰되, 관리자는 <b>발송된 주문도</b> 환불한다. 그래서
 * 여기서 지키는 것은 "버튼이 어디에 있는가" 와 "무엇을 보내는가" 다.
 */
describe("환불 관리", () => {
    beforeEach(() => {
        jest.clearAllMocks();
        adminListOrders.mockResolvedValue(page([
            row(),
            row({ orderId: 2, trackingId: "track-2", orderStatus: "DISPATCHED" }),
            row({ orderId: 3, trackingId: "track-3", orderStatus: "REFUNDED",
                refundId: "re_3", refundedAt: "2026-09-19T09:00:00" }),
            row({ orderId: 4, trackingId: "track-4", orderStatus: "NOT_PROCESSED", paymentProvider: null }),
        ]));
    });

    it("기본은 결제 완료(PAID) 목록이다", async () => {
        render(withI18n(<Refunds />));
        await screen.findAllByText("buyer@example.com");

        expect(adminListOrders).toHaveBeenCalledWith(
            expect.objectContaining({ status: "PAID", page: 0 }));
    });

    /** 관리자는 발송된 주문도 환불한다 — 고객 화면과 다른 점이다. */
    it("결제된 주문에는 전부 환불 버튼이 있고, 미결제·환불됨에는 없다", async () => {
        render(withI18n(<Refunds />));
        await screen.findAllByText("buyer@example.com");

        // PAID · DISPATCHED 두 줄에만 버튼
        expect(screen.getAllByRole("button", { name: "환불" })).toHaveLength(2);
        // 환불된 줄은 참조를 보여 준다
        expect(screen.getByText("re_3")).toBeInTheDocument();
    });

    /** 🔒 보내는 것은 어느 주문·왜 뿐이다. 금액·결제 참조는 본문에 없다. */
    it("🔒 확정하면 trackingId 와 사유만 보내고 목록을 다시 읽는다", async () => {
        adminRefundOrder.mockResolvedValue({ message: "환불이 처리되었습니다." });
        render(withI18n(<Refunds />));
        await screen.findAllByText("buyer@example.com");

        fireEvent.click(screen.getAllByRole("button", { name: "환불" })[0]);
        fireEvent.change(screen.getByLabelText("환불 사유"), { target: { value: "고객센터 접수" } });
        fireEvent.click(screen.getByRole("button", { name: "환불 확정" }));

        await waitFor(() => expect(adminRefundOrder).toHaveBeenCalledWith("track-1", "고객센터 접수", null));
        // 화면의 행을 손으로 바꾸지 않고 서버에서 다시 읽는다.
        await waitFor(() => expect(adminListOrders).toHaveBeenCalledTimes(2));
    });

    it("서버가 거절하면 이유를 보여 준다", async () => {
        adminRefundOrder.mockRejectedValue({
            response: { data: { message: "제공자가 환불을 받아들이지 않았습니다." } },
        });
        render(withI18n(<Refunds />));
        await screen.findAllByText("buyer@example.com");

        fireEvent.click(screen.getAllByRole("button", { name: "환불" })[0]);
        fireEvent.click(screen.getByRole("button", { name: "환불 확정" }));

        expect(await screen.findByText("제공자가 환불을 받아들이지 않았습니다.")).toBeInTheDocument();
        expect(adminListOrders).toHaveBeenCalledTimes(1);
    });

    it("상태 필터를 바꾸면 그 상태로 다시 읽는다", async () => {
        render(withI18n(<Refunds />));
        await screen.findAllByText("buyer@example.com");

        fireEvent.change(screen.getByLabelText("상태"), { target: { value: "REFUNDED" } });

        await waitFor(() => expect(adminListOrders).toHaveBeenLastCalledWith(
            expect.objectContaining({ status: "REFUNDED", page: 0 })));
    });

    it("추적번호를 넣으면 그것으로 찾는다", async () => {
        render(withI18n(<Refunds />));
        await screen.findAllByText("buyer@example.com");

        fireEvent.change(screen.getByLabelText("추적번호"), { target: { value: " track-9 " } });

        await waitFor(() => expect(adminListOrders).toHaveBeenLastCalledWith(
            expect.objectContaining({ trackingId: "track-9" })));
    });

    // ── 반품 입고 ───────────────────────────────────────────────────────────

    /** 물건이 밖에 있고 아직 안 돌아온 주문에만 — 발송 전 환불(재고가 이미 돌아옴)에는 없다. */
    it("반품 입고 버튼은 반품 대기·발송된 주문에만 있다", async () => {
        adminListOrders.mockResolvedValue(page([
            row({ orderId: 1, trackingId: "t-paid", orderStatus: "PAID" }),
            row({ orderId: 2, trackingId: "t-out", orderStatus: "DISPATCHED" }),
            row({ orderId: 3, trackingId: "t-wait", orderStatus: "REFUNDED", awaitingReturn: true }),
            row({ orderId: 4, trackingId: "t-home", orderStatus: "REFUNDED", awaitingReturn: false }),
            row({ orderId: 5, trackingId: "t-done", orderStatus: "REFUNDED",
                returnReceivedAt: "2026-09-19T10:00:00", stockRestoredAt: "2026-09-19T10:00:01" }),
        ]));
        render(withI18n(<Refunds />));
        await screen.findAllByText("buyer@example.com");

        // DISPATCHED · REFUNDED+awaitingReturn 두 줄
        expect(screen.getAllByRole("button", { name: "반품 입고" })).toHaveLength(2);
        // 필터 옵션에도 같은 글자가 있으므로 행의 표시만 센다(옵션 1 + 행 1)
        expect(screen.getAllByText("반품 대기")).toHaveLength(2);
        // 시각은 로케일 형식이라 앞글자만 본다(입고 패널은 닫혀 있어 체크박스 라벨은 없다)
        expect(screen.getByText(/^재고 복원 /)).toBeInTheDocument();
    });

    /** 🔒 보내는 것은 어느 주문·재고로 되돌릴지뿐이다. 수량은 본문에 없다. */
    it("🔒 입고 확정은 trackingId 와 restock 만 보내고 목록을 다시 읽는다", async () => {
        adminListOrders.mockResolvedValue(page([
            row({ orderId: 3, trackingId: "t-wait", orderStatus: "REFUNDED", awaitingReturn: true }),
        ]));
        adminReceiveReturn.mockResolvedValue({ message: "반품을 입고하고 재고를 복원했습니다." });
        render(withI18n(<Refunds />));
        await screen.findAllByText("buyer@example.com");

        fireEvent.click(screen.getByRole("button", { name: "반품 입고" }));
        fireEvent.click(screen.getByRole("button", { name: "입고 확정" }));

        await waitFor(() => expect(adminReceiveReturn).toHaveBeenCalledWith("t-wait", true, null));
        await waitFor(() => expect(adminListOrders).toHaveBeenCalledTimes(2));
    });

    it("재고 복원을 끄면 restock=false 로 보낸다 — 파손·오염", async () => {
        adminListOrders.mockResolvedValue(page([
            row({ orderId: 3, trackingId: "t-wait", orderStatus: "REFUNDED", awaitingReturn: true }),
        ]));
        adminReceiveReturn.mockResolvedValue({ message: "반품을 입고했습니다(재고 복원 없음)." });
        render(withI18n(<Refunds />));
        await screen.findAllByText("buyer@example.com");

        fireEvent.click(screen.getByRole("button", { name: "반품 입고" }));
        fireEvent.click(screen.getByLabelText("재고 복원"));
        fireEvent.click(screen.getByRole("button", { name: "입고 확정" }));

        await waitFor(() => expect(adminReceiveReturn).toHaveBeenCalledWith("t-wait", false, null));
    });

    // ── 부분 환불·부분 반품 ─────────────────────────────────────────────────

    const withItems = (over = {}) => row({
        orderId: 9, trackingId: "t-items", orderStatus: "DELIVERED",
        items: [
            { orderProductId: 101, name: "티셔츠", size: "M", qty: 2, price: 20, refundedQty: 0, returnedQty: 0 },
            { orderProductId: 102, name: "바지", size: "L", qty: 1, price: 10, refundedQty: 1, returnedQty: 0 },
        ],
        refundedAmount: 10,
        ...over,
    });

    /** 🔒 품목·수량만 보낸다. 금액은 서버가 정한다. 이미 환불된 줄은 잠긴다. */
    it("🔒 일부 수량만 고르면 items 로 보내고 금액은 보내지 않는다", async () => {
        adminListOrders.mockResolvedValue(page([withItems()]));
        adminRefundOrder.mockResolvedValue({ message: "선택한 품목이 환불되었습니다." });
        render(withI18n(<Refunds />));
        await screen.findAllByText("buyer@example.com");

        expect(screen.getByText(/환불 −/)).toBeInTheDocument();          // 누적 환불액
        fireEvent.click(screen.getByRole("button", { name: "환불" }));
        expect(screen.getByLabelText("환불 수량 바지")).toBeDisabled();     // 남은 0
        fireEvent.change(screen.getByLabelText("환불 수량 티셔츠"), { target: { value: "1" } });
        fireEvent.click(screen.getByRole("button", { name: "환불 확정" }));

        await waitFor(() => expect(adminRefundOrder).toHaveBeenCalledWith(
            "t-items", "", [{ orderProductId: 101, qty: 1 }]));
        const args = adminRefundOrder.mock.calls[0];
        expect(JSON.stringify(args)).not.toMatch(/amount|price/);
    });

    it("남은 전부를 그대로 두면 items 없이 보낸다 — 서버 기본(남은 전부)", async () => {
        adminListOrders.mockResolvedValue(page([withItems()]));
        adminRefundOrder.mockResolvedValue({ message: "환불이 처리되었습니다." });
        render(withI18n(<Refunds />));
        await screen.findAllByText("buyer@example.com");

        fireEvent.click(screen.getByRole("button", { name: "환불" }));
        fireEvent.click(screen.getByRole("button", { name: "환불 확정" }));

        await waitFor(() => expect(adminRefundOrder).toHaveBeenCalledWith("t-items", "", null));
    });

    it("부분 반품은 입고 수량을 items 로 보낸다", async () => {
        adminListOrders.mockResolvedValue(page([withItems({ orderStatus: "REFUNDED", awaitingReturn: true,
            items: [
                { orderProductId: 101, name: "티셔츠", size: "M", qty: 2, refundedQty: 2, returnedQty: 0 },
                { orderProductId: 102, name: "바지", size: "L", qty: 1, refundedQty: 1, returnedQty: 1 },
            ] })]));
        adminReceiveReturn.mockResolvedValue({ message: "반품을 입고하고 재고를 복원했습니다." });
        render(withI18n(<Refunds />));
        await screen.findAllByText("buyer@example.com");

        fireEvent.click(screen.getByRole("button", { name: "반품 입고" }));
        expect(screen.getByLabelText("입고 수량 바지")).toBeDisabled();     // 이미 돌아옴
        fireEvent.change(screen.getByLabelText("입고 수량 티셔츠"), { target: { value: "1" } });
        fireEvent.click(screen.getByRole("button", { name: "입고 확정" }));

        await waitFor(() => expect(adminReceiveReturn).toHaveBeenCalledWith(
            "t-items", true, [{ orderProductId: 101, qty: 1 }]));
    });

    it("전 수량이 환불된 주문에는 환불 버튼이, 전 수량이 돌아온 주문에는 입고 버튼이 없다", async () => {
        adminListOrders.mockResolvedValue(page([
            withItems({ orderId: 1, trackingId: "a", orderStatus: "DELIVERED",
                items: [{ orderProductId: 101, name: "티셔츠", qty: 2, refundedQty: 2, returnedQty: 2 }] }),
        ]));
        render(withI18n(<Refunds />));
        await screen.findAllByText("buyer@example.com");

        expect(screen.queryByRole("button", { name: "환불" })).not.toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "반품 입고" })).not.toBeInTheDocument();
    });

    // ── 재고 초과 판매(docs/53 §7) ──────────────────────────────────────────

    it("🔴 재고 부족 수량을 품목과 주문에 보인다 — 발송 수량에서 빼야 한다", async () => {
        adminListOrders.mockResolvedValue(page([withItems({ orderStatus: "PAID",
            items: [
                { orderProductId: 101, name: "티셔츠", size: "M", qty: 3, refundedQty: 2, shortQty: 2 },
                { orderProductId: 102, name: "바지", size: "L", qty: 1 },
            ] })]));
        render(withI18n(<Refunds />));
        await screen.findAllByText("buyer@example.com");

        expect(screen.getByText(/티셔츠 · M ×3 \(재고 부족 2, 환불 2\)/)).toBeInTheDocument();
        expect(screen.getByRole("status")).toHaveTextContent("재고 부족 2개");
    });

    it("부족이 없으면 경고가 없다", async () => {
        adminListOrders.mockResolvedValue(page([withItems({ orderStatus: "PAID",
            items: [{ orderProductId: 101, name: "티셔츠", qty: 1, shortQty: 0 }] })]));
        render(withI18n(<Refunds />));
        await screen.findAllByText("buyer@example.com");

        expect(screen.queryByRole("status")).not.toBeInTheDocument();
    });

    it("🔴 반품 입고는 나간 적 없는 부족분을 빼고 센다 — 서버의 remainingToReturn 과 같은 식", async () => {
        adminListOrders.mockResolvedValue(page([withItems({ orderStatus: "REFUNDED", awaitingReturn: true,
            items: [{ orderProductId: 101, name: "티셔츠", size: "M", qty: 3, refundedQty: 3, shortQty: 2, returnedQty: 0 }] })]));
        render(withI18n(<Refunds />));
        await screen.findAllByText("buyer@example.com");

        fireEvent.click(screen.getByRole("button", { name: "반품 입고" }));

        const picker = screen.getByLabelText("입고 수량 티셔츠");
        expect(Array.from(picker.options).map((o) => o.value)).toEqual(["0", "1"]);   // 3 − 2 = 1 까지
    });

    // ── 무통장입금 입금 확인 ────────────────────────────────────────────────

    /** 🔒 카드 결제 확인과 같은 무게. 보내는 것은 어느 주문·참조뿐이고 금액은 없다. */
    it("🔒 입금 대기 주문에 입금 확인 버튼이 있고, 확정하면 trackingId 와 참조만 보낸다", async () => {
        adminListOrders.mockResolvedValue(page([
            row({ orderId: 1, trackingId: "t-cash", orderStatus: "UNPAID", paymentMethod: "cash",
                paymentProvider: null, providerPaymentId: null, depositorName: "홍길동",
                depositDeadline: "2026-09-21T10:00:00", currency: "KRW", chargedAmount: 80973 }),
            row({ orderId: 2, trackingId: "t-done", orderStatus: "UNPAID", paymentMethod: "cash",
                paymentProvider: "BANK_TRANSFER", providerPaymentId: "deposit-2" }),   // 확인됨, 반영 중
            row({ orderId: 3, trackingId: "t-card", orderStatus: "NOT_PROCESSED", paymentProvider: null }),
            // 포트원 가상계좌 — 포트원 웹훅이 확인한다. 사람이 누를 버튼이 없어야 한다.
            row({ orderId: 4, trackingId: "t-vbank", orderStatus: "UNPAID", paymentMethod: "portone",
                paymentProvider: null, providerPaymentId: null, depositDeadline: "2026-09-21T10:00:00" }),
        ]));
        adminConfirmDeposit.mockResolvedValue({ message: "입금을 확인했습니다." });
        render(withI18n(<Refunds />));
        await screen.findAllByText("buyer@example.com");

        expect(screen.getAllByRole("button", { name: "입금 확인" })).toHaveLength(1);
        expect(screen.getByText(/홍길동/)).toBeInTheDocument();
        expect(screen.getByText(/가상계좌 \(포트원이 입금 확인\)/)).toBeInTheDocument();
        expect(screen.getByText(/확인됨\(반영 중\)/)).toBeInTheDocument();

        fireEvent.click(screen.getByRole("button", { name: "입금 확인" }));
        expect(screen.getByText(/통장에/)).toBeInTheDocument();   // 확인 패널 — 청구액(KRW)이 그 안에 있다
        fireEvent.change(screen.getByLabelText("입금 참조"), { target: { value: "TX-9" } });
        fireEvent.click(screen.getByRole("button", { name: "입금 확인 확정" }));

        await waitFor(() => expect(adminConfirmDeposit).toHaveBeenCalledWith("t-cash", "TX-9"));
        expect(JSON.stringify(adminConfirmDeposit.mock.calls[0])).not.toMatch(/amount/);
        await waitFor(() => expect(adminListOrders).toHaveBeenCalledTimes(2));
    });

    it("반품 대기 필터는 AWAITING_RETURN 으로 읽는다", async () => {
        render(withI18n(<Refunds />));
        await screen.findAllByText("buyer@example.com");

        fireEvent.change(screen.getByLabelText("상태"), { target: { value: "AWAITING_RETURN" } });

        await waitFor(() => expect(adminListOrders).toHaveBeenLastCalledWith(
            expect.objectContaining({ status: "AWAITING_RETURN", page: 0 })));
    });

    it("목록을 못 읽으면 오류를 보여 준다 — 빈 목록처럼 보이면 안 된다", async () => {
        adminListOrders.mockRejectedValue({ response: { data: { message: "관리자만 볼 수 있습니다." } } });
        render(withI18n(<Refunds />));

        expect(await screen.findByText("관리자만 볼 수 있습니다.")).toBeInTheDocument();
    });
});
