/**
 * API 의 기준 주소.
 *
 * <p><b>왜 상수 하나로 묶나.</b> 예전에는 네 곳이 각자
 * {@code `${process.env.REACT_APP_API_URL}/...`} 를 그대로 썼다. CRA 는 이 값을
 * <b>빌드 시점에</b> 문자열로 박아 넣으므로, 빌드할 때 변수를 안 주면 번들에 남는 것은
 * 빈 문자열이 아니라 <b>리터럴 "undefined"</b> 다. 템플릿 문자열이 undefined 를 그렇게
 * 이어 붙이기 때문이다. 그러면 모든 요청이 {@code /undefined/catalog-service/...} 로
 * 나가고 사이트 전체가 죽는다 — 빌드는 경고 한 줄만 남기고 성공한다.
 *
 * <p>기본값이 빈 문자열인 이유: 이 배포에서 프론트엔드와 API 는 <b>같은 출처</b>다.
 * Cloudflare Tunnel 이 한 호스트명에서 경로로 갈라 보낸다
 * ({@code ^/(user|catalog|...)-service} → 게이트웨이, 나머지 → 프론트엔드).
 * 그래서 상대 경로가 곧 정답이고, 변수를 깜빡해도 동작한다.
 *
 * <p>그래도 빌드할 때 값을 주는 편이 낫다 — 로컬 개발은 프론트(3000)와 게이트웨이(8000)의
 * 출처가 달라서 상대 경로로는 닿지 않는다. ops/19 의 경고는 그래서 남겨 둔다.
 */
export const API_BASE = process.env.REACT_APP_API_URL || "";
