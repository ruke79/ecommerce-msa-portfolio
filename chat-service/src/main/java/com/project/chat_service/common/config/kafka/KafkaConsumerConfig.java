package com.project.chat_service.common.config.kafka;

import com.project.common.kafka.avro.AvroDtoDeserializer;
import com.google.common.collect.ImmutableMap;
import com.project.common.message.dto.request.UserCreatedRequest;
import com.project.chat_service.dto.MessageDto;
import com.project.chat_service.model.ChatMessage;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import com.project.common.config.KafkaSecuritySettings;
import com.project.common.kafka.LoggingRebalanceListener;
import com.project.common.kafka.RebalanceSettings;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.HashMap;
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
    ConcurrentKafkaListenerContainerFactory<String, MessageDto> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, MessageDto> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.getContainerProperties().setConsumerRebalanceListener(new LoggingRebalanceListener());
        factory.setCommonErrorHandler(kafkaErrorHandler);
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }

    @Bean
    public ConsumerFactory<String, MessageDto> consumerFactory() {
                // 리스너(MessageConsumer)가 받는 타입은 MessageDto 다. 예전의 ChatMessage 선언은 __TypeId__ 헤더가 MessageDto 를
        // 가리켜서만 동작했다 — Avro 는 헤더가 없으니 선언을 실제 타입으로 맞춘다.
        JsonDeserializer<MessageDto> deserializer = new JsonDeserializer<>(MessageDto.class, true);
        deserializer.addTrustedPackages("*");
        Map<String, Object> consumerConfigurations = ImmutableMap.<String, Object>builder()
                .put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServerUrl)
                .put(ConsumerConfig.GROUP_ID_CONFIG, "chat")
                .put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, RebalanceSettings.assignors(environment))
                .put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)
                .put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, deserializer)
                .put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")
                .putAll(securityProps())
                .build();

        return new DefaultKafkaConsumerFactory<>(consumerConfigurations, new StringDeserializer(),
                new org.springframework.kafka.support.serializer.ErrorHandlingDeserializer<>(
                        // Avro 또는 JSON 을 MessageDto 로 — 리스너(MessageConsumer)가 받는 타입이다(docs/38 §1.16).
                        new AvroDtoDeserializer<>(new ChatMessageAvroMapping())));
    }
    // 🐛 예전: 여기에 쓰는 곳 없는 알림 팩토리(kafkaNotificationContainerFactory)가 있었다. 그룹 id 가 "notification" —
    //    notification-service 의 그룹과 같아, 누가 이 팩토리로 리스너를 붙이면 두 서비스가 파티션을 나눠 가져 서로의 메시지를 조용히
    //    잃었다. 지웠다(2026-10-16).
    // [JA] 以前は使われない通知ファクトリがあり、グループ id が notification-service と同じだった。削除した。

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, UserCreatedRequest> userCreatedContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, UserCreatedRequest> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(userCreatedConsumerFactory());
        factory.getContainerProperties().setConsumerRebalanceListener(new LoggingRebalanceListener());
        factory.setBatchListener(true);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }

    @Bean
    public ConsumerFactory<String, UserCreatedRequest> userCreatedConsumerFactory() {
        // 헤더(__TypeId__)가 있으면 쓰고, 없으면 UserCreatedRequest 로 읽는다 — CDC 릴레이는 헤더를 못 싣는다(docs/38 §5 A3).
        JsonDeserializer<UserCreatedRequest> deserializer = new JsonDeserializer<>(UserCreatedRequest.class, true);
        deserializer.addTrustedPackages("*");
        Map<String, Object> consumerConfigurations = ImmutableMap.<String, Object>builder()
                .put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServerUrl)
                .put(ConsumerConfig.GROUP_ID_CONFIG, "user-3")
                .put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, RebalanceSettings.assignors(environment))
                .put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)
                .put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, deserializer)
                .put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")
                .putAll(securityProps())
                .build();

        return new DefaultKafkaConsumerFactory<>(consumerConfigurations, new StringDeserializer(),
                new org.springframework.kafka.support.serializer.ErrorHandlingDeserializer<>(
                        // 🐛 user-service 는 user-created 를 Avro 로 쓴다(459126e). 여기만 JsonDeserializer 로 남아 있어
                        //    **모든 가입 이벤트가 역직렬화에서 실패해 DLT 로 갔다** — 채팅 회원은 접속 순간 즉석으로만
                        //    생겼고(ChatService.createMissingMember), 닉네임·사진은 한 번도 원본 값이 아니었다.
                        //    Avro 또는 JSON 을 같은 DTO 로 읽는다 — coupon 과 같다(docs/38 §1.16, docs/48 §6-⑤).
                        new AvroDtoDeserializer<>(new com.project.common.kafka.avro.UserCreatedRequestAvroMapping())));
    }

    /**
     * {@code user-updated}·{@code user-deleted} — 원본의 프로필 변경·탈퇴(docs/48 §6-⑤). 값은 JSON 문자열이고
     * 리스너가 필드 이름으로 읽는다. 컨슈머 그룹은 리스너의 {@code id} 가 된다(chat-user-updated·chat-user-deleted —
     * {@code @KafkaListener} 의 id 가 그룹을 덮어쓴다). user-created 와 그룹이 나뉘어 있어 한쪽의 DLT 격리·재처리가
     * 다른 쪽의 오프셋을 건드리지 않는다. 아래 GROUP_ID 는 id 없이 이 팩토리를 쓰는 리스너의 기본값일 뿐이다.
     */
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> userLifecycleContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(userLifecycleConsumerFactory());
        factory.getContainerProperties().setConsumerRebalanceListener(new LoggingRebalanceListener());
        factory.setBatchListener(true);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }

    @Bean
    public ConsumerFactory<String, String> userLifecycleConsumerFactory() {
        Map<String, Object> consumerConfigurations = ImmutableMap.<String, Object>builder()
                .put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServerUrl)
                .put(ConsumerConfig.GROUP_ID_CONFIG, "chat-user-lifecycle")
                .put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, RebalanceSettings.assignors(environment))
                .put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)
                .put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)
                // 탈퇴는 놓치면 안 된다 — 이 그룹이 처음 뜨기 전에 쌓인 것부터 읽는다. 처리는 멱등하다.
                .put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                .putAll(securityProps())
                .build();

        return new DefaultKafkaConsumerFactory<>(consumerConfigurations, new StringDeserializer(),
                new StringDeserializer());
    }

}
