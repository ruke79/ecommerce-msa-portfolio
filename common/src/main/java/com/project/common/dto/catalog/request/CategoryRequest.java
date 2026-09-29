package com.project.common.dto.catalog.request;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Setter
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) 
public class CategoryRequest {
    private String name;
    private String slug;
}
