/**
 * 탭 사이 로그인 상태 동기화.
 *
 * <p><b>로그인 상태는 탭마다가 아니라 브라우저 하나에 하나다.</b> 리프레시 토큰은 HttpOnly 쿠키라 모든 탭이 같이 쓴다. 그런데
 * 액세스 토큰과 "로그인됨" 표시는 탭마다 메모리(AuthContext)에 따로 있어서, 예전에는 탭끼리 어긋났다:
 *
 * <ul>
 *   <li>한 탭에서 로그아웃해도 다른 탭은 액세스 토큰이 끝날 때까지 로그인된 화면이었다 — 그동안 주문 · 결제 버튼도 눌렸다.</li>
 *   <li>한 탭에서 로그인해도 열려 있던 다른 탭은 새로고침할 때까지 로그인 화면이었다.</li>
 *   <li>한 탭은 "세션 만료", 다른 탭은 멀쩡한 상태가 생겼다 — 같은 쿠키를 쓰는데도.</li>
 * </ul>
 *
 * <p>그래서 상태가 <b>바뀐 사실</b>만 다른 탭에 알린다. 받은 탭은 스스로 판단한다:
 *
 * <ul>
 *   <li>{@code signed-out} — 로그인돼 있으면 로그아웃(다시 알리지 않는다).</li>
 *   <li>{@code signed-in} — 로그아웃 상태면 공유 쿠키로 <b>자기 액세스 토큰을 직접 받는다</b>. 다른 사용자로 바뀌었으면 새로고침 —
 *       앞 사용자의 화면 상태(장바구니 · 폼)를 남기지 않는다. 같은 사용자로 이미 로그인돼 있으면 아무것도 하지 않는다 —
 *       토큰 갱신마다 오는 알림이 갱신 연쇄를 일으키지 않게.</li>
 * </ul>
 *
 * <p>🔒 <b>토큰은 보내지 않는다.</b> 보내는 것은 종류와 사용자 id 뿐이다. localStorage 대체 경로에서도 값을 쓰자마자 지운다 —
 * 토큰을 저장소에 두면 XSS 한 번에 세션이 넘어간다(액세스 토큰을 메모리에만 두는 이유와 같다).
 *
 * <p>BroadcastChannel 이 없는 브라우저는 localStorage 의 storage 이벤트로 대신한다(다른 탭에만 발생한다).
 */
// [JA] タブ間のログイン状態の同期。リフレッシュトークンは HttpOnly Cookie で全タブ共有だが、アクセストークンとログイン表示はタブごとの
//      メモリにあるため、タブ同士がずれていた。状態が変わった事実(signed-in / signed-out)だけを他のタブに知らせ、受けたタブが自分で判断する。
//      トークンは送らない(種類とユーザー id のみ)。BroadcastChannel がなければ localStorage の storage イベントで代える。

export const AUTH_SYNC_CHANNEL = "ecommerce-auth-sync";
const STORAGE_KEY = "ecommerce-auth-sync";

export const SIGNED_IN = "signed-in";
export const SIGNED_OUT = "signed-out";

// 이 탭의 표식 — 같은 탭 안의 다른 채널 인스턴스가 받는 자기 메시지를 거른다(BroadcastChannel 은 같은 탭의 다른 인스턴스에도 전달한다).
const TAB_ID = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;

let sender = null;

function channelSupported() {
  return typeof window !== "undefined" && typeof window.BroadcastChannel === "function";
}

function envelope(type, uid) {
  // 허용한 필드만 — 호출부가 무엇을 넘겨도 토큰이 섞여 나가지 않는다.
  return { type, uid: uid == null ? null : String(uid), from: TAB_ID, at: Date.now() };
}

/**
 * 다른 탭에 알린다.
 *
 * @param {"signed-in"|"signed-out"} type
 * @param {string|null} [uid] 사용자 id(문자열). 토큰은 받지 않는다.
 */
export function publishAuth(type, uid = null) {
  if (type !== SIGNED_IN && type !== SIGNED_OUT) return;
  const message = envelope(type, uid);
  try {
    if (channelSupported()) {
      if (!sender) sender = new window.BroadcastChannel(AUTH_SYNC_CHANNEL);
      sender.postMessage(message);
      return;
    }
    // 대체 경로: 값을 바꿔야 이벤트가 나므로 at 이 들어간 새 값을 쓰고 바로 지운다.
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(message));
    window.localStorage.removeItem(STORAGE_KEY);
  } catch {
    // 저장소가 막힌 환경(사생활 보호 모드 등) — 동기화만 빠지고 앱은 그대로 동작한다.
  }
}

function accept(raw, handler) {
  if (!raw || typeof raw !== "object") return;
  if (raw.from === TAB_ID) return;
  if (raw.type !== SIGNED_IN && raw.type !== SIGNED_OUT) return;
  handler({ type: raw.type, uid: raw.uid == null ? null : String(raw.uid) });
}

/**
 * 다른 탭의 알림을 받는다.
 *
 * @param {(msg: {type: string, uid: string|null}) => void} handler
 * @returns {() => void} 구독 해제
 */
export function subscribeAuth(handler) {
  if (typeof window === "undefined") return () => {};
  if (channelSupported()) {
    const channel = new window.BroadcastChannel(AUTH_SYNC_CHANNEL);
    channel.onmessage = (event) => accept(event.data, handler);
    return () => channel.close();
  }
  const onStorage = (event) => {
    if (event.key !== STORAGE_KEY || !event.newValue) return;
    try {
      accept(JSON.parse(event.newValue), handler);
    } catch {
      // 깨진 값은 무시한다
    }
  };
  window.addEventListener("storage", onStorage);
  return () => window.removeEventListener("storage", onStorage);
}

/** 테스트용 — 보내는 채널을 닫고 다시 만들게 한다. */
export function resetAuthSyncForTest() {
  if (sender) {
    try {
      sender.close();
    } catch {
      // 무시
    }
  }
  sender = null;
}
