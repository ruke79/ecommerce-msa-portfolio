package com.project.delivery_service.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.project.common.outbox.OutboxRelayTrigger;

/**
 * 커밋 직후 릴레이를 깨운다 — 10초 주기를 기다리지 않는다(common {@link OutboxRelayTrigger}, 다른 아웃박스와 같은 장치).
 * [JA] コミット直後にリレーを起こす — 10 秒の周期を待たない。
 */
@Configuration
public class DeliveryOutboxConfig {

    @Bean(destroyMethod = "close")
    OutboxRelayTrigger deliveryOutboxTrigger(DeliveryOutboxRelay relay,
            @Value("${delivery.outbox.trigger-enabled:true}") boolean enabled) {
        return new OutboxRelayTrigger("delivery-outbox", relay::relayOnce, enabled);
    }
}
