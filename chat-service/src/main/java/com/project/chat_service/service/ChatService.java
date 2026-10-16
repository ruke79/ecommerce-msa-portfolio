package com.project.chat_service.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.concurrent.TimeUnit;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.project.chat_service.client.UserServiceClient;
import com.project.chat_service.common.util.KafkaUtil;
import com.project.chat_service.common.util.TokenHandler;
import com.project.chat_service.dto.MessageDto;
import com.project.chat_service.mapper.MessageMapper;
import com.project.chat_service.message.producer.MessageProducer;
import com.project.chat_service.model.ChatMember;
import com.project.chat_service.model.ChatMessage;
import com.project.chat_service.model.ChatRoom;
import com.project.chat_service.model.ParticipantChatRoom;
import com.project.chat_service.repository.jpa.ParticipantChatRoomRepository;
import com.project.chat_service.repository.jpa.ChatRoomJpaRepository;
import com.project.chat_service.repository.mongo.ChatMessageRepository;
import com.project.chat_service.repository.redis.ChatRoomRepository;
import com.project.common.dto.chat.ChatRoomDto;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;




@Log4j2
@RequiredArgsConstructor
@Service
@Transactional
public class ChatService {

    @PersistenceContext
    private EntityManager em;

    private final ChatMessageRepository chatMessageRepository;
    private final MessageProducer messageProducer;
    private final ChatRoomRepository chatRoomRepository;
    /** 방의 DB 쪽. 위의 {@code chatRoomRepository} 는 Redis 쪽이다(ChatRoomJpaRepository 주석). */
    private final ChatRoomJpaRepository chatRoomJpaRepository;
    private final ChatMemberService chatMemberService;
    private final ParticipantChatRoomRepository participantChatRoomRepository;
    private final SequenceGeneratorService sequenceGenerator;
    private final RedissonClient redissonClient;

    private final MessageMapper messageMapper;

    /**
     * 구독(입장) 시의 참여자 등록.
     *
     * <p>{@code userId} 는 CONNECT 때 검증된 토큰의 {@code uid} 이고, 그것이 곧 신원이다(member_id). 예전에는
     * 이메일이 신원이었고 uid 는 회원 행을 즉석에서 만들 때만 따로 넘겼다(docs/48 §6-⑥).
     */
    public void connect(String roomId, String userSessionId, Long userId) {

        // 세션↔방 매핑이 먼저다. 참여자 등록이 실패해도 DISCONNECT 가 정리할 것은 남는다.
        chatRoomRepository.setUserEnterInfo(userSessionId, roomId);
        saveParticipantRoom(roomId, userId);

        // send 입장 메시지
    }

    /** 친구 채팅방이 있는가 — 없는 방 id 로 보낸 메시지 · 안내는 저장하지 않는다(StompController). */
    @Transactional(readOnly = true)
    public boolean roomExists(String roomId) {
        return roomId != null && chatRoomJpaRepository.existsByRoomUid(roomId);
    }

    public void disconnect(String userSessionId) {

        String roomId = chatRoomRepository.getUserEnterRoomId(userSessionId);

        // create quit message
        // sendMessage(qit meesage);

        chatRoomRepository.removeUserEnterInfo(userSessionId);
    }

    @Transactional(readOnly = true)
    public List<MessageDto> getRoomMessagesByCurser(String roomId, String cursorId) {

         PageRequest pageable = PageRequest.of(0, 20 + 1);
        Slice<ChatMessage> chatList;

        if ( cursorId.equals("-1") ) {
            chatList = chatMessageRepository.findLatestInRoom(roomId, pageable);
        }
        else  {
            // id 를 숫자로 비교한다 — 사전순이면 "9" 가 "10" 보다 커서 건너뛰거나 겹쳤다(ChatMessageRepository).
            chatList = chatMessageRepository.findOlderInRoom(roomId, cursorId, pageable);
        }

        return chatList.stream()
            .map(MessageMapper::fromChatMessage)
            .collect(Collectors.toList());
    }

    /** 저장 — 소비자(MessageConsumer)가 부른다. 발신자는 id 로만 저장한다(docs/48 §6-⑥). */
    public String saveMessage(MessageDto message) {
        ChatMessage msg = chatMessageRepository.save(MessageMapper.toChatMessage(message));
        return msg.getContent();
    }

