package com.project.delivery_service.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.kafka.clients.producer.ProducerConfig;
import com.project.common.kafka.AggregateKeyPartitioner;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import com.project.common.config.KafkaSecuritySettings;
import org.springframework.core.convert.ConversionException;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.messaging.converter.MessageConversionException;

import com.google.common.collect.ImmutableMap;

/**
 * Kafka 컨슈머 공통 에러 처리(DLQ) 설정 — docs/03 R-2, docs/09 참고.
 *
 * 기존에는 에러 핸들러가 전혀 없어 poison message 1건이 파티션 전체를 무한 재시도로
 * 블로킹했다(docs/03 F-2). 이 설정은:
 *  1) 처리 실패 레코드를 지수 백오프로 3회 재시도 후 <topic>.DLT 로 격리하고,
 *  2) 역직렬화 실패(복구 불가)는 재시도 없이 즉시 DLT 로 보낸다.
 *
 * 리스너는 BatchListenerFailedException(index)을 던져 "배치의 몇 번째 레코드가
 * 실패했는지"를 알려야, 이미 처리된 앞 레코드들의 오프셋은 커밋되고 실패 레코드만
 * 격리된 뒤 나머지가 재전달된다 (각 서비스 Kafka 리스너 참조).
 */
@Configuration
public class KafkaDltConfig {

    /**
     * Kafka 보안 프로퍼티(SSL/SASL)는 {@link KafkaSecuritySettings} 한 곳에서 만든다.
     * 예전에는 이 클래스마다 같은 여섯 줄을 복제했고, 값이 비어 있어도 그대로 넣어
     * SSL 을 켜는 순간 빈 경로로 죽는 조합이었다.
     */
    @Autowired
    private Environment environment;

    private Map<String, Object> securityProps() {
        return KafkaSecuritySettings.from(environment);
    }

    @Value("${kafka.url}")
    private String kafkaServerUrl;

    private Map<String, Object> dltProducerProps(Class<?> valueSerializer) {
        return ImmutableMap.<String, Object>builder()
                .put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServerUrl)
                .put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class)
                .put(ProducerConfig.PARTITIONER_CLASS_CONFIG, AggregateKeyPartitioner.class)
                .put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, valueSerializer)
                .putAll(securityProps())
                .build();
    }

    /** 정상 역직렬화된 페이로드를 DLT 로 보낼 때 사용(JSON 재직렬화). */
    @Bean
    public KafkaTemplate<String, Object> dltJsonKafkaTemplate() {
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(dltProducerProps(JsonSerializer.class)));
    }

    /** 역직렬화 실패 레코드의 원본 바이트를 DLT 로 보낼 때 사용. */
    @Bean
    public KafkaTemplate<String, byte[]> dltBytesKafkaTemplate() {
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(dltProducerProps(ByteArraySerializer.class)));
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(
            KafkaTemplate<String, Object> dltJsonKafkaTemplate,
            KafkaTemplate<String, byte[]> dltBytesKafkaTemplate,
            io.micrometer.core.instrument.MeterRegistry meters) {

        // 값 타입에 따라 템플릿 선택: 역직렬화 실패면 byte[](원본 보존), 그 외엔 JSON.
        Map<Class<?>, KafkaOperations<?, ?>> templates = new LinkedHashMap<>();
        templates.put(byte[].class, dltBytesKafkaTemplate);
        templates.put(Object.class, dltJsonKafkaTemplate);

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(templates,
                // 파티션 -1: DLT 의 파티션 수가 원본 토픽과 달라도 안전하게 브로커가 선택
                (record, ex) -> new TopicPartition(record.topic() + ".DLT", -1));

        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(1_000L);
        backOff.setMultiplier(2.0);

        // DLT 로 가는 순간을 센다(경보 KafkaDeadLetterPublished) · 일시적인 실패는 약 3분 다시 한다 — order · catalog · pay 와 같은
        // 공통 정책(DltRetryPolicy)에, 이 서비스는 order-service 호출의 일시 실패(닿지 못함 · 5xx · 429)를 더한다.
        // 🐛 예전에는 모든 실패가 1 · 2 · 4초 세 번 뒤 payment.DLT 였고 세지도 않았다 — order-service 가 배포 · 페일오버로 10초만
        // 멈춰도 그동안의 결제 완료가 모두 DLT 로 가, 배송 없이 PAID 로 남은 주문을 아무도 몰랐다.
        // [JA] DLT へ送った瞬間を数え、一時的な失敗(order-service に届かない · 5xx · 429 を含む)は約 3 分再試行する。以前は
        //      すべて 7 秒で payment.DLT、数えもせず、order-service が 10 秒止まるだけで配送のない注文が生まれていた。
        DefaultErrorHandler handler = new DefaultErrorHandler(com.project.common.kafka.DltRetryPolicy.counting(recoverer, meters), backOff);
        handler.setBackOffFunction((record, ex) -> isTransient(ex)
                ? com.project.common.kafka.DltRetryPolicy.transientBackOff() : null);
        // 재시도해도 절대 성공할 수 없는 예외는 즉시 DLT 로 (poison 즉시 격리)
        handler.addNotRetryableExceptions(
                DeserializationException.class,
                MessageConversionException.class,
                ConversionException.class,
                ClassCastException.class);
        return handler;
    }

    /** 시간이 지나면 같은 메시지가 성공할 수 있는 실패 — 공통(DB) + order-service 호출의 닿지 못함 · 5xx · 429. 404 등은 아니다. */
    static boolean isTransient(Throwable ex) {
        if (com.project.common.kafka.DltRetryPolicy.isTransient(ex)) {
            return true;
        }
        for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof feign.RetryableException) {
                return true;
            }
            if (t instanceof feign.FeignException fe && (fe.status() >= 500 || fe.status() == 429)) {
                return true;
            }
            if (t instanceof java.net.ConnectException || t instanceof java.net.SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }
}
