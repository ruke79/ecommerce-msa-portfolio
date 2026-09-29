import { toUser, normalizeRoles, isAdmin, uidOf, sameUserId } from "util/jwtUser";

/**
 * JWT 클레임 → 화면용 사용자 객체 변환.
 *
 * 🐛 회귀 대상: 프론트엔드 세 곳이 `decodedToken.roles`(복수)를 읽었는데 백엔드가 넣는
 * 클레임은 `role`(단수)다. 그래서 user.roles 는 **항상 빈 배열**이었고, 관리자 전용 라우트
 * 가드를 추가하는 순간 진짜 관리자까지 차단됐다.
 */
describe("normalizeRoles", () => {
  it("reads the singular `role` claim the backend actually emits", () => {
    // 이 한 줄이 버그의 핵심이다. 고치기 전에는 [] 였다.
    expect(normalizeRoles({ role: "ROLE_ADMIN" })).toEqual(["ROLE_ADMIN"]);
  });

  it("still reads a plural `roles` claim if a deployment sends one", () => {
    expect(normalizeRoles({ roles: "ROLE_USER,ROLE_ADMIN" }))
      .toEqual(["ROLE_USER", "ROLE_ADMIN"]);
  });

  it("accepts an array as well as a comma string", () => {
    expect(normalizeRoles({ role: ["ROLE_USER", "ROLE_ADMIN"] }))
      .toEqual(["ROLE_USER", "ROLE_ADMIN"]);
  });

  it("trims whitespace and drops empty entries", () => {
    expect(normalizeRoles({ role: " ROLE_USER , , ROLE_ADMIN " }))
      .toEqual(["ROLE_USER", "ROLE_ADMIN"]);
  });

  it("returns an empty array rather than throwing on a missing claim", () => {
    expect(normalizeRoles({})).toEqual([]);
    expect(normalizeRoles(null)).toEqual([]);
    expect(normalizeRoles(undefined)).toEqual([]);
  });
});

describe("isAdmin", () => {
  // 코드베이스에 ADMIN 과 ROLE_ADMIN 이 섞여 있다. 한쪽만 인정하면 관리자가 조용히
  // 자기 화면에서 쫓겨나고, 화면에는 이유가 드러나지 않는다.
  it.each([
    ["ROLE_ADMIN"],
    ["ADMIN"],
    ["role_admin"],
    [" Admin "],
  ])("treats %s as admin", (role) => {
    expect(isAdmin({ roles: [role] })).toBe(true);
  });

  it("rejects ordinary users", () => {
    expect(isAdmin({ roles: ["ROLE_USER"] })).toBe(false);
  });

  it("rejects a user with no roles instead of throwing", () => {
    expect(isAdmin({})).toBe(false);
    expect(isAdmin(null)).toBe(false);
    // roles 가 배열이 아닌 형태로 와도 터지지 않아야 한다.
    expect(isAdmin({ roles: "ROLE_ADMIN" })).toBe(false);
  });
});

const b64url = (obj) =>
  Buffer.from(typeof obj === "string" ? obj : JSON.stringify(obj)).toString("base64")
    .replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const tokenWith = (payloadText) => `${b64url({ alg: "HS256" })}.${b64url(payloadText)}.sig`;

describe("toUser", () => {
  it("maps subject, uid and role from a decoded token", () => {
    expect(toUser({ sub: "a@example.com", uid: 42, role: "ROLE_ADMIN" })).toEqual({
      email: "a@example.com",
      userId: "42",
      roles: ["ROLE_ADMIN"],
    });
  });

  /** 🐛 19자리 TSID 가 JS Number 를 지나면 끝자리가 뭉개진다 — 채팅의 "내 메시지" 판별이 틀렸다. */
  it("🐛 reads a 19-digit uid exactly from the raw token (JSON.parse would round it)", () => {
    const raw = tokenWith('{"sub":"a@example.com","uid":1234567890123456789,"role":"ROLE_USER"}');
    const decoded = JSON.parse('{"sub":"a@example.com","uid":1234567890123456789,"role":"ROLE_USER"}');
    expect(String(decoded.uid)).not.toBe("1234567890123456789"); // 이것이 버그였다
    expect(toUser(decoded, raw).userId).toBe("1234567890123456789");
  });

  it("uidOf tolerates garbage and missing claims", () => {
    expect(uidOf(undefined)).toBeNull();
    expect(uidOf("not-a-jwt")).toBeNull();
    expect(uidOf(tokenWith('{"sub":"a@example.com"}'))).toBeNull();
    expect(uidOf(tokenWith('{"uid":"77"}'))).toBe("77");
  });

  it("sameUserId compares as text and never matches missing ids", () => {
    expect(sameUserId("1234567890123456789", "1234567890123456789")).toBe(true);
    expect(sameUserId(42, "42")).toBe(true);
    expect(sameUserId(null, null)).toBe(false);
    expect(sameUserId(undefined, "1")).toBe(false);
  });

  it("leaves userId null when the token predates the uid claim", () => {
    // uid 클레임은 이번 보안 수정에서 추가됐다. 그 전에 발급된 토큰에는 없다.
    expect(toUser({ sub: "a@example.com", role: "ROLE_USER" }).userId).toBeNull();
  });
});
