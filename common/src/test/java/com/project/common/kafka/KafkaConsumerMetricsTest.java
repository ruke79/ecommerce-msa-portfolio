package com.project.common.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.MicrometerConsumerListener;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 컨슈머 지연을 재는 지표가 서비스가 직접 만든 팩토리에도 붙는다(docs/91 §3).
 * [JA] コンシューマー遅延の指標がサービス自前のファクトリーにも付く。
 */
@DisplayName("common — Kafka 컨슈머 지표(지연)를 모든 컨슈머 팩토리에")
class KafkaConsumerMetricsTest {

    private static Map<String, Object> props() {
        // 연결하지 않는다 — KafkaConsumer 는 만들 때 브로커에 붙지 않는다.
        return Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1",
                ConsumerConfig.GROUP_ID_CONFIG, "metrics-test",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    }

    private static long micrometerListeners(DefaultKafkaConsumerFactory<?, ?> f) {
        return f.getListeners().stream().filter(MicrometerConsumerListener.class::isInstance).count();
    }

    @Test
    @DisplayName("🔴 빈이 아닌 팩토리(리스너 컨테이너 팩토리 안)에도 붙고, 컨슈머를 만들면 kafka.consumer 지표가 레지스트리에 생긴다")
    void attachesToFactoryInsideContainerFactory() {
        MeterRegistry meters = new SimpleMeterRegistry();
        DefaultKafkaConsumerFactory<String, String> inner = new DefaultKafkaConsumerFactory<>(props());

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KafkaConsumerMetricsAutoConfiguration.class))
                .withBean(MeterRegistry.class, () -> meters)
                .withBean("kafkaListenerContainerFactory", ConcurrentKafkaListenerContainerFactory.class, () -> {
                    ConcurrentKafkaListenerContainerFactory<String, String> f = new ConcurrentKafkaListenerContainerFactory<>();
                    f.setConsumerFactory(inner);
                    return f;
                })
                .run(ctx -> assertThat(ctx).hasNotFailed());

        assertThat(micrometerListeners(inner)).isEqualTo(1);
        try (Consumer<String, String> consumer = inner.createConsumer()) {
            assertThat(meters.getMeters()).as("컨슈머의 클라이언트 지표")
                    .anyMatch(m -> m.getId().getName().startsWith("kafka.consumer"));
        }
    }

    @Test
    @DisplayName("🔴 팩토리 빈에도 붙고, 같은 팩토리가 컨테이너 팩토리에 또 있어도 · 이미 달려 있어도 하나만")
    void attachesOnceToFactoryBean() {
        MeterRegistry meters = new SimpleMeterRegistry();
        DefaultKafkaConsumerFactory<String, String> shared = new DefaultKafkaConsumerFactory<>(props());
        DefaultKafkaConsumerFactory<String, String> preset = new DefaultKafkaConsumerFactory<>(props());
        preset.addListener(new MicrometerConsumerListener<>(meters));

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KafkaConsumerMetricsAutoConfiguration.class))
                .withBean(MeterRegistry.class, () -> meters)
                .withBean("sharedConsumerFactory", DefaultKafkaConsumerFactory.class, () -> shared)
                .withBean("presetConsumerFactory", DefaultKafkaConsumerFactory.class, () -> preset)
                .withBean("containerFactory", ConcurrentKafkaListenerContainerFactory.class, () -> {
                    ConcurrentKafkaListenerContainerFactory<String, String> f = new ConcurrentKafkaListenerContainerFactory<>();
                    f.setConsumerFactory(shared);
                    return f;
                })
                .run(ctx -> assertThat(ctx).hasNotFailed());

        assertThat(micrometerListeners(shared)).isEqualTo(1);
        assertThat(micrometerListeners(preset)).isEqualTo(1);
    }

    @Test
    @DisplayName("끄면(ecommerce.kafka.consumer-metrics.enabled=false) 붙이지 않는다 · 자동 구성 목록에 있다")
    void canBeDisabledAndIsListed() throws Exception {
        DefaultKafkaConsumerFactory<String, String> f = new DefaultKafkaConsumerFactory<>(props());
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KafkaConsumerMetricsAutoConfiguration.class))
                .withPropertyValues("ecommerce.kafka.consumer-metrics.enabled=false")
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean("consumerFactory", DefaultKafkaConsumerFactory.class, () -> f)
                .run(ctx -> assertThat(ctx).hasNotFailed());
        assertThat(micrometerListeners(f)).isZero();

        String imports = new ClassPathResource("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(imports).contains(KafkaConsumerMetricsAutoConfiguration.class.getName());
    }
}
