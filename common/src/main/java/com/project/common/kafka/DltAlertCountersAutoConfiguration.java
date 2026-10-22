package com.project.common.kafka;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * DLT 계수기({@value DltRetryPolicy#DLT_METRIC}{topic})를 기동이 끝날 때 리스너 토픽마다 0 으로 만든다(B-101).
 *
 * <p>계수기가 첫 DLT 때 생기면 첫 값이 이미 1 이라 경보 KafkaDeadLetterPublished 의 {@code increase()} 가 그 건을 0 으로 본다 — 파드마다
 * 토픽마다 첫 격리가 조용했다(2026-10-22 실습에서 OrderOutboxRowFailed 로 같은 결함이 드러났다). 리스너 컨테이너는 기동이 끝나야
 * 모두 등록되므로 {@link ApplicationReadyEvent} 에서. 토픽 패턴으로 듣는 컨테이너는 토픽 이름을 미리 모른다 — 건너뛴다.
 *
 * <p>[JA] DLT カウンターを起動完了時にリスナーのトピックごとに 0 で作る。初回の DLT で生まれると increase() が初回を 0 と見て
 *      KafkaDeadLetterPublished が鳴らなかった。
 */
@AutoConfiguration
@ConditionalOnClass(name = { "org.springframework.kafka.config.KafkaListenerEndpointRegistry",
        "io.micrometer.core.instrument.MeterRegistry" })
public class DltAlertCountersAutoConfiguration {

    @Bean
    DltAlertCounters dltAlertCounters(ObjectProvider<KafkaListenerEndpointRegistry> listeners, ObjectProvider<MeterRegistry> meters) {
        return new DltAlertCounters(listeners, meters);
    }

    public static class DltAlertCounters {
        private final ObjectProvider<KafkaListenerEndpointRegistry> listeners;
        private final ObjectProvider<MeterRegistry> meters;

        DltAlertCounters(ObjectProvider<KafkaListenerEndpointRegistry> listeners, ObjectProvider<MeterRegistry> meters) {
            this.listeners = listeners;
            this.meters = meters;
        }

        @EventListener(ApplicationReadyEvent.class)
        public void register() {
            KafkaListenerEndpointRegistry registry = listeners.getIfAvailable();
            MeterRegistry registryOfMeters = meters.getIfAvailable();
            if (registry == null || registryOfMeters == null) {
                return;
            }
            DltRetryPolicy.registerAlertCounters(registryOfMeters, topics(registry));
        }

        static Set<String> topics(KafkaListenerEndpointRegistry registry) {
            Set<String> topics = new TreeSet<>();
            for (MessageListenerContainer container : registry.getAllListenerContainers()) {
                String[] named = container.getContainerProperties().getTopics();
                if (named != null) {
                    Arrays.stream(named).filter(Objects::nonNull).forEach(topics::add);
                }
            }
            return topics;
        }
    }
}
