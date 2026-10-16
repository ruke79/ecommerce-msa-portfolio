package com.project.delivery_service.config;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import com.project.common.config.KafkaSecuritySettings;
import com.project.common.kafka.LoggingRebalanceListener;
import com.project.common.kafka.RebalanceSettings;
import com.project.common.kafka.avro.AvroJsonTextDeserializer;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;

/**
 * 배송 컨슈머 설정 — 결제 완료(payment)를 듣는다(PaymentCompletedListener).
 *
 * <p><b>왜 String 으로 받나.</b> 결제 도메인의 DTO 에 결합하지 않기 위해서다 — 결제 쪽 스키마가 바뀔 때마다 배송 생성이 역직렬화
 * 실패로 멈출 이유가 없다. 원문을 받아 필요한 필드(주문 id · 상태)만 꺼내 읽는다.
 *
 * <p>SSL 설정은 다른 서비스와 같은 형태다. 스토어 <b>형식</b>까지 주입받는 것이 중요하다 —
 * {@code .p12} 를 {@code "JKS"} 로 박아 두면 mTLS 를 켜는 순간 전 서비스가 기동 불가가 된다(B-29).
 */
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

    /** poison message 가 파티션을 막지 않도록 재시도 후 DLT 로 보낸다(docs/11 §3). */
    @Autowired
    private DefaultErrorHandler kafkaErrorHandler;

    @Value("${kafka.url}")
    private String kafkaServerUrl;


    @Bean
    public ConsumerFactory<String, String> deliveryConsumerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServerUrl);
        // 그룹을 서비스 단위로 하나만 둔다. 레플리카가 늘어도 한 메시지는 한 파드만 받는다 —
        // 배송은 파드마다 만들면 안 되므로 이게 맞다(채팅 브로드캐스트와 반대다, docs/21 §2).
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "delivery");
        config.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, RebalanceSettings.assignors(environment));
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        // 오프셋이 없을 때(그룹이 처음 뜨거나 7일 무활동으로 만료) 처음부터 읽는다. 🐛 예전: latest — 그 사이의 결제 완료가 전부
        // 사라져 결제됐는데 배송이 없는 주문이 남았다(아무도 모른다). 다시 읽어도 안전하다 — order_id UNIQUE + 선조회로 멱등이고,
        // 그 사이 취소 · 환불된 주문엔 만들지 않는다(PaymentCompletedListener). 그룹이 이미 있는 운영에서는 저장된 오프셋이 우선이라
        // 배포 즉시 바뀌는 것은 없다(DeliveryKafkaOffsetResetTest).
        // [JA] オフセットがないときは最初から読む。以前は latest で、その間の決済完了が消え配送のない注文が残った。再読込は冪等で安全。
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.putAll(securityProps());
        // order·payment 토픽은 이제 Avro 다. 이 소비자는 JSON 문자열을 읽으므로 Avro 면 JSON 으로 바꿔 준다(docs/38 §1.16).
        return new DefaultKafkaConsumerFactory<>(config, new StringDeserializer(),
                new org.springframework.kafka.support.serializer.ErrorHandlingDeserializer<>(new AvroJsonTextDeserializer()));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> deliveryListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(deliveryConsumerFactory());
        factory.getContainerProperties().setConsumerRebalanceListener(new LoggingRebalanceListener());
        factory.setBatchListener(true);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }
}
