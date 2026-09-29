package com.project.common.dto.order;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED) 
@AllArgsConstructor
public class OrderAddressDto {

    private String id;

    private String firstname;

    private String lastname;

    private String address1;

    private String address2;

    private String city;

    private String state;

    private String zipCode;
    
    private String country;

    private String phoneNumber;

    private boolean active;

}
