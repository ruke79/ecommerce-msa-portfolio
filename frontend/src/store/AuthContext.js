import React, {
  createContext,
  useContext,
  useState,
  useCallback,
  useMemo,
} from "react";
import { useEffect } from "react";
import api, { getRequest } from "util/api";
import toast from "react-hot-toast";
import { useErrorBoundary } from "react-error-boundary";
import { useNavigate } from "react-router-dom";
import { useSelector } from "react-redux";
import { publishAuth, SIGNED_IN, SIGNED_OUT } from "util/authSync";

export const STATUS = Object.freeze({
  IDLE: "idle",
  PENDING: "pending",
  SUCCEEDED: "succeeded",
  FAILED: "failed",
});

const initialState = {
  multiLogin: false,
  user: {},
  token: null,
  expiresAt: null,
  isAuthenticated: false,
  status: STATUS.PENDING,   // ProtectedRoute state sync
};

const AuthContext = createContext({
  ...initialState,
  login: (user = {}, token = "", expiresAt = "") => {},
  logout: () => {},
  updateUser: () => {},
  updateToken: () => {},
  setAuthenticationStatus: () => {},
});

const authReducer = (state, action) => {
  switch (action.type) {
    case "login": {
      return {
        // multiLogin: action.payload.multiLogin,
        user: action.payload.user,
        token: action.payload.token,
        expiresAt: action.payload.expiresAt,
        isAuthenticated: true,
        status: STATUS.SUCCEEDED,
      };
    }
    case "logout": {
      return {
        ...initialState,
        status: STATUS.IDLE,
      };
    }
    case "updateUser": {
      return {
        ...state,
        user: action.payload.user,
      };
    }
    case "updateToken": {
      return {
        ...state,
        token: action.payload.token,
      };
    }

    case "status": {
      return {
        ...state,
        status: action.payload.status,
      };
    }
    default: {
      throw new Error(`Unhandled action type: ${action.type}`);
    }
  }
};

export const ContextProvider = ({ children }) => {
  // 인증 상태는 전적으로 메모리(reducer)에 보관한다. (localStorage 조회 제거)
  const [state, dispatch] = React.useReducer(authReducer, initialState);

  // 탭 간 동기화(util/authSync) — 로그아웃은 "로그인돼 있던 탭이 로그아웃할 때" 만 알린다. 처음 열린 탭의 복구 실패(쿠키 없음)까지
  // 알리면, 다른 탭의 멀쩡한 로그인을 끊는다.
  // [JA] タブ間同期 — ログアウトは「ログインしていたタブがログアウトするとき」だけ知らせる。
  const authenticatedRef = React.useRef(false);
  authenticatedRef.current = state.isAuthenticated;

  /**
   * @param options.broadcast false 면 다른 탭에 알리지 않는다 — 다른 탭의 알림을 받아 따라 할 때(알림이 되돌아오지 않게)
   */
  const login = React.useCallback((user, token, expiresAt, { broadcast = true } = {}) => {
    dispatch({
      type: "login",
      payload: {
        user,
        token,
        expiresAt,
      },
    });
    // 갱신마다 알린다 — 받은 탭 중 로그아웃 상태인 탭만 움직인다(같은 사용자로 로그인된 탭은 무시한다).
    if (broadcast) publishAuth(SIGNED_IN, user?.userId);
  }, []);

  const logout = React.useCallback(({ broadcast = true } = {}) => {
    const wasAuthenticated = authenticatedRef.current;
    dispatch({
      type: "logout",
    });
    if (broadcast === true && wasAuthenticated) publishAuth(SIGNED_OUT);
  }, []);

  const updateUser = React.useCallback((user) => {
    dispatch({
      type: "updateUser",
      payload: {
        user,
      },
    });
  }, []);
  const updateToken = React.useCallback((token) => {
    dispatch({
      type: "updateToken",
      payload: {
        token,
      },
    });
  }, []);
  const setAuthenticationStatus = React.useCallback((status) => {
    dispatch({
      type: "status",
      payload: {
        status,
      },
    });
  }, []);

  const value = React.useMemo(
    () => ({
      ...state,
      login,
      logout,
      updateUser,
      setAuthenticationStatus,
      updateToken,
    }),
    [state, setAuthenticationStatus, login, logout, updateUser, updateToken]
  );

  // const fetchUser =useCallback(async () => {
  //   // 토큰이 없으면 API 호출하지 않음
  //   if (!state.token) {
  //     setAuthenticationStatus(STATUS.IDLE);
  //     return;
  //   }

  //   try {
  //     const { data } = await getRequest(`/user-service/api/auth/user`);
  //     // fetchUser가 성공하면 사용자 정보 업데이트
  //     updateUser(data);
  //     setAuthenticationStatus(STATUS.SUCCEEDED);
  //   } catch (error) {
  //     console.error(error.response?.data.message);
  //     toast.error("Can't fetch current user. Please retry later.");
  //     setAuthenticationStatus(STATUS.FAILED);
  //     logout();
  //   }
  // }, [state.token, updateUser, logout]);

  //   useEffect(() => {
  //       fetchUser();    
  //   }, [fetchUser]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
};

//by using this (useMyContext) custom hook we can reach our context provier and access the datas across our components
export const useAuthContext = () => {
  const context = useContext(AuthContext);

  return context;
};
