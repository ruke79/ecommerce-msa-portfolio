package com.project.userservice.security.request;

import java.util.List;

import com.project.userservice.dto.ReviewStyleDto;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class ReviewRequest {

    
    private String productId;
    
    private String size;
    private ReviewStyleDto style;
    private String fit;
    private float rating;
    private String review;
    private List<String> images;

    

}
