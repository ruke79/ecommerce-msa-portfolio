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

  const login = React.useCallback((user, token, expiresAt) => {
    dispatch({
      type: "login",
      payload: {
        user,
        token,
        expiresAt,
      },
    });
  }, []);

  const logout = React.useCallback(() => {
    dispatch({
      type: "logout",
    });
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
