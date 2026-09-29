package com.project.common.dto.catalog.request;

import java.util.List;

import com.project.common.dto.ProductInfoDto;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Setter
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) 
public class ProductInfoRequest {

    private List<ProductInfoDto> products;
}
