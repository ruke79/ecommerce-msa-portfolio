import { SERVER_FORMATS, chatMessageText, localizeChatName, localizeChatNotice } from "util/chatNotice";
import ko from "i18n/messages/ko";
import ja from "i18n/messages/ja";

const tOf = (dict) => (key, vars) =>
    (dict[key] ?? key).replace(/\{\{(\w+)\}\}/g, (m, n) => (vars && n in vars ? vars[n] : m));
const tJa = tOf(ja);
const tKo = tOf(ko);

/** 서버 모양에 예시 값을 넣는다 — {name}·{id}. */
const sample = (format) => format.replace("{name}", "Tanaka").replace("{id}", "3f2k");   // 이름은 사람이 정한 값이라 그대로 남는다

describe("채팅 시스템 문구 (docs/60 §8)", () => {
    it("🔴 서버가 만드는 모양은 모두 알아본다 — 일본어 화면에 한국어·영어가 남지 않는다", () => {
        for (const format of SERVER_FORMATS) {
            const text = sample(format);
            const notice = localizeChatNotice(tJa, text);
            const out = notice !== text ? notice : localizeChatName(tJa, text);
            expect({ format, out }).toEqual({ format, out: expect.not.stringMatching(/[가-힣]|entered|leaved/u) });
        }
    });

    it("입장·퇴장 — 영어 문장이 화면 언어로, 사람이 정한 닉네임은 그대로", () => {
        expect(localizeChatNotice(tJa, "Tanaka entered.")).toBe("Tanakaさんが入室しました。");
        expect(localizeChatNotice(tKo, "홍길동 leaved.")).toBe("홍길동 님이 나갔습니다.");
    });

    it("상담 연결·종료", () => {
        expect(localizeChatNotice(tJa, "상담원 Kim 님이 연결되었습니다.")).toBe("サポート担当者のKimさんが対応を開始しました。");
        expect(localizeChatNotice(tJa, "고객님이 상담을 종료했습니다.")).toBe("お客様がお問い合わせを終了しました。");
    });

    it("안내 속의 대체 이름도 바꾼다 — 탈퇴한 회원의 입장 안내", () => {
        expect(localizeChatNotice(tJa, "탈퇴한 사용자 entered.")).toBe("退会したユーザーさんが入室しました。");
        expect(localizeChatName(tJa, "회원 3f2k")).toBe("会員 3f2k");
        expect(localizeChatName(tJa, "고객지원팀")).toBe("カスタマーサポート");
    });

    it("🔒 사람이 쓴 글은 바꾸지 않는다 — 안내 자리가 아니면 번역하지 않는다", () => {
        const human = { type: "TALK", content: "고객님이 상담을 종료했습니다." };
        expect(chatMessageText(tJa, human)).toBe("고객님이 상담을 종료했습니다.");
        const notice = { type: "TEXT_LEAVE", content: "고객님이 상담을 종료했습니다." };
        expect(chatMessageText(tJa, notice)).toBe("お客様がお問い合わせを終了しました。");
        expect(localizeChatName(tJa, "회원님")).toBe("회원님");
        expect(localizeChatNotice(tJa, "안녕하세요")).toBe("안녕하세요");
    });
});
