package com.project.catalog_service.dto.data;

import lombok.Data;

@Data
public class ProductSizeCsvDto {
    private Long size_id;
    private String size;
    private Integer quantity;
    private Float price;
    private Long skuproduct_id;
}