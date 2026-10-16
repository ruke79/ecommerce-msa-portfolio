package com.project.pay_service.message.config;

import com.google.common.collect.ImmutableMap;
import com.project.common.message.dto.request.PaymentRequest;
import com.project.common.message.dto.request.UserCreatedRequest;

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
import com.project.common.kafka.avro.PaymentRequestAvroMapping;
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
    public ConsumerFactory<String, PaymentRequest> paymentRequestConsumerFactory() {
        // 헤더(__TypeId__)가 있으면 쓰고, 없으면 PaymentRequest 로 읽는다 — CDC 릴레이는 헤더를 못 싣는다(docs/38 §5 A3).
        JsonDeserializer<PaymentRequest> deserializer = new JsonDeserializer<>(PaymentRequest.class, true);
        deserializer.addTrustedPackages("*");

        Map<String, Object> consumerConfigurations = ImmutableMap.<String, Object>builder()
                .put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServerUrl)
                .put(ConsumerConfig.GROUP_ID_CONFIG, "payment-request")
                .put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, RebalanceSettings.assignors(environment))
                .put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)
                .put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, deserializer)
                .put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed")
                // 그룹 오프셋이 없을 때(첫 배포 · 보존 기간 만료) 쌓인 결제 요청을 건너뛰지 않는다(PY7) — 다시 처리해도 중복 판정이 흡수한다.
                .put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                .put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
                .putAll(securityProps())
                .build();

        return new DefaultKafkaConsumerFactory<>(consumerConfigurations, new StringDeserializer(),
                new org.springframework.kafka.support.serializer.ErrorHandlingDeserializer<>(
                        // Avro 또는 JSON 을 같은 DTO 로 — 롤링 배포·CDC·e2e 의 JSON 도 읽는다(docs/38 §1.16).
                        new AvroDtoDeserializer<>(new PaymentRequestAvroMapping())));

    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, PaymentRequest> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, PaymentRequest> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(paymentRequestConsumerFactory());
        factory.getContainerProperties().setConsumerRebalanceListener(new LoggingRebalanceListener());
        factory.setBatchListener(true);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        factory.getContainerProperties().setObservationEnabled(true);
        // 🔴 레코드 어댑터(setBatchToRecordAdapter)를 두지 않는다 — DefaultBatchToRecordAdapter 는 리스너의 예외를 잡아 recoverer 에
        //    넘기기만 하고 다시 던지지 않는다. 예전 recoverer 는 레코드를 필드에 담기만 해서, 결제 요청 처리에 실패하면 재시도 · DLT ·
        //    경보 없이 오프셋이 커밋됐다(손님은 돈을 냈는데 결제 응답이 영영 나가지 않았다). 배치 그대로 받아 실패 위치를
        //    BatchListenerFailedException 으로 에러 핸들러에 넘긴다(가드 B-88).
        // [JA] レコードアダプターを置かない — 例外を recoverer に渡すだけで再送出しない。以前は決済リクエストの失敗が
        //      再試行 · DLT · 警報なしに消えていた。

        return factory;

    }
}
