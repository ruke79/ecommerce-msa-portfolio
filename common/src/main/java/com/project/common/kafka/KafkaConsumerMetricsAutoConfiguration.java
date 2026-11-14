package com.project.common.kafka;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.AbstractKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.MicrometerConsumerListener;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 모든 Kafka 컨슈머의 클라이언트 지표를 Micrometer 로 — 컨슈머 지연(lag)을 재려고(docs/91 §3, docs/89 단계 0).
 *
 * <p>🐛 <b>예전에는 컨슈머 지연을 잴 지표가 하나도 없었다.</b> Spring Boot 는 자기 자동 설정의 컨슈머 팩토리에만
 * {@link MicrometerConsumerListener} 를 단다. 이 저장소의 서비스는 모두 팩토리를 직접 만든다({@code KafkaConsumerConfig} ·
 * {@code KafkaDltConfig} — 아홉 서비스) — 그래서 {@code kafka_consumer_fetch_manager_records_lag_max} 가 어디에도 없었고,
 * 경보 규칙 머리말도 "컨슈머 랙은 로드맵" 이었다. 부하 측정(ops/53 ceiling)이 "어디서 먼저 막히나" 를 볼 때 Kafka 지연이 빠졌다.
 *
 * <p>팩토리 빈이면 그것에, 리스너 컨테이너 팩토리 빈이면 그 안의 컨슈머 팩토리에(빈이 아닌 {@code new DefaultKafkaConsumerFactory}
 * 를 넘긴 곳 — 대부분이 그렇다) 단다. 이미 달려 있으면 더하지 않는다. 지표는 컨슈머가 만들어질 때(컨테이너 시작) 묶인다.
 * 끄려면 {@code ecommerce.kafka.consumer-metrics.enabled=false}.
 *
 * <p>[JA] すべての Kafka コンシューマーのクライアント指標を Micrometer へ — コンシューマー遅延(lag)を測るため。以前は Boot が自分の
 *      ファクトリーにだけリスナーを付け、このリポジトリはファクトリーを自前で作るので lag の指標がどこにもなかった。ファクトリーの
 *      Bean か、リスナーコンテナファクトリーの中のファクトリーに付ける(付いていれば足さない)。
 */
@AutoConfiguration
@ConditionalOnClass(name = { "org.springframework.kafka.core.MicrometerConsumerListener",
        "io.micrometer.core.instrument.MeterRegistry" })
@ConditionalOnProperty(name = "ecommerce.kafka.consumer-metrics.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaConsumerMetricsAutoConfiguration {

    /** static — BeanPostProcessor 는 다른 빈보다 먼저 만들어진다. 레지스트리는 쓸 때 꺼낸다. */
    @Bean
    static BeanPostProcessor kafkaConsumerMetricsPostProcessor(ObjectProvider<MeterRegistry> meters) {
        return new ConsumerMetricsPostProcessor(meters);
    }

    static final class ConsumerMetricsPostProcessor implements BeanPostProcessor {
        private final ObjectProvider<MeterRegistry> meters;

        ConsumerMetricsPostProcessor(ObjectProvider<MeterRegistry> meters) {
            this.meters = meters;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (bean instanceof ConsumerFactory<?, ?> factory) {
                attach(factory);
            } else if (bean instanceof AbstractKafkaListenerContainerFactory<?, ?, ?> containers
                    && containers.getConsumerFactory() != null) {
                attach(containers.getConsumerFactory());
            }
            return bean;
        }

        @SuppressWarnings({ "unchecked", "rawtypes" })
        private void attach(ConsumerFactory<?, ?> factory) {
            boolean already = factory.getListeners().stream().anyMatch(MicrometerConsumerListener.class::isInstance);
            if (already) {
                return;
            }
            MeterRegistry registry = meters.getIfAvailable();
            if (registry == null) {
                return;
            }
            ((ConsumerFactory) factory).addListener(new MicrometerConsumerListener<>(registry));
        }
    }
}
