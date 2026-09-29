package com.project.catalog_service.model;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@MappedSuperclass
@SuperBuilder // @Builder 대신 @SuperBuilder 사용
@NoArgsConstructor
@AllArgsConstructor
public class BaseEntity {

    @Version
    @Column(name = "version")
    private Long version;    

    @CreatedDate
    @Column(updatable=false)
    @Builder.Default 
    private LocalDateTime createdAt = LocalDateTime.now();
    @CreatedBy
    @Column(updatable = false)
    @Builder.Default
    private String createdBy = "Admin";
    @LastModifiedDate    
    private LocalDateTime updatedAt;
    @LastModifiedBy    
    private String updatedBy;

}