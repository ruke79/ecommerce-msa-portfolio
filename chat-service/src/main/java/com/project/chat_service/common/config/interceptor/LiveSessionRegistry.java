package com.project.chat_service.common.config.interceptor;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

import com.project.common.security.SessionRevocation;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * 채팅 WebSocket 을 <b>토큰 수명과 로그인 해제</b>에 묶는다(user-service docs/68 §9).
 *
 * <p>🐛 예전: STOMP CONNECT 때 토큰을 한 번 보고 끝이었다. 연결은 끊길 때까지 — 토큰이 만료돼도, 로그아웃 · "모든 기기에서 로그아웃" ·
 * 리프레시 토큰 재사용 감지로 로그인이 끊겨도 — 계속 방의 메시지를 받았다. 상한이 없었다.
 *
 * <ul>
 *   <li><b>만료에 닫는다</b> — CONNECT 때 토큰의 {@code exp} 에 닫기를 예약한다(close code 4401). 프론트는 만료 10초 전에 갱신한 토큰으로
 *       이미 다시 붙어 있으므로 사용자에게는 보이지 않는다. 다시 붙지 않는 클라이언트(오래된 탭 · 도둑)만 여기서 끊긴다.</li>
 *   <li><b>로그인 해제는 즉시</b> — user-service 가 {@link SessionRevocation#CHANNEL} 로 알리면 그 사용자의 연결을 모두 4401 로 닫는다.
 *       로그인이 살아 있는 기기는 갱신 뒤 다시 붙고, 끊긴 기기는 갱신이 실패해 로그인 화면으로 간다.</li>
 *   <li><b>다시 붙는 것도 막는다</b> — 닫기만 하면 옛 토큰으로 곧장 다시 붙는다. CONNECT 에서 해제 시각 이전에 발급된 토큰을 거절한다
 *       ({@link #allowed}).</li>
 * </ul>
 *
 * <p>세션 id 는 STOMP 의 {@code simpSessionId} 와 같다 — {@code StompSubProtocolHandler} 가 WebSocket(SockJS) 세션의 id 를 그대로 쓴다.
 */
// [JA] チャットの WebSocket をトークン寿命とログイン解除に結びつける。exp で 4401 で閉じ、ログイン解除の通知で即座に閉じ、
//      解除時刻より前に発行されたトークンでの再接続を CONNECT で拒否する。
@Slf4j
@Component
public class LiveSessionRegistry {

    private final StringRedisTemplate redis;

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Long> owners = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> deadlines = new ConcurrentHashMap<>();

    /** 닫기 예약 — 하는 일이 작아 스레드 하나면 된다. 데몬이라 종료를 붙잡지 않는다. */
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ws-token-expiry");
        t.setDaemon(true);
        return t;
    });

    public LiveSessionRegistry(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** WebSocket 처리기에 씌운다 — 연결 · 끊김을 안다(WebSocketConfiguration). */
    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                sessions.put(session.getId(), session);
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                forget(session.getId());
                super.afterConnectionClosed(session, status);
            }
        };
    }

    /**
     * 이 토큰으로 CONNECT 해도 되는가 — 로그인 해제 시각 이전에 발급된 토큰은 안 된다.
     * Redis 를 읽지 못하면 막지 않는다 — 만료 묶기가 여전히 상한(최대 15분)을 건다. Redis 장애로 채팅 전체를 멈추지 않는다.
     */
    public boolean allowed(Long userId, long issuedAtEpochSec) {
        try {
            return SessionRevocation.allowed(redis.opsForValue().get(SessionRevocation.cutoffKey(userId)), issuedAtEpochSec);
        } catch (RuntimeException e) {
            log.warn("로그인 해제 표시를 읽지 못했다 — 만료 묶기만으로 둔다: {}", e.getClass().getSimpleName());
            return true;
        }
    }

    /** CONNECT 가 통과했다 — 이 세션의 주인과 토큰 만료를 적고, 만료에 닫기를 예약한다. */
    public void bind(String sessionId, Long userId, long expiresAtEpochMs) {
        if (sessionId == null) {
            return;
        }
        owners.put(sessionId, userId);
        long delay = Math.max(0, expiresAtEpochMs - System.currentTimeMillis());
        ScheduledFuture<?> previous = deadlines.put(sessionId,
                timer.schedule(() -> close(sessionId, "token expired"), delay, TimeUnit.MILLISECONDS));
        if (previous != null) {
            previous.cancel(false);
        }
    }

    /** 로그인 해제 — 이 사용자의 연결을 모두 닫는다. */
    public int closeUser(Long userId) {
        int closed = 0;
        for (Map.Entry<String, Long> e : owners.entrySet()) {
            if (userId.equals(e.getValue()) && close(e.getKey(), "session revoked")) {
                closed++;
            }
        }
        if (closed > 0) {
            log.info("[auth] 로그인 해제 — 채팅 연결 {}개를 닫았다. userId={}", closed, userId);
        }
        return closed;
    }

    /** 열려 있는 연결 수(관측 · 테스트). */
    public int size() {
        return sessions.size();
    }

    private boolean close(String sessionId, String reason) {
        WebSocketSession session = sessions.get(sessionId);
        forget(sessionId);
        if (session == null || !session.isOpen()) {
            return false;
        }
        try {
            session.close(new CloseStatus(SessionRevocation.CLOSE_CODE, reason));
            return true;
        } catch (IOException | RuntimeException e) {
            log.debug("연결을 닫지 못했다(이미 끊겼다): {}", e.getClass().getSimpleName());
            return false;
        }
    }

    private void forget(String sessionId) {
        sessions.remove(sessionId);
        owners.remove(sessionId);
        ScheduledFuture<?> deadline = deadlines.remove(sessionId);
        if (deadline != null) {
            deadline.cancel(false);
        }
    }

    @PreDestroy
    void shutdown() {
        timer.shutdownNow();
    }
}
