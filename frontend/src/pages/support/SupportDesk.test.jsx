import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

import { Desk } from "pages/support/SupportDesk";
import { withI18n } from "i18n/testing";
import {
    claimSupportThread, getSupportDesk, getSupportMessages, getSupportThread, releaseSupportThread, updateSupportNote,
} from "util/api";

jest.mock("util/api");
const mockUser = { userId: "2001", roles: ["ROLE_SUPPORT"] };
jest.mock("store/AuthContext", () => ({ useAuthContext: () => ({ user: mockUser }) }));
const mockStomp = { handlers: {} };
jest.mock("util/stompProvider", () => ({
    useStomp: () => ({
        isConnected: true,
        subscribe: (dest, cb) => { mockStomp.handlers[dest] = cb; return () => { delete mockStomp.handlers[dest]; }; },
        sendMessage: () => true,
    }),
}));
jest.mock("react-hot-toast", () => ({ __esModule: true, default: jest.fn() }));
// eslint-disable-next-line import/first
import toast from "react-hot-toast";

const WAITING = {
    threadId: "5", roomId: "support-5", status: "WAITING", customerId: "1001", customerName: "홍길동", topic: "DELIVERY",
    awaitingReply: true, lastMessagePreview: "배송이 안 와요", createdAt: "2026-09-25T01:00:00Z",
};
const MINE = { ...WAITING, threadId: "8", roomId: "support-8", status: "ACTIVE", customerId: "1003",
    customerName: "김철수", agentId: "2001", agentName: "민지", awaitingReply: true, topic: "PAYMENT" };

const renderDesk = () => render(withI18n(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <Desk />
    </QueryClientProvider>
));

const listItem = async (name) => {
    const [first] = await screen.findAllByText(name);
    return first.closest("button");
};

/**
 * 상담 데스크 (docs/58 §9 · §11).
 */
