package com.project.userservice.model;

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
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

//@Data
@Getter
@Setter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class BaseEntity {

    @Version
    @Column(name = "version")
    private Long version;
    
    @CreatedDate
    @Column(updatable=false)
    private LocalDateTime createdAt;
    @CreatedBy
    @Column(updatable = false)
    private String createdBy;
    @LastModifiedDate
    @Column(insertable=false)
    private LocalDateTime updatedAt;
    @LastModifiedBy
    @Column(insertable=false)
    private String updatedBy;

}