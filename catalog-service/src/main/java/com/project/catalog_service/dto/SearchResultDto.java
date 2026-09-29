package com.project.catalog_service.dto;

import java.util.List;
import java.util.Set;

import org.springframework.data.domain.Page;

import com.project.common.dto.CategoryDto;
import com.project.common.dto.ProductDetailsDto;
import com.project.common.dto.ProductDto;
import com.project.common.dto.SubCategoryDto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.AccessLevel;

@ToString
@Builder(toBuilder = true)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class SearchResultDto {

    //private Set<ProductDto> products;
    private Page<ProductDto> product;

    private List<CategoryDto> categories;

    private List<SubCategoryDto> subCategories;

    private List<String> colors;

    private List<String> brandsDB;

    private List<String> sizes;

    private List<ProductDetailsDto> details;


    private int totalProducts;


    

}
