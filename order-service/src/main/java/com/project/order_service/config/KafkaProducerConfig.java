package com.project.order_service.config;

import com.project.common.kafka.KafkaTransactionalIds;
import com.project.common.kafka.avro.CartEmptyRequestAvroMapping;
import com.project.common.kafka.avro.CartRollbackRequestAvroMapping;
import com.project.common.kafka.avro.CouponRollbackRequestAvroMapping;
import com.project.common.kafka.avro.ProductUpdateRequestAvroMapping;
import org.apache.kafka.clients.producer.ProducerConfig;
import com.project.common.kafka.AggregateKeyPartitioner;
import com.project.common.kafka.avro.AvroDtoSerializer;
import com.project.common.kafka.avro.PaymentRequestAvroMapping;
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
import org.springframework.orm.jpa.JpaTransactionManager;

import com.google.common.collect.ImmutableMap;
import com.project.common.message.dto.request.CartEmptyRequest;
import com.project.common.message.dto.request.CartRollbackRequest;
import com.project.common.message.dto.request.CouponRollbackRequest;
import com.project.common.message.dto.request.PaymentRequest;
import com.project.common.message.dto.request.ProductUpdateRequest;
import com.project.common.message.dto.request.TokenRequest;

import jakarta.persistence.EntityManagerFactory;

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
                // 트랜잭션 없음, 멱등만 — 아웃박스 릴레이는 레코드 하나를 보내고 ack 뒤에 COMPLETED 를 찍는다. 트랜잭션은 DB 커밋 뒤에 커밋되므로 오히려 유실 창을 만들었다(docs/38 §1.17)
                .put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
                .put(ProducerConfig.ACKS_CONFIG, "all")
                .putAll(securityProps())
                .build();
    }
    

    @Bean
    public ProducerFactory<String, PaymentRequest> paymentRequestProducerFactory() {
        // 값은 Avro 단일 객체 인코딩(docs/38 §1.16). 인스턴스로 넘기므로 맵의 VALUE_SERIALIZER 는 무시된다.
        return new DefaultKafkaProducerFactory<>(producerConfigurations(),
                new StringSerializer(), new AvroDtoSerializer<>(new PaymentRequestAvroMapping()));

    }

    @Bean
    public KafkaTemplate<String, PaymentRequest> paymentRequestKafkaTemplate() {
        return new KafkaTemplate<>(paymentRequestProducerFactory());
    }

    /**
     * 주문 상태 변경(docs/76) — DTO 없이 생성 레코드({@code OrderStatusChanged})를 단일 객체 인코딩으로 그대로 싣는다.
     * saga-projection 이 같은 {@code AvroSpecificSerde} 로 읽는다.
     * [JA] 注文状態変更(docs/76) — DTO なしで生成レコードを単一オブジェクト符号化のまま載せる。
     */
    @Bean
    public ProducerFactory<String, com.project.common.avro.OrderStatusChanged> orderStatusProducerFactory() {
        return new DefaultKafkaProducerFactory<>(producerConfigurations(), new StringSerializer(),
                new com.project.common.kafka.avro.AvroSpecificSerde<>(
                        com.project.common.avro.OrderStatusChanged.getEncoder(),
                        com.project.common.avro.OrderStatusChanged.getDecoder()).serializer());
    }

    @Bean
    public KafkaTemplate<String, com.project.common.avro.OrderStatusChanged> orderStatusKafkaTemplate() {
        return new KafkaTemplate<>(orderStatusProducerFactory());
    }

    @Bean
    public Map<String, Object> couponRollbackConfigurations() {
        return ImmutableMap.<String, Object>builder()
                .put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServerUrl)
                .put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class)
                .put(ProducerConfig.PARTITIONER_CLASS_CONFIG, AggregateKeyPartitioner.class)
                .put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class)                
                // 안정적인 id — 파드 재시작 시 이전 화신을 펜싱한다. 두 토픽을 한 트랜잭션으로 보내는 사가 보상용(docs/38 §1.17)
                .put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, KafkaTransactionalIds.stable("order-saga-coupon-rollback"))
                .put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
                .putAll(securityProps())
                .build();
    }


    @Bean
    public ProducerFactory<String, CouponRollbackRequest> couponRollbackProducerFactory() {
        // 값은 Avro 단일 객체 인코딩(docs/38 §1.16). 인스턴스로 넘기므로 맵의 VALUE_SERIALIZER 는 무시된다.
        return new DefaultKafkaProducerFactory<>(couponRollbackConfigurations(),
                new StringSerializer(), new AvroDtoSerializer<>(new CouponRollbackRequestAvroMapping()));

    }

    @Bean
    public KafkaTemplate<String, CouponRollbackRequest> couponRollbackKafkaTemplate() {
        return new KafkaTemplate<>(couponRollbackProducerFactory());
    }

    @Bean
    public Map<String, Object> cartRollbackConfigurations() {
        return ImmutableMap.<String, Object>builder()
                .put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServerUrl)
                .put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class)
                .put(ProducerConfig.PARTITIONER_CLASS_CONFIG, AggregateKeyPartitioner.class)
                .put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class)                
                // 안정적인 id — 파드 재시작 시 이전 화신을 펜싱한다. 두 토픽을 한 트랜잭션으로 보내는 사가 보상용(docs/38 §1.17)
                .put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, KafkaTransactionalIds.stable("order-saga-cart-rollback"))
                .put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
                .putAll(securityProps())
                .build();
    }

    @Bean
    public ProducerFactory<String, CartRollbackRequest> cartRollbackProducerFactory() {
        // 값은 Avro 단일 객체 인코딩(docs/38 §1.16). 인스턴스로 넘기므로 맵의 VALUE_SERIALIZER 는 무시된다.
        return new DefaultKafkaProducerFactory<>(cartRollbackConfigurations(),
                new StringSerializer(), new AvroDtoSerializer<>(new CartRollbackRequestAvroMapping()));

    }

    @Bean
    public KafkaTemplate<String, CartRollbackRequest> cartRollbackKafkaTemplate() {
        return new KafkaTemplate<>(cartRollbackProducerFactory());
    }

    @Bean
    public Map<String, Object> ProductUpdateConfigurations() {
        return ImmutableMap.<String, Object>builder()
                .put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServerUrl)
                .put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class)
                .put(ProducerConfig.PARTITIONER_CLASS_CONFIG, AggregateKeyPartitioner.class)
                .put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class)                
                // 안정적인 id — 파드 재시작 시 이전 화신을 펜싱한다. 두 토픽을 한 트랜잭션으로 보내는 사가 보상용(docs/38 §1.17)
                .put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, KafkaTransactionalIds.stable("order-saga-product-update"))
                .put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
                .putAll(securityProps())
                .build();

    }

    @Bean
    public ProducerFactory<String, ProductUpdateRequest> productUpdateProducerFactory() {
        // 값은 Avro 단일 객체 인코딩(docs/38 §1.16). 인스턴스로 넘기므로 맵의 VALUE_SERIALIZER 는 무시된다.
        return new DefaultKafkaProducerFactory<>(ProductUpdateConfigurations(),
                new StringSerializer(), new AvroDtoSerializer<>(new ProductUpdateRequestAvroMapping()));

    }

    @Bean
    public KafkaTemplate<String, ProductUpdateRequest> productUpdateKafkaTemplate() {
        return new KafkaTemplate<>(productUpdateProducerFactory());
    }

    @Bean
    public Map<String, Object> cartEmptyConfigurations() {
        return ImmutableMap.<String, Object>builder()
                .put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServerUrl)
                .put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class)
                .put(ProducerConfig.PARTITIONER_CLASS_CONFIG, AggregateKeyPartitioner.class)
                .put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class)                
                // 안정적인 id — 파드 재시작 시 이전 화신을 펜싱한다. 두 토픽을 한 트랜잭션으로 보내는 사가 보상용(docs/38 §1.17)
                .put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, KafkaTransactionalIds.stable("order-saga-cart-empty"))
                .put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
                .putAll(securityProps())
                .build();

    }

    @Bean
    public ProducerFactory<String, CartEmptyRequest> cartEmptyProducerFactory() {
        // 값은 Avro 단일 객체 인코딩(docs/38 §1.16). 인스턴스로 넘기므로 맵의 VALUE_SERIALIZER 는 무시된다.
        return new DefaultKafkaProducerFactory<>(cartEmptyConfigurations(),
                new StringSerializer(), new AvroDtoSerializer<>(new CartEmptyRequestAvroMapping()));

    }

    @Bean
    public KafkaTemplate<String, CartEmptyRequest> cartEmptyKafkaTemplate() {
        return new KafkaTemplate<>(cartEmptyProducerFactory());
    }

     @Bean
    public Map<String, Object> tokenUpdateConfigurations() {
        return ImmutableMap.<String, Object>builder()
                .put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServerUrl)
                .put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class)
                .put(ProducerConfig.PARTITIONER_CLASS_CONFIG, AggregateKeyPartitioner.class)
                .put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class)                
                // 트랜잭션 없음, 멱등만 — 아웃박스 릴레이(토큰) — 위와 같다(docs/38 §1.17)
                .put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
                .put(ProducerConfig.ACKS_CONFIG, "all")
                .putAll(securityProps())
                .build();

    }

    @Bean
    public ProducerFactory<String, String> tokenUpdateProducerFactory() {
        return new DefaultKafkaProducerFactory<>(tokenUpdateConfigurations());

    }

    @Bean
    public KafkaTemplate<String, String> tokenUpdateKafkaTemplate() {
        return new KafkaTemplate<>(tokenUpdateProducerFactory());
    }

    /**
     * 구매 확정 이벤트({@code order-completed}, docs/25 §12) — JSON 문자열.
     *
     * <p>{@code delivery-status} 와 같은 모양이다: DTO 없이 문자열로 보내고 notification-service
     * 가 필드 이름으로 읽는다. 토큰 릴레이와 설정이 같지만 빈을 따로 두는 이유는, 이 토픽의
     * 발행자가 "토큰" 이름의 템플릿을 들고 있으면 읽는 사람이 헷갈리기 때문이다.
     */
    @Bean
    public ProducerFactory<String, String> orderCompletedProducerFactory() {
        return new DefaultKafkaProducerFactory<>(tokenUpdateConfigurations());
    }

    @Bean
    public KafkaTemplate<String, String> orderCompletedKafkaTemplate() {
        return new KafkaTemplate<>(orderCompletedProducerFactory());
    }

    /**
     * 환불 이벤트({@code order-refunded}, docs/49 §10) — JSON 문자열. 구매 확정과 같은 모양이다.
     * 트랜잭션 프로듀서가 아니다 — 환불은 HTTP 요청에서 커밋이 끝난 뒤 보낸다.
     */
    @Bean
    public ProducerFactory<String, String> orderRefundedProducerFactory() {
        return new DefaultKafkaProducerFactory<>(tokenUpdateConfigurations());
    }

    @Bean
    public KafkaTemplate<String, String> orderRefundedKafkaTemplate() {
        return new KafkaTemplate<>(orderRefundedProducerFactory());
    }

}
