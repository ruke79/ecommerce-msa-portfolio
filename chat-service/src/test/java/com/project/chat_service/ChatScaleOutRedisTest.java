package com.project.chat_service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.converter.ByteArrayMessageConverter;
import org.springframework.messaging.converter.CompositeMessageConverter;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.chat_service.constants.SupportThreadStatus;
import com.project.chat_service.dto.MessageDto;
import com.project.chat_service.message.fanout.StompFanout;
import com.project.chat_service.message.fanout.StompFanoutConfig;
import com.project.chat_service.model.SupportThread;
import com.project.chat_service.repository.jpa.SupportThreadRepository;
import com.project.chat_service.service.SupportPresence;
import com.project.chat_service.service.SupportReplyNotifier;
import com.project.common.constants.chat.MessageType;

/**
 * chat 파드 둘(docs/21 §2) — 실제 Redis 하나를 두 "파드" 가 같이 쓴다.
 *
 * <ul>
 *   <li>한 파드가 소비한 메시지를 <b>두 파드의 구독자가 모두, 보낸 순서대로</b> 받는다(StompFanout · 스레드 하나 실행기).</li>
 *   <li>A 파드에서 상담 방을 보고 있으면 B 파드도 안다 — 답변 알림을 보내지 않는다(SupportPresence).</li>
 *   <li>같은 상담의 답변을 두 파드가 소비해도 알림은 한 통(SupportReplyNotifier 의 Redis SET NX).</li>
 * </ul>
 *
 * <p>[JA] chat の Pod 二つ — 実際の Redis を共有して、配信 · 順序、相談の閲覧状況、通知の重複抑制を見る。
 */
@Tag("containers")
@Testcontainers
@DisplayName("chat 스케일아웃 — Redis 팬아웃 · presence · 알림 중복 억제 (파드 둘)")
class ChatScaleOutRedisTest {

