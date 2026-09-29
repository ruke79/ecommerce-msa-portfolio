package com.project.cart_service.message.consumer;


import org.springframework.stereotype.Service;

import com.project.cart_service.service.CartService;
import com.project.common.message.dto.request.CartEmptyRequest;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class CartEmptyMessageListener {

    private final CartService cartService;

    @Transactional
    public void cartEmpty(CartEmptyRequest request) {

                
        cartService.cartEmpty(request);       
        
    }

}
