import { useMemo } from "react";
import PropTypes from "prop-types";

import { useAuthContext } from "store/AuthContext";
import { StompProvider } from "util/stompProvider";
import { API_BASE } from "util/apiBase";

/**
 * 상담 화면의 STOMP 연결 — 친구 채팅(ChatPage)과 같은 엔드포인트. CONNECT 때 서버가 토큰에서 신원·역할을 읽는다.
 * 주소와 헤더는 메모이즈한다 — 매 렌더에 새 객체면 StompProvider 가 매번 다시 연결한다(ChatPage 주석).
 */
export default function SupportStomp({ children }) {
    const { token } = useAuthContext();
    const brokerURL = useMemo(() => `${API_BASE}/chat-service/chat`, []);
    const connectHeaders = useMemo(() => ({ Authorization: token }), [token]);
    return <StompProvider brokerURL={brokerURL} connectHeaders={connectHeaders}>{children}</StompProvider>;
}

SupportStomp.propTypes = { children: PropTypes.node.isRequired };
