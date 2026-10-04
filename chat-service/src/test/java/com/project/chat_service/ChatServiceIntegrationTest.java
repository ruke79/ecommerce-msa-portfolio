package com.project.chat_service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.project.chat_service.mapper.MessageMapper;
import com.project.chat_service.model.ChatMessage;
import com.project.chat_service.repository.mongo.ChatMessageRepository;
import com.project.common.constants.chat.MessageType;

/**
 * chat-service 통합 테스트 — 실제 MongoDB/PostgreSQL/Redis 위에서.
 *
 * <p>채팅 경로는 MongoDB 없이는 전혀 검증할 수 없었고, 그래서 오랫동안 미검증으로 남아 있었다.
 * 실제로 붙여 보니 <b>TLS 하드코딩(B-23)</b>과 <b>저장 경로의 결함 3건(B-24)</b>이 드러났다.
 * 여기서는 그 중 코드로 고칠 수 있었던 부분이 유지되는지를 못 박는다.
 *
 * <p>⚠️ 메시지 <b>저장 자체가 호출되지 않는</b> 문제(docs/16 B-24 1번)는 설계 결정이라 미해결이다.
 * 따라서 이 테스트는 리포지토리를 직접 호출해 <b>저장·조회 경로가 동작함</b>을 확인한다 —
 * 배선만 연결하면 바로 쓸 수 있는 상태라는 뜻이기도 하다.
 */
