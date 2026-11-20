package com.project.coupon_service.config;

import com.project.common.kafka.avro.CouponRollbackRequestAvroMapping;
import com.project.common.kafka.avro.UserCreatedRequestAvroMapping;
import com.google.common.collect.ImmutableMap;
import com.project.common.message.dto.request.CouponRollbackRequest;
import com.project.common.message.dto.request.UserCreatedRequest;
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
     *
     * <p>[JA] Kafka セキュリティプロパティ(SSL/SASL)は {@link KafkaSecuritySettings} 一か所で作る。以前はクラスごとに同じ六行を複製し、値が空でもそのまま入れていたため、SSL を有効にした瞬間に空パスで落ちる組み合わせだった。
     */
    @Autowired
    private Environment environment;

    private Map<String, Object> securityProps() {
        return KafkaSecuritySettings.from(environment);
    }

    // 재시도+DLT 격리 (KafkaDltConfig, docs/11 §3) — poison message 파티션 블로킹 방지
    // [JA] リトライ+DLT 隔離 (KafkaDltConfig, docs/11 §3) — poison message によるパーティションのブロッキング防止
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.kafka.listener.DefaultErrorHandler kafkaErrorHandler;
    @Value("${kafka.url}")
    private String kafkaServerUrl;

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, UserCreatedRequest> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, UserCreatedRequest> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(userCreatedConsumerFactory());
        factory.getContainerProperties().setConsumerRebalanceListener(new LoggingRebalanceListener());
        factory.setBatchListener(false);
        // ⚠️ 이 팩토리에만 에러 핸들러가 빠져 있었다. 이름이 kafkaListenerContainerFactory 라
        //    **containerFactory 를 지정하지 않은 리스너의 기본값**인데, UserCreatedConsumer 가
        //    정확히 그렇다. 그래서 user-created.DLT 는 만들어져 있어도 쓰이지 않았고,
        //    poison message 하나가 그 파티션을 무한 재시도로 막는다 — DLQ 를 넣은 이유였던
        //    바로 그 장애다(docs/03 F-2). 같은 파일의 다른 두 팩토리는 이미 붙이고 있었다.
        // [JA] ⚠️ このファクトリーだけエラーハンドラーが抜けていた。名前が kafkaListenerContainerFactory なので
        //      **containerFactory を指定しないリスナーのデフォルト**であり、UserCreatedConsumer がまさにそれだ。
        //      そのため user-created.DLT は作られていても使われず、poison message 一つがそのパーティションを無限リトライで塞ぐ —
        //      DLQ を入れた理由そのものの障害だ(docs/03 F-2)。同じファイルの他の二つのファクトリーは既に付けていた。
        factory.setCommonErrorHandler(kafkaErrorHandler);
        // 이 팩토리만 레코드 단위라 스프링의 컨슈머 추적이 그대로 걸린다 — 켜기만 하면 된다. 빠져 있어서
        // 가입 쿠폰 발급이 가입 요청의 추적에서 떨어져 보였다(docs/51 §4h).
        // [JA] このファクトリーだけレコード単位なので Spring のコンシューマートレースがそのまま掛かる — 有効にするだけでよい。
        //      抜けていたため、登録クーポンの発行が登録リクエストのトレースから外れて見えた(docs/51 §4h)。
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }

    @Bean
    public ConsumerFactory<String, UserCreatedRequest> userCreatedConsumerFactory() {
        // 헤더(__TypeId__)가 있으면 쓰고, 없으면 UserCreatedRequest 로 읽는다 — CDC 릴레이는 헤더를 못 싣는다(docs/38 §5 A3).
        // [JA] ヘッダー(__TypeId__)があれば使い、なければ UserCreatedRequest として読む — CDC リレーはヘッダーを載せられない(docs/38 §5 A3)。
        JsonDeserializer<UserCreatedRequest> deserializer = new JsonDeserializer<>(UserCreatedRequest.class, true);
        com.project.common.kafka.KafkaTrustedPackages.trustMessageDtos(deserializer);  // B-115: "*" 금지 — 메시지 DTO 패키지만 ／ [JA] B-115: "*" 禁止 — メッセージ DTO パッケージのみ

        Map<String, Object> consumerConfigurations = ImmutableMap.<String, Object>builder()
                .put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServerUrl)
                .put(ConsumerConfig.GROUP_ID_CONFIG, "user-2")
                .put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, RebalanceSettings.assignors(environment))
                .put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)
                .put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, deserializer)
                .put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed")
                // 오프셋이 없으면(그룹이 처음 뜸 · 무활동으로 만료 · 새 Kafka 클러스터) 처음부터 — 🐛 예전 latest 는 그 사이의 메시지를 건너뛰었다.
                // 다시 읽어도 안전하다 — 가입 쿠폰은 같은 이름 · 사용자면 다시 만들지 않는다(CP4). [JA] 最初から — 同じ名前 · 利用者なら作り直さない(docs/89 §2-1)。
                .put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                .put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
                .putAll(securityProps())
                .build();

        return new DefaultKafkaConsumerFactory<>(consumerConfigurations, new StringDeserializer(),
                new org.springframework.kafka.support.serializer.ErrorHandlingDeserializer<>(
                        // Avro 또는 JSON 을 같은 DTO 로 — 롤링 배포·CDC·e2e 의 JSON 도 읽는다(docs/38 §1.16).
                        // [JA] Avro でも JSON でも同じ DTO に — ローリングデプロイ・CDC・e2e の JSON も読む(docs/38 §1.16)。
                        new AvroDtoDeserializer<>(new UserCreatedRequestAvroMapping())));
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, CouponRollbackRequest> couponRollbackContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, CouponRollbackRequest> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(couponRollbackConsumerFactory());
        factory.getContainerProperties().setConsumerRebalanceListener(new LoggingRebalanceListener());
        factory.setBatchListener(true);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }

    @Bean
    public ConsumerFactory<String, CouponRollbackRequest> couponRollbackConsumerFactory() {
        // 헤더(__TypeId__)가 있으면 쓰고, 없으면 CouponRollbackRequest 로 읽는다 — CDC 릴레이는 헤더를 못 싣는다(docs/38 §5 A3).
        // [JA] ヘッダー(__TypeId__)があれば使い、なければ CouponRollbackRequest として読む — CDC リレーはヘッダーを載せられない(docs/38 §5 A3)。
        JsonDeserializer<CouponRollbackRequest> deserializer = new JsonDeserializer<>(CouponRollbackRequest.class, true);
        com.project.common.kafka.KafkaTrustedPackages.trustMessageDtos(deserializer);  // B-115: "*" 금지 — 메시지 DTO 패키지만 ／ [JA] B-115: "*" 禁止 — メッセージ DTO パッケージのみ

        Map<String, Object> consumerConfigurations = ImmutableMap.<String, Object>builder()
                .put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServerUrl)
                .put(ConsumerConfig.GROUP_ID_CONFIG, "coupon")
                .put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, RebalanceSettings.assignors(environment))
                .put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)
                .put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, deserializer)
                .put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed")
                // 오프셋이 없으면(그룹이 처음 뜸 · 무활동으로 만료 · 새 Kafka 클러스터) 처음부터 — 🐛 예전 latest 는 그 사이의 메시지를 건너뛰었다.
                // 다시 읽어도 안전하다 — 쿠폰 보상은 그 주문이 쓴 쿠폰만 되돌린다(used_order_id), 없으면 "되돌릴 것 없음". [JA] 最初から — その注文が使ったクーポンだけ戻す(docs/89 §2-1)。
                .put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                .put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
                .putAll(securityProps())
                .build();

        return new DefaultKafkaConsumerFactory<>(consumerConfigurations, new StringDeserializer(),
                new org.springframework.kafka.support.serializer.ErrorHandlingDeserializer<>(
                        // Avro 또는 JSON 을 같은 DTO 로 — 롤링 배포·CDC·e2e 의 JSON 도 읽는다(docs/38 §1.16).
                        // [JA] Avro でも JSON でも同じ DTO に — ローリングデプロイ・CDC・e2e の JSON も読む(docs/38 §1.16)。
                        new AvroDtoDeserializer<>(new CouponRollbackRequestAvroMapping())));
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, PaymentResponse> paymentResponseContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, PaymentResponse> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(paymentResponseConsumerFactory());
        factory.getContainerProperties().setConsumerRebalanceListener(new LoggingRebalanceListener());
        factory.setBatchListener(true);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }

    @Bean
    public ConsumerFactory<String, PaymentResponse> paymentResponseConsumerFactory() {
        // 헤더(__TypeId__)가 있으면 쓰고, 없으면 PaymentResponse 로 읽는다 — CDC 릴레이는 헤더를 못 싣는다(docs/38 §5 A3).
        // [JA] ヘッダー(__TypeId__)があれば使い、なければ PaymentResponse として読む — CDC リレーはヘッダーを載せられない(docs/38 §5 A3)。
        JsonDeserializer<PaymentResponse> deserializer = new JsonDeserializer<>(PaymentResponse.class, true);
        com.project.common.kafka.KafkaTrustedPackages.trustMessageDtos(deserializer);  // B-115: "*" 금지 — 메시지 DTO 패키지만 ／ [JA] B-115: "*" 禁止 — メッセージ DTO パッケージのみ

        Map<String, Object> consumerConfigurations = ImmutableMap.<String, Object>builder()
                .put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServerUrl)
                .put(ConsumerConfig.GROUP_ID_CONFIG, "coupon-used")
                .put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, RebalanceSettings.assignors(environment))
                .put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)
                .put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, deserializer)
                .put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed")
                // 오프셋이 없으면(그룹이 처음 뜸 · 무활동으로 만료 · 새 Kafka 클러스터) 처음부터 — 🐛 예전 latest 는 그 사이의 메시지를 건너뛰었다.
                // 다시 읽어도 안전하다 — 결제 응답의 쿠폰 사용 처리는 eventId 처리 원장이 거른다. [JA] 最初から — 処理台帳が重複を除く(docs/89 §2-1)。
                .put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                .put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
                .putAll(securityProps())
                .build();

        return new DefaultKafkaConsumerFactory<>(consumerConfigurations, new StringDeserializer(),
                new org.springframework.kafka.support.serializer.ErrorHandlingDeserializer<>(
                        // Avro 또는 JSON 을 같은 DTO 로 — 롤링 배포·CDC·e2e 의 JSON 도 읽는다(docs/38 §1.16).
                        // [JA] Avro でも JSON でも同じ DTO に — ローリングデプロイ・CDC・e2e の JSON も読む(docs/38 §1.16)。
                        new AvroDtoDeserializer<>(new PaymentResponseAvroMapping())));
    }
}
