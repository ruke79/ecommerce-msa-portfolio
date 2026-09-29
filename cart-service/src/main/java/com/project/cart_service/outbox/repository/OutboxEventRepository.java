package com.project.cart_service.outbox.repository;

import org.springframework.data.repository.CrudRepository;

import com.project.cart_service.outbox.model.OutboxEvent;



public interface OutboxEventRepository extends CrudRepository<OutboxEvent, String> {


}
