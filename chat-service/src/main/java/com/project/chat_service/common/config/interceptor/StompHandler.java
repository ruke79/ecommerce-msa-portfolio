package com.project.chat_service.common.config.interceptor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;

import com.project.chat_service.common.exception.handler.ErrorCode;
import com.project.chat_service.common.util.TokenHandler;
import com.project.chat_service.service.ChatService;

import java.util.Objects;

/**
 * STOMP 프레임의 인증 · 권한(CONNECT · SUBSCRIBE · SEND). 빈은 {@code WebSocketConfiguration.myChannelInterceptor()} 하나다.
 *
 * <p>🐛 예전: {@code @Component} 도 붙어 있어 같은 인터셉터가 빈 둘로 떴다(인터셉터로 쓰이는 것은 하나). 지웠다.
 * [JA] 以前は @Component も付いていて、同じインターセプターが Bean 二つで起動していた。
 */
@Slf4j
public class StompHandler implements ChannelInterceptor {

    // prevent circular referenece
    @Autowired
    private TokenHandler tokenHandler;
    @Autowired
    private ChatService chatService;

    /** 세션에 담는 신원 — 검증된 토큰의 uid(member_id) 하나다. 이메일은 담지 않는다(docs/48 §6-⑥). */
    public static final String SESSION_ATTR_USER_ID = "userId";
    /** 세션에 담는 역할 — 검증된 토큰의 role 클레임. 상담 데스크 권한에 쓴다(docs/58). */
    public static final String SESSION_ATTR_USER_ROLE = "userRole";

    /**
     * 구독할 수 있는 주소의 모양 — 방 하나. 🔒 기본 브로커는 구독 주소를 <b>패턴</b>으로 맞춘다(AntPathMatcher) —
     * {@code /sub/**} 를 구독하면 모든 방의 메시지가, 상담 메시지까지 온다. 와일드카드가 들어갈 수 없는 모양만
     * 받는다(docs/58).
     */
    static final java.util.regex.Pattern ROOM_DESTINATION =
            java.util.regex.Pattern.compile("^/sub/chat/room/([A-Za-z0-9-]{1,64})$");

    /**
     * 🐛 {@code @Lazy} 가 없어 chat-service 가 기동하지 못했다(2026-09-26, docs/57 §17). 순환:
     * 브로커 템플릿 → clientInboundChannel → 이 인터셉터 → SupportService → SupportDeskNotifier → 브로커 템플릿.
     * 처음 쓸 때 받는다 — STOMP 프레임을 처리할 때는 컨텍스트가 이미 다 떠 있다. {@code WebSocketWiringTest} 가 막는다.
     */
    @Autowired
    @org.springframework.context.annotation.Lazy
    private com.project.chat_service.service.SupportService supportService;
    @Autowired
    private com.project.chat_service.service.SupportPresence supportPresence;
    /** 연결을 토큰 수명 · 로그인 해제에 묶는다(docs/68 §9). */
    @Autowired
    private LiveSessionRegistry liveSessions;

    /** 세션에 담는 토큰 만료(epoch ms) — 이 뒤의 SUBSCRIBE · SEND 는 거절한다(닫기 예약이 늦어도 막는다). */
    public static final String SESSION_ATTR_EXPIRES_AT = "tokenExpiresAt";

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        StompCommand command = accessor.getCommand();
        log.debug("STOMP Command: {}", command);

