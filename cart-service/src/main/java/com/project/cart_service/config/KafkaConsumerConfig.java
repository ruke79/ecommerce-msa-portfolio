package com.project.cart_service.config;

import com.project.common.kafka.avro.AvroDtoDeserializer;
import com.project.common.kafka.avro.CartEmptyRequestAvroMapping;
import com.project.common.kafka.avro.CartRollbackRequestAvroMapping;
import com.project.common.kafka.avro.CouponUseRequestAvroMapping;
import com.google.common.collect.ImmutableMap;
import com.project.common.message.dto.request.UserCreatedRequest;
import com.project.common.message.dto.request.CartEmptyRequest;
import com.project.common.message.dto.request.CartRollbackRequest;
import com.project.common.message.dto.request.CouponRollbackRequest;
import com.project.common.message.dto.request.CouponUseRequest;

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
    public ConsumerFactory<String, String> paymentFailedConsumerFactory() {
        // 헤더(__TypeId__)가 있으면 쓰고, 없으면 String 로 읽는다 — CDC 릴레이는 헤더를 못 싣는다(docs/38 §5 A3).
        JsonDeserializer<String> deserializer = new JsonDeserializer<>(String.class, true);
        deserializer.addTrustedPackages("*");

        Map<String, Object> consumerConfigurations = ImmutableMap.<String, Object>builder()
                .put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServerUrl)
                .put(ConsumerConfig.GROUP_ID_CONFIG, "payment")
                .put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, RebalanceSettings.assignors(environment))
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

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> paymentFailedListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(paymentFailedConsumerFactory());
        factory.getContainerProperties().setConsumerRebalanceListener(new LoggingRebalanceListener());
        factory.setCommonErrorHandler(kafkaErrorHandler);
        factory.getContainerProperties().setObservationEnabled(true);

        return factory;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, CouponUseRequest> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, CouponUseRequest> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(couponUseConsumerFactory());
        factory.getContainerProperties().setConsumerRebalanceListener(new LoggingRebalanceListener());
        factory.setBatchListener(true);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }

    @Bean
    public ConsumerFactory<String, CouponUseRequest> couponUseConsumerFactory() {
        // 헤더(__TypeId__)가 있으면 쓰고, 없으면 CouponUseRequest 로 읽는다 — CDC 릴레이는 헤더를 못 싣는다(docs/38 §5 A3).
        JsonDeserializer<CouponUseRequest> deserializer = new JsonDeserializer<>(CouponUseRequest.class, true);
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
                        new AvroDtoDeserializer<>(new CouponUseRequestAvroMapping())));

    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, CartEmptyRequest> cartEmptyContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, CartEmptyRequest> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(cartEmptyConsumerFactory());
        factory.getContainerProperties().setConsumerRebalanceListener(new LoggingRebalanceListener());
        factory.setBatchListener(true);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }

    @Bean
    public ConsumerFactory<String, CartEmptyRequest> cartEmptyConsumerFactory() {
        // 헤더(__TypeId__)가 있으면 쓰고, 없으면 CartEmptyRequest 로 읽는다 — CDC 릴레이는 헤더를 못 싣는다(docs/38 §5 A3).
        JsonDeserializer<CartEmptyRequest> deserializer = new JsonDeserializer<>(CartEmptyRequest.class, true);
        deserializer.addTrustedPackages("*");

        Map<String, Object> consumerConfigurations = ImmutableMap.<String, Object>builder()
                .put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServerUrl)
                .put(ConsumerConfig.GROUP_ID_CONFIG, "cart")
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
                        new AvroDtoDeserializer<>(new CartEmptyRequestAvroMapping())));
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, CartRollbackRequest> cartRollbackContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, CartRollbackRequest> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(cartRollbackConsumerFactory());
        factory.getContainerProperties().setConsumerRebalanceListener(new LoggingRebalanceListener());
        factory.setBatchListener(true);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }

    @Bean
    public ConsumerFactory<String, CartRollbackRequest> cartRollbackConsumerFactory() {
        // 헤더(__TypeId__)가 있으면 쓰고, 없으면 CartRollbackRequest 로 읽는다 — CDC 릴레이는 헤더를 못 싣는다(docs/38 §5 A3).
        JsonDeserializer<CartRollbackRequest> deserializer = new JsonDeserializer<>(CartRollbackRequest.class, true);
        deserializer.addTrustedPackages("*");

        Map<String, Object> consumerConfigurations = ImmutableMap.<String, Object>builder()
                .put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServerUrl)
                .put(ConsumerConfig.GROUP_ID_CONFIG, "cart-rollback")
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
                        new AvroDtoDeserializer<>(new CartRollbackRequestAvroMapping())));
    }
}
