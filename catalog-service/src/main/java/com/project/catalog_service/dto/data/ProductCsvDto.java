package com.project.catalog_service.dto.data;

import lombok.Data;

@Data
public class ProductCsvDto {
    private Long product_id;
    private String name;
    private String description;
    private String brand;
    private String slug;
    private Long category_id;
    private String refund_policy;
    private Float rating;
    private String shipping;
    private String created_at;
}
