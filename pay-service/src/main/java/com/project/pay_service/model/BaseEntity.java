package com.project.pay_service.model;

import java.time.LocalDateTime;

import org.checkerframework.checker.units.qual.A;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;


import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@MappedSuperclass
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class BaseEntity {

    @Version
    @Column(name = "version")
    private Long version;

    @Builder.Default
    @CreatedDate
    @Column(updatable=false)
    private LocalDateTime createdAt = LocalDateTime.now();
    @CreatedBy
    @Column(updatable = false)
    private String createdBy;
    @LastModifiedDate    
    private LocalDateTime updatedAt;
    @LastModifiedBy    
    private String updatedBy;

}