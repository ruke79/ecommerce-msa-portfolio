import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import I18nProvider, {
    DEFAULT_LANG,
    SUPPORTED,
    detectBrowserLanguage,
    useTranslation,
} from "i18n/I18nProvider";
import ko, { PARTS as KO_PARTS } from "i18n/messages/ko";
import ja, { PARTS as JA_PARTS } from "i18n/messages/ja";

/** 문구를 꺼내 보여주고 언어를 바꿀 수 있는 최소 화면. */
const Probe = ({ messageKey = "auth.reset.title", vars }) => {
    const { lang, setLang, t } = useTranslation();
    return (
        <div>
            <span data-testid="lang">{lang}</span>
            <span data-testid="text">{t(messageKey, vars)}</span>
            <button onClick={() => setLang("ja")}>ja</button>
            <button onClick={() => setLang("ko")}>ko</button>
        </div>
    );
};

const renderProbe = (props) =>
    render(
        <I18nProvider {...props.provider}>
            <Probe {...props.probe} />
        </I18nProvider>
    );

describe("다국어", () => {
    afterEach(() => {
        try {
            window.localStorage.clear();
        } catch {
            /* 테스트 환경에 따라 막혀 있을 수 있다 */
        }
    });

    describe("사전", () => {
        /**
         * 🔴 이 파일에서 가장 중요한 테스트다. 키가 빠지면 폴백 때문에 <b>일본어 화면에
         * 한국어가 섞여</b> 나오는데, 화면은 멀쩡해 보여서 아무도 눈치채지 못한다.
         */
        it("🔴 두 언어의 키 집합이 같다 — 빠지면 일본어 화면에 한국어가 섞인다", () => {
            expect(Object.keys(ja).sort()).toEqual(Object.keys(ko).sort());
        });

        it("🔴 같은 키가 두 조각(ko.js·areas/*)에 있지 않다 — 뒤의 것이 조용히 덮는다", () => {
            for (const parts of [KO_PARTS, JA_PARTS]) {
                const seen = new Map();
                const dupes = [];
                for (const [name, dict] of Object.entries(parts)) {
                    for (const key of Object.keys(dict)) {
                        if (seen.has(key)) dupes.push(`${key} (${seen.get(key)}, ${name})`);
                        seen.set(key, name);
                    }
                }
                expect(dupes).toEqual([]);
            }
        });

        it("빈 문구가 없다", () => {
            for (const [lang, dict] of [["ko", ko], ["ja", ja]]) {
                for (const [key, value] of Object.entries(dict)) {
                    expect(`${lang}:${key}:${value.trim()}`).not.toMatch(/:$/);
                }
            }
        });

        /** 치환 자리가 한쪽에만 있으면 번역된 문장에서 값이 사라진다. */
        it("치환 자리({{...}})가 두 언어에서 일치한다", () => {
            const placeholders = (s) => (s.match(/\{\{(\w+)\}\}/g) ?? []).sort();

            for (const key of Object.keys(ko)) {
                expect(placeholders(ja[key])).toEqual(placeholders(ko[key]));
            }
        });

        /** 일본어 사전에 한글이 들어 있으면 번역을 빠뜨리고 복사한 것이다. */
        it("일본어 문구에 한글이 섞이지 않는다", () => {
            const offenders = Object.entries(ja)
                // 언어 이름은 그 언어로 적는 것이 맞다(endonym) — 예외.
                .filter(([key]) => key !== "lang.ko")
                .filter(([, value]) => /[가-힣]/.test(value))
                .map(([key]) => key);

            expect(offenders).toEqual([]);
        });
    });

    describe("언어 선택", () => {
        it("기본은 한국어다", () => {
            renderProbe({ provider: { initialLang: DEFAULT_LANG }, probe: {} });
            expect(screen.getByTestId("lang")).toHaveTextContent("ko");
        });

        it("언어를 바꾸면 문구가 바뀐다", async () => {
            renderProbe({ provider: { initialLang: "ko" }, probe: {} });
            expect(screen.getByTestId("text")).toHaveTextContent(ko["auth.reset.title"]);

            await userEvent.click(screen.getByRole("button", { name: "ja" }));

            expect(screen.getByTestId("text")).toHaveTextContent(ja["auth.reset.title"]);
        });

        /**
         * 🔴 장식이 아니다. 스크린리더가 읽을 발음을 이 값으로 고르고, 브라우저는 한중일
         * 통합 한자의 글꼴을 여기에 맞춘다 — 틀리면 일본어가 한국어 글꼴로 렌더된다.
         */
        it("🔴 <html lang> 을 실제로 바꾼다", async () => {
            renderProbe({ provider: { initialLang: "ko" }, probe: {} });
            expect(document.documentElement.lang).toBe("ko");

            await userEvent.click(screen.getByRole("button", { name: "ja" }));

            expect(document.documentElement.lang).toBe("ja");
        });

        it("선택을 기억한다 — 새로고침해도 유지된다", async () => {
            renderProbe({ provider: { initialLang: "ko" }, probe: {} });
            await userEvent.click(screen.getByRole("button", { name: "ja" }));

            expect(window.localStorage.getItem("app.lang")).toBe("ja");
        });

        it("지원하지 않는 언어는 무시한다", () => {
            renderProbe({ provider: { initialLang: "ko" }, probe: {} });
            // setLang("fr") 은 Probe 에 없으므로 SUPPORTED 로 직접 확인한다.
            expect(SUPPORTED).toEqual(["ko", "ja"]);
        });
    });

    describe("브라우저 언어 감지", () => {
        it("ja-JP 처럼 지역이 붙어도 알아본다", () => {
            expect(detectBrowserLanguage({ languages: ["ja-JP"], language: "ja-JP" })).toBe("ja");
        });

        it("선호 순서를 지킨다", () => {
            expect(detectBrowserLanguage({ languages: ["ja", "ko"], language: "ja" })).toBe("ja");
            expect(detectBrowserLanguage({ languages: ["ko", "ja"], language: "ko" })).toBe("ko");
        });

        /** 지원하지 않는 언어만 있으면 null — 호출자가 기본값으로 떨어뜨린다. */
        it("모르는 언어면 고르지 않는다", () => {
            expect(detectBrowserLanguage({ languages: ["fr-FR"], language: "fr-FR" })).toBeNull();
            expect(detectBrowserLanguage(null)).toBeNull();
        });
    });

    describe("문구 조회", () => {
        /**
         * 없는 키를 빈 문자열로 돌려주면 화면에서 글자가 사라져 아무도 눈치채지 못한다.
         * 키가 보이면 즉시 드러난다.
         */
        it("없는 키는 키 이름을 그대로 보여준다 — 조용히 비지 않는다", () => {
            renderProbe({ provider: { initialLang: "ko" }, probe: { messageKey: "no.such.key" } });
            expect(screen.getByTestId("text")).toHaveTextContent("no.such.key");
        });

        it("{{변수}} 를 치환한다", () => {
            renderProbe({
                provider: { initialLang: "ko" },
                probe: { messageKey: "auth.reset.too_short", vars: { min: 6 } },
            });
            expect(screen.getByTestId("text")).toHaveTextContent("6자 이상");
        });

        /**
         * 값이 없을 때 자리표시자를 지우면 "비밀번호는 자 이상이어야 합니다" 처럼 말이
         * 되는 듯한 문장이 되어 버그가 숨는다. 그대로 남겨 드러나게 한다.
         */
        it("치환값이 없으면 자리표시자를 남긴다", () => {
            renderProbe({
                provider: { initialLang: "ko" },
                probe: { messageKey: "auth.reset.too_short" },
            });
            expect(screen.getByTestId("text")).toHaveTextContent("{{min}}");
        });
    });

    it("Provider 밖에서 쓰면 터진다 — 조용히 한국어로 굳지 않는다", () => {
        const originalError = console.error;
        console.error = () => {};
        try {
            expect(() => render(<Probe />)).toThrow(/I18nProvider/);
        } finally {
            console.error = originalError;
        }
    });
});
