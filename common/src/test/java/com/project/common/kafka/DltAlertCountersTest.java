package com.project.common.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListenerContainer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * DLT 계수기는 기동이 끝날 때 리스너 토픽마다 0 으로 있다 — 첫 격리부터 경보 KafkaDeadLetterPublished 의 increase() 가 본다(B-101).
 * [JA] DLT カウンターは起動完了時にリスナーのトピックごとに 0 で存在する — 初回の隔離から increase() が見る。
 */
@DisplayName("common — DLT 계수기를 리스너 토픽마다 0 으로")
class DltAlertCountersTest {

    @Test
    @DisplayName("🔴 기동이 끝나면 리스너 토픽마다 kafka.dlt.records{topic} 가 0 으로 있다 — 토픽 패턴 컨테이너는 건너뛴다")
    void zeroCounterPerListenerTopic() {
        MessageListenerContainer named = mock(MessageListenerContainer.class);
        when(named.getContainerProperties()).thenReturn(new ContainerProperties("order", "payment"));
        MessageListenerContainer byPattern = mock(MessageListenerContainer.class);
        when(byPattern.getContainerProperties()).thenReturn(new ContainerProperties(Pattern.compile("audit-.*")));
        KafkaListenerEndpointRegistry listeners = mock(KafkaListenerEndpointRegistry.class);
        when(listeners.getAllListenerContainers()).thenReturn(List.of(named, byPattern));
        MeterRegistry meters = new SimpleMeterRegistry();

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DltAlertCountersAutoConfiguration.class))
                .withBean(KafkaListenerEndpointRegistry.class, () -> listeners)
                .withBean(MeterRegistry.class, () -> meters)
                .run(ctx -> ctx.getBean(DltAlertCountersAutoConfiguration.DltAlertCounters.class).register());

        for (String topic : List.of("order", "payment")) {
            Counter c = meters.find(DltRetryPolicy.DLT_METRIC).tag("topic", topic).counter();
            assertThat(c).as("토픽 %s 의 DLT 계수기", topic).isNotNull();
            assertThat(c.count()).isZero();
        }
        assertThat(meters.find(DltRetryPolicy.DLT_METRIC).counters()).hasSize(2);
    }

    @Test
    @DisplayName("자동 구성 목록에 있다 — 서비스마다 따로 등록하지 않아도 켜진다")
    void listedAsAutoConfiguration() throws Exception {
        String imports = new ClassPathResource("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(imports.lines()).contains(DltAlertCountersAutoConfiguration.class.getName());
    }
}
