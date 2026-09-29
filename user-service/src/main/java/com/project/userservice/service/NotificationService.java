package com.project.userservice.service;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.userservice.model.Notification;
import com.project.userservice.model.User;
import com.project.userservice.repository.jpa.NotificationRepository;
import com.project.userservice.security.jwt.JwtUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * SSE 구독과 알림 발송.
 *
 * <p><b>⚠️ 클래스 레벨 {@code @Transactional} 을 달지 말 것.</b> 예전에는 달려 있었고,
 * 그래서 {@link #createEmitter}까지 트랜잭션 안에서 돌았다. SSE 요청은 <b>끝나지 않는
 * 요청</b>이라, 그 트랜잭션이 잡은 커넥션이 연결이 살아 있는 내내 반납되지 않았다.
 *
 * <p>풀 크기가 작으면 즉시 장애가 된다. 실측: SSE 연결 3개 = HikariPool
 * {@code total=3, active=3, idle=0} → 그 뒤 <b>모든</b> DB 요청이
 * "Could not open JPA EntityManager for transaction" 로 실패했다. 로그인조차 안 됐다.
 * 증상("로그인이 안 된다")이 원인("SSE 가 커넥션을 물고 있다")에서 한참 멀다.
 *
 * <p>게다가 이 누수는 오래 잠복해 있었다 — 게이트웨이에 SSE 라우트가 잘못 적혀 있어
 * ({@code /user-service/sse} vs 실제 {@code /api/sse}) 이 메서드에 <b>도달조차 못 했기</b>
 * 때문이다. 그 라우트를 고친 배포에서 곧바로 터졌다.
 *
 * <p>그래서 트랜잭션은 <b>DB 를 실제로 만지는 메서드에만</b> 단다. 여기서는
 * {@link #sendNotification} 하나뿐이고, 그것도 짧게 끝난다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    
    private final Map<String, SseEmitter> userEmitters = new ConcurrentHashMap<>();
    // 예전에는 여기서 Executors.newSingleThreadExecutor() 를 직접 만들었다 — 스레드 하나가
    // 접속한 모든 브라우저를 담당하고, 큐는 무제한이고, 종료 훅도 없었다. AsyncSseConfig 참조.
    private final SseDispatcher sseDispatcher;

    private final NotificationRepository notificationRepository;

    private final JwtUtils jwtUtils;

    /** 로그인 해제 표시(common SessionRevocation)를 읽는다 — 문자열로 적히므로 문자열 템플릿으로. */
    private final org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    /** 세션 → 사용자. 로그인 해제 알림은 사용자 단위로 온다(docs/68 §9). */
    private final Map<String, Long> sessionUsers = new ConcurrentHashMap<>();

    /**
     * SSE 구독의 수명. 톰캣 기본(30초)은 너무 짧다 — 그 주기로 끊겼다 붙기를 반복한다.
     *
     * <p>무한(0)으로 두지 않는 이유: 어떤 이유로든 정리되지 않은 구독이 영원히 남는 것보다,
     * 넉넉한 상한을 두고 클라이언트가 조용히 재연결하게 두는 편이 안전하다. EventSource 는
     * 스트림이 닫히면 알아서 다시 붙는다.
     */
    @Value("${app.sse.timeout-ms:1800000}")
    private long sseTimeoutMs;

    /**
     * SSE 구독을 연다.
     *
     * <p><b>⚠️ 세션 id 는 반드시 지역 변수여야 한다.</b> 예전에는 이게
     * {@code private String sessionId} 인스턴스 필드였다. Spring 빈은 싱글턴이므로 그 필드는
     * <b>모든 요청이 공유</b>했고, 두 가지가 깨졌다:
     *
     * <ul>
     *   <li><b>엉뚱한 사람이 로그아웃됐다.</b> B 가 SSE 에 붙어 필드가 B 의 세션이 된 직후
     *       A 가 재로그인하면, A 의 이전 세션을 끊으려는 알림이 <b>B 에게</b> 갔다.</li>
     *   <li><b>emitter 맵이 오염됐다.</b> 아래 세 람다가 필드를 <b>실행 시점에</b> 읽으므로,
     *       연결이 끊길 때 자기 것이 아닌 키를 지웠다 — 살아 있는 구독이 맵에서 사라지고
     *       (알림이 영영 안 감) 죽은 구독은 남았다(누수).</li>
     * </ul>
     *
     * <p>지역 변수로 두면 람다가 그 값을 캡처하므로 둘 다 사라진다.
     */
    public SseEmitter createEmitter(String token) {

        final String sessionId = jwtUtils.getSessionIdFromJwtToken(token);
        final JwtUtils.LiveClaims live = jwtUtils.liveClaimsOf(token);

        // 🔒 로그인이 해제된 뒤의 옛 토큰으로는 다시 붙지 못한다(docs/68 §9). 닫기만 하면 옛 토큰으로 곧장 다시 붙는다.
        //    거절 대신 "Reauth" 를 한 번 보내고 닫는다 — 프론트는 토큰을 갱신하고, 로그인이 끊겼다면 갱신이 실패해 로그인 화면으로 간다.
        if (live.userId() != null && !com.project.common.security.SessionRevocation.allowed(
                stringRedisTemplate.opsForValue().get(com.project.common.security.SessionRevocation.cutoffKey(live.userId())),
                live.issuedAtEpochSec())) {
            SseEmitter refused = new SseEmitter(1_000L);
            try {
                refused.send(SseEmitter.event().name("notification").data(com.project.common.security.SessionRevocation.SSE_REAUTH));
            } catch (IOException ignored) {
                // 끊긴 브라우저 — 보낼 곳이 없다
            }
            refused.complete();
            return refused;
        }

        // ⚠️ 인자 없는 `new SseEmitter()` 는 **컨테이너 기본 비동기 타임아웃**을 쓴다.
        //    톰캣 기본값이 30초라, 30초마다 AsyncRequestTimeoutException 으로 스트림이 닫히고
        //    브라우저에는 원인 없는 오류로 나타났다. 실측으로 확인한 증상이다.
        //
        // 🔴 스트림은 <b>토큰 만료</b>를 넘기지 않는다(docs/68 §9) — 예전에는 30분이라, 로그인이 끊긴 뒤에도 최대 30분 알림을 받았다.
        //    프론트는 만료 10초 전에 갱신한 토큰으로 이미 새로 붙어 있으므로(Notification.js) 사용자에게는 보이지 않는다.
        //    [JA] ストリームはトークンの有効期限を超えない。フロントは期限 10 秒前に更新したトークンで既に張り直している。
        long untilExpiry = Math.max(1_000L, live.expiresAtEpochMs() - System.currentTimeMillis());
        SseEmitter emitter = new SseEmitter(Math.min(sseTimeoutMs, untilExpiry));
        userEmitters.put(sessionId, emitter);
        if (live.userId() != null) {
            sessionUsers.put(sessionId, live.userId());
        }

        // 람다가 **이 호출의** sessionId 를 캡처한다(위 주석 참조).
        emitter.onCompletion(() -> forget(sessionId, emitter));
        emitter.onTimeout(() -> forget(sessionId, emitter));
        emitter.onError((e) -> forget(sessionId, emitter));

           // SSE 연결이 성공적으로 이루어졌음을 알리는 초기 이벤트 전송
        try {
            emitter.send(SseEmitter.event().name("init").data("connection established"));
            log.debug("SSE 구독 시작");
        } catch (IOException e) {
            log.error("Failed to send initial SSE event: {}", e.getMessage());
            emitter.completeWithError(e); // 예외 발생 시 Emitter 종료
        }
        
        return emitter;
    }
    
    
    /**
     * 특정 <b>세션</b>에 알림을 보낸다.
     *
     * <p>대상 세션을 인자로 받는 이유: 배달은 {@code userEmitters.get(sessionId)} 로 이뤄진다.
     * 예전에는 이 값을 공유 필드에서 읽어서, 같은 계정의 여러 세션 중 <b>어느 것에 갈지가
     * 마지막 SSE 구독자에 따라 결정됐다</b>(다른 사용자의 세션일 수도 있었다).
     *
     * <p>다중 로그인 차단이 대표적인 호출자다. "이전 세션을 끊는다"는 <b>그 이전 세션 id 를
     * 알고 있을 때만</b> 성립한다 — 계정만으로는 어느 것을 끊어야 할지 정할 수 없다.
     *
     * @param userId    알림 소유자(감사·조회용)
     * @param sessionId <b>배달 대상</b> 세션. null 이면 보내지 않는다
     */
    @Transactional
    public void sendNotification(String userId, String sessionId, String message) {
        if (sessionId == null || sessionId.isBlank()) {
            // 대상이 없으면 조용히 아무에게나 보내는 것보다 안 보내는 편이 낫다.
            log.warn("알림 대상 세션이 없어 보내지 않는다: userId={}, message={}", userId, message);
            return;
        }
        Notification notification = new Notification(message, userId, sessionId);
        Notification savedNotification = notificationRepository.save(notification);
        publishNotification(savedNotification);
    }
    
    private void publishNotification(Notification notification) {
        Long num = redisTemplate.convertAndSend("sse", notification);
        // 구독자 수만 남긴다 — 세션 id 는 INFO 로 남기지 않는다.
        log.debug("SSE 발행: 수신 구독자={}", num);
    }
    
    /**
     * 열려 있는 모든 구독에 하트비트를 보낸다.
     *
     * <p>SSE 는 보낼 것이 없으면 아무것도 흐르지 않고, 중간의 프록시는 그걸 유휴 연결로 보고
     * 끊는다(Cloudflare 약 100초). 타임아웃만 늘리면 이번엔 거기서 끊긴다 — 둘 다 필요하다.
     *
     * <p>전송은 {@code sseExecutor} 로 넘긴다. 느린 클라이언트 하나가 스케줄러 스레드를
     * 붙잡으면 나머지 하트비트가 전부 밀린다.
     */
    @Scheduled(fixedDelayString = "${app.sse.heartbeat-ms:20000}")
    public void heartbeat() {
        if (userEmitters.isEmpty()) {
            return;
        }
        // 맵의 순서는 (세션, emitter) 인데 heartbeat 의 인자는 (emitter, 세션)이라
        // 메서드 참조로는 맞지 않는다.
        userEmitters.forEach((sessionId, emitter) -> sseDispatcher.heartbeat(emitter, sessionId));
    }

    private void sendRealTimeNotification(Notification notification) {
        SseEmitter emitter = userEmitters.get(notification.getSessionId());
        if (emitter != null) {
            sseDispatcher.send(emitter, notification.getSessionId(), notification.getMessage());
        }
    }
    
    /** 이 emitter 가 아직 그 세션의 것일 때만 지운다 — 같은 세션이 새로 붙은 뒤 옛 emitter 의 콜백이 새 것을 지우지 않게. */
    private void forget(String sessionId, SseEmitter emitter) {
        if (userEmitters.remove(sessionId, emitter)) {
            sessionUsers.remove(sessionId);
        }
    }

    /**
     * 로그인 해제 — 이 사용자의 알림 스트림을 지금 닫는다(docs/68 §9). "Reauth" 를 보내면 프론트가 토큰을 갱신한다:
     * 로그인이 살아 있는 기기는 조용히 다시 붙고, 끊긴 기기는 로그인 화면으로 간다.
     */
    // [JA] ログイン解除 — このユーザーの通知ストリームを今すぐ閉じる。"Reauth" を受けたフロントはトークンを更新する。
    void closeUserStreams(Long userId) {
        int closed = 0;
        for (Map.Entry<String, Long> e : sessionUsers.entrySet()) {
            if (!userId.equals(e.getValue())) {
                continue;
            }
            SseEmitter emitter = userEmitters.get(e.getKey());
            if (emitter == null) {
                continue;
            }
            try {
                emitter.send(SseEmitter.event().name("notification").data(com.project.common.security.SessionRevocation.SSE_REAUTH));
            } catch (IOException | IllegalStateException ignored) {
                // 이미 끊긴 브라우저
            }
            emitter.complete();
            forget(e.getKey(), emitter);
            closed++;
        }
        if (closed > 0) {
            log.info("[auth] 로그인 해제 — 알림 스트림 {}개를 닫았다. userId={}", closed, userId);
        }
    }

    @Bean
    RedisMessageListenerContainer redisContainer(RedisConnectionFactory redisConnectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(redisConnectionFactory);
        container.addMessageListener((message, pattern) -> {
            String body = redisTemplate.getStringSerializer().deserialize(message.getBody());
            Notification notification = null;
            try {
                notification = objectMapper.readValue(body, Notification.class);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
            
            assert notification != null;
            
            sendRealTimeNotification(notification);
        }, new PatternTopic("sse"));

        // 로그인 해제(docs/68 §9) — 본문은 사용자 id 하나. 모르는 값이면 무시한다(컨테이너를 죽이지 않는다).
        container.addMessageListener((message, pattern) -> {
            try {
                closeUserStreams(Long.parseLong(new String(message.getBody(),
                        java.nio.charset.StandardCharsets.UTF_8).replace("\"", "").trim()));
            } catch (RuntimeException e) {
                log.warn("로그인 해제 알림을 처리하지 못했다: {}", e.getClass().getSimpleName());
            }
        }, new org.springframework.data.redis.listener.ChannelTopic(com.project.common.security.SessionRevocation.CHANNEL));

        return container;
    }
}


