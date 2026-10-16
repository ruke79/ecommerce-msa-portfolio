package com.project.pay_service.ports.input.message.listener;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import com.project.common.constants.PaymentStatus;
import com.project.common.message.dto.request.PaymentRequest;
import com.project.common.outbox.OutboxStatus;
import com.project.pay_service.outbox.OrderOutboxHelper;
import com.project.pay_service.outbox.model.OrderOutboxEvent;
import com.project.pay_service.ports.output.message.publisher.PaymentResponseKafkaPublisher;
import com.project.pay_service.service.PaymentService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Validated
@Service
@RequiredArgsConstructor
public class PaymentRequestMessageListener {

        
    
    private final PaymentService paymentService;

    void completePayment(PaymentRequest paymentRequest) {

        if (paymentService.publishIfOutboxEventProcessedForPayment(Long.toString(paymentRequest.getOrderId()),
                paymentRequest.getPaymentStatus(), OutboxStatus.COMPLETED)) {
            log.info("An outbox message with aggregate id: {} is already saved to database!",
                    paymentRequest.getOrderId());
            return;
        }
        if (paymentService.hasPendingResponse(Long.toString(paymentRequest.getOrderId()), paymentRequest.getPaymentStatus())) {
            return;
        }
        log.info("Received payment complete event for order id: {}", paymentRequest.getOrderId());
        
        paymentService.persistPayment(paymentRequest);

    }

    void cancelPayment(PaymentRequest paymentRequest) {

        if (paymentService.publishIfOutboxEventProcessedForPayment(Long.toString(paymentRequest.getOrderId()),
                paymentRequest.getPaymentStatus(), OutboxStatus.COMPLETED)) {
            log.info("An outbox message with aggregate id: {} is already saved to database!",
                    paymentRequest.getOrderId());
            return;
        }
        if (paymentService.hasPendingResponse(Long.toString(paymentRequest.getOrderId()), paymentRequest.getPaymentStatus())) {
            return;
        }

        log.info("Received payment cancel event for order id: {}", paymentRequest.getOrderId());
        
        paymentService.persistPayment(paymentRequest);
    }

    
}
