package com.project.cart_service.model;

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
import lombok.Data;

@Data
@MappedSuperclass
public class BaseEntity {

    @Version
    @Column(name = "version")
    private Long version;

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