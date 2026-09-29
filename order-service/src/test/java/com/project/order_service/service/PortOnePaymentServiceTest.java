package com.project.order_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.constants.OrderStatus;
import com.project.common.constants.PaymentProvider;
import com.project.order_service.model.Order;
import com.project.order_service.payment.PaymentVerificationException;
import com.project.order_service.payment.VerifiedPayment;
import com.project.order_service.payment.portone.PortOneGateway;
import com.project.order_service.payment.portone.VirtualAccount;

/**
 * 포트원 웹훅·복귀 — 결제 완료와 <b>가상계좌 발급</b>을 가른다 (docs/49 §11).
 *
 * <p>어느 쪽인지는 본문이 아니라 <b>포트원 조회</b>가 말한다. 발급이면 계좌를 주문에 붙이고
 * (입금 대기), 완료면 결제를 붙인다 — 가상계좌 입금의 완료 웹훅이 곧 "입금 확인" 이다.
 */
@DisplayName("포트원 가상계좌")
class PortOnePaymentServiceTest {

    private OrderCacheService cached;
    private OrderDataService data;
    private PortOneGateway gateway;
    private BankTransferService bankTransfer;
    private PortOnePaymentService service;

    private static final VirtualAccount VA = new VirtualAccount("KOOKMIN", "123-45-6789", "예시상점",
            LocalDateTime.now().plusDays(2), new BigDecimal("80973"), "KRW");

    @BeforeEach
    void setUp() {
        cached = mock(OrderCacheService.class);
        data = mock(OrderDataService.class);
        gateway = mock(PortOneGateway.class);
        bankTransfer = mock(BankTransferService.class);
        service = new PortOnePaymentService(cached, data, gateway, Optional.empty(), new ObjectMapper(), bankTransfer);

        Order order = Order.builder().orderId(1L).trackingId("track-1").customerId(7L)
                .orderStatus(OrderStatus.NOT_PROCESSED).paymentMethod("portone").build();
        when(data.getOrderByTrackingId("track-1")).thenReturn(order);
        when(gateway.trackingIdOf("pay-1")).thenReturn("track-1");
    }

    private void portOneSays(String status, boolean completed, Optional<VirtualAccount> va) {
        when(gateway.verify("pay-1")).thenReturn(new VerifiedPayment(PaymentProvider.PORTONE, "pay-1",
                completed, status, new BigDecimal("80973"), "KRW"));
        when(gateway.virtualAccountOf("pay-1")).thenReturn(va);
    }

    // ---------------------------------------------------------------- 웹훅

    @Test
    @DisplayName("발급 웹훅: 포트원이 '발급됨' 이라고 하면 계좌를 주문에 붙인다 — 결제는 붙이지 않는다")
    void issuedWebhookRegistersAccount() {
        when(gateway.paidPaymentRefOf(any())).thenReturn(Optional.empty());
        when(gateway.issuedPaymentRefOf(any())).thenReturn(Optional.of("pay-1"));
        portOneSays("VIRTUAL_ACCOUNT_ISSUED", false, Optional.of(VA));

        service.handleWebhook("{\"type\":\"Transaction.VirtualAccountIssued\",\"data\":{\"paymentId\":\"pay-1\"}}",
                null, null, null);

        verify(bankTransfer).registerVirtualAccount(eq("track-1"), eq("pay-1"), eq(VA), isNull());
        verify(cached, never()).recordPayment(any(), any(), any(), any());
    }

    /** 🔴 가상계좌 입금의 완료 웹훅이 곧 입금 확인이다 — 관리자 손을 거치지 않는다. */
    @Test
    @DisplayName("🔴 완료 웹훅: 결제를 붙인다 — 가상계좌 입금이면 이것이 입금 확인이다")
    void paidWebhookRecordsPayment() {
        when(gateway.paidPaymentRefOf(any())).thenReturn(Optional.of("pay-1"));
        portOneSays("PAID", true, Optional.empty());

        service.handleWebhook("{\"type\":\"Transaction.Paid\",\"data\":{\"paymentId\":\"pay-1\"}}", null, null, null);

        verify(cached).recordPayment("track-1", PaymentProvider.PORTONE, "pay-1", 7L);
        verify(bankTransfer, never()).registerVirtualAccount(any(), any(), any(), any());
    }

