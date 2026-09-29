package com.project.catalog_service.dto.data;

import lombok.Data;

@Data
public class CategoryCsvDto {
    private Long category_id;
    private String category_name;
    private String slug;
    private String created_at;
}
