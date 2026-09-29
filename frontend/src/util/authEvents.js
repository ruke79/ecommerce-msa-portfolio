/**
 * 긴 연결(채팅 WebSocket · 알림 SSE)이 "토큰을 갱신해 달라" 고 알리는 약속(user-service docs/68 §9).
 *
 * 서버는 토큰이 만료되거나 로그인이 해제되면 연결을 닫는다 — 채팅은 close code 4401, 알림은 "Reauth" 이벤트.
 * 연결은 스스로 다시 붙지 않는다(옛 토큰으로 붙어 봐야 거절된다). 대신 이 이벤트로 TokenRefresher 에 갱신을 부탁한다:
 * 로그인이 살아 있으면 새 토큰이 나오고, 연결은 토큰이 바뀐 것을 보고 다시 붙는다. 로그인이 끊겼으면 갱신이 실패해 로그아웃된다.
 */
export const AUTH_REFRESH_EVENT = "auth:refresh-needed";

/** 서버가 "갱신하고 다시 붙어라" 로 닫은 WebSocket close code(common SessionRevocation.CLOSE_CODE). */
export const SESSION_CLOSE_CODE = 4401;

/** 서버가 SSE 로 보내는 같은 뜻의 값(common SessionRevocation.SSE_REAUTH). */
export const SSE_REAUTH = "Reauth";

export const requestTokenRefresh = () => {
  window.dispatchEvent(new Event(AUTH_REFRESH_EVENT));
};
