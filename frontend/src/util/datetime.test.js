import fs from "fs";
import path from "path";

import { formatDate, formatDateTime, localInputToServer, parseServerDate, serverToLocalInput, timeOf } from "util/datetime";

/**
 * 🐛 회귀 대상 — 화면의 시각이 9시간 어긋나 있었다.
 *
 * 서버는 UTC 를 시간대 표시 없이 내려보내는데, 자바스크립트는 그런 문자열을 **현지 시각**
 * 으로 읽는다. 스무 곳 중 다섯 곳만 `Z` 를 붙이고 있었고 나머지는 전부 틀렸다.
 */
describe("서버 시각 읽기", () => {
    /** 🔴 핵심 — 시간대 표시가 없는 값은 UTC 다. */
    it("🔴 시간대 표시가 없으면 UTC 로 읽는다", () => {
        expect(parseServerDate("2026-09-20T00:27:39").toISOString()).toBe("2026-09-20T00:27:39.000Z");
        expect(parseServerDate("2026-09-20T00:27:39.97").toISOString()).toBe("2026-09-20T00:27:39.970Z");
    });

    it("시간대 표시가 있으면 그대로 믿는다", () => {
        expect(parseServerDate("2026-09-20T00:27:39Z").toISOString()).toBe("2026-09-20T00:27:39.000Z");
        expect(parseServerDate("2026-09-20T09:27:39+09:00").toISOString()).toBe("2026-09-20T00:27:39.000Z");
    });

    /**
     * 날짜만 있는 값에 Z 를 붙이면 시간대에 따라 달력 날짜가 하루 밀린다.
     * 현지 자정으로 읽어야 어디서나 같은 날짜로 보인다.
     */
    it("날짜만 있는 값은 현지 자정으로 읽는다 — 날짜가 밀리지 않게", () => {
        const parsed = parseServerDate("2026-09-20");
        expect(parsed.getFullYear()).toBe(2026);
        expect(parsed.getMonth()).toBe(8);      // 0부터 센다
        expect(parsed.getDate()).toBe(20);
    });

    it("읽을 수 없으면 null 이고, 화면은 기본값을 쓴다", () => {
        for (const bad of [null, undefined, "", "   ", "어제", "not-a-date"]) {
            expect(parseServerDate(bad)).toBeNull();
        }
        expect(formatDateTime(null)).toBe("-");
        expect(formatDate("", "없음")).toBe("없음");
    });

    it("Date 와 epoch 밀리초도 그대로 받는다", () => {
        const now = new Date();
        expect(parseServerDate(now)).toBe(now);
        expect(parseServerDate(now.getTime()).getTime()).toBe(now.getTime());
        expect(parseServerDate(new Date("nope"))).toBeNull();
    });

    it("정렬용 값은 읽을 수 없을 때 0 이다", () => {
        expect(timeOf("2026-09-20T00:00:00")).toBeGreaterThan(0);
        expect(timeOf("망가진 값")).toBe(0);
    });
});

/**
 * 🔴 규칙이 한곳에만 있어야 한다.
 *
 * 이 검사가 없으면 새 화면이 또 `new Date(값)` 을 쓰고, 그 화면만 조용히 9시간 틀린다.
 * `util/envRefs.test.js` 가 `process.env` 직접 참조를 막는 것과 같은 방식이다.
 */
describe("시각 파싱은 util/datetime 한곳에서만", () => {
    const SRC = path.join(__dirname, "..");

    /** 인자를 받는 `new Date(...)`. `new Date()`(지금 시각)는 문제가 아니다. */
    const NEW_DATE_WITH_ARG = /new\s+Date\s*\(\s*[^)\s]/;

    const walk = (dir, found = []) => {
        for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
            const full = path.join(dir, entry.name);
            if (entry.isDirectory()) walk(full, found);
            else if (/\.(js|jsx)$/.test(entry.name) && !/\.test\.(js|jsx)$/.test(entry.name)) found.push(full);
        }
        return found;
    };

    it("🔴 util/datetime.js 밖에서는 서버 시각을 직접 파싱하지 않는다", () => {
        const offenders = [];
        for (const file of walk(SRC)) {
            if (file.endsWith(path.join("util", "datetime.js"))) continue;
            const lines = fs.readFileSync(file, "utf8").split("\n");
            lines.forEach((line, i) => {
                if (line.trim().startsWith("*") || line.trim().startsWith("//")) return;
                if (NEW_DATE_WITH_ARG.test(line)) {
                    offenders.push(`${path.relative(SRC, file)}:${i + 1}  ${line.trim().slice(0, 80)}`);
                }
            });
        }

        expect(offenders).toEqual([]);
    });
});

describe("관리자 입력칸(datetime-local) ↔ 서버 시각 — 시간대는 jest.globalSetup.js 가 Asia/Seoul 로 고정", () => {
    it("전제: 테스트가 서울 시간대에서 돈다(UTC 면 아래 검증이 빈 통과가 된다)", () => {
        expect(new Date("2026-09-27T00:00:00Z").getTimezoneOffset()).toBe(-540);
    });

    it("🔴 서울에서 오늘 16:00 을 넣으면 서버에는 UTC 07:00 으로 간다 — 그대로 보내면 서울 다음 날 01:00 이 된다", () => {
        expect(localInputToServer("2026-09-27T16:00")).toBe("2026-09-27T07:00:00");
        // 자정 근처: 서울 00:30 은 UTC 로 전날 15:30
        expect(localInputToServer("2026-09-28T00:30")).toBe("2026-09-27T15:30:00");
    });

    it("서버 값(UTC)을 입력칸에 채울 때는 현지로 — 저장했다 다시 열어도 같은 값", () => {
        expect(serverToLocalInput("2026-09-27T07:00:00")).toBe("2026-09-27T16:00");
        expect(serverToLocalInput(localInputToServer("2026-12-31T23:59"))).toBe("2026-12-31T23:59");
    });

    it("비어 있으면 null / 빈 문자열 — 서버에서 null 은 '제한 없음'", () => {
        expect(localInputToServer("")).toBeNull();
        expect(localInputToServer(null)).toBeNull();
        expect(serverToLocalInput(null)).toBe("");
        expect(serverToLocalInput("엉망")).toBe("");
    });
});

describe("datetime-local 입력칸이 있는 화면은 보낼 때 변환한다", () => {
    const SRC = path.join(__dirname, "..");
    const walk = (dir, found = []) => {
        for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
            const full = path.join(dir, entry.name);
            if (entry.isDirectory()) walk(full, found);
            else if (/\.jsx$/.test(entry.name) && !/\.test\.jsx$/.test(entry.name)) found.push(full);
        }
        return found;
    };

    it("🔴 type=\"datetime-local\" 을 쓰는 파일은 localInputToServer 를 쓴다 — 현지 시각을 UTC 로 저장하면 9시간 어긋난다", () => {
        const offenders = walk(SRC)
            .filter((f) => fs.readFileSync(f, "utf8").includes('type="datetime-local"'))
            .filter((f) => !fs.readFileSync(f, "utf8").includes("localInputToServer"))
            .map((f) => path.relative(SRC, f));
        expect(offenders).toEqual([]);
    });
});

