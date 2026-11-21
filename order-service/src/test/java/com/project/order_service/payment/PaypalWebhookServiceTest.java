package com.project.order_service.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import com.project.common.constants.PaymentProvider;
import com.project.order_service.service.OrderDataService;
import com.project.order_service.service.OrderDataService.ExternalRefund;
import com.project.order_service.service.ProcessedEventLedger;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * PayPal 웹훅(5단계 14) — 서명은 PayPal 에 묻고, 환불은 캡처 id 로 PayPal 에 다시 물어 주문을 찾아 외부 환불로 반영, 분쟁은 한 번만 세고,
 * 그 밖(구독 등)은 받아만 둔다.
 * [JA] PayPal のウェブフック — 署名は PayPal に問い、返金はキャプチャ id で問い直して注文を探し、紛争は一度だけ数え、その他は受けるだけ。
 */
@DisplayName("PayPal 웹훅 — 서명 · 환불 반영 · 분쟁 · 그 밖")
class PaypalWebhookServiceTest {

    private static final Map<String, String> HEADERS = Map.of("paypal-transmission-id", "t");

    private boolean signatureOk = true;
    private final PaypalOrderVerifier paypal = new PaypalOrderVerifier() {
        @Override
        public VerifiedPaypalOrder verify(String paypalOrderId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RefundedPaypalOrder refund(String paypalOrderId, BigDecimal amount, String currency, String refundKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean verifyWebhookSignature(String webhookId, Map<String, String> headers, String rawEvent) {
            return signatureOk && "WH-1".equals(webhookId);
        }

        @Override
        public String paypalOrderIdOfCapture(String captureId) {
            return "CAP-9".equals(captureId) ? "PO-1" : null;
        }
    };
    private OrderDataService orders;
    private ProcessedEventLedger ledger;
    private SimpleMeterRegistry meters;
    private PaypalWebhookService svc;

    @BeforeEach
    void setUp() {
        orders = mock(OrderDataService.class);
        ledger = mock(ProcessedEventLedger.class);
        meters = new SimpleMeterRegistry();
        svc = new PaypalWebhookService(paypal, orders, ledger, TransactionOperations.withoutTransaction(), "WH-1");
        svc.meters = meters;
        svc.registerAlertCounters();
    }

    private static String refundEvent(String type, String value) {
        return "{\"id\":\"WH-EV-1\",\"event_type\":\"" + type + "\",\"resource_version\":\"2.0\",\"resource\":{\"id\":\"R-1\","
                + "\"amount\":{\"value\":\"" + value + "\",\"currency_code\":\"USD\"},\"links\":["
                + "{\"rel\":\"self\",\"href\":\"https://api.sandbox.paypal.com/v2/payments/refunds/R-1\"},"
                + "{\"rel\":\"up\",\"href\":\"https://api.sandbox.paypal.com/v2/payments/captures/CAP-9\"}]}}";
    }

    private double count(String metric, String... tags) {
        var c = meters.find(metric).tags(tags).counter();
        return c == null ? -1 : c.count();
    }

    @Test
    @DisplayName("🔴 서명이 틀리면 거절 — 주문을 건드리지 않는다")
    void badSignatureIsRejected() {
        signatureOk = false;
        assertThatThrownBy(() -> svc.handle(refundEvent("PAYMENT.CAPTURE.REFUNDED", "10.00"), HEADERS))
                .isInstanceOf(PaypalWebhookService.Rejected.class);
        verify(orders, never()).recordExternalRefund(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("🔴 환불 — 캡처 id(up 링크)로 PayPal 에 물어 찾은 주문에 외부 환불로 반영, 일부면 경보 계수기")
    void refundIsRecordedAgainstTheCapturesOrder() {
        when(orders.recordExternalRefund(eq(PaymentProvider.PAYPAL), eq("PO-1"), eq("R-1"), any(), eq("USD"), anyString()))
                .thenReturn(ExternalRefund.RECORDED_PARTIAL);

        assertThat(svc.handle(refundEvent("PAYMENT.CAPTURE.REFUNDED", "10.00"), HEADERS)).isEqualTo(PaypalWebhookService.Result.HANDLED);

        verify(orders).recordExternalRefund(eq(PaymentProvider.PAYPAL), eq("PO-1"), eq("R-1"),
                eq(new BigDecimal("10.00")), eq("USD"), anyString());
        assertThat(count(PaypalWebhookService.REFUND_METRIC, "result", "partial")).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 반영된 환불(우리 API 환불의 웹훅)은 DUPLICATE · 계수기 그대로")
    void ownRefundIsDuplicate() {
        when(orders.recordExternalRefund(any(), any(), any(), any(), any(), any())).thenReturn(ExternalRefund.ALREADY_RECORDED);
        assertThat(svc.handle(refundEvent("PAYMENT.CAPTURE.REVERSED", "10.00"), HEADERS)).isEqualTo(PaypalWebhookService.Result.DUPLICATE);
        assertThat(count(PaypalWebhookService.REFUND_METRIC, "result", "partial")).isZero();
    }

    @Test
    @DisplayName("🔴 분쟁 — 이벤트 id 로 한 번만 센다(같은 이벤트가 다시 와도)")
    void disputeIsCountedOnce() {
        String dispute = "{\"id\":\"WH-EV-2\",\"event_type\":\"CUSTOMER.DISPUTE.CREATED\",\"resource\":{\"dispute_id\":\"PP-D-1\","
                + "\"status\":\"OPEN\",\"reason\":\"MERCHANDISE_OR_SERVICE_NOT_RECEIVED\","
                + "\"disputed_transactions\":[{\"seller_transaction_id\":\"CAP-9\"}]}}";
        when(ledger.markFirst("paypal-webhook:WH-EV-2")).thenReturn(true).thenReturn(false);

        assertThat(svc.handle(dispute, HEADERS)).isEqualTo(PaypalWebhookService.Result.HANDLED);
        assertThat(svc.handle(dispute, HEADERS)).isEqualTo(PaypalWebhookService.Result.DUPLICATE);
        assertThat(count(PaypalWebhookService.DISPUTE_METRIC, "provider", "paypal")).isEqualTo(1);
    }

    @Test
    @DisplayName("처리하지 않는 종류(구독 · Sale)는 받아만 둔다 · 웹훅 id 가 비면 꺼짐")
    void otherTypesAreIgnoredAndBlankIdDisables() {
        String sub = "{\"id\":\"WH-EV-3\",\"event_type\":\"BILLING.SUBSCRIPTION.ACTIVATED\",\"resource_version\":\"2.0\",\"resource\":{}}";
        assertThat(svc.handle(sub, HEADERS)).isEqualTo(PaypalWebhookService.Result.IGNORED);
        assertThat(new PaypalWebhookService(paypal, orders, ledger, TransactionOperations.withoutTransaction(), " ").enabled()).isFalse();
        assertThatThrownBy(() -> svc.handle("not json", HEADERS)).isInstanceOf(PaypalWebhookService.Rejected.class);
    }
}
