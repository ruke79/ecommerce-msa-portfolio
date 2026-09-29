package com.project.userservice.service;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import lombok.extern.slf4j.Slf4j;

/**
 * SSE 로 한 건 내보내는 일을 {@code sseExecutor} 풀로 넘긴다({@link com.project.userservice.config.AsyncSseConfig}).
 *
 * <p>별도 빈인 이유: {@code @Async} 는 프록시를 거쳐야 동작하므로 같은 클래스 안의 private
 * 메서드에는 붙일 수 없다. {@code NotificationService} 의 Redis 구독 콜백이 이 빈을 부른다.
 */
@Slf4j
@Component
public class SseDispatcher {

    @Async("sseExecutor")
    public void send(SseEmitter emitter, String sessionId, String message) {
        try {
            emitter.send(SseEmitter.event().name("notification").data(message));
        } catch (Exception e) {
            // 클라이언트가 이미 끊긴 경우가 대부분이다. 스택을 찍을 만한 일이 아니다.
            log.debug("SSE 전송 실패 (session={}): {}", sessionId, e.getMessage());
            terminate(emitter, sessionId, e);
        }
    }

    /**
     * 하트비트 한 번.
     *
     * <p><b>왜 필요한가.</b> SSE 는 서버가 보낼 것이 없으면 아무것도 흐르지 않는다. 중간의
     * 프록시들은 그걸 <b>유휴 연결</b>로 보고 끊는다 — Cloudflare 는 100초쯤, 보통의 LB·nginx 도
     * 수십 초다. 그러면 브라우저 쪽에는 원인 없는 오류로 나타나고, EventSource 가 재연결하면서
     * 같은 일이 반복된다.
     *
     * <p>주석 이벤트({@code :ping})를 쓴다. 명세상 클라이언트가 무시하므로 화면 코드에
     * 분기를 만들 필요가 없고, 바이트 몇 개로 연결이 살아 있음을 알린다.
     */
    @Async("sseExecutor")
    public void heartbeat(SseEmitter emitter, String sessionId) {
        try {
            emitter.send(SseEmitter.event().comment("ping"));
        } catch (Exception e) {
            log.debug("SSE 하트비트 실패 — 끊긴 연결로 본다 (session={}): {}", sessionId, e.getMessage());
            terminate(emitter, sessionId, e);
        }
    }

    /**
     * 죽은 구독을 정리한다.
     *
     * <p>{@code send()} 가 던져도 SseEmitter 는 <b>스스로 닫히지 않는다.</b> 그대로 두면
     * 맵에 영원히 남아, 알림을 보낼 때마다 실패하고 하트비트마다 또 실패한다.
     * {@code completeWithError} 가 등록된 {@code onError} 콜백을 깨워 맵에서 빼 준다.
     */
    private void terminate(SseEmitter emitter, String sessionId, Exception cause) {
        try {
            emitter.completeWithError(cause);
        } catch (Exception ignored) {
            // 이미 완료된 emitter 면 IllegalStateException 이 난다. 목적은 달성된 셈이다.
            log.trace("이미 종료된 SSE 구독 (session={})", sessionId);
        }
    }
}
