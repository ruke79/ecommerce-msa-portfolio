package com.project.userservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.userservice.model.Notification;
import com.project.userservice.repository.jpa.NotificationRepository;
import com.project.userservice.security.jwt.JwtUtils;

/**
 * SSE 구독이 <b>세션별로</b> 격리되는가.
 *
 * <p>이 테스트가 존재하는 이유: {@code NotificationService} 가 세션 id 를
 * {@code private String sessionId} <b>인스턴스 필드</b>에 들고 있었다. Spring 빈은
 * 싱글턴이라 그 필드는 모든 요청이 공유했고, 두 가지가 깨졌다.
 *
 * <ul>
 *   <li>다중 로그인 차단이 <b>엉뚱한 사람을 로그아웃시켰다</b> — B 가 SSE 에 붙은 직후
 *       A 가 재로그인하면 A 의 이전 세션을 끊으려던 알림이 B 에게 갔다.</li>
 *   <li>emitter 맵이 오염됐다 — 정리 람다가 필드를 실행 시점에 읽어 <b>남의 키</b>를 지웠다.</li>
 * </ul>
 *
 * <p>둘 다 레플리카가 하나여도, 사용자가 둘만 있어도 재현된다. 그런데 증상은 "가끔 로그아웃이
 * 된다"라서 원인에 도달하기 어렵다.
 */
class NotificationSessionRoutingTest {

    private final RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
    private final SseDispatcher dispatcher = mock(SseDispatcher.class);
    private final NotificationRepository repository = mock(NotificationRepository.class);
    private final JwtUtils jwtUtils = mock(JwtUtils.class);
    private final org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate =
            mock(org.springframework.data.redis.core.StringRedisTemplate.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);

    private final NotificationService service = new NotificationService(
            redisTemplate, new ObjectMapper(), dispatcher, repository, jwtUtils, stringRedisTemplate);

