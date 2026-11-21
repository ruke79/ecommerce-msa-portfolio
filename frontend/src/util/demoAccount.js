/**
 * 공개 데모 계정인가 — 이메일 도메인으로(docs/100: user-service 가 demo<N>@demo.invalid 로 만든다). 관리자 화면의 '데모' 표시용 —
 * 권한 판단에는 쓰지 않는다(그것은 게이트웨이가 토큰의 demo 클레임으로 한다).
 * [JA] 公開デモアカウントか — メールドメインで。管理画面の「デモ」表示用 — 権限判断には使わない。
 */
export const DEMO_EMAIL_DOMAIN = "demo.invalid";

export function isDemoEmail(email) {
  return typeof email === "string" && email.toLowerCase().endsWith("@" + DEMO_EMAIL_DOMAIN);
}
