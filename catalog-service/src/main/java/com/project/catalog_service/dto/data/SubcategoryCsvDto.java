package com.project.catalog_service.dto.data;

import lombok.Data;

@Data
public class SubcategoryCsvDto {
    private Long subcategory_id;
    private String subcategory_name;
    private String slug;
    private Long category_id;
    private String created_at;
}
