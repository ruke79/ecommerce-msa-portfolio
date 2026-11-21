package com.project.delivery_service.config;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.producer.ProducerConfig;
import com.project.common.kafka.AggregateKeyPartitioner;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import com.project.common.config.KafkaSecuritySettings;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * 배송 상태 이벤트 발행용 프로듀서.
 *
 * <p>스토어 형식을 하드코딩하지 않는다 — {@code .p12} 를 {@code "JKS"} 로 박아 두면
 * mTLS 를 켜는 순간 기동 불가가 된다(B-29).
 *
 * <p>[JA] 配送状態イベント発行用のプロデューサー。
 * <p>ストア形式をハードコードしない — {@code .p12} を {@code "JKS"} と埋め込むと mTLS を有効にした瞬間に起動不能になる(B-29)。
 */
@Configuration
public class KafkaProducerConfig {

    /**
     * Kafka 보안 프로퍼티(SSL/SASL)는 {@link KafkaSecuritySettings} 한 곳에서 만든다.
     * 예전에는 이 클래스마다 같은 여섯 줄을 복제했고, 값이 비어 있어도 그대로 넣어
     * SSL 을 켜는 순간 빈 경로로 죽는 조합이었다.
     *
     * <p>[JA] Kafka セキュリティプロパティ(SSL/SASL)は {@link KafkaSecuritySettings} 一か所で作る。以前はクラスごとに同じ六行を複製し、値が空でもそのまま入れていたため、SSL を有効にした瞬間に空パスで落ちる組み合わせだった。
     */
    @Autowired
    private Environment environment;

    private Map<String, Object> securityProps() {
        return KafkaSecuritySettings.from(environment);
    }

    @Value("${kafka.url}")
    private String kafkaServerUrl;


    @Bean
    public ProducerFactory<String, String> deliveryProducerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServerUrl);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.PARTITIONER_CLASS_CONFIG, AggregateKeyPartitioner.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        // 브로커가 없을 때 send() 가 메타데이터를 기다리는 상한 — 기본 60초면 아웃박스 릴레이가 그동안 DB 트랜잭션(advisory 잠금)을
        // 쥔다. 5초 · 응답 상한 5초 · 전달 상한 10초(pay · user 와 같은 값). 못 보낸 행은 STARTED 로 남아 다음 주기에 간다.
        // [JA] ブローカーがないとき send() がメタデータを待つ上限 — 既定 60 秒だとリレーがその間 DB トランザクションを握る。
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10_000);
        config.putAll(securityProps());
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> deliveryProducerFactory) {
        return new KafkaTemplate<>(deliveryProducerFactory);
    }
}
