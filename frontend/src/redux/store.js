import { configureStore, combineReducers } from "@reduxjs/toolkit";
import menuReducer from "./MenuSlice";
import dialogReducer from "./DialogSlice";
import cartReducer from "./CartSlice";
import chatReducer from "./ChatSlice";
import { persistStore, persistReducer, FLUSH, REHYDRATE, PAUSE, PERSIST, PURGE, REGISTER } from "redux-persist";
import storage from "redux-persist/lib/storage"; // defaults to localStorage for web

const rootReducer = combineReducers({
    menu: menuReducer,
    dialog: dialogReducer,
    cart: cartReducer,
    chat: chatReducer,
});

/**
 * 어떤 상태를 새로고침 뒤에도 남길 것인가.
 *
 * <p><b>예전 설정은 아무것도 저장하지 않았다.</b> `whitelist: ['auth']` 로 돼 있었는데
 * rootReducer 에 `auth` 가 없다(위쪽에 주석 처리돼 있었다). redux-persist 의 whitelist 는
 * <b>존재하는 최상위 리듀서 키</b>만 의미가 있으므로, 없는 키를 지정하면 저장 대상이 0개가
 * 된다. PersistGate 로 앱을 감싸고 persistStore 까지 부르면서 실제로는 아무 일도 하지 않는
 * 상태였다 — 장바구니는 새로고침하면 매번 비워졌다.
 *
 * <p>이제 `cart` 만 저장한다. 담아 둔 물건이 새로고침으로 사라지는 것은 사용자가 가장 크게
 * 체감하는 손실이다. 반대로 저장하면 안 되는 것들:
 *  - `chat`: 서버가 진실이다. 오래된 대화 목록을 되살리면 실제와 어긋난다.
 *  - `menu`/`dialog`: 순수 UI 상태다. 열려 있던 모달이 새로고침 후 되살아나면 이상하다.
 *  - 인증 토큰: 애초에 이 스토어에 없다. XSS 로 탈취되지 않도록 메모리(AuthContext)에만
 *    두는 것이 의도된 설계다(util/tokenUtil.js). 여기 whitelist 에 auth 를 넣으려는 시도는
 *    그 설계를 되돌리는 것이므로 하면 안 된다.
 *
 * <p>`blacklist: ['token']` 도 지웠다. blacklist 역시 최상위 키 목록이라 `token` 이라는
 * 리듀서가 없는 이상 아무것도 하지 않았다 — "토큰은 저장 안 함"이라는 안심을 주는 주석만
 * 남기고 실제 보호는 하지 않는, 있어서 더 위험한 설정이었다.
 */
const persistConfig = {
    key: "root",
    storage,
    whitelist: ["cart"],
};

const persistedReducer = persistReducer(persistConfig, rootReducer);

export const store = configureStore({
    reducer: persistedReducer,
    devTools: process.env.REACT_APP_PROFILE !== "production",
    middleware: (getDefaultMiddleware) =>
        getDefaultMiddleware({
            // serializableCheck 를 통째로 끄면 redux-persist 의 내부 액션뿐 아니라
            // **우리 코드가 스토어에 넣는 File·Date 같은 값도** 검사에서 빠진다. 그러면
            // 직렬화 불가능한 값이 들어가도 개발 중에 아무 경고가 없다가 persist 시점에야
            // 터진다. 검사는 켜 두고 redux-persist 액션만 예외로 둔다(공식 권장 방식).
            serializableCheck: {
                ignoredActions: [FLUSH, REHYDRATE, PAUSE, PERSIST, PURGE, REGISTER],
            },
        }),
});


export const persistor = persistStore(store)

