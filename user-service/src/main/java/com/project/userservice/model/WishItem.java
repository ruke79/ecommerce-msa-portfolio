package com.project.userservice.model;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnore;

import io.hypersistence.utils.hibernate.id.Tsid;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import lombok.AccessLevel;

@Getter
@Setter
@Entity
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name="wishlist", schema="users",
       indexes = {
        @Index(columnList = "style, user_id, product_id", name = "idx_wishItem") }       
)
public class WishItem extends BaseEntity{
    
    
    @Id @Tsid
    @Column(name="wishItem_id")
    private Long wishItemId;

    private String style;

    
      // 한 명의 사용자는 여러 위시리스트 항목을 가질 수 있으므로 @ManyToOne 관계가 적절합니다.
    @ManyToOne(fetch=FetchType.LAZY)
    @JoinColumn(name="user_id", referencedColumnName = "user_id", nullable = false)
    @JsonBackReference
    private User user;

    
    // @OneToOne(fetch=FetchType.LAZY, cascade=CascadeType.PERSIST, targetEntity=Product.class)
    // @JoinColumn(name="product_id", referencedColumnName = "product_id", nullable = false)
    // private Product product;
    private Long productId;

    public WishItem(String style, Long productId) {
        this.style = style;
        this.productId = productId;
    }
}