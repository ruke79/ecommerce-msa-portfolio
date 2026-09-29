package com.project.common.dto.catalog.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.AccessLevel;

@Setter
@Getter
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CategoryResponse {
    private String id;
    private String name;   
    //private String slug; 
}
