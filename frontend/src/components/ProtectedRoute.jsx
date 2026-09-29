import { Navigate, useLocation } from "react-router-dom";
import { useAuthContext, STATUS } from "../store/AuthContext";
import DotLoaderSpinner from "./loader/Loading";

/**
 * 로그인(그리고 선택적으로 관리자 권한)이 필요한 화면을 감싼다.
 *
 * <p><b>이건 UI 가드일 뿐 보안 경계가 아니다.</b> 진짜 권한 검사는 게이트웨이와 각 서비스가
 * 한다 — 브라우저에서 도는 코드는 사용자가 얼마든지 바꿀 수 있기 때문이다. 그래도 필요한
 * 이유는, 권한 없는 사용자에게 관리자 화면의 구조·메뉴·엔드포인트를 노출하지 않기 위해서다.
 *
 * <p>예전에는 `/admin` 라우트가 이 컴포넌트로 감싸여 있지도 않았다. 로그인하지 않은
 * 사용자도 주소창에 /admin/product 를 치면 관리자 화면이 그려졌다(그 안의 API 호출만 401 로
 * 실패했다). 게다가 역할 검사가 아예 없어서, 일반 회원으로 로그인만 하면 관리자 화면을
 * 볼 수 있었다.
 */
const ProtectedRoute = ({ children, adminOnly = false, supportOnly = false }) => {
  const { status, isAuthenticated, user } = useAuthContext();
  const location = useLocation();

  // 새로고침 직후에는 리프레시 토큰으로 세션을 복구하는 중이다. 이때 리다이렉트해 버리면
  // 로그인한 사용자가 매번 로그인 화면으로 튕긴다.
  if (status === STATUS.PENDING) {
    return <DotLoaderSpinner loading={true} />;
  }

  if (!isAuthenticated) {
    // 어디로 가려다 막혔는지 넘긴다. 로그인 화면이 성공 후 그리로 돌려보낸다.
    // 예전에는 로그인하면 **무조건 홈**이었다 — /checkout 에서 세션이 끊겨 로그인하고
    // 돌아오면 장바구니부터 다시 시작해야 했다.
    return <Navigate to="/signin" replace state={{ from: location }} />;
  }

  if (adminOnly && !hasAdminRole(user)) {
    return <Navigate to="/" replace />;
  }

  // 상담 데스크(docs/58) — 상담원과 관리자. 서버도 같은 규칙을 강제한다(SupportService.requireAgent).
  if (supportOnly && !hasSupportRole(user)) {
    return <Navigate to="/" replace />;
  }

  return children;
};

/**
 * 역할 표기가 배포마다 다르다 — JWT 의 roles 클레임이 "ADMIN" 인 곳도 있고 Spring Security
 * 관례대로 "ROLE_ADMIN" 인 곳도 있다. 둘 다 받아들인다. 한쪽만 보면 관리자가 조용히 자기
 * 화면에서 쫓겨나고, 원인이 화면에 전혀 드러나지 않는다.
 */
export function hasAdminRole(user) {
  const roles = user?.roles;
  if (!Array.isArray(roles)) return false;
  return roles.some((role) => {
    const normalized = String(role).trim().toUpperCase();
    return normalized === "ADMIN" || normalized === "ROLE_ADMIN";
  });
}

/** 고객지원팀 상담원(ROLE_SUPPORT) 또는 관리자 — 상담 데스크를 쓸 수 있다. */
export function hasSupportRole(user) {
  if (hasAdminRole(user)) return true;
  const roles = user?.roles;
  if (!Array.isArray(roles)) return false;
  return roles.some((role) => {
    const normalized = String(role).trim().toUpperCase();
    return normalized === "SUPPORT" || normalized === "ROLE_SUPPORT";
  });
}

export default ProtectedRoute;
