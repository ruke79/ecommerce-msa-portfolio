package com.project.userservice.message.config;

import com.google.common.collect.ImmutableMap;
import com.project.common.dto.notification.AlarmEventDto;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import com.project.common.config.KafkaSecuritySettings;
import com.project.common.kafka.LoggingRebalanceListener;
import com.project.common.kafka.RebalanceSettings;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.Map;

@EnableKafka
@Configuration
public class KafkaConsumerConfig {

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

    // 재시도+DLT 격리 (KafkaDltConfig, docs/11 §3) — poison message 파티션 블로킹 방지
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.kafka.listener.DefaultErrorHandler kafkaErrorHandler;

    @Value("${kafka.url}")
    private String kafkaServerUrl;

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, AlarmEventDto> kafkaNotificationContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, AlarmEventDto> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(kafkaNotificationConsumer());
        factory.getContainerProperties().setConsumerRebalanceListener(new LoggingRebalanceListener());
        factory.setCommonErrorHandler(kafkaErrorHandler);
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }

    @Bean
    public ConsumerFactory<String, AlarmEventDto> kafkaNotificationConsumer() {
        JsonDeserializer<AlarmEventDto> deserializer = new JsonDeserializer<>(AlarmEventDto.class, false);
        deserializer.addTrustedPackages("*");
        Map<String, Object> consumerConfigurations = ImmutableMap.<String, Object>builder()
                .put(ConsumerConfig.GROUP_ID_CONFIG, "notification")
                .put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, RebalanceSettings.assignors(environment))
                .put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServerUrl)
                .put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)
                .put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, deserializer)
                .put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed")
                .put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")
                .put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
                .putAll(securityProps())
                .build();

        return new DefaultKafkaConsumerFactory<>(consumerConfigurations, new StringDeserializer(),
                new org.springframework.kafka.support.serializer.ErrorHandlingDeserializer<>(deserializer));
    }

}
