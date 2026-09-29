package com.project.userservice.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.AccessLevel;

@Builder
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class CartDto {

    private String userId;
    private String userImage;
    private List<AddressDto> address;


    private List<CartProductDto> products;

    private int cartTotal;
    private int totalAfterDiscount;

}
