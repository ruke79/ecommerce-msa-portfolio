/**
 * 창(탭)마다의 결제 시도 키 — 서버가 주문 하나에 살아 있는 결제창을 하나로 지킨다(order V27).
 *
 * <p>결제 시작(재고 · 쿠폰을 잡는 호출)에 실어 보낸다. 같은 탭에서 다시 누르거나 새로고침하면 같은 키라 다시 열 수 있고, 다른 탭은
 * 다른 키라 그 탭의 결제창이 살아 있는 동안(서버 기본 10분) 거절된다 — 🐛 예전에는 두 탭에서 같은 주문을 둘 다 결제할 수 있었다.
 *
 * <p>sessionStorage 를 못 쓰면(사생활 보호 모드 등) 이 페이지가 살아 있는 동안의 메모리로 대신한다.
 */
const memory = new Map();

function newKey() {
  return typeof crypto !== "undefined" && crypto.randomUUID
    ? crypto.randomUUID()
    : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
}

export function paymentAttemptKey(trackingId) {
  const name = `payment-attempt:${trackingId}`;
  try {
    const saved = window.sessionStorage.getItem(name);
    if (saved) return saved;
  } catch (e) {
    // 저장소를 못 쓴다 — 아래 메모리로.
  }
  if (memory.has(name)) return memory.get(name);
  const key = newKey();
  memory.set(name, key);
  try {
    window.sessionStorage.setItem(name, key);
  } catch (e) {
    // 메모리에는 남았다.
  }
  return key;
}
