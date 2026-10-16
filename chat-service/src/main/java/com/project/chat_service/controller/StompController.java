package com.project.chat_service.controller;


import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import com.project.chat_service.common.exception.handler.ErrorCode;
import com.project.chat_service.common.util.TokenHandler;
import com.project.chat_service.dto.MessageDto;
import com.project.chat_service.model.ChatRoom;
import com.project.chat_service.service.ChatService;
import com.project.common.constants.chat.MessageType;
import com.project.common.dto.chat.ChatRoomDto;
import com.project.common.dto.chat.request.ChatRoomRequest;


import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import java.util.Objects;

@Log4j2
@RequiredArgsConstructor
@Controller
public class StompController {


    private final ChatService chatService;
    private final com.project.chat_service.service.SupportService supportService;


    /**
     * 메시지 보내기. 🔒 본문은 1 ~ {@value MessageDto#MAX_CONTENT_LENGTH}자(화면의 입력 상한과 같다). 친구 채팅은 <b>있는 방</b>에만,
     * 유형은 {@code TEXT_TALK} 으로 고정하고 이미지 주소는 버린다.
     *
     * <p>🐛 예전: 본문 길이 검사가 없었다(STOMP 상한 10MB) — 아무 길이나 몽고에 저장되고 방 전체에 방송됐다. 유형은 클라이언트 값
     * 그대로라 {@code TEXT_ENTER} 로 "OO님이 입장했습니다" 같은 안내를 흉내 낼 수 있었고, 화면이 그리지도 않는 임의 이미지 주소가
     * 저장 · 전달됐다. 없는 방 id 로 보낸 것도 저장됐다.
     *
     * <p>[JA] 本文は 1 〜 1000 字。友達チャットは存在するルームだけ、種別は TEXT_TALK 固定、画像 URL は捨てる。以前は長さの検査がなく、
     * 種別はクライアントの値のままで入室案内を偽装でき、存在しないルームへの送信も保存された。
     */
    @MessageMapping("/chat/message")
    public void sendMessage(@Valid MessageDto request, SimpMessageHeaderAccessor headerAccessor) {
            authenticate(request, headerAccessor);
            if (!acceptableContent(request.getContent())) {
                log.warn("메시지 거절 — 본문이 비었거나 {}자를 넘는다: room={}, sender={}",
                        MessageDto.MAX_CONTENT_LENGTH, request.getRoomId(), request.getSenderId());
                return;
            }
            if (!com.project.chat_service.model.SupportThread.isSupportRoom(request.getRoomId())) {
                if (!chatService.roomExists(request.getRoomId())) {
                    log.warn("메시지 거절 — 없는 방: room={}, sender={}", request.getRoomId(), request.getSenderId());
                    return;
                }
                request.setType(MessageType.TEXT_TALK);
                request.setImageUrl(null);
            }
            if (com.project.chat_service.model.SupportThread.isSupportRoom(request.getRoomId())) {
                // 상담(docs/58) — 고객 본인이거나 맡은 상담원이어야 보낼 수 있다. 거절되면 보내지 않는다.
                try {
                    supportService.acceptMessage(request, role(headerAccessor));
                } catch (com.project.chat_service.common.exception.handler.CustomException e) {
                    log.warn("상담 메시지 거절: room={}, sender={}, reason={}",
                            request.getRoomId(), request.getSenderId(), e.getErrorCode());
                    return;
                }
            }
            chatService.sendMessage(request);
    }

    @MessageMapping("/chat/enter")
	public void enterChatRoom( @RequestBody MessageDto msg, SimpMessageHeaderAccessor headerAccessor) {

        authenticate(msg, headerAccessor);
        // 상담에는 "누가 입장했다" 안내가 없다 — 연결·종료 안내는 서버가 팀 명의로 낸다(SupportService).
        if (com.project.chat_service.model.SupportThread.isSupportRoom(msg.getRoomId())) {
            return;
        }
        // 없는 방의 입장 안내는 저장하지 않는다(위 sendMessage 와 같다)
        if (!chatService.roomExists(msg.getRoomId())) {
            log.warn("입장 안내 거절 — 없는 방: room={}, sender={}", msg.getRoomId(), msg.getSenderId());
            return;
        }
        chatService.sendEnterMessage(msg, headerAccessor);
    }

    @MessageMapping("/chat/leave")
	public void leaveChatRoom( @RequestBody MessageDto msg, SimpMessageHeaderAccessor headerAccessor) {

        authenticate(msg, headerAccessor);
        if (com.project.chat_service.model.SupportThread.isSupportRoom(msg.getRoomId())) {
            return;
        }
        if (!chatService.roomExists(msg.getRoomId())) {
            log.warn("퇴장 안내 거절 — 없는 방: room={}, sender={}", msg.getRoomId(), msg.getSenderId());
            return;
        }
        chatService.sendLeaveMessage(msg, headerAccessor);
    }

    static boolean acceptableContent(String content) {
        return content != null && !content.isBlank() && content.length() <= MessageDto.MAX_CONTENT_LENGTH;
    }

    private static String role(SimpMessageHeaderAccessor headerAccessor) {
        Object role = Objects.requireNonNull(headerAccessor.getSessionAttributes())
                .get(com.project.chat_service.common.config.interceptor.StompHandler.SESSION_ATTR_USER_ROLE);
        return role instanceof String r ? r : null;
    }

    /**
     * StompHandler가 CONNECT 시점에 검증된 토큰의 uid 를 세션에 저장해두므로, 클라이언트가 본문에 실어 보낸
     * 발신자(senderId)는 신뢰하지 않고 이 값으로 덮어써서 다른 사용자 명의로 메시지를 보내는 위조를 막는다. 옛 클라이언트가
     * 보낸 email 은 MessageDto 에 필드가 없어 애초에 읽히지 않는다.
     */
    static void authenticate(MessageDto msg, SimpMessageHeaderAccessor headerAccessor) {
        Object userId = Objects.requireNonNull(headerAccessor.getSessionAttributes())
                .get(com.project.chat_service.common.config.interceptor.StompHandler.SESSION_ATTR_USER_ID);
        if (!(userId instanceof Long id)) {
            throw ErrorCode.unauthorizedTokenException();
        }
        msg.setSenderId(id);
    }

}
