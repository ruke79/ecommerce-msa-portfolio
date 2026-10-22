// 손님 IP 흉내 · 부하 VM 나누기 — lib.js 와 ops/53 이 쓴다. k6 모듈을 쓰지 않는 순수 함수라 LoadK6ScriptTest 가 node 로 돌려 본다(docs/83 §2.6).
// [JA] 客の IP の模倣 · 負荷 VM の分割 — k6 モジュールを使わない純粋関数で、LoadK6ScriptTest が node で動かす。
//
// 게이트웨이의 유입 제한 키는 로그인 사용자 → CF-Connecting-IP → 소켓 주소다(CustomUserKeyResolver). 운영은 Cloudflare 엣지가
// CF-Connecting-IP 를 실제 손님 IP 로 덮어쓰고, 실습 클러스터에는 엣지가 없어 모든 요청이 Traefik 파드 IP 하나로 보인다 — 부하 VM 을
// 늘려도 게이트웨이에게는 손님 하나다. CLIENT_IPS=N 이면 VU 마다 시험용 대역(198.18.0.0/15, RFC 2544)의 주소를 그 헤더에 싣는다:
// 실습에서 운영과 같이 손님마다 버킷이 생긴다. 운영에서는 엣지가 덮어써 아무 일도 없다(그리고 ops/53 은 운영 주소를 거절한다).

export const CLIENT_IP_MAX = 65536;

/** VU 번호(1부터) → 손님 IP. count 개를 돌려 쓰고, offset 은 다른 부하 VM 과 겹치지 않게(SPLIT). */
export function clientIp(vuId, count, offset = 0) {
  if (!(count > 0)) {
    return null;
  }
  const n = offset + ((Math.max(1, vuId) - 1) % count);
  return `198.18.${Math.floor(n / 256) % 256}.${n % 256}`;
}

/** "i/n" → { part, of } — 1 ≤ i ≤ n ≤ 16. 틀리면 null. */
export function splitPart(spec) {
  const m = /^([1-9][0-9]?)\/([1-9][0-9]?)$/.exec(String(spec || ''));
  if (!m) {
    return null;
  }
  const part = Number(m[1]);
  const of = Number(m[2]);
  return part <= of && of <= 16 ? { part, of } : null;
}
