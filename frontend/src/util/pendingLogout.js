/**
 * 서버에 반영되지 못한 로그아웃 — 다음 접속 때 마무리한다(docs/68 §13).
 *
 * <p>🐛 예전: 로그아웃 요청이 실패하면(네트워크 · 서버 오류) "로그아웃하지 못했습니다" 만 띄우고 <b>화면은 로그인 상태</b>였다. 공용 PC 의
 * 사용자는 로그아웃된 줄 알고 자리를 뜬다.
 *
 * <p>지금: 화면은 <b>바로</b> 로그아웃한다(다른 탭도). 다만 리프레시 쿠키는 HttpOnly 라 화면이 지울 수 없어, 그대로 두면 다음 접속의 자동
 * 복구가 다시 로그인시킨다. 그래서 이 표시를 남기고, 다음 접속 때는 복구 대신 <b>서버 로그아웃을 먼저 마무리</b>한다. 마무리하기 전까지는
 * 로그아웃 상태로 둔다.
 *
 * <p>새로 로그인하기 시작하면(로그인 화면 · 소셜 로그인 버튼) 표시를 지운다 — 남겨 두면 다음 접속이 새 로그인을 끊는다.
 *
 * <p>값은 "1" 뿐이다 — 토큰 · 사용자 정보는 남기지 않는다.
 */
// [JA] サーバーに反映できなかったログアウト — 次回アクセス時に仕上げる。画面はすぐにログアウトし、リフレッシュ Cookie は HttpOnly で
//      画面から消せないため印を残し、次回は自動復旧の代わりにサーバーのログアウトを先に完了させる。新しくログインし始めたら印を消す。
const KEY = "ecommerce-pending-logout";

export function markPendingLogout() {
  try {
    window.localStorage.setItem(KEY, "1");
  } catch {
    // 저장소가 막힌 환경 — 이번 화면의 로그아웃만 한다
  }
}

export function clearPendingLogout() {
  try {
    window.localStorage.removeItem(KEY);
  } catch {
    // 무시
  }
}

export function hasPendingLogout() {
  try {
    return window.localStorage.getItem(KEY) === "1";
  } catch {
    return false;
  }
}
