import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { SupportRoom } from "pages/support/SupportPage";
import { withI18n } from "i18n/testing";
import {
    closeSupportThread, getMySupportHistory, getMySupportThread, getSupportMessages, getSupportThread, openSupportThread,
    rateSupportThread,
} from "util/api";

jest.mock("util/api");
jest.mock("store/AuthContext", () => ({ useAuthContext: () => ({ user: { userId: "1001" } }) }));

// STOMP 는 흉내 낸다 — 구독 콜백을 붙잡아 서버가 보낸 것처럼 부르고, 보낸 프레임을 기록한다.
const mockStomp = { handlers: {}, sent: [] };
jest.mock("util/stompProvider", () => ({
    useStomp: () => ({
        isConnected: true,
        subscribe: (dest, cb) => { mockStomp.handlers[dest] = cb; return () => { delete mockStomp.handlers[dest]; }; },
        sendMessage: (dest, body) => { mockStomp.sent.push({ dest, body }); return true; },
    }),
}));

const WAITING = { threadId: "5", roomId: "support-5", status: "WAITING", topic: "DELIVERY", queuePosition: 3 };
const ACTIVE = { ...WAITING, status: "ACTIVE", agentId: "2001", agentName: "민지", queuePosition: null };
const CLOSED = { ...ACTIVE, status: "CLOSED", closedAt: "2026-09-27T02:00:00Z" };

const deliver = (msg) => act(() => { mockStomp.handlers["/sub/chat/room/support-5"]({ body: JSON.stringify(msg) }); });

/**
 * 1:1 고객 상담 — 고객 화면 (docs/58 §11).
 */
