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

    // 🐛 두 연관 모두 JPA 기본값(EAGER)이었다. 상품을 하나 읽어도 연결 행마다 상품 · 하위 카테고리를 함께 끌어왔고, JPQL 로 연결 행을
    //    읽는 곳(하위 카테고리 → 상품 목록)에서는 행마다 추가 SELECT 가 나갔다. 연결 행은 대개 상품 쪽 컬렉션으로 읽히므로 상품은 이미
    //    영속성 컨텍스트에 있고, 하위 카테고리는 Subcategory 의 @BatchSize 로 한 번에 채운다(ProductQueryCountTest).
    // [JA] 両方 JPA の既定(EAGER)だった。LAZY にし、下位カテゴリは Subcategory の @BatchSize でまとめて読む。
    @JsonBackReference // Product로의 순환 참조를 끊습니다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name="product_id")
    private Product product;

    @JsonBackReference // Subcategory로의 순환 참조를 끊습니다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name="subcategory_id")
    private Subcategory subcategory;
}
