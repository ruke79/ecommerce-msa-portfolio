package com.project.notification_service.config;

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
 * 알림 컨슈머 설정.
 *
 * <p><b>왜 String 으로 받나.</b> 다른 서비스들은 각자의 DTO 로 역직렬화하지만, 알림 서비스는
 * 주문·결제·채팅 등 <b>여러 도메인</b>의 이벤트를 듣는다. 각 DTO 에 의존하면 그 도메인의 스키마가
 * 바뀔 때마다 알림 서비스가 역직렬화 실패로 죽는다 — 알림은 그런 결합을 가질 이유가 없다.
 * 원문을 받아 필요한 필드만 꺼내 읽는다(NotificationEventListener).
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
    public ConsumerFactory<String, String> notificationConsumerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServerUrl);
        // 기본 그룹. ⚠️ 실제로는 리스너마다 리스너 id 가 곧 group.id 다(idIsGroup) — 리스너마다 따로 그룹이고, 같은
        // 리스너의 레플리카끼리는 한 그룹이라 한 메시지는 한 파드만 받는다(알림은 파드마다 만들면 안 된다, docs/21 §2).
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "notification");
        config.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, RebalanceSettings.assignors(environment));
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        // 오프셋이 없을 때(새 리스너를 처음 배포 · 그룹 오프셋 만료)는 처음부터. 🐛 예전에는 latest 라 그 사이의 메시지 — 가입 확인 ·
        // 비밀번호 재설정(outgoing-mail) · 보안 경고까지 — 가 조용히 사라졌다. 다시 읽어도 각 리스너의 멱등 키(sourceEventId ·
        // eventId)가 중복을 거른다. 지금 이 순간에만 의미 있는 접속 알림(user-presence)만 리스너에서 latest 로 둔다.
        // [JA] オフセットがないときは最初から。以前は latest で、新しいリスナーの初回デプロイやオフセット失効の間のメッセージ(登録確認・
        //      パスワード再設定・セキュリティ警告まで)が黙って消えていた。冪等キーが重複を除く。今だけ意味のある接続通知だけ latest。
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.putAll(securityProps());
        // order·payment 토픽은 이제 Avro 다. 이 소비자는 JSON 문자열을 읽으므로 Avro 면 JSON 으로 바꿔 준다(docs/38 §1.16).
        return new DefaultKafkaConsumerFactory<>(config, new StringDeserializer(),
                new org.springframework.kafka.support.serializer.ErrorHandlingDeserializer<>(new AvroJsonTextDeserializer()));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> notificationListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(notificationConsumerFactory());
        factory.getContainerProperties().setConsumerRebalanceListener(new LoggingRebalanceListener());
        factory.setBatchListener(true);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }
}
