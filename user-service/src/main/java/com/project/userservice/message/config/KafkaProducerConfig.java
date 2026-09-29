package com.project.userservice.message.config;

import com.project.common.kafka.avro.AvroDtoSerializer;
import com.project.common.kafka.avro.UserCreatedRequestAvroMapping;
import com.google.common.collect.ImmutableMap;
import com.project.common.message.dto.request.UserCreatedRequest;
import com.project.userservice.dto.AlarmEventDto;

import org.apache.kafka.clients.producer.ProducerConfig;
import com.project.common.kafka.AggregateKeyPartitioner;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import com.project.common.config.KafkaSecuritySettings;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
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


    // @Bean
    // public KafkaAdmin.NewTopics newTopics() {
    //     return new KafkaAdmin.NewTopics(
    //             TopicBuilder.name("user-created")
    //             .partitions(3)
    //             .replicas(1)
    //             .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(1000 * 60 * 60))
    //             .build(),
    //             TopicBuilder.name("order")
    //             .partitions(4)
    //             .replicas(1)
    //             .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(1000 * 60 * 60))
    //             .build(),
    //             TopicBuilder.name("payment")
    //                      .partitions(4)
    //                      .replicas(1)
    //                      .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(1000 * 60 * 60))
    //                     .build()
    //     );
    // }


    @Bean
    public ProducerFactory<String, UserCreatedRequest> userCreatedProducerFactory() {
        // 값은 Avro 단일 객체 인코딩(docs/38 §1.16). 인스턴스로 넘기므로 맵의 VALUE_SERIALIZER 는 무시된다.
        return new DefaultKafkaProducerFactory<>(producerConfig(),
                new StringSerializer(), new AvroDtoSerializer<>(new UserCreatedRequestAvroMapping()));
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
   

    @Bean
    public KafkaTemplate<String, UserCreatedRequest> kafkaTemplate() {
        return new KafkaTemplate<>(userCreatedProducerFactory());
    }

    //  @Bean
    // public ProducerFactory<String, AlarmEventDto> notificationProducerFactory() {
    //     return new DefaultKafkaProducerFactory<>(producerConfig());
    // }
    // @Bean
    // public KafkaTemplate<String, AlarmEventDto> notificationKafkaTemplate() {
    //     return new KafkaTemplate<>(notificationProducerFactory());
    // }
    
}
