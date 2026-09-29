import { useCallback, useEffect, useRef, useState } from "react";

import { getSupportMessages } from "util/api";
import { useStomp } from "util/stompProvider";

/** 서버가 한 번에 주는 기록의 수. 하나 더(21개) 오면 더 있다는 뜻이다(ChatService.getRoomMessagesByCurser). */
export const SUPPORT_PAGE_SIZE = 20;

/** 안내 메시지(연결·종료) — 서버가 팀 명의로, 발신자 없이 보낸다(SupportService.announce). */
export const isNotice = (m) => m?.type === "TEXT_ENTER" || m?.type === "TEXT_LEAVE" || m?.senderId == null;

/** 서버는 최신부터(내림차순) 준다. 화면은 오래된 것부터 그린다. */
function splitPage(page) {
    const list = Array.isArray(page) ? page : [];
    return { items: list.slice(0, SUPPORT_PAGE_SIZE).reverse(), more: list.length > SUPPORT_PAGE_SIZE };
}

function merge(prev, incoming) {
    const seen = new Set(prev.map((m) => m.id));
    const added = incoming.filter((m) => !seen.has(m.id));
    return added.length ? [...prev, ...added] : prev;
}

/**
 * 상담 스레드 하나의 대화 (docs/58).
 *
 * <p>기록은 REST({@code /threads/{id}/messages} — 본인·상담원만), 실시간은 STOMP 로 받는다. 구독 주소는
 * {@code /sub/chat/room/support-<id>} 이고 서버가 <b>주소로</b> 권한을 본다. 보내기는 친구 채팅과 같은
 * {@code /pub/chat/message} 다 — 발신자는 서버가 세션에서 정하므로 싣지 않는다.
 *
 * @param thread   상담 스레드(threadId·roomId). 없으면 아무것도 하지 않는다
 * @param onNotice  연결·종료 안내가 왔을 때 — 화면은 스레드 상태를 다시 읽는다
 * @param onMessage 실시간으로 메시지가 올 때마다(안내 포함) — 화면이 "새 답변" 표시에 쓴다
 */
export function useSupportConversation(thread, { onNotice, onMessage } = {}) {
    const { isConnected, subscribe, sendMessage } = useStomp();
    const [messages, setMessages] = useState([]);
    const [hasMore, setHasMore] = useState(false);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState(false);
    const onNoticeRef = useRef(onNotice);
    onNoticeRef.current = onNotice;
    const onMessageRef = useRef(onMessage);
    onMessageRef.current = onMessage;

    const threadId = thread?.threadId;
    const roomId = thread?.roomId;

    useEffect(() => {
        setMessages([]);
        setHasMore(false);
        setError(false);
        if (!threadId) return undefined;
        let cancelled = false;
        setLoading(true);
        getSupportMessages(threadId, "-1")
            .then((page) => {
                if (cancelled) return;
                const { items, more } = splitPage(page);
                // 기록을 읽는 사이 실시간으로 먼저 온 것이 있을 수 있다 — 합친다.
                setMessages((prev) => merge(items, prev));
                setHasMore(more);
            })
            .catch(() => !cancelled && setError(true))
            .finally(() => !cancelled && setLoading(false));
        return () => { cancelled = true; };
    }, [threadId]);

    useEffect(() => {
        if (!isConnected || !roomId) return undefined;
        return subscribe(`/sub/chat/room/${roomId}`, (frame) => {
            let msg;
            try {
                msg = JSON.parse(frame.body);
            } catch {
                return;
            }
            setMessages((prev) => merge(prev, [msg]));
            onMessageRef.current?.(msg);
            if (isNotice(msg)) onNoticeRef.current?.(msg);
        }, { roomId });
    }, [isConnected, roomId, subscribe]);

    const loadMore = useCallback(async () => {
        if (!threadId || !messages.length) return;
        try {
            const { items, more } = splitPage(await getSupportMessages(threadId, messages[0].id));
            setMessages((prev) => merge(items, prev));
            setHasMore(more);
        } catch {
            setError(true);
        }
    }, [threadId, messages]);

    const send = useCallback((text) => {
        const content = String(text ?? "").trim();
        if (!content || !roomId) return false;
        return sendMessage("/pub/chat/message", { roomId, content, type: "TEXT_TALK" });
    }, [roomId, sendMessage]);

    return { messages, hasMore, loadMore, loading, error, isConnected, send };
}
