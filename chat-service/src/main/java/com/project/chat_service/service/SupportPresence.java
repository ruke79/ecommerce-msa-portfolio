package com.project.chat_service.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 누가 지금 어느 상담 방을 보고 있는가 (docs/58 §10) — STOMP 구독으로 안다.
 *
 * <p>상담원 답변 알림을 <b>보고 있는 고객에게는 보내지 않기</b> 위해서다. 대화창을 열어 둔 사람에게 벨 알림까지
 * 울리면 소음이다. 구독(SUBSCRIBE)에 적고, 구독 해지(UNSUBSCRIBE)·연결 끊김(DISCONNECT)에 지운다.
 *
 * <p>메모리에 둔다 — chat-service 는 파드 하나다(인메모리 브로커, replicas: 1). 파드가 재시작되면 비지만, 그때는
 * 모든 소켓도 끊겨 클라이언트가 다시 구독하므로 곧 채워진다. 틀려도 "알림 한 통 더" 쪽으로 틀린다.
 */
@Component
public class SupportPresence {

    private record Watch(Long userId, String roomId) {
    }

    /** 세션 → (구독 id → 무엇을 보고 있나). */
    private final Map<String, Map<String, Watch>> sessions = new ConcurrentHashMap<>();

    public void subscribed(String sessionId, String subscriptionId, Long userId, String roomId) {
        if (sessionId == null || subscriptionId == null || userId == null || roomId == null) {
            return;
        }
        sessions.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
                .put(subscriptionId, new Watch(userId, roomId));
    }

    public void unsubscribed(String sessionId, String subscriptionId) {
        if (sessionId == null || subscriptionId == null) {
            return;
        }
        Map<String, Watch> subs = sessions.get(sessionId);
        if (subs != null) {
            subs.remove(subscriptionId);
            if (subs.isEmpty()) {
                sessions.remove(sessionId, subs);
            }
        }
    }

    public void disconnected(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    /** 이 사용자가 이 방을 지금 구독하고 있는가(탭 하나라도). */
    public boolean watching(Long userId, String roomId) {
        if (userId == null || roomId == null) {
            return false;
        }
        return sessions.values().stream()
                .flatMap(subs -> subs.values().stream())
                .anyMatch(w -> userId.equals(w.userId()) && roomId.equals(w.roomId()));
    }
}
