import { render, screen } from "@testing-library/react";
import { withI18n } from "i18n/testing";

import ChatFooter, { MAX_MESSAGE_LENGTH } from "components/chat/chatting/ChatFooter";

/**
 * 채팅 입력은 1000자까지 — 서버(chat-service)가 그보다 긴 본문을 버린다. 예전에는 입력에 상한이 없어 긴 글은 보내도 아무 일도
 * 일어나지 않았다(서버 상한이 생긴 뒤).
 */
describe("ChatFooter — 입력 상한", () => {
    it("🔴 입력란의 maxLength 는 서버 상한과 같은 1000", () => {
        render(withI18n(<ChatFooter onChatSumbmit={() => {}} />));
        expect(MAX_MESSAGE_LENGTH).toBe(1000);
        expect(screen.getByRole("textbox")).toHaveAttribute("maxLength", "1000");
    });
});
