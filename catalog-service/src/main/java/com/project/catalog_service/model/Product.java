package com.project.catalog_service.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.hibernate.annotations.BatchSize;

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
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
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


@Entity
@Getter
@Setter
@SuperBuilder // @Builder 대신 @SuperBuilder 사용
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name="product",  schema="product",
       indexes = {
        @Index(columnList = "name, brand, slug, category_id", name = "idx_product") })
// 삭제된 상품은 **모든 JPA 조회에서** 자동으로 빠진다 — frontpage·페이지네이션·검색·
// slug 상세까지 수십 개의 파생 쿼리를 하나씩 고치는 대신 여기 한 줄로 막는다.
// 대가: DELETED 행은 JPA 로 읽을 수 없어 애플리케이션에서 복구가 불가능하다(의도 —
// V8 마이그레이션 주석 참조. 복구는 SQL 로만 한다).
@org.hibernate.annotations.SQLRestriction("status <> 'DELETED'")
public class Product extends BaseEntity {

    

    @Id //@Tsid        
    @GeneratedValue(strategy = GenerationType.IDENTITY)  
    @Column(name = "product_id")
    private Long productId;

    private String name;

    @Column(length = 1000)
    private String description;

    private String brand;

    private String slug;


    @JsonBackReference // Category로의 순환 참조를 끊습니다.
    @ManyToOne(fetch=FetchType.LAZY)
    @JoinColumn(name="category_id", referencedColumnName = "category_id", nullable=false)
    private Category category;    

    // ⚠️ 배치 페치 — 이 네 컬렉션은 모두 LAZY 이고 ProductMapper.toDto 가 **넷 다** 순회한다.
    // 배치 페치가 없으면 상품 N건 조회가 1 + 4N 쿼리가 된다(20건이면 81번).
    // 예전에는 @BatchSize 가 주석 처리된 채 남아 있었다 — 문제를 알았지만 켜지 않은 흔적이다.
    // ProductQueryCountTest 가 선언과 실제 쿼리 수를 함께 검증한다(docs/19 §2).
    @BatchSize(size = 20)
    @Builder.Default 
    @JsonManagedReference
    @OneToMany(mappedBy="product", fetch = FetchType.LAZY, 
    cascade = CascadeType.PERSIST, targetEntity = ProductSubcategory.class, orphanRemoval = true)    
//     @JoinTable(name="product_subcatgeory",
//     joinColumns =  { @JoinColumn(name="product_id", referencedColumnName = "product_id") },
//     inverseJoinColumns = { @JoinColumn(name="subcategory_id", referencedColumnName = "subcategory_id")})    
    private List<ProductSubcategory> subcategories = new ArrayList<>();   

    @Builder.Default
    @BatchSize(size = 20)
    @JsonManagedReference // ProductDetails로의 순환 참조를 관리합니다.
    @OneToMany(mappedBy="product", fetch = FetchType.LAZY,
    cascade = CascadeType.PERSIST,targetEntity = ProductDetails.class, orphanRemoval = true)
    private Set<ProductDetails> details = new java.util.HashSet<>();

    // 🐛 예전 cascade 가 MERGE 뿐이라, 새 상품을 persist 하면 Q&A 가 저장되지 않고 <b>조용히 빠졌다</b>(details 는 PERSIST 라 저장됐다).
    @Builder.Default
    @BatchSize(size = 20)
    @JsonManagedReference // ProductQA로의 순환 참조를 관리합니다.
    @OneToMany(mappedBy="product", fetch = FetchType.LAZY,
    cascade = {CascadeType.PERSIST, CascadeType.MERGE}, targetEntity = ProductQA.class, orphanRemoval = true)
    private Set<ProductQA> questions = new java.util.HashSet<>();

    @BatchSize(size = 20)
    @JsonManagedReference // ProductColorAttribute로의 순환 참조를 관리합니다.
    @OneToMany(mappedBy="product", fetch = FetchType.LAZY,
            cascade = CascadeType.PERSIST,targetEntity = ProductSku.class, orphanRemoval = true)
    // 순서를 정한다 — 스타일(색상)은 인덱스로 주고받는다(?style=1, 장바구니의 style). 순서 없는 Set 이면 같은 인덱스가 상품
    // 화면(캐시된 DTO)과 장바구니 담기(새로 읽은 엔티티)에서 다른 SKU 를 가리킬 수 있었다. @OrderBy 면 Hibernate 가 순서를
    // 지키는 Set 을 쓴다.
    @jakarta.persistence.OrderBy("skuproductId ASC")
    @Builder.Default
    private Set<ProductSku> skus = new java.util.LinkedHashSet<>();

    /**
     * 상세를 <b>바꿔 넣는다</b> — 컬렉션 객체는 그대로 두고 내용만(orphanRemoval 이 빠진 행을 지운다).
     *
     * <p>🐛 예전: {@code setDetails(new HashSet<>())} 로 컬렉션을 통째로 갈아 끼웠다. 이미 저장된 상품(변형 추가 · 대량 적재의
     * 부모)에서는 Hibernate 가 "orphan deletion 컬렉션이 더 이상 참조되지 않는다" 로 flush 를 거절했다 — 변형 등록이 늘 500 이었다.
     */
    // [JA] 詳細を入れ替える — コレクション自体はそのままで中身だけ。以前はコレクションを丸ごと差し替え、保存済みの商品では flush が拒否されていた。
    public void replaceDetails(java.util.Collection<ProductDetails> next) {
        if (details == null) {
            details = new java.util.HashSet<>();
        }
        details.clear();
        details.addAll(next);
    }

    /** {@link #replaceDetails} 와 같다 — Q&A. */
    public void replaceQuestions(java.util.Collection<ProductQA> next) {
        if (questions == null) {
            questions = new java.util.HashSet<>();
        }
        questions.clear();
        questions.addAll(next);
    }

    @Builder.Default 
    private String refundPolicy = "30 days";

    /**
     * ACTIVE · SOLD_OUT · DELETED (V8). 문자열로 두는 이유: 값 집합이 DB CHECK 와
     * 프론트엔드에도 있어, enum 을 셋이 공유할 방법이 없다 — 한 곳(마이그레이션)을
     * 정본으로 두고 나머지는 문자열을 따른다.
     */
    @Builder.Default
    @Column(length = 20, nullable = false)
    private String status = "ACTIVE";

    @Builder.Default 
    private float rating = 0F;

    //private int num_reviews = 0;

    private BigDecimal shipping;
}
