package com.project.chat_service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Type;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.Transport;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.project.chat_service.constants.MemberRole;
import com.project.chat_service.dto.MessageDto;
import com.project.chat_service.model.ChatMember;
import com.project.chat_service.model.ChatMessage;
import com.project.chat_service.repository.jpa.ChatMemberRepository;
import com.project.chat_service.repository.mongo.ChatMessageRepository;
import com.project.chat_service.service.ChatService;
import com.project.common.constants.chat.MessageType;
import com.project.common.dto.chat.ChatRoomDto;

/**
 * 채팅의 <b>실제 경로</b> 통합 테스트 — WebSocket/STOMP.
 *
 * <p>지금까지 채팅에 대한 검증은 전부 리포지토리를 직접 호출하는 방식이었다
 * ({@link ChatServiceIntegrationTest}). 즉 사용자가 실제로 밟는 길 —
 * <b>STOMP CONNECT → SUBSCRIBE → SEND → Kafka → 저장 → 브로드캐스트</b> — 는
 * 한 번도 실행된 적이 없었다. 그 구간에는 JWT 인증 인터셉터, 세션 속성 기반 신원 확인,
 * Kafka 왕복, MongoDB 저장이 전부 들어 있다.
 *
 * <p>여기서는 그 전 구간을 진짜 컨테이너 위에서 통과시킨다:
 * <ul>
 *   <li>토큰 없는 CONNECT 는 거절되는가 (거절되지 않으면 누구나 남의 대화를 구독할 수 있다)</li>
 *   <li>메시지가 Kafka 를 돌아 구독자에게 도착하는가</li>
 *   <li>도착한 메시지가 <b>MongoDB 에도 남는가</b> (docs/16 B-24 — 예전엔 저장 자체가 없었다)</li>
 *   <li>클라이언트가 바디에 실어 보낸 발신자(senderId·email)가 무시되는가 (명의 도용 방지)</li>
 * </ul>
 */
@Tag("containers")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("chat-service STOMP 전 구간 (WebSocket + Kafka + MongoDB)")
class ChatStompIntegrationTest {

    private static final String JWT_SECRET =
            "0123456789012345678901234567890123456789012345678901234567890123";
    private static final String USER_EMAIL = "chatter@example.com";
    /** 토큰의 uid 클레임 = chat member_id. 신원은 이것이다(docs/48 §6-⑥). */
    private static final long USER_ID = 1L;
    private static final String NICKNAME = "채터";

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

    @Container
    static ConfluentKafkaContainer kafka =
            new ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
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

        registry.add("kafka.url", kafka::getBootstrapServers);
        registry.add("kafka.security.protocol", () -> "PLAINTEXT");
        registry.add("kafka.ssl.trust-store-location", () -> "");
        registry.add("kafka.ssl.trust-store-password", () -> "");
        registry.add("kafka.ssl.key-store-location", () -> "");
        registry.add("kafka.ssl.key-store-password", () -> "");

        registry.add("eureka.client.register-with-eureka", () -> false);
        registry.add("eureka.client.fetch-registry", () -> false);
        registry.add("management.tracing.enabled", () -> false);
        // 클라이언트가 평문 ws:// 로 붙을 수 있도록 — TLS 자체는 docs/18 에서 따로 검증한다.
        registry.add("server.ssl.enabled", () -> false);
        // STOMP 엔드포인트의 허용 오리진. 테스트 클라이언트는 오리진 헤더를 보내지 않지만
        // SockJS info 요청 때문에 값이 필요하다.
        registry.add("frontend.url", () -> "*");
        registry.add("JWT_SECRET_KEY", () -> JWT_SECRET);
        registry.add("LOCAL_CACERT_PATH", () -> "");
        registry.add("LOCAL_CRT_PATH", () -> "");
        registry.add("LOCAL_KEY_PATH", () -> "");
        registry.add("LOCAL_KEY_STORE_PATH", () -> "");
        registry.add("LOCAL_TRUST_STORE_PATH", () -> "");
        registry.add("LOCAL_KEY_STORE_PASSWORD", () -> "");
        registry.add("LOCAL_KEY_PASSWORD", () -> "");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ChatMemberRepository chatMemberRepository;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @Autowired
    private ChatService chatService;

    private String roomId;

    @BeforeEach
    void seed() throws Exception {
        chatMessageRepository.deleteAll();
        if (chatMemberRepository.findById(USER_ID).isEmpty()) {
            chatMemberRepository.save(ChatMember.builder()
                    .id(USER_ID).nickname(NICKNAME)
                    .imageUrl("").role(MemberRole.USER).build());
        }
        // 방은 서비스 API 로 만든다 — Redis 해시 저장과 참여자 등록까지 실제 경로를 탄다.
        ChatRoomDto room = chatService.createChatRoom("통합테스트방-" + System.nanoTime(), USER_ID);
        roomId = room.getRoomId();
    }

