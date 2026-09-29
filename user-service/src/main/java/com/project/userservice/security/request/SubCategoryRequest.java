package com.project.userservice.security.request;


import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
@AllArgsConstructor
public class SubCategoryRequest {

    private String subcategoryName;
    private String parent;
    private String slug;

}
