/**
 * JWT 페이로드에서 화면이 쓰는 사용자 객체를 만든다.
 *
 * <p><b>왜 헬퍼로 뺐나.</b> 같은 변환이 세 군데(SignInPage, TokenRefresher 의 초기 복구와
 * 401 재발급)에 복사돼 있었고, 세 곳 모두 <b>똑같이 틀려</b> 있었다:
 *
 * <pre>
 *   roles: decodedToken.roles ? decodedToken.roles.split(",") : []
 * </pre>
 *
 * 백엔드가 넣는 클레임은 {@code roles}(복수)가 아니라 <b>{@code role}(단수)</b>이고 값은
 * {@code "ROLE_USER"} / {@code "ROLE_ADMIN"} 이다(JwtUtils.java). 따라서
 * {@code decodedToken.roles} 는 언제나 undefined 였고 <b>user.roles 는 항상 빈 배열</b>이었다.
 *
 * <p>지금까지는 역할을 실제로 검사하는 코드가 없어서 아무도 눈치채지 못했다. 그런데
 * 관리자 전용 라우트 가드를 추가하는 순간 이 빈 배열 때문에 <b>진짜 관리자까지 관리자
 * 화면에서 쫓겨난다.</b> 가드를 넣기 전에 반드시 같이 고쳐야 하는 부분이다.
 *
 * <p>단수/복수, 배열/쉼표 문자열을 모두 받아들인다. 백엔드가 나중에 표기를 바꿔도 화면이
 * 조용히 권한을 잃지 않도록 하기 위해서다.
 */
export function toUser(decodedToken, rawToken) {
  return {
    email: decodedToken?.sub,
    userId: uidOf(rawToken) ?? (decodedToken?.uid != null ? String(decodedToken.uid) : null),
    roles: normalizeRoles(decodedToken),
  };
}

/**
 * 토큰의 {@code uid} 클레임을 <b>문자열 그대로</b> 읽는다.
 *
 * <p>🐛 uid 는 19자리 TSID 다. {@code jwtDecode} 는 페이로드를 {@code JSON.parse} 하므로 숫자 클레임이 JS
 * {@code Number}(2^53, 약 16자리)가 되어 <b>끝자리가 뭉개진다</b> — 1234567890123456789 가 1234567890123456800 이
 * 된다. 서버가 보내는 id(채팅 발신자 등)는 문자열이라 정확하니, 둘을 비교하면 "내 메시지"가 조용히 남의 메시지가
 * 된다(docs/48 §6-⑥). 그래서 파싱하기 전의 JSON 텍스트에서 숫자를 글자로 꺼낸다. 서명은 여기서 보지 않는다 —
 * 화면 표시용이고, 신원 판정은 서버가 검증된 토큰으로 한다.
 *
 * @returns 숫자 문자열, 없거나 읽을 수 없으면 null
 */
export function uidOf(rawToken) {
  if (typeof rawToken !== "string") return null;
  const part = rawToken.split(".")[1];
  if (!part) return null;
  try {
    const b64 = part.replace(/-/g, "+").replace(/_/g, "/");
    const json = decodeURIComponent(
      Array.from(atob(b64.padEnd(b64.length + ((4 - (b64.length % 4)) % 4), "=")))
        .map((c) => "%" + c.charCodeAt(0).toString(16).padStart(2, "0"))
        .join("")
    );
    const m = json.match(/"uid"\s*:\s*"?(-?\d+)"?/);
    return m ? m[1] : null;
  } catch (e) {
    return null;
  }
}

/** 두 사용자 id 가 같은가 — 둘 다 있을 때만. 숫자·문자열이 섞여 와도 글자로 비교한다(정밀도 손실 없이). */
export function sameUserId(a, b) {
  return a != null && b != null && String(a) === String(b);
}

export function normalizeRoles(decodedToken) {
  const raw = decodedToken?.role ?? decodedToken?.roles;
  if (!raw) return [];

  const list = Array.isArray(raw) ? raw : String(raw).split(",");
  return list.map((r) => String(r).trim()).filter(Boolean);
}

/**
 * 관리자인가.
 *
 * <p>{@code ADMIN} 과 {@code ROLE_ADMIN} 을 모두 받는다. Spring Security 관례는 접두사가
 * 붙은 쪽이지만 코드베이스 안에 둘 다 쓰이는 곳이 있다. 한쪽만 보면 관리자가 조용히 자기
 * 화면에서 쫓겨나고, 화면에는 이유가 전혀 드러나지 않는다.
 */
export function isAdmin(user) {
  return normalizeRolesFromUser(user).some(
    (role) => role === "ADMIN" || role === "ROLE_ADMIN"
  );
}

function normalizeRolesFromUser(user) {
  const roles = user?.roles;
  if (!Array.isArray(roles)) return [];
  return roles.map((r) => String(r).trim().toUpperCase());
}