describe("SupportRoom — 고객 상담 화면", () => {
    beforeEach(() => {
        mockStomp.handlers = {};
        mockStomp.sent = [];
        getSupportMessages.mockResolvedValue([]);
        getMySupportHistory.mockResolvedValue([]);
    });
    afterEach(() => jest.restoreAllMocks());
    afterEach(() => jest.clearAllMocks());

    // ── 시작 · 대기 ──────────────────────────────────────────────────

    it("열린 상담이 없으면 문의 유형을 고르고 시작한다 — 고른 유형으로 열리고 대기 순번이 보인다", async () => {
        getMySupportThread.mockResolvedValue(null);
        openSupportThread.mockResolvedValue(WAITING);
        render(withI18n(<SupportRoom />));

        await userEvent.click(await screen.findByRole("button", { name: "배송" }));
        expect(screen.getByRole("button", { name: "배송" })).toHaveAttribute("aria-pressed", "true");
        await userEvent.click(screen.getByRole("button", { name: "상담 시작하기" }));

        expect(openSupportThread).toHaveBeenCalledWith("DELIVERY");
        expect(await screen.findByTestId("support-status")).toHaveTextContent("상담원 연결 대기 중");
        expect(screen.getByTestId("support-status")).toHaveTextContent("배송");
        expect(screen.getByTestId("queue")).toHaveTextContent("대기 순번 3번째");
    });

    it("유형을 고르지 않고 시작하면 '기타' 로 연다", async () => {
        getMySupportThread.mockResolvedValue(null);
        openSupportThread.mockResolvedValue({ ...WAITING, topic: "OTHER" });
        render(withI18n(<SupportRoom />));

        await userEvent.click(await screen.findByRole("button", { name: "상담 시작하기" }));

        expect(openSupportThread).toHaveBeenCalledWith("OTHER");
    });

    it("🔴 메시지는 방 id 와 본문만 보낸다 — 발신자는 서버가 세션에서 정한다", async () => {
        getMySupportThread.mockResolvedValue(WAITING);
        render(withI18n(<SupportRoom />));

        const box = await screen.findByRole("textbox", { name: "메시지를 입력하세요" });
        await userEvent.type(box, "배송이 안 와요");
        await userEvent.click(screen.getByRole("button", { name: "보내기" }));

        expect(mockStomp.sent).toEqual([{
            dest: "/pub/chat/message",
            body: { roomId: "support-5", content: "배송이 안 와요", type: "TEXT_TALK" },
        }]);
        expect(mockStomp.sent[0].body).not.toHaveProperty("senderId");
    });

    it("Shift+Enter 는 줄바꿈, Enter 는 보내기", async () => {
        getMySupportThread.mockResolvedValue(WAITING);
        render(withI18n(<SupportRoom />));

        const box = await screen.findByRole("textbox", { name: "메시지를 입력하세요" });
        await userEvent.type(box, "첫 줄{Shift>}{Enter}{/Shift}둘째 줄");
        expect(mockStomp.sent).toHaveLength(0);
        await userEvent.type(box, "{Enter}");

        expect(mockStomp.sent[0].body.content).toBe("첫 줄\n둘째 줄");
    });

    it("상담원이 연결되면(안내 수신) 상태를 다시 읽어 상담원 이름을 보인다", async () => {
        getMySupportThread.mockResolvedValue(WAITING);
        getSupportThread.mockResolvedValue(ACTIVE);
        render(withI18n(<SupportRoom />));
        await screen.findByTestId("support-status");
        await waitFor(() => expect(mockStomp.handlers["/sub/chat/room/support-5"]).toBeDefined());

        deliver({ id: "10", type: "TEXT_ENTER", content: "상담원 민지 님이 연결되었습니다.", nickname: "고객지원팀", senderId: null });

        expect(await screen.findByText("상담원 민지 님이 연결되었습니다.")).toBeInTheDocument();
        expect(await screen.findByText("상담원 민지")).toBeInTheDocument();
        expect(screen.getByTestId("support-status")).toHaveTextContent("상담 중");
        expect(screen.queryByTestId("queue")).not.toBeInTheDocument();
    });

    it("상담원이 반납하면 '상담원이 바뀝니다' 안내가 번역되어 보이고, 다시 대기 상태로 읽는다", async () => {
        getMySupportThread.mockResolvedValue(ACTIVE);
        getSupportThread.mockResolvedValue({ ...WAITING, queuePosition: 1 });
        render(withI18n(<SupportRoom />, "ja"));
        await waitFor(() => expect(mockStomp.handlers["/sub/chat/room/support-5"]).toBeDefined());

        deliver({ id: "11", type: "TEXT_LEAVE", content: "상담원이 바뀝니다. 잠시만 기다려 주세요.", senderId: null });

        expect(await screen.findByText("担当者が交代します。少々お待ちください。")).toBeInTheDocument();
        expect(await screen.findByTestId("queue")).toHaveTextContent("順番待ち 1番目");
    });

    // ── 대화 ─────────────────────────────────────────────────────────

    it("내 메시지와 상담원 메시지를 구분한다 — senderId 는 문자열로 비교한다", async () => {
        getMySupportThread.mockResolvedValue(ACTIVE);
        // 서버는 최신부터 준다.
        getSupportMessages.mockResolvedValue([
            { id: "3", type: "TEXT_TALK", content: "확인해 보겠습니다", senderId: "2001", nickname: "민지", createdAt: 1790000060000 },
            { id: "2", type: "TEXT_TALK", content: "배송이 안 와요", senderId: "1001", nickname: "나", createdAt: 1790000000000 },
        ]);
        render(withI18n(<SupportRoom />));

        const mine = await screen.findByText("배송이 안 와요");
        const theirs = screen.getByText("확인해 보겠습니다");
        expect(mine.closest("li")).toHaveAttribute("data-kind", "mine");
        expect(theirs.closest("li")).toHaveAttribute("data-kind", "theirs");
        // 오래된 것이 위에 온다.
        expect(mine.compareDocumentPosition(theirs) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    });

    it("날짜가 바뀌면 구분선을 넣는다", async () => {
        getMySupportThread.mockResolvedValue(ACTIVE);
        getSupportMessages.mockResolvedValue([
            { id: "3", type: "TEXT_TALK", content: "둘째 날", senderId: "2001", createdAt: "2026-09-02T03:00:00Z" },
            { id: "2", type: "TEXT_TALK", content: "첫째 날", senderId: "1001", createdAt: "2026-09-01T03:00:00Z" },
        ]);
        const { container } = render(withI18n(<SupportRoom />));

        await screen.findByText("둘째 날");
        expect(container.querySelectorAll('[data-kind="day"]')).toHaveLength(2);
    });

    it("같은 메시지가 기록과 실시간으로 두 번 와도 한 번만 그린다", async () => {
        getMySupportThread.mockResolvedValue(ACTIVE);
        getSupportMessages.mockResolvedValue([{ id: "2", type: "TEXT_TALK", content: "안녕하세요", senderId: "2001" }]);
        render(withI18n(<SupportRoom />));
        await screen.findByText("안녕하세요");
        await waitFor(() => expect(mockStomp.handlers["/sub/chat/room/support-5"]).toBeDefined());

        deliver({ id: "2", type: "TEXT_TALK", content: "안녕하세요", senderId: "2001" });

        expect(screen.getAllByText("안녕하세요")).toHaveLength(1);
    });

    // ── 종료 · 평가 ──────────────────────────────────────────────────

    it("상담을 종료하면 입력창이 사라지고 평가 · '새로 문의하기' 가 보인다", async () => {
        getMySupportThread.mockResolvedValue(ACTIVE);
        closeSupportThread.mockResolvedValue(CLOSED);
        jest.spyOn(window, "confirm").mockReturnValue(true);
        render(withI18n(<SupportRoom />));

        await userEvent.click(await screen.findByRole("button", { name: "상담 종료" }));

        expect(closeSupportThread).toHaveBeenCalledWith("5");
        expect(await screen.findByTestId("rating")).toBeInTheDocument();
        expect(screen.getAllByRole("button", { name: "새로 문의하기" }).length).toBeGreaterThan(0);
        expect(screen.queryByRole("textbox", { name: "메시지를 입력하세요" })).not.toBeInTheDocument();
        expect(screen.getByText(/상담이 종료되었습니다/)).toBeInTheDocument();
        expect(getMySupportHistory).toHaveBeenCalledTimes(2);   // 지난 상담 목록도 다시 읽는다
    });

    it("별점과 한 줄 의견을 한 번 남긴다 — 보내면 감사 문구로 바뀐다", async () => {
        getMySupportThread.mockResolvedValue(CLOSED);
        rateSupportThread.mockResolvedValue({ ...CLOSED, rating: 4, ratingComment: "빨랐어요" });
        render(withI18n(<SupportRoom />));

        await userEvent.click(await screen.findByRole("radio", { name: "별 4개" }));
        await userEvent.type(screen.getByRole("textbox", { name: /한 줄 의견/ }), "빨랐어요");
        await userEvent.click(screen.getByRole("button", { name: "평가 보내기" }));

        expect(rateSupportThread).toHaveBeenCalledWith("5", 4, "빨랐어요");
        expect(await screen.findByTestId("rated")).toHaveTextContent("평가해 주셔서 감사합니다.");
        expect(screen.queryByRole("button", { name: "평가 보내기" })).not.toBeInTheDocument();
    });

    it("이미 평가한 상담은 평가 입력이 없다", async () => {
        getMySupportThread.mockResolvedValue({ ...CLOSED, rating: 5 });
        render(withI18n(<SupportRoom />));

        expect(await screen.findByTestId("rated")).toBeInTheDocument();
        expect(screen.queryByRole("radiogroup")).not.toBeInTheDocument();
    });

    it("'새로 문의하기' 를 누르면 유형을 다시 고르는 화면으로 간다(바로 열지 않는다)", async () => {
        getMySupportThread.mockResolvedValue(CLOSED);
        render(withI18n(<SupportRoom />));

        const [first] = await screen.findAllByRole("button", { name: "새로 문의하기" });
        await userEvent.click(first);

        expect(await screen.findByRole("button", { name: "상담 시작하기" })).toBeInTheDocument();
        expect(openSupportThread).not.toHaveBeenCalled();
    });

    // ── 지난 상담 ────────────────────────────────────────────────────

    it("지난 상담을 누르면 그때의 대화를 읽기 전용으로 보고, 돌아올 수 있다", async () => {
        getMySupportThread.mockResolvedValue(null);
        getMySupportHistory.mockResolvedValue([
            { threadId: "3", roomId: "support-3", status: "CLOSED", topic: "PAYMENT", rating: 5,
                lastMessagePreview: "환불 완료되었습니다", createdAt: "2026-09-01T01:00:00Z", closedAt: "2026-09-01T02:00:00Z" },
        ]);
        getSupportMessages.mockImplementation((id) => Promise.resolve(id === "3"
            ? [{ id: "30", type: "TEXT_TALK", content: "환불 완료되었습니다", senderId: "2001", nickname: "민지" }] : []));
        render(withI18n(<SupportRoom />));

        const history = await screen.findByText("지난 상담");
        await userEvent.click(await within(history.parentElement).findByText("환불 완료되었습니다"));

        expect(getSupportMessages).toHaveBeenCalledWith("3", "-1");
        expect(await screen.findByText("지난 상담은 읽기만 할 수 있습니다.")).toBeInTheDocument();
        expect(screen.queryByRole("textbox", { name: "메시지를 입력하세요" })).not.toBeInTheDocument();

        await userEvent.click(screen.getByRole("button", { name: /현재 상담/ }));
        expect(await screen.findByRole("button", { name: "상담 시작하기" })).toBeInTheDocument();
    });

    it("지난 상담이 없으면 그렇다고 말한다", async () => {
        getMySupportThread.mockResolvedValue(null);
        render(withI18n(<SupportRoom />));

        expect(await screen.findByText("지난 상담이 없습니다.")).toBeInTheDocument();
    });

    // ── 그 밖 ────────────────────────────────────────────────────────

    it("일본어로도 그린다", async () => {
        getMySupportThread.mockResolvedValue(null);
        render(withI18n(<SupportRoom />, "ja"));

        expect(await screen.findByRole("button", { name: "相談を始める" })).toBeInTheDocument();
        expect(screen.getByRole("button", { name: "配送" })).toBeInTheDocument();
    });

    it("탭이 뒤에 있을 때 상담원 답변이 오면 창 제목에 수를 붙이고, 돌아오면 지운다", async () => {
        getMySupportThread.mockResolvedValue(ACTIVE);
        document.title = "쇼핑몰";
        const hidden = jest.spyOn(document, "hidden", "get").mockReturnValue(true);
        render(withI18n(<SupportRoom />));
        await screen.findByTestId("support-status");
        await waitFor(() => expect(mockStomp.handlers["/sub/chat/room/support-5"]).toBeDefined());

        deliver({ id: "20", type: "TEXT_TALK", content: "확인했습니다", senderId: "2001" });
        deliver({ id: "21", type: "TEXT_TALK", content: "내가 쓴 것", senderId: "1001" });

        await waitFor(() => expect(document.title).toBe("(1) 쇼핑몰"));

        hidden.mockReturnValue(false);
        act(() => { document.dispatchEvent(new Event("visibilitychange")); });
        await waitFor(() => expect(document.title).toBe("쇼핑몰"));
        hidden.mockRestore();
    });
});