    /**
     * 메시지를 보낸다.
     *
     * <h3>🐛 회원 복제 행이 없으면 id 도 시각도 없는 메시지가 나갔다</h3>
     *
     * <p>예전에는 {@code if (member != null)} 안에서만 id 와 {@code createdAt} 을 붙였다.
     * 회원 복제 행({@code ChatMember})은 {@code user-created} 이벤트로 채워지는데, 그
     * 이벤트가 발행되지 않던 경로가 있었다(docs/48 — 메일 인증을 끈 가입). 그런 계정이
     * 보낸 메시지는 <b>id 없이, 시각 없이</b> 카프카로 나갔고, 화면은 {@code createdAt}
     * 이 없는 메시지를 그리다 죽었다.
     *
     * <p>id 와 시각은 <b>발신자가 누구든</b> 붙인다 — 그 둘은 메시지의 속성이지 회원의
     * 속성이 아니다. 닉네임만 회원 행에서 오고, 없으면 대체값을 쓴다.
     * 발신자({@code senderId})는 STOMP 세션에서 검증된 uid 라({@code StompController}) 신뢰할 수 있다.
     * 본문에 실려 온 닉네임은 쓰지 않는다 — 클라이언트가 임의로 정한 값이다.
     */
    public void sendMessage(MessageDto message) {

        Long senderId = message.getSenderId();

        message.setId(sequenceGenerator.generateSequence(MessageDto.SEQUENCE_NAME));

        ChatMember member = chatMemberService.findById(senderId);
        String nickname;
        if (member != null) {
            nickname = member.getNickname();
        } else {
            nickname = fallbackNickname();
            log.warn("채팅 회원 행이 없는 발신자 — 대체 닉네임을 쓴다(docs/48). messageId={}", message.getId());
        }
        message.setSendTimeAndSender(java.time.Instant.now(), nickname, senderId, 0);

        messageProducer.send(KafkaUtil.KAFKA_TOPIC, message);
    }

    /** 검증된 발신자의 표시 이름 — 회원 행의 닉네임, 없으면 대체값. */
    private String displayNameOf(Long senderId) {
        ChatMember member = chatMemberService.findById(senderId);
        return member != null && member.getNickname() != null ? member.getNickname() : fallbackNickname();
    }

    /**
     * 회원 행이 없을 때의 닉네임. <b>식별자에서 만들지 않는다</b> — 닉네임은 상대방 화면에 보인다. 예전에는 이메일
     * 로컬 파트를 썼는데, 로컬 파트가 곧 계정 아이디인 경우가 흔하고, 가입 이벤트가 전부 DLT 로 가던 동안
     * (docs/48 §6-⑤) 이 경로가 사실상 모든 사람의 이름이었다.
     */
    static String fallbackNickname() {
        return UNKNOWN_NICKNAME;
    }

    static final String UNKNOWN_NICKNAME = ChatNotices.UNKNOWN_NICKNAME;

    /**
     * 채팅방 목록 — <b>DB 기준</b>.
     *
     * <p>🐛 예전에는 Redis 해시에서 읽었다. 그런데 방은 DB 에도 저장된다(createChatRoom). 두
     * 저장소가 어긋나는 순간 — Redis 가 비워지거나(재시작·만료·다른 인스턴스), 한쪽 쓰기만
     * 성공하거나 — DB 에 멀쩡히 있는 방이 목록에서 <b>사라졌다.</b> 진실은 한 곳이어야 하고,
     * 그것은 트랜잭션이 있는 DB 다. Redis 의 방 해시는 이제 생성 시점의 중복 방지 잠금과
     * 함께 남는 거울일 뿐, 읽는 곳이 없다.
     */
    @Transactional(readOnly = true)
    public List<ChatRoom> fildAllRooms() {
        return chatRoomJpaRepository.findAllByOrderByIdAsc();
    }

    /** 방 이름 최대 길이 — 화면(CreateRoomModal 의 MAX_LEN)과 같다. */
    public static final int ROOM_NAME_MAX_LENGTH = 20;

