package com.project.coupon_service.config;

import com.project.common.kafka.avro.AvroDtoSerializer;
import com.project.common.kafka.avro.CouponUseRequestAvroMapping;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import com.project.common.kafka.AggregateKeyPartitioner;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import com.project.common.config.KafkaSecuritySettings;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import com.google.common.collect.ImmutableMap;

import com.project.common.message.dto.request.CouponUseRequest;

import java.util.Map;

@EnableKafka
@Configuration
public class KafkaProducerConfig {

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

    @Bean
    public Map<String, Object> producerConfigurations() {
        return ImmutableMap.<String, Object>builder()
                .put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServerUrl)
                .put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class)
                .put(ProducerConfig.PARTITIONER_CLASS_CONFIG, AggregateKeyPartitioner.class)
                .put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class)
                // 트랜잭션 없음, 멱등만 — coupon-use 는 레코드 하나다(docs/38 §1.17)
                .put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
                .put(ProducerConfig.ACKS_CONFIG, "all")
                .put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed")
                .put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")
                .put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
                .putAll(securityProps())
                .build();

    }

    @Bean
    public ProducerFactory<String, CouponUseRequest> couponUseProducerFactory() {
        // 값은 Avro 단일 객체 인코딩(docs/38 §1.16). 인스턴스로 넘기므로 맵의 VALUE_SERIALIZER 는 무시된다.
        return new DefaultKafkaProducerFactory<>(producerConfigurations(),
                new StringSerializer(), new AvroDtoSerializer<>(new CouponUseRequestAvroMapping()));

    }

    @Bean
    public KafkaTemplate<String, CouponUseRequest> couponUseKafkaTemplate() {
        return new KafkaTemplate<>(couponUseProducerFactory());
    }

    /**
     * 🐛 <b>이 빈이 없어서 coupon-service 가 기동에 실패했다.</b>
     *
     * <p>{@code CouponIssuedProducer} 는 JSON 문자열을 발행하므로
     * {@code KafkaTemplate<String, String>} 을 주입받는데, 이 설정에는 위의
     * {@code KafkaTemplate<String, CouponUseRequest>}(Avro) 하나뿐이었다. 스프링은 제네릭까지
     * 보고 후보를 거르므로 <b>맞는 빈이 하나도 없다</b>고 판단했다:
     *
     * <pre>
     * Parameter 0 of constructor in …CouponIssuedProducer required a bean of type
     * 'org.springframework.kafka.core.KafkaTemplate' that could not be found.
     * </pre>
     *
     * <p>부트의 자동 설정이 대신 만들어 주지도 않는다 — {@code KafkaAutoConfiguration} 은
     * 사용자가 정의한 {@code KafkaTemplate} 이 하나라도 있으면 통째로 물러난다
     * ({@code @ConditionalOnMissingBean}). 즉 <b>Avro 템플릿을 정의한 순간 문자열 템플릿이
     * 사라졌다.</b>
     *
     * <p>이름을 {@code couponEventKafkaTemplate} 으로 둔 이유는 catalog-service 의
     * {@code contentEventKafkaTemplate} 과 같다 — 주입받는 쪽이 어느 것을 쓰는지 이름으로
     * 드러나야 한다. 값 직렬화는 {@code StringSerializer} 다. 맵의
     * {@code VALUE_SERIALIZER_CLASS_CONFIG}(JsonSerializer)는 인스턴스를 직접 넘기므로 무시된다 —
     * 그것을 그대로 쓰면 이미 JSON 인 문자열이 <b>한 번 더 따옴표로 감싸져</b> 나간다.
     */
    @Bean
    public ProducerFactory<String, String> couponEventProducerFactory() {
        return new DefaultKafkaProducerFactory<>(producerConfigurations(),
                new StringSerializer(), new StringSerializer());
    }

    @Bean
    public KafkaTemplate<String, String> couponEventKafkaTemplate() {
        return new KafkaTemplate<>(couponEventProducerFactory());
    }

}
