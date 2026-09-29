package com.project.common.dto.catalog.request;


import java.util.List;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED) 
public class ProductInfosLoadRequest {

    List<ProductAddRequest> products;
}
