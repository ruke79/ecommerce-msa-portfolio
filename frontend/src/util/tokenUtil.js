/**
 * 클라이언트 스토리지 정리 유틸.
 *
 * 설계 원칙(의도적):
 *  - 액세스 토큰: XSS 토큰 탈취를 막기 위해 **메모리(AuthContext)에만** 보관한다.
 *    → 여기(localStorage)에 토큰을 저장/조회하는 메서드는 두지 않는다(재도입 방지 footgun 제거).
 *  - 리프레시 토큰: 서버가 관리하는 HttpOnly 쿠키.
 *
 * 이 모듈은 로그아웃 시 과거에 남아 있을 수 있는 잔여 클라이언트 스토리지만 정리한다.
 *
 * <p>[JA] クライアントストレージ整理ユーティリティ。設計原則(意図的): アクセストークンは XSS によるトークン窃取を防ぐため<b>メモリ(AuthContext)にのみ</b>保管する
 * → ここ(localStorage)にトークンを保存 · 取得するメソッドは置かない(再導入防止の footgun 除去)。リフレッシュトークンはサーバーが管理する HttpOnly Cookie。
 * このモジュールはログアウト時に過去に残っているかもしれないクライアントストレージだけを整理する。
 */
const LEGACY_KEYS = ["access_token", "USER", "IS_ADMIN"];

class TokenUtil {
  /** 로그아웃 시 잔여 키만 제거한다(무관한 redux-persist 상태는 건드리지 않음). */
  // [JA] ログアウト時に残ったキーだけを削除する(無関係な redux-persist の状態には触れない)。
  remove() {
    try {
      LEGACY_KEYS.forEach((key) => localStorage.removeItem(key));
    } catch (_) {
      // Safari private mode 등 storage 접근 불가 환경 방어
      // [JA] Safari プライベートモードなどストレージにアクセスできない環境の防御
    }
  }
}

export default new TokenUtil();
