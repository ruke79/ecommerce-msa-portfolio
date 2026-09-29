package com.project.chat_service.common.config.kafka;

import org.apache.avro.message.BinaryMessageDecoder;
import org.apache.avro.message.BinaryMessageEncoder;

import com.project.chat_service.dto.MessageDto;
import com.project.common.avro.ChatMessage;
import com.project.common.constants.chat.MessageType;
import com.project.common.kafka.avro.AvroMapping;

/**
 * chat 토픽: {@link MessageDto} ↔ Avro {@link ChatMessage}.
 *
 * <p>매핑이 common 이 아니라 여기 있는 이유: DTO 가 chat-service 것이다. 스키마와 생성 클래스는
 * common 에 있어 notification 의 문자열 소비자도 같은 스키마로 읽는다({@code AvroDecoders}).
 */
public class ChatMessageAvroMapping implements AvroMapping<MessageDto, ChatMessage> {

    @Override
    public Class<MessageDto> dtoType() {
        return MessageDto.class;
    }

    @Override
    public ChatMessage toRecord(MessageDto d) {
        return ChatMessage.newBuilder()
                .setId(d.getId())
                .setType(d.getType() == null ? null : com.project.common.avro.MessageType.valueOf(d.getType().name()))
                .setRoomId(d.getRoomId())
                .setSenderId(d.getSenderId())
                .setNickname(d.getNickname())
                .setContent(d.getContent())
                .setImageUrl(d.getImageUrl())
                .setReadCount(d.getReadCount())
                .setCreatedAt(d.getCreatedAt())
                .build();
    }

    @Override
    public MessageDto toDto(ChatMessage r) {
        return MessageDto.builder()
                .id(r.getId())
                .type(r.getType() == null ? null : MessageType.valueOf(r.getType().name()))
                .roomId(r.getRoomId())
                .senderId(r.getSenderId())
                .nickname(r.getNickname())
                .content(r.getContent())
                .imageUrl(r.getImageUrl())
                .readCount(r.getReadCount())
                .createdAt(r.getCreatedAt())
                .build();
    }

    @Override
    public BinaryMessageEncoder<ChatMessage> encoder() {
        return ChatMessage.getEncoder();
    }

    @Override
    public BinaryMessageDecoder<ChatMessage> decoder() {
        // 옛 스키마(v1·v2)로 쓴 레코드도 읽는다 — 배포 도중 옛 파드가 쓴 것(AvroSchemaHistory). v1 은 발신자가 이메일이라
        // 그 몇 건은 senderId 없이(발신자 모름) 읽힌다 — 이메일은 v3 에 필드가 없어 버려진다.
        return ChatMessage.createDecoder(com.project.common.kafka.avro.AvroSchemaHistory.store(
                com.project.common.kafka.avro.AvroSchemaHistory.CHAT_MESSAGE));
    }
}
