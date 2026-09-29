import { render, screen } from "@testing-library/react";
import { withI18n } from "i18n/testing";

import ChatContent, {
    dayKey,
    formatChatDate,
    formatChatTime,
    toChatDate,
} from "components/chat/chatting/ChatContent";

jest.mock("store/AuthContext", () => ({
    useAuthContext: () => ({ user: { email: "me@example.com", userId: "1234567890123456789" } }),
}));

const T0 = Date.UTC(2026, 8, 18, 6, 24, 5); // 2026-09-18 06:24:05Z (금요일)

const msg = (over = {}) => ({
    id: "m1",
    senderId: "2",
    content: "안녕하세요",
    createdAt: T0,
    ...over,
});

const renderChat = (list) => render(withI18n(<ChatContent chattingList={list} />));

/**
 * 사용자 보고: "채팅방 만들고 채팅 입력하니깐 Cannot read properties of undefined
 * (reading 'trim')" — 그 뒤 ErrorBoundary 화면("화면을 그리는 중 오류가 발생했습니다")
 * 에서 빠져나올 수 없었다.
 *
 * <p>원인은 날짜 구분선을 만들 때 {@code toLocaleDateString()} 의 결과를 {@code '.'}
 * 로 쪼갠 것이다. 점으로 나뉘는 로케일은 {@code ko-KR} 뿐이다.
 */
describe("ChatContent — 날짜 구분선", () => {
    let langBackup;

    beforeEach(() => {
        langBackup = document.documentElement.lang;
    });

    afterEach(() => {
        document.documentElement.lang = langBackup;
    });

    /**
     * 🔴 회귀 대상. 첫 메시지는 <b>반드시</b> 날짜 구분선을 만든다(이전 메시지가 없으면
     * "날짜가 바뀌었다" 로 본다). 그래서 방을 만들고 첫 메시지를 보내는 순간이 재현 조건이다.
     */
    it("🔴 영어 로케일에서 첫 메시지를 그려도 죽지 않는다", () => {
        document.documentElement.lang = "en";

        expect(() => renderChat([msg()])).not.toThrow();
        expect(screen.getByText("안녕하세요")).toBeInTheDocument();
    });

    it("🔴 일본어 로케일에서도 죽지 않는다", () => {
        document.documentElement.lang = "ja";

        expect(() => renderChat([msg()])).not.toThrow();
    });

    it("한국어에서는 예전과 같은 문구가 나온다", () => {
        document.documentElement.lang = "ko";
        renderChat([msg()]);

        expect(screen.getByText(/2026년 9월 18일 금요일/)).toBeInTheDocument();
    });

    /**
     * 🔴 createdAt 이 없는 메시지. 발신자의 회원 복제 행이 없으면 서버가 시각 없이 보내던
     * 경로가 있었다(ChatService.sendMessage 의 `if (member != null)`). 화면은 말풍선만
     * 그리고 넘어가야 한다 — 시각이 없다고 대화 전체가 사라지면 안 된다.
     */
    it("🔴 createdAt 이 없어도 말풍선은 그린다", () => {
        expect(() => renderChat([msg({ createdAt: undefined, id: undefined })])).not.toThrow();
        expect(screen.getByText("안녕하세요")).toBeInTheDocument();
    });

    it("같은 날의 두 번째 메시지에는 구분선을 다시 넣지 않는다", () => {
        document.documentElement.lang = "ko";
        renderChat([msg(), msg({ id: "m2", content: "두 번째", createdAt: T0 + 60_000 })]);

        expect(screen.getAllByText(/2026년 9월 18일/)).toHaveLength(1);
    });

    it("날짜가 바뀌면 구분선을 넣는다", () => {
        document.documentElement.lang = "ko";
        const nextDay = T0 + 24 * 60 * 60 * 1000;
        renderChat([msg(), msg({ id: "m2", content: "다음 날", createdAt: nextDay })]);

        expect(screen.getByText(/2026년 9월 18일/)).toBeInTheDocument();
        expect(screen.getByText(/2026년 9월 19일/)).toBeInTheDocument();
    });
});

describe("날짜·시각 도우미", () => {
    it("epoch millis · ISO 문자열을 읽고, 못 읽으면 null", () => {
        expect(toChatDate(T0)?.getTime()).toBe(T0);
        expect(toChatDate("2026-09-18T06:24:05Z")?.getTime()).toBe(T0);
        expect(toChatDate(undefined)).toBeNull();
        expect(toChatDate(null)).toBeNull();
        expect(toChatDate("not a date")).toBeNull();
    });

    /**
     * 🔴 로케일마다 다른 문자열이 <b>같은 코드</b>에서 나온다 — 쪼개서 조립하지 않기
     * 때문이다. 이것이 "en 에서 죽지 않는다" 의 원리다.
     */
    it("🔴 형식을 요청하지, 문자열을 쪼개지 않는다", () => {
        const d = new Date(T0);

        expect(formatChatDate(d, "ko-KR")).toMatch(/2026년 9월 18일 금요일/);
        expect(formatChatDate(d, "ja-JP")).toMatch(/2026年9月18日金曜日/);
        expect(formatChatDate(d, "en-US")).toMatch(/Friday, September 18, 2026/);
    });

    it("시각에는 초가 없다 — 영어 로케일에서도 오전/오후가 남는다", () => {
        const d = new Date(T0);

        expect(formatChatTime(d, "ko-KR")).not.toMatch(/:\d\d:\d\d/);
        expect(formatChatTime(d, "en-US")).toMatch(/[AP]M$/);
        expect(formatChatTime(d, "en-US")).not.toMatch(/:\d\d:\d\d/);
    });

    it("빈 날짜는 빈 문구", () => {
        expect(formatChatDate(null)).toBe("");
        expect(formatChatTime(null)).toBe("");
        expect(dayKey(null)).toBe("");
    });
});
