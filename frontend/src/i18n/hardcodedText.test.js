/**
 * 🔴 화면에 보이는 글자는 사전(i18n/messages)에서만 온다 — docs/60 §5.
 *
 * <p>일본어로 바꿨는데 한국어·영어가 남는 것은 누군가 문구를 JSX 에 그대로 쓴 것이다. 화면은 멀쩡해 보여서(한국어 화면에서는
 * 맞는 글자다) 리뷰에서도 안 보인다. 이 검사가 소스를 구문 트리로 읽어 찾는다:
 *
 * <ul>
 *   <li>JSX 텍스트에 한글이나 영어 단어</li>
 *   <li>보이는 JSX 속성(placeholder·title·alt·aria-label·label)의 글자</li>
 *   <li>한글이 든 문자열·템플릿 리터럴 — 어디에 있든(토스트·오류·상태 이름표). 예외는 console.* 의 인자</li>
 *   <li>영어 문장을 토스트·alert·confirm·setError·Error 로 넘기는 것, 객체의 label·title·description·message 속성</li>
 * </ul>
 *
 * <p>봐주는 것은 hardcodedText.allow.js 에만 — 상표·통화 기호처럼 번역하지 않는 것이 맞는 글자뿐이다.
 *
 * <p>일부만 보고 싶으면: {@code I18N_SCAN_ONLY=pages/admin,components/admin npx jest src/i18n/hardcodedText.test.js}
 */
import fs from "fs";
import path from "path";
import { parse } from "@babel/parser";
import traverse from "@babel/traverse";

import { ALLOWED_FILES, ALLOWED_TEXT } from "i18n/hardcodedText.allow";

const SRC = path.resolve(__dirname, "..");
const HANGUL = /[가-힣]/;
const WORD = /[A-Za-z]{2,}/;
const PROSE = /[A-Za-z]{2,}[^"]*\s+[A-Za-z]{2,}|^[A-Z][a-z]+[.!?]?$/;
const VISIBLE_ATTRS = new Set(["placeholder", "title", "alt", "aria-label", "label"]);
const MESSAGE_PROPS = new Set(["label", "title", "description", "message", "text", "header", "placeholder", "msg"]);
const MESSAGE_CALLEE = /^(toast|alert|confirm|prompt|setError|setMessage|setNotice|setSaveError|setErr|showError|Error|TypeError)$/;

const traverseFn = traverse.default ?? traverse;

function sourceFiles(dir) {
    const out = [];
    for (const name of fs.readdirSync(dir)) {
        const full = path.join(dir, name);
        const rel = path.relative(SRC, full).split(path.sep).join("/");
        if (fs.statSync(full).isDirectory()) {
            if (name === "__mocks__" || rel === "i18n/messages") continue;
            out.push(...sourceFiles(full));
        } else if (/\.(jsx?|tsx?)$/.test(name) && !/\.test\.|setupTests/.test(name) && !ALLOWED_FILES.has(rel)) {
            out.push(rel);
        }
    }
    return out;
}

const calleeName = (callee) => {
    if (!callee) return "";
    if (callee.type === "Identifier") return callee.name;
    if (callee.type === "MemberExpression") {
        const obj = callee.object.type === "Identifier" ? callee.object.name : "";
        const prop = callee.property.name ?? "";
        if (obj === "console") return "console";
        if (obj === "toast" || obj === "window") return obj === "toast" ? "toast" : prop;
        return prop;
    }
    return "";
};

const allowed = (text) => ALLOWED_TEXT.has(text.trim()) || !text.trim();

/** 한 파일의 위반 — [줄, 종류, 글자]. */
export function scan(rel) {
    const code = fs.readFileSync(path.join(SRC, rel), "utf8");
    const ast = parse(code, { sourceType: "module", plugins: ["jsx"] });
    const found = [];
    const report = (node, kind, text) => {
        const clean = String(text).replace(/\s+/g, " ").trim();
        if (!allowed(clean)) found.push(`${rel}:${node.loc.start.line} [${kind}] ${clean.slice(0, 100)}`);
    };
    const insideConsole = (p) => !!p.findParent((q) => q.isCallExpression() && calleeName(q.node.callee) === "console");

    traverseFn(ast, {
        JSXText(p) {
            const v = p.node.value;
            if (HANGUL.test(v) || WORD.test(v)) report(p.node, "jsx", v);
        },
        StringLiteral(p) {
            const v = p.node.value;
            const parent = p.parent;
            if (["ImportDeclaration", "ExportAllDeclaration", "ExportNamedDeclaration"].includes(parent.type)) return;
            if (parent.type === "CallExpression" && ["require", "t", "translate"].includes(calleeName(parent.callee))
                && parent.arguments[0] === p.node) return;
            if (parent.type === "JSXAttribute") {
                const n = parent.name.name;
                if (HANGUL.test(v) || (VISIBLE_ATTRS.has(n) && WORD.test(v))) report(p.node, `attr:${n}`, v);
                return;
            }
            if (insideConsole(p)) return;
            if (HANGUL.test(v)) { report(p.node, "string", v); return; }
            // 영어 문장 — 사람에게 보여 주는 자리에서만(클래스 이름·키·URL 과 구분)
            if ((parent.type === "CallExpression" || parent.type === "NewExpression")
                && MESSAGE_CALLEE.test(calleeName(parent.callee)) && WORD.test(v)) {
                report(p.node, "message", v);
            } else if (parent.type === "ObjectProperty" && parent.value === p.node
                && MESSAGE_PROPS.has(parent.key.name ?? parent.key.value) && PROSE.test(v)) {
                report(p.node, `prop:${parent.key.name ?? parent.key.value}`, v);
            }
        },
        TemplateLiteral(p) {
            if (insideConsole(p)) return;
            const v = p.node.quasis.map((q) => q.value.cooked).join("{}");
            if (HANGUL.test(v)) report(p.node, "template", v);
        },
    });
    return found;
}

describe("화면 문구는 사전에서만 (docs/60 §5)", () => {
    const only = (process.env.I18N_SCAN_ONLY ?? "").split(",").map((s) => s.trim()).filter(Boolean);
    const files = sourceFiles(SRC).filter((f) => only.length === 0 || only.some((o) => f.startsWith(o)));

    it("🔴 하드코딩된 한국어·영어 문구가 없다", () => {
        const violations = files.flatMap(scan);
        if (violations.length) {
            // 한눈에 보이게 — 어느 파일 몇째 줄의 어떤 글자인지.
            throw new Error(`사전으로 옮기지 않은 문구 ${violations.length}개:\n` + violations.join("\n"));
        }
    });

    it("검사기가 실제로 잡는다 — 자기 점검", () => {
        const tmp = path.join(SRC, "i18n", "__scan_probe__.jsx");
        fs.writeFileSync(tmp, [
            'export const A = () => <p title="Hello there">안녕하세요 {x}</p>;',
            'toast.error("Something went wrong");',
            'console.log("로그는 괜찮다");',
            'const s = { label: "Sign in now" };',
            'const ok = <b>PayPal</b>;',
        ].join("\n"));
        try {
            const found = scan("i18n/__scan_probe__.jsx").join("\n");
            expect(found).toContain("[jsx] 안녕하세요");
            expect(found).toContain("[attr:title] Hello there");
            expect(found).toContain("[message] Something went wrong");
            expect(found).toContain("[prop:label] Sign in now");
            expect(found).not.toContain("로그는 괜찮다");
            expect(found).not.toContain("PayPal");
        } finally {
            fs.unlinkSync(tmp);
        }
    });
});
