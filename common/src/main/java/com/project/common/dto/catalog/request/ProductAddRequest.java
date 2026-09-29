package com.project.common.dto.catalog.request;


import java.util.List;
import java.util.Set;

import com.project.common.dto.CategoryDto;
import com.project.common.dto.ProductColorDto;
import com.project.common.dto.ProductDetailsDto;
import com.project.common.dto.ProductQADto;
import com.project.common.dto.ProductSizeDto;
import com.project.common.dto.SubCategoryDto;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED) 
// create or load category , subcateory including creating product
public class ProductAddRequest {

     private String name;

    private String description;

    private String brand;

    private String sku;

    private List<String> images;

    private String discount;

    private String slug;

    private String parent;

    private CategoryDto category;

   private List<SubCategoryDto> subCategories;

  
   private ProductColorDto color;

    private List<ProductSizeDto> sizes;

    private Set<ProductDetailsDto> details;

    private Set<ProductQADto> questions;

    private String shippingFee;  
}
