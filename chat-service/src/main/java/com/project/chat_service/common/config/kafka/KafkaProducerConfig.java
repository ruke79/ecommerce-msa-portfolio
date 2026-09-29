package com.project.chat_service.common.config.kafka;

import com.project.common.kafka.avro.AvroDtoSerializer;
import com.google.common.collect.ImmutableMap;
import com.project.chat_service.dto.MessageDto;

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

    //Kafka ProduceFactory를 생성하는 Bean 메서드
    @Bean
    public ProducerFactory<String, MessageDto> producerFactory() {

        // 값은 Avro 단일 객체 인코딩(docs/38 §1.16). 인스턴스로 넘기므로 맵의 VALUE_SERIALIZER 는 무시된다.
        ProducerFactory<String, MessageDto> factory =
        new DefaultKafkaProducerFactory<>(producerConfig(),
                new StringSerializer(), new AvroDtoSerializer<>(new ChatMessageAvroMapping()));
        

        return factory;
    }
    // Kafka Producer 구성을 위한 설정값들을 포함한 맵을 반환하는 메서드
    @Bean
    public Map<String, Object> producerConfig() {
        return ImmutableMap.<String, Object>builder()
                .put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServerUrl)
                .put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class)
                .put(ProducerConfig.PARTITIONER_CLASS_CONFIG, AggregateKeyPartitioner.class)
                .put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class)                                
                .putAll(securityProps())
                .build();
    }

    // KafkaTemplate을 생성하는 Bean 메서드
    @Bean
    public KafkaTemplate<String, MessageDto > kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    /**
     * 상담 답변 알림(docs/58 §10, 토픽 support-reply) — JSON 문자열. 채팅 메시지(Avro)와 값 직렬화가 달라 따로 둔다.
     * 받는 쪽(notification-service)의 {@code AvroJsonTextDeserializer} 는 Avro 가 아니면 문자열 그대로 넘긴다.
     */
    @Bean
    public KafkaTemplate<String, String> supportEventKafkaTemplate() {
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(producerConfig(),
                new StringSerializer(), new StringSerializer()));
    }

}
