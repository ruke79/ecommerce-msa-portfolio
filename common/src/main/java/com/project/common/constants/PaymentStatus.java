package com.project.common.constants;

import java.util.Map;
import java.util.HashMap;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;


@Getter
@RequiredArgsConstructor
public enum PaymentStatus {

    COMPLETED("COMPLETED"),
    CANCELED("CANCELED"),
    FAILED("FAILED");
    
    private String status;
    
    private static class Holder {
        static Map<String, PaymentStatus> MAP = new HashMap<>();
    }

    private PaymentStatus(String status) {
        Holder.MAP.put(status, this);
    }

    public static PaymentStatus getStatus(String val) {
        PaymentStatus status = Holder.MAP.get(val);
        if(status == null) {
            
            throw new  IllegalStateException(String.format("Unsupported status %s.", val)); 
        }

        return status;        
    }

}
