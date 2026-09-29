import { fireEvent, render, screen, waitFor } from "@testing-library/react";

import Deliveries from "pages/admin/Deliveries";
import { withI18n } from "i18n/testing";
import { adminCreateDeliveryForOrder, adminListDeliveries, fetchCarriers, registerTracking } from "api/deliveryApi";

jest.mock("api/deliveryApi");

const shipment = (over = {}) => ({
    shipmentId: 11, sequenceNo: 1, carrierCode: null, carrierName: null, trackingNumber: null,
    status: "PENDING", stage: "ORDERED", international: false,
    items: [{ productId: 10, productName: "티셔츠", quantity: 2 }], events: [],
    ...over,
});

const row = (over = {}, shipments = [shipment()]) => ({
    customerId: "7",
    createdAt: "2026-09-19T01:00:00",
    delivery: {
        deliveryId: 3, orderId: "889196209423729897", status: "PENDING", stage: "ORDERED",
        address: { recipientName: "홍길동", recipientPhone: "010-1234-5678", postalCode: "06236",
            line1: "서울 강남구 테헤란로 1", line2: null, city: "서울", state: "서울", country: "KR", memo: null },
        shipments,
        ...over,
    },
});

/**
 * 관리자 배송 — 송장 등록.
 *
 * <p>지키는 것은 "어느 줄에 버튼이 있는가" 와 "무엇을 보내는가" 다. 송장번호와 택배사 코드
 * 외에는 아무것도 보내지 않고, 등록 뒤에는 서버에서 다시 읽는다.
 */
