package com.project.userservice.security.response;

import java.util.List;

import com.project.userservice.dto.ReviewDto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ReviewsResponse {

    private List<ReviewDto> reviews;
    private int num_reviews;

}
