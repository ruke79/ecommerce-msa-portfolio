package com.project.order_service.ports.output.message.publisher;

import com.project.common.outbox.OutboxStatus;
import com.project.order_service.outbox.model.PaymentOutboxEvent;

import java.util.function.BiConsumer;

public interface TokenRequestMessagePublisher {

    /**
     * @return 상태가 확정되었으면 true, 일시적 실패라 다음 폴링에서 재시도해야 하면 false.
     *         (PaymentRequestKafkaPublisher 의 주석에 이유가 적혀 있다.)
     */
    boolean publish(PaymentOutboxEvent outboxMessage, BiConsumer<PaymentOutboxEvent, OutboxStatus> outboxCallback);
}
