package com.project.catalog_service.model;

import java.util.ArrayList;
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
import jakarta.persistence.Lob;
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

@Getter
@Setter
@Entity
@SuperBuilder // @Builder 대신 @SuperBuilder 사용
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name="product_sku",  schema="product",
       indexes = {
        @Index(columnList = "sku, sold", name = "idx_product_sku"),
        // color_id 는 예전에 @OneToOne 이 만들던 UNIQUE 제약이 인덱스 역할을 겸하고 있었다.
        // @ManyToOne 으로 바로잡으면 그 제약이 사라지므로 인덱스를 명시해야 한다 — PostgreSQL 은
        // FK 컬럼에 인덱스를 자동으로 만들지 않는다. 신규 스키마는 여기서, 기존 스키마는 V4 에서.
        @Index(columnList = "color_id", name = "idx_product_sku_color_id") })
public class ProductSku extends BaseEntity {

    

    @Id //@Tsid
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "skuproduct_id")
    private Long skuproductId;

    private String sku;

    
    @Column(length = 256)
    private List<String> images;
    
    //@Column(length = 20000)
    //private List<String> descriptionImages;

    @Builder.Default 
    private int discount = 0;

    @Builder.Default 
    private int sold = 0;

    @JsonManagedReference // ProductSize로의 순환 참조를 관리합니다.        
    @OneToMany(mappedBy="sku", fetch = FetchType.LAZY,
            cascade = CascadeType.PERSIST,targetEntity = ProductSize.class)
    // ES 색인 쿼리(ElasticsearchBatchConfig.productReader)가 bag 두 개를 동시에 JOIN FETCH 할 수
    // 없어 이 컬렉션은 패치 대상에서 뺐다. N+1 을 피하려면 배치 로딩이 필요하다(docs/16 B-26).
    @org.hibernate.annotations.BatchSize(size = 100)
    // 순서를 정한다 — 화면·장바구니·catalog 가 사이즈를 **인덱스**로 주고받는다(?size=1). 순서가 없으면 DB 가 돌려주는 대로라
    // 같은 인덱스가 경로마다 다른 행을 가리킬 수 있다. 같은 이름의 사이즈가 여럿이면(사이즈 없는 상품의 빈 이름) "첫 행" 도
    // 이 순서다(ProductSizeRepository.findFirst…).
    @jakarta.persistence.OrderBy("sizeId ASC")
    @Builder.Default
    private List<ProductSize> sizes = new ArrayList<>();

    // ⚠️ 예전에는 @OneToOne 이었다. JPA 규약상 @OneToOne 의 소유측 FK 에는 **UNIQUE 제약**이
    // 붙으므로, 색상 행 하나를 오직 한 SKU 만 참조할 수 있게 된다. 그 결과:
    //   · 카탈로그 전체의 SKU 수가 **색상 행 수로 상한**이 걸린다(시드는 509=509 로 이미 포화).
    //   · 색상 필터 패싯이 무의미해진다 — 색상 1개당 상품이 정확히 1개뿐이다.
    // 이 저장소의 코드는 일관되게 **공유 어휘**를 전제하고 있었다:
    //   · ProductSkuRepository.findColorsByProductId 가 select DISTINCT b.color (공유 전제)
    //   · ProductColorRepository.findByColor → Optional (색상명이 행을 식별)
    //   · frontend/ColorsFilter.jsx 가 색상 필터 패싯
    //   · ProductColor 안에 @OneToMany(mappedBy="color") 가 주석으로 남아 있다 — 원저자도
    //     "여러 주문이 한 색상을 참조"하는 모델을 의도했다는 흔적이다.
    // 따라서 설계 판단이 아니라 **매핑 오류**로 보고 @ManyToOne 으로 바로잡는다(docs/19 §5.1).
    // 기존 데이터는 색상값이 전부 고유해 제약 해제만으로 안전하게 이전된다(V4 마이그레이션).
    @ManyToOne(fetch=FetchType.LAZY, cascade=CascadeType.PERSIST, targetEntity=ProductColor.class)
    @JoinColumn(name="color_id", referencedColumnName = "color_id", nullable = true)
    private ProductColor color;


    @JsonBackReference // Product로의 순환 참조를 끊습니다.
    @ManyToOne(fetch=FetchType.LAZY)
    @JoinColumn(name="product_id", referencedColumnName = "product_id", nullable=false)
    private Product product;

    /**
     * 같음은 <b>id</b> 로만 — 이 엔티티는 {@code Product.skus}(Set) 의 원소다.
     *
     * <p>🐛 예전에는 선언이 없어 {@link BaseEntity} 의 {@code @Data} 가 만든 equals/hashCode(감사 필드·version)를 물려받았다.
     * 재고가 바뀌면 version·updatedAt 이 바뀌어 hashCode 가 달라지고, HashSet 의 순서가 로드마다 달라졌다 — 그런데 화면과
     * 장바구니는 SKU 를 <b>인덱스</b>(?style=1)로 주고받는다. 감사 필드가 같은 두 SKU 는 아예 하나로 합쳐질 수도 있었다.
     * hashCode 는 상수다 — 저장 전(id 없음)과 후에 바뀌면 Set 이 원소를 잃는다.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ProductSku other)) {
            return false;
        }
        return skuproductId != null && skuproductId.equals(other.getSkuproductId());
    }

    @Override
    public int hashCode() {
        return ProductSku.class.hashCode();
    }

}