describe("Desk — 상담 데스크", () => {
    beforeEach(() => {
        mockStomp.handlers = {};
        mockUser.roles = ["ROLE_SUPPORT"];
        getSupportMessages.mockResolvedValue([]);
        getSupportThread.mockResolvedValue(undefined);
        getSupportDesk.mockImplementation((view) => Promise.resolve(view === "WAITING" ? [WAITING] : []));
    });
    afterEach(() => jest.restoreAllMocks());
    afterEach(() => jest.clearAllMocks());

    it("대기 목록에 고객 이름 · 마지막 메시지 · 유형 · '답변 필요' 가 보인다", async () => {
        renderDesk();

        const item = await listItem("홍길동");
        expect(within(item).getByText("배송이 안 와요")).toBeInTheDocument();
        expect(within(item).getByText("답변 필요")).toBeInTheDocument();
        expect(within(item).getByText("배송")).toBeInTheDocument();
        expect(getSupportDesk).toHaveBeenCalledWith("WAITING", undefined);
    });

    it("🔴 대기 중인 상담은 맡기 전에는 답할 수 없다 — 맡으면 입력창이 열린다", async () => {
        claimSupportThread.mockResolvedValue({ ...WAITING, status: "ACTIVE", agentId: "2001", agentName: "민지" });
        renderDesk();

        await userEvent.click(await listItem("홍길동"));
        expect(screen.queryByRole("textbox", { name: "메시지를 입력하세요" })).not.toBeInTheDocument();
        expect(screen.getByText("맡은 상담원만 답할 수 있습니다.")).toBeInTheDocument();

        await userEvent.click(screen.getByRole("button", { name: "상담 맡기" }));

        expect(claimSupportThread).toHaveBeenCalledWith("5");
        expect(await screen.findByRole("textbox", { name: "메시지를 입력하세요" })).toBeInTheDocument();
    });

    it("다른 상담원이 먼저 맡았으면 서버의 이유를 보인다", async () => {
        claimSupportThread.mockRejectedValue({ response: { data: { message: "다른 상담원이 이미 맡은 상담입니다." } } });
        renderDesk();

        await userEvent.click(await listItem("홍길동"));
        await userEvent.click(screen.getByRole("button", { name: "상담 맡기" }));

        expect(await screen.findByRole("alert")).toHaveTextContent("다른 상담원이 이미 맡은 상담입니다.");
    });

    it("탭을 바꾸면 그 목록을 읽는다", async () => {
        renderDesk();
        await listItem("홍길동");

        await userEvent.click(screen.getByRole("tab", { name: "내 담당" }));

        await waitFor(() => expect(getSupportDesk).toHaveBeenCalledWith("MINE", undefined));
        expect(await screen.findByText("해당하는 상담이 없습니다.")).toBeInTheDocument();
    });

    // ── 거르기 ────────────────────────────────────────────────────────

    it("유형으로 거르면 서버에 유형을 넘겨 읽는다", async () => {
        renderDesk();
        await listItem("홍길동");

        await userEvent.selectOptions(screen.getByRole("combobox", { name: "문의 유형으로 거르기" }), "PAYMENT");

        await waitFor(() => expect(getSupportDesk).toHaveBeenCalledWith("WAITING", "PAYMENT"));
    });

    it("검색은 고객 이름 · 마지막 메시지로 목록을 좁힌다", async () => {
        getSupportDesk.mockImplementation((view) => Promise.resolve(view === "WAITING"
            ? [WAITING, { ...WAITING, threadId: "6", customerName: "이영희", lastMessagePreview: "쿠폰 문의" }] : []));
        renderDesk();
        await listItem("이영희");

        await userEvent.type(screen.getByRole("searchbox", { name: "고객·내용 검색" }), "쿠폰");

        expect(screen.queryByText("홍길동")).not.toBeInTheDocument();
        expect(screen.getByText("이영희")).toBeInTheDocument();
    });

    // ── 상담원 도구 ──────────────────────────────────────────────────

    it("빠른 답변 — 고르면 고객 이름이 들어간 문장이 입력칸에 들어간다(바로 보내지 않는다)", async () => {
        getSupportDesk.mockImplementation((view) => Promise.resolve(view === "MINE" ? [MINE] : []));
        renderDesk();
        await userEvent.click(screen.getByRole("tab", { name: /내 담당/ }));
        await userEvent.click(await listItem("김철수"));

        await userEvent.click(screen.getByRole("button", { name: "빠른 답변" }));
        await userEvent.click(screen.getByRole("menuitem", { name: /첫인사/ }));

        expect(screen.getByRole("textbox", { name: "메시지를 입력하세요" }))
            .toHaveValue("안녕하세요, 김철수 님. 고객지원팀입니다. 무엇을 도와드릴까요?");
        expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    });

    it("🔒 내부 메모 — '고객에게 보이지 않습니다' 를 알리고, 저장하면 서버에 보낸다", async () => {
        getSupportDesk.mockImplementation((view) => Promise.resolve(view === "MINE" ? [MINE] : []));
        updateSupportNote.mockResolvedValue({ ...MINE, internalNote: "환불 규정 안내함", noteUpdatedAt: "2026-09-27T01:00:00Z" });
        renderDesk();
        await userEvent.click(screen.getByRole("tab", { name: /내 담당/ }));
        await userEvent.click(await listItem("김철수"));

        const panel = screen.getByTestId("info-panel");
        expect(within(panel).getByText(/고객에게는 보이지 않습니다/)).toBeInTheDocument();
        await userEvent.type(within(panel).getByRole("textbox", { name: "내부 메모" }), "환불 규정 안내함");
        await userEvent.click(within(panel).getByRole("button", { name: "메모 저장" }));

        expect(updateSupportNote).toHaveBeenCalledWith("8", "환불 규정 안내함");
        expect(await within(panel).findByText("저장했습니다.")).toBeInTheDocument();
    });

    it("상담을 고르면 상세를 다시 읽어 고객의 지난 상담 수를 보인다", async () => {
        getSupportThread.mockResolvedValue({ ...WAITING, customerPastThreads: 3 });
        renderDesk();

        await userEvent.click(await listItem("홍길동"));

        expect(getSupportThread).toHaveBeenCalledWith("5");
        expect(await within(screen.getByTestId("info-panel")).findByText("3건")).toBeInTheDocument();
    });

    it("맡은 상담은 반납할 수 있다 — 확인을 받고 서버에 보낸다", async () => {
        getSupportDesk.mockImplementation((view) => Promise.resolve(view === "MINE" ? [MINE] : []));
        releaseSupportThread.mockResolvedValue({ ...MINE, status: "WAITING", agentId: null, agentName: null });
        jest.spyOn(window, "confirm").mockReturnValue(true);
        renderDesk();
        await userEvent.click(screen.getByRole("tab", { name: /내 담당/ }));
        await userEvent.click(await listItem("김철수"));

        await userEvent.click(screen.getByRole("button", { name: "반납" }));

        expect(window.confirm).toHaveBeenCalled();
        expect(releaseSupportThread).toHaveBeenCalledWith("8");
        expect(await screen.findByRole("button", { name: "상담 맡기" })).toBeInTheDocument();
    });

    it("남이 맡은 상담에는 반납 버튼이 없다 — 관리자에게는 있다", async () => {
        const others = { ...MINE, agentId: "2999", agentName: "수진" };
        getSupportDesk.mockImplementation((view) => Promise.resolve(view === "ACTIVE" ? [others] : []));
        const { unmount } = renderDesk();
        await userEvent.click(screen.getByRole("tab", { name: "전체 상담 중" }));
        await userEvent.click(await listItem("김철수"));
        expect(screen.queryByRole("button", { name: "반납" })).not.toBeInTheDocument();
        unmount();

        mockUser.roles = ["ROLE_ADMIN"];
        renderDesk();
        await userEvent.click(screen.getByRole("tab", { name: "전체 상담 중" }));
        await userEvent.click(await listItem("김철수"));
        expect(screen.getByRole("button", { name: "반납" })).toBeInTheDocument();
    });

    it("종료된 상담에 고객 평가가 있으면 별점과 의견을 보인다", async () => {
        const closed = { ...MINE, status: "CLOSED", rating: 4, ratingComment: "빨랐어요" };
        getSupportDesk.mockImplementation((view) => Promise.resolve(view === "CLOSED" ? [closed] : []));
        renderDesk();
        await userEvent.click(screen.getByRole("tab", { name: "종료" }));
        await userEvent.click(await listItem("김철수"));

        const rating = within(screen.getByTestId("info-panel")).getByTestId("info-rating");
        expect(within(rating).getByRole("img", { name: "별 4개" })).toBeInTheDocument();
        expect(rating).toHaveTextContent("빨랐어요");
    });

    // ── 실시간 알림(docs/58 §9) ─────────────────────────────────────────

    const push = (event) => act(() => { mockStomp.handlers["/sub/support/desk"]({ body: JSON.stringify(event) }); });

    it("상담원 전용 알림 주소를 구독한다", async () => {
        renderDesk();
        await listItem("홍길동");
        expect(mockStomp.handlers["/sub/support/desk"]).toBeDefined();
    });

    it("🔴 새 문의 알림이 오면 토스트를 띄우고 목록을 다시 읽는다", async () => {
        renderDesk();
        await listItem("홍길동");
        const before = getSupportDesk.mock.calls.length;

        push({ threadId: "9", kind: "NEW_THREAD", senderId: "1009" });

        expect(toast).toHaveBeenCalledWith("새 문의가 도착했습니다.", expect.anything());
        await waitFor(() => expect(getSupportDesk.mock.calls.length).toBeGreaterThan(before));
    });

    it("다른 상담원이 반납한 상담이 대기열로 돌아오면 알린다 — 내가 반납한 것은 알리지 않는다", async () => {
        renderDesk();
        await listItem("홍길동");

        push({ threadId: "8", kind: "RELEASED", senderId: "2001" });
        expect(toast).not.toHaveBeenCalled();

        push({ threadId: "8", kind: "RELEASED", senderId: "2999" });
        expect(toast).toHaveBeenCalledWith("반납된 상담이 대기 목록으로 돌아왔습니다.", expect.anything());
    });

    it("🔴 내가 맡은 상담에 고객이 말하면 이름과 함께 알린다", async () => {
        getSupportDesk.mockImplementation((view) => Promise.resolve(
            view === "WAITING" ? [WAITING] : view === "MINE" ? [MINE] : []));
        renderDesk();
        await listItem("홍길동");
        await waitFor(() => expect(screen.getByTestId("badge-MINE")).toHaveTextContent("1"));

        push({ threadId: "8", kind: "MESSAGE", senderId: "1003" });

        expect(toast).toHaveBeenCalledWith("김철수 님의 새 메시지", expect.anything());
    });

    it("내가 보낸 메시지, 남이 맡은 상담, 지금 열어 둔 상담은 따로 알리지 않는다", async () => {
        getSupportDesk.mockImplementation((view) => Promise.resolve(
            view === "WAITING" ? [WAITING] : view === "MINE" ? [MINE] : []));
        renderDesk();
        await listItem("홍길동");
        await waitFor(() => expect(screen.getByTestId("badge-MINE")).toBeInTheDocument());

        push({ threadId: "8", kind: "MESSAGE", senderId: "2001" });   // 나
        push({ threadId: "77", kind: "MESSAGE", senderId: "1077" });  // 내 담당이 아님
        expect(toast).not.toHaveBeenCalled();

        await userEvent.click(screen.getByRole("tab", { name: /내 담당/ }));
        await userEvent.click(await listItem("김철수"));
        push({ threadId: "8", kind: "MESSAGE", senderId: "1003" });   // 보고 있는 상담
        expect(toast).not.toHaveBeenCalled();
    });

    it("탭 배지 · 요약 — 대기 수, 내 담당 중 답변이 필요한 수", async () => {
        getSupportDesk.mockImplementation((view) => Promise.resolve(
            view === "WAITING" ? [WAITING, { ...WAITING, threadId: "6", customerName: "이영희" }]
                : view === "MINE" ? [MINE, { ...MINE, threadId: "10", awaitingReply: false }] : []));
        renderDesk();

        await waitFor(() => expect(screen.getByTestId("badge-WAITING")).toHaveTextContent("2"));
        expect(screen.getByTestId("badge-MINE")).toHaveTextContent("1");
        expect(screen.getByText("대기 2")).toBeInTheDocument();
        expect(screen.getByText("답변 필요 1")).toBeInTheDocument();
    });
});
