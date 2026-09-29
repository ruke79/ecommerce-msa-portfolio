package com.project.order_service.payment;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.project.common.constants.PaymentProvider;

import lombok.extern.slf4j.Slf4j;

/**
 * 제공자 → 게이트웨이.
 *
 * <p>스프링이 찾은 {@link PaymentGateway} 빈을 모아 {@code provider()} 로 색인한다.
 * 그래서 새 결제수단을 붙이는 쪽은 <b>여기를 고치지 않는다</b> — 빈 하나를 만들면 된다.
 *
 * <p>기동 시점에 무엇이 붙었는지 로그로 남긴다. 설정 때문에 게이트웨이가 하나 빠져도
 * 증상은 "그 결제수단만 안 된다"라서, 그 사실이 <b>기동 로그에 보여야</b> 한다.
 */
@Slf4j
@Component
public class PaymentGateways {

    private final Map<PaymentProvider, PaymentGateway> byProvider =
            new EnumMap<>(PaymentProvider.class);

    public PaymentGateways(List<PaymentGateway> gateways) {
        for (PaymentGateway gateway : gateways) {
            PaymentGateway previous = byProvider.put(gateway.provider(), gateway);
            if (previous != null) {
                // 같은 제공자에 둘이 등록되면 어느 쪽이 이겼는지 알 수 없다. 설정 실수다.
                throw new IllegalStateException(
                        "결제 게이트웨이가 중복 등록됐습니다: " + gateway.provider()
                                + " (" + previous.getClass().getSimpleName()
                                + ", " + gateway.getClass().getSimpleName() + ")");
            }
        }
        log.info("결제 게이트웨이: {}", byProvider.keySet());
    }

    /**
     * @throws PaymentVerificationException 그 제공자를 다룰 수 없을 때. 조용히 null 을
     *         돌려주면 호출부에서 NPE 가 나고 원인이 한 겹 묻힌다 — 이 저장소가 이미
     *         겪은 실패다(docs/47).
     */
    public PaymentGateway require(PaymentProvider provider) {
        PaymentGateway gateway = (provider == null) ? null : byProvider.get(provider);
        if (gateway == null) {
            throw new PaymentVerificationException(
                    "지원하지 않는 결제수단입니다: " + provider);
        }
        return gateway;
    }

    /** 호스팅 결제 페이지를 여는 제공자인지. 아니면 시작 단계가 없다(PayPal 처럼). */
    /** 환불할 수 있는 게이트웨이. 환불 API 가 없는 제공자면 이유를 달고 거부한다. */
    public RefundingGateway requireRefunding(PaymentProvider provider) {
        PaymentGateway gateway = require(provider);
        if (!(gateway instanceof RefundingGateway refunding)) {
            throw new PaymentVerificationException(
                    "이 결제수단은 자동 환불을 지원하지 않습니다: " + provider);
        }
        return refunding;
    }

    public HostedCheckoutGateway requireHosted(PaymentProvider provider) {
        PaymentGateway gateway = require(provider);
        if (!(gateway instanceof HostedCheckoutGateway hosted)) {
            throw new PaymentVerificationException(
                    "결제 페이지를 여는 방식이 아닙니다: " + provider);
        }
        return hosted;
    }
}
