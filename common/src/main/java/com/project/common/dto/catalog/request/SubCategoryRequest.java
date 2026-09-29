package com.project.common.dto.catalog.request;


import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Setter
@Getter
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED) 
public class SubCategoryRequest {

    private String subcategoryName;
    private String parent;
    private String slug;

}
