package com.project.order_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.constants.OrderStatus;
import com.project.common.constants.PaymentProvider;
import com.project.common.constants.PaymentStatus;
import com.project.common.constants.PaymentType;
import com.project.common.message.dto.request.PaymentRequest;
import com.project.common.outbox.OutboxStatus;
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
 * 무통장입금 상태 기계 (docs/49 §11).
 *
 * <pre>
 *   NOT_PROCESSED ─(입금 안내)─▶ UNPAID ─(입금 확인: 관리자)─▶ PAID(사가)
 *                                  └─(기한 경과 · 취소)─▶ CANCELED
 * </pre>
 *
 * <p>여기 고정하는 것: 입금 확인은 카드 결제 확인과 <b>같은 아웃박스</b>로 나간다(그래야
 * 그 뒤가 같다), 돈이 오지 않은 주문은 취소이지 환불이 아니다, 그리고 확인이 끝난 주문은
 * 만료 잡이 접지 않는다.
 */
@DisplayName("무통장입금")
class BankTransferTest {

    private OrderRepository orderRepository;
    private PaymentOutboxHelper outboxHelper;
    private OrderDataService service;
    private Order order;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        outboxHelper = mock(PaymentOutboxHelper.class);

        order = Order.builder()
                .orderId(1L).trackingId("track-1").customerId(7L)
                .paymentMethod("cash").total(new BigDecimal("59.98")).totalBeforeDiscount(new BigDecimal("59.98"))
                .currency("KRW").chargedAmount(new BigDecimal("80973")).fxRate(new BigDecimal("1350"))
                .couponApplied("SUMMER")
                .orderStatus(OrderStatus.NOT_PROCESSED)
                .build();
        when(orderRepository.findByTrackingId("track-1")).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(i -> i.getArgument(0));
        when(orderRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));


        service = new OrderDataService(orderRepository, new ObjectMapper(), mock(OrderAddressRepository.class), outboxHelper,
                new PaymentGateways(List.of(new BankTransferGateway())), new PaymentCurrencyPolicy());
    }

    // ---------------------------------------------------------------- 입금 안내

    @Test
    @DisplayName("입금 안내를 받으면 UNPAID 가 되고 기한·입금자명이 남는다")
    void startMovesToUnpaid() {
        Order o = service.startBankTransfer("track-1", 7L, " 홍길동 ", 48);

        assertThat(o.getOrderStatus()).isEqualTo(OrderStatus.UNPAID);
        assertThat(o.getDepositorName()).isEqualTo("홍길동");
        assertThat(o.getDepositDeadline()).isAfter(LocalDateTime.now().plusHours(47));
        verify(outboxHelper, never()).savePaymentOutbox(any(), any());   // 돈이 안 왔다 — 하류에 알릴 것이 없다
    }

    @Test
    @DisplayName("이미 입금 대기면 입금자명만 고친다 — 기한은 그대로")
    void startIsIdempotentOnUnpaid() {
        LocalDateTime deadline = LocalDateTime.now().plusHours(10);
        order.setOrderStatus(OrderStatus.UNPAID);
        order.setDepositDeadline(deadline);
        order.setDepositorName("홍길동");

        Order o = service.startBankTransfer("track-1", 7L, "김철수", 48);

        assertThat(o.getDepositorName()).isEqualTo("김철수");
        assertThat(o.getDepositDeadline()).isEqualTo(deadline);
    }

    @Test
    @DisplayName("🔒 남의 주문·다른 결제수단·결제된 주문에는 안내를 만들지 않는다")
    void startIsGuarded() {
        assertThatThrownBy(() -> service.startBankTransfer("track-1", 99L, "x", 48))
                .isInstanceOf(PaymentVerificationException.class).hasMessageContaining("찾을 수 없습니다");

        order.setPaymentMethod("stripe");
        assertThatThrownBy(() -> service.startBankTransfer("track-1", 7L, "x", 48))
                .isInstanceOf(PaymentVerificationException.class).hasMessageContaining("무통장입금으로 주문한");

        order.setPaymentMethod("cash");
        order.setOrderStatus(OrderStatus.PAID);
        assertThatThrownBy(() -> service.startBankTransfer("track-1", 7L, "x", 48))
                .isInstanceOf(PaymentVerificationException.class).hasMessageContaining("입금 안내를 받을 수 있는");

        order.setOrderStatus(OrderStatus.NOT_PROCESSED);
        assertThatThrownBy(() -> service.startBankTransfer("track-1", 7L, "  ", 48))
                .isInstanceOf(PaymentVerificationException.class).hasMessageContaining("입금자명");
    }

    // ---------------------------------------------------------------- 입금 확인

    /** 🔴 카드 결제 확인과 같은 아웃박스 — 그래야 pay-service → 사가 → PAID · 재고 차감이 같다. */
    @Test
    @DisplayName("🔴 입금 확인은 결제를 붙이고 같은 아웃박스로 COMPLETED 를 보낸다")
    void confirmDepositGoesThroughTheOutbox() {
        order.setOrderStatus(OrderStatus.UNPAID);

        Order o = service.confirmDeposit("track-1", "TX 2026-09-19 #123");

        assertThat(o.getPaymentProvider()).isEqualTo(PaymentProvider.BANK_TRANSFER);
        assertThat(o.getProviderPaymentId()).startsWith("deposit-1-");
        assertThat(o.getOrderStatus()).as("PAID 는 사가가 결제 응답을 받고 바꾼다").isEqualTo(OrderStatus.UNPAID);

        ArgumentCaptor<PaymentRequest> captor = ArgumentCaptor.forClass(PaymentRequest.class);
        verify(outboxHelper).savePaymentOutbox(captor.capture(), eq(OutboxStatus.STARTED));
        PaymentRequest sent = captor.getValue();
        assertThat(sent.getPaymentStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(sent.getPaymentType()).isEqualTo(PaymentType.CASH);
        assertThat(sent.getPaymentProvider()).isEqualTo(PaymentProvider.BANK_TRANSFER);
        assertThat(sent.getAmounts()).as("하류는 상점 통화").isEqualByComparingTo("59.98");
    }

    @Test
    @DisplayName("이미 확인된 입금은 아웃박스를 또 채우지 않는다 (멱등)")
    void confirmDepositIsIdempotent() {
        order.setOrderStatus(OrderStatus.UNPAID);
        service.confirmDeposit("track-1", null);
        service.confirmDeposit("track-1", null);

        verify(outboxHelper, org.mockito.Mockito.times(1)).savePaymentOutbox(any(), any());
    }

    @Test
    @DisplayName("입금 대기가 아닌 주문은 확인할 수 없다 — 안내 전·결제됨·접힘")
    void confirmDepositNeedsUnpaid() {
        for (OrderStatus s : new OrderStatus[] {OrderStatus.NOT_PROCESSED, OrderStatus.PAID, OrderStatus.CANCELED}) {
            order.setOrderStatus(s);
            assertThatThrownBy(() -> service.confirmDeposit("track-1", null))
                    .as(s.name()).isInstanceOf(PaymentVerificationException.class)
                    .hasMessageContaining("입금 대기 중인 주문이 아닙니다");
        }
        verify(outboxHelper, never()).savePaymentOutbox(any(), any());
    }

    // ---------------------------------------------------------------- 취소·만료

    @Test
    @DisplayName("입금 전에는 손님이 스스로 접을 수 있다 — 환불이 아니라 취소")
    void customerCancelsBeforeDeposit() {
        order.setOrderStatus(OrderStatus.UNPAID);

        Order o = service.cancelUnpaid("track-1", 7L, false);

        assertThat(o.getOrderStatus()).isEqualTo(OrderStatus.CANCELED);
        verify(outboxHelper, never()).savePaymentOutbox(any(), any());
    }

    /** 🔴 입금이 확인된 뒤에는 돈이 있다 — 취소가 아니라 환불의 영역이다. */
    @Test
    @DisplayName("🔴 입금이 확인된 주문은 취소할 수 없다")
    void cannotCancelAfterDepositConfirmed() {
        order.setOrderStatus(OrderStatus.UNPAID);
        service.confirmDeposit("track-1", null);

        assertThatThrownBy(() -> service.cancelUnpaid("track-1", 7L, true))
                .isInstanceOf(PaymentVerificationException.class)
                .hasMessageContaining("입금 대기 중인 주문만");
    }

    @Test
    @DisplayName("기한이 지난 입금 대기 주문은 접힌다 — 확인이 끝난 주문은 건드리지 않는다")
    void expiryClosesOnlyUnconfirmed() {
        order.setOrderStatus(OrderStatus.UNPAID);
        order.setDepositDeadline(LocalDateTime.now().minusHours(1));
        Order confirmed = Order.builder().orderId(2L).trackingId("track-2").customerId(7L)
                .paymentMethod("cash").orderStatus(OrderStatus.UNPAID)
                .paymentProvider(PaymentProvider.BANK_TRANSFER).providerPaymentId("deposit-2")
                .depositDeadline(LocalDateTime.now().minusHours(1)).build();
        when(orderRepository.findByOrderStatusAndDepositDeadlineBefore(eq(OrderStatus.UNPAID), any()))
                .thenReturn(List.of(order, confirmed));

        List<Order> closed = service.expireUnpaid(LocalDateTime.now());

        assertThat(closed).containsExactly(order);
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(confirmed.getOrderStatus()).isEqualTo(OrderStatus.UNPAID);
    }

    // ---------------------------------------------------------------- 환불

    /** 게이트웨이가 언제나 "받아들였다" 고 답하므로 고객이 스스로 부르면 돈 없이 REFUNDED 가 된다. */
    @Test
    @DisplayName("🔒 무통장입금 주문의 환불은 관리자만 — 고객은 고객센터 안내")
    void bankTransferRefundIsAdminOnly() {
        order.setOrderStatus(OrderStatus.PAID);
        order.setPaymentProvider(PaymentProvider.BANK_TRANSFER);
        order.setProviderPaymentId("deposit-1");

        assertThatThrownBy(() -> service.refundOrder("track-1", 7L, false, null))
                .isInstanceOf(PaymentVerificationException.class)
                .hasMessageContaining("환불 계좌");

        Order refunded = service.refundOrder("track-1", 7L, true, "계좌 송금 완료");
        assertThat(refunded.getOrderStatus()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(refunded.getRefundId()).startsWith("bank-refund-");
    }

    // ---------------------------------------------------------------- 포트원 가상계좌

    private static final com.project.order_service.payment.portone.VirtualAccount VA =
            new com.project.order_service.payment.portone.VirtualAccount("KOOKMIN", "123-45-6789", "예시상점",
                    LocalDateTime.now().plusDays(2), new BigDecimal("80973"), "KRW");

    @Test
    @DisplayName("가상계좌가 발급되면 UNPAID 가 되고 PG 의 계좌·기한이 주문에 남는다")
    void virtualAccountMovesToUnpaid() {
        order.setPaymentMethod("portone");

        Order o = service.registerVirtualAccount("track-1", "pay-1", VA, 7L, 48);

        assertThat(o.getOrderStatus()).isEqualTo(OrderStatus.UNPAID);
        assertThat(o.hasVirtualAccount()).isTrue();
        assertThat(o.getVbankAccount()).isEqualTo("123-45-6789");
        assertThat(o.getPendingPaymentRef()).isEqualTo("pay-1");
        assertThat(o.getDepositDeadline()).isEqualTo(VA.dueAt());
        verify(outboxHelper, never()).savePaymentOutbox(any(), any());
    }

    @Test
    @DisplayName("발급 웹훅과 복귀가 겹쳐도 한 번이다 — 같은 참조면 그대로")
    void virtualAccountIsIdempotent() {
        order.setPaymentMethod("portone");
        service.registerVirtualAccount("track-1", "pay-1", VA, null, 48);
        service.registerVirtualAccount("track-1", "pay-1", VA, 7L, 48);

        verify(orderRepository, org.mockito.Mockito.times(1)).save(any(Order.class));
    }

    /** 🔒 1원짜리 가상계좌를 붙여 두면 손님이 틀린 안내를 본다 — 청구액과 대조한다. */
    @Test
    @DisplayName("🔒 가상계좌 금액·통화가 청구액과 다르면 붙이지 않는다")
    void virtualAccountAmountMustMatch() {
        order.setPaymentMethod("portone");
        var wrong = new com.project.order_service.payment.portone.VirtualAccount("KOOKMIN", "1", "x",
                null, new BigDecimal("1"), "KRW");

        assertThatThrownBy(() -> service.registerVirtualAccount("track-1", "pay-1", wrong, null, 48))
                .isInstanceOf(PaymentVerificationException.class).hasMessageContaining("금액이 청구액과 다릅니다");
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.NOT_PROCESSED);
    }

    @Test
    @DisplayName("🔒 남의 주문에는 가상계좌를 붙일 수 없다 (복귀 경로)")
    void virtualAccountOwnership() {
        assertThatThrownBy(() -> service.registerVirtualAccount("track-1", "pay-1", VA, 99L, 48))
                .isInstanceOf(PaymentVerificationException.class).hasMessageContaining("찾을 수 없습니다");
    }

    /** 사람이 확인하면 PG 의 진실과 갈린다 — 나중에 웹훅이 오면 두 번 반영된다. */
    @Test
    @DisplayName("🔒 가상계좌 주문은 관리자가 입금을 확인할 수 없다 — 포트원 웹훅이 한다")
    void virtualAccountCannotBeConfirmedByAdmin() {
        order.setPaymentMethod("portone");
        service.registerVirtualAccount("track-1", "pay-1", VA, null, 48);

        assertThatThrownBy(() -> service.confirmDeposit("track-1", null))
                .isInstanceOf(PaymentVerificationException.class).hasMessageContaining("포트원 웹훅");
        verify(outboxHelper, never()).savePaymentOutbox(any(), any());
    }

    /** 🔴 기한이 지나 우리가 접은 뒤 입금이 도착하면 조용히 삼키지 않는다 — 수동 환불이 보여야 한다. */
    @Test
    @DisplayName("🔴 취소된 주문에 결제가 도착하면 거절하고 error 로 남긴다")
    void paymentOnCanceledOrderIsRejected() {
        order.setOrderStatus(OrderStatus.CANCELED);
        order.setPaymentMethod("portone");
        com.project.order_service.payment.PaymentGateway portone = mock(com.project.order_service.payment.PaymentGateway.class);
        when(portone.provider()).thenReturn(PaymentProvider.PORTONE);
        // 소유권 검사는 주문자 id(7)로 통과한다 — 이 테스트의 관심은 그 뒤의 상태 검사다.
        OrderDataService withPortOne = new OrderDataService(orderRepository,
                new ObjectMapper(), mock(OrderAddressRepository.class),
                outboxHelper, new PaymentGateways(List.of(portone)), new PaymentCurrencyPolicy());

        assertThatThrownBy(() -> withPortOne.recordPayment("track-1", PaymentProvider.PORTONE, "pay-late",
                7L))
                .isInstanceOf(PaymentVerificationException.class).hasMessageContaining("취소된 주문");
        verify(portone, never()).verify(any());
        verify(outboxHelper, never()).savePaymentOutbox(any(), any());
    }

    @Test
    @DisplayName("결제수단 id cash 는 BANK_TRANSFER 로 읽힌다 — 청구 통화 설정이 그 이름을 쓴다")
    void cashMapsToBankTransfer() {
        assertThat(PaymentProvider.from("cash")).isEqualTo(PaymentProvider.BANK_TRANSFER);
        assertThat(PaymentProvider.from("CASH")).isEqualTo(PaymentProvider.BANK_TRANSFER);
        assertThat(PaymentProvider.from("bank_transfer")).isEqualTo(PaymentProvider.BANK_TRANSFER);
    }
}
