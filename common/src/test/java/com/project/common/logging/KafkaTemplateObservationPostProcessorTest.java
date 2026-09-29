package com.project.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 보내는 쪽 추적 (docs/51 §4d).
 *
 * <p>컨슈머 쪽 추적만 켜져 있어, 메시지에 추적 헤더가 실리지 않았고 받는 쪽이 새 추적을
 * 시작했다. Zipkin 에서 "장바구니 비우기"·"배송 상태"·"구매 확정 알림" 이 원인이 된 요청과
 * 따로 떨어져 보였다. 공통 자동 구성이 모든 {@link KafkaTemplate} 빈에 켠다.
 */
@DisplayName("KafkaTemplate 프로듀서 추적")
class KafkaTemplateObservationPostProcessorTest {

    private static boolean observationEnabled(KafkaTemplate<?, ?> template) {
        return (boolean) ReflectionTestUtils.getField(template, "observationEnabled");
    }

    private static AnnotationConfigApplicationContext context(Map<String, Object> properties) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
        context.register(CommonLoggingAutoConfiguration.class);
        // 브로커에 붙지 않는다 — 템플릿은 첫 발송 때 연결한다.
        context.registerBean("sampleTemplate", KafkaTemplate.class,
                () -> new KafkaTemplate<>(new DefaultKafkaProducerFactory<String, String>(Map.of())));
        context.refresh();
        return context;
    }

    @Test
    @DisplayName("🔴 공통 자동 구성이 붙은 서비스의 KafkaTemplate 빈은 추적이 켜진다")
    void enablesObservationOnEveryTemplateBean() {
        try (AnnotationConfigApplicationContext context = context(Map.of())) {
            assertThat(observationEnabled(context.getBean("sampleTemplate", KafkaTemplate.class))).isTrue();
        }
    }

    @Test
    @DisplayName("app.tracing.kafka-producer=false 면 손대지 않는다")
    void canBeTurnedOff() {
        try (AnnotationConfigApplicationContext context = context(Map.of("app.tracing.kafka-producer", "false"))) {
            assertThat(observationEnabled(context.getBean("sampleTemplate", KafkaTemplate.class))).isFalse();
        }
    }

    @Test
    @DisplayName("템플릿이 아닌 빈은 그대로 돌려준다")
    void leavesOtherBeansAlone() {
        Object other = new Object();
        assertThat(new KafkaTemplateObservationPostProcessor().postProcessBeforeInitialization(other, "x"))
                .isSameAs(other);
    }
}
