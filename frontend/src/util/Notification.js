import { useEffect, useRef, useState, useCallback } from "react";
import { EventSourcePolyfill, NativeEventSource } from "event-source-polyfill";

import { useLogout } from "hook/hooks";
import { useAuthContext } from "store/AuthContext";
import { API_BASE } from "./apiBase";
import { SSE_REAUTH, requestTokenRefresh } from "./authEvents";

const EventSourceImpl = EventSourcePolyfill || NativeEventSource;
const isDev = process.env.NODE_ENV !== "production";
const MAX_RECONNECT_ATTEMPTS = 5;

/**
 * 서버가 보내는 실시간 알림(SSE) 구독.
 *
 * <p>고친 것들:
 *
 * <ol>
 *   <li><b>재연결이 영원히 멈췄다.</b> 시도 횟수를 세는 `reconnectAttempts` 는 성공(onopen)
 *       했을 때만 0 으로 돌아갔다. 그래서 서버 배포처럼 몇 분간 끊기는 상황에서 5번을
 *       금방 소진하면, 이후 서버가 돌아와도 <b>다시는 연결을 시도하지 않았다.</b> 사용자가
 *       페이지를 새로고침하기 전까지 알림이 죽는다. 이제 상한에 닿으면 5분 간격의 "느린
 *       재시도"로 넘어간다.</li>
 *   <li><b>연결이 겹쳤다.</b> 새 연결을 만들기 전에 이전 EventSource 를 닫지 않아서,
 *       토큰이 갱신될 때마다(TokenRefresher 가 만료 10초 전에 갱신한다) 연결이 하나씩
 *       늘었다. 오래 열어 둔 탭에서는 같은 알림이 여러 번 도착한다.</li>
 *   <li><b>정리(cleanup)가 재연결을 막지 못했다.</b> 언마운트 시 타이머는 지웠지만, 이미
 *       실행 중이던 onerror 가 새 타이머를 걸면 그대로 살아남았다. 이제 "그만" 플래그를
 *       두고 그 뒤의 재연결을 막는다.</li>
 *   <li>오타 `EventSoruce`, 그리고 모든 알림 본문을 콘솔에 찍던 `console.log` 를 정리했다.
 *       알림 내용에는 주문번호 같은 개인정보가 들어갈 수 있어 운영 콘솔에 남기면 안 된다.</li>
 * </ol>
 */
export const useNotification = () => {
  const { token, isAuthenticated } = useAuthContext();
  const { logout, isPending } = useLogout(true);

  const [isConnected, setIsConnected] = useState(false);

  const eventSource = useRef(null);
  const reconnectAttempts = useRef(0);
  const timerId = useRef(null);
  const stopped = useRef(false);
  // 서버가 "갱신하고 다시 붙어라" 를 보냈다 — 이 토큰으로는 재연결하지 않는다(토큰이 바뀌면 새로 붙는다).
  const awaitingReauth = useRef(false);

  const connect = useCallback(() => {
    if (stopped.current || !isAuthenticated || !token || isPending) return;

    function scheduleReconnect() {
      if (stopped.current || awaitingReauth.current) return;

      const attempt = reconnectAttempts.current;
      // 상한을 넘겨도 포기하지 않는다 — 대신 5분마다 조용히 다시 시도한다. 서버가
      // 돌아왔을 때 사용자가 새로고침하지 않아도 알림이 되살아나야 한다.
      const delay = attempt >= MAX_RECONNECT_ATTEMPTS
        ? 5 * 60 * 1000
        : Math.min(1000 * 2 ** attempt, 30000);

      reconnectAttempts.current = attempt + 1;
      clearTimeout(timerId.current);
      timerId.current = setTimeout(connect, delay);
    }

    // 새로 열기 전에 반드시 이전 연결을 닫는다.
    eventSource.current?.close();
    awaitingReauth.current = false;

    try {
      const source = new EventSourceImpl(
        `${API_BASE}/user-service/api/sse`,
        {
          headers: {
            "Content-Type": "text/event-stream",
            "Cache-Control": "no-cache",
            // nginx 가 SSE 를 버퍼링하지 않도록 하는 힌트. 서버도 같은 헤더를
            // 응답에 실어야 실제로 효과가 있다(frontend/nginx.conf 참조).
            "X-Accel-Buffering": "no",
            Authorization: `Bearer ${token}`,
          },
          heartbeatTimeout: 120000,
          withCredentials: true,
        }
      );
      eventSource.current = source;

      source.onopen = () => {
        setIsConnected(true);
        reconnectAttempts.current = 0;
        clearTimeout(timerId.current);
      };

      source.addEventListener("notification", (event) => {
        // 다른 기기에서 로그인해 이 세션이 밀려난 경우다.
        if (event.data === "Logout") {
          logout();
        }
        // 토큰이 만료됐거나 로그인이 해제됐다(docs/68 §9) — 갱신을 부탁한다. 로그인이 살아 있으면 새 토큰으로 다시 붙고,
        // 끊겼으면 갱신이 실패해 로그아웃된다.
        if (event.data === SSE_REAUTH) {
          awaitingReauth.current = true;
          source.close();
          setIsConnected(false);
          requestTokenRefresh();
        }
      });

      source.onerror = () => {
        source.close();
        setIsConnected(false);
        scheduleReconnect();
      };
    } catch (err) {
      if (isDev) console.error("SSE 연결 실패:", err);
      scheduleReconnect();
    }
  }, [token, isAuthenticated, isPending, logout]);

  useEffect(() => {
    stopped.current = false;
    connect();

    return () => {
      stopped.current = true;
      clearTimeout(timerId.current);
      eventSource.current?.close();
      eventSource.current = null;
    };
  }, [connect]);

  return { isConnected };
};

export default function Notification() {
  useNotification();
  return null;
}