describe("배송 관리", () => {
    beforeEach(() => {
        jest.clearAllMocks();
        fetchCarriers.mockResolvedValue([
            { code: "SIMULATED", name: "시뮬레이터", supportsTracking: true },
            { code: "KOREA_POST", name: "우체국택배", supportsTracking: true },
        ]);
        adminListDeliveries.mockResolvedValue([
            row(),
            row({ deliveryId: 4, orderId: "2", status: "IN_TRANSIT", stage: "IN_TRANSIT",
                address: { recipientName: "김영희", recipientPhone: "010-0000-0000", line1: "부산 해운대구 1", country: "KR" } },
                [shipment({ shipmentId: 12, carrierCode: "SIMULATED", carrierName: "시뮬레이터",
                    trackingNumber: "SIM-1", status: "IN_TRANSIT", stage: "IN_TRANSIT" })]),
        ]);
    });

    it("기본은 송장 대기 목록이고, 송장 없는 배송에만 등록 버튼이 있다", async () => {
        render(withI18n(<Deliveries />));
        await screen.findByText("홍길동");

        expect(adminListDeliveries).toHaveBeenCalledWith({ awaiting: true });
        expect(screen.getAllByRole("button", { name: "송장 등록" })).toHaveLength(1);
        // 이미 송장이 있는 줄은 택배사·송장을 보여 준다
        expect(screen.getByText(/시뮬레이터 · SIM-1/)).toBeInTheDocument();
    });

    it("등록하면 택배사 코드와 송장번호만 보내고 목록을 다시 읽는다", async () => {
        registerTracking.mockResolvedValue({ registered: true, status: "INFO_RECEIVED" });
        render(withI18n(<Deliveries />));
        await screen.findByText("홍길동");

        fireEvent.click(screen.getByRole("button", { name: "송장 등록" }));
        fireEvent.change(screen.getByLabelText("택배사"), { target: { value: "KOREA_POST" } });
        fireEvent.change(screen.getByLabelText("송장번호"), { target: { value: " 1234567890 " } });
        fireEvent.click(screen.getByRole("button", { name: "등록" }));

        // 🔴 18자리가 하나도 안 바뀐 채로 가야 한다. 숫자로 오가면 …920 이 되어 서버가
        //    그 주문을 찾지 못한다 — 송장 등록이 언제나 실패하던 이유였다(docs/25 §11.7).
        await waitFor(() => expect(registerTracking).toHaveBeenCalledWith("889196209423729897",
            { carrierCode: "KOREA_POST", trackingNumber: "1234567890" }));
        await waitFor(() => expect(adminListDeliveries).toHaveBeenCalledTimes(2));
    });

    it("실제 택배사인데 송장번호가 비어 있으면 보내지 않는다", async () => {
        render(withI18n(<Deliveries />));
        await screen.findByText("홍길동");

        fireEvent.click(screen.getByRole("button", { name: "송장 등록" }));
        fireEvent.change(screen.getByLabelText("택배사"), { target: { value: "KOREA_POST" } });
        fireEvent.click(screen.getByRole("button", { name: "등록" }));

        expect(await screen.findByRole("alert")).toHaveTextContent("송장번호");
        expect(registerTracking).not.toHaveBeenCalled();
    });

    it("🔴 테스트 택배사는 송장번호를 비워도 등록한다 — 서버가 SIM-<주문>-<상자> 를 만든다", async () => {
        registerTracking.mockResolvedValue({ registered: true, status: "INFO_RECEIVED" });
        render(withI18n(<Deliveries />));
        await screen.findByText("홍길동");

        fireEvent.click(screen.getByRole("button", { name: "송장 등록" }));
        fireEvent.click(screen.getByRole("button", { name: "등록" }));

        await waitFor(() => expect(registerTracking).toHaveBeenCalledWith("889196209423729897",
            { carrierCode: "SIMULATED", trackingNumber: "" }));
    });

    it("서버가 거절하면 이유를 보여 준다", async () => {
        registerTracking.mockRejectedValue({ response: { data: { message: "주문 또는 택배사를 찾을 수 없습니다" } } });
        render(withI18n(<Deliveries />));
        await screen.findByText("홍길동");

        fireEvent.click(screen.getByRole("button", { name: "송장 등록" }));
        fireEvent.change(screen.getByLabelText("송장번호"), { target: { value: "SIM-9" } });
        fireEvent.click(screen.getByRole("button", { name: "등록" }));

        expect(await screen.findByRole("alert")).toHaveTextContent("주문 또는 택배사를 찾을 수 없습니다");
    });

    /**
     * 🐛 결제는 됐는데 배송이 없는 주문은 이 목록에 **아예 안 나온다** — 배송이 없으니까.
     * 고칠 화면이 없으면 영영 못 고친다. 주문 번호로 직접 잇는다.
     */
    it("🔴 배송이 없는 주문은 번호로 이어 만든다 — 보내는 것은 주문 번호뿐이다", async () => {
        adminCreateDeliveryForOrder.mockResolvedValue({ created: true, deliveryId: 9, orderId: 5 });
        render(withI18n(<Deliveries />));
        await screen.findByText("홍길동");

        fireEvent.change(screen.getByLabelText("주문 번호"), { target: { value: " 889195154191369385 " } });
        fireEvent.click(screen.getByRole("button", { name: "배송 만들기" }));

        await waitFor(() => expect(adminCreateDeliveryForOrder).toHaveBeenCalledWith("889195154191369385"));
        expect(await screen.findByRole("status")).toHaveTextContent("배송을 만들었습니다");
        await waitFor(() => expect(adminListDeliveries).toHaveBeenCalledTimes(2));
    });

    it("이미 배송이 있으면 서버가 알려주는 이유를 보여 준다", async () => {
        adminCreateDeliveryForOrder.mockResolvedValue({ created: false, message: "이미 배송이 있거나 주문에서 배송지를 읽지 못했습니다" });
        render(withI18n(<Deliveries />));
        await screen.findByText("홍길동");

        fireEvent.change(screen.getByLabelText("주문 번호"), { target: { value: "5" } });
        fireEvent.click(screen.getByRole("button", { name: "배송 만들기" }));

        expect(await screen.findByRole("status")).toHaveTextContent("이미 배송이 있거나");
    });

    it("주문 번호가 비어 있으면 보내지 않는다", async () => {
        render(withI18n(<Deliveries />));
        await screen.findByText("홍길동");

        fireEvent.click(screen.getByRole("button", { name: "배송 만들기" }));

        expect(await screen.findByRole("alert")).toHaveTextContent("주문 번호");
        expect(adminCreateDeliveryForOrder).not.toHaveBeenCalled();
    });

    /** 일본어로 바꾸면 한국어가 남지 않는다 — 서버 데이터(이름·주소)는 그대로다. */
    it("일본어 화면은 일본어 문구로 그린다", async () => {
        render(withI18n(<Deliveries />, "ja"));
        await screen.findByText("홍길동");

        expect(screen.getByRole("heading", { name: "配送管理" })).toBeInTheDocument();
        expect(screen.getAllByRole("button", { name: "送り状を登録" })).toHaveLength(1);
        expect(screen.queryByText("송장 등록")).not.toBeInTheDocument();

        fireEvent.click(screen.getByRole("button", { name: "配送を作成" }));
        expect(await screen.findByRole("alert")).toHaveTextContent("注文番号を入力してください。");
    });

    it("최근 전체로 바꾸면 awaiting=false 로 다시 읽는다", async () => {
        render(withI18n(<Deliveries />));
        await screen.findByText("홍길동");

        fireEvent.change(screen.getByLabelText("보기"), { target: { value: "all" } });

        await waitFor(() => expect(adminListDeliveries).toHaveBeenLastCalledWith({ awaiting: false }));
    });
});
