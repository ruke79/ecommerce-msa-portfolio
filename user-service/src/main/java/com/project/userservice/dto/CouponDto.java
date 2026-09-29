package com.project.userservice.dto;

import jakarta.persistence.Column;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.AccessLevel;

@Data
@Builder
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class CouponDto {

    private String id;

    private String name;
     
    private String startDate;
    
    private String endDate;

    private String discount;
    
}
