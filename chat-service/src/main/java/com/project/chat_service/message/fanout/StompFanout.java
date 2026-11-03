package com.project.chat_service.message.fanout;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.converter.CompositeMessageConverter;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.stereotype.Component;

import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.util.MimeTypeUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.Metrics;
import lombok.extern.slf4j.Slf4j;

/**
 * 구독자에게 보내는 STOMP 메시지를 <b>모든 chat 파드</b>에 퍼뜨린다 — Redis pub/sub 팬아웃(docs/21 §2).
 *
 * <p>🐛 예전: 브로커가 인메모리({@code enableSimpleBroker})라 구독은 각 파드의 메모리에만 있고, Kafka 컨슈머 그룹은 메시지를
 * 파드 <b>하나에만</b>준다. 파드가 둘이면 A 에 붙은 사용자는 B 가 소비한 메시지를 받지 못했다 — 그래서 replicas 1 고정(B-34)이었다.
 * 이제 소비한 파드는 {@link #CHANNEL} 에 한 번 발행하고, 모든 파드(자기 자신 포함)가 받아 각자의 구독자에게 보낸다.
 * 저장은 그대로 컨슈머 그룹 하나에서 한 번만 한다(MessageConsumer) — 뿌리는 경로만 바뀐다.
 *
 * <ul>
 *   <li><b>형식은 그대로</b> — 페이로드를 STOMP 변환기({@code brokerMessageConverter} — Spring Boot 가 앱의 ObjectMapper 를 건다)의
 *       ObjectMapper 로 <b>JSON 문자열</b>로 만들어 싣고, 받은 파드는 그 바이트를 그대로({@code application/json}) 보낸다. JSON 트리로
 *       옮기면 숫자 표현이 바뀐다(시각 {@code 1793491200.000000000} → {@code 1.7934912E9} — StompFanoutTest 가 잡았다).</li>
 *   <li><b>순서</b> — 받는 쪽은 스레드 하나로 차례대로 보낸다({@link StompFanoutConfig}). 같은 방의 메시지는 Kafka 키(방)로 이미
 *       순서대로 소비되고, Redis 는 한 연결의 발행 순서대로 전달한다.</li>
 *   <li><b>Redis 에 못 실으면 이 파드의 구독자에게라도</b> 보낸다(파드 하나일 때는 예전과 같다) — 다른 파드의 구독자는 그 메시지를
 *       실시간으로 놓친다(이력에는 있다). 계수기 {@value #FAILED_METRIC} · 경보 {@code ChatFanoutPublishFailed}.</li>
 *   <li>Redis pub/sub 은 최대 한 번 전달이다 — 구독 연결이 잠깐 끊긴 파드는 그동안의 실시간 메시지를 놓친다. 메시지는 먼저 저장되므로
 *       다시 열면 이력에 있다.</li>
 * </ul>
 *
 * <p>[JA] 購読者への STOMP メッセージをすべての chat Pod に広げる(Redis pub/sub のファンアウト)。以前はインメモリのブローカーと
 * コンシューマーグループのせいで Pod が二つだと別の Pod の購読者に届かず、replicas 1 固定だった。保存は一度だけのまま、配る経路だけ変える。
 */
@Slf4j
@Component
public class StompFanout {

    /** 모든 chat 파드가 구독하는 Redis 채널. */
    public static final String CHANNEL = "chat:stomp-fanout";

    /** Redis 에 싣지 못해 이 파드의 구독자에게만 보낸 수 — 경보 {@code ChatFanoutPublishFailed}. */
    public static final String FAILED_METRIC = "chat.stomp.fanout.failed";

    /** 받은 메시지를 보낼 수 있는 주소 — SimpleBroker 의 접두사. 다른 주소는 버린다. */
    static final String BROKER_PREFIX = "/sub/";

    private final StringRedisTemplate redis;
    private final SimpMessageSendingOperations template;
    private final ObjectMapper mapper;