    /**
     * 🐛 예전: 이름을 보지 않았다 — 빈 이름의 방이 생기고, 255자를 넘으면 DB 오류가 500 으로 나갔다. 화면은 20자로 막는다.
     * [JA] 以前は名前を見ていなかった — 空の名前のルームができ、255 字を超えると DB エラーが 500 になった。
     */
    public ChatRoomDto createChatRoom(String roomName, Long userId) throws Exception {
        if (roomName == null || roomName.isBlank() || roomName.strip().length() > ROOM_NAME_MAX_LENGTH) {
            throw new com.project.common.domain.exception.DomainException(
                    "채팅방 이름은 1 ~ " + ROOM_NAME_MAX_LENGTH + "자로 정해 주세요.");
        }
        roomName = roomName.strip();
        String lockKey = "chatroom:create:lock:" + roomName;
        RLock lock = redissonClient.getLock(lockKey);
        ChatRoomDto chatRoomDto = null;

        try {
            boolean isLocked = lock.tryLock(10, 5, TimeUnit.SECONDS);
            if (isLocked) {
                try {
                    ChatRoom chatRoom = ChatRoom.create(roomName);
                    saveChatRoom(chatRoom);
                    // 예전에는 Redis 해시(CHAT_ROOM)에도 방 사본을 넣었다 — 목록이 DB 기준이 된 뒤로 읽는 곳이 없는, 쌓이기만 하는 사본이었다.
                    chatRoomDto = ChatRoomDto.builder().name(chatRoom.getRoomName()).roomId(chatRoom.getRoomUid()).build();
                    saveParticipantRoom(chatRoom.getRoomUid(), userId);
                } finally {
                    lock.unlock();
                }
            } else {
                throw new IllegalStateException("Failed to acquire lock for chatroom creation.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while acquiring lock", e);
        }

        return chatRoomDto;
    }

    /**
     * 입장 안내. 🔒 문구는 방의 상대방 화면에 그대로 찍힌다 — 식별자를 넣지 않는다. 닉네임은 본문(클라이언트
     * 임의값)이 아니라 회원 행에서 온다(sendMessage 와 같다, docs/48 §6-⑥).
     *
     * <p>예전에는 여기서 세션 속성 {@code email}(세션 값을 다시 적었다)과 {@code nickname}(<b>본문 값</b>)을 적었다.
     * 둘 다 읽는 곳이 없었다. 이제 세션에는 방 번호만 적는다. 신원은 CONNECT 가 적은 {@code userId} 하나다.
     */
    @Transactional(readOnly = true)
    public void  sendEnterMessage(MessageDto msg, SimpMessageHeaderAccessor headerAccessor) {
        Objects.requireNonNull(headerAccessor.getSessionAttributes()).put("roomId", msg.getRoomId());
        String nickname = displayNameOf(msg.getSenderId());
        msg.setContent(ChatNotices.entered(nickname));
        msg.setSendTimeAndSender(java.time.Instant.now(), nickname, msg.getSenderId(), 0);

        messageProducer.send(KafkaUtil.KAFKA_TOPIC, msg);
    }

    @Transactional(readOnly = true)
    public void  sendLeaveMessage(MessageDto msg, SimpMessageHeaderAccessor headerAccessor) {
        Objects.requireNonNull(headerAccessor.getSessionAttributes()).put("roomId", msg.getRoomId());
        String nickname = displayNameOf(msg.getSenderId());
        msg.setContent(ChatNotices.left(nickname));
        msg.setSendTimeAndSender(java.time.Instant.now(), nickname, msg.getSenderId(), 0);

        messageProducer.send(KafkaUtil.KAFKA_TOPIC, msg);
    }

    @Transactional
    public void saveChatRoom(ChatRoom chatRoom) {
        em.persist(chatRoom);
    }

    /**
     * 사용자를 채팅방 참여자로 등록한다.
     *
     * <p>🐛 <b>채팅방을 만들면 500 이 나던 자리다.</b> 회원 조회
     * 은 회원 행이 없으면 {@code null} 을 돌려준다(orElse(null)). 그런데 아래에서 곧바로
     * {@code member.getRoomList()} 를 불러 NPE 가 났다.
     *
     * <p>회원 행이 없는 것은 예외 상황이 아니라 <b>흔한 정상 상태</b>다. {@code chat_member}
     * 행은 오직 {@code user-created} 카프카 이벤트로만 만들어지므로(UserMessageListener),
     * 다음 경우 전부 행이 없다:
     *
     * <ul>
     *   <li>chat-service 가 배포되기 전에 가입한 사용자</li>
     *   <li>그 사용자의 {@code user-created} 이벤트가 DLT 로 간 경우</li>
     *   <li>DB 에 직접 만든 계정(관리자·테스트 계정)</li>
     * </ul>
     *
     * 즉 "예전 가입자는 채팅방을 만들 수 없다"였고, 화면에는 500 만 보였다.
     *
     * <p>없으면 만든다. 채팅 회원은 파생 데이터이고, 필요한 것은 member_id(= uid) 하나다. 가입 이벤트가 나중에
     * 도착하면 같은 id 의 행을 원본 프로필로 채운다(UserMessageListener).
     */
    private ChatMember saveParticipantRoom(String roomId, Long userId) {
        ChatMember member = chatMemberService.findById(userId);
        if (member == null) {
            member = createMissingMember(userId);
        }

        // 🔴 방은 **DB 에서** 읽는다. 예전에는 Redis 에서 읽은 것을 그대로 연관으로 걸었는데,
        //    그것은 역직렬화된 분리 객체라 JPA 가 관리하지 않는다. 그 객체로 참여자 행을
        //    만들면 flush 단계에서 터지고, 그 예외가 SUBSCRIBE 프레임을 죽였다
        //    (ChatRoomJpaRepository 주석). 방을 만든 사람은 이미 참여자라 아래 if 를 건너뛰므로
        //    **자기가 만든 방에서는 멀쩡하고 남이 만든 방에서만 실패**하는 모양이었다.
        ChatRoom chatRoom = chatRoomJpaRepository.findByRoomUid(roomId).orElse(null);
        if (chatRoom == null) {
            // 무엇이 없는지 말한다. Redis 에만 있고 DB 에 없다면 그것도 여기서 드러난다.
            throw new com.project.common.domain.exception.DomainException("채팅방을 찾을 수 없습니다: " + roomId);
        }

        // 재구독/재접속마다 호출되므로, 이미 참여중인 사용자는 다시 저장하지 않는다.
        // 🐛 예전에는 "있나 보고 없으면 persist" 였다 — 두 탭이 동시에 구독하면 둘 다 "없음" 을 읽어 참여 행이 둘 생겼다.
        //    이제 (방, 회원)은 유니크(V9)이고, 넣기는 없을 때만이라 늦은 쪽도 실패하지 않는다.
        // [JA] 以前は「あるか見て無ければ persist」で、同時の購読で参加行が二つできていた。今は(ルーム, 会員)ユニーク + 無ければ挿入。
        if (!participantChatRoomRepository.existsByRoom_RoomUidAndMember_Id(roomId, member.getId())) {
            participantChatRoomRepository.insertIfAbsent(chatRoom.getId(), member.getId());
        }
        return member;
    }

    /**
     * 회원 행을 보장한다 — 있으면 그것, 없으면 즉석에서 만든다(아래 {@link #createMissingMember}).
     * 상담 스레드(docs/58)가 고객·상담원 id 로 외래 키를 걸기 전에 부른다.
     */
    public ChatMember ensureMember(Long userId) {
        ChatMember member = chatMemberService.findById(userId);
        return member != null ? member : createMissingMember(userId);
    }

    /**
     * 채팅 회원 행을 즉석에서 만든다.
     *
     * <p>{@code member_id} 는 user-service 의 userId 를 그대로 쓴다 — user-created 이벤트가
     * 나중에 도착해도 같은 id 라 새 행이 생기지 않는다(ChatMember 는 Persistable 로
     * persist 를 강제하므로 충돌이 드러난다).
     *
     * <p>userId 를 모르면 만들 수 없다. 임의의 id 를 배정하면 나중에 도착한 이벤트가
     * <b>같은 사람의 두 번째 회원 행</b>을 만들고, 참여 이력이 둘로 갈린다.
     */
    private ChatMember createMissingMember(Long userId) {
        if (userId == null) {
            throw new com.project.common.domain.exception.DomainException(
                    "채팅 회원 정보가 없습니다. 다시 로그인한 뒤 시도해 주세요.");
        }

        log.info("채팅 회원 행이 없어 즉석에서 만든다. memberId={}", userId);

        ChatMember member = ChatMember.builder()
                .id(userId)
                // 이메일은 두지 않는다 — 신원은 id 다(docs/48 §6-⑥).
                // 닉네임은 user-created 이벤트가 주는 값이다(도착하면 UserMessageListener 가 채운다). 그 전까지의
                // 임시값에 이메일 앞부분을 쓰지 않는다 — 닉네임은 **상대방 화면에 보인다.** 예전에는 가입 이벤트가
                // 전부 DLT 로 가(docs/48 §6-⑤) 모든 채팅 회원이 이메일 아이디를 이름으로 달고 있었다.
                .nickname(ChatNotices.tempNickname(userId))
                .build();

        em.persist(member);
        em.flush();
        return member;
    }
}