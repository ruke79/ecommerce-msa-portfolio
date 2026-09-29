package com.project.chat_service.controller;

import java.util.ArrayList;
import java.util.List;

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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;


@Log4j2
@RequiredArgsConstructor
@RestController // RestContro
@RequestMapping("/api/chat")
public class ChatApiController {


        private final TokenHandler tokenHandler;
    private final ChatService chatService;

      @PostMapping("/room")
    @ResponseBody
    public ChatRoomDto CreateChatRoom(@RequestBody ChatRoomRequest chatroomReqest, HttpServletRequest request, HttpServletResponse response) throws Exception {

        // userId 도 함께 넘긴다. 이 사용자의 chat_member 행이 아직 없으면(가입 이벤트가
        // 도착하지 않았거나 chat-service 배포 전에 가입한 사용자) 서비스가 즉석에서
        // 만드는데, 그때 member_id 로 user-service 의 userId 가 필요하다.
        // 예전에는 회원 행이 없으면 NPE 로 500 이 났다.
        ChatRoomDto dto = chatService.createChatRoom(chatroomReqest.getRoomName(), getMemberId(request));

        return dto;
    }
    
    @GetMapping("/rooms")
    @ResponseBody
    public List<ChatRoomDto> room(){
        List<ChatRoom> allRoom = chatService.fildAllRooms();
        log.info(allRoom.size());
        List<ChatRoomDto> all = new ArrayList<>();
        for (ChatRoom chatRoom : allRoom) {
            ChatRoomDto chatRoomDto = ChatRoomDto.builder()
                    .name(chatRoom.getRoomName())
                    .roomId(chatRoom.getRoomUid())                    
                    .build();
            
            all.add(chatRoomDto);
        }
        
        return all;
    }

     @GetMapping("/room/{roomId}")
    public ResponseEntity<List<MessageDto>> getRoomMessages(@PathVariable("roomId") String roomId,
                @RequestParam(name = "cursor") String cursor,
                HttpServletRequest request) {
        // 방 목록(GET /rooms)이 모든 인증 사용자에게 공개되는 공개 채팅방 모델이므로,
        // 여기서는 "유효한 인증"만 요구하고 방 참여 이력은 별도로 강제하지 않는다.
        getMemberId(request);
        // 🔒 상담 기록은 여기로 읽을 수 없다 — 이 경로는 방 참여를 확인하지 않는다(공개 방 전제). 상담은
        //    본인·상담원만 보는 /api/chat/support/threads/{id}/messages 로 읽는다(docs/58).
        if (com.project.chat_service.model.SupportThread.isSupportRoom(roomId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        log.info("cursor: {}", cursor);

        return ResponseEntity.status(HttpStatus.OK)
			.body(chatService.getRoomMessagesByCurser(roomId, cursor));

    }


    /** 검증된 토큰의 uid 클레임 — 신원이자 chat_member.member_id. 없으면 401(docs/48 §6-⑥). */
    private Long getMemberId(HttpServletRequest request) {
        Long memberId = tokenHandler.resolveVerifiedUserId(request.getHeader("Authorization"));
        if (memberId == null) {
            throw com.project.chat_service.common.exception.handler.ErrorCode.unauthorizedTokenException();
        }
        return memberId;
    }


}
