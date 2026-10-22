const fs = require("fs");
const path = require("path");

/**
 * index.html 은 이 출처의 번들만 받는다 — 외부 CSS · 스크립트를 두지 않는다.
 *
 * <p>🔒 예전에는 채팅 아이콘 셋을 위해 Font Awesome 의 all.min.css 를 cdnjs 에서 무결성 해시(SRI) 없이 받았다. CDN 이 오염되면
 * 모든 화면에 남의 CSS 가 들어오고(속성 선택자로 입력값을 빼낼 수 있다), 그 CSS 가 다시 받는 웹폰트는 SRI 로도 지킬 수 없다.
 * 같은 Font Awesome 5 아이콘은 react-icons/fa(SVG)로 그린다.
 *
 * <p>[JA] index.html は同じオリジンのバンドルだけを読む。以前は Font Awesome を CDN から SRI なしで読んでいた。
 */
const SRC = __dirname;
const INDEX = path.join(SRC, "..", "public", "index.html");

const sourceFiles = (dir) =>
    fs.readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
        const full = path.join(dir, e.name);
        if (e.isDirectory()) return e.name === "__mocks__" ? [] : sourceFiles(full);
        return /\.(js|jsx)$/.test(e.name) && !/\.test\.(js|jsx)$/.test(e.name) ? [full] : [];
    });

describe("index.html — 외부 자원", () => {
    // 주석 안의 설명(예전 주소)은 세지 않는다 — 브라우저가 받는 태그만 본다.
    const html = fs.readFileSync(INDEX, "utf8").replace(/<!--[\s\S]*?-->/g, "");

    it("🔒 외부 출처의 stylesheet · script 가 없다", () => {
        const tags = html.match(/<(link|script)\b[^>]*>/gi) ?? [];
        const external = tags.filter((tag) => /\b(href|src)\s*=\s*["']?(https?:)?\/\//i.test(tag));
        expect(external).toEqual([]);
    });

    it("Font Awesome 의 CSS 클래스(fas fa-…)를 쓰는 곳이 없다 — CSS 가 없으면 빈칸이 된다", () => {
        const users = sourceFiles(SRC).filter((f) => /className=["'`]fa[srb]? fa-/.test(fs.readFileSync(f, "utf8")));
        expect(users.map((f) => path.relative(SRC, f))).toEqual([]);
    });
});
