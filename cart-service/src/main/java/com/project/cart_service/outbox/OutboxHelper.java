package com.project.cart_service.outbox;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.cart_service.outbox.repository.OutboxEventRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxHelper {

    private final OutboxEventRepository OutboxEventRepository;
    private final ObjectMapper objectMapper;


    
    

}
