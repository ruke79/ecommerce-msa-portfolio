import { render, screen } from "@testing-library/react";
import { withI18n } from "i18n/testing";

import ChatContent from "components/chat/chatting/ChatContent";

/**
 * "내 메시지" 판별은 uid(senderId)로 — 이메일이 아니다(docs/48 §6-⑥).
 *
 * <p>🐛 두 가지를 함께 고정한다. (1) 예전에는 이메일로 비교했고, 그래서 서버가 메시지마다 발신자 이메일을 방의 모든
 * 참여자에게 보냈다. (2) id 로 바꾸는 순간의 함정 — 19자리 TSID 가 JS Number 를 지나면 끝자리가 뭉개져, 문자열로
 * 온 senderId 와 영영 같지 않다. 비교는 글자로 한다.
 */
const ME = "1234567890123456789";

jest.mock("store/AuthContext", () => ({
    useAuthContext: () => ({ user: { email: "me@example.com", userId: "1234567890123456789" } }),
}));

jest.mock("./ChatMessages", () => ({
    MyChat: ({ msg }) => <div data-testid="mine">{msg}</div>,
    OthersChat: ({ msg }) => <div data-testid="others">{msg}</div>,
    OthersChatWithThumbnail: ({ msg }) => <div data-testid="others">{msg}</div>,
}));

const T0 = Date.UTC(2026, 8, 18, 6, 24, 5);
const msg = (id, senderId, content, over = {}) => ({ id, senderId, content, createdAt: T0, ...over });

describe("ChatContent — 내 메시지 판별", () => {
    it("🔴 senderId 가 내 uid(문자열)와 같으면 내 메시지다 — 19자리 그대로", () => {
        render(withI18n(<ChatContent chattingList={[msg("1", ME, "내가 쓴 것"), msg("2", "99", "남이 쓴 것")]} />));

        expect(screen.getByTestId("mine")).toHaveTextContent("내가 쓴 것");
        expect(screen.getByTestId("others")).toHaveTextContent("남이 쓴 것");
    });

    it("🐛 Number 로 뭉개진 id(…800)는 내 것이 아니다 — 글자로 비교해야 드러난다", () => {
        const rounded = String(Number(ME)); // "1234567890123456800"
        expect(rounded).not.toBe(ME);
        render(withI18n(<ChatContent chattingList={[msg("1", rounded, "비슷한 id")]} />));

        expect(screen.queryByTestId("mine")).toBeNull();
    });

    it("🔒 같은 이메일이 실려 와도 내 메시지로 보지 않는다 — 이메일은 판별에 쓰지 않는다", () => {
        render(withI18n(<ChatContent chattingList={[msg("1", "99", "사칭", { email: "me@example.com" })]} />));

        expect(screen.queryByTestId("mine")).toBeNull();
    });

    it("발신자를 모르는 옛 메시지(senderId 없음)는 남의 메시지로 그린다", () => {
        render(withI18n(<ChatContent chattingList={[msg("1", undefined, "옛 메시지")]} />));

        expect(screen.getByTestId("others")).toHaveTextContent("옛 메시지");
    });
});
