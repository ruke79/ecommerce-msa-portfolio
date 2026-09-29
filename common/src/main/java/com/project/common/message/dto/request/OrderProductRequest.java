package com.project.common.message.dto.request;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;

import java.io.Serializable;

import com.project.common.constants.OrderStatus;


@Getter
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderProductRequest implements Serializable  {
        
    private Long productId;
    private int qty;
    private String size;
    private Long colorId;    
    
    
}
