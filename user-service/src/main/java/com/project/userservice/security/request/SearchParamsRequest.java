package com.project.userservice.security.request;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SearchParamsRequest {

    private String search;

    private String category;

    private String style;

    private String size;

    private String color;

    private String brand;

    private String material;

    private String gender;

    private Integer lowPrice;

    private Integer highPrice;

    private Integer shipping = 0;

    private Integer rating;

    private int page;

    private int pageSize;



}