    /** 토큰의 사용자 · 발급 · 만료 — 기본은 사용자 7, 방금 발급, 한 시간 뒤 만료(30분 타임아웃보다 길다). */
    @org.junit.jupiter.api.BeforeEach
    void liveClaims() {
        long now = System.currentTimeMillis();
        when(jwtUtils.liveClaimsOf(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new JwtUtils.LiveClaims(7L, now / 1000, now + 3_600_000));
    }

    @SuppressWarnings("unchecked")
    private Map<String, SseEmitter> emitters() {
        return (Map<String, SseEmitter>) ReflectionTestUtils.getField(service, "userEmitters");
    }

    @Test
    @DisplayName("구독이 둘이면 emitter 가 세션별로 따로 등록된다 — 나중 구독이 앞 것을 덮지 않는다")
    void eachSubscriptionKeepsItsOwnSession() {
        when(jwtUtils.getSessionIdFromJwtToken("token-A")).thenReturn("session-A");
        when(jwtUtils.getSessionIdFromJwtToken("token-B")).thenReturn("session-B");

        service.createEmitter("token-A");
        service.createEmitter("token-B");

        assertThat(emitters()).containsKeys("session-A", "session-B");
    }

    @Test
    @DisplayName("A 의 구독이 끊겨도 B 의 emitter 는 남는다 (예전에는 정리 람다가 남의 키를 지웠다)")
    void completingOneSubscriptionMustNotEvictAnother() {
        when(jwtUtils.getSessionIdFromJwtToken("token-A")).thenReturn("session-A");
        when(jwtUtils.getSessionIdFromJwtToken("token-B")).thenReturn("session-B");

        SseEmitter a = service.createEmitter("token-A");
        service.createEmitter("token-B");   // 공유 필드였다면 여기서 필드가 session-B 가 된다

        // ⚠️ emitter.complete() 는 **비동기 요청에 붙어 있을 때만** 콜백을 돌린다. 단위 테스트의
        //    맨 SseEmitter 는 플래그만 세우므로, 등록된 콜백을 직접 실행한다.
        runCompletionCallback(a);

        assertThat(emitters()).doesNotContainKey("session-A");
        assertThat(emitters())
                .as("A 가 끊겼는데 B 의 구독이 사라졌다 — 정리 람다가 공유 상태를 읽고 있다")
                .containsKey("session-B");
    }

    /** ResponseBodyEmitter 의 completionCallback 을 꺼내 실행한다(Spring 내부 필드). */
    private static void runCompletionCallback(SseEmitter emitter) {
        Object callback = ReflectionTestUtils.getField(emitter, "completionCallback");
        assertThat(callback)
                .as("Spring 의 ResponseBodyEmitter 내부 구조가 바뀌었다 — 이 테스트를 손봐야 한다")
                .isInstanceOf(Runnable.class);
        ((Runnable) callback).run();
    }

    @Test
    @DisplayName("알림은 **인자로 받은** 세션으로 간다 — 마지막 구독자에게 가지 않는다")
    void notificationGoesToTheSessionItWasAddressedTo() {
        when(jwtUtils.getSessionIdFromJwtToken("token-B")).thenReturn("session-B");
        when(repository.save(any(Notification.class))).thenAnswer(i -> i.getArgument(0));

        // B 가 **마지막으로** 구독한다. 공유 필드 시절에는 이 값이 배달 대상이 됐다.
        service.createEmitter("token-B");

        // A 의 **이전 세션**을 끊으라는 알림.
        service.sendNotification("a@example.com", "session-A-old", "Logout");

        // 저장된 알림이 올바른 세션을 가리키는지 확인한다(배달은 Redis pub/sub 을 거친다).
        org.mockito.ArgumentCaptor<Notification> saved =
                org.mockito.ArgumentCaptor.forClass(Notification.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getSessionId())
                .as("마지막으로 구독한 세션(session-B)으로 가면 **다른 사용자가** 로그아웃된다")
                .isEqualTo("session-A-old");
    }

    @Test
    @DisplayName("대상 세션이 없으면 아무에게도 보내지 않는다")
    void missingTargetSendsNothing() {
        service.sendNotification("a@example.com", null, "Logout");
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("createEmitter 는 트랜잭션 밖에서 돌아야 한다 — SSE 는 끝나지 않는 요청이다")
    void createEmitterMustNotBeTransactional() {
        // 실제 장애: 클래스 레벨 @Transactional 때문에 createEmitter 가 트랜잭션을 열었고,
        // SSE 연결이 살아 있는 내내 커넥션이 반납되지 않았다. 연결 3개로 풀이 말랐고
        // (total=3, active=3, idle=0) 그 뒤 로그인을 포함한 **모든** DB 요청이 실패했다.
        //
        // 그래서 두 가지를 못 박는다: 클래스에 @Transactional 이 없을 것, 그리고
        // createEmitter 에도 없을 것. DB 를 만지는 sendNotification 에만 있어야 한다.
        assertThat(NotificationService.class.getAnnotation(Transactional.class))
                .as("클래스 레벨 @Transactional 은 createEmitter 까지 트랜잭션에 넣는다 — "
                        + "SSE 커넥션이 풀을 말린다")
                .isNull();

        assertThat(methodOf("createEmitter", String.class).getAnnotation(Transactional.class))
                .as("createEmitter 는 쿼리를 하나도 하지 않는다. 트랜잭션은 낭비이자 누수다")
                .isNull();

        assertThat(methodOf("sendNotification", String.class, String.class, String.class)
                .getAnnotation(Transactional.class))
                .as("알림 저장은 트랜잭션이 필요하다")
                .isNotNull();
    }

    @Test
    @DisplayName("SSE 구독에 명시적 타임아웃이 붙는다 — 컨테이너 기본값(톰캣 30초)은 너무 짧다")
    void emitterGetsAnExplicitTimeout() {
        when(jwtUtils.getSessionIdFromJwtToken("token-A")).thenReturn("session-A");
        ReflectionTestUtils.setField(service, "sseTimeoutMs", 1_800_000L);

        SseEmitter emitter = service.createEmitter("token-A");

        // 인자 없는 new SseEmitter() 는 timeout 이 null 이고, 그러면 톰캣 기본 30초가 쓰인다.
        // 30초마다 AsyncRequestTimeoutException 으로 스트림이 닫혀 브라우저에 오류가 났다.
        assertThat(emitter.getTimeout())
                .as("timeout 이 null 이면 컨테이너 기본값(톰캣 30초)으로 떨어진다")
                .isEqualTo(1_800_000L);
    }

    @Test
    @DisplayName("하트비트는 열려 있는 모든 구독에 간다 — 유휴 연결은 프록시가 끊는다")
    void heartbeatReachesEverySubscription() {
        when(jwtUtils.getSessionIdFromJwtToken("token-A")).thenReturn("session-A");
        when(jwtUtils.getSessionIdFromJwtToken("token-B")).thenReturn("session-B");

        SseEmitter a = service.createEmitter("token-A");
        SseEmitter b = service.createEmitter("token-B");

        service.heartbeat();

        verify(dispatcher).heartbeat(a, "session-A");
        verify(dispatcher).heartbeat(b, "session-B");
    }

    private static java.lang.reflect.Method methodOf(String name, Class<?>... params) {
        try {
            return NotificationService.class.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("메서드 시그니처가 바뀌었다: " + name, e);
        }
    }

    // ── 긴 연결을 로그인에 묶기(docs/68 §9) ───────────────────────────────────────

    @Test
    @DisplayName("🔴 스트림은 토큰 만료를 넘기지 않는다 — 예전에는 30분이라 로그인이 끊긴 뒤에도 알림을 받았다")
    void emitterEndsAtTokenExpiry() {
        when(jwtUtils.getSessionIdFromJwtToken("token-A")).thenReturn("session-A");
        long now = System.currentTimeMillis();
        when(jwtUtils.liveClaimsOf("token-A")).thenReturn(new JwtUtils.LiveClaims(7L, now / 1000, now + 600_000));
        ReflectionTestUtils.setField(service, "sseTimeoutMs", 1_800_000L);

        SseEmitter emitter = service.createEmitter("token-A");

        assertThat(emitter.getTimeout()).isLessThanOrEqualTo(600_000L).isGreaterThan(590_000L);
    }

    @Test
    @DisplayName("🔴 로그인 해제 이전에 발급된 토큰으로는 다시 붙지 못한다 — Reauth 를 보내고 바로 닫는다")
    void tokenIssuedBeforeCutoffIsRefused() {
        when(jwtUtils.getSessionIdFromJwtToken("old-token")).thenReturn("session-old");
        long now = System.currentTimeMillis();
        when(jwtUtils.liveClaimsOf("old-token")).thenReturn(new JwtUtils.LiveClaims(7L, now / 1000 - 60, now + 600_000));
        when(stringRedisTemplate.opsForValue().get("auth:cutoff:7")).thenReturn(String.valueOf(now / 1000));

        service.createEmitter("old-token");

        assertThat(emitters()).as("거절한 스트림을 배달 대상으로 등록하면 안 된다").doesNotContainKey("session-old");
    }

    @Test
    @DisplayName("해제 뒤에 새로 발급된 토큰은 그대로 붙는다 — 로그인이 살아 있는 기기")
    void tokenIssuedAfterCutoffIsAllowed() {
        when(jwtUtils.getSessionIdFromJwtToken("new-token")).thenReturn("session-new");
        long now = System.currentTimeMillis();
        when(stringRedisTemplate.opsForValue().get("auth:cutoff:7")).thenReturn(String.valueOf(now / 1000 - 60));

        service.createEmitter("new-token");

        assertThat(emitters()).containsKey("session-new");
    }

    @Test
    @DisplayName("🔴 로그인 해제 알림이 오면 그 사용자의 스트림만 닫는다")
    void revocationClosesOnlyThatUsersStreams() {
        long now = System.currentTimeMillis();
        when(jwtUtils.getSessionIdFromJwtToken("mine")).thenReturn("session-mine");
        when(jwtUtils.getSessionIdFromJwtToken("theirs")).thenReturn("session-theirs");
        when(jwtUtils.liveClaimsOf("theirs")).thenReturn(new JwtUtils.LiveClaims(8L, now / 1000, now + 600_000));
        service.createEmitter("mine");
        service.createEmitter("theirs");

        service.closeUserStreams(7L);

        assertThat(emitters()).doesNotContainKey("session-mine").containsKey("session-theirs");
    }
}
