import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";

import ProtectedRoute from "components/ProtectedRoute";
import { STATUS } from "store/AuthContext";

// AuthContext 를 통째로 흉내 내지 않고 useAuthContext 만 갈아끼운다. 이 테스트가 확인하려는
// 것은 가드의 판단 규칙 하나뿐이다.
// [JA] AuthContext を丸ごと真似せず useAuthContext だけを差し替える。このテストが確認したいのは
//      ガードの判断規則一つだけである。
jest.mock("store/AuthContext", () => ({
  STATUS: { IDLE: "idle", PENDING: "pending", SUCCEEDED: "succeeded", FAILED: "failed" },
  useAuthContext: jest.fn(),
}));

// eslint-disable-next-line import/first
import { useAuthContext } from "store/AuthContext";

function renderAt(path, element) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/signin" element={<div>SIGNIN PAGE</div>} />
        <Route path="/" element={<div>HOME PAGE</div>} />
        <Route path="/admin" element={element} />
        <Route path="/cart" element={element} />
      </Routes>
    </MemoryRouter>
  );
}

const SECRET = <div>PROTECTED CONTENT</div>;

describe("ProtectedRoute", () => {
  afterEach(() => jest.clearAllMocks());

  it("waits while the session is being restored", () => {
    // 새로고침 직후에는 리프레시 토큰으로 세션을 복구하는 중이다. 여기서 리다이렉트하면
    // 로그인한 사용자가 매번 로그인 화면으로 튕긴다.
    // [JA] リロード直後はリフレッシュトークンでセッションを復旧している最中である。ここでリダイレクトすると
    //      ログインしたユーザーが毎回ログイン画面に弾かれる。
    useAuthContext.mockReturnValue({ status: STATUS.PENDING, isAuthenticated: false, user: null });

    renderAt("/cart", <ProtectedRoute>{SECRET}</ProtectedRoute>);

    expect(screen.queryByText("PROTECTED CONTENT")).not.toBeInTheDocument();
    expect(screen.queryByText("SIGNIN PAGE")).not.toBeInTheDocument();
  });

  it("sends anonymous visitors to sign-in", () => {
    useAuthContext.mockReturnValue({ status: STATUS.IDLE, isAuthenticated: false, user: null });

    renderAt("/cart", <ProtectedRoute>{SECRET}</ProtectedRoute>);

    expect(screen.getByText("SIGNIN PAGE")).toBeInTheDocument();
  });

  it("lets an authenticated user through", () => {
    useAuthContext.mockReturnValue({
      status: STATUS.SUCCEEDED,
      isAuthenticated: true,
      user: { roles: ["ROLE_USER"] },
    });

    renderAt("/cart", <ProtectedRoute>{SECRET}</ProtectedRoute>);

    expect(screen.getByText("PROTECTED CONTENT")).toBeInTheDocument();
  });

  describe("adminOnly", () => {
    it("🐛 blocks a signed-in non-admin", () => {
      // 예전에는 /admin 라우트에 가드가 아예 없었고, ProtectedRoute 에도 역할 검사가
      // 없어서 로그인만 하면 누구나 관리자 화면을 볼 수 있었다.
      // [JA] 以前は /admin ルートにガードがまったくなく、ProtectedRoute にも役割の検査が
      //      なかったので、ログインさえすれば誰でも管理画面を見られた。
      useAuthContext.mockReturnValue({
        status: STATUS.SUCCEEDED,
        isAuthenticated: true,
        user: { roles: ["ROLE_USER"] },
      });

      renderAt("/admin", <ProtectedRoute adminOnly>{SECRET}</ProtectedRoute>);

      expect(screen.queryByText("PROTECTED CONTENT")).not.toBeInTheDocument();
      expect(screen.getByText("HOME PAGE")).toBeInTheDocument();
    });

    it("🐛 admits a real admin — the role claim must parse", () => {
      // 이 케이스가 두 번째 버그를 잡는다. 프론트가 `roles`(복수)를 읽는 바람에 역할이
      // 항상 빈 배열이었고, 가드를 붙이는 순간 진짜 관리자까지 막혔다.
      // [JA] このケースが二つ目のバグを捕まえる。フロントが `roles`(複数形)を読んでいたせいで役割が
      //      常に空の配列で、ガードを付けた瞬間に本物の管理者まで塞がれた。
      useAuthContext.mockReturnValue({
        status: STATUS.SUCCEEDED,
        isAuthenticated: true,
        user: { roles: ["ROLE_ADMIN"] },
      });

      renderAt("/admin", <ProtectedRoute adminOnly>{SECRET}</ProtectedRoute>);

      expect(screen.getByText("PROTECTED CONTENT")).toBeInTheDocument();
    });

    it("accepts the unprefixed ADMIN spelling too", () => {
      useAuthContext.mockReturnValue({
        status: STATUS.SUCCEEDED,
        isAuthenticated: true,
        user: { roles: ["ADMIN"] },
      });

      renderAt("/admin", <ProtectedRoute adminOnly>{SECRET}</ProtectedRoute>);

      expect(screen.getByText("PROTECTED CONTENT")).toBeInTheDocument();
    });

    it("still requires authentication before checking the role", () => {
      useAuthContext.mockReturnValue({ status: STATUS.IDLE, isAuthenticated: false, user: null });

      renderAt("/admin", <ProtectedRoute adminOnly>{SECRET}</ProtectedRoute>);

      expect(screen.getByText("SIGNIN PAGE")).toBeInTheDocument();
    });

    it("does not crash when the user object has no roles", () => {
      useAuthContext.mockReturnValue({
        status: STATUS.SUCCEEDED,
        isAuthenticated: true,
        user: {},
      });

      renderAt("/admin", <ProtectedRoute adminOnly>{SECRET}</ProtectedRoute>);

      expect(screen.getByText("HOME PAGE")).toBeInTheDocument();
    });
  });

  // ── 상담 데스크(docs/58) ────────────────────────────────────────────
  // [JA] ── 相談デスク(docs/58) ──
  describe("supportOnly", () => {
    const at = (roles) => {
      useAuthContext.mockReturnValue({ status: STATUS.SUCCEEDED, isAuthenticated: true, user: { roles } });
      renderAt("/admin", <ProtectedRoute supportOnly>{SECRET}</ProtectedRoute>);
    };

    it("상담원(ROLE_SUPPORT)은 들어간다", () => {
      at(["ROLE_SUPPORT"]);
      expect(screen.getByText("PROTECTED CONTENT")).toBeInTheDocument();
    });

    it("관리자도 들어간다", () => {
      at(["ROLE_ADMIN"]);
      expect(screen.getByText("PROTECTED CONTENT")).toBeInTheDocument();
    });

    it("일반 회원은 홈으로", () => {
      at(["ROLE_USER"]);
      expect(screen.queryByText("PROTECTED CONTENT")).not.toBeInTheDocument();
      expect(screen.getByText("HOME PAGE")).toBeInTheDocument();
    });

    it("🔒 상담원이라도 관리자 화면(adminOnly)은 못 들어간다", () => {
      useAuthContext.mockReturnValue({ status: STATUS.SUCCEEDED, isAuthenticated: true, user: { roles: ["ROLE_SUPPORT"] } });
      renderAt("/admin", <ProtectedRoute adminOnly>{SECRET}</ProtectedRoute>);
      expect(screen.queryByText("PROTECTED CONTENT")).not.toBeInTheDocument();
    });
  });
});
