package com.project.catalog_service.mapper;

import com.project.catalog_service.model.ProductQA;
import com.project.common.dto.ProductQADto;

public class ProductQAMapper {
    public static ProductQADto toDto(ProductQA qa) {
        if (qa == null) {
            return null;
        }
        return ProductQADto.builder()
                .question(qa.getQuestion())
                .answer(qa.getAnswer())
                .build();
    }

    // Dto에서 Entity로 변환하는 메서드 추가
    public static ProductQA toEntity(ProductQADto dto) {
        if (dto == null) {
            return null;
        }
        return ProductQA.builder()
                .question(dto.getQuestion())
                .answer(dto.getAnswer())
                .build();
    }
}