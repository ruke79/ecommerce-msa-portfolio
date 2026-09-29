package com.project.catalog_service.dto.data;

import lombok.Data;

@Data
public class ProductSubcategoryCsvDto {
    private Long id;
    private Long product_id;
    private Long subcategory_id;
}
