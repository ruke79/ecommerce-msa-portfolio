const fs = require("fs");
const path = require("path");

/**
 * frontend nginx 의 보안 헤더 — 모든 응답에 붙는다(nginx.conf 의 "보안 헤더" 절).
 *
 * <p>🔒 예전에는 하나도 없어 결제 · 계정 · 관리자 화면을 남의 사이트가 iframe 에 넣을 수 있었다(클릭재킹, 2026-10-22 점검).
 * ⚠️ nginx 는 location 안에 add_header 가 하나라도 있으면 server 의 add_header 를 <b>물려받지 않는다</b> — 캐시 헤더를 단
 * /index.html · /static/ 에서 보안 헤더가 조용히 사라지는 것이 이 설정에서 가장 흔한 실수라, 그 location 들도 본다.
 *
 * <p>[JA] nginx のセキュリティヘッダー。location に add_header が一つでもあると server の分を継承しない — そこも確かめる。
 */
const CONF = fs.readFileSync(path.join(__dirname, "..", "nginx.conf"), "utf8")
    .split("\n").map((line) => line.replace(/#.*$/, "")).join("\n");

/** server 바로 안의 add_header 와 location 마다의 add_header — 중괄호를 세어 가른다. */
function parse(conf) {
    const server = [];
    const locations = [];
    const stack = [];
    let current = null;
    for (const raw of conf.split("\n")) {
        const line = raw.trim();
        const loc = line.match(/^location\s+(.+?)\s*\{$/);
        if (loc) {
            current = { name: loc[1], headers: {} };
            locations.push(current);
            stack.push("location");
            continue;
        }
        if (/\{$/.test(line)) { stack.push("block"); continue; }
        if (line === "}") {
            if (stack.pop() === "location") current = null;
            continue;
        }
        const h = line.match(/^add_header\s+(\S+)\s+"([^"]*)"/) || line.match(/^add_header\s+(\S+)\s+(\S+)/);
        if (!h) continue;
        if (current) current.headers[h[1].toLowerCase()] = h[2];
        else if (stack.length === 1) server.push([h[1].toLowerCase(), h[2]]);
    }
    return { server: Object.fromEntries(server), locations };
}

const { server, locations } = parse(CONF);

const REQUIRED = [
    "x-frame-options",
    "content-security-policy",
    "x-content-type-options",
    "referrer-policy",
    "permissions-policy",
    "strict-transport-security",
];

describe("frontend nginx — 보안 헤더", () => {
    it("🔒 server 에 여섯 가지 — 클릭재킹(DENY · frame-ancestors 'none')은 강제", () => {
        expect(Object.keys(server)).toEqual(expect.arrayContaining(REQUIRED));
        expect(server["x-frame-options"]).toBe("DENY");
        expect(server["content-security-policy"]).toContain("frame-ancestors 'none'");
        expect(server["x-content-type-options"]).toBe("nosniff");
    });

    it("CSP 는 스크립트에 'unsafe-inline' · 'unsafe-eval' 을 열지 않는다 · object-src 'none'", () => {
        const policy = server["content-security-policy"];
        const scriptSrc = policy.split(";").map((d) => d.trim()).find((d) => d.startsWith("script-src"));
        expect(scriptSrc).toBeDefined();
        expect(scriptSrc).not.toMatch(/'unsafe-(inline|eval)'/);
        expect(policy).toContain("object-src 'none'");
        expect(policy).toContain("frame-ancestors 'none'");
    });

    /**
     * 🔒 2026-10-25 강제로 바꿨다 — 사흘 동안 Report-Only 로 위반을 모으고, 운영에서 카드 · PayPal · 무통장을 돌려 콘솔 위반 0.
     * 전체 정책이 강제 헤더에 있어야 한다: Report-Only 로 되돌리면(또는 강제 헤더가 frame-ancestors 만 남으면) 스크립트 출처
     * 제한이 다시 '보고만' 이 된다. [JA] 2026-10-25 に強制へ。Report-Only へ戻ると script-src の制限がまた報告だけになる。
     */
    it("🔒 CSP 전체가 강제 — default-src · script-src 가 강제 헤더에 있고 Report-Only 는 어디에도 없다", () => {
        const policy = server["content-security-policy"];
        expect(policy).toContain("default-src 'self'");
        expect(policy).toMatch(/script-src 'self' /);
        expect(policy).toContain("frame-ancestors 'none'");
        expect(CONF).not.toMatch(/Content-Security-Policy-Report-Only/i);
    });

    it("🔒 add_header 를 쓰는 location 은 모두 같은 보안 헤더를 다시 둔다 — nginx 는 물려주지 않는다", () => {
        const withHeaders = locations.filter((l) => Object.keys(l.headers).length > 0);
        expect(withHeaders.map((l) => l.name)).toEqual(expect.arrayContaining(["= /index.html", "/static/"]));
        const missing = withHeaders.flatMap((l) =>
            REQUIRED.filter((h) => l.headers[h] !== server[h]).map((h) => `${l.name}: ${h}`));
        expect(missing).toEqual([]);
    });
});
