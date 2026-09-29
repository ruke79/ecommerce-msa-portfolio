package com.project.chat_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RedissonClient;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.project.chat_service.common.util.KafkaUtil;
import com.project.chat_service.dto.MessageDto;
import com.project.chat_service.mapper.MessageMapper;
import com.project.chat_service.message.producer.MessageProducer;
import com.project.chat_service.model.ChatMember;
import com.project.chat_service.repository.mongo.ChatMessageRepository;
import com.project.chat_service.repository.redis.ChatRoomRepository;
import com.project.chat_service.repository.jpa.ParticipantChatRoomRepository;
import com.project.common.constants.chat.MessageType;

/**
 * 메시지 발신 — 발신자의 회원 복제 행이 <b>없을 때</b>.
 *
 * <h3>🐛 회귀 대상</h3>
 *
 * <p>예전에는 {@code if (member != null)} 안에서만 id 와 {@code createdAt} 을 붙였다.
 * 회원 복제 행({@code ChatMember})은 {@code user-created} 이벤트로 채워지는데, 그 이벤트가
 * 발행되지 않던 경로가 있었다(docs/48 — 메일 인증을 끈 가입). 그런 계정이 보낸 메시지는
 * <b>id 없이, 시각 없이</b> 카프카로 나갔다. 화면은 {@code createdAt} 이 없는 메시지를
 * 그리다 죽었다("Cannot read properties of undefined (reading 'trim')").
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ChatService.sendMessage — 회원 행이 없는 발신자")
class ChatServiceSendMessageTest {

    @Mock ChatMessageRepository chatMessageRepository;
    @Mock MessageProducer messageProducer;
    @Mock ChatRoomRepository chatRoomRepository;
    @Mock com.project.chat_service.repository.jpa.ChatRoomJpaRepository chatRoomJpaRepository;
    @Mock ChatMemberService chatMemberService;
    @Mock ParticipantChatRoomRepository participantChatRoomRepository;
    @Mock SequenceGeneratorService sequenceGenerator;
    @Mock RedissonClient redissonClient;
    @Mock MongoTemplate mongoTemplate;
    @Mock MessageMapper messageMapper;

    ChatService service;

    @BeforeEach
    void setUp() {
        service = new ChatService(chatMessageRepository, messageProducer, chatRoomRepository,
                chatRoomJpaRepository, chatMemberService, participantChatRoomRepository,
                sequenceGenerator, redissonClient, mongoTemplate, messageMapper);
        when(sequenceGenerator.generateSequence(anyString())).thenReturn("42");
    }

    private static final long GHOST = 99L;
    private static final long KNOWN = 7L;

    /** 컨트롤러가 세션의 uid 로 senderId 를 채운 뒤의 모양이다(StompController.authenticate). */
    private static MessageDto outgoing(long senderId) {
        return MessageDto.builder()
                .type(MessageType.TEXT_TALK)
                .roomId("room-1")
                .senderId(senderId)
                .nickname("클라이언트가-보낸-닉네임")
                .content("안녕하세요")
                .build();
    }

    private MessageDto sent() {
        ArgumentCaptor<MessageDto> captor = ArgumentCaptor.forClass(MessageDto.class);
        verify(messageProducer).send(eq(KafkaUtil.KAFKA_TOPIC), captor.capture());
        return captor.getValue();
    }

    /** 🔴 id 와 시각은 메시지의 속성이지 회원의 속성이 아니다 — 회원 행이 없어도 붙는다. */
    @Test
    @DisplayName("🔴 회원 행이 없어도 id 와 createdAt 이 붙어서 나간다")
    void idAndTimestampDoNotDependOnMemberRow() {
        when(chatMemberService.findById(GHOST)).thenReturn(null);

        service.sendMessage(outgoing(GHOST));

        MessageDto out = sent();
        assertThat(out.getId()).isEqualTo("42");
        assertThat(out.getCreatedAt()).isNotNull().isPositive();
    }

    /**
     * 본문의 닉네임은 쓰지 않는다 — 클라이언트가 임의로 정한 값이다. 이메일에서도 만들지 않는다 — 닉네임은 상대방
     * 화면에 보이고, 로컬 파트는 대개 계정 아이디다(docs/48 §6-⑥).
     */
    @Test
    @DisplayName("🔒 회원 행이 없으면 닉네임은 대체값 — 본문 닉네임도, 이메일의 어느 부분도 아니다")
    void nicknameFallbackRevealsNothing() {
        when(chatMemberService.findById(GHOST)).thenReturn(null);

        service.sendMessage(outgoing(GHOST));

        MessageDto out = sent();
        assertThat(out.getNickname()).isEqualTo(ChatService.UNKNOWN_NICKNAME);
        assertThat(out.getNickname()).doesNotContain("ghost").doesNotContain("@");
    }

    @Test
    @DisplayName("회원 행이 있으면 그 닉네임을 쓴다 — 기존 동작 그대로")
    void memberNicknameWins() {
        when(chatMemberService.findById(KNOWN))
                .thenReturn(ChatMember.builder().nickname("알려진사람").build());

        service.sendMessage(outgoing(KNOWN));

        MessageDto out = sent();
        assertThat(out.getNickname()).isEqualTo("알려진사람");
        assertThat(out.getSenderId()).isEqualTo(KNOWN);
    }

    /** 🔒 화면으로 나가는 JSON — senderId 는 문자열(19자리 TSID 가 JS Number 에서 뭉개진다), email 은 없다. */
    @Test
    @DisplayName("🔒 브로드캐스트 JSON: senderId 는 문자열, email 필드는 아예 없다")
    void broadcastJsonHasStringSenderAndNoEmail() throws Exception {
        // 옛 클라이언트가 본문에 email 을 실어 보낸다 — 받지도(필드가 없다) 내보내지도 않는다.
        MessageDto m = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                "{\"roomId\":\"room-1\",\"content\":\"hi\",\"email\":\"should-not-leak@example.com\"}",
                MessageDto.class);
        m.setSenderId(1234567890123456789L);

        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(m);

        assertThat(json).contains("\"senderId\":\"1234567890123456789\"").doesNotContain("email")
                .doesNotContain("should-not-leak");
        MessageDto back = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, MessageDto.class);
        assertThat(back.getSenderId()).isEqualTo(1234567890123456789L);
    }

    // ── 입장·퇴장 안내 ──────────────────────────────────────────────────

    private static org.springframework.messaging.simp.SimpMessageHeaderAccessor session() {
        org.springframework.messaging.simp.SimpMessageHeaderAccessor h =
                org.springframework.messaging.simp.SimpMessageHeaderAccessor.create();
        h.setSessionAttributes(new java.util.HashMap<>());
        return h;
    }

    /** 🔒 예전에는 "tester@example.com entered." 가 방의 상대방 화면에 찍혔다. */
    @Test
    @DisplayName("🔒 입장·퇴장 안내에 이메일이 없다 — 회원 행의 닉네임을 쓴다")
    void enterAndLeaveDoNotShowEmail() {
        when(chatMemberService.findById(KNOWN))
                .thenReturn(ChatMember.builder().nickname("알려진사람").build());

        service.sendEnterMessage(outgoing(KNOWN), session());
        service.sendLeaveMessage(outgoing(KNOWN), session());

        ArgumentCaptor<MessageDto> captor = ArgumentCaptor.forClass(MessageDto.class);
        verify(messageProducer, org.mockito.Mockito.times(2)).send(eq(KafkaUtil.KAFKA_TOPIC), captor.capture());
        assertThat(captor.getAllValues()).extracting(MessageDto::getContent)
                .containsExactly("알려진사람 entered.", "알려진사람 leaved.")
                .noneMatch(c -> c.contains("@") || c.contains("known"));
        assertThat(captor.getAllValues()).extracting(MessageDto::getNickname)
                .as("본문 닉네임(클라이언트 임의값)이 아니다").containsOnly("알려진사람");
    }
}