        if (StompCommand.CONNECT == command) {

            String accessToken = getAccessToken(accessor);

            if (accessToken == null) {
                log.warn("STOMP CONNECT rejected: missing access token");
                throw ErrorCode.unauthorizedTokenException();
            }
            if (!tokenHandler.verifyToken(accessToken)) {
                log.warn("STOMP CONNECT rejected: invalid access token");
                throw ErrorCode.unauthorizedTokenException();
            }

            // 이후 SUBSCRIBE/SEND 프레임에서는 클라이언트가 보낸 헤더·본문을 신뢰하지 않고 CONNECT 시점에 토큰으로
            // 검증된 uid 만을 사용자 식별자로 사용한다. uid 가 없는 토큰은 게이트웨이도 401 로 막는다 — 여기서도 막는다.
            Long userId = tokenHandler.getUserId(accessToken);
            if (userId == null) {
                log.warn("STOMP CONNECT rejected: token has no uid claim");
                throw ErrorCode.unauthorizedTokenException();
            }
            // 🔒 로그인이 해제된 뒤의 옛 토큰으로는 다시 붙지 못한다(docs/68 §9) — 닫기만 하면 옛 토큰으로 곧장 다시 붙는다.
            //    [JA] ログイン解除後の古いトークンでは再接続できない — 閉じるだけでは古いトークンですぐ張り直される。
            if (!liveSessions.allowed(userId, tokenHandler.getIssuedAtEpochSec(accessToken))) {
                log.warn("STOMP CONNECT rejected: token issued before session revocation. userId={}", userId);
                throw ErrorCode.unauthorizedTokenException();
            }
            long expiresAt = tokenHandler.getExpiresAtEpochMs(accessToken);
            Objects.requireNonNull(accessor.getSessionAttributes()).put(SESSION_ATTR_USER_ID, userId);
            accessor.getSessionAttributes().put(SESSION_ATTR_EXPIRES_AT, expiresAt);
            // 🔴 연결은 토큰 만료에 닫힌다(close 4401) — 예전에는 CONNECT 뒤 끊길 때까지 살아 있었다.
            liveSessions.bind(accessor.getSessionId(), userId, expiresAt);
            String role = tokenHandler.getRole(accessToken);
            if (role != null) {
                accessor.getSessionAttributes().put(SESSION_ATTR_USER_ROLE, role);
            }

        } else if (StompCommand.SUBSCRIBE == command) {

            String userSessionId = (String) message.getHeaders().get("simpSessionId");
            String roomId = getRoomId(accessor);
            Long userId = getAuthenticatedUserId(accessor);
            requireUnexpired(accessor);

            // 상담 데스크 알림(docs/58 §9) — 상담원·관리자만. 방이 아니므로 참여자 기록도 없다.
            if (com.project.chat_service.service.SupportDeskNotifier.DESTINATION.equals(accessor.getDestination())) {
                if (!com.project.chat_service.constants.SupportRoles.isAgent(getAuthenticatedRole(accessor))) {
                    log.warn("SUBSCRIBE rejected: support desk requires agent role. userId={}", userId);
                    throw ErrorCode.unauthorizedTokenException();
                }
                return message;
            }

            if (roomId == null) {
                log.warn("SUBSCRIBE rejected: missing roomId");
                throw ErrorCode.unauthorizedTokenException();
            }

            // 🔒 권한은 헤더가 아니라 <b>실제 구독 주소</b>로 판정한다. roomId 헤더는 클라이언트가 정한다 — 헤더에는
            //    공개 방을, 주소에는 남의 상담을 적으면 헤더만 보는 검사는 통과한다.
            java.util.regex.Matcher dest = ROOM_DESTINATION.matcher(String.valueOf(accessor.getDestination()));
            if (!dest.matches()) {
                log.warn("SUBSCRIBE rejected: destination not a single room: {}", accessor.getDestination());
                throw ErrorCode.unauthorizedTokenException();
            }
            String destinationRoom = dest.group(1);
            if (com.project.chat_service.model.SupportThread.isSupportRoom(destinationRoom)
                    || com.project.chat_service.model.SupportThread.isSupportRoom(roomId)) {
                if (!destinationRoom.equals(roomId)
                        || !supportService.canSubscribe(destinationRoom, userId, getAuthenticatedRole(accessor))) {
                    log.warn("SUBSCRIBE rejected: support room not accessible. room={}, userId={}", destinationRoom, userId);
                    throw ErrorCode.unauthorizedTokenException();
                }
                // 상담은 친구 채팅방의 참여자 표(participant_chat_room)에 적지 않는다 — 방 행이 없다.
                // 대신 "보고 있다" 를 적는다 — 보고 있는 고객에게는 답변 알림을 보내지 않는다(docs/58 §10).
                supportPresence.subscribed(userSessionId, accessor.getSubscriptionId(), userId, destinationRoom);
                return message;
            }

            log.info("Subscribe : room={}, session={}", roomId, userSessionId);

            // 🐛 <b>여기서 던진 예외가 구독 자체를 죽였다.</b> 클라이언트에는 STOMP ERROR 의
            //    "Failed to send message to ExecutorSubscribableChannel[clientInboundChannel]"
            //    만 보이고, 그 방은 영영 못 들어간다. 그런데 참여자 등록은 <b>기록</b>이지
            //    권한 검사가 아니다 — 구독 자체는 누구에게도 막혀 있지 않다. 기록에 실패하면
            //    남기고 넘어간다. 진짜 원인은 아래 로그(예외 타입·메시지)에 남는다.
            try {
                chatService.connect(roomId, userSessionId, userId);
            } catch (RuntimeException e) {
                log.error("참여자 등록 실패 — 구독은 그대로 진행한다. room={}, session={}",
                        roomId, userSessionId, e);
            }

        } else if (StompCommand.SEND == command) {

            // 🔒 클라이언트는 애플리케이션 주소(/pub/**)로만 보낸다. 브로커 주소(/sub/**)로 보낸 SEND 는 기본 브로커가
            //    <b>그대로 구독자에게 배달한다</b> — 컨트롤러의 발신자 덮어쓰기·상담 권한 검사를 건너뛰고 아무 방(남의
            //    상담 포함)에나 임의의 발신자·내용을 넣을 수 있었다(docs/58 §9).
            getAuthenticatedUserId(accessor);
            requireUnexpired(accessor);
            String destination = accessor.getDestination();
            if (destination == null || !destination.startsWith("/pub/")) {
                log.warn("SEND rejected: not an application destination: {}", destination);
                throw ErrorCode.unauthorizedTokenException();
            }

        } else if (StompCommand.UNSUBSCRIBE == command) {

            supportPresence.unsubscribed((String) message.getHeaders().get("simpSessionId"), accessor.getSubscriptionId());

        } else if (StompCommand.DISCONNECT == command) {

            String userSessionId = (String) message.getHeaders().get("simpSessionId");
            log.info("Disconnect : session={}", userSessionId);
            supportPresence.disconnected(userSessionId);
            chatService.disconnect(userSessionId);
        }

