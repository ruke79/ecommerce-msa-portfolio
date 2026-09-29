package com.project.common.message.dto.response;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Setter;

import java.math.BigDecimal;

import com.project.common.constants.PaymentStatus;

import java.io.Serializable;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentResponse implements Serializable {
    
    private Long  paymentId;
    private Long  orderId;    
    private Long  customerId;
    private String couponName;
    private BigDecimal amounts;
    private PaymentStatus paymentStatus;    

}
