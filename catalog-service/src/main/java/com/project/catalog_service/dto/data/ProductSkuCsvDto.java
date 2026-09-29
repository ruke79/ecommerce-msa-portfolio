package com.project.catalog_service.dto.data;

import lombok.Data;

@Data
public class ProductSkuCsvDto {
    private Long skuproduct_id;
    private String sku;
    private String images;
    private Integer discount;
    private Integer sold;
    private Long color_id;
    private Long product_id;
    private String created_at;
}