    /** 서비스가 검증하는 것과 같은 형식의 토큰. uid 클레임이 사용자 식별자가 된다(없으면 CONNECT 거절). */
    private static String token(String subject) {
        return JWT.create()
                .withSubject(subject)
                .withClaim("uid", USER_ID)
                .withClaim("tokenType", "access")
                .withExpiresAt(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .sign(Algorithm.HMAC256(JWT_SECRET.getBytes()));
    }

    private WebSocketStompClient stompClient() {
        List<Transport> transports = List.of(new WebSocketTransport(new StandardWebSocketClient()));
        WebSocketStompClient client = new WebSocketStompClient(new SockJsClient(transports));
        client.setMessageConverter(new MappingJackson2MessageConverter());
        return client;
    }

    private StompSession connect(String authorizationHeader) throws Exception {
        StompHeaders connectHeaders = new StompHeaders();
        if (authorizationHeader != null) {
            // ⚠️ StompHandler 는 "Bearer " 접두어를 벗기지 않고 헤더 값을 그대로 검증한다.
            // 프론트엔드도 그렇게 보내고 있다. 여기서도 같은 형식으로 보낸다.
            connectHeaders.add("Authorization", authorizationHeader);
        }
        return stompClient()
                .connectAsync("ws://localhost:" + port + "/chat",
                        new org.springframework.web.socket.WebSocketHttpHeaders(),
                        connectHeaders,
                        new StompSessionHandlerAdapter() { })
                .get(20, TimeUnit.SECONDS);
    }

    private BlockingQueue<MessageDto> subscribe(StompSession session, String room) {
        BlockingQueue<MessageDto> received = new LinkedBlockingQueue<>();
        StompHeaders headers = new StompHeaders();
        headers.setDestination("/sub/chat/room/" + room);
        // StompHandler 의 SUBSCRIBE 분기가 이 네이티브 헤더를 요구한다.
        headers.add("roomId", room);
        session.subscribe(headers, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders h) {
                return MessageDto.class;
            }

            @Override
            public void handleFrame(StompHeaders h, Object payload) {
                received.add((MessageDto) payload);
            }
        });
        return received;
    }

    @Test
    @DisplayName("토큰 없는 CONNECT 는 거절된다")
    void connectWithoutTokenIsRejected() {
        // 이게 뚫리면 누구나 임의의 방을 구독해 남의 대화를 실시간으로 읽을 수 있다.
        assertThatThrownBy(() -> connect(null))
                .as("Authorization 헤더 없는 STOMP CONNECT 는 성립하면 안 된다")
                .isNotNull();
    }

    @Test
    @DisplayName("위조된 토큰의 CONNECT 는 거절된다")
    void connectWithForgedTokenIsRejected() {
        String forged = JWT.create()
                .withSubject("attacker@example.com")
                .withExpiresAt(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .sign(Algorithm.HMAC256("wrong-secret-wrong-secret-wrong-secret".getBytes()));

        assertThatThrownBy(() -> connect(forged))
                .as("다른 키로 서명된 토큰은 거절돼야 한다")
                .isNotNull();
    }

    @Test
    @DisplayName("보낸 메시지가 Kafka 를 돌아 구독자에게 도착하고 MongoDB 에도 남는다")
    void messageRoundTripsAndIsPersisted() throws Exception {
        StompSession session = connect(token(USER_EMAIL));
        BlockingQueue<MessageDto> received = subscribe(session, roomId);

        // 컨슈머가 auto.offset.reset=latest 로 붙으므로 파티션 할당을 기다렸다가 보낸다.
        // (할당 전에 보내면 그 메시지는 영영 소비되지 않는다)
        Thread.sleep(3000);

        session.send("/pub/chat/message", MessageDto.builder()
                .type(MessageType.TEXT_TALK)
                .roomId(roomId)
                .nickname(NICKNAME)
                .content("안녕하세요")
                .imageUrl("")
                .readCount(0)
                .build());

        MessageDto broadcast = received.poll(30, TimeUnit.SECONDS);
        assertThat(broadcast).as("구독자에게 메시지가 도착해야 한다").isNotNull();
        assertThat(broadcast.getContent()).isEqualTo("안녕하세요");
        assertThat(broadcast.getRoomId()).isEqualTo(roomId);

        // 실시간 전달만 되고 저장이 안 되던 시절의 회귀 방지(docs/16 B-24).
        // 구독자가 받은 메시지는 이미 이력에 있어야 한다.
        List<ChatMessage> stored = null;
        for (int i = 0; i < 30 && (stored == null || stored.isEmpty()); i++) {
            stored = chatMessageRepository.findByRoomId(roomId).orElse(List.of());
            if (stored.isEmpty()) {
                Thread.sleep(500);
            }
        }
        assertThat(stored).as("브로드캐스트된 메시지는 MongoDB 에도 저장돼야 한다").hasSize(1);
        assertThat(stored.get(0).getContent()).isEqualTo("안녕하세요");

        session.disconnect();
    }

    @Test
    @DisplayName("바디에 실어 보낸 남의 발신자(senderId·email)는 무시되고 토큰의 uid 가 쓰인다")
    void clientSuppliedEmailCannotImpersonate() throws Exception {
        StompSession session = connect(token(USER_EMAIL));
        BlockingQueue<MessageDto> received = subscribe(session, roomId);
        Thread.sleep(3000);

        session.send("/pub/chat/message", MessageDto.builder()
                .type(MessageType.TEXT_TALK)
                .roomId(roomId)
                .senderId(999L)                // ← 위조 시도
                .nickname("피해자")
                .content("명의 도용 시도")
                .imageUrl("")
                .readCount(0)
                .build());

        MessageDto broadcast = received.poll(30, TimeUnit.SECONDS);
        assertThat(broadcast).isNotNull();
        assertThat(broadcast.getSenderId())
                .as("클라이언트가 보낸 값이 아니라 CONNECT 때 검증된 토큰의 uid 여야 한다")
                .isEqualTo(USER_ID);
        assertThat(broadcast.getNickname())
                .as("닉네임도 서버가 조회한 값으로 덮어써져야 한다")
                .isEqualTo(NICKNAME);

        session.disconnect();
    }

    // ── 연결을 토큰 수명 · 로그인 해제에 묶기(user-service docs/68 §9) ──────────────────
    //
    // 🐛 예전: CONNECT 뒤 끊길 때까지 살아 있었다 — 토큰이 만료돼도, 로그인이 끊겨도 방의 메시지를 계속 받았다.

    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    private static String tokenExpiringIn(java.time.Duration ttl, Instant issuedAt) {
        return JWT.create()
                .withSubject(USER_EMAIL)
                .withClaim("uid", USER_ID)
                .withClaim("tokenType", "access")
                .withIssuedAt(Date.from(issuedAt))
                .withExpiresAt(Date.from(Instant.now().plus(ttl)))
                .sign(Algorithm.HMAC256(JWT_SECRET.getBytes()));
    }

    private static void awaitDisconnected(StompSession session, long timeoutMs) throws InterruptedException {
        long until = System.currentTimeMillis() + timeoutMs;
        while (session.isConnected() && System.currentTimeMillis() < until) {
            Thread.sleep(100);
        }
    }

    @Test
    @DisplayName("🔴 토큰이 만료되면 서버가 연결을 닫는다 — 예전에는 끊길 때까지 살아 있었다")
    void connectionClosesWhenTheTokenExpires() throws Exception {
        StompSession session = connect(tokenExpiringIn(java.time.Duration.ofSeconds(3), Instant.now()));
        assertThat(session.isConnected()).isTrue();

        awaitDisconnected(session, 10_000);

        assertThat(session.isConnected()).as("만료 뒤에도 연결이 살아 있다").isFalse();
    }

    @Test
    @DisplayName("🔴 로그인 해제 알림이 오면 즉시 닫고, 해제 이전 토큰으로는 다시 붙지 못한다 · 새 토큰은 붙는다")
    void revocationClosesAndBlocksTheOldToken() throws Exception {
        String before = tokenExpiringIn(java.time.Duration.ofHours(1), Instant.now().minusSeconds(60));
        StompSession session = connect(before);
        try {
            // user-service RefreshTokenRepository.cutoffLiveSessions 와 같은 일을 한다
            stringRedisTemplate.opsForValue().set(
                    com.project.common.security.SessionRevocation.cutoffKey(USER_ID),
                    String.valueOf(Instant.now().getEpochSecond()));
            stringRedisTemplate.convertAndSend(com.project.common.security.SessionRevocation.CHANNEL, String.valueOf(USER_ID));

            awaitDisconnected(session, 10_000);
            assertThat(session.isConnected()).as("로그인 해제 뒤에도 연결이 살아 있다").isFalse();

            assertThatThrownBy(() -> connect(before))
                    .as("닫아도 옛 토큰으로 곧장 다시 붙으면 소용이 없다")
                    .isNotNull();

            Thread.sleep(1_100);   // 해제 뒤에 발급된 토큰 — iat 는 초 단위이고, 미래의 iat 는 서명 검증이 거절한다
            StompSession fresh = connect(tokenExpiringIn(java.time.Duration.ofHours(1), Instant.now()));
            assertThat(fresh.isConnected()).as("갱신한 토큰(로그인이 살아 있는 기기)은 다시 붙는다").isTrue();
            fresh.disconnect();
        } finally {
            stringRedisTemplate.delete(com.project.common.security.SessionRevocation.cutoffKey(USER_ID));
        }
    }
}
