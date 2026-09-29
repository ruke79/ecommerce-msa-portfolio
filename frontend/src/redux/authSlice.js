import { createSlice } from '@reduxjs/toolkit';

export const STATUS = Object.freeze({
  IDLE: "idle",
  PENDING: "pending",
  SUCCEEDED: "succeeded",
  FAILED: "failed",
});

const initialState = {
    user: null,
    token: null,
    expiresAt: null,
    isAuthenticated: false,
    status: STATUS.IDLE, // 'idle', 'pending', 'succeeded', 'failed'
};

const authSlice = createSlice({
    name: 'auth',
    initialState,
    reducers: {
        login: (state, action) => {
            const { user, token, expiresAt } = action.payload;
            state.user = user;
            state.token = token;
            state.expiresAt = expiresAt;
            state.isAuthenticated = true;
            state.status = STATUS.SUCCEEDED;
        },
        logout: (state) => {
            state.user = null;
            state.token = null;
            state.expiresAt = null;
            state.isAuthenticated = false;
            state.status = STATUS.IDLE;
        },
        updateUser: (state, action) => {
            state.user = action.payload.user;
        },
        setStatus: (state, action) => {
            state.status = action.payload.status;
        },
    },
});

export const { login, logout, updateUser, setStatus } = authSlice.actions;
export default authSlice.reducer;