import { displayName, avatarUrl, initial } from "util/userDisplay";

/**
 * 🐛 회귀 대상: **화면 곳곳에 `undefined` 가 찍혔다.**
 *
 * `util/jwtUser.js` 의 `toUser()` 는 JWT 에서 `{ email, userId, roles }` 만 만든다.
 * 그런데 화면들은 있지도 않은 필드를 파일마다 다른 이름으로 읽었다 —
 * `user.name`(orders), `user.userName`(address), `data.username`(Sidebar),
 * `user.image`(UserInfo). 결과는 "undefined's Orders" 와 깨진 아바타였다.
 */
describe("displayName — 표시할 이름", () => {
  it("이메일밖에 없으면 @ 앞부분을 쓴다 — 'undefined' 가 아니라", () => {
    expect(displayName({ email: "hong@example.com" })).toBe("hong");
  });

  /**
   * 서버 DTO 는 username(실명)과 name(별명)을 둘 다 갖고 있고, 기존 화면들이
   * name · username · userName 을 제각각 썼다. 프로필 엔드포인트가 생겨 어느 쪽이
   * 채워지든 동작해야 한다.
   */
  it("이름이 있으면 그것을 쓴다 — name · username · userName 어느 쪽이든", () => {
    expect(displayName({ name: "홍길동", email: "a@b.com" })).toBe("홍길동");
    expect(displayName({ username: "홍길동", email: "a@b.com" })).toBe("홍길동");
    expect(displayName({ userName: "홍길동", email: "a@b.com" })).toBe("홍길동");
  });

  it("빈 문자열·공백뿐인 이름은 이름이 아니다", () => {
    expect(displayName({ name: "", email: "hong@example.com" })).toBe("hong");
    expect(displayName({ name: "   ", email: "hong@example.com" })).toBe("hong");
  });

  it("아무것도 없어도 undefined 를 그리지 않는다", () => {
    expect(displayName(undefined)).toBe("회원");
    expect(displayName({})).toBe("회원");
    expect(displayName(null)).toBe("회원");
  });

  it("@ 가 없는 이메일이어도 그대로 쓴다", () => {
    expect(displayName({ email: "admin" })).toBe("admin");
  });
});

describe("avatarUrl — 프로필 사진", () => {
  it("없으면 null 이다 — 호출부가 폴백을 그릴 수 있도록", () => {
    // `<img src={undefined}>` 는 React 가 src 를 빼 버려 깨진 이미지가 남는다.
    // 값이 없는 것은 정상 상태이지 오류가 아니다.
    expect(avatarUrl({})).toBeNull();
    expect(avatarUrl({ image: "" })).toBeNull();
    expect(avatarUrl({ image: "   " })).toBeNull();
    expect(avatarUrl(undefined)).toBeNull();
  });

  it("있으면 그대로 돌려준다", () => {
    expect(avatarUrl({ image: "https://cdn/x.png" })).toBe("https://cdn/x.png");
  });
});

describe("initial — 폴백 아바타의 글자", () => {
  it("표시 이름의 첫 글자를 대문자로", () => {
    expect(initial({ email: "hong@example.com" })).toBe("H");
    expect(initial({ name: "홍길동" })).toBe("홍");
    expect(initial({})).toBe("회");
  });
});