@Tag("containers")
@Testcontainers
@SpringBootTest
@DisplayName("chat-service 통합 (MongoDB Testcontainers)")
class ChatServiceIntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:7"));

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("chatdb")
                    .withUsername("product")
                    .withPassword("testpw");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379)
                    .withCommand("redis-server", "--requirepass", "testpw");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // ssl=false 로 붙는다 — 예전에는 클라이언트가 TLS 를 하드코딩해 이 설정을 무시했고,
        // TLS 없는 MongoDB 에는 아예 접속할 수 없었다(docs/16 B-23).
        registry.add("spring.data.mongodb.client",
                () -> mongo.getConnectionString() + "/chat?ssl=false");
        registry.add("spring.data.mongodb.name", () -> "chat");
        registry.add("spring.data.mongodb.cacert", () -> "");
        registry.add("spring.data.mongodb.keystore", () -> "");
        registry.add("spring.data.mongodb.keystorePassword", () -> "");

        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "testpw");
        registry.add("spring.data.redis.ssl.enabled", () -> false);

        registry.add("eureka.client.enabled", () -> false);
        // 백그라운드 정비(인덱스 · 옛 시각 보정)는 테스트가 직접 부른다 — 심어 둔 문서의 시각을 몰래 옮기지 않게.
        registry.add("chat.message-maintenance.enabled", () -> false);
        registry.add("eureka.client.register-with-eureka", () -> false);
        registry.add("eureka.client.fetch-registry", () -> false);
        registry.add("management.tracing.enabled", () -> false);
        registry.add("server.ssl.enabled", () -> false);
        registry.add("JWT_SECRET_KEY",
                () -> "0123456789012345678901234567890123456789012345678901234567890123");
        registry.add("LOCAL_CACERT_PATH", () -> "");
        registry.add("LOCAL_CRT_PATH", () -> "");
        registry.add("LOCAL_KEY_PATH", () -> "");
        registry.add("LOCAL_KEY_STORE_PATH", () -> "");
        registry.add("LOCAL_TRUST_STORE_PATH", () -> "");
        registry.add("LOCAL_KEY_STORE_PASSWORD", () -> "");
        registry.add("LOCAL_KEY_PASSWORD", () -> "");
    }

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @Autowired
    private com.project.chat_service.repository.jpa.SupportThreadRepository supportThreads;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager txManager;

    @Autowired
    private com.project.chat_service.service.ChatService chatService;

    @BeforeEach
    void clean() {
        chatMessageRepository.deleteAll();
    }

    private ChatMessage message(String id, String roomId, String content, long createdAt) {
        ChatMessage msg = ChatMessage.get(
                MessageType.TEXT_TALK, "테스터", 7L, roomId,
                content, "", 0, Long.toString(createdAt));
        msg.setId(id);
        return msg;
    }

    @Test
    @DisplayName("메시지를 저장하고 방 기준으로 다시 읽을 수 있다")
    void saveAndReadByRoom() {
        chatMessageRepository.save(message("1001", "room-a", "첫 메시지", 1785196001000L));
        chatMessageRepository.save(message("1002", "room-a", "둘째 메시지", 1785196002000L));
        chatMessageRepository.save(message("2001", "room-b", "다른 방", 1785196003000L));

        List<ChatMessage> roomA = chatMessageRepository.findByRoomId("room-a").orElseThrow();

        assertThat(roomA).hasSize(2);
        assertThat(roomA).extracting(ChatMessage::getContent)
                .containsExactlyInAnyOrder("첫 메시지", "둘째 메시지");
    }

    @Test
    @DisplayName("roomId 가 닉네임으로 뒤바뀌어 저장되지 않는다 (B-24 회귀)")
    void roomIdIsNotOverwrittenByNickname() {
        // 매퍼가 인자 순서를 뒤바꿔 넘기던 시절에는 roomId 자리에 닉네임이 들어가
        // findByRoomId 로 영영 조회되지 않았다. 매퍼를 거친 뒤에도 방으로 찾을 수 있어야 한다.
        var dto = com.project.chat_service.dto.MessageDto.builder()
                .id("3001").type(MessageType.TEXT_TALK).roomId("room-c")
                .senderId(7L).nickname("테스터")
                .content("매퍼 경유").imageUrl("").readCount(0)
                .createdAt(1785196004000L).build();

        ChatMessage saved = chatMessageRepository.save(MessageMapper.toChatMessage(dto));
        assertThat(saved.getRoomId()).isEqualTo("room-c");

        assertThat(chatMessageRepository.findByRoomId("room-c").orElseThrow())
                .as("매퍼를 거쳐 저장한 메시지도 방으로 조회돼야 한다")
                .hasSize(1);
        assertThat(chatMessageRepository.findByRoomId("테스터").orElse(List.of()))
                .as("닉네임이 방 번호로 쓰이면 안 된다")
                .isEmpty();
    }

    @Test
    @DisplayName("커서 페이지네이션이 id 역순으로 동작한다")
    void cursorPaginationWorks() {
        chatMessageRepository.save(message("1001", "room-a", "1", 1785196001000L));
        chatMessageRepository.save(message("1002", "room-a", "2", 1785196002000L));
        chatMessageRepository.save(message("1003", "room-a", "3", 1785196003000L));

        var page = chatMessageRepository.findOlderInRoom("room-a", "1003", PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(ChatMessage::getId).containsExactly("1002", "1001");
    }

    @Test
    @DisplayName("id 의 자릿수가 바뀌어도 숫자 순서로 정렬·커서가 동작한다 — 사전순이면 \"9\" > \"10\" (docs/58 §7)")
    void cursorUsesNumericOrderAcrossDigitBoundary() {
        for (String id : List.of("8", "9", "10", "11", "12")) {
            chatMessageRepository.save(message(id, "room-n", "m" + id, 1785196000000L + Long.parseLong(id)));
        }

        var latest = chatMessageRepository.findLatestInRoom("room-n", PageRequest.of(0, 3));
        assertThat(latest.getContent()).extracting(ChatMessage::getId)
                .as("최신 3개 — 사전순이면 9, 8, 12 가 나왔다")
                .containsExactly("12", "11", "10");

        var older = chatMessageRepository.findOlderInRoom("room-n", "10", PageRequest.of(0, 10));
        assertThat(older.getContent()).extracting(ChatMessage::getId)
                .as("10 보다 오래된 것 — 사전순이면 \"9\" 가 \"10\" 보다 커서 빠졌다")
                .containsExactly("9", "8");
    }

    @Test
    @DisplayName("createdAt 이 없는 문서가 섞여도 조회 매핑이 깨지지 않는다 (B-24 회귀)")
    void malformedDocumentDoesNotBreakRoomHistory() {
        chatMessageRepository.save(message("1001", "room-a", "정상", 1785196001000L));
        chatMessageRepository.save(message("1004", "room-a", "타임스탬프 없음", 0L));
        // 실제로는 필드 자체가 없는 문서가 있을 수 있다. 매퍼가 이를 견디는지 확인한다.
        ChatMessage noTimestamp = message("1005", "room-a", "필드 없음", 0L);
        noTimestamp.setCreatedAt(null);
        chatMessageRepository.save(noTimestamp);

        List<ChatMessage> all = chatMessageRepository.findByRoomId("room-a").orElseThrow();

        assertThat(all).hasSize(3);
        // 방 전체를 DTO 로 옮길 때 한 건 때문에 예외가 나면 안 된다(예전엔 HTTP 500 이었다).
        assertThat(all.stream().map(MessageMapper::fromChatMessage).toList()).hasSize(3);
    }

    @Test
    @DisplayName("같은 메시지가 재전달돼도 문서가 늘지 않는다 (upsert 멱등성)")
    void redeliveryDoesNotDuplicateMessages() {
        // 소비자 측 저장(docs/16 B-24 배선)을 켠 뒤 생긴 새 위험이다. Kafka 는 at-least-once 라
        // 같은 메시지가 다시 오는데, 엔티티가 생산자 시퀀스 id 를 싣지 않으면 MongoDB 가 매번
        // 새 _id 를 만들어 **같은 대화가 여러 번 저장**된다.
        var dto = com.project.chat_service.dto.MessageDto.builder()
                .id("seq-9001").type(MessageType.TEXT_TALK).roomId("room-dup")
                .senderId(7L).nickname("테스터")
                .content("한 번만 저장돼야 한다").imageUrl("").readCount(0)
                .createdAt(1785196005000L).build();

        for (int i = 0; i < 3; i++) {
            chatMessageRepository.save(MessageMapper.toChatMessage(dto));
        }

        assertThat(chatMessageRepository.findByRoomId("room-dup").orElseThrow())
                .as("재전달 3회에도 문서는 한 건이어야 한다")
                .hasSize(1);
    }

    @Test
    @DisplayName("생산자가 부여한 id 가 문서 _id 로 보존된다")
    void producerAssignedIdIsPreserved() {
        var dto = com.project.chat_service.dto.MessageDto.builder()
                .id("seq-9002").type(MessageType.TEXT_TALK).roomId("room-id")
                .senderId(7L).nickname("테스터")
                .content("id 보존").imageUrl("").readCount(0)
                .createdAt(1785196006000L).build();

        assertThat(chatMessageRepository.save(MessageMapper.toChatMessage(dto)).getId())
                .isEqualTo("seq-9002");
    }

    @Test
    @DisplayName("고객 메시지 갱신이 그 사이에 커밋된 상담 배정(claim)을 덮어쓰지 않는다 — @DynamicUpdate")
    void messageDoesNotOverwriteConcurrentClaim() {
        var tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
        tx.executeWithoutResult(s -> { chatService.ensureMember(501L); chatService.ensureMember(900L); });
        var thread = supportThreads.save(
                com.project.chat_service.model.SupportThread.open(501L, java.time.Instant.now()));
        Long id = thread.getId();

        // 고객 메시지(acceptMessage 와 같은 순서): 스레드를 읽고 요약을 바꾼다 → 커밋 전에 상담원이 맡는다 → 커밋.
        tx.executeWithoutResult(s -> {
            var t = supportThreads.findById(id).orElseThrow();
            t.recordMessage("주문이 아직 안 왔어요", true, java.time.Instant.now());

            var requiresNew = new org.springframework.transaction.support.TransactionTemplate(txManager);
            requiresNew.setPropagationBehavior(
                    org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            int claimed = requiresNew.execute(s2 -> supportThreads.claim(id, 900L, java.time.Instant.now()));
            assertThat(claimed).isEqualTo(1);
        });

        var after = supportThreads.findById(id).orElseThrow();
        // 예전에는 메시지 쪽 UPDATE 가 모든 컬럼을 다시 써 WAITING · 담당자 없음으로 돌아갔다.
        assertThat(after.getStatus()).isEqualTo(com.project.chat_service.constants.SupportThreadStatus.ACTIVE);
        assertThat(after.getAgentId()).isEqualTo(900L);
        assertThat(after.getLastMessagePreview()).isEqualTo("주문이 아직 안 왔어요");
        assertThat(after.isAwaitingReply()).isTrue();
    }

    @Autowired
    private com.project.chat_service.repository.jpa.ChatMemberRepository chatMembers;

    @Test
    @DisplayName("탈퇴(익명화)가 커밋되는 사이에 온 프로필 변경이 이름을 되살리지 않는다 — 회원 행 쓰기 잠금")
    void profileUpdateDoesNotResurrectWithdrawnMember() throws Exception {
        var tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
        tx.executeWithoutResult(s -> chatService.ensureMember(777L));
        var locked = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            // user-deleted 처리(UserMessageListener.userDeleted 와 같은 순서): 잠그고 읽어 익명화 → 커밋 전에 잠시 멈춘다.
            var deleted = pool.submit(() -> tx.executeWithoutResult(s -> {
                chatMembers.findForUpdate(777L).orElseThrow().anonymize();
                chatMembers.flush();
                locked.countDown();
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            locked.await();
            // 그 사이에 user-updated 처리 — 예전처럼 그냥 읽었다면 익명화 전 값을 보고 시각 검사를 통과했다.
            var updated = pool.submit(() -> tx.execute(s ->
                    chatMembers.findForUpdate(777L).orElseThrow().applyProfile("되살아난 이름", null, 1_000L)));
            deleted.get();
            assertThat(updated.get()).as("탈퇴가 커밋된 뒤의 값을 읽어 거른다").isFalse();
        } finally {
            pool.shutdownNow();
        }
        assertThat(chatMembers.findById(777L).orElseThrow().getNickname())
                .isEqualTo(com.project.chat_service.model.ChatMember.WITHDRAWN_NICKNAME);
    }

    @Autowired
    private com.project.chat_service.repository.jpa.ParticipantChatRoomRepository participants;

    @Test
    @DisplayName("참여 행은 없을 때만 넣는다 — 두 번 넣어도 한 줄(V9 유니크)")
    void participantInsertIsIdempotent() {
        var tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
        Long roomPk = tx.execute(s -> {
            chatService.ensureMember(778L);
            var room = com.project.chat_service.model.ChatRoom.create("dup-test");
            chatService.saveChatRoom(room);
            return room.getId();
        });
        int first = tx.execute(s -> participants.insertIfAbsent(roomPk, 778L));
        int second = tx.execute(s -> participants.insertIfAbsent(roomPk, 778L));
        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
    }
}
