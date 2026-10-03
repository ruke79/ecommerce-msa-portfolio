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
import lombok.Getter;
import lombok.Setter;

// 🐛 예전 @Data — equals/hashCode 가 버전 · 생성/수정 시각만으로 만들어져, 다른 행 둘이 "같은" 엔티티가 되고(장바구니에서 고르지
//    않은 줄이 선택된 것으로 잡힐 수 있었다) 버전이 오르면 hashCode 가 바뀌었다. 엔티티는 동일성(==)으로 비교한다.
// [JA] 以前は @Data — equals/hashCode がバージョン · 作成/更新時刻だけで作られ、別の行が「同じ」になり、バージョンが上がると hashCode が変わった。
@Getter
@Setter
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