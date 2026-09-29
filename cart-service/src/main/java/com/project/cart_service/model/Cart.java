package com.project.cart_service.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.AccessLevel;

@Getter
@Setter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name="cart", schema = "cart",
       indexes = {
        @Index(columnList = "user_id", name = "idx_cart_user") }
)
public class Cart extends BaseEntity {

    @Id @Tsid
    //@GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name="cart_id")
    private Long cartId;

    @JsonManagedReference
    @OneToMany(mappedBy = "cart", fetch = FetchType.LAZY,
    cascade = CascadeType.PERSIST,targetEntity = CartProduct.class, orphanRemoval = true)
    private List<CartProduct> cartProducts = new ArrayList<>();


    private BigDecimal cartTotal;
    private BigDecimal totalAfterDiscount;

    // @OneToOne(fetch=FetchType.cade=CascadeType.PERSIST, targetEntity=User.class)
    // @JoinColumn(name="user_id", referencedColumnName = "user_id", nullable = false)
    // private User user;
    private Long userId;

    public Cart(Long userId) {
        this.userId = userId;
    }
}
