package com.project.cart_service.message.consumer;


import org.springframework.stereotype.Service;

import com.project.cart_service.model.Cart;
import com.project.cart_service.service.CartService;
import com.project.common.message.dto.request.CartRollbackRequest;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class CartRollbackMessageListener {

    private final CartService cartService;

    @Transactional
    public void cartRollback(CartRollbackRequest request) {

                
        Cart cart = cartService.cartRollback(request);       

        if (cart == null) {
            log.error("Cart could not be rollbacked in cart database with id: {}", request.getUserId());
            //throw new OrderDomainException("Customer could not be created in order database with id " +
            //createdUser.getUserId());
        }
        log.info("Cart is rollbacked in cart database with id: {}", cart.getCartId());
        
    }

}
