package com.project.delivery_service.config;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.producer.ProducerConfig;
import com.project.common.kafka.AggregateKeyPartitioner;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import com.project.common.config.KafkaSecuritySettings;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * 배송 상태 이벤트 발행용 프로듀서.
 *
 * <p>스토어 형식을 하드코딩하지 않는다 — {@code .p12} 를 {@code "JKS"} 로 박아 두면
 * mTLS 를 켜는 순간 기동 불가가 된다(B-29).
 */
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
    public ProducerFactory<String, String> deliveryProducerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServerUrl);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.PARTITIONER_CLASS_CONFIG, AggregateKeyPartitioner.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.putAll(securityProps());
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> deliveryProducerFactory) {
        return new KafkaTemplate<>(deliveryProducerFactory);
    }
}
