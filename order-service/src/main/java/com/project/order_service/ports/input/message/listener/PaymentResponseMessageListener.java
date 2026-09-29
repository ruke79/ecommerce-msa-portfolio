package com.project.order_service.ports.input.message.listener;

import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import com.project.common.message.dto.response.PaymentResponse;
import com.project.order_service.saga.OrderSaga;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Validated
@Service
@RequiredArgsConstructor
public class PaymentResponseMessageListener {

    private final OrderSaga orderSaga; 

    void paymentCompleted(PaymentResponse paymentResponse) {

        orderSaga.process(paymentResponse);       
        

    }

    void paymentCancelled(PaymentResponse paymentResponse) {

        orderSaga.rollback(paymentResponse);

    }
}
