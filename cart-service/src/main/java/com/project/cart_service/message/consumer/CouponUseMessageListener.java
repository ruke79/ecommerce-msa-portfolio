package com.project.cart_service.message.consumer;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import com.project.cart_service.model.Cart;
import com.project.cart_service.service.CartService;

import com.project.common.message.dto.request.CouponUseRequest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class CouponUseMessageListener {

    private final CartService cartService;

    
    void couponUsed(CouponUseRequest request) {


        log.info("Coupon : {}", request.toString());

        Cart cart = cartService.couponUsed(request);
         
        if (cart == null) {
            log.error("Coupon could not be created in order database with id: {}", request.getUserId());
            //throw new OrderDomainException("Customer could not be created in order database with id " +
            //createdUser.getUserId());
        }
        log.info("Cart is created in cart database with id: {}", cart.getCartId());


    }
}
