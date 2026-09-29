import { act, render as rtlRender, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import DeliveryTracker, { POLL_MS } from "components/delivery/DeliveryTracker";
import { fetchDeliveryByOrder, cancelDelivery } from "api/deliveryApi";
import { withI18n } from "i18n/testing";

// 컴포넌트가 useTranslation 을 쓰므로 Provider 로 감싼다 — rerender 도 같은 wrapper 를 쓴다.
const render = (ui, options) => rtlRender(ui, { wrapper: ({ children }) => withI18n(children), ...options });

jest.mock("api/deliveryApi");
jest.mock("react-router-dom", () => ({ useNavigate: () => jest.fn() }));

const domestic = {
  orderId: 1,
  stage: "IN_TRANSIT",
  status: "IN_TRANSIT",
  estimatedAt: "2026-08-01T09:00:00",
  canCancel: false,
  canChangeAddress: false,
  address: { recipientName: "홍길동", line1: "서울시 어딘가", postalCode: "06000" },
  shipments: [
    {
      shipmentId: 11,
      sequenceNo: 1,
      carrierName: "우체국택배",
      trackingNumber: "1234567890",
      international: false,
      items: [{ productId: 1, productName: "티셔츠", quantity: 1 }],
      events: [
        { occurredAt: "2026-07-30T10:00:00", location: "대전허브", description: "간선 상차" },
      ],
    },
  ],
};

describe("DeliveryTracker", () => {
  afterEach(() => jest.clearAllMocks());

  it("shows a preparing message when no delivery exists yet", async () => {
    // 결제 직후에는 아직 배송 레코드가 없다. 이건 오류가 아니라 정상 상태다.
    fetchDeliveryByOrder.mockRejectedValue(new Error("404"));

    render(<DeliveryTracker orderId={1} />);

    expect(await screen.findByText(/배송 준비 중입니다/)).toBeInTheDocument();
  });

  /**
   * 🐛 송장을 막 등록하면 첫 조회 전이라 예상 도착일이 없다. 그때 헤드라인이 무조건
   * "배송 준비 중" 이라, 진행 바는 "발송" 을 가리키는데 글은 준비 중이라고 했다.
   */
  it("🔴 예상 도착일이 없으면 단계로 말한다 — 발송 뒤에 '배송 준비 중' 이라고 하지 않는다", async () => {
    fetchDeliveryByOrder.mockResolvedValue({
      ...domestic, status: "INFO_RECEIVED", stage: "SHIPPED", estimatedAt: null,
    });

    render(<DeliveryTracker orderId={1} />);

    expect(await screen.findByText("발송되었습니다")).toBeInTheDocument();
    expect(screen.queryByText("배송 준비 중")).not.toBeInTheDocument();
  });

  it("송장 전(ORDERED)에는 그대로 '배송 준비 중' 이다", async () => {
    fetchDeliveryByOrder.mockResolvedValue({
      ...domestic, status: "PENDING", stage: "ORDERED", estimatedAt: null,
    });

    render(<DeliveryTracker orderId={1} />);

    expect(await screen.findByText("배송 준비 중")).toBeInTheDocument();
  });

  /**
   * 🐛 처음 한 번만 읽었다. 시뮬레이터가 단계를 밟아도, 배달이 끝나도 화면은 그대로였고
   * 새로고침해야 보였다. 끝나지 않은 배송은 주기적으로 읽고, 끝나면 멈춘다.
   */
  it("🔴 진행 중인 배송은 주기적으로 다시 읽고, 배달이 끝나면 멈춘다", async () => {
    jest.useFakeTimers();
    try {
      fetchDeliveryByOrder
        .mockResolvedValueOnce(domestic)                                   // IN_TRANSIT
        .mockResolvedValueOnce({ ...domestic, status: "DELIVERED", stage: "DELIVERED" });

      render(<DeliveryTracker orderId={1} />);
      await screen.findByLabelText("배송 진행 상황");
      expect(fetchDeliveryByOrder).toHaveBeenCalledTimes(1);

      await act(async () => { jest.advanceTimersByTime(POLL_MS); });
      expect(fetchDeliveryByOrder).toHaveBeenCalledTimes(2);
      expect(await screen.findByText(/배달이 완료되었습니다/)).toBeInTheDocument();

      // 끝난 배송은 더 읽지 않는다 — 택배사 API 쿼터와 같은 이유로 화면도 조용해야 한다.
      await act(async () => { jest.advanceTimersByTime(POLL_MS * 3); });
      expect(fetchDeliveryByOrder).toHaveBeenCalledTimes(2);
    } finally {
      jest.useRealTimers();
    }
  });

  it("아직 배송이 없어도(결제 직후) 생길 때까지 다시 읽는다", async () => {
    jest.useFakeTimers();
    try {
      fetchDeliveryByOrder
        .mockRejectedValueOnce(new Error("404"))
        .mockResolvedValueOnce({ ...domestic, status: "PENDING", stage: "ORDERED", estimatedAt: null });

      render(<DeliveryTracker orderId={1} />);
      expect(await screen.findByText(/배송 준비 중입니다/)).toBeInTheDocument();

      await act(async () => { jest.advanceTimersByTime(POLL_MS); });
      expect(fetchDeliveryByOrder).toHaveBeenCalledTimes(2);
      expect(await screen.findByLabelText("배송 진행 상황")).toBeInTheDocument();
    } finally {
      jest.useRealTimers();
    }
  });

  /**
   * 🐛 송장이 등록되면 여기는 "발송되었습니다" 로 바뀌는데 주문 진행 띠·환불 안내는 새로고침해야
   * 바뀌었다. 주문은 react-query 가 들고 있고 아무도 다시 읽으라고 하지 않았다.
   */
  it("🔴 주기 읽기에서 배송 상태가 바뀌면 onStatusChange 를 새 배송과 함께 부른다 — 첫 읽기·같은 상태는 아니다", async () => {
    jest.useFakeTimers();
    try {
      const shipped = { ...domestic, status: "INFO_RECEIVED", stage: "SHIPPED", estimatedAt: null };
      fetchDeliveryByOrder
        .mockResolvedValueOnce({ ...domestic, status: "PENDING", stage: "ORDERED", estimatedAt: null })
        .mockResolvedValueOnce({ ...domestic, status: "PENDING", stage: "ORDERED", estimatedAt: null })
        .mockResolvedValueOnce(shipped);
      const onStatusChange = jest.fn();

      render(<DeliveryTracker orderId={1} onStatusChange={onStatusChange} />);
      await screen.findByLabelText("배송 진행 상황");
      expect(onStatusChange).not.toHaveBeenCalled();          // 첫 읽기는 변화가 아니다

      await act(async () => { jest.advanceTimersByTime(POLL_MS); });
      expect(onStatusChange).not.toHaveBeenCalled();          // 같은 상태

      await act(async () => { jest.advanceTimersByTime(POLL_MS); });
      expect(onStatusChange).toHaveBeenCalledTimes(1);
      expect(onStatusChange).toHaveBeenCalledWith(expect.objectContaining({ status: "INFO_RECEIVED", stage: "SHIPPED" }));
    } finally {
      jest.useRealTimers();
    }
  });

  it("배송이 아직 없다가 생겨도(404 → PENDING) 알린다 — 주문이 PROCESSING 으로 따라와야 한다", async () => {
    jest.useFakeTimers();
    try {
      fetchDeliveryByOrder
        .mockRejectedValueOnce(new Error("404"))
        .mockResolvedValueOnce({ ...domestic, status: "PENDING", stage: "ORDERED", estimatedAt: null });
      const onStatusChange = jest.fn();

      render(<DeliveryTracker orderId={1} onStatusChange={onStatusChange} />);
      await screen.findByText(/배송 준비 중입니다/);
      await act(async () => { jest.advanceTimersByTime(POLL_MS); });

      expect(onStatusChange).toHaveBeenCalledTimes(1);
    } finally {
      jest.useRealTimers();
    }
  });

  it("주문 상태가 바뀌면 기다리지 않고 바로 다시 읽는다", async () => {
    fetchDeliveryByOrder.mockResolvedValue(domestic);
    const { rerender } = render(<DeliveryTracker orderId={1} orderStatus="DISPATCHED" />);
    await screen.findByLabelText("배송 진행 상황");
    expect(fetchDeliveryByOrder).toHaveBeenCalledTimes(1);

    rerender(<DeliveryTracker orderId={1} orderStatus="DELIVERED" />);

    await waitFor(() => expect(fetchDeliveryByOrder).toHaveBeenCalledTimes(2));
  });

  it("renders carrier and tracking number", async () => {
    fetchDeliveryByOrder.mockResolvedValue(domestic);

    render(<DeliveryTracker orderId={1} />);

    expect(await screen.findByText(/우체국택배/)).toBeInTheDocument();
    expect(screen.getByText(/1234567890/)).toBeInTheDocument();
  });

  it("collapses the 12 internal states into a small stage bar", async () => {
    fetchDeliveryByOrder.mockResolvedValue(domestic);

    render(<DeliveryTracker orderId={1} />);

    const bar = await screen.findByLabelText("배송 진행 상황");
    // 국내 배송은 4단계. 내부 상태(IN_TRANSIT 등)를 그대로 노출하지 않는다.
    expect(bar.querySelectorAll("li")).toHaveLength(4);
    expect(bar.textContent).not.toContain("IN_TRANSIT");
  });

  it("adds a customs stage for international shipments", async () => {
    fetchDeliveryByOrder.mockResolvedValue({
      ...domestic,
      shipments: [{ ...domestic.shipments[0], international: true }],
    });

    render(<DeliveryTracker orderId={1} />);

    const bar = await screen.findByLabelText("배송 진행 상황");
    expect(bar.querySelectorAll("li")).toHaveLength(5);
    expect(bar.textContent).toContain("통관");
  });

  it("labels each box when a delivery is split", async () => {
    fetchDeliveryByOrder.mockResolvedValue({
      ...domestic,
      shipments: [
        { ...domestic.shipments[0], shipmentId: 11, sequenceNo: 1 },
        { ...domestic.shipments[0], shipmentId: 12, sequenceNo: 2, trackingNumber: "999" },
      ],
    });

    render(<DeliveryTracker orderId={1} />);

    expect(await screen.findByText("패키지 1/2")).toBeInTheDocument();
    expect(screen.getByText("패키지 2/2")).toBeInTheDocument();
  });

  it("hides the progress bar when the delivery is cancelled", async () => {
    // 진행 바만 그리면 문제 상태가 묻힌다.
    fetchDeliveryByOrder.mockResolvedValue({ ...domestic, stage: "CANCELLED" });

    render(<DeliveryTracker orderId={1} />);

    expect(await screen.findByText(/배송이 취소되었습니다/)).toBeInTheDocument();
    expect(screen.queryByLabelText("배송 진행 상황")).not.toBeInTheDocument();
  });

  it("surfaces a customs hold, because the user may have to act", async () => {
    fetchDeliveryByOrder.mockResolvedValue({ ...domestic, status: "CUSTOMS_HOLD" });

    render(<DeliveryTracker orderId={1} />);

    expect(await screen.findByText(/통관이 진행 중입니다/)).toBeInTheDocument();
  });

  it("explains why the address cannot be changed instead of just hiding the button", async () => {
    fetchDeliveryByOrder.mockResolvedValue({ ...domestic, canChangeAddress: false });

    render(<DeliveryTracker orderId={1} />);

    expect(await screen.findByText(/발송 후에는 변경할 수 없습니다/)).toBeInTheDocument();
  });

  it("shows the cancel button only when cancelling is possible", async () => {
    fetchDeliveryByOrder.mockResolvedValue({ ...domestic, canCancel: true });

    render(<DeliveryTracker orderId={1} />);

    expect(await screen.findByRole("button", { name: "배송 취소" })).toBeInTheDocument();
  });

  /**
   * 🐛 배송을 취소해도 주문은 DISPATCHED 그대로였고 "배송이 시작된 주문은 환불할 수 없습니다"
   * 가 그대로 떠 있었다. 서버가 주문을 PAID 로 되돌리는 것은 Kafka 왕복 뒤라, 주문 화면이
   * 다시 읽어야 안다 — 그 신호가 onCancelled 다.
   */
  it("🔴 취소가 받아들여지면 onCancelled 를 한 번 부른다 — 주문 화면이 다시 읽도록", async () => {
    fetchDeliveryByOrder
      .mockResolvedValueOnce({ ...domestic, canCancel: true })
      .mockResolvedValueOnce({ ...domestic, canCancel: false, stage: "CANCELLED", status: "CANCELLED" });
    cancelDelivery.mockResolvedValue({ ok: true, cancelled: true });
    const onCancelled = jest.fn();

    render(<DeliveryTracker orderId={1} onCancelled={onCancelled} />);
    await userEvent.click(await screen.findByRole("button", { name: "배송 취소" }));

    expect(await screen.findByText(/배송이 취소되었습니다/)).toBeInTheDocument();
    expect(onCancelled).toHaveBeenCalledTimes(1);
  });

  it("취소가 거절되면 onCancelled 를 부르지 않는다", async () => {
    fetchDeliveryByOrder.mockResolvedValue({ ...domestic, canCancel: true });
    cancelDelivery.mockResolvedValue({ ok: false, alreadyDispatched: true, message: "이미 발송되어 취소할 수 없습니다" });
    const onCancelled = jest.fn();

    render(<DeliveryTracker orderId={1} onCancelled={onCancelled} />);
    await userEvent.click(await screen.findByRole("button", { name: "배송 취소" }));

    expect(await screen.findByRole("alert")).toBeInTheDocument();
    expect(onCancelled).not.toHaveBeenCalled();
  });

  it("shows the server's reason when cancelling is refused", async () => {
    fetchDeliveryByOrder.mockResolvedValue({ ...domestic, canCancel: true });
    cancelDelivery.mockResolvedValue({
      ok: false,
      alreadyDispatched: true,
      message: "이미 발송되어 취소할 수 없습니다",
    });

    render(<DeliveryTracker orderId={1} />);
    await userEvent.click(await screen.findByRole("button", { name: "배송 취소" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("이미 발송되어 취소할 수 없습니다");
  });

  it("reloads the delivery after a successful cancel", async () => {
    fetchDeliveryByOrder.mockResolvedValue({ ...domestic, canCancel: true });
    cancelDelivery.mockResolvedValue({ ok: true, cancelled: true });

    render(<DeliveryTracker orderId={1} />);
    await userEvent.click(await screen.findByRole("button", { name: "배송 취소" }));

    // 최초 1회 + 취소 후 1회
    await waitFor(() => expect(fetchDeliveryByOrder).toHaveBeenCalledTimes(2));
  });

  it("links back to the reordered-from order", async () => {
    fetchDeliveryByOrder.mockResolvedValue({ ...domestic, reorderedFromOrderId: 77 });

    render(<DeliveryTracker orderId={1} />);

    expect(await screen.findByText(/취소하신 주문 #77/)).toBeInTheDocument();
  });

  it("keeps the timeline collapsed until asked", async () => {
    fetchDeliveryByOrder.mockResolvedValue(domestic);

    render(<DeliveryTracker orderId={1} />);

    const toggle = await screen.findByRole("button", { name: /배송 상세 보기/ });
    expect(screen.queryByText("간선 상차")).not.toBeInTheDocument();

    await userEvent.click(toggle);

    expect(screen.getByText("간선 상차")).toBeInTheDocument();
  });

  /** 요일은 Intl 이 언어에 맞춰 준다 — 예전에는 한국어 요일 배열이 박혀 있었다. */
  it("예상 도착일을 한국어로 말한다", async () => {
    fetchDeliveryByOrder.mockResolvedValue(domestic);   // 2026-08-01 은 토요일

    render(<DeliveryTracker orderId={1} />);

    expect(await screen.findByText("8월 1일 (토) 도착 예정")).toBeInTheDocument();
  });
});

/** 🔴 일본어 화면에 한국어가 남으면 안 된다 — 한국어 테스트는 하드코딩이어도 통과한다. */
describe("DeliveryTracker (일본어)", () => {
  afterEach(() => jest.clearAllMocks());

  it("헤드라인·진행 단계·버튼이 일본어다", async () => {
    fetchDeliveryByOrder.mockResolvedValue({ ...domestic, canCancel: true });

    rtlRender(withI18n(<DeliveryTracker orderId={1} />, "ja"));

    expect(await screen.findByText("8月1日（土）お届け予定")).toBeInTheDocument();
    expect(screen.getByLabelText("配送状況")).toBeInTheDocument();
    expect(screen.getByText("配送中")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "配送をキャンセルする" })).toBeInTheDocument();
    expect(screen.getByText("発送後は変更できません")).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/배송|도착|발송/);
  });

  it("배송이 아직 없으면 준비 중이라고 일본어로 말한다", async () => {
    fetchDeliveryByOrder.mockRejectedValue(new Error("404"));

    rtlRender(withI18n(<DeliveryTracker orderId={1} />, "ja"));

    expect(await screen.findByText(/発送準備中です/)).toBeInTheDocument();
  });
});
