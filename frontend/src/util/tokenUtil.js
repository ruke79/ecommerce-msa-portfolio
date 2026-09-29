/**
 * 클라이언트 스토리지 정리 유틸.
 *
 * 설계 원칙(의도적):
 *  - 액세스 토큰: XSS 토큰 탈취를 막기 위해 **메모리(AuthContext)에만** 보관한다.
 *    → 여기(localStorage)에 토큰을 저장/조회하는 메서드는 두지 않는다(재도입 방지 footgun 제거).
 *  - 리프레시 토큰: 서버가 관리하는 HttpOnly 쿠키.
 *
 * 이 모듈은 로그아웃 시 과거에 남아 있을 수 있는 잔여 클라이언트 스토리지만 정리한다.
 */
const LEGACY_KEYS = ["access_token", "USER", "IS_ADMIN"];

class TokenUtil {
  /** 로그아웃 시 잔여 키만 제거한다(무관한 redux-persist 상태는 건드리지 않음). */
  remove() {
    try {
      LEGACY_KEYS.forEach((key) => localStorage.removeItem(key));
    } catch (_) {
      // Safari private mode 등 storage 접근 불가 환경 방어
    }
  }
}

export default new TokenUtil();
