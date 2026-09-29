package com.project.catalog_service.dto.data;

import lombok.Data;

@Data
public class ProductDetailsCsvDto {
    private Long pdetail_id;
    private String name;
    private String value;
    private Long product_id;
    private String created_at;
}
