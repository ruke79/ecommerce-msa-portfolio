package com.project.cart_service.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.kafka.clients.producer.ProducerConfig;
import com.project.common.kafka.AggregateKeyPartitioner;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import com.project.common.config.KafkaSecuritySettings;
import org.springframework.core.convert.ConversionException;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.messaging.converter.MessageConversionException;

import com.google.common.collect.ImmutableMap;

/**
 * Kafka 컨슈머 공통 에러 처리(DLQ) 설정 — docs/03 R-2, docs/09 참고.
 *
 * 기존에는 에러 핸들러가 전혀 없어 poison message 1건이 파티션 전체를 무한 재시도로
 * 블로킹했다(docs/03 F-2). 이 설정은:
 *  1) 처리 실패 레코드를 지수 백오프로 3회 재시도 후 <topic>.DLT 로 격리하고,
 *  2) 역직렬화 실패(복구 불가)는 재시도 없이 즉시 DLT 로 보낸다.
 *
 * 리스너는 BatchListenerFailedException(index)을 던져 "배치의 몇 번째 레코드가
 * 실패했는지"를 알려야, 이미 처리된 앞 레코드들의 오프셋은 커밋되고 실패 레코드만
 * 격리된 뒤 나머지가 재전달된다 (각 서비스 Kafka 리스너 참조).
 *
 * <p>[JA] Kafka コンシューマ共通のエラー処理(DLQ)設定 — docs/03 R-2, docs/09 参照。
 *      以前はエラーハンドラがまったくなく、poison message 1 件がパーティション全体を無限リトライで
 *      ブロックしていた(docs/03 F-2)。この設定は:
 *       1) 処理に失敗したレコードを指数バックオフで 3 回リトライした後 <topic>.DLT に隔離し、
 *       2) デシリアライズ失敗(回復不能)はリトライなしで即座に DLT に送る。
 *      リスナーは BatchListenerFailedException(index) を投げて「バッチの何番目のレコードが
 *      失敗したか」を知らせる必要がある。そうすれば処理済みの前のレコードのオフセットはコミットされ、失敗レコードだけ
 *      隔離された後に残りが再配信される(各サービスの Kafka リスナー参照)。
 */
@Configuration
public class KafkaDltConfig {

    /**
     * Kafka 보안 프로퍼티(SSL/SASL)는 {@link KafkaSecuritySettings} 한 곳에서 만든다.
     * 예전에는 이 클래스마다 같은 여섯 줄을 복제했고, 값이 비어 있어도 그대로 넣어
     * SSL 을 켜는 순간 빈 경로로 죽는 조합이었다.
     *
     * <p>[JA] Kafka のセキュリティプロパティ(SSL/SASL)は {@link KafkaSecuritySettings} 1 か所で作る。
     *      以前はこのクラスごとに同じ 6 行を複製し、値が空でもそのまま入れていたため
     *      SSL を有効にした瞬間に空のパスで死ぬ組み合わせだった。
     */
    @Autowired
    private Environment environment;

    private Map<String, Object> securityProps() {
        return KafkaSecuritySettings.from(environment);
    }

    @Value("${kafka.url}")
    private String kafkaServerUrl;

    private Map<String, Object> dltProducerProps(Class<?> valueSerializer) {
        return ImmutableMap.<String, Object>builder()
                .put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServerUrl)
                .put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class)
                .put(ProducerConfig.PARTITIONER_CLASS_CONFIG, AggregateKeyPartitioner.class)
                .put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, valueSerializer)
                .putAll(securityProps())
                .build();
    }

    /** 정상 역직렬화된 페이로드를 DLT 로 보낼 때 사용(JSON 재직렬화). */
    // [JA] 正常にデシリアライズされたペイロードを DLT に送るときに使う(JSON 再シリアライズ)。
    @Bean
    public KafkaTemplate<String, Object> dltJsonKafkaTemplate() {
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(dltProducerProps(JsonSerializer.class)));
    }

    /** 역직렬화 실패 레코드의 원본 바이트를 DLT 로 보낼 때 사용. */
    // [JA] デシリアライズに失敗したレコードの元バイトを DLT に送るときに使う。
    @Bean
    public KafkaTemplate<String, byte[]> dltBytesKafkaTemplate() {
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(dltProducerProps(ByteArraySerializer.class)));
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(
            KafkaTemplate<String, Object> dltJsonKafkaTemplate,
            KafkaTemplate<String, byte[]> dltBytesKafkaTemplate) {

        // 값 타입에 따라 템플릿 선택: 역직렬화 실패면 byte[](원본 보존), 그 외엔 JSON.
        // [JA] 値の型によってテンプレートを選ぶ: デシリアライズ失敗なら byte[](原本保存)、それ以外は JSON。
        Map<Class<?>, KafkaOperations<?, ?>> templates = new LinkedHashMap<>();
        templates.put(byte[].class, dltBytesKafkaTemplate);
        templates.put(Object.class, dltJsonKafkaTemplate);

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(templates,
                // 파티션 -1: DLT 의 파티션 수가 원본 토픽과 달라도 안전하게 브로커가 선택
                // [JA] パーティション -1: DLT のパーティション数が元トピックと違っても安全にブローカーが選ぶ
                (record, ex) -> new TopicPartition(record.topic() + ".DLT", -1));

        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(1_000L);
        backOff.setMultiplier(2.0);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        // 재시도해도 절대 성공할 수 없는 예외는 즉시 DLT 로 (poison 즉시 격리)
        // [JA] リトライしても絶対に成功しえない例外は即座に DLT へ(poison の即時隔離)
        handler.addNotRetryableExceptions(
                DeserializationException.class,
                MessageConversionException.class,
                ConversionException.class,
                ClassCastException.class);
        return handler;
    }
}
