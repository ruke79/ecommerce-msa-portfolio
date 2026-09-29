const fs = require("fs");
const path = require("path");

/**
 * `src/<폴더>` 이름이 npm 패키지 이름과 같으면 그 폴더는 **상대 경로로만** 가져온다.
 *
 * <p>🐛 `import … from "redux/CartSlice"` 가 jest 에서는 `src/redux/CartSlice` 로 풀려 테스트가 모두 초록이었는데, 운영 빌드
 * (webpack)는 같은 문자열을 **npm 패키지 `redux` 의 하위 경로**로 풀어 "Package path ./CartSlice is not exported" 로 실패했다
 * (CartSync.jsx). 둘의 모듈 해석 순서가 달라서 생기는 일이라, 테스트로만 확인한 변경은 배포 단계에서야 깨진다.
 */
const SRC = __dirname;
const NODE_MODULES = path.join(SRC, "..", "node_modules");

const sourceFiles = (dir) =>
    fs.readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
        const full = path.join(dir, e.name);
        if (e.isDirectory()) return e.name === "__mocks__" ? [] : sourceFiles(full);
        return /\.(js|jsx)$/.test(e.name) && !/\.test\.(js|jsx)$/.test(e.name) ? [full] : [];
    });

describe("import 경로 — src 폴더와 npm 패키지 이름이 겹칠 때", () => {
    const clashing = fs
        .readdirSync(SRC, { withFileTypes: true })
        .filter((e) => e.isDirectory() && fs.existsSync(path.join(NODE_MODULES, e.name, "package.json")))
        .map((e) => e.name);

    it("겹치는 폴더를 찾는다(지금은 redux)", () => {
        expect(clashing).toContain("redux");
    });

    it("🔴 앱 코드는 그런 폴더를 `이름/…` 으로 가져오지 않는다 — webpack 은 npm 패키지로 푼다", () => {
        const offenders = [];
        for (const file of sourceFiles(SRC)) {
            const src = fs.readFileSync(file, "utf8");
            for (const m of src.matchAll(/(?:from\s+|import\s*\(\s*|require\s*\(\s*)["']([^"'./][^"']*)["']/g)) {
                const [first, ...rest] = m[1].split("/");
                const insideSrc = rest.length > 0 && fs.existsSync(path.join(SRC, first, rest.join("/")) + ".js")
                    || rest.length > 0 && fs.existsSync(path.join(SRC, first, rest.join("/")) + ".jsx");
                if (clashing.includes(first) && insideSrc) {
                    offenders.push(`${path.relative(SRC, file)}: ${m[1]}`);
                }
            }
        }
        expect(offenders).toEqual([]);
    });
});
