package com.project.order_service.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.common.constants.PaymentProvider;

/**
 * 결제 제공자 레지스트리.
 *
 * <h3>이 포트가 존재하는 이유</h3>
 *
 * <p>예전에는 결제 확인이 {@code persisitPaypalPayment} 한 메서드였고, 거기 <b>제공자별
 * 조회</b>와 <b>모든 결제 공통 규칙</b>(금액 대조·통화 대조·재사용 차단·아웃박스)이
 * 섞여 있었다. 그 상태로 결제수단을 추가하면 규칙이 복사되고, 복사본 하나에서 금액
 * 대조를 빠뜨리면 <b>그 수단으로만</b> 결제 없이 주문이 완료된다.
 *
 * <p>그래서 이 테스트가 고정하는 것은 "게이트웨이를 하나 더 꽂으면 그대로 동작한다" 이다 —
 * 새 결제수단을 붙이는 사람이 <b>규칙을 다시 쓰지 않아도 된다</b>는 뜻이기 때문이다.
 */
@DisplayName("결제 제공자 레지스트리")
class PaymentGatewaysTest {

    /** 새 결제수단을 붙이는 쪽이 작성할 코드의 전부. */
    private static PaymentGateway gateway(PaymentProvider provider) {
        return new PaymentGateway() {
            @Override
            public PaymentProvider provider() {
                return provider;
            }

            @Override
            public VerifiedPayment verify(String providerPaymentId) {
                return new VerifiedPayment(provider, providerPaymentId, true, "ok",
                        new BigDecimal("100"), "USD");
            }
        };
    }

    @Test
    @DisplayName("제공자로 게이트웨이를 찾는다")
    void resolvesByProvider() {
        PaymentGateways gateways = new PaymentGateways(
                List.of(gateway(PaymentProvider.PAYPAL), gateway(PaymentProvider.STRIPE)));

        assertThat(gateways.require(PaymentProvider.STRIPE).provider())
                .isEqualTo(PaymentProvider.STRIPE);
    }

    /**
     * 🔴 조용히 null 을 돌려주면 호출부에서 NPE 가 나고 원인이 한 겹 묻힌다 —
     * 이 저장소가 이미 겪은 실패다(docs/47).
     */
    @Test
    @DisplayName("모르는 제공자는 이유를 달고 거부한다")
    void anUnknownProviderIsRejectedWithAReason() {
        PaymentGateways gateways = new PaymentGateways(List.of(gateway(PaymentProvider.PAYPAL)));

        assertThatThrownBy(() -> gateways.require(PaymentProvider.STRIPE))
                .isInstanceOf(PaymentVerificationException.class)
                .hasMessageContaining("지원하지 않는");

        assertThatThrownBy(() -> gateways.require(null))
                .isInstanceOf(PaymentVerificationException.class);
    }

    /**
     * 같은 제공자에 둘이 등록되면 <b>어느 쪽이 이겼는지 알 수 없다.</b> 결제 확인의
     * 동작이 빈 등록 순서에 좌우되는 것은 설정 실수이고, 기동 시점에 드러나야 한다.
     */
    @Test
    @DisplayName("같은 제공자가 둘이면 기동을 막는다")
    void duplicateProvidersFailFast() {
        assertThatThrownBy(() -> new PaymentGateways(
                List.of(gateway(PaymentProvider.PAYPAL), gateway(PaymentProvider.PAYPAL))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("중복");
    }

    /**
     * 🔴 <b>새 결제수단을 붙이는 데 필요한 것이 이것뿐임을 고정한다.</b> 레지스트리를
     * 고치지 않아도 되고(빈을 모아서 색인한다), 확인 규칙을 다시 쓰지 않아도 된다.
     */
    @Test
    @DisplayName("게이트웨이를 꽂는 것만으로 등록된다 — 레지스트리를 고치지 않는다")
    void addingAProviderNeedsNothingElse() {
        PaymentGateways gateways = new PaymentGateways(List.of(
                gateway(PaymentProvider.PAYPAL),
                gateway(PaymentProvider.STRIPE),
                gateway(PaymentProvider.PORTONE),
                gateway(PaymentProvider.BANK_TRANSFER)));

        for (PaymentProvider provider : PaymentProvider.values()) {
            assertThat(gateways.require(provider).provider()).isEqualTo(provider);
        }
    }

    /**
     * 호스팅 결제 페이지를 여는 제공자와 아닌 제공자를 구분한다.
     *
     * <p>Stripe Checkout 은 <b>서버가 세션을 먼저 만들고</b> 브라우저를 보내지만,
     * PayPal·포트원은 <b>브라우저가 결제창을 연다.</b> 시작 단계가 없는 제공자에게 빈
     * 메서드를 강요하지 않으려고 포트를 둘로 나눴다.
     */
    @Test
    @DisplayName("시작 단계가 없는 제공자에게 결제 페이지를 요구하면 거부한다")
    void aNonHostedProviderCannotStartCheckout() {
        PaymentGateways gateways = new PaymentGateways(List.of(gateway(PaymentProvider.PORTONE)));

        assertThatThrownBy(() -> gateways.requireHosted(PaymentProvider.PORTONE))
                .isInstanceOf(PaymentVerificationException.class)
                .hasMessageContaining("결제 페이지");
    }
}
