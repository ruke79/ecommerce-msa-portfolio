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
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

// 🐛 예전 @Data — equals/hashCode 가 버전 · 생성/수정 시각만으로 만들어졌다. Product.details · questions 는 HashSet 이라,
//    같은 순간에 만든 행(한 SQL 로 적재한 시드, 반복문에서 연달아 만든 새 상세)이 하나로 합쳐졌고, 서로 다른 엔티티 종류끼리도
//    '같다' 고 나왔다. 엔티티는 동일성(==)으로 비교한다(ProductSku 처럼 id 로 비교하는 것은 그 클래스가 직접 정한다).
// [JA] 以前は @Data — equals/hashCode がバージョン · 作成/更新時刻だけで作られ、HashSet の詳細 · Q&A が一つにまとまっていた。
@Getter
@Setter
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