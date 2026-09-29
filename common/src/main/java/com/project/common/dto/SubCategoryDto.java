package com.project.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.AccessLevel;

@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class SubCategoryDto {
    private String id;
    private CategoryDto parent;
    private String name;
    private String slug;
}
