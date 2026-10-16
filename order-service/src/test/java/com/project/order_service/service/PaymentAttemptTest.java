package com.project.order_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.constants.OrderStatus;
import com.project.common.constants.PaymentProvider;
import com.project.order_service.client.CatalogServiceClient;
import com.project.order_service.model.Order;
import com.project.order_service.outbox.PaymentOutboxHelper;
import com.project.order_service.payment.PaymentCurrencyPolicy;
import com.project.order_service.payment.PaymentGateways;
import com.project.order_service.payment.PaymentVerificationException;
import com.project.order_service.payment.bank.BankTransferGateway;
import com.project.order_service.repository.jpa.OrderAddressRepository;
import com.project.order_service.repository.jpa.OrderRepository;

/**
 * 주문 하나에 결제창 하나(V27) — 결제 시작이 창마다의 시도 키를 적고, 살아 있는 동안 다른 키를 거절한다. 이미 결제가 붙은 주문에는
 * 결제 시작 · 입금 안내 · 입금 확인을 하지 않는다.
 *
 * <p>🐛 예전: 결제창을 서버가 기록하지 않아 두 탭에서 둘 다 결제할 수 있었다. 입금 확인은 UNPAID 만 보고 이미 붙은 카드 결제의 참조를
 * 덮어썼고, 입금 안내는 결제 기록 뒤 사가가 PAID 로 바꾸기 전의 틈에 UNPAID 로 바꿨다.
 *
 * <p>[JA] 注文一つに決済画面一つ。決済済みの注文には決済開始 · 入金案内 · 入金確認をしない。
 */
@DisplayName("결제 시도 — 주문당 결제창 하나")
class PaymentAttemptTest {

    private OrderRepository orderRepository;
    private PaymentOutboxHelper outboxHelper;
    private OrderDataService service;
    private Order order;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        outboxHelper = mock(PaymentOutboxHelper.class);
        order = Order.builder().orderId(1L).trackingId("track-1").customerId(7L).paymentMethod("cash")
                .total(new BigDecimal("59.98")).orderStatus(OrderStatus.NOT_PROCESSED).build();
        when(orderRepository.findByTrackingId("track-1")).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(i -> i.getArgument(0));
        service = new OrderDataService(orderRepository, new ObjectMapper(), mock(OrderAddressRepository.class), outboxHelper,
                new PaymentGateways(List.of(new BankTransferGateway())), new PaymentCurrencyPolicy());
    }

    private void start(String attempt) {
        service.startPaymentAttempt("track-1", attempt, Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("🔴 한 창이 결제 중이면 다른 창의 결제 시작을 거절한다 — 같은 창은 다시 열고 끝을 늘린다")
    void otherWindowIsRefused() {
        start("tab-one-attempt");
        assertThat(order.getPaymentAttempt()).isEqualTo("tab-one-attempt");
        LocalDateTime first = order.getPaymentAttemptUntil();
        assertThat(first).isAfter(LocalDateTime.now().plusMinutes(9));

        assertThatThrownBy(() -> start("tab-two-attempt"))
                .isInstanceOf(PaymentVerificationException.class).hasMessage(OrderDataService.OTHER_WINDOW_MESSAGE);
        assertThat(order.getPaymentAttempt()).as("거절이 시도를 덮지 않는다").isEqualTo("tab-one-attempt");

        assertThatCode(() -> start("tab-one-attempt")).doesNotThrowAnyException();
        assertThat(order.getPaymentAttemptUntil()).isAfterOrEqualTo(first);
    }

    @Test
    @DisplayName("끝이 지난 시도는 다른 창이 넘겨받는다 — 결제창을 닫고 떠난 탭이 주문을 붙들지 않는다. 키가 없으면(옛 화면) 적지 않는다")
    void expiredAttemptIsTakenOverAndNoKeyWritesNothing() {
        order.setPaymentAttempt("tab-one-attempt");
        order.setPaymentAttemptUntil(LocalDateTime.now().minusSeconds(1));
        start("tab-two-attempt");
        assertThat(order.getPaymentAttempt()).isEqualTo("tab-two-attempt");

        order.setPaymentAttemptUntil(LocalDateTime.now().plusMinutes(5));
        assertThatCode(() -> start(null)).doesNotThrowAnyException();
        assertThat(order.getPaymentAttempt()).isEqualTo("tab-two-attempt");
    }

    @Test
    @DisplayName("🔴 이미 결제가 붙은 주문은 키와 상관없이 결제를 시작하지 않는다(결제 기록 뒤 PAID 전의 틈 포함)")
    void paidOrderRefusesStart() {
        order.setPaymentProvider(PaymentProvider.PAYPAL);
        order.setProviderPaymentId("CAPTURE-1");

        assertThatThrownBy(() -> start("tab-one-attempt")).hasMessage(OrderDataService.ALREADY_PAID_MESSAGE);
        assertThatThrownBy(() -> start(null)).hasMessage(OrderDataService.ALREADY_PAID_MESSAGE);
        verify(orderRepository, org.mockito.Mockito.times(2)).lockVersion(1L);   // 잠그고 본다
    }

    @Test
    @DisplayName("🔴 결제가 붙은 주문에 입금 안내를 내지 않는다 — 예전에는 PAID 전의 틈에 UNPAID 로 바꿨다")
    void bankTransferStartRefusesPaidOrder() {
        order.setPaymentProvider(PaymentProvider.PAYPAL);
        order.setProviderPaymentId("CAPTURE-1");

        assertThatThrownBy(() -> service.startBankTransfer("track-1", 7L, "홍길동", 48))
                .hasMessage(OrderDataService.ALREADY_PAID_MESSAGE);
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.NOT_PROCESSED);
    }

    @Test
    @DisplayName("🔴 다른 결제가 붙은 주문의 입금 확인은 덮지 않고 거절하며 이중 결제로 센다")
    void depositOnPaidOrderIsRefusedAndCounted() {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        io.micrometer.core.instrument.Metrics.addRegistry(meters);
        try {
            order.setOrderStatus(OrderStatus.UNPAID);
            order.setPaymentProvider(PaymentProvider.PAYPAL);
            order.setProviderPaymentId("CAPTURE-1");

            assertThatThrownBy(() -> service.confirmDeposit("track-1", "TX-1"))
                    .isInstanceOf(PaymentVerificationException.class).hasMessage(OrderDataService.DEPOSIT_ON_PAID_MESSAGE);

            assertThat(order.getProviderPaymentId()).as("카드 결제의 참조가 남는다").isEqualTo("CAPTURE-1");
            verify(outboxHelper, never()).savePaymentOutbox(any(), any());
            assertThat(meters.counter(OrderDataService.DUPLICATE_PAYMENT_METRIC, "provider", "BANK_TRANSFER").count()).isEqualTo(1);
        } finally {
            io.micrometer.core.instrument.Metrics.removeRegistry(meters);
        }
    }
}
