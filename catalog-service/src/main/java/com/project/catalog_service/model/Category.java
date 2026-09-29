package com.project.catalog_service.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.hibernate.annotations.BatchSize;

import com.fasterxml.jackson.annotation.JsonManagedReference;

import io.hypersistence.utils.hibernate.id.Tsid;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Getter
@Setter
@Entity
@SuperBuilder // @Builder 대신 @SuperBuilder 사용
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name="category", schema="product",
       indexes = {
        @Index(columnList = "category_name, slug", name = "idx_category") }
)
public class Category extends BaseEntity {



    @Id //@Tsid
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "category_id")
    private Long categoryId;

    
    @Column(name = "category_name")
    private String categoryName;

    private String slug;

    @Builder.Default 
    @JsonManagedReference // Subcategory로의 순환 참조를 관리합니다.
    @OneToMany(mappedBy="category", fetch = FetchType.LAZY,
    cascade = CascadeType.PERSIST,targetEntity = Subcategory.class)
    private List<Subcategory> subCategories = new ArrayList<>();


    @Builder.Default 
    @BatchSize(size=250)
    @JsonManagedReference // Product로의 순환 참조를 관리합니다.
    @OneToMany(mappedBy="category", fetch = FetchType.LAZY,
            cascade = CascadeType.PERSIST,targetEntity = Product.class)
    private List<Product> products = new ArrayList<>();


    public Category(String name, String slug) {
        this.categoryName = name;
        this.slug = slug;
    }

}
