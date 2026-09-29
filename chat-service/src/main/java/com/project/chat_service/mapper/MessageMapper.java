package com.project.chat_service.mapper;

import com.project.chat_service.dto.MessageDto;
import com.project.chat_service.model.ChatMessage;

import org.springframework.stereotype.Component;

@Component
public class MessageMapper {

    /** createdAt(문자열 epoch millis)을 안전하게 파싱한다. 값이 없거나 형식이 어긋나면 0. */
    private static long parseCreatedAt(String createdAt) {
        if (createdAt == null || createdAt.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(createdAt.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * MessageDto를 ChatMessage 엔티티로 변환합니다.
     *
     * @param dto 변환할 MessageDto 객체
     * @return ChatMessage 엔티티 객체
     */
    static public ChatMessage toChatMessage(MessageDto dto) {
        if (dto == null) {
            return null;
        }

        // ChatMessage.get 의 시그니처는 (type, nickname, email, roomId, ...) 인데
        // 예전에는 (type, roomId, email, nickname, ...) 순서로 넘겨 **roomId 와 nickname 이
        // 뒤바뀐 채 저장**됐다(docs/16 B-24). 그대로 저장되면 메시지의 roomId 가 사용자 닉네임이
        // 되어 findByRoomId 로 영영 조회되지 않는다. 인자 순서를 시그니처에 맞춘다.
        // 발신자는 id 로만 저장한다 — 이메일은 몽고에 남기지 않는다(docs/48 §6-⑥).
        ChatMessage message = ChatMessage.get(
            dto.getType(),
            dto.getNickname(),
            dto.getSenderId(),
            dto.getRoomId(),
            dto.getContent(),
            dto.getImageUrl(),
            dto.getReadCount(),
            Long.toString(dto.getCreatedAt())
        );

        // 생산자(ChatService.sendMessage)가 발행 전에 부여한 시퀀스 id 를 그대로 문서 _id 로 쓴다.
        // 이걸 빠뜨리면 MongoDB 가 매 저장마다 새 ObjectId 를 만들어, Kafka 재전달 때
        // **같은 메시지가 여러 건 저장**된다. id 를 실으면 save() 가 upsert 가 되어
        // 재전달이 몇 번 오든 문서는 한 건으로 수렴한다 — 별도 멱등 원장이 필요 없다.
        message.setId(dto.getId());
        return message;
    }
    
    /**
     * ChatMessage 엔티티를 MessageDto로 변환합니다.
     *
     * @param chat 변환할 ChatMessage 엔티티 객체
     * @return MessageDto 객체
     */
    static public MessageDto fromChatMessage(ChatMessage chat) {
        if (chat == null) {
            return null;
        }

        return MessageDto.builder()
            .id(chat.getId())
            .type(chat.getType())
            .roomId(chat.getRoomId())
            .senderId(chat.getSenderId())
            .nickname(chat.getNickname())
            .content(chat.getContent())
            .imageUrl(chat.getImageUrl())
            .readCount(chat.getReadCount())
            // MongoDB 는 스키마리스라 createdAt 이 비어 있거나 숫자가 아닌 문서가 섞일 수 있다.
            // 무방비 Long.parseLong 이라 **그런 문서 하나가 방 전체의 대화 이력 조회를 500 으로**
            // 만들었다(docs/16 B-24, 실제 재현). 파싱 실패는 그 메시지 하나만 0 으로 떨어뜨린다.
            .createdAt(parseCreatedAt(chat.getCreatedAt()))
            .build();
    }
}