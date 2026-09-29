package com.project.catalog_service.model;

import java.util.List;

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
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Entity
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name="product_subcategory",  schema="product",
       indexes = {
        @Index(columnList = "subcategory_id, product_id", name = "idx_product_subcategory") })
public class ProductSubcategory {
    
    @Version
    @Column(name = "version")
    private Long version;     

    @Id //@Tsid
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "proc_subcategory_id")
    private Long id;

    @JsonBackReference // Product로의 순환 참조를 끊습니다.
    @ManyToOne
    @JoinColumn(name="product_id")
    private Product product;

    @JsonBackReference // Subcategory로의 순환 참조를 끊습니다.
    @ManyToOne
    @JoinColumn(name="subcategory_id")
    private Subcategory subcategory;
}
