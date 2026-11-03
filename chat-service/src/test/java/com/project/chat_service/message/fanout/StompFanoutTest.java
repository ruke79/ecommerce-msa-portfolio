package com.project.chat_service.message.fanout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.converter.ByteArrayMessageConverter;
import org.springframework.messaging.converter.CompositeMessageConverter;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import com.project.chat_service.service.SupportDeskNotifier;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * STOMP 팬아웃(docs/21 §2) — 보내는 쪽은 Redis 에만 싣고(자기 구독자에게는 Redis 로 돌아온 것으로 보낸다), 받는 쪽은 예전 직접 보내기와
 * <b>같은 JSON</b> 을 구독자에게 보낸다. Redis 에 못 실으면 이 파드의 구독자에게라도 보낸다.
 * 여러 파드 사이의 실제 전달 · 순서는 {@code ChatScaleOutRedisTest}(컨테이너).
 */
@DisplayName("StompFanout — Redis 로 모든 파드에")
class StompFanoutTest {

    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    List<Message<?>> delivered = new ArrayList<>();
    /** Spring Boot 가 STOMP 에 거는 변환기와 같은 모양 — Jackson 은 앱의 ObjectMapper(시간 모듈 · 날짜를 문자열로). */
    CompositeMessageConverter converter = new CompositeMessageConverter(List.of(
            new StringMessageConverter(), new ByteArrayMessageConverter(), bootJackson()));

    private static MappingJackson2MessageConverter bootJackson() {
        MappingJackson2MessageConverter c = new MappingJackson2MessageConverter();
        c.setObjectMapper(org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json().build());
        return c;
    }
    SimpMessagingTemplate template;
    StompFanout fanout;

    @BeforeEach
    void setUp() {
        template = new SimpMessagingTemplate((message, timeout) -> delivered.add(message));
        template.setMessageConverter(converter);
        fanout = new StompFanout(redis, template, converter);
    }

    private String published() {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(redis).convertAndSend(eq(StompFanout.CHANNEL), body.capture());
        return body.getValue();
    }

    @Test
    @DisplayName("🔴 보내기는 Redis 채널에 한 번 — 이 파드의 구독자에게 직접 보내지 않는다(돌아온 봉투로 보낸다, 두 번 받지 않게)")
    void publishesOnce() {
        fanout.send("/sub/chat/room/r1", new SupportDeskNotifier.DeskEvent(5L, SupportDeskNotifier.Kind.MESSAGE, 7L,
                Instant.parse("2026-11-01T00:00:00Z")));

        assertThat(published()).contains("\"d\":\"/sub/chat/room/r1\"");
        assertThat(delivered).as("직접 보내지 않는다").isEmpty();
    }

    @Test
    @DisplayName("🔴 받은 봉투는 예전 직접 보내기와 같은 JSON 으로 구독자에게 — TSID 는 문자열, 시각은 변환기의 형식 그대로")
    void sameJsonAsDirectSend() {
        var event = new SupportDeskNotifier.DeskEvent(1234567890123456789L, SupportDeskNotifier.Kind.CLAIMED, 42L,
                Instant.parse("2026-11-01T00:00:00Z"));
        template.convertAndSend("/sub/support/desk", event);
        String direct = new String((byte[]) delivered.remove(0).getPayload(), StandardCharsets.UTF_8);

        fanout.send("/sub/support/desk", event);
        fanout.onEnvelope(published().getBytes(StandardCharsets.UTF_8));

        assertThat(delivered).hasSize(1);
        Message<?> m = delivered.get(0);
        assertThat(new String((byte[]) m.getPayload(), StandardCharsets.UTF_8)).isEqualTo(direct);
        assertThat(m.getHeaders().get("simpDestination")).isEqualTo("/sub/support/desk");
        assertThat(String.valueOf(m.getHeaders().get(org.springframework.messaging.MessageHeaders.CONTENT_TYPE))).isEqualTo("application/json");
        assertThat(direct).contains("\"threadId\":\"1234567890123456789\"");
    }

    @Test
    @DisplayName("🔴 Redis 에 못 실으면 이 파드의 구독자에게라도 보내고 센다(경보 ChatFanoutPublishFailed)")
    void redisDownDeliversLocally() {
        Metrics.addRegistry(new SimpleMeterRegistry());
        double before = Metrics.counter(StompFanout.FAILED_METRIC).count();
        doThrow(new RedisConnectionFailureException("down")).when(redis).convertAndSend(anyString(), anyString());

        fanout.send("/sub/chat/room/r1", java.util.Map.of("content", "hi"));

        assertThat(delivered).hasSize(1);
        assertThat(delivered.get(0).getHeaders().get("simpDestination")).isEqualTo("/sub/chat/room/r1");
        assertThat(Metrics.counter(StompFanout.FAILED_METRIC).count()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("브로커 밖 주소 · 깨진 봉투는 버린다 — 리스너는 던지지 않는다")
    void rejectsForeignEnvelopes() {
        fanout.onEnvelope("{\"d\":\"/pub/chat/message\",\"p\":{}}".getBytes(StandardCharsets.UTF_8));
        fanout.onEnvelope("{\"d\":\"/user/queue/x\",\"p\":{}}".getBytes(StandardCharsets.UTF_8));
        fanout.onEnvelope("not json".getBytes(StandardCharsets.UTF_8));

        assertThat(delivered).isEmpty();
    }
}
