package com.project.order_service.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.order_service.ports.output.message.publisher.CartEmptyKafkaPublisher;
import com.project.order_service.ports.output.message.publisher.CartRollbackKafkaPublisher;
import com.project.order_service.ports.output.message.publisher.CouponRollbackKafkaPublisher;
import com.project.order_service.ports.output.message.publisher.OrderCompletedKafkaPublisher;
import com.project.order_service.ports.output.message.publisher.OrderRefundedKafkaPublisher;
import com.project.order_service.ports.output.message.publisher.PaymentRequestKafkaPublisher;
import com.project.order_service.ports.output.message.publisher.ProductUpdateKafkaPublisher;
import com.project.order_service.ports.output.message.publisher.TokenRequestKafkaPublisher;

/**
 * 🐛 회귀 대상 — <b>order-service 가 배포에서 기동하지 못했다.</b>
 *
 * <pre>
 *   APPLICATION FAILED TO START
 *   Parameter 0 of constructor in TokenRequestKafkaPublisher required a single bean, but 2 were found:
 *     - tokenUpdateKafkaTemplate
 *     - orderCompletedKafkaTemplate
 * </pre>
 *
 * <p>구매 확정 알림(docs/25 §12)이 {@code KafkaTemplate<String, String>} 을 하나 더 만들었는데, 기존
 * 발행자가 같은 타입을 이름 없이 주입받고 있었다. 발행자 단위 테스트는 목으로 만들어서 <b>빈 배선은
 * 한 번도 검사되지 않았다.</b> 여기서 실제 {@link KafkaProducerConfig} 와 발행자 전부를 스프링
 * 컨텍스트로 띄운다. 브로커에는 붙지 않는다(템플릿은 첫 발송 때 연결한다).
 */
@DisplayName("order-service — Kafka 발행자 빈 배선")
class KafkaPublisherWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withPropertyValues("kafka.url=localhost:9092")
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withUserConfiguration(KafkaProducerConfig.class,
                    TokenRequestKafkaPublisher.class,
                    OrderCompletedKafkaPublisher.class,
                    OrderRefundedKafkaPublisher.class,
                    PaymentRequestKafkaPublisher.class,
                    ProductUpdateKafkaPublisher.class,
                    CartEmptyKafkaPublisher.class,
                    CartRollbackKafkaPublisher.class,
                    CouponRollbackKafkaPublisher.class);

    @Test
    @DisplayName("🔴 모든 발행자가 뜬다 — 같은 타입의 템플릿이 둘이어도 모호하지 않다")
    void everyPublisherStarts() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(TokenRequestKafkaPublisher.class);
            assertThat(context).hasSingleBean(OrderCompletedKafkaPublisher.class);
            assertThat(context).hasSingleBean(OrderRefundedKafkaPublisher.class);
        });
    }

    /** 이름이 뒤바뀌면 토큰 갱신이 구매 확정 토픽 설정으로(또는 그 반대로) 나간다. */
    @Test
    @DisplayName("각 발행자가 자기 템플릿을 받는다 — 토큰 갱신과 구매 확정이 섞이지 않는다")
    void eachPublisherGetsItsOwnTemplate() {
        runner.run(context -> {
            Object token = ReflectionTestUtils.getField(context.getBean(TokenRequestKafkaPublisher.class), "kafkaTemplate");
            Object completed = ReflectionTestUtils.getField(context.getBean(OrderCompletedKafkaPublisher.class), "kafkaTemplate");

            assertThat(token).isSameAs(context.getBean("tokenUpdateKafkaTemplate", KafkaTemplate.class));
            assertThat(completed).isSameAs(context.getBean("orderCompletedKafkaTemplate", KafkaTemplate.class));
            assertThat(token).isNotSameAs(completed);
            assertThat(ReflectionTestUtils.getField(context.getBean(OrderRefundedKafkaPublisher.class), "kafkaTemplate"))
                    .isSameAs(context.getBean("orderRefundedKafkaTemplate", KafkaTemplate.class));
        });
    }
}
