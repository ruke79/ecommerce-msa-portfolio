package com.project.common.dto.catalog.request;


import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.Set;

import com.project.common.dto.ProductColorDto;
import com.project.common.dto.ProductDetailsDto;
import com.project.common.dto.ProductQADto;
import com.project.common.dto.ProductSizeDto;



@Setter
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) 
public class ProductRequest {

    private String name;

    private String description;

    private String brand;

    private String sku;

    private List<String> images;

    private String discount;

    private String slug;

    private String parent;

    private String category;

    private List<String> subCategories;

    private ProductColorDto color;

    private List<ProductSizeDto> sizes;

    private Set<ProductDetailsDto> details;

    private Set<ProductQADto> questions;

    private String shippingFee;  

}
