package com.project.pay_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.project.common.constants.PaymentStatus;
import com.project.common.constants.PaymentType;
import com.project.common.message.dto.request.PaymentRequest;
import com.project.common.outbox.OutboxRelayTrigger;
import com.project.common.outbox.OutboxTraceContext;
import com.project.pay_service.outbox.OrderOutboxHelper;
import com.project.pay_service.outbox.repository.jpa.OrderOutboxRepository;
import com.project.pay_service.ports.output.message.publisher.PaymentResponseKafkaPublisher;
import com.project.pay_service.repository.jpa.PaymentRepository;

/**
 * 결제 행과 결제 응답 아웃박스 행은 <b>함께</b> 커밋되거나 <b>함께</b> 되돌려진다 — 실제 트랜잭션(H2)으로.
 *
 * <p>🐛 예전: {@code persistPayment} 에 트랜잭션이 없어 둘이 따로 커밋됐다. 아웃박스 쓰기가 실패하면 결제만 남고 order-service 로 갈
 * 응답이 없었다. 모형(mock) 테스트로는 트랜잭션 경계를 볼 수 없어 실제 JPA · 실제 스프링 프록시로 확인한다.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:pay;MODE=PostgreSQL;INIT=CREATE SCHEMA IF NOT EXISTS pay",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ PaymentService.class, OrderOutboxHelper.class, JacksonAutoConfiguration.class })
@Transactional(propagation = Propagation.NOT_SUPPORTED)   // 테스트 트랜잭션이 서비스의 경계를 가리지 않게
@DisplayName("pay-service — 결제와 아웃박스는 한 트랜잭션")
class PaymentPersistAtomicityTest {

    @Autowired PaymentService paymentService;
    @Autowired PaymentRepository paymentRepository;
    @Autowired OrderOutboxRepository outboxRepository;
    @MockitoSpyBean OrderOutboxHelper outboxHelper;
    @MockitoBean OutboxTraceContext outboxTraceContext;
    @MockitoBean OutboxRelayTrigger relayTrigger;
    @MockitoBean PaymentResponseKafkaPublisher publisher;

    @BeforeEach
    void clean() {
        outboxRepository.deleteAll();
        paymentRepository.deleteAll();
    }

    private static PaymentRequest request(long orderId) {
        return PaymentRequest.builder()
                .orderId(orderId).customerId(7L).trackingId("trk-" + orderId)
                .amounts(new BigDecimal("12000")).paymentType(PaymentType.PAYPAL)
                .paymentStatus(PaymentStatus.COMPLETED).createdAt(LocalDateTime.now())
                .build();
    }

    @Test
    @DisplayName("정상 — 결제 행과 아웃박스 행이 둘 다 남는다")
    void bothRowsCommit() {
        paymentService.persistPayment(request(1L));

        assertThat(paymentRepository.count()).isEqualTo(1);
        assertThat(outboxRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("🔴 아웃박스 쓰기가 실패하면 결제 행도 남지 않는다 — 예전에는 결제만 남고 응답이 사라졌다")
    void outboxFailureRollsBackThePayment() {
        doThrow(new IllegalStateException("outbox down")).when(outboxHelper).saveOrderOutbox(any(), any());

        assertThatThrownBy(() -> paymentService.persistPayment(request(2L))).isInstanceOf(IllegalStateException.class);

        assertThat(paymentRepository.findByTrackingId("trk-2"))
                .as("결제만 커밋되면 order-service 는 응답을 영영 받지 못한다(재전달이 오지 않으면)")
                .isEmpty();
        assertThat(outboxRepository.count()).isZero();
    }

    @Test
    @DisplayName("되돌려진 뒤 재전달이 오면 처음부터 다시 쓴다 — 둘 다 한 번씩")
    void redeliveryAfterRollbackWritesBoth() {
        doThrow(new IllegalStateException("outbox down")).doCallRealMethod().when(outboxHelper).saveOrderOutbox(any(), any());
        assertThatThrownBy(() -> paymentService.persistPayment(request(3L))).isInstanceOf(IllegalStateException.class);

        paymentService.persistPayment(request(3L));

        assertThat(paymentRepository.count()).isEqualTo(1);
        assertThat(outboxRepository.count()).isEqualTo(1);
    }
}
