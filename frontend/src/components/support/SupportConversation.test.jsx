import { fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import SupportConversation, { MAX_MESSAGE_LENGTH } from "components/support/SupportConversation";
import { withI18n } from "i18n/testing";

const msg = (id, senderId, content) => ({ id, type: "TEXT_TALK", senderId, content, createdAt: 1790000000000 + Number(id) });

const renderIt = (props) => render(withI18n(
    <SupportConversation messages={[]} myId="1001" canSend onSend={() => true} isConnected {...props} />,
));

/** 위로 올려 읽는 중인 것처럼 — jsdom 은 크기가 0 이라 직접 준다. */
function scrollUp(list) {
    Object.defineProperty(list, "scrollHeight", { configurable: true, value: 2000 });
    Object.defineProperty(list, "clientHeight", { configurable: true, value: 400 });
    list.scrollTop = 100;
    fireEvent.scroll(list);
}

/**
 * 상담 대화창 (docs/58 §11).
 */
describe("SupportConversation", () => {
    it("위를 읽는 중에 상대 메시지가 오면 자리를 옮기지 않고 '새 메시지 N' 을 띄운다 — 누르면 바닥으로", async () => {
        const { rerender } = renderIt({ messages: [msg("1", "2001", "안녕하세요")] });
        const list = screen.getByRole("log");
        scrollUp(list);

        rerender(withI18n(<SupportConversation messages={[msg("1", "2001", "안녕하세요"), msg("2", "2001", "확인했습니다")]}
            myId="1001" canSend onSend={() => true} isConnected />));

        expect(list.scrollTop).toBe(100);
        const pill = screen.getByTestId("new-messages");
        expect(pill).toHaveTextContent("새 메시지 1");

        await userEvent.click(pill);
        expect(list.scrollTop).toBe(2000);
        expect(screen.queryByTestId("new-messages")).not.toBeInTheDocument();
    });

    it("내가 보낸 메시지는 알림 없이 바닥으로 따라간다", () => {
        const { rerender } = renderIt({ messages: [msg("1", "2001", "안녕하세요")] });
        const list = screen.getByRole("log");
        scrollUp(list);

        rerender(withI18n(<SupportConversation messages={[msg("1", "2001", "안녕하세요"), msg("2", "1001", "내 말")]}
            myId="1001" canSend onSend={() => true} isConnected />));

        expect(screen.queryByTestId("new-messages")).not.toBeInTheDocument();
        expect(list.scrollTop).toBe(2000);
    });

    it("같은 사람이 이어 말하면 이름은 첫 말에만", () => {
        renderIt({
            messages: [
                { ...msg("1", "2001", "첫 말"), nickname: "민지" },
                { ...msg("2", "2001", "둘째 말"), nickname: "민지" },
            ],
        });
        expect(screen.getAllByText("민지")).toHaveLength(1);
    });

    it("글자 수가 한도에 가까워지면 남은 양을 보인다", async () => {
        renderIt();
        const box = screen.getByRole("textbox", { name: "메시지를 입력하세요" });
        expect(screen.queryByTestId("char-count")).not.toBeInTheDocument();

        fireEvent.change(box, { target: { value: "가".repeat(900) } });

        expect(screen.getByTestId("char-count")).toHaveTextContent(`900/${MAX_MESSAGE_LENGTH}`);
    });

    it("빠른 답변이 없으면(고객 화면) 빠른 답변 버튼이 없다", () => {
        renderIt();
        expect(screen.queryByRole("button", { name: "빠른 답변" })).not.toBeInTheDocument();
    });

    it("⚠️ 떠 있는 요소가 헤더 메뉴를 덮지 않게 대화창이 자기 쌓임 맥락(isolate)을 가진다", () => {
        const { container } = renderIt();
        expect(container.firstChild).toHaveClass("isolate");
    });
});