    /** 🔒 본문이 "발급됐다" 고 해도 포트원이 아니라고 하면 아무것도 붙지 않는다. */
    @Test
    @DisplayName("🔒 본문은 단서일 뿐 — 포트원 조회가 발급도 완료도 아니라면 무시")
    void bodyIsOnlyAHint() {
        when(gateway.paidPaymentRefOf(any())).thenReturn(Optional.empty());
        when(gateway.issuedPaymentRefOf(any())).thenReturn(Optional.of("pay-1"));
        portOneSays("FAILED", false, Optional.empty());

        service.handleWebhook("{\"type\":\"Transaction.VirtualAccountIssued\",\"data\":{\"paymentId\":\"pay-1\"}}",
                null, null, null);

        verify(bankTransfer, never()).registerVirtualAccount(any(), any(), any(), any());
        verify(cached, never()).recordPayment(any(), any(), any(), any());
    }

    @Test
    @DisplayName("관계없는 알림(취소·실패)은 200 으로 끝난다")
    void unrelatedIsIgnored() {
        when(gateway.paidPaymentRefOf(any())).thenReturn(Optional.empty());
        when(gateway.issuedPaymentRefOf(any())).thenReturn(Optional.empty());

        service.handleWebhook("{\"type\":\"Transaction.Cancelled\",\"data\":{\"paymentId\":\"pay-1\"}}", null, null, null);

        verify(gateway, never()).verify(any());
    }

    // ---------------------------------------------------------------- 브라우저 복귀

    @Test
    @DisplayName("복귀: 가상계좌가 발급된 것이면 계좌를 붙이고 VIRTUAL_ACCOUNT_ISSUED 를 돌려준다")
    void returnWithVirtualAccount() {
        portOneSays("VIRTUAL_ACCOUNT_ISSUED", false, Optional.of(VA));

        PortOnePaymentService.Outcome outcome = service.confirmReturn("track-1", "pay-1", 7L);

        assertThat(outcome).isEqualTo(PortOnePaymentService.Outcome.VIRTUAL_ACCOUNT_ISSUED);
        verify(bankTransfer).registerVirtualAccount("track-1", "pay-1", VA, 7L);
        verify(cached, never()).recordPayment(any(), any(), any(), any());
    }

    @Test
    @DisplayName("복귀: 결제가 끝났으면 예전처럼 결제를 붙이고 PAID")
    void returnWithPayment() {
        portOneSays("PAID", true, Optional.empty());

        PortOnePaymentService.Outcome outcome = service.confirmReturn("track-1", "pay-1", 7L);

        assertThat(outcome).isEqualTo(PortOnePaymentService.Outcome.PAID);
        verify(cached).recordPayment("track-1", PaymentProvider.PORTONE, "pay-1", 7L);
    }

    /** 🔒 다른 주문의 가상계좌를 내 주문에 붙일 수 없다 — 포트원이 말하는 주문 참조로 대조. */
    @Test
    @DisplayName("🔒 복귀: 가상계좌가 다른 주문의 것이면 거절")
    void returnWithSomeoneElsesAccount() {
        portOneSays("VIRTUAL_ACCOUNT_ISSUED", false, Optional.of(VA));
        when(gateway.trackingIdOf("pay-1")).thenReturn("track-OTHER");

        assertThatThrownBy(() -> service.confirmReturn("track-1", "pay-1", 7L))
                .isInstanceOf(PaymentVerificationException.class)
                .hasMessageContaining("다른 주문");
        verify(bankTransfer, never()).registerVirtualAccount(any(), any(), any(), any());
    }
}
