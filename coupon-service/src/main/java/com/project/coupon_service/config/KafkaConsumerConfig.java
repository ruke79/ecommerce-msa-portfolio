package com.project.coupon_service.config;

import com.project.common.kafka.avro.CouponRollbackRequestAvroMapping;
import com.project.common.kafka.avro.UserCreatedRequestAvroMapping;
import com.google.common.collect.ImmutableMap;
import com.project.common.message.dto.request.CouponRollbackRequest;
import com.project.common.message.dto.request.UserCreatedRequest;
import com.project.common.message.dto.response.PaymentResponse;

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
import com.project.common.kafka.avro.AvroDtoDeserializer;
import com.project.common.kafka.avro.PaymentResponseAvroMapping;
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
    ConcurrentKafkaListenerContainerFactory<String, UserCreatedRequest> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, UserCreatedRequest> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(userCreatedConsumerFactory());
        factory.getContainerProperties().setConsumerRebalanceListener(new LoggingRebalanceListener());
        factory.setBatchListener(false);
        // ⚠️ 이 팩토리에만 에러 핸들러가 빠져 있었다. 이름이 kafkaListenerContainerFactory 라
        //    **containerFactory 를 지정하지 않은 리스너의 기본값**인데, UserCreatedConsumer 가
        //    정확히 그렇다. 그래서 user-created.DLT 는 만들어져 있어도 쓰이지 않았고,
        //    poison message 하나가 그 파티션을 무한 재시도로 막는다 — DLQ 를 넣은 이유였던
        //    바로 그 장애다(docs/03 F-2). 같은 파일의 다른 두 팩토리는 이미 붙이고 있었다.
        factory.setCommonErrorHandler(kafkaErrorHandler);
        // 이 팩토리만 레코드 단위라 스프링의 컨슈머 추적이 그대로 걸린다 — 켜기만 하면 된다. 빠져 있어서
        // 가입 쿠폰 발급이 가입 요청의 추적에서 떨어져 보였다(docs/51 §4h).
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
                .put(ConsumerConfig.GROUP_ID_CONFIG, "user-2")
                .put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, RebalanceSettings.assignors(environment))
                .put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)
                .put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, deserializer)
                .put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed")
                .put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")
                .put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
                .putAll(securityProps())
                .build();

        return new DefaultKafkaConsumerFactory<>(consumerConfigurations, new StringDeserializer(),
                new org.springframework.kafka.support.serializer.ErrorHandlingDeserializer<>(
                        // Avro 또는 JSON 을 같은 DTO 로 — 롤링 배포·CDC·e2e 의 JSON 도 읽는다(docs/38 §1.16).
                        new AvroDtoDeserializer<>(new UserCreatedRequestAvroMapping())));
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, CouponRollbackRequest> couponRollbackContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, CouponRollbackRequest> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(couponRollbackConsumerFactory());
        factory.getContainerProperties().setConsumerRebalanceListener(new LoggingRebalanceListener());
        factory.setBatchListener(true);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }

    @Bean
    public ConsumerFactory<String, CouponRollbackRequest> couponRollbackConsumerFactory() {
        // 헤더(__TypeId__)가 있으면 쓰고, 없으면 CouponRollbackRequest 로 읽는다 — CDC 릴레이는 헤더를 못 싣는다(docs/38 §5 A3).
        JsonDeserializer<CouponRollbackRequest> deserializer = new JsonDeserializer<>(CouponRollbackRequest.class, true);
        deserializer.addTrustedPackages("*");

        Map<String, Object> consumerConfigurations = ImmutableMap.<String, Object>builder()
                .put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServerUrl)
                .put(ConsumerConfig.GROUP_ID_CONFIG, "coupon")
                .put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, RebalanceSettings.assignors(environment))
                .put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)
                .put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, deserializer)
                .put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed")
                .put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")
                .put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
                .putAll(securityProps())
                .build();

        return new DefaultKafkaConsumerFactory<>(consumerConfigurations, new StringDeserializer(),
                new org.springframework.kafka.support.serializer.ErrorHandlingDeserializer<>(
                        // Avro 또는 JSON 을 같은 DTO 로 — 롤링 배포·CDC·e2e 의 JSON 도 읽는다(docs/38 §1.16).
                        new AvroDtoDeserializer<>(new CouponRollbackRequestAvroMapping())));
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, PaymentResponse> paymentResponseContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, PaymentResponse> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(paymentResponseConsumerFactory());
        factory.getContainerProperties().setConsumerRebalanceListener(new LoggingRebalanceListener());
        factory.setBatchListener(true);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }

    @Bean
    public ConsumerFactory<String, PaymentResponse> paymentResponseConsumerFactory() {
        // 헤더(__TypeId__)가 있으면 쓰고, 없으면 PaymentResponse 로 읽는다 — CDC 릴레이는 헤더를 못 싣는다(docs/38 §5 A3).
        JsonDeserializer<PaymentResponse> deserializer = new JsonDeserializer<>(PaymentResponse.class, true);
        deserializer.addTrustedPackages("*");

        Map<String, Object> consumerConfigurations = ImmutableMap.<String, Object>builder()
                .put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServerUrl)
                .put(ConsumerConfig.GROUP_ID_CONFIG, "coupon-used")
                .put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, RebalanceSettings.assignors(environment))
                .put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)
                .put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, deserializer)
                .put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed")
                .put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")
                .put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
                .putAll(securityProps())
                .build();

        return new DefaultKafkaConsumerFactory<>(consumerConfigurations, new StringDeserializer(),
                new org.springframework.kafka.support.serializer.ErrorHandlingDeserializer<>(
                        // Avro 또는 JSON 을 같은 DTO 로 — 롤링 배포·CDC·e2e 의 JSON 도 읽는다(docs/38 §1.16).
                        new AvroDtoDeserializer<>(new PaymentResponseAvroMapping())));
    }
}
