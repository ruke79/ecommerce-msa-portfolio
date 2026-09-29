package com.project.catalog_service.model;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnore;

import io.hypersistence.utils.hibernate.id.Tsid;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Getter
@Setter
@Entity
@SuperBuilder // @Builder 대신 @SuperBuilder 사용
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name="product_qa" ,schema="product")
public class ProductQA extends BaseEntity {

    

    @Id //@Tsid    
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "qa_id")
    private Long  qaId;
    
    private String question;

    private String answer;

    @JsonBackReference // Product로의 순환 참조를 끊습니다.
    @ManyToOne(fetch=FetchType.LAZY)
    @JoinColumn(name="product_id", referencedColumnName = "product_id", nullable=false)
    private Product product;
}
