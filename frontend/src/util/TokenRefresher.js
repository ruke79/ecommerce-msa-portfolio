import { useRef, useEffect, useState, useCallback } from "react";
import { useNavigate } from "react-router-dom";
import api, { getRequest, postRequest } from "util/api";
import { STATUS, useAuthContext } from "store/AuthContext";
import toast from "react-hot-toast";
import { jwtDecode } from "jwt-decode";
import { toUser } from "./jwtUser";
import { translate } from "i18n/I18nProvider";
import { AUTH_REFRESH_EVENT } from "./authEvents";
import { subscribeAuth, SIGNED_IN, SIGNED_OUT } from "./authSync";
import { hasPendingLogout, clearPendingLogout } from "./pendingLogout";

/**
 * 강제 로그아웃이 일어났다는 사실과 **누가 시켰는지**를 남긴다.
 *
 * <p>왜 필요한가. 이 인터셉터는 401 을 받은 요청이 재시도 후에도 401 이면 `logout()` 을
 * 부른다. 그 순간 앱 전체가 로그아웃되고 ProtectedRoute 가 로그인 화면으로 보낸다 —
 * 그런데 **어떤 요청 때문인지는 아무 데도 남지 않았다.** 화면에는 "관리자 페이지에
 * 들어가면 로그인 화면으로 튕긴다" 로만 보인다.
 *
 * <p>실제로 그 증상을 며칠 쫓았다. 원인 후보가 라우트 가드·토큰 만료·역할 클레임·
 * 게이트웨이 라우트로 넷이었는데, 정작 "무슨 요청이 401 을 두 번 받았나" 한 줄만 있으면
 * 바로 좁혀지는 문제였다. 부수적인 호출 하나(예: 배너의 storage-health)가 앱 전체를
 * 로그아웃시킬 수 있다는 것도 이 한 줄이 있어야 드러난다.
 *
 * <p>URL 만 남기고 토큰·헤더는 남기지 않는다. 콘솔 로그도 유출 경로다.
 */
function reportForcedLogout(config, refreshStatus) {
  // 콘솔 문구는 개발자용이라 번역하지 않는다. 사유는 갱신 응답 코드(없으면 요청 자체가 실패)로 받는다.
  // eslint-disable-next-line no-console
  console.warn(
    `[auth] 강제 로그아웃 — ${refreshStatus != null ? `토큰 갱신이 ${refreshStatus} 응답` : "토큰 갱신 요청 자체가 실패"}. ` +
    `원인 요청: ${config?.method?.toUpperCase() ?? "GET"} ${config?.url ?? "(알 수 없음)"}`
  );
  toast.error(translate("auth2.session_expired"), {
    id: "forcedLogout",   // 여러 요청이 동시에 실패해도 토스트는 하나만.
  });
}

/**
 * 토큰 갱신 엔드포인트. 이 요청만은 아래 인터셉터가 손대지 않는다.
 *
 * <p><b>손대면 교착이 난다.</b> 갱신 요청이 401 을 받으면 인터셉터의 401 분기로 들어가는데,
 * 그때 {@code isRefreshing} 은 이미 true 다. 그래서 "다른 요청이 갱신 중" 경로를 타고
 * 큐에 자기 자신을 넣은 뒤 <b>아무도 깨우지 않는 프라미스</b>를 기다린다. 큐를 깨우는
 * 코드는 바깥의 await 가 끝나야 실행되는데 그 await 가 방금 큐에 들어간 그 프라미스다 —
 * 영원히 멈추고, {@code finally} 도 실행되지 않아 {@code isRefreshing} 이 true 로 굳는다.
 * 그 뒤로는 <b>모든 401 요청이 같은 큐에서 멈춘다.</b>
 *
 * <p>지금 서버는 이 경로에서 204/400 만 돌려주고 게이트웨이의 공개 라우트라 401 이 날
 * 일이 없다. 그래도 막아 둔다 — 게이트웨이 설정이 한 번 바뀌면 앱 전체가 조용히
 * 멈추는데, 증상이 "아무 반응이 없다" 라 원인을 찾기가 매우 어렵다.
 */
export const REFRESH_URL = "/user-service/api/token/refresh";

function isRefreshRequest(config) {
  return String(config?.url ?? "").includes(REFRESH_URL);
}

