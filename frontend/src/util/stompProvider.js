import { Client } from "@stomp/stompjs";
import SockJS from "sockjs-client";
import { createContext, useContext, useRef, useState, useEffect, useCallback } from "react";
import PropTypes from "prop-types";
import { translate } from "i18n/I18nProvider";
import { SESSION_CLOSE_CODE, requestTokenRefresh } from "./authEvents";

// Context 생성
const StompContext = createContext(null);

const isDev = process.env.NODE_ENV !== "production";

export function StompProvider({
  children,
  brokerURL,
  connectHeaders,
  onConnectCallback,
  onDisconnectCallback,
  onCloseCallback,
  onErrorCallback,
  reconnectDelay = 5000,
}) {
  const stompClientRef = useRef(null);
  const [isConnected, setIsConnected] = useState(false);
  const [connectionError, setConnectionError] = useState(null);

  useEffect(() => {
    const sockjsOptions = {
      headers: connectHeaders,
    };

    const client = new Client({
      webSocketFactory: () => new SockJS(brokerURL, null, sockjsOptions),
      connectHeaders,
      // 운영 환경에서는 STOMP 프레임(인증 헤더 포함)을 콘솔에 남기지 않는다.
      debug: isDev ? (str) => console.debug("[STOMP]", str) : () => {},
      reconnectDelay,
      heartbeatIncoming: 4000,
      heartbeatOutgoing: 4000,
      onConnect: () => {
        setIsConnected(true);
        setConnectionError(null);
        onConnectCallback && onConnectCallback();
      },
      onStompError: (frame) => {
        const message = frame.headers?.["message"] || translate("stomp.broker_error");
        console.error("STOMP broker error:", message, frame.body);
        setConnectionError(message);
        onErrorCallback && onErrorCallback(frame);
      },
      onWebSocketError: () => {
        setConnectionError(translate("stomp.connect_failed"));
      },
      onDisconnect: () => {
        setIsConnected(false);
        onDisconnectCallback && onDisconnectCallback();
      },
      onWebSocketClose: (event) => {
        setIsConnected(false);
        // 서버가 "토큰이 만료됐거나 로그인이 해제됐다" 로 닫았다(docs/68 §9). 같은 토큰으로 다시 붙어 봐야 거절되므로
        // 자동 재연결을 멈추고 갱신을 부탁한다 — 새 토큰이 나오면 connectHeaders 가 바뀌어 이 effect 가 다시 붙는다.
        if (event?.code === SESSION_CLOSE_CODE) {
          client.deactivate();
          requestTokenRefresh();
        }
        onCloseCallback && onCloseCallback();
      },
    });

    client.activate();
    stompClientRef.current = client;

    return () => {
      stompClientRef.current = null;
      client.deactivate();
    };
  }, [
    brokerURL,
    connectHeaders,
    onConnectCallback,
    onDisconnectCallback,
    onCloseCallback,
    onErrorCallback,
    reconnectDelay,
  ]);

  const subscribe = useCallback(
    (destination, callback, headers = {}) => {
      if (!isConnected || !stompClientRef.current) {
        console.warn("STOMP not connected, cannot subscribe yet.");
        return () => {};
      }
      // 구독마다 독립적인 StompSubscription을 캡처해서 반환하므로,
      // 방을 빠르게 전환해도 이전 구독이 최신 구독을 대신 해지하는 일이 없다.
      const subscription = stompClientRef.current.subscribe(destination, callback, headers);
      return () => subscription.unsubscribe();
    },
    [isConnected]
  );

  const sendMessage = useCallback(
    (destination, body = {}, headers = {}) => {
      if (!isConnected || !stompClientRef.current) {
        console.warn("STOMP not connected, cannot send message now.");
        return false;
      }
      stompClientRef.current.publish({
        destination,
        body: JSON.stringify(body),
        headers,
      });
      return true;
    },
    [isConnected]
  );

  return (
    <StompContext.Provider value={{ isConnected, connectionError, subscribe, sendMessage }}>
      {children}
    </StompContext.Provider>
  );
}
StompProvider.propTypes = {
  children: PropTypes.node.isRequired,
  brokerURL: PropTypes.string.isRequired,
  connectHeaders: PropTypes.object,
  onConnectCallback: PropTypes.func,
  onDisconnectCallback: PropTypes.func,
  onCloseCallback: PropTypes.func,
  onErrorCallback: PropTypes.func,
  reconnectDelay: PropTypes.number,
};

// Context를 쉽게 사용하기 위한 커스텀 훅
export function useStomp() {
  const context = useContext(StompContext);
  if (!context) {
    throw new Error("useStomp must be used within a StompProvider");
  }
  return context;
}
