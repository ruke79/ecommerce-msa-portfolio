package com.project.order_service.config;

import com.google.common.collect.ImmutableMap;

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
    @Value("${kafka.url}")
    private String kafkaServerUrl;
    

    // user-created 구독은 없앴다 — order-service 는 고객 사본이 필요 없다. 주문자 id 는 토큰의 uid(User-Id)다
    // (docs/48 §6-①). 기본 이름의 팩토리(kafkaListenerContainerFactory)도 그 리스너 하나만 쓰던 것이라 함께 뺐다.

    @Bean
    public ConsumerFactory<String, PaymentResponse> paymentResponseConsumerFactory() {
        // 헤더(__TypeId__)가 있으면 쓰고, 없으면 PaymentResponse 로 읽는다 — CDC 릴레이는 헤더를 못 싣는다(docs/38 §5 A3).
        JsonDeserializer<PaymentResponse> deserializer = new JsonDeserializer<>(PaymentResponse.class, true);
        deserializer.addTrustedPackages("*");
        Map<String, Object> consumerConfigurations = ImmutableMap.<String, Object>builder()
                .put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServerUrl)
                .put(ConsumerConfig.GROUP_ID_CONFIG, "payment-response")
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

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, PaymentResponse> paymentResponseListenerContainerFactory(
            org.springframework.kafka.listener.DefaultErrorHandler kafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, PaymentResponse> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(paymentResponseConsumerFactory());
        factory.getContainerProperties().setConsumerRebalanceListener(new LoggingRebalanceListener());
        factory.setBatchListener(true);
        // 사가의 핵심 토픽(payment): 실패 레코드는 재시도 후 payment.DLT 로 격리해
        // 파티션 블로킹(주문 영구 PENDING, docs/03 F-2)을 방지한다.
        factory.setCommonErrorHandler(kafkaErrorHandler);
        factory.getContainerProperties().setObservationEnabled(true);
        // 🔴 레코드 어댑터(setBatchToRecordAdapter)를 두지 않는다 — DefaultBatchToRecordAdapter 는 리스너의 예외를 잡아 recoverer 에
        //    넘기기만 하고 다시 던지지 않는다. 예전 recoverer 는 레코드를 필드에 담기만 해서, 결제 응답 처리의 실패가 위의 재시도
        //    (DltRetryPolicy 약 3분) · payment.DLT · 경보에 한 번도 닿지 않고 사라졌다 — 돈은 냈는데 주문은 결제 전(가드 B-88).
        // [JA] レコードアダプターを置かない — 決済応答の失敗が再試行 · DLT · 警報に届かず消えていた。

        return factory;

    }

    /**
     * {@code delivery-status} — delivery-service 가 JSON 문자열로 발행한다(DTO 없음).
     *
     * <p>그룹을 따로 둔다. notification-service 도 같은 토픽을 듣지만 그쪽은 알림용이고
     * 여기는 주문 상태용이다 — 한쪽이 밀려도 다른 쪽이 멈추지 않는다. 문자열로 받아
     * 필요한 필드만 읽는다({@code DeliveryStatusKafkaListener}).
     */
    @Bean
    public ConsumerFactory<String, String> deliveryStatusConsumerFactory() {
        Map<String, Object> config = ImmutableMap.<String, Object>builder()
                .put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServerUrl)
                .put(ConsumerConfig.GROUP_ID_CONFIG, "order-delivery-status")
                .put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, RebalanceSettings.assignors(environment))
                .put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)
                .put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)
                .put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed")
                .put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")
                .put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
                .putAll(securityProps())
                .build();
        // 지금은 JSON 이지만 이 토픽이 Avro 로 바뀌어도 JSON 텍스트로 읽는다(docs/38 §1.16).
        return new DefaultKafkaConsumerFactory<>(config, new StringDeserializer(),
                new org.springframework.kafka.support.serializer.ErrorHandlingDeserializer<>(
                        new com.project.common.kafka.avro.AvroJsonTextDeserializer()));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> deliveryStatusListenerContainerFactory(
            org.springframework.kafka.listener.DefaultErrorHandler kafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(deliveryStatusConsumerFactory());
        factory.getContainerProperties().setConsumerRebalanceListener(new LoggingRebalanceListener());
        factory.setBatchListener(true);
        // 실패 레코드는 재시도 후 delivery-status.DLT 로 격리한다(B-42, ops/02).
        factory.setCommonErrorHandler(kafkaErrorHandler);
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }

    /**
     * {@code stock-shortage} — catalog 가 "결제됐지만 n개 모자라다" 고 알린다(docs/53 §초과 판매). 값은 JSON 이고 리스너가
     * 필드 이름으로 읽는다. 돈이 걸린 알림이라 이 그룹이 처음 뜨기 전에 쌓인 것부터 읽는다(earliest) — 처리는
     * 멱등하다(주문의 shortageEventId).
     */
    @Bean
    public ConsumerFactory<String, String> stockShortageConsumerFactory() {
        Map<String, Object> config = ImmutableMap.<String, Object>builder()
                .put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServerUrl)
                .put(ConsumerConfig.GROUP_ID_CONFIG, "order-stock-shortage")
                .put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, RebalanceSettings.assignors(environment))
                .put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)
                .put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)
                .put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed")
                .put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                .put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
                .putAll(securityProps())
                .build();
        return new DefaultKafkaConsumerFactory<>(config, new StringDeserializer(),
                new org.springframework.kafka.support.serializer.ErrorHandlingDeserializer<>(new StringDeserializer()));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> stockShortageListenerContainerFactory(
            org.springframework.kafka.listener.DefaultErrorHandler kafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(stockShortageConsumerFactory());
        factory.getContainerProperties().setConsumerRebalanceListener(new LoggingRebalanceListener());
        factory.setBatchListener(true);
        // 자동 환불이 실패하면 재시도 후 stock-shortage.DLT 로 격리한다(B-42, ops/02). 실패마다 "수동 환불 필요" 가 남는다.
        factory.setCommonErrorHandler(kafkaErrorHandler);
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }
}