    public StompFanout(StringRedisTemplate redis, SimpMessageSendingOperations template,
            @Qualifier("brokerMessageConverter") CompositeMessageConverter brokerConverter) {
        this.redis = redis;
        this.template = template;
        this.mapper = stompObjectMapper(brokerConverter);
    }

    /**
     * 경보가 {@code increase()} 로 보는 계수기를 기동 때 0 으로 만든다(B-101).
     * [JA] 警報が increase() で見るカウンターを起動時に 0 で作る。
     */
    @jakarta.annotation.PostConstruct
    void registerAlertCounters() {
        Metrics.counter(FAILED_METRIC);
    }

    /** STOMP 변환기가 쓰는 ObjectMapper — 구독자가 받는 JSON 을 예전과 같게. 없으면(테스트) 모듈을 찾은 기본값. */
    static ObjectMapper stompObjectMapper(CompositeMessageConverter brokerConverter) {
        if (brokerConverter != null) {
            for (var c : brokerConverter.getConverters()) {
                if (c instanceof MappingJackson2MessageConverter jackson) {
                    return jackson.getObjectMapper();
                }
            }
        }
        return new ObjectMapper().findAndRegisterModules();
    }

    /** 모든 파드의 {@code destination} 구독자에게 보낸다. 실패는 호출자에게 올리지 않는다(저장은 이미 끝났다). */
    public void send(String destination, Object payload) {
        String body;
        String envelope;
        try {
            body = mapper.writeValueAsString(payload);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("d", destination);
            e.put("p", body);
            envelope = mapper.writeValueAsString(e);
        } catch (Exception e) {
            // 직렬화가 안 되는 페이로드면 예전처럼 변환기에 맡긴다(실패도 예전과 같다)
            log.warn("STOMP 팬아웃 직렬화 실패 — 이 파드에만 보낸다: destination={} ({})", destination, e.getClass().getSimpleName());
            try {
                template.convertAndSend(destination, payload);
            } catch (RuntimeException ex) {
                log.warn("STOMP 전송 실패: destination={} ({})", destination, ex.getClass().getSimpleName());
            }
            return;
        }
        try {
            redis.convertAndSend(CHANNEL, envelope);
        } catch (RuntimeException e) {
            Metrics.counter(FAILED_METRIC).increment();
            log.warn("STOMP 팬아웃 발행 실패 — 이 파드의 구독자에게만 보낸다: destination={} ({})",
                    destination, e.getClass().getSimpleName());
            deliverLocally(destination, body);
        }
    }

    /** Redis 에서 받은 봉투 하나 — 이 파드의 구독자에게 보낸다. 어떤 실패도 리스너 밖으로 내보내지 않는다. */
    void onEnvelope(byte[] raw) {
        try {
            JsonNode e = mapper.readTree(new String(raw, StandardCharsets.UTF_8));
            String destination = e.path("d").asText(null);
            String body = e.path("p").isTextual() ? e.get("p").asText() : null;
            if (destination == null || !destination.startsWith(BROKER_PREFIX) || body == null) {
                log.warn("STOMP 팬아웃: 모르는 봉투를 버린다");
                return;
            }
            deliverLocally(destination, body);
        } catch (Exception ex) {
            log.warn("STOMP 팬아웃 수신 처리 실패 ({})", ex.getClass().getSimpleName());
        }
    }

    /** 이미 JSON 인 바이트를 그대로 — 변환기를 다시 거치지 않는다(직접 보내기와 같은 바이트 · {@code application/json}). */
    private void deliverLocally(String destination, String json) {
        try {
            template.send(destination, MessageBuilder.withPayload(json.getBytes(StandardCharsets.UTF_8))
                    .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.APPLICATION_JSON).build());
        } catch (RuntimeException e) {
            log.warn("STOMP 전송 실패: destination={} ({})", destination, e.getClass().getSimpleName());
        }
    }
}
