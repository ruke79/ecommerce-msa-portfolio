package com.project.chat_service.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.chat_service.dto.MessageDto;
import com.project.chat_service.model.ChatMessage;
import com.project.common.constants.chat.MessageType;

/**
 * MessageMapper 회귀 테스트.
 *
 * <p>여기서 고정하는 두 동작은 모두 실제 MongoDB 를 붙여 보고서야 드러난 결함이다(docs/16 B-24).
 * 둘 다 "컴파일도 되고 단위 테스트도 없던" 상태였다.
 */
class MessageMapperTest {

    private MessageDto sampleDto(Long createdAt) {
        return MessageDto.builder()
                .id("msg-1")
                .type(MessageType.TEXT_TALK)
                .roomId("room-42")
                .senderId(7L)
                .nickname("테스터")
                .content("안녕하세요")
                .imageUrl("")
                .readCount(0)
                .createdAt(createdAt)
                .build();
    }

    @Test
    @DisplayName("toChatMessage 는 roomId 와 nickname 을 각자 제자리에 넣어야 한다")
    void toChatMessage_shouldNotSwapRoomIdAndNickname() {
        // 과거 버그: ChatMessage.get(type, nickname, email, roomId, ...) 시그니처에
        // (type, roomId, email, nickname, ...) 순서로 넘겨 두 값이 뒤바뀌어 저장됐다.
        // 그 상태로 저장되면 메시지의 roomId 가 닉네임이 되어 findByRoomId 로 영영 조회되지 않는다.
        ChatMessage entity = MessageMapper.toChatMessage(sampleDto(1785196000000L));

        assertThat(entity.getRoomId()).isEqualTo("room-42");
        assertThat(entity.getNickname()).isEqualTo("테스터");
        assertThat(entity.getSenderId()).isEqualTo(7L);
        assertThat(entity.getContent()).isEqualTo("안녕하세요");
        assertThat(entity.getType()).isEqualTo(MessageType.TEXT_TALK);
    }

    @Test
    @DisplayName("toChatMessage → fromChatMessage 왕복에서 값이 보존되어야 한다")
    void roundTrip_shouldPreserveFields() {
        MessageDto original = sampleDto(1785196000000L);

        MessageDto result = MessageMapper.fromChatMessage(MessageMapper.toChatMessage(original));

        assertThat(result.getRoomId()).isEqualTo(original.getRoomId());
        assertThat(result.getNickname()).isEqualTo(original.getNickname());
        assertThat(result.getSenderId()).isEqualTo(original.getSenderId());
        assertThat(result.getContent()).isEqualTo(original.getContent());
        assertThat(result.getCreatedAt()).isEqualTo(original.getCreatedAt());
    }

    @Test
    @DisplayName("createdAt 이 없는 문서 하나가 방 전체 조회를 깨뜨리면 안 된다")
    void fromChatMessage_shouldTolerateMissingCreatedAt() {
        // MongoDB 는 스키마리스라 createdAt 이 빠진 문서가 섞일 수 있다. 과거에는 무방비
        // Long.parseLong 이라 그런 문서 1건이 방 전체 이력 조회를 HTTP 500 으로 만들었다.
        ChatMessage noTimestamp = ChatMessage.get(
                MessageType.TEXT_TALK, "테스터", 7L, "room-42",
                "타임스탬프 없음", "", 0, null);

        MessageDto dto = MessageMapper.fromChatMessage(noTimestamp);

        assertThat(dto).isNotNull();
        assertThat(dto.getCreatedAt()).isZero();
        assertThat(dto.getContent()).isEqualTo("타임스탬프 없음");
    }

    @Test
    @DisplayName("createdAt 이 숫자가 아니어도 예외를 던지지 않는다")
    void fromChatMessage_shouldTolerateNonNumericCreatedAt() {
        ChatMessage malformed = ChatMessage.get(
                MessageType.TEXT_TALK, "테스터", 7L, "room-42",
                "형식 오류", "", 0, "Mon Jul 27 23:00:03 UTC 2026");

        assertThat(MessageMapper.fromChatMessage(malformed).getCreatedAt()).isZero();
    }

    /** 🔒 몽고 문서·DTO 어디에도 이메일 필드가 없다 — 다시 생기면 저장·전송 경로로 샌다(docs/48 §6-⑥). */
    @Test
    @DisplayName("🔒 ChatMessage·MessageDto 에 email 필드가 없다")
    void noEmailFieldAnywhere() {
        assertThat(java.util.Arrays.stream(ChatMessage.class.getDeclaredFields()).map(java.lang.reflect.Field::getName))
                .doesNotContain("email");
        assertThat(java.util.Arrays.stream(MessageDto.class.getDeclaredFields()).map(java.lang.reflect.Field::getName))
                .doesNotContain("email");
    }

    @Test
    @DisplayName("null 입력은 null 을 돌려준다")
    void nullInputs() {
        assertThat(MessageMapper.toChatMessage(null)).isNull();
        assertThat(MessageMapper.fromChatMessage(null)).isNull();
    }
}
