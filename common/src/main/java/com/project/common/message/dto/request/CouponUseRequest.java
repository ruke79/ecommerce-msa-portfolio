package com.project.common.message.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.AccessLevel;
import java.io.Serializable;
import java.math.BigDecimal;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public class CouponUseRequest implements Serializable {

    private Long userId;
    private int discount;    
    private BigDecimal totalAfterDiscount;
    // 사용자 액세스 토큰은 싣지 않는다(docs/54 §10.1).
    
}