/**
 * 로그인·회원가입처럼 <b>아직 세션이 없는</b> 요청.
 *
 * <p>🐛 <b>여기가 "로그인 실패 이유를 알 수 없다" 의 두 번째 원인이었다.</b>
 *
 * <p>비밀번호를 틀리면 서버는 401 + 사유를 담은 본문을 돌려준다. 그런데 그 401 이 아래
 * 인터셉터의 401 분기로 들어가면, 인터셉터는 "토큰이 만료됐구나" 하고 갱신을 시도한다.
 * 로그인 전이라 갱신 쿠키가 없으니 204 가 오고, 인터셉터는 원래 오류를
 * <b>{@code new Error("token refresh failed")} 로 갈아치운 뒤</b> reject 한다.
 * 그 에러에는 {@code response.data.message} 가 없다 — 그래서 화면은 서버가 보낸 이유를
 * 영영 보지 못하고 기본 문구만 띄웠다.
 *
 * <p>게다가 로그인도 안 한 사용자에게 {@code logout()} 과 강제 로그아웃 로그까지 남겼다.
 *
 * <p>이 경로들의 401 은 "세션이 끝났다"가 아니라 <b>그 요청 자체의 답</b>이다. 손대지
 * 않고 호출부로 그대로 돌려준다.
 */
export const NO_SESSION_URLS = [
  "/user-service/api/auth/public/",
];

function isPreAuthRequest(config) {
  const url = String(config?.url ?? "");
  return NO_SESSION_URLS.some((prefix) => url.includes(prefix));
}

/** 만료 몇 초 전에 미리 갱신하나. */
const REFRESH_LEAD_MS = 10 * 1000;
/** 갱신 사이 최소 간격 — 무엇이 어긋나도 갱신이 연달아 쏟아지지 않게. */
export const MIN_REFRESH_DELAY_MS = 5 * 1000;

/**
 * 다음 선제 갱신까지 기다릴 시간.
 *
 * <p>🐛 예전: {@code exp(서버 시계) − Date.now()(이 PC 시계)}. PC 시계가 액세스 토큰 수명(15분)보다 앞서 있으면 <b>막 받은 토큰도 이미
 * 만료로 보여</b> 즉시 갱신 → 새 토큰도 만료로 보여 즉시 갱신 … 이 끝없이 돌았다. 갱신마다 리프레시 토큰이 회전하니 서버도 함께 바빴다.
 * 시계를 손으로 맞추는 PC · 시간대가 틀린 PC 에서 실제로 생긴다.
 *
 * <p>지금: 두 시계를 섞지 않는다. 토큰의 <b>수명(exp − iat, 둘 다 서버 시계)</b>만큼 지금부터 기다린다 — 이 함수는 토큰을 막 받은
 * 때 불린다(로그인 · 갱신 직후). 그래도 무엇이 어긋나면 최소 간격을 둔다. 갱신이 늦어지는 쪽(PC 시계가 느림)은 401 → 갱신 → 재시도가 받는다.
 */
// [JA] 次の先行更新まで待つ時間。以前は exp(サーバー時計) − Date.now()(この PC の時計)で、PC の時計がトークン寿命より進んでいると、
//      受け取ったばかりのトークンも期限切れに見えて即時更新が無限に続いた。今はトークンの寿命(exp − iat、どちらもサーバー時計)だけ待つ。
export function refreshDelayMs(token, expiresAt, now) {
  let lifetimeMs = null;
  try {
    const { iat, exp } = token ? jwtDecode(token) : {};
    if (Number.isFinite(iat) && Number.isFinite(exp) && exp > iat) {
      lifetimeMs = (exp - iat) * 1000;
    }
  } catch {
    // 읽을 수 없는 토큰 — 아래의 예전 계산으로
  }
  const delay = lifetimeMs != null
    ? lifetimeMs - REFRESH_LEAD_MS
    : expiresAt * 1000 - now - REFRESH_LEAD_MS;
  return Math.max(MIN_REFRESH_DELAY_MS, delay);
}

