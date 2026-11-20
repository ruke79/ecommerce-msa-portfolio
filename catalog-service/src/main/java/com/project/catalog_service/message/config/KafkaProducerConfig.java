package com.project.catalog_service.message.config;

import java.util.Map;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import com.google.common.collect.ImmutableMap;
import com.project.common.config.KafkaSecuritySettings;
import com.project.common.kafka.AggregateKeyPartitioner;

/**
 * 이 서비스가 <b>발행</b>하는 이벤트용 프로듀서.
 *
 * <p>지금까지 catalog-service 에는 프로듀서 설정이 없었다 — {@link KafkaDltConfig} 의
 * DLT 전용 템플릿만 있었고, 그건 컨슈머가 실패한 레코드를 격리할 때만 쓴다. 공지 게시를
 * 알리려면 업무 이벤트를 내보내는 경로가 하나 필요하다.
 *
 * <p><b>값이 왜 String 인가.</b> 알림 서비스는 도메인 DTO 를 의존하지 않고 JSON 을
 * {@code JsonNode} 로 읽는다(NotificationEventListener 주석) — 알림 하나 때문에 모든
 * 도메인의 스키마 변경에 끌려다니지 않기 위해서다. 그래서 여기서도 문자열 JSON 으로
 * 보낸다. Avro 로 보내려면 공용 스키마와 매핑을 새로 만들어야 하는데, 공지 알림 하나에
 * 그만한 결합을 만들 이유가 없다.
 *
 * <p><b>멱등 프로듀서.</b> 재시도로 같은 레코드가 두 번 쓰이는 것을 브로커가 막는다.
 * 그래도 소비 쪽 중복은 남으므로(리밸런스 등) 이벤트에 안정적인 키를 실어 보낸다 —
 * 최종 방어선은 {@code broadcast_notification.source_event_id} 의 UNIQUE 다.
 *
 * <p>[JA] このサービスが発行するイベント用のプロデューサー。
 * これまで catalog-service にはプロデューサー設定がなかった — KafkaDltConfig の DLT 専用テンプレートだけがあり、それはコンシューマが失敗レコードを
 * 隔離するときにしか使わない。お知らせの掲載を知らせるには、業務イベントを送り出す経路が一つ必要だ。
 * 値がなぜ String なのか。通知サービスはドメイン DTO に依存せず JSON を JsonNode として読む(NotificationEventListener のコメント) —
 * 通知一つのために全ドメインのスキーマ変更に引きずられないためだ。だからここでも文字列 JSON で送る。Avro で送るには共通スキーマとマッピングを
 * 新たに作る必要があるが、お知らせ通知一つのためにそれだけの結合を作る理由はない。
 * 冪等プロデューサー。リトライで同じレコードが二度書かれるのをブローカーが防ぐ。それでも消費側の重複は残るので(リバランスなど)、
 * イベントに安定したキーを載せて送る — 最終防衛線は broadcast_notification.source_event_id の UNIQUE である。
 */
@Configuration
public class KafkaProducerConfig {

    /** SSL/SASL 프로퍼티는 {@link KafkaSecuritySettings} 한 곳에서 만든다. */
    // [JA] SSL/SASL のプロパティは KafkaSecuritySettings 一か所で作る。
    @Autowired
    private Environment environment;

    @Value("${kafka.url}")
    private String kafkaServerUrl;

    @Bean
    public ProducerFactory<String, String> contentEventProducerFactory() {
        Map<String, Object> props = ImmutableMap.<String, Object>builder()
                .put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServerUrl)
                .put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class)
                .put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class)
                // 같은 키는 같은 파티션으로 — 한 공지의 게시/수정 순서가 뒤집히지 않는다.
                // [JA] 同じキーは同じパーティションへ — 一つのお知らせの掲載/修正の順序が逆転しない。
                .put(ProducerConfig.PARTITIONER_CLASS_CONFIG, AggregateKeyPartitioner.class)
                .put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
                .put(ProducerConfig.ACKS_CONFIG, "all")
                .putAll(KafkaSecuritySettings.from(environment))
                .build();

        return new DefaultKafkaProducerFactory<>(props);
    }

    /**
     * 이름을 {@code contentEventKafkaTemplate} 로 둔 이유: {@link KafkaDltConfig} 가 이미
     * {@code dltJsonKafkaTemplate}·{@code dltBytesKafkaTemplate} 을 등록한다. 타입이 달라
     * 충돌하진 않지만, 주입받는 쪽이 어느 것을 쓰는지 이름으로 드러나야 한다.
     *
     * <p>[JA] 名前を contentEventKafkaTemplate にした理由: KafkaDltConfig がすでに dltJsonKafkaTemplate・dltBytesKafkaTemplate を登録している。
     * 型が違うので衝突はしないが、注入される側がどれを使うのかが名前で分かるべきだ。
     */
    @Bean
    public KafkaTemplate<String, String> contentEventKafkaTemplate() {
        return new KafkaTemplate<>(contentEventProducerFactory());
    }
}
