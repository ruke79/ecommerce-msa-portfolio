package com.project.cart_service.model;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonBackReference;

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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.AccessLevel;

@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Getter
@Setter
@Entity
@Table(name="cart_product", schema = "cart",              
      indexes = {
        @Index(columnList = "_uid, name, size, style", name = "idx_cart_product") }
      )
public class CartProduct extends BaseEntity {

    @Id @Tsid
    //@GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "cartproduct_id")
    private Long cartproductId;

    private String _uid;

    private String name;

    private String image;

    private String size;

    /**
     * 이 줄의 SKU — 사이즈 한 줄의 재고 단위 코드(catalog V17). 식별자가 아니라 기록이다: 줄은 여전히
     * (상품, 색상, 사이즈 이름)으로 찾는다. 담을 때 catalog 가 준 값을 적고, 주문 품목이 그대로 가져간다(V4).
     */
    @Column(name = "sku_code", length = 96)
    private String skuCode;

    private int style;

    private int qty;

    // @ManyToOne(fetch=FetchType.LAZY)
    //     @JoinColumn(name="product_id", referencedColumnName = "product_id", nullable=false)
    //     private Product product;
    private Long productId;

    //     @ManyToOne(fetch=FetchType.LAZY)
    //     @JoinColumn(name="color_id", referencedColumnName = "color_id", nullable=false)
    //     private ProductColorAttribute color;
    private Long colorId;

    @JsonBackReference
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cart_id", nullable = true)
    private Cart cart;

    private BigDecimal price;    

    private BigDecimal shipping;    

    /**
     * 체크아웃에 고른 줄인가 — 주문(/checkout)은 고른 줄로만 만든다. 체크아웃이 줄을 지우지 않고 이 표시만 바꾼다(V3).
     */
    @Builder.Default
    @Column(nullable = false)
    private boolean selected = true;
    
}
