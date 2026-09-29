import fs from "fs";
import path from "path";

/**
 * 🔴 브라우저 번들의 `process.env` 참조는 **번들러가 바꿔 줄 수 있는 꼴**이어야 한다.
 *
 * <p>브라우저에는 `process` 가 없다. webpack 은 `process.env.이름` 을 빌드 시점 값으로
 * 문자 치환할 뿐이라, 그 꼴이 아닌 참조는 그대로 남아 운영에서
 * `Uncaught ReferenceError: process is not defined` 로 **앱 전체가 죽는다.** 테스트는
 * Node 에서 돌아 `process` 가 있으므로 여기서 잡지 않으면 아무도 못 잡는다(실제로 겪었다 —
 * `util/money.js` 의 상점 통화가 첫 화면을 죽였다).
 *
 * <p>허용하는 꼴은 하나다: `process.env.REACT_APP_*` · `process.env.NODE_ENV` ·
 * `process.env.PUBLIC_URL`. 맨몸의 `process.env`(기본 인자·전달), `process.env[동적]`,
 * 그 외 이름은 금지다. 다른 이름이 필요하면 REACT_APP_ 를 붙인다 — 그것이 "공개된다" 는
 * 표시다(B-51).
 */
const SRC = path.resolve(__dirname, "..");
const ALLOWED = /^process\.env\.(REACT_APP_[A-Z0-9_]+|NODE_ENV|PUBLIC_URL)\b/;

function walk(dir, out = []) {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) walk(full, out);
        // setupTests.js 는 Jest(Node) 전용이라 번들에 들어가지 않는다.
        else if (/\.(js|jsx)$/.test(entry.name) && !/\.test\.(js|jsx)$/.test(entry.name)
                && entry.name !== "setupTests.js") out.push(full);
    }
    return out;
}

describe("브라우저 번들의 process 참조", () => {
    it("process.env.<REACT_APP_*|NODE_ENV|PUBLIC_URL> 꼴만 쓴다 — 맨몸의 process.env 금지", () => {
        const offenders = [];
        for (const file of walk(SRC)) {
            const text = fs.readFileSync(file, "utf8");
            const lines = text.split("\n");
            lines.forEach((line, i) => {
                // 주석 줄은 제외 — 설명하려면 언급은 해야 한다. `typeof process` 가드는 안전하다.
                if (/^\s*(\/\/|\*|\/\*)/.test(line) || /typeof\s+process\b/.test(line)) return;
                let m;
                // 식별자로서의 process 만 — 경로("/payment/process")·다른 식별자(processPayment)는 제외.
                const re = /(?<![\w$./'"-])process(?![\w$])(\.env\b[^\s;,)]*)?/g;
                while ((m = re.exec(line)) !== null) {
                    const ref = m[0];
                    if (!ALLOWED.test(ref)) {
                        offenders.push(`${path.relative(SRC, file)}:${i + 1}  ${ref}`);
                    }
                }
            });
        }
        expect(offenders).toEqual([]);
    });
});
