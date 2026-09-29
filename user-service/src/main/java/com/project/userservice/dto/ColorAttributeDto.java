package com.project.userservice.dto;


import jakarta.persistence.Column;
import jakarta.persistence.Lob;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;

@Builder
@Data
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor

public class ColorAttributeDto {

    private Long id;
    private String color;     
    
    private String colorImage;
}
