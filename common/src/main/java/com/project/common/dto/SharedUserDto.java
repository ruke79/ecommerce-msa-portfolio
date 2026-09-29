package com.project.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.AccessLevel;

@Builder
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class SharedUserDto {

    private Long userId;
    private String username; // 실명
    private String nickname; // 별명
    private String email;
    private String image;
}
