/**
 * 페이지가 API 와 <b>다른 출처</b>로 열렸으면 API 의 출처로 옮길 주소를 돌려준다. 옮길 필요가 없으면 null.
 *
 * <h3>🐛 Firefox 에서 "Cross-Origin Request Blocked … token/refresh (403)"</h3>
 *
 * <p>운영 번들의 API 주소는 빌드 때 박힌 절대 주소({@code REACT_APP_API_URL=https://www.<도메인>})다.
 * 그런데 Cloudflare Tunnel 은 호스트명을 가리지 않고 사이트를 내준다 — {@code https://<도메인>}(www 없음)
 * 으로 들어와도 화면은 뜬다. 그러면 모든 API 호출이 www 로 가는 <b>교차 출처 요청</b>이 되고,
 * 게이트웨이의 CORS 허용 목록(로컬 개발 출처뿐)에 없어 403 으로 거절된다. 로그인 쿠키도 www 에 있어
 * 그 주소에서는 어차피 로그인이 이어지지 않는다.
 *
 * <p>운영은 "프론트와 API 가 같은 출처" 로 설계됐다(k8s-overlays/prod/cloudflared.yaml). 그러니 다른
 * 출처로 열렸다면 고칠 곳은 CORS 가 아니라 <b>주소</b>다 — 같은 경로로 API 출처에 다시 연다.
 *
 * <p>하지 않는 경우: 운영 빌드가 아닐 때, API 주소가 비었거나 상대 경로일 때(그때는 어느 호스트든 같은
 * 출처다), 로컬 호스트일 때(로컬 개발은 3000 ↔ 8000 으로 원래 출처가 다르다).
 */
const LOCAL_HOSTS = new Set(["localhost", "127.0.0.1", "[::1]", "::1"]);

export function canonicalRedirectTarget(apiBase, location, isProduction) {
    if (!isProduction || !apiBase || !location) {
        return null;
    }
    let api;
    try {
        api = new URL(apiBase);
    } catch (e) {
        return null;   // 상대 경로 — 어느 호스트에서든 같은 출처다
    }
    if (api.origin === location.origin || LOCAL_HOSTS.has(location.hostname)) {
        return null;
    }
    return api.origin + location.pathname + location.search + location.hash;
}