export function useTokenRefresh() {
  // Use useRef to prevent a re-render in the useEffect.
  // A ref, cannot be used as a useEffect dependency, hence,
  // your linters shouldn't complain about missing dependencies.
  const navRef = useRef(useNavigate());
  // Promise를 저장하여 대기 중인 요청을 처리할 큐
  const refreshAndRetryQueue = useRef([]);
  const isRefreshing = useRef(false);

  const {
    login,
    logout,
    token,
    user,
    addresses,
    status,
    isAuthenticated,
    expiresAt,
    setAuthenticationStatus
  } = useAuthContext();
  const tokenRef = useRef(token);

  

  /** @param options.broadcast false 면 결과를 다른 탭에 알리지 않는다(다른 탭의 알림을 받아 갱신할 때). */
  const refreshAccessToken = useCallback(async ({ broadcast = true } = {}) => {
    try {
      const response = await getRequest("/user-service/api/token/refresh");

      if (response.status === 204) {
        logout({ broadcast });
      } else if (response.status === 200) {
        const accessToken = response.headers["access"];
        const decodedToken = jwtDecode(accessToken);
        const user = toUser(decodedToken, accessToken);
        const expiredAt = decodedToken.exp;
        login(user, accessToken, expiredAt, { broadcast });
        //setAuthenticationStatus(STATUS.SUCCEEDED);  --> flicking problem
      }
    } catch (error) {
      logout({ broadcast });
      //setAuthenticationStatus(STATUS.FAILED);  --> flicking problem
    }
  }, [login, logout]);

  // 다른 탭의 로그인 · 로그아웃을 따라간다(util/authSync). 받은 알림으로는 다시 알리지 않는다 — 탭끼리 주고받기가 멈추지 않는다.
  // [JA] 他のタブのログイン · ログアウトに追従する。受けた通知では再通知しない。
  const authSnapshot = useRef({ isAuthenticated, status, userId: null });
  authSnapshot.current = { isAuthenticated, status, userId: user?.userId ?? null };
  useEffect(() => {
    return subscribeAuth(({ type, uid }) => {
      const now = authSnapshot.current;
      if (type === SIGNED_OUT) {
        if (now.isAuthenticated) {
          logout({ broadcast: false });
          toast(translate("auth2.signed_out_elsewhere"), { id: "signedOutElsewhere" });
        }
        return;
      }
      if (type === SIGNED_IN) {
        // 처음 복구 중이면 그 갱신이 결과를 정한다 — 겹쳐 부르면 리프레시 토큰이 두 번 회전한다.
        if (now.status === STATUS.PENDING) return;
        if (!now.isAuthenticated) {
          // 로그인 화면이나 "세션 만료" 에 멈춰 있던 탭 — 공유 쿠키로 자기 토큰을 받는다. 작성 중이던 화면은 그대로 둔다.
          refreshAccessToken({ broadcast: false });
        } else if (uid && now.userId && uid !== now.userId) {
          // 다른 사용자로 바뀌었다 — 앞 사용자의 화면 상태를 남기지 않는다.
          window.location.reload();
        }
      }
    });
  }, [login, logout, refreshAccessToken]);

  // 지난번 로그아웃이 서버에 반영되지 못했다(util/pendingLogout) — 자동 복구 대신 서버 로그아웃을 먼저 마무리한다.
  // 로그아웃 API 는 게이트웨이가 액세스 토큰을 요구하므로 한 번 갱신해 받은 토큰으로 부르고, 그 토큰은 화면 상태에 넣지 않는다.
  // 서버가 답했으면(성공이든 "이미 끝난 세션" 이든) 표시를 지운다. 네트워크가 안 되면 표시를 남기고 로그아웃 상태로 둔다.
  // [JA] 前回のログアウトがサーバーに反映されていない — 自動復旧の代わりにサーバーのログアウトを先に完了させる。
  const finishPendingLogout = useCallback(async () => {
    try {
      const response = await getRequest(REFRESH_URL);
      if (response.status === 200) {
        await postRequest("/user-service/api/auth/logout", null, undefined, {
          headers: { Authorization: `Bearer ${response.headers["access"]}`, MultiLogin: "false" },
        });
      }
      clearPendingLogout();
    } catch (error) {
      if (error?.response) clearPendingLogout();   // 서버가 답했다 — 세션이 이미 끝났다
    } finally {
      logout({ broadcast: false });
    }
  }, [logout]);

  useEffect(() => {
    if (status === STATUS.PENDING) {
      if (hasPendingLogout()) {
        finishPendingLogout();
      } else {
        refreshAccessToken();
      }
    }
  }, [refreshAccessToken, finishPendingLogout, status]); // status를 의존성 배열에 추가하여 초기 로딩 시 동작하도록 변경

  // 긴 연결(채팅 · 알림)이 서버에게 닫혔다 — 토큰이 만료됐거나 로그인이 해제됐다(docs/68 §9). 한 번만 갱신한다:
  // 여러 연결이 한꺼번에 닫혀도 갱신 요청은 하나다(갱신마다 리프레시 토큰이 회전한다).
  const eventRefreshInFlight = useRef(false);
  useEffect(() => {
    const onRefreshNeeded = async () => {
      if (!isAuthenticated || eventRefreshInFlight.current) return;
      eventRefreshInFlight.current = true;
      try {
        await refreshAccessToken();
      } finally {
        eventRefreshInFlight.current = false;
      }
    };
    window.addEventListener(AUTH_REFRESH_EVENT, onRefreshNeeded);
    return () => window.removeEventListener(AUTH_REFRESH_EVENT, onRefreshNeeded);
  }, [isAuthenticated, refreshAccessToken]);

  useEffect(() => {
    let refreshAccessTokenTimerId;

    if (isAuthenticated && expiresAt) {
      const delay = refreshDelayMs(token, expiresAt, Date.now());
      refreshAccessTokenTimerId = setTimeout(() => {
        refreshAccessToken();
      }, delay);
    }

    return () => {
      if (isAuthenticated && refreshAccessTokenTimerId) {
        clearTimeout(refreshAccessTokenTimerId);
      }
    };
  }, [token, expiresAt, isAuthenticated, refreshAccessToken]);

  const [prevToken, setPrevToken] = useState(null);
  if (prevToken !== token) {
    setPrevToken(token);

    tokenRef.current = token;
  }

  useEffect(() => {
    const reqInterceptor = api.interceptors.request.use(
      async (config) => {
        //console.log(config.headers['MultiLogin']);

        //'MultiLogin' : 'false',
        //config.headers.MultiLogin =

        if (tokenRef.current) {
          config.headers.Authorization = `Bearer ${tokenRef.current}`;
        }

        return config;
      },
      (error) => {
        return Promise.reject(error);
      }
    );

    const resInterceptor = api.interceptors.response.use(
      (res) => {
        return res;
      },
      async (error) => {
        let originalConfig = error.config;

        const msg = error?.response?.data;

        // 갱신 요청은 건드리지 않는다 — 교착이 난다(REFRESH_URL 주석).
        // 갱신 실패의 처리는 아래 401 분기와 refreshAccessToken 이 직접 한다.
        if (isRefreshRequest(originalConfig)) {
          return Promise.reject(error);
        }

        // 로그인·가입 등 세션이 없는 요청의 실패는 그 요청의 답이다. 갱신을 시도하면
        // 서버가 보낸 실패 사유가 "token refresh failed" 로 덮인다(NO_SESSION_URLS 주석).
        if (isPreAuthRequest(originalConfig)) {
          return Promise.reject(error);
        }

        switch (error?.response?.status) {
          /**
           * 🐛 예전에는 `navRef.current("/")` — <b>어떤 500 이든 홈으로</b> 보냈다.
           *
           * 사용자는 자기가 무엇을 하다 튕겼는지 알 수 없었고, 작성 중이던 폼도 함께
           * 사라졌다. 게다가 화면 한구석의 부수적인 호출 하나가 실패해도 전체가
           * 이동했다.
           *
           * 서버 오류는 <b>그 요청의 실패</b>다. 호출부로 돌려준다 — 부를 때 감싸 둔
           * 화면은 자기 자리에 메시지를 그리고, 그러지 않은 화면은 ErrorBoundary 가 받는다.
           */
          case 500:
            break;

          /**
           * 🐛 예전에는 `/error_server` 로 보내고 <b>로그아웃까지</b> 했다.
           *
           * 503 은 서비스가 잠시 뜨지 않았다는 뜻이다(서킷 브레이커가 열렸거나 파드가
           * 재시작 중이거나). <b>사용자의 세션과는 아무 상관이 없다.</b> 배포 중에
           * 503 하나를 만난 사용자가 로그아웃되어 장바구니 화면에서 쫓겨났다.
           */
          case 503:
            toast.error(translate("auth2.service_unavailable"), {
              id: "serviceUnavailable",
            });
            break;

          case 400:
            // 갱신 토큰이 만료됐다 = 세션이 진짜로 끝났다. 이때는 로그아웃이 맞다.
            if (msg === "refresh token expired") {
              logout();
              navRef.current("/signin");

              toast.error(translate("auth2.signin_again"), {
                id: "loginError",
              });
            }
            break;
          case 401: {
            /**
             * 🐛 <b>여기가 "부수적인 요청 하나가 앱 전체를 로그아웃시킨다" 의 지점이었다.</b>
             *
             * 예전에는 리프레시 후 재시도해도 401 이면 무조건 {@code logout()} 이었다.
             * 그런데 <b>리프레시는 성공했다</b> — 새 토큰을 받아 재시도한 것이다. 그러고도
             * 401 이라면 그 토큰이 무효한 것이 아니라 <b>그 엔드포인트가 이 사용자에게
             * 닫혀 있다</b>는 뜻이다(사실상 403).
             *
             * 실제로 `/admin/product` 가 로그인 화면으로 튕긴 원인이 이것이었다. 화면
             * 상단 배너의 storage-health 호출 하나가 401 을 받아 <b>세션을 날렸다.</b>
             * 관리자는 자기 권한 문제인지 로그인 문제인지 구분할 수 없었다.
             *
             * 세션이 끝났다고 판정할 수 있는 것은 <b>갱신 자체가 실패했을 때뿐</b>이다.
             * 그 경로(아래 non-200 · catch · 400 "refresh token expired")는 그대로 둔다.
             * 개별 요청의 401 은 그 요청의 실패로 돌려준다.
             */
            if (originalConfig._retry) {
              // eslint-disable-next-line no-console
              console.warn(
                `[auth] 401 (갱신 후에도) — ${originalConfig?.method?.toUpperCase() ?? "GET"} ` +
                `${originalConfig?.url ?? "(알 수 없음)"}. 세션은 유지한다 — 이 요청에 대한 권한이 없다.`
              );
              return Promise.reject(error);
            }

            if (!isRefreshing.current) {
              isRefreshing.current = true;
              try {
                const response = await getRequest(
                  "/user-service/api/token/refresh"
                );

                if (response.status === 200) {
                  const accessToken = response.headers["access"];
                  const decodedToken = jwtDecode(accessToken);
                  const user = toUser(decodedToken, accessToken);
                  login(user, accessToken, decodedToken.exp);

                  // 대기 중이던 모든 요청을 새 토큰으로 깨운다.
                  refreshAndRetryQueue.current.forEach(({ resolve }) =>
                    resolve(accessToken)
                  );
                  refreshAndRetryQueue.current.length = 0;

                  originalConfig._retry = true;
                  originalConfig.headers["Authorization"] = `Bearer ${accessToken}`;
                  return api(originalConfig);
                }

                // 200이 아니면(예: 204 = 리프레시 토큰 없음) 갱신 불가 → 대기 요청까지 정리하고 로그아웃.
                // (기존 코드는 이 경로에서 큐에 push만 하고 아무도 resolve하지 않아 요청이 영구 hang했음)
                const authError = new Error("token refresh failed");
                refreshAndRetryQueue.current.forEach(({ reject }) =>
                  reject(authError)
                );
                refreshAndRetryQueue.current.length = 0;
                reportForcedLogout(originalConfig, response.status);
                logout();
                return Promise.reject(authError);
              } catch (refreshError) {
                refreshAndRetryQueue.current.forEach(({ reject }) =>
                  reject(refreshError)
                );
                refreshAndRetryQueue.current.length = 0;
                reportForcedLogout(originalConfig, null);
                logout();
                return Promise.reject(refreshError);
              } finally {
                isRefreshing.current = false;
              }
            }

            // 다른 요청이 이미 갱신 중이면, 큐에서 대기했다가 새 토큰으로 재시도한다.
            return new Promise((resolve, reject) => {
              refreshAndRetryQueue.current.push({ resolve, reject });
            }).then((newAccessToken) => {
              originalConfig._retry = true;
              originalConfig.headers["Authorization"] = `Bearer ${newAccessToken}`;
              return api(originalConfig);
            });
          }

          default:
            break;
        }
        return Promise.reject(error);
      }
    );

    return () => {
      api.interceptors.request.eject(reqInterceptor);
      api.interceptors.response.eject(resInterceptor);
    };
  }, [token, login, logout]);
}

export default function TokenRefresher() {
  useTokenRefresh();
  return <></>;
}