        return message;
    }

    /**
     * CONNECT 시점에 토큰 검증 후 세션에 저장해 둔 인증된 사용자 id 를 반환한다.
     * 클라이언트가 SUBSCRIBE/SEND 프레임에 실어 보내는 헤더/바디 값은 위조 가능하므로 사용하지 않는다.
     */
    private Long getAuthenticatedUserId(StompHeaderAccessor accessor) {
        Object userId = accessor.getSessionAttributes() == null
                ? null
                : accessor.getSessionAttributes().get(SESSION_ATTR_USER_ID);
        if (!(userId instanceof Long id)) {
            log.warn("Rejected STOMP frame with no authenticated session identity");
            throw ErrorCode.unauthorizedTokenException();
        }
        return id;
    }

    /**
     * CONNECT 때 적어 둔 토큰 만료가 지났으면 거절한다. 닫기 예약(LiveSessionRegistry)이 주된 장치이고 이것은 두 번째 겹이다 —
     * 예약이 몇 ms 늦어도 만료된 토큰으로 구독 · 전송하지 못한다. 만료가 없으면(CONNECT 를 거치지 않은 세션) 거절한다.
     */
    private void requireUnexpired(StompHeaderAccessor accessor) {
        Object expiresAt = accessor.getSessionAttributes() == null
                ? null
                : accessor.getSessionAttributes().get(SESSION_ATTR_EXPIRES_AT);
        if (!(expiresAt instanceof Long at) || at <= System.currentTimeMillis()) {
            log.warn("Rejected STOMP frame: token expired or unknown");
            throw ErrorCode.unauthorizedTokenException();
        }
    }

    private String getAuthenticatedRole(StompHeaderAccessor accessor) {
        Object role = accessor.getSessionAttributes() == null
                ? null
                : accessor.getSessionAttributes().get(SESSION_ATTR_USER_ROLE);
        return role instanceof String r ? r : null;
    }

    private String getAccessToken(StompHeaderAccessor accessor) {
        return accessor.getFirstNativeHeader("Authorization");
    }

    private String getRoomId(StompHeaderAccessor accessor) {
        return accessor.getFirstNativeHeader("roomId");
    }

}
