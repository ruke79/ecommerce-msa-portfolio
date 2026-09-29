/**
 * 화면에 사용자를 표시하는 방법.
 *
 * <p>🐛 화면 곳곳에 `undefined` 가 찍히고 있었다. `util/jwtUser.js` 의 `toUser()` 는
 * JWT 페이로드에서 <b>`{ email, userId, roles }` 만</b> 만드는데, 화면들은 있지도 않은
 * 필드를 읽었다 — 그것도 파일마다 다른 이름으로:
 *
 * <pre>
 *   pages/profile/orders.jsx     `${user.name}'s Orders`      → "undefined's Orders"
 *   pages/profile/address.jsx    `${user.userName}'s Address` → "undefined's Address"
 *   components/profile/.../Sidebar.jsx  {data.username}       → 빈 칸
 *   components/order/UserInfo.jsx       {user.name}, src={user.image} → 빈 이름 + 깨진 이미지
 * </pre>
 *
 * <p><b>근본 해법은 프로필을 서버에서 받는 것이다.</b> JWT 는 신원 증명이지 프로필
 * 저장소가 아니다 — 이름을 바꿔도 토큰은 그대로다. 그런데 지금 user-service 에는
 * <b>프로필을 돌려주는 GET 자체가 없다</b>(docs/43 A2). 엔드포인트가 생기면
 * `toUser()` 결과에 name·image 가 채워질 것이고, 그때 이 함수들은 <b>고칠 필요가 없다</b> —
 * 이미 그 필드를 먼저 본다.
 *
 * <p>그때까지는 이메일에서 표시 이름을 만든다. "undefined" 보다는 낫고, 무엇보다
 * 사용자가 자기 계정을 알아볼 수 있다.
 */

import { translate } from "i18n/I18nProvider";

/**
 * 화면에 쓸 이름.
 *
 * <p>여러 이름을 순서대로 보는 이유: 서버 DTO 는 `username`(실명)과 `name`(별명)을 모두
 * 갖고 있고(UserProfileDto), 기존 화면들이 `name` · `username` · `userName` 을 제각각
 * 쓰고 있었다. 어느 쪽이 채워지든 동작하게 둔다.
 */
export function displayName(user) {
  const named = user?.name ?? user?.username ?? user?.userName;
  if (typeof named === "string" && named.trim() !== "") {
    return named.trim();
  }

  const email = user?.email;
  if (typeof email === "string" && email.includes("@")) {
    return email.split("@")[0];
  }
  if (typeof email === "string" && email.trim() !== "") {
    return email.trim();
  }

  return translate("user_display.member");
}

/** 프로필 이미지 주소. 없으면 null — 호출부가 폴백을 그린다. */
export function avatarUrl(user) {
  const image = user?.image;
  return typeof image === "string" && image.trim() !== "" ? image : null;
}

/**
 * 이미지가 없을 때 쓸 이니셜 한 글자.
 *
 * <p>`<img src={undefined}>` 는 React 가 src 속성을 빼 버려 <b>깨진 이미지 아이콘</b>이
 * 남는다. 사용자에게는 "사진을 아직 안 올림"이 아니라 "고장남"으로 보인다.
 */
export function initial(user) {
  const name = displayName(user);
  return name.charAt(0).toUpperCase();
}
