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
 *
 * <p>[JA] 配送コンシューマー設定 — 決済完了(payment)を購読する(PaymentCompletedListener)。
 * <p><b>なぜ String で受け取るのか。</b>決済ドメインの DTO に結合しないためだ — 決済側のスキーマが変わるたびに配送作成がデシリアライズ失敗で止まる理由はない。原文を受け取り必要なフィールド(注文 id · 状態)だけを取り出して読む。
 * <p>SSL 設定は他のサービスと同じ形だ。ストアの<b>形式</b>まで注入されることが重要だ — {@code .p12} を {@code "JKS"} と埋め込むと mTLS を有効にした瞬間に全サービスが起動不能になる(B-29)。
 */
@EnableKafka
@Configuration
public class KafkaConsumerConfig {

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

    /** poison message 가 파티션을 막지 않도록 재시도 후 DLT 로 보낸다(docs/11 §3). */
    // [JA] poison message がパーティションを塞がないようリトライ後 DLT へ送る(docs/11 §3)。
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
        // [JA] グループはサービス単位で一つだけ置く。レプリカが増えても一つのメッセージは一つのポッドだけが受け取る —
        //      配送はポッドごとに作ってはならないのでこれが正しい(チャットのブロードキャストと逆だ, docs/21 §2)。
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "delivery");
        config.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, RebalanceSettings.assignors(environment));
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        // 오프셋이 없을 때(그룹이 처음 뜨거나 7일 무활동으로 만료) 처음부터 읽는다. 🐛 예전: latest — 그 사이의 결제 완료가 전부
        // 사라져 결제됐는데 배송이 없는 주문이 남았다(아무도 모른다). 다시 읽어도 안전하다 — order_id UNIQUE + 선조회로 멱등이고,
        // 그 사이 취소 · 환불된 주문엔 만들지 않는다(PaymentCompletedListener). 그룹이 이미 있는 운영에서는 저장된 오프셋이 우선이라
        // 배포 즉시 바뀌는 것은 없다(DeliveryKafkaOffsetResetTest).
        // [JA] オフセットがないとき(グループが初めて起動する、または 7 日の無活動で失効)は最初から読む。🐛 以前: latest — その間の決済完了がすべて
        //      消え、決済されたのに配送のない注文が残った(誰も気づかない)。読み直しても安全 — order_id UNIQUE + 事前照会で冪等で、
        //      その間に取消 · 返金された注文には作らない(PaymentCompletedListener)。グループが既にある本番では保存済みのオフセットが優先なので
        //      デプロイ直後に変わるものはない(DeliveryKafkaOffsetResetTest)。
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.putAll(securityProps());
        // order·payment 토픽은 이제 Avro 다. 이 소비자는 JSON 문자열을 읽으므로 Avro 면 JSON 으로 바꿔 준다(docs/38 §1.16).
        // [JA] order・payment トピックは今は Avro だ。このコンシューマーは JSON 文字列を読むので、Avro なら JSON に変換する(docs/38 §1.16)。
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
