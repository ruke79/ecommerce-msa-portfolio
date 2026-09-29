package com.project.common.dto;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Locale.Category;

import lombok.AccessLevel;

@Builder
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class CategoryDto {    
    private String id;
    private String name;
    private String slug;
     
}
