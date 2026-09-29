package com.project.catalog_service.dto.data;

import lombok.Data;

@Data
public class ProductQACsvDto {
    private Long qa_id;
    private String question;
    private String answer;
    private Long product_id;
    private String created_at;
}