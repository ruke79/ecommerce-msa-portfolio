package com.project.userservice.dto;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;

@Data
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class SizeAttributeDto {

    private Long id;

    private String size;

    private int quantity;

    private int price;

}
