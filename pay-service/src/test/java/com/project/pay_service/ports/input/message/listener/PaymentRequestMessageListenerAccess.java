package com.project.pay_service.ports.input.message.listener;

import com.project.common.message.dto.request.PaymentRequest;
import com.project.pay_service.service.PaymentService;

/** 테스트용 — 패키지 안의 PaymentRequestMessageListener 를 다른 패키지의 테스트가 부르게 한다. */
public class PaymentRequestMessageListenerAccess {

    private final PaymentRequestMessageListener listener;

    public PaymentRequestMessageListenerAccess(PaymentService payments) {
        this.listener = new PaymentRequestMessageListener(payments);
    }

    public void complete(PaymentRequest request) {
        listener.completePayment(request);
    }
}
