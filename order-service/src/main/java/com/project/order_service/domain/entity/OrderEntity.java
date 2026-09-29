package com.project.order_service.domain.entity;

import java.util.UUID;

import com.project.common.constants.OrderStatus;
import com.project.common.domain.entity.AggregateRoot;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class OrderEntity extends AggregateRoot<UUID> {

    private UUID custmerId;
    private OrderStatus orderStatus;
    
    public void init() {
        setId(UUID.randomUUID());
        orderStatus = OrderStatus.NOT_PROCESSED;
    }

}
