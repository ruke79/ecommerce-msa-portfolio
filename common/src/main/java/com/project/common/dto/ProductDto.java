package com.project.common.dto;


import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;



import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ProductDto  {
    
        
    private String id;

    private String name;

    private String description;

    private String brand;

    private String slug;

    private CategoryDto category;

    private List<SubCategoryDto> subCategories;

    private List<ProductDetailsDto> details;   
        
    private List<ProductQADto> questions;
    
    private List<ProductSkuDto> skus;

    private String refundPolicy;

    /** ACTIVE · SOLD_OUT · DELETED — 관리자 화면의 배지·품절 토글이 읽는다(V8). */
    private String status;

    private float rating;

    //private int num_reviews;

    private String shipping;    

    private String createdAt;

}
