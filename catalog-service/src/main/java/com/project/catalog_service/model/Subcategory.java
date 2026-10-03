package com.project.catalog_service.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnore;
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
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToMany;
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
import lombok.experimental.SuperBuilder;

@Getter
@Setter
@Entity
@SuperBuilder // @Builder 대신 @SuperBuilder 사용
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name="subcategory",  schema="product",
       indexes = {
        @Index(columnList = "subcategory_name, slug", name = "idx_subcategory") })
public class Subcategory extends BaseEntity {

    
    @Id //@Tsid
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "subcategory_id")
    private Long subcategoryId;    

    
    @Column(name = "subcategory_name")
    private String subcategoryName;

    private String slug;

    
    @JsonBackReference // Category로의 순환 참조를 끊습니다.
    @ManyToOne(fetch=FetchType.LAZY)
    @JoinColumn(name="category_id", referencedColumnName = "category_id", nullable=false)
    private Category category;    

    // @JsonIgnore
    // @OneToMany(mappedBy = "subCategories", fetch= FetchType.LAZY, cascade = CascadeType.PERSIST)    
    // private List<Product> products = new ArrayList<>();

    @Builder.Default 
    @JsonManagedReference // ProductSubcategory로의 순환 참조를 관리합니다.
    @OneToMany(mappedBy = "subcategory", fetch= FetchType.LAZY, cascade = CascadeType.PERSIST)        
    private List<ProductSubcategory> products = new ArrayList<>();

    // this() 로 시작한다 — @Builder.Default 의 컬렉션 기본값은 손으로 쓴 생성자에 남지 않는다(EntityLombokGuardTest).
    public Subcategory(String name, String slug, Category category) {
        this();
        this.subcategoryName = name;
        this.slug = slug;
        this.category = category;
    }

}
