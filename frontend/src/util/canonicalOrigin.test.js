import { canonicalRedirectTarget } from "./canonicalOrigin";

const loc = (href) => {
    const u = new URL(href);
    return { origin: u.origin, hostname: u.hostname, pathname: u.pathname, search: u.search, hash: u.hash };
};
const API = "https://www.ysyproject.com";

describe("canonicalRedirectTarget — API 와 다른 출처로 열리면 API 출처로", () => {
    it("🔴 www 없는 주소로 열리면 경로·쿼리·해시를 그대로 www 로 옮긴다", () => {
        expect(canonicalRedirectTarget(API, loc("https://ysyproject.com/order/abc?x=1#top"), true))
            .toBe("https://www.ysyproject.com/order/abc?x=1#top");
    });

    it("http 로 열려도 옮긴다 — 출처는 스킴까지 포함한다", () => {
        expect(canonicalRedirectTarget(API, loc("http://www.ysyproject.com/"), true))
            .toBe("https://www.ysyproject.com/");
    });

    it("이미 같은 출처면 옮기지 않는다 — 무한 이동이 없다", () => {
        expect(canonicalRedirectTarget(API, loc("https://www.ysyproject.com/browse"), true)).toBeNull();
        expect(canonicalRedirectTarget(API + "/", loc("https://www.ysyproject.com/"), true)).toBeNull();
    });

    it("운영 빌드가 아니거나 로컬 호스트면 옮기지 않는다 — 로컬 개발은 원래 출처가 다르다", () => {
        expect(canonicalRedirectTarget(API, loc("https://ysyproject.com/"), false)).toBeNull();
        expect(canonicalRedirectTarget("https://localhost:8000", loc("https://localhost:3000/"), true)).toBeNull();
    });

    it("API 주소가 비었거나 상대 경로면 옮기지 않는다 — 어느 호스트든 같은 출처다", () => {
        expect(canonicalRedirectTarget("", loc("https://ysyproject.com/"), true)).toBeNull();
        expect(canonicalRedirectTarget("/api", loc("https://ysyproject.com/"), true)).toBeNull();
    });
});