    @Container
    static GenericContainer<?> redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static final String PROBE = "/sub/chat/room/probe";

    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory(redisContainer.getHost(), redisContainer.getMappedPort(6379));
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
    }

    @AfterAll
    static void close() {
        factory.destroy();
    }

    /** 파드 하나 — 자기 STOMP 템플릿(받은 메시지를 모은다)과 팬아웃 구독. */
    static final class Pod implements AutoCloseable {
        /** 시험 방(r1)으로 온 것만 — 구독 확인용 probe 는 따로 센다(늦게 도착한 probe 가 순서 검사에 섞이지 않게). */
        final List<String> received = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger probes = new AtomicInteger();
        final StompFanout fanout;
        final RedisMessageListenerContainer listener;
        final ThreadPoolTaskExecutor executor;

        Pod() throws Exception {
            MappingJackson2MessageConverter jackson = new MappingJackson2MessageConverter();
            jackson.setObjectMapper(org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json().build());
            CompositeMessageConverter converter = new CompositeMessageConverter(
                    List.of(new StringMessageConverter(), new ByteArrayMessageConverter(), jackson));
            SimpMessagingTemplate template = new SimpMessagingTemplate((message, timeout) -> {
                if (PROBE.equals(SimpMessageHeaderAccessor.getDestination(message.getHeaders()))) {
                    probes.incrementAndGet();
                } else {
                    received.add(new String((byte[]) message.getPayload(), StandardCharsets.UTF_8));
                }
                return true;
            });
            template.setMessageConverter(converter);
            fanout = new StompFanout(redis, template, converter);
            StompFanoutConfig config = new StompFanoutConfig();
            executor = config.stompFanoutExecutor();
            executor.initialize();
            listener = config.stompFanoutListener(factory, fanout, executor);
            listener.afterPropertiesSet();
            listener.start();
        }

        @Override
        public void close() throws Exception {
            listener.stop();
            listener.destroy();
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("🔴 한 파드가 보낸 300개를 두 파드의 구독자가 모두, 보낸 순서대로 받는다")
    void fanoutReachesEveryPodInOrder() throws Exception {
        try (Pod a = new Pod(); Pod b = new Pod()) {
            // 두 구독이 실제로 붙었는지 — 시험 봉투가 둘 다에 닿을 때까지
            await().atMost(Duration.ofSeconds(10)).until(() -> {
                a.fanout.send(PROBE, Map.of("seq", -1));
                return a.probes.get() > 0 && b.probes.get() > 0;
            });

            for (int i = 0; i < 300; i++) {
                a.fanout.send("/sub/chat/room/r1", Map.of("seq", i));
            }

            await().atMost(Duration.ofSeconds(20)).until(() -> a.received.size() >= 300 && b.received.size() >= 300);
            List<String> expected = new ArrayList<>();
            for (int i = 0; i < 300; i++) {
                expected.add("{\"seq\":" + i + "}");
            }
            assertThat(new ArrayList<>(a.received)).as("보낸 파드 자신의 구독자 — 돌아온 봉투로, 한 번씩").isEqualTo(expected);
            assertThat(new ArrayList<>(b.received)).as("다른 파드의 구독자 — 순서 그대로").isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("🔴 A 파드에서 상담 방을 보고 있으면 B 파드도 안다 — 구독 해지 · 끊김 · 파드 종료 · 죽은 파드(TTL)면 모른다")
    void presenceAcrossPods() {
        SupportPresence a = new SupportPresence(redis);
        SupportPresence b = new SupportPresence(redis);

        a.subscribed("s1", "sub-1", 7L, "support-5");
        assertThat(b.watching(7L, "support-5")).isTrue();
        assertThat(b.watching(8L, "support-5")).isFalse();
        assertThat(b.watching(7L, "support-6")).isFalse();

        a.unsubscribed("s1", "sub-1");
        assertThat(b.watching(7L, "support-5")).as("구독 해지").isFalse();

        a.subscribed("s1", "sub-1", 7L, "support-5");
        a.subscribed("s2", "sub-9", 7L, "support-5");   // 같은 사람의 탭 둘
        a.disconnected("s1");
        assertThat(b.watching(7L, "support-5")).as("탭 하나는 아직 보고 있다").isTrue();
        a.disconnected("s2");
        assertThat(b.watching(7L, "support-5")).as("둘 다 끊김").isFalse();

        SupportPresence c = new SupportPresence(redis);
        c.subscribed("s3", "sub-1", 9L, "support-7");
        assertThat(b.watching(9L, "support-7")).isTrue();
        String cKey = (String) ReflectionTestUtils.invokeMethod(c, "podKey");
        redis.delete(cKey);   // 파드가 죽어 해시가 TTL 로 사라졌다
        assertThat(b.watching(9L, "support-7")).as("죽은 파드의 기록은 남지 않는다").isFalse();
        assertThat(redis.opsForSet().isMember("chat:support-presence:pods", cKey)).as("목록에서도 지운다").isFalse();

        a.subscribed("s4", "sub-1", 11L, "support-8");
        ReflectionTestUtils.invokeMethod(a, "stop");   // 파드가 내려간다
        assertThat(b.watching(11L, "support-8")).as("내려간 파드").isFalse();
    }

    @Test
    @DisplayName("🔴 같은 상담의 답변을 두 파드가 소비해도 알림은 한 통 — Redis 기록(60초)을 같이 본다")
    void replyNotificationOncePerThreadAcrossPods() {
        SupportThreadRepository threads = mock(SupportThreadRepository.class);
        SupportThread t = SupportThread.open(1001L, Instant.now());
        ReflectionTestUtils.setField(t, "id", 5L);
        ReflectionTestUtils.setField(t, "status", SupportThreadStatus.ACTIVE);
        ReflectionTestUtils.setField(t, "agentId", 2001L);
        when(threads.findById(5L)).thenReturn(Optional.of(t));
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        redis.delete("chat:support-reply:5");

        SupportReplyNotifier podA = new SupportReplyNotifier(threads, new SupportPresence(), kafka, new ObjectMapper(), redis);
        SupportReplyNotifier podB = new SupportReplyNotifier(threads, new SupportPresence(), kafka, new ObjectMapper(), redis);
        podA.onMessage(agentReply("41"));
        podB.onMessage(agentReply("42"));

        verify(kafka, times(1)).send(anyString(), anyString(), anyString());
        assertThat(redis.getExpire("chat:support-reply:5")).as("60초 창").isBetween(1L, 60L);
    }

    private static MessageDto agentReply(String id) {
        MessageDto m = MessageDto.builder().id(id).roomId("support-5").type(MessageType.TEXT_TALK)
                .content("확인했습니다").nickname("상담원").build();
        m.setSenderId(2001L);
        return m;
    }
}